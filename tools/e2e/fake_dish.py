#!/usr/bin/env python3
"""Fake Starlink dish for end-to-end tests.

Serves SpaceX.API.Device.Device/Handle over plaintext gRPC, answers reboot and
get_device_info like a dish does, and appends one JSON line per request to --log.

Requires: pip install grpcio grpcio-tools
"""
import argparse
import json
import os
import sys
import tempfile
import threading
import time
from concurrent import futures

import grpc
from grpc_tools import protoc

HERE = os.path.dirname(os.path.abspath(__file__))


def load_stubs():
    out = tempfile.mkdtemp(prefix="fake_dish_")
    pkg = os.path.join(out, "spacex", "api", "device")
    os.makedirs(pkg)
    for d in (os.path.join(out, "spacex"), os.path.join(out, "spacex", "api"), pkg):
        open(os.path.join(d, "__init__.py"), "a").close()
    proto_src = os.path.join(pkg, "device.proto")
    with open(os.path.join(HERE, "device.proto")) as f, open(proto_src, "w") as g:
        g.write(f.read())
    rc = protoc.main(["protoc", "-I" + out, "--python_out=" + out, "--grpc_python_out=" + out,
                      "spacex/api/device/device.proto"])
    if rc != 0:
        sys.exit("protoc failed: %d" % rc)
    sys.path.insert(0, out)
    from spacex.api.device import device_pb2, device_pb2_grpc  # noqa: E402
    return device_pb2, device_pb2_grpc


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=9200)
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--log", required=True)
    args = ap.parse_args()

    pb, pb_grpc = load_stubs()
    lock = threading.Lock()

    class Device(pb_grpc.DeviceServicer):
        def Handle(self, request, context):
            kind = request.WhichOneof("request")
            entry = {"t": time.time(), "request": kind, "peer": context.peer()}
            with lock, open(args.log, "a") as f:
                f.write(json.dumps(entry) + "\n")
            print("[fake-dish] %s from %s" % (kind, context.peer()), flush=True)
            if kind == "reboot":
                return pb.Response(id=request.id, reboot=pb.RebootResponse())
            if kind == "get_device_info":
                info = pb.DeviceInfo(id="ut01000000-00000000-00e2e000", hardware_version="fake_dish",
                                     software_version="e2e", country_code="UA")
                return pb.Response(id=request.id,
                                   get_device_info=pb.GetDeviceInfoResponse(device_info=info))
            context.abort(grpc.StatusCode.UNIMPLEMENTED, "unsupported request %s" % kind)

    open(args.log, "a").close()
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=4))
    pb_grpc.add_DeviceServicer_to_server(Device(), server)
    server.add_insecure_port("%s:%d" % (args.host, args.port))
    server.start()
    print("[fake-dish] listening on %s:%d, log %s" % (args.host, args.port, args.log), flush=True)
    server.wait_for_termination()


if __name__ == "__main__":
    main()
