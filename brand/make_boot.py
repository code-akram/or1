#!/usr/bin/env python3
"""Boot screens for the RAVOZ V1: the or1 pixel wordmark, off-white on black, and nothing else.

Builds, from the stock logo partition (backup/<date>/by-name/logo.bin):
  out/or1.png               the full-screen frame (720x1600)
  out/logo.bin              the logo partition with the RAVOZ screens replaced by that frame
  out/bootanimation.zip     Android's boot animation: the same frame, held until the chat is up
  out/shutdownanimation.zip the same, while powering off

Standard library only. Usage: python3 brand/make_boot.py backup/20261009-202452/by-name/logo.bin
"""
import io
import struct
import sys
import zipfile
import zlib
from pathlib import Path

W, H = 720, 1600
INK = (0xEC, 0xEA, 0xE4)  # the app's text colour (Palette.Text)
PAPER = (0, 0, 0)
CELL = 20  # px per wordmark pixel

# The wordmark, one character per pixel: o, r and 1 in square blocks, all at x-height.
WORDMARK = [
    "### ###  #",
    "# # #   ##",
    "# # #    #",
    "### #    #",
]

# Logo partition images that show RAVOZ: the boot logo (0), the kernel logo (2) and the charger's boot logo (38).
# The others are the charging screens, left as they are.
RAVOZ_IMAGES = (0, 2, 38)

LOGO_MAGIC = 0x58881688


def frame_rgb() -> bytes:
    """The full-screen frame, RGB rows top to bottom."""
    px = bytearray(bytes(PAPER) * (W * H))
    gw, gh = len(WORDMARK[0]) * CELL, len(WORDMARK) * CELL
    x0, y0 = (W - gw) // 2, (H - gh) // 2
    for r, row in enumerate(WORDMARK):
        for c, ch in enumerate(row):
            if ch != "#":
                continue
            for y in range(y0 + r * CELL, y0 + (r + 1) * CELL):
                start = (y * W + x0 + c * CELL) * 3
                px[start:start + CELL * 3] = bytes(INK) * CELL
    return bytes(px)


def png(rgb: bytes) -> bytes:
    raw = b"".join(b"\0" + rgb[y * W * 3:(y + 1) * W * 3] for y in range(H))

    def chunk(tag: bytes, body: bytes) -> bytes:
        return struct.pack(">I", len(body)) + tag + body + struct.pack(">I", zlib.crc32(tag + body) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", W, H, 8, 2, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")


def logo_bin(stock: bytes, rgb: bytes) -> bytes:
    """MTK logo partition: a 512-byte header (magic, body size, name), then a body of image count, total size, an
    offset table and zlib-compressed BGRA images. Rebuilt with the RAVOZ images replaced; padded to the stock size."""
    magic, size = struct.unpack_from("<II", stock, 0)
    assert magic == LOGO_MAGIC, "not an MTK logo partition"
    body = stock[512:512 + size]
    count, total = struct.unpack_from("<II", body, 0)
    offsets = struct.unpack_from(f"<{count}I", body, 8)
    images = [body[offsets[i]:offsets[i + 1] if i + 1 < count else total] for i in range(count)]

    bgra = bytearray(W * H * 4)
    bgra[0::4], bgra[1::4], bgra[2::4] = rgb[2::3], rgb[1::3], rgb[0::3]
    bgra[3::4] = b"\xff" * (W * H)
    ours = zlib.compress(bytes(bgra), 9)
    for i in RAVOZ_IMAGES:
        assert len(zlib.decompress(images[i])) == W * H * 4, f"image {i} isn't full-screen"
        images[i] = ours

    table = 8 + 4 * count
    new_offsets, pos = [], table
    for img in images:
        new_offsets.append(pos)
        pos += len(img)
    new_body = struct.pack("<II", count, pos) + struct.pack(f"<{count}I", *new_offsets) + b"".join(images)
    header = bytearray(stock[:512])
    struct.pack_into("<I", header, 4, len(new_body))
    out = bytes(header) + new_body
    assert len(out) <= len(stock), "doesn't fit the partition"
    return out + stock[len(out):]  # the rest of the partition as it was


def animation(frame_png: bytes) -> bytes:
    """One part, one frame, looped until boot completes. Android requires stored (uncompressed) entries."""
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_STORED) as z:
        z.writestr("desc.txt", f"{W} {H} 1\np 0 0 part0\n")
        z.writestr("part0/00000.png", frame_png)
    return buf.getvalue()


def main() -> None:
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    stock = Path(sys.argv[1]).read_bytes()
    out = Path(__file__).parent / "out"
    out.mkdir(exist_ok=True)
    rgb = frame_rgb()
    frame = png(rgb)
    (out / "or1.png").write_bytes(frame)
    (out / "logo.bin").write_bytes(logo_bin(stock, rgb))
    (out / "bootanimation.zip").write_bytes(animation(frame))
    (out / "shutdownanimation.zip").write_bytes(animation(frame))
    for f in sorted(out.iterdir()):
        print(f"{f.name}: {f.stat().st_size} bytes")


if __name__ == "__main__":
    main()
