package com.aboba.starlinkreconnect.grpc;

import java.util.List;

import javax.net.SocketFactory;

/**
 * Starlink local gRPC API client (SpaceX.API.Device.Device/Handle), hand-rolled so that the app
 * needs neither grpc-java nor protobuf. Works for the dish (192.168.100.1:9200) and the router
 * (192.168.1.1:9000), both of which speak the same Device service over plaintext HTTP/2.
 */
public final class StarlinkClient {
    public static final String DISH_HOST = "192.168.100.1";
    public static final int DISH_PORT = 9200;
    public static final String ROUTER_HOST = "192.168.1.1";
    public static final int ROUTER_PORT = 9000;

    public static final String HANDLE_PATH = "/SpaceX.API.Device.Device/Handle";

    // spacex.api.device.Request field numbers (oneof request).
    static final int REQUEST_ID = 1;
    static final int REQUEST_TARGET_ID = 13;
    static final int REQUEST_EPOCH_ID = 14;
    static final int REQUEST_REBOOT = 1001;
    static final int REQUEST_GET_DEVICE_INFO = 1008;

    // spacex.api.device.Response field numbers (oneof response).
    static final int RESPONSE_REBOOT = 1001;
    static final int RESPONSE_GET_DEVICE_INFO = 1004;
    // GetDeviceInfoResponse.device_info = 1; DeviceInfo fields (spacex/api/device/common.proto).
    static final int DEVICE_INFO_ID = 1;
    static final int DEVICE_INFO_HARDWARE_VERSION = 2;
    static final int DEVICE_INFO_SOFTWARE_VERSION = 3;
    static final int DEVICE_INFO_COUNTRY_CODE = 4;

    /** Outcome of a command. */
    public static final class Result {
        public final boolean ok;
        public final String detail;

        public Result(boolean ok, String detail) {
            this.ok = ok;
            this.detail = detail;
        }

        @Override
        public String toString() {
            return (ok ? "OK: " : "FAILED: ") + detail;
        }
    }

    private final SocketFactory socketFactory;
    private final Http2GrpcClient.Resolver resolver;
    private final String host;
    private final int port;
    private final int timeoutMs;

    public StarlinkClient(SocketFactory socketFactory, String host, int port, int timeoutMs) {
        this(socketFactory, null, host, port, timeoutMs);
    }

    public StarlinkClient(SocketFactory socketFactory, Http2GrpcClient.Resolver resolver,
                          String host, int port, int timeoutMs) {
        this.socketFactory = socketFactory;
        this.resolver = resolver;
        this.host = host;
        this.port = port;
        this.timeoutMs = timeoutMs;
    }

    public static StarlinkClient dish(SocketFactory sf, int timeoutMs) {
        return new StarlinkClient(sf, DISH_HOST, DISH_PORT, timeoutMs);
    }

    public static StarlinkClient router(SocketFactory sf, int timeoutMs) {
        return new StarlinkClient(sf, ROUTER_HOST, ROUTER_PORT, timeoutMs);
    }

    /**
     * Sends Request{reboot: RebootRequest{}}.
     *
     * The dish normally answers with an empty RebootResponse before restarting. The router (and
     * a Starlink Mini) often drop the connection or answer UNAVAILABLE while already going down.
     * Those two outcomes are therefore followed by a short probe: if the device stops accepting
     * connections within a few seconds the reboot is reported as sent; if it stays up and keeps
     * answering, the attempt is reported as failed so that the caller retries.
     */
    public Result reboot() {
        byte[] body = new Proto.Writer().emptyMessage(REQUEST_REBOOT).toByteArray();
        try {
            Http2GrpcClient.Response r = Http2GrpcClient.unaryCall(socketFactory, resolver, host, port,
                    HANDLE_PATH, body, timeoutMs);
            if (r.isOk()) {
                return new Result(true, "reboot accepted by " + host + ":" + port);
            }
            if (r.grpcStatus == GRPC_UNAVAILABLE && r.message == null && r.httpStatus == 200) {
                if (wentDownAfterCommand()) {
                    return new Result(true, "reboot sent to " + host + ":" + port
                            + ", device answered UNAVAILABLE and then went down (restarting)");
                }
                return new Result(false, "reboot not accepted by " + host + ":" + port
                        + ": UNAVAILABLE but the device is still up" + (r.grpcMessage != null ? " - " + r.grpcMessage : ""));
            }
            return new Result(false, "reboot rejected by " + host + ":" + port + ": "
                    + statusName(r.grpcStatus) + (r.grpcMessage != null ? " - " + r.grpcMessage : ""));
        } catch (Http2GrpcClient.TransportException e) {
            boolean ambiguous = e.requestSent
                    && (e.kind == Http2GrpcClient.Failure.DROPPED || e.kind == Http2GrpcClient.Failure.TIMEOUT);
            if (ambiguous) {
                // The command was delivered; a device that restarts may drop the link (FIN/RST) or
                // simply go silent. Watch whether it disappears before judging.
                if (wentDownAfterCommand()) {
                    return new Result(true, "reboot sent to " + host + ":" + port + ", "
                            + (e.kind == Http2GrpcClient.Failure.DROPPED ? "connection dropped" : "no reply")
                            + " and the device went down (restarting)");
                }
                return new Result(false, host + ":" + port + " got the command but is still up ("
                        + e.getMessage() + ")");
            }
            return new Result(false, host + ":" + port + " " + failureText(e));
        }
    }

