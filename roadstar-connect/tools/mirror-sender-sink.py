#!/usr/bin/env python3
"""CI-only local TLS sink for the actual Android phone capture path.

Stores counts and metadata only. No screen, compressed video or PCM data is saved.
Run before instrumentation; Android emulator reaches this host at 10.0.2.2:8999.
"""
import argparse
import json
import socket
import ssl
import struct
import sys
import time
from pathlib import Path

MAGIC = 0x52434D31
MAX_PAYLOAD = 2 * 1024 * 1024
FORMAT, VIDEO, AUDIO, STATUS, STOP = range(1, 6)
NAMES = {FORMAT: "FORMAT", VIDEO: "VIDEO", AUDIO: "AUDIO", STATUS: "STATUS", STOP: "STOP"}


def exact(connection, length, allow_eof=False):
    result = bytearray()
    while len(result) < length:
        part = connection.recv(min(length - len(result), 65536))
        if not part:
            if allow_eof and not result:
                return None
            raise ValueError("Truncated wire message")
        result.extend(part)
    return bytes(result)


def video_format(payload):
    if not 16 <= len(payload) <= 131088:
        raise ValueError("Invalid FORMAT length")
    width, height = struct.unpack_from(">ii", payload)
    if (width, height) not in ((1920, 1080), (1280, 720)):
        raise ValueError("Unsupported video dimensions")
    position = 8
    sizes = []
    for _ in range(2):
        if position + 4 > len(payload):
            raise ValueError("Truncated CSD header")
        length, = struct.unpack_from(">i", payload, position)
        position += 4
        if not 0 <= length <= 65536 or position + length > len(payload):
            raise ValueError("Invalid CSD length")
        sizes.append(length)
        position += length
    if position != len(payload):
        raise ValueError("Trailing FORMAT bytes")
    return {"width": width, "height": height, "csd_lengths": sizes}


def receive_session(connection, report):
    hello = exact(connection, 10, allow_eof=True)
    if hello is None:
        return False  # Certificate inspection deliberately sends no pairing code.
    if hello != struct.pack(">I", MAGIC) + b"123456":
        raise ValueError("Invalid HELLO magic or pairing code")
    report["authenticated"] = True
    connection.sendall(struct.pack(">Iii", MAGIC, 1920, 1080))
    configured = False
    while True:
        header = exact(connection, 13, allow_eof=True)
        if header is None:
            report["eos"] = True
            report["termination"] = "peer_eof"
            return True
        kind, pts, length = struct.unpack(">Bqi", header)
        if kind not in NAMES or pts < 0 or not 0 <= length <= MAX_PAYLOAD:
            raise ValueError("Invalid frame type, timestamp or payload length")
        if kind == FORMAT and not 16 <= length <= 131088:
            raise ValueError("Invalid FORMAT header length")
        if kind == AUDIO and (length > 16384 or length % 2):
            raise ValueError("Invalid PCM frame length")
        if kind == STATUS and length > 1024:
            raise ValueError("Oversized status")
        if kind == STOP and length:
            raise ValueError("STOP must be empty")
        payload = exact(connection, length)
        if kind == FORMAT:
            report["format"] = video_format(payload)
            if (report["format"]["width"], report["format"]["height"]) != (1920, 1080):
                raise ValueError("Actual phone capture must encode the selected Full HD 1920x1080")
            configured = True
        elif kind in (VIDEO, AUDIO):
            if not configured:
                report["media_before_format"] = True
                raise ValueError("Media arrived before FORMAT")
            if report["first_media"] is None:
                report["first_media"] = NAMES[kind]
            if kind == VIDEO and not length:
                raise ValueError("Real encoder produced empty VIDEO")
            if kind == AUDIO:
                report["pcm_samples"] += length // 2
                # PCM16 little-endian. Each nonzero byte pair represents an audible sample.
                report["nonzero_pcm_samples"] += sum(
                    payload[i] != 0 or payload[i + 1] != 0 for i in range(0, length, 2))
        elif kind == STATUS:
            payload.decode("utf-8", errors="strict")
        elif kind == STOP:
            report["stop_received"] = True
            report["eos"] = True
            report["termination"] = "stop_frame"
        report["frames"][NAMES[kind]] += 1
        if kind == STOP:
            return True


def serve(cert, key, report_path):
    report = {"authenticated": False, "frames": {name: 0 for name in NAMES.values()},
              "first_media": None, "media_before_format": False, "pcm_samples": 0,
              "nonzero_pcm_samples": 0, "eos": False, "stop_received": False,
              "certificate_probes": 0, "success": False, "ok": False}
    started = time.monotonic()
    try:
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(cert, key)
        with socket.socket() as server:
            server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            server.bind(("127.0.0.1", 8999))
            server.listen(2)
            server.settimeout(900)  # CI starts the sink before booting and installing the emulator.
            print("Mirror sender sink ready on 127.0.0.1:8999", flush=True)
            while True:
                client, _ = server.accept()
                client.settimeout(35)
                with context.wrap_socket(client, server_side=True) as connection:
                    if receive_session(connection, report):
                        break
                    report["certificate_probes"] += 1
        report["success"] = bool(report["authenticated"] and report["eos"]
                                 and not report["media_before_format"]
                                 and report["frames"]["FORMAT"] > 0
                                 and report["frames"]["VIDEO"] > 0
                                 and report["frames"]["AUDIO"] > 0
                                 and report["nonzero_pcm_samples"] > 0)
        if not report["success"]:
            raise ValueError("Capture did not deliver ordered video and nonzero internal PCM")
        report["ok"] = True
    except Exception as error:
        report["error"] = str(error)
        print("Mirror sender sink failed: " + str(error), file=sys.stderr, flush=True)
    finally:
        report["elapsed_seconds"] = round(time.monotonic() - started, 3)
        destination = Path(report_path)
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(report), flush=True)
    return 0 if report["success"] else 1


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cert", required=True)
    parser.add_argument("--key", required=True)
    parser.add_argument("--report", required=True)
    options = parser.parse_args()
    raise SystemExit(serve(options.cert, options.key, options.report))
