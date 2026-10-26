#!/usr/bin/env python3
"""Control the Android serial bridge and expose its raw TCP stream as a PTY."""
import argparse
import json
import os
import subprocess
import sys
import socket

HOST = "127.0.0.1"
DATA_PORT = 8765
CONTROL_PORT = 8766
PREFIX = os.environ.get("PREFIX", "/data/data/com.termux/files/usr")
DEFAULT_PTY = os.path.join(PREFIX, "tmp", "serial-bridge")

def send_command(payload):
    with socket.create_connection((HOST, CONTROL_PORT), timeout=3) as sock:
        sock.sendall((json.dumps(payload) + "\n").encode("utf-8"))
        stream = sock.makefile("rb")
        response = json.loads(stream.readline().decode("utf-8"))
    print(json.dumps(response, indent=2))
    if not response.get("ok"):
        raise SystemExit(1)

def main():
    parser = argparse.ArgumentParser()
    subs = parser.add_subparsers(dest="action", required=True)
    subs.add_parser("status")
    baud = subs.add_parser("baud")
    baud.add_argument("rate", type=int)
    cfg = subs.add_parser("configure")
    cfg.add_argument("--baudrate", type=int)
    cfg.add_argument("--data-bits", type=int, choices=(5, 6, 7, 8))
    cfg.add_argument("--stop-bits", type=int, choices=(1, 2))
    cfg.add_argument("--parity", choices=("none", "even", "odd", "mark", "space"))
    pty = subs.add_parser("pty")
    pty.add_argument("--path", default=DEFAULT_PTY)
    args = parser.parse_args()

    if args.action == "status":
        send_command({"command": "status"})
    elif args.action == "baud":
        send_command({"command": "configure", "baudrate": args.rate})
    elif args.action == "configure":
        payload = {"command": "configure"}
        if args.baudrate is not None: payload["baudrate"] = args.baudrate
        if args.data_bits is not None: payload["data_bits"] = args.data_bits
        if args.stop_bits is not None: payload["stop_bits"] = args.stop_bits
        if args.parity is not None: payload["parity"] = args.parity.upper()
        send_command(payload)
    elif args.action == "pty":
        try:
            os.unlink(args.path)
        except FileNotFoundError:
            pass
        os.makedirs(os.path.dirname(args.path), exist_ok=True)
        try:
            os.execvp("socat", ["socat", "-d", "-d",
                f"pty,link={args.path},raw,echo=0,waitslave",
                f"tcp:{HOST}:{DATA_PORT},nodelay"])
        except FileNotFoundError:
            print("Install socat first: pkg install socat", file=sys.stderr)
            raise SystemExit(2)

if __name__ == "__main__":
    main()
