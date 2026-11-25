#!/usr/bin/env python3
"""Give the in-app mark a charcoal tile for the night theme.

The header used to show a bare lens glyph, which kept the page quiet but lost
the one thing the launcher icon has: the cream plate the mark sits on. The tile
is back, so it has to stop being the brightest surface on a dark page.

The launcher art is a raster file and stays exactly as rendered; this script
only re-lights its plate. The mark itself — rim, glass, handle, the A and the
文 — is cut out by its own geometry (a ring and the handle capsule) and left
untouched, while every plate pixel is mapped from its paper luminance onto a
charcoal gradient. The artwork's cast shadow survives the mapping as a slightly
darker charcoal, so the mark still sits on the plate rather than floating.

    python3 night_mark.py

writes
    lucid-icon-1024-night.png

build_header.sh runs this and then cuts both themes into res/drawable-* and
res/drawable-night-*.
"""
import math
import struct
import zlib

HERE = __file__.rsplit("/", 1)[0]
SRC = f"{HERE}/lucid-icon-1024.png"
OUT = f"{HERE}/lucid-icon-1024-night.png"

# ── the mark, measured off the render (1024 canvas) ────────────────────────
CX, CY = 473.0, 470.0       # centre of the glass
RING_R = 266.0              # outer edge of the cream rim
HANDLE_HALF = 56.0          # half-width of the handle
HANDLE_R0, HANDLE_R1 = 170.0, 505.0   # the handle runs from inside the rim
ANGLE = math.radians(45.0)  # ... down to its rounded tip, both on the +x/+y ray

# ── the night plate ───────────────────────────────────────────────────────
# A warm charcoal, matched to the night page's shell and knob surfaces. The
# paper's own gradient is kept, keyed on luminance, so the tile reads as lit
# from the same corner it was in the day theme.
DARK_LO = (0x1D, 0x1A, 0x15)
DARK_HI = (0x3C, 0x37, 0x2F)
LUMA_LO, LUMA_HI = 0.72, 0.99   # paper sits high in this range; the cast
                                # shadow falls to the bottom of it


def read_png(path):
    """Decode an 8-bit RGBA, non-interlaced PNG into a flat bytearray."""
    data = open(path, "rb").read()
    assert data[:8] == b"\x89PNG\r\n\x1a\n", path
    pos, idat, w, h = 8, b"", None, None
    while pos < len(data):
        length = struct.unpack(">I", data[pos:pos + 4])[0]
        tag = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if tag == b"IHDR":
            w, h, depth, colour, _, _, interlace = struct.unpack(">IIBBBBB", chunk)
            assert (depth, colour, interlace) == (8, 6, 0), (depth, colour, interlace)
        elif tag == b"IDAT":
            idat += chunk
        elif tag == b"IEND":
            break
    raw = zlib.decompress(idat)
    stride = w * 4
    out = bytearray(stride * h)
    prev = bytearray(stride)
    p = 0
    for y in range(h):
        filt = raw[p]
        p += 1
        line = bytearray(raw[p:p + stride])
        p += stride
        if filt == 1:
            for i in range(4, stride):
                line[i] = (line[i] + line[i - 4]) & 255
        elif filt == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 255
        elif filt == 3:
            for i in range(stride):
                left = line[i - 4] if i >= 4 else 0
                line[i] = (line[i] + ((left + prev[i]) >> 1)) & 255
        elif filt == 4:
            for i in range(stride):
                left = line[i - 4] if i >= 4 else 0
                up = prev[i]
                corner = prev[i - 4] if i >= 4 else 0
                pa, pb, pc = abs(up - corner), abs(left - corner), abs(left + up - 2 * corner)
                pred = left if (pa <= pb and pa <= pc) else (up if pb <= pc else corner)
                line[i] = (line[i] + pred) & 255
        elif filt != 0:
            raise ValueError(f"unknown PNG filter {filt}")
        out[y * stride:(y + 1) * stride] = line
        prev = line
    return w, h, out


def write_png(path, w, h, pixels):
    stride = w * 4
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        raw += pixels[y * stride:(y + 1) * stride]

    def chunk(tag, payload):
        return (struct.pack(">I", len(payload)) + tag + payload +
                struct.pack(">I", zlib.crc32(tag + payload) & 0xFFFFFFFF))

    ihdr = struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n")
        f.write(chunk(b"IHDR", ihdr))
        f.write(chunk(b"IDAT", zlib.compress(bytes(raw), 9)))
        f.write(chunk(b"IEND", b""))


def handle_distance(x, y):
    """Distance from (x, y) to the handle's centre line, minus its half-width."""
    x0 = CX + HANDLE_R0 * math.cos(ANGLE)
    y0 = CY + HANDLE_R0 * math.sin(ANGLE)
    x1 = CX + HANDLE_R1 * math.cos(ANGLE)
    y1 = CY + HANDLE_R1 * math.sin(ANGLE)
    vx, vy = x1 - x0, y1 - y0
    wx, wy = x - x0, y - y0
    t = max(0.0, min(1.0, (wx * vx + wy * vy) / (vx * vx + vy * vy)))
    return math.hypot(x - (x0 + t * vx), y - (y0 + t * vy)) - HANDLE_HALF


def mix(a, b, t):
    return a + (b - a) * t


def main():
    w, h, px = read_png(SRC)
    for y in range(h):
        for x in range(w):
            i = (y * w + x) * 4
            r, g, b = px[i], px[i + 1], px[i + 2]

            # positive = plate, negative = the mark itself, one pixel of feather
            d = min(math.hypot(x + 0.5 - CX, y + 0.5 - CY) - RING_R,
                    handle_distance(x + 0.5, y + 0.5))
            if d <= 0:
                continue

            luma = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
            t = max(0.0, min(1.0, (luma - LUMA_LO) / (LUMA_HI - LUMA_LO)))
            keep = min(1.0, d)
            px[i] = int(round(mix(r, mix(DARK_LO[0], DARK_HI[0], t), keep)))
            px[i + 1] = int(round(mix(g, mix(DARK_LO[1], DARK_HI[1], t), keep)))
            px[i + 2] = int(round(mix(b, mix(DARK_LO[2], DARK_HI[2], t), keep)))
    write_png(OUT, w, h, px)
    print(OUT)


if __name__ == "__main__":
    main()
