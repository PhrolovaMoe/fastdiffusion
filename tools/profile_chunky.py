#!/usr/bin/env python3
"""Profile a fresh Chunky selection on an already-running, local RCON test server.

RCON_PASSWORD=... python3 tools/profile_chunky.py PID output.jfr --center -8192 -8192
Use a disposable world with a fixed seed/settings. Existing chunks invalidate the comparison.
"""
import argparse
from datetime import datetime
import os
import re
from pathlib import Path
import socket
import struct
import subprocess
import tempfile
import time


def receive(sock):
    def exact(size):
        result = b""
        while len(result) < size:
            part = sock.recv(size - len(result))
            if not part:
                raise ConnectionError("RCON connection closed")
            result += part
        return result
    size, = struct.unpack("<i", exact(4))
    packet = exact(size)
    request_id, kind = struct.unpack("<ii", packet[:8])
    return request_id, packet[8:-2].decode(errors="replace")


def command(args, text):
    def packet(request_id, kind, body):
        data = body.encode() + b"\0\0"
        return struct.pack("<iii", len(data) + 8, request_id, kind) + data
    with socket.create_connection(("127.0.0.1", args.port), timeout=120) as sock:
        sock.sendall(packet(1, 3, os.environ["RCON_PASSWORD"]))
        request_id, _ = receive(sock)
        if request_id == -1:
            raise RuntimeError("RCON authentication failed")
        sock.sendall(packet(2, 2, text))
        _, response = receive(sock)
        return response.strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pid", type=int)
    parser.add_argument("output", type=Path)
    parser.add_argument("--center", nargs=2, type=int, default=[-8192, -8192])
    parser.add_argument("--radius", type=int, default=512)
    parser.add_argument("--port", type=int, default=25575)
    parser.add_argument("--java-home", type=Path, default=Path(os.environ.get("JAVA_HOME", "/usr/lib/jvm/openjdk21")))
    parser.add_argument("--timeout", type=int, default=600)
    parser.add_argument("--log", type=Path, help="Server latest.log; defaults to the Java process's working directory")
    parser.add_argument("--no-jfr", action="store_true", help="Measure throughput without recording overhead")
    args = parser.parse_args()
    args.output = args.output.resolve()
    if args.log is None:
        args.log = Path(f"/proc/{args.pid}/cwd").resolve() / "logs/latest.log"
    log_offset = args.log.stat().st_size
    if args.output.exists():
        raise RuntimeError(f"Recording already exists: {args.output}")
    if "No tasks running" not in command(args, "chunky progress"):
        raise RuntimeError("A Chunky task is already running; leave it undisturbed")
    for text in ["chunky world minecraft:overworld", f"chunky center {' '.join(map(str, args.center))}",
                 f"chunky radius {args.radius}", "chunky shape square"]:
        print(command(args, text), flush=True)
    print(command(args, "fastdiffusion stats"), flush=True)
    with tempfile.TemporaryDirectory(prefix="chunky-profile-") as temp:
        settings = Path(temp) / "chunky.jfc"
        if not args.no_jfr:
            subprocess.run([str(args.java_home / "bin/jfr"), "configure", "--input",
                            str(args.java_home / "lib/jfr/profile.jfc"), "--output", str(settings),
                            "jdk.ExecutionSample#period=2ms", "+minecraft.ChunkGeneration#enabled=true",
                            "+minecraft.ChunkGeneration#threshold=0ms"], check=True)
            result = subprocess.run([str(args.java_home / "bin/jcmd"), str(args.pid), "JFR.start",
                                     "name=chunky_profile", f"settings={settings}", f"filename={args.output}"],
                                    check=True, capture_output=True, text=True)
            print(result.stdout, flush=True)
            if "Started recording" not in result.stdout:
                raise RuntimeError("JFR did not start")
        start = time.monotonic()
        start_wall = datetime.now()
        try:
            print(command(args, "chunky start"), flush=True)
            while time.monotonic() - start < args.timeout:
                time.sleep(1)
                progress = command(args, "chunky progress")
                print(progress, flush=True)
                with args.log.open() as log:
                    log.seek(log_offset)
                    messages = log.read()
                    log_offset = log.tell()
                # Chunky removes its task when it has SUBMITTED the last request, before
                # all futures finish. "No tasks running" alone overstates throughput.
                finished = next((line for line in messages.splitlines()
                                 if "Task finished for minecraft:overworld." in line and "(100.00%)" in line), None)
                if finished:
                    print(finished, flush=True)
                    timestamp = re.search(r"\[(\d{2}[A-Za-z]{3}\d{4} \d{2}:\d{2}:\d{2}\.\d+)\]", finished)
                    if timestamp:
                        ended = datetime.strptime(timestamp[1], "%d%b%Y %H:%M:%S.%f")
                        print(f"Generation finish log elapsed: {(ended - start_wall).total_seconds():.3f} s", flush=True)
                    print(f"Generation completion observed after {time.monotonic() - start:.3f} s (1-second polling)", flush=True)
                    print(command(args, "save-all flush"), flush=True)
                    print(f"Generation + save flush: {time.monotonic() - start:.3f} s", flush=True)
                    break
            else:
                raise TimeoutError("Chunky did not complete; task is left running")
        finally:
            if not args.no_jfr:
                subprocess.run([str(args.java_home / "bin/jcmd"), str(args.pid), "JFR.stop", "name=chunky_profile"], check=True)
    print(command(args, "fastdiffusion stats"), flush=True)
    if not args.no_jfr:
        subprocess.run([str(args.java_home / "bin/java"), str(Path(__file__).with_name("SummarizeJfr.java")),
                        str(args.output)], check=True)


if __name__ == "__main__":
    main()
