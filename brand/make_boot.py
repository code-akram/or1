#!/usr/bin/env python3
"""Boot and charging screens for the RAVOZ V1: the or1 pixel style, off-white on black, and nothing else.

Builds, from the stock logo partition (backup/<date>/by-name/logo.bin):
  out/or1.png               the boot frame (720x1600): the wordmark, centred
  out/charging.png          a contact sheet of every charging image, for review
  out/logo.bin              the logo partition, every image redrawn (boot logos, charging screens, digits)
  out/bootanimation.zip     Android's boot animation: the boot frame, held until the chat is up
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
DIM = (0x39, 0x38, 0x35)  # the rail colour (Palette.Border)
ACCENT = (0xD9, 0x77, 0x57)  # terracotta (Palette.Accent): low battery only
PAPER = (0, 0, 0)

# The wordmark, one character per block: o, r and 1, all at x-height.
WORDMARK = [
    "### ###  #",
    "# # #   ##",
    "# # #    #",
    "### #    #",
]

# The pixel font for numbers, as in the app's status rail (app/.../ui/StatusRail.kt): square blocks, no diagonals.
GLYPHS = {
    "0": ["###", "#.#", "#.#", "#.#", "###"],
    "1": [".#", "##", ".#", ".#", ".#"],
    "2": ["###", "..#", "###", "#..", "###"],
    "3": ["###", "..#", "###", "..#", "###"],
    "4": ["#.#", "#.#", "###", "..#", "..#"],
    "5": ["###", "#..", "###", "..#", "###"],
    "6": ["###", "#..", "###", "#.#", "###"],
    "7": ["###", "..#", "..#", "..#", "..#"],
    "8": ["###", "#.#", "###", "#.#", "###"],
    "9": ["###", "#.#", "###", "..#", "###"],
    "%": ["#.#", "..#", ".#.", "#..", "#.#"],
}

# The logo partition (MTK): what each image is, from the stock artwork and the sizes LK draws them at.
BOOT_LOGOS = (0, 2, 38)  # boot logo, kernel logo, charger's boot logo
DIGITS = range(4, 14)  # 45x64, 0-9, drawn over the charging screen
PERCENT = 14  # 57x64
WAVES = range(15, 25)  # 180x32, the animated top of the fill
LEVELS = range(25, 35)  # full-screen charging frames, rising fill
LINE = 36  # one row of the fill, repeated by LK up to the level
FULL = (37, 42)  # charged: full battery and 100%
BACKGROUNDS = (1, 35)  # the battery, empty: LK fills it and draws the digits
LOW = (3, 39, 40, 41)  # low battery / plug in
FAST_LEVELS = range(43, 49)  # full-screen frames of the fast-charging animation
BIG_DIGITS = range(49, 59)  # 72x128, 0-9
BIG_PERCENT = 59

# Where LK fills the battery (measured from the stock frames): x 270-449, from y 1024 up to y 707 when full. The
# outline sits one block outside it, so LK's fill lands inside whatever animation style it picks.
FILL_X, FILL_W = 270, 180
FILL_TOP, FILL_BOTTOM = 707, 1025
B = 10  # px per block on the charging screens
DIGITS_TOP = 521  # where LK draws the percentage (stock 100% artwork at y 519-583), 60 px glyphs

LOGO_MAGIC = 0x58881688


class Image:
    def __init__(self, w: int, h: int, color=PAPER):
        self.w, self.h = w, h
        self.px = bytearray(bytes(color) * (w * h))

    def rect(self, x: int, y: int, w: int, h: int, color) -> None:
        x0, x1 = max(0, x), min(self.w, x + w)
        for yy in range(max(0, y), min(self.h, y + h)):
            self.px[(yy * self.w + x0) * 3:(yy * self.w + x1) * 3] = bytes(color) * (x1 - x0)

    def text(self, s: str, x: int, y: int, block: int, color) -> None:
        """Bottoms aligned to a 5-row line starting at y."""
        for ch in s:
            g = GLYPHS[ch]
            top = y + (5 - len(g)) * block
            for r, row in enumerate(g):
                for c, bit in enumerate(row):
                    if bit == "#":
                        self.rect(x + c * block, top + r * block, block, block, color)
            x += (len(g[0]) + 1) * block

    def bgra(self) -> bytes:
        out = bytearray(self.w * self.h * 4)
        out[0::4], out[1::4], out[2::4] = self.px[2::3], self.px[1::3], self.px[0::3]
        out[3::4] = b"\xff" * (self.w * self.h)
        return bytes(out)

    def png(self) -> bytes:
        raw = b"".join(b"\0" + self.px[y * self.w * 3:(y + 1) * self.w * 3] for y in range(self.h))

        def chunk(tag: bytes, body: bytes) -> bytes:
            return struct.pack(">I", len(body)) + tag + body + struct.pack(">I", zlib.crc32(tag + body) & 0xFFFFFFFF)

        ihdr = struct.pack(">IIBBBBB", self.w, self.h, 8, 2, 0, 0, 0)
        return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b"")


def text_width(s: str, block: int) -> int:
    return (sum(len(GLYPHS[c][0]) + 1 for c in s) - 1) * block


def boot_frame() -> Image:
    """The wordmark at 20 px per block, centred."""
    img = Image(W, H)
    cell = 20
    x0, y0 = (W - len(WORDMARK[0]) * cell) // 2, (H - len(WORDMARK) * cell) // 2
    for r, row in enumerate(WORDMARK):
        for c, bit in enumerate(row):
            if bit == "#":
                img.rect(x0 + c * cell, y0 + r * cell, cell, cell, INK)
    return img


def battery(fill: float = 0.0, outline=INK, fill_color=INK, label: str = "") -> Image:
    """The pixel battery around LK's fill box: a one-block outline with a one-block gap, a cap, the fill from the
    bottom, and an optional percentage where LK draws its digits."""
    img = Image(W, H)
    left, right = FILL_X - 2 * B, FILL_X + FILL_W + B
    top, bottom = FILL_TOP - 2 * B, FILL_BOTTOM + B
    img.rect(left, top, right + B - left, B, outline)
    img.rect(left, bottom, right + B - left, B, outline)
    img.rect(left, top, B, bottom - top, outline)
    img.rect(right, top, B, bottom - top, outline)
    img.rect(W // 2 - 3 * B, top - B, 6 * B, B, outline)  # the cap
    height = round((FILL_BOTTOM - FILL_TOP) * fill)
    if height:
        img.rect(FILL_X, FILL_BOTTOM - height, FILL_W, height, fill_color)
    if label:
        img.text(label, (W - text_width(label, 12)) // 2, DIGITS_TOP, 12, INK)
    return img


def glyph_tile(ch: str, w: int, h: int, block: int) -> Image:
    img = Image(w, h)
    img.text(ch, (w - text_width(ch, block)) // 2, (h - 5 * block) // 2, block, INK)
    return img


def wave(frame: int) -> Image:
    """The top of the fill, 180x32: the fill's top row of blocks with one block riding on it, marching across."""
    img = Image(FILL_W, 32)
    img.rect(0, 32 - B, FILL_W, B, INK)
    img.rect(round(frame * (FILL_W - B) / 9), 32 - 2 * B - 2, B, B, INK)
    return img


