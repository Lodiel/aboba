package com.aboba.starlinkreconnect.grpc;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.net.SocketFactory;

/**
 * A deliberately tiny HTTP/2 (RFC 7540) client that can perform one plaintext ("h2c") gRPC
 * unary call per connection. No TLS, no dependencies. It handles exactly what a gRPC server
 * sends for a unary call: SETTINGS, PING, WINDOW_UPDATE, HEADERS (+CONTINUATION), DATA,
 * trailers, RST_STREAM and GOAWAY.
 */
public final class Http2GrpcClient {
    private static final byte[] PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    private static final int TYPE_DATA = 0x0;
    private static final int TYPE_HEADERS = 0x1;
    private static final int TYPE_RST_STREAM = 0x3;
    private static final int TYPE_SETTINGS = 0x4;
    private static final int TYPE_PING = 0x6;
    private static final int TYPE_GOAWAY = 0x7;
    private static final int TYPE_WINDOW_UPDATE = 0x8;
    private static final int TYPE_CONTINUATION = 0x9;

    private static final int FLAG_END_STREAM = 0x1;
    private static final int FLAG_ACK = 0x1;
    private static final int FLAG_END_HEADERS = 0x4;
    private static final int FLAG_PADDED = 0x8;
    private static final int FLAG_PRIORITY = 0x20;

    private static final int SETTINGS_HEADER_TABLE_SIZE = 0x1;
    private static final int SETTINGS_ENABLE_PUSH = 0x2;
    private static final int SETTINGS_MAX_FRAME_SIZE = 0x5;

    private static final int STREAM_ID = 1;
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final int LOCAL_HEADER_TABLE_SIZE = 4096;

    /** Result of a unary call. */
    public static final class Response {
        public int httpStatus = -1;
        /** grpc-status from the trailers, or -1 when absent. */
        public int grpcStatus = -1;
        public String grpcMessage;
        /** Raw bytes of the first gRPC message in the response, or null when none arrived. */
        public byte[] message;
        public boolean messageCompressed;
        public final List<String[]> headers = new ArrayList<>();
        public final List<String[]> trailers = new ArrayList<>();

        public boolean isOk() {
            return grpcStatus == 0 || (grpcStatus == -1 && httpStatus == 200 && message != null);
        }

        @Override
        public String toString() {
            return "http=" + httpStatus + " grpc-status=" + grpcStatus
                    + (grpcMessage != null ? " (" + grpcMessage + ")" : "")
                    + " message=" + (message == null ? "none" : message.length + " bytes");
        }
    }

    private Http2GrpcClient() {}

