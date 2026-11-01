#!/usr/bin/env python3
"""Convert a binary PPM (P6) to PNG using only the standard library."""
import struct
import sys
import zlib


def main():
    src, dst = sys.argv[1], sys.argv[2]
    data = open(src, "rb").read()
    header, _, rest = data.split(b"\n", 1)[0], None, data.split(b"\n", 1)[1]
    _, w, h, _ = header.split()
    w, h = int(w), int(h)
    rows = b"".join(b"\x00" + rest[y * w * 3:(y + 1) * w * 3] for y in range(h))

    def chunk(tag, body):
        return struct.pack(">I", len(body)) + tag + body + struct.pack(">I", zlib.crc32(tag + body) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(rows, 6)) + chunk(b"IEND", b"")
    open(dst, "wb").write(png)


if __name__ == "__main__":
    main()