def charging_images() -> dict[int, Image]:
    images = {i: boot_frame() for i in BOOT_LOGOS}
    images |= {i: glyph_tile(str(d), 45, 64, 12) for d, i in enumerate(DIGITS)}
    images[PERCENT] = glyph_tile("%", 57, 64, 12)
    images |= {i: wave(k) for k, i in enumerate(WAVES)}
    images |= {i: battery((k + 1) / 10) for k, i in enumerate(LEVELS)}
    images[LINE] = Image(FILL_W, 2, INK)
    images |= {i: battery(1.0, label="100%") for i in FULL}
    images |= {i: battery() for i in BACKGROUNDS}
    images |= {i: battery(0.1, outline=ACCENT, fill_color=ACCENT) for i in LOW}
    images |= {i: battery((k + 1) / len(FAST_LEVELS)) for k, i in enumerate(FAST_LEVELS)}
    images |= {i: glyph_tile(str(d), 72, 128, 22) for d, i in enumerate(BIG_DIGITS)}
    images[BIG_PERCENT] = glyph_tile("%", 72, 128, 22)
    return images


def logo_bin(stock: bytes, images: dict[int, Image]) -> bytes:
    """MTK logo partition: a 512-byte header (magic, body size, name), then a body of image count, total size, an
    offset table and zlib-compressed BGRA images. Rebuilt with the given images; padded to the stock size."""
    magic, size = struct.unpack_from("<II", stock, 0)
    assert magic == LOGO_MAGIC, "not an MTK logo partition"
    body = stock[512:512 + size]
    count, total = struct.unpack_from("<II", body, 0)
    offsets = struct.unpack_from(f"<{count}I", body, 8)
    blobs = [body[offsets[i]:offsets[i + 1] if i + 1 < count else total] for i in range(count)]
    for i, img in images.items():
        assert len(zlib.decompress(blobs[i])) == img.w * img.h * 4, f"image {i} isn't {img.w}x{img.h}"
        blobs[i] = zlib.compress(img.bgra(), 9)

    new_offsets, pos = [], 8 + 4 * count
    for blob in blobs:
        new_offsets.append(pos)
        pos += len(blob)
    new_body = struct.pack("<II", count, pos) + struct.pack(f"<{count}I", *new_offsets) + b"".join(blobs)
    header = bytearray(stock[:512])
    struct.pack_into("<I", header, 4, len(new_body))
    out = bytes(header) + new_body
    assert len(out) <= len(stock), "doesn't fit the partition"
    return out + stock[len(out):]  # the rest of the partition as it was