    /**
     * After an ambiguous reboot outcome, polls the device a few times: true when it stops
     * accepting TCP connections (i.e. it is really restarting).
     */
    private boolean wentDownAfterCommand() {
        byte[] body = new Proto.Writer().emptyMessage(REQUEST_GET_DEVICE_INFO).toByteArray();
        for (int i = 0; i < PROBE_ATTEMPTS; i++) {
            sleep(PROBE_GAP_MS);
            try {
                Http2GrpcClient.unaryCall(socketFactory, resolver, host, port, HANDLE_PATH, body, PROBE_TIMEOUT_MS);
            } catch (Http2GrpcClient.TransportException e) {
                if (e.kind == Http2GrpcClient.Failure.CONNECT
                        || (e.kind == Http2GrpcClient.Failure.TIMEOUT && !e.requestSent)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Routers keep accepting TCP for a number of seconds after the command; watch for ~20 s. */
    static final int PROBE_ATTEMPTS = 7;
    static final long PROBE_GAP_MS = 3_000L;
    static final int PROBE_TIMEOUT_MS = 3_000;

    static String failureText(Http2GrpcClient.TransportException e) {
        switch (e.kind) {
            case CONNECT: return "unreachable: " + e.getMessage();
            case TIMEOUT: return (e.requestSent ? "did not answer: " : "unreachable: ") + e.getMessage();
            case DROPPED: return "dropped the connection: " + e.getMessage();
            case REJECTED: return "refused the request: " + e.getMessage();
            default: return "sent an invalid reply: " + e.getMessage();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static final int GRPC_UNAVAILABLE = 14;

    /** Human-readable gRPC status code names (https://grpc.io/docs/guides/status-codes/). */
    static String statusName(int code) {
        switch (code) {
            case 0: return "OK";
            case 1: return "CANCELLED";
            case 2: return "UNKNOWN";
            case 3: return "INVALID_ARGUMENT";
            case 4: return "DEADLINE_EXCEEDED";
            case 5: return "NOT_FOUND";
            case 6: return "ALREADY_EXISTS";
            case 7: return "PERMISSION_DENIED (firmware refuses this command)";
            case 8: return "RESOURCE_EXHAUSTED";
            case 9: return "FAILED_PRECONDITION";
            case 10: return "ABORTED";
            case 11: return "OUT_OF_RANGE";
            case 12: return "UNIMPLEMENTED (not supported by this device/firmware)";
            case 13: return "INTERNAL";
            case 14: return "UNAVAILABLE";
            case 15: return "DATA_LOSS";
            case 16: return "UNAUTHENTICATED";
            case -1: return "no grpc-status in reply";
            default: return "status " + code;
        }
    }

    /** Sends Request{get_device_info: {}} and returns "id / hw / sw" when the reply parses. */
    public Result getDeviceInfo() {
        byte[] body = new Proto.Writer().emptyMessage(REQUEST_GET_DEVICE_INFO).toByteArray();
        try {
            Http2GrpcClient.Response r = Http2GrpcClient.unaryCall(socketFactory, resolver, host, port,
                    HANDLE_PATH, body, timeoutMs);
            if (!r.isOk()) {
                return new Result(false, host + ":" + port + " answered " + statusName(r.grpcStatus)
                        + (r.grpcMessage != null ? " - " + r.grpcMessage : ""));
            }
            if (r.message == null || r.messageCompressed) {
                return new Result(true, host + ":" + port + " reachable (no readable device info)");
            }
            List<Proto.Field> response = Proto.parse(r.message);
            Proto.Field gdi = Proto.find(response, RESPONSE_GET_DEVICE_INFO);
            Proto.Field info = gdi == null || gdi.bytes == null ? null
                    : Proto.find(Proto.parse(gdi.bytes), 1);
            if (info == null || info.bytes == null) {
                return new Result(true, host + ":" + port + " reachable (device info not in reply)");
            }
            List<Proto.Field> di = Proto.parse(info.bytes);
            String id = text(Proto.find(di, DEVICE_INFO_ID));
            String hw = text(Proto.find(di, DEVICE_INFO_HARDWARE_VERSION));
            String sw = text(Proto.find(di, DEVICE_INFO_SOFTWARE_VERSION));
            return new Result(true, "id " + id + ", hw " + hw + ", sw " + sw);
        } catch (Http2GrpcClient.TransportException e) {
            return new Result(false, host + ":" + port + " " + failureText(e));
        }
    }

    private static String text(Proto.Field f) {
        String s = f == null ? null : f.asString();
        return s == null || s.isEmpty() ? "?" : s;
    }

}
