#!/usr/bin/env python3
"""Guest-side client for the VirtualMinecraft bus (line-delimited JSON-RPC 2.0 over virtio-serial).

Run *inside* the VM. Examples:
    bus.py list
    bus.py redstone.setOutput north 15
    bus.py redstone.getInputs
    bus.py invoke <address> setOutput front true
    bus.py subscribe redstone_changed --watch      # print events until Ctrl-C
Numbers, true/false/null and JSON literals are parsed; anything else is a string.
Fetch it into the guest with e.g.  curl -O http://10.0.2.2:8000/bus.py  (python3 -m http.server on the host).
"""
import json
import os
import sys

PORT = os.environ.get("VMC_BUS", "/dev/virtio-ports/vmc.bus")


def parse_arg(a):
    try:
        return json.loads(a)
    except ValueError:
        return a


class Bus:
    def __init__(self, path=PORT):
        # One fd, read and write; O_RDWR on the virtio port works without any tty layer.
        self.fd = os.open(path, os.O_RDWR)
        self.buf = b""
        self.next_id = 1

    def send(self, method, params=None, notify=False):
        msg = {"jsonrpc": "2.0", "method": method}
        if params is not None:
            msg["params"] = params
        if not notify:
            msg["id"] = self.next_id
            self.next_id += 1
        os.write(self.fd, (json.dumps(msg) + "\n").encode())
        return None if notify else msg["id"]

    def read(self):
        while b"\n" not in self.buf:
            chunk = os.read(self.fd, 65536)
            if not chunk:
                raise EOFError("bus closed")
            self.buf += chunk
        line, self.buf = self.buf.split(b"\n", 1)
        return json.loads(line)

    def call(self, method, params=None):
        rid = self.send(method, params)
        while True:
            msg = self.read()
            if msg.get("id") == rid:
                if "error" in msg:
                    raise RuntimeError("%s (code %s)" % (msg["error"]["message"], msg["error"]["code"]))
                return msg.get("result")
            if msg.get("method") == "event":
                print("event:", json.dumps(msg["params"]), file=sys.stderr)


def main(argv):
    if not argv or argv[0] in ("-h", "--help"):
        print(__doc__)
        return 0
    watch = "--watch" in argv
    argv = [a for a in argv if a != "--watch"]
    method, args = argv[0], [parse_arg(a) for a in argv[1:]]
    bus = Bus()
    if method == "invoke":
        params = {"address": args[0], "method": args[1], "args": args[2:]}
    elif method in ("subscribe", "unsubscribe"):
        params = args or ["*"]
    else:
        params = args or None
    print(json.dumps(bus.call(method, params)))
    if watch:
        while True:
            msg = bus.read()
            print(json.dumps(msg.get("params", msg)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
