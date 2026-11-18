#!/usr/bin/env python3
"""Generates 16x16 block textures for the Backrooms mod. Stdlib only."""
import os, struct, zlib

OUT = "src/main/resources/assets/backrooms/textures/block"

def h2(x, y, s):
    n = (x * 374761393 + y * 668265263 + s * 1274126177) & 0xFFFFFFFF
    n = ((n ^ (n >> 13)) * 1274126177) & 0xFFFFFFFF
    return ((n ^ (n >> 16)) & 0xFF) / 255.0

def cl(v):
    return max(0, min(255, int(v)))

def png(name, fn):
    rows = b""
    for y in range(16):
        rows += b"\x00" + b"".join(bytes(cl(c) for c in fn(x, y)) for x in range(16))
    def chunk(t, d):
        c = t + d
        return struct.pack(">I", len(d)) + c + struct.pack(">I", zlib.crc32(c) & 0xFFFFFFFF)
    data = (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", 16, 16, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(rows))
            + chunk(b"IEND", b""))
    path = os.path.join(OUT, name)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(data)
    print("wrote", path)

# Yellow wallpaper: mono-yellow with mottled noise and faint vertical stripes
def wallpaper(x, y):
    n = h2(x, y, 1) * 24 - 12
    stripe = -18 if x % 8 == 0 else 0
    stain = 14 if h2(x // 4, y // 4, 2) > 0.82 else 0
    return (199 + n + stripe + stain, 178 + n + stripe + stain * 0.6, 106 + n + stripe + stain * 0.4, 255)

# Moist carpet: dull, heavily mottled yellow-brown
def carpet(x, y):
    n = h2(x, y, 3) * 44 - 22
    blotch = -20 if h2(x // 3, y // 3, 4) > 0.7 else 0
    return (138 + n + blotch, 123 + n + blotch, 79 + n + blotch, 255)

# Ceiling tile: pale with grid seams
def ceiling(x, y):
    n = h2(x, y, 5) * 10 - 5
    seam = -46 if (x % 8 == 0 or y % 8 == 0) else 0
    hole = -30 if h2(x, y, 6) > 0.93 else 0
    return (218 + n + seam + hole, 212 + n + seam + hole, 186 + n + seam + hole, 255)

# Fluorescent panel: bright tubes with darker frame
def light_panel(x, y):
    frame = x == 0 or y == 0 or x == 15 or y == 15
    if frame:
        return (196, 190, 158, 255)
    tube = 18 if y % 5 == 2 else 0
    return (245 + tube // 2, 244 + tube // 2, 214 + tube, 255)

png("yellow_wallpaper.png", wallpaper)
png("moist_carpet.png", carpet)
png("ceiling_tile.png", ceiling)
png("fluorescent_panel.png", light_panel)
