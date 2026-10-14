package com.aboba.starlinkreconnect.grpc;

import java.io.IOException;
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

        Result(boolean ok, String detail) {
            this.ok = ok;
            this.detail = detail;
        }

        @Override
        public String toString() {
            return (ok ? "OK: " : "FAILED: ") + detail;
        }
    }

    private final SocketFactory socketFactory;
    private final String host;
    private final int port;
    private final int timeoutMs;

    public StarlinkClient(SocketFactory socketFactory, String host, int port, int timeoutMs) {
        this.socketFactory = socketFactory;
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
     * Sends Request{reboot: RebootRequest{}}. A device that drops the connection right after the
     * request was delivered is reported as success: the router in particular restarts before it
     * manages to answer.
     */
    public Result reboot() {
        byte[] body = new Proto.Writer().emptyMessage(REQUEST_REBOOT).toByteArray();
        try {
            Http2GrpcClient.Response r = Http2GrpcClient.unaryCall(socketFactory, host, port,
                    HANDLE_PATH, body, timeoutMs);
            if (r.isOk()) {
                return new Result(true, "reboot accepted by " + host + ":" + port);
            }
            if (r.grpcStatus == GRPC_UNAVAILABLE && r.message == null && r.httpStatus == 200) {
                return new Result(true, "reboot sent to " + host + ":" + port
                        + ", device went away while answering (" + statusName(r.grpcStatus) + ")");
            }
            return new Result(false, "reboot rejected by " + host + ":" + port + ": "
                    + statusName(r.grpcStatus) + (r.grpcMessage != null ? " - " + r.grpcMessage : ""));
        } catch (Http2GrpcClient.TransportException e) {
            if (e.requestSent && !(e.getCause() instanceof java.net.SocketTimeoutException)) {
                return new Result(true, "reboot sent to " + host + ":" + port
                        + ", connection dropped before the reply (device restarting): " + e.getMessage());
            }
            return new Result(false, host + ":" + port + " unreachable: " + e.getMessage());
        } catch (IOException e) {
            return new Result(false, host + ":" + port + " unreachable: " + describe(e));
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
            Http2GrpcClient.Response r = Http2GrpcClient.unaryCall(socketFactory, host, port,
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
        } catch (IOException e) {
            return new Result(false, host + ":" + port + " unreachable: " + describe(e));
        }
    }

    private static String text(Proto.Field f) {
        String s = f == null ? null : f.asString();
        return s == null || s.isEmpty() ? "?" : s;
    }

    private static String describe(IOException e) {
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }
}