def contact_sheet(images: dict[int, Image]) -> Image:
    """Every image at 1/4 scale (small ones at full size), in index order, on grey."""
    tiles = []
    for i in sorted(images):
        img = images[i]
        s = 4 if img.w == W else 1
        tiles.append((img, s))
    sheet_w, pad = 1200, 8
    x = y = row_h = 0
    places = []
    for img, s in tiles:
        w, h = img.w // s, img.h // s
        if x + w > sheet_w:
            x, y, row_h = 0, y + row_h + pad, 0
        places.append((x, y))
        x, row_h = x + w + pad, max(row_h, h)
    sheet = Image(sheet_w, y + row_h, (0x30, 0x30, 0x30))
    for (img, s), (ox, oy) in zip(tiles, places):
        for yy in range(img.h // s):
            for xx in range(img.w // s):
                p = ((yy * s) * img.w + xx * s) * 3
                q = ((oy + yy) * sheet_w + ox + xx) * 3
                sheet.px[q:q + 3] = img.px[p:p + 3]
    return sheet


def animation(frame_png: bytes) -> bytes:
    """One part, one frame, looped until boot completes. Android requires stored (uncompressed) entries. A fixed
    timestamp (the first install's) keeps the zip byte-identical across builds, which install_bootanim.py checks."""
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_STORED) as z:
        for name, data in (("desc.txt", f"{W} {H} 1\np 0 0 part0\n".encode()), ("part0/00000.png", frame_png)):
            z.writestr(zipfile.ZipInfo(name, date_time=(2026, 10, 10, 18, 40, 42)), data)
    return buf.getvalue()


def main() -> None:
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    stock = Path(sys.argv[1]).read_bytes()
    out = Path(__file__).parent / "out"
    out.mkdir(exist_ok=True)
    frame = boot_frame().png()
    images = charging_images()
    (out / "or1.png").write_bytes(frame)
    (out / "charging.png").write_bytes(contact_sheet(images).png())
    (out / "logo.bin").write_bytes(logo_bin(stock, images))
    (out / "bootanimation.zip").write_bytes(animation(frame))
    (out / "shutdownanimation.zip").write_bytes(animation(frame))
    for f in sorted(out.iterdir()):
        if f.is_file():
            print(f"{f.name}: {f.stat().st_size} bytes")


if __name__ == "__main__":
    main()