    /**
     * Performs a unary gRPC call.
     *
     * @param factory   socket factory (use the Android Network's factory to pin the Wi-Fi link);
     *                  null for the default
     * @param path      e.g. "/SpaceX.API.Device.Device/Handle"
     * @param body      serialized protobuf request
     * @param timeoutMs overall deadline for connect + response
     */
    public static Response unaryCall(SocketFactory factory, String host, int port, String path,
                                     byte[] body, int timeoutMs) throws IOException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        SocketFactory sf = factory != null ? factory : SocketFactory.getDefault();
        try (Socket socket = sf.createSocket()) {
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(Math.max(1, timeoutMs));
            OutputStream out = socket.getOutputStream();
            DataInputStream in = new DataInputStream(socket.getInputStream());

            // Client preface + our SETTINGS.
            ByteArrayOutputStream settings = new ByteArrayOutputStream();
            writeSetting(settings, SETTINGS_ENABLE_PUSH, 0);
            writeSetting(settings, SETTINGS_HEADER_TABLE_SIZE, LOCAL_HEADER_TABLE_SIZE);
            ByteArrayOutputStream initial = new ByteArrayOutputStream();
            initial.write(PREFACE);
            writeFrame(initial, TYPE_SETTINGS, 0, 0, settings.toByteArray());

            // HEADERS for stream 1 (no END_STREAM: the body follows in DATA).
            ByteArrayOutputStream block = new ByteArrayOutputStream();
            Hpack.encodeLiteral(block, ":method", "POST");
            Hpack.encodeLiteral(block, ":scheme", "http");
            Hpack.encodeLiteral(block, ":path", path);
            Hpack.encodeLiteral(block, ":authority", host + ":" + port);
            Hpack.encodeLiteral(block, "content-type", "application/grpc");
            Hpack.encodeLiteral(block, "te", "trailers");
            Hpack.encodeLiteral(block, "user-agent", "starlink-reconnect/1.0 (minimal-h2c)");
            Hpack.encodeLiteral(block, "grpc-timeout", Math.max(1, timeoutMs) + "m");
            writeFrame(initial, TYPE_HEADERS, FLAG_END_HEADERS, STREAM_ID, block.toByteArray());

            // DATA: gRPC length-prefixed message, END_STREAM.
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            data.write(0); // not compressed
            data.write((body.length >>> 24) & 0xFF);
            data.write((body.length >>> 16) & 0xFF);
            data.write((body.length >>> 8) & 0xFF);
            data.write(body.length & 0xFF);
            data.write(body);
            writeFrame(initial, TYPE_DATA, FLAG_END_STREAM, STREAM_ID, data.toByteArray());

            out.write(initial.toByteArray());
            out.flush();

            return readResponse(in, out, deadline);
        }
    }

    private static Response readResponse(DataInputStream in, OutputStream out, long deadline)
            throws IOException {
        Response resp = new Response();
        Hpack.Decoder decoder = new Hpack.Decoder(LOCAL_HEADER_TABLE_SIZE);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int peerMaxFrameSize = 16384;
        int receivedSinceWindowUpdate = 0;
        boolean gotHeaders = false;
        byte[] header = new byte[9];

        while (true) {
            long remainingMs = (deadline - System.nanoTime()) / 1_000_000L;
            if (remainingMs <= 0) throw new SocketTimeoutException("gRPC call deadline exceeded");
            try {
                in.readFully(header);
            } catch (EOFException e) {
                throw new IOException("connection closed before the response completed", e);
            }
            int length = ((header[0] & 0xFF) << 16) | ((header[1] & 0xFF) << 8) | (header[2] & 0xFF);
            int type = header[3] & 0xFF;
            int flags = header[4] & 0xFF;
            int streamId = ((header[5] & 0x7F) << 24) | ((header[6] & 0xFF) << 16)
                    | ((header[7] & 0xFF) << 8) | (header[8] & 0xFF);
            if (length > Math.max(peerMaxFrameSize, 16384) + 1024 || length > MAX_RESPONSE_BYTES) {
                throw new IOException("HTTP/2 frame too large: " + length);
            }
            byte[] payload = new byte[length];
            in.readFully(payload);

            switch (type) {
                case TYPE_SETTINGS:
                    if ((flags & FLAG_ACK) == 0) {
                        for (int i = 0; i + 6 <= length; i += 6) {
                            int id = ((payload[i] & 0xFF) << 8) | (payload[i + 1] & 0xFF);
                            long value = ((payload[i + 2] & 0xFFL) << 24) | ((payload[i + 3] & 0xFF) << 16)
                                    | ((payload[i + 4] & 0xFF) << 8) | (payload[i + 5] & 0xFF);
                            if (id == SETTINGS_MAX_FRAME_SIZE && value >= 16384 && value <= 16777215) {
                                peerMaxFrameSize = (int) value;
                            }
                        }
                        writeFrame(out, TYPE_SETTINGS, FLAG_ACK, 0, new byte[0]);
                        out.flush();
                    }
                    break;

                case TYPE_PING:
                    if ((flags & FLAG_ACK) == 0) {
                        writeFrame(out, TYPE_PING, FLAG_ACK, 0, payload);
                        out.flush();
                    }
                    break;

                case TYPE_GOAWAY: {
                    int lastStreamId = ((payload[0] & 0x7F) << 24) | ((payload[1] & 0xFF) << 16)
                            | ((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF);
                    long errorCode = ((payload[4] & 0xFFL) << 24) | ((payload[5] & 0xFF) << 16)
                            | ((payload[6] & 0xFF) << 8) | (payload[7] & 0xFF);
                    String debug = length > 8 ? new String(payload, 8, length - 8, StandardCharsets.UTF_8) : "";
                    if (errorCode != 0 || lastStreamId < STREAM_ID) {
                        throw new IOException("server sent GOAWAY (error " + errorCode
                                + ", last stream " + lastStreamId + ") " + debug);
                    }
                    break; // graceful: our stream may still complete
                }

                case TYPE_RST_STREAM:
                    if (streamId == STREAM_ID) {
                        long errorCode = ((payload[0] & 0xFFL) << 24) | ((payload[1] & 0xFF) << 16)
                                | ((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF);
                        throw new IOException("server reset the stream (error " + errorCode + ")");
                    }
                    break;

                case TYPE_HEADERS: {
                    if (streamId != STREAM_ID) break;
                    int off = 0;
                    int end = length;
                    if ((flags & FLAG_PADDED) != 0) {
                        int pad = payload[0] & 0xFF;
                        off = 1;
                        end = length - pad;
                        if (end < off) throw new IOException("HTTP/2 HEADERS padding overflow");
                    }
                    if ((flags & FLAG_PRIORITY) != 0) {
                        off += 5;
                        if (off > end) throw new IOException("HTTP/2 HEADERS priority overflow");
                    }
                    ByteArrayOutputStream blockBytes = new ByteArrayOutputStream();
                    blockBytes.write(payload, off, end - off);
                    int hflags = flags;
                    while ((hflags & FLAG_END_HEADERS) == 0) {
                        in.readFully(header);
                        int clen = ((header[0] & 0xFF) << 16) | ((header[1] & 0xFF) << 8) | (header[2] & 0xFF);
                        int ctype = header[3] & 0xFF;
                        hflags = header[4] & 0xFF;
                        if (ctype != TYPE_CONTINUATION) {
                            throw new IOException("expected CONTINUATION, got frame type " + ctype);
                        }
                        if (clen > MAX_RESPONSE_BYTES) throw new IOException("CONTINUATION too large");
                        byte[] cpayload = new byte[clen];
                        in.readFully(cpayload);
                        blockBytes.write(cpayload, 0, clen);
                    }
                    byte[] blk = blockBytes.toByteArray();
                    List<String[]> decoded = decoder.decode(blk, 0, blk.length);
                    boolean endStream = (flags & FLAG_END_STREAM) != 0;
                    if (!gotHeaders && !endStream) {
                        gotHeaders = true;
                        resp.headers.addAll(decoded);
                        applyStatus(resp, decoded);
                    } else {
                        // Trailers, or a trailers-only response.
                        if (!gotHeaders) {
                            resp.headers.addAll(decoded);
                            applyStatus(resp, decoded);
                        }
                        resp.trailers.addAll(decoded);
                        applyGrpcStatus(resp, decoded);
                        if (endStream) {
                            finish(resp, body.toByteArray());
                            return resp;
                        }
                    }
                    break;
                }

                case TYPE_DATA: {
                    if (streamId != STREAM_ID) break;
                    int off = 0;
                    int end = length;
                    if ((flags & FLAG_PADDED) != 0) {
                        int pad = payload[0] & 0xFF;
                        off = 1;
                        end = length - pad;
                        if (end < off) throw new IOException("HTTP/2 DATA padding overflow");
                    }
                    body.write(payload, off, end - off);
                    if (body.size() > MAX_RESPONSE_BYTES) throw new IOException("response too large");
                    receivedSinceWindowUpdate += length;
                    if (receivedSinceWindowUpdate >= 16384) {
                        byte[] inc = new byte[] {
                                (byte) ((receivedSinceWindowUpdate >>> 24) & 0x7F),
                                (byte) ((receivedSinceWindowUpdate >>> 16) & 0xFF),
                                (byte) ((receivedSinceWindowUpdate >>> 8) & 0xFF),
                                (byte) (receivedSinceWindowUpdate & 0xFF)};
                        writeFrame(out, TYPE_WINDOW_UPDATE, 0, 0, inc);
                        writeFrame(out, TYPE_WINDOW_UPDATE, 0, STREAM_ID, inc);
                        out.flush();
                        receivedSinceWindowUpdate = 0;
                    }
                    if ((flags & FLAG_END_STREAM) != 0) {
                        finish(resp, body.toByteArray());
                        return resp;
                    }
                    break;
                }

                default:
                    // PRIORITY, PUSH_PROMISE (disabled), WINDOW_UPDATE, CONTINUATION out of place, unknown
                    break;
            }
        }
    }

    private static void finish(Response resp, byte[] body) {
        if (body.length >= 5) {
            int compressed = body[0] & 0xFF;
            int len = ((body[1] & 0xFF) << 24) | ((body[2] & 0xFF) << 16)
                    | ((body[3] & 0xFF) << 8) | (body[4] & 0xFF);
            if (len >= 0 && 5 + len <= body.length) {
                resp.message = new byte[len];
                System.arraycopy(body, 5, resp.message, 0, len);
                resp.messageCompressed = compressed != 0;
            }
        }
    }

    private static void applyStatus(Response resp, List<String[]> headers) {
        for (String[] h : headers) {
            if (":status".equals(h[0])) {
                try {
                    resp.httpStatus = Integer.parseInt(h[1].trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
    }

    private static void applyGrpcStatus(Response resp, List<String[]> headers) {
        for (String[] h : headers) {
            if ("grpc-status".equals(h[0])) {
                try {
                    resp.grpcStatus = Integer.parseInt(h[1].trim());
                } catch (NumberFormatException ignored) {
                }
            } else if ("grpc-message".equals(h[0])) {
                resp.grpcMessage = percentDecode(h[1]);
            }
        }
    }

    /** gRPC percent-encodes grpc-message (UTF-8, '%XX'); '+' is literal. */
    static String percentDecode(String s) {
        if (s.indexOf('%') < 0) return s;
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        byte[] raw = s.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < raw.length; i++) {
            if (raw[i] == '%' && i + 2 < raw.length) {
                int hi = Character.digit(raw[i + 1], 16);
                int lo = Character.digit(raw[i + 2], 16);
                if (hi >= 0 && lo >= 0) {
                    out.write((hi << 4) | lo);
                    i += 2;
                    continue;
                }
            }
            out.write(raw[i]);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void writeSetting(ByteArrayOutputStream out, int id, int value) {
        out.write((id >>> 8) & 0xFF);
        out.write(id & 0xFF);
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeFrame(OutputStream out, int type, int flags, int streamId, byte[] payload)
            throws IOException {
        int len = payload.length;
        byte[] h = new byte[9];
        h[0] = (byte) ((len >>> 16) & 0xFF);
        h[1] = (byte) ((len >>> 8) & 0xFF);
        h[2] = (byte) (len & 0xFF);
        h[3] = (byte) type;
        h[4] = (byte) flags;
        h[5] = (byte) ((streamId >>> 24) & 0x7F);
        h[6] = (byte) ((streamId >>> 16) & 0xFF);
        h[7] = (byte) ((streamId >>> 8) & 0xFF);
        h[8] = (byte) (streamId & 0xFF);
        out.write(h);
        out.write(payload);
    }
}
