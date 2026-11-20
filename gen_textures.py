#!/usr/bin/env python3
"""Generates the 16x16 textures for the Backrooms mod. Stdlib only.

Every block texture is seamless: stripes wrap at 16 and the blotch layer is
value noise on a grid that divides 16. Block textures keep one seam per face
and no border, so stacked blocks never double the line.

Run from the repo root: writes the assets plus build/texture_preview/ (3x3
tiling, 1:1 and mip-map simulations) for eyeballing.
"""
import os, struct, zlib

BLOCK = "src/main/resources/assets/backrooms/textures/block"
ITEM = "src/main/resources/assets/backrooms/textures/item"
PREVIEW = "build/texture_preview"


def h2(x, y, s):
    n = (x * 374761393 + y * 668265263 + s * 1274126177) & 0xFFFFFFFF
    n = ((n ^ (n >> 13)) * 1274126177) & 0xFFFFFFFF
    return ((n ^ (n >> 16)) & 0xFF) / 255.0


def vnoise(x, y, cell, seed):
    """Smooth value noise on a wrapping grid of 16/cell cells."""
    n = 16 // cell
    fx, fy = x / cell, y / cell
    ix, iy = int(fx) % n, int(fy) % n
    tx, ty = fx - int(fx), fy - int(fy)
    tx = tx * tx * (3 - 2 * tx)
    ty = ty * ty * (3 - 2 * ty)
    a = h2(ix, iy, seed)
    b = h2((ix + 1) % n, iy, seed)
    c = h2(ix, (iy + 1) % n, seed)
    d = h2((ix + 1) % n, (iy + 1) % n, seed)
    return (a * (1 - tx) + b * tx) * (1 - ty) + (c * (1 - tx) + d * tx) * ty


def cl(v):
    return max(0, min(255, int(v)))


def png_bytes(px):
    h, w = len(px), len(px[0])
    rows = b""
    for y in range(h):
        rows += b"\x00" + b"".join(bytes(cl(c) for c in px[y][x]) for x in range(w))

    def chunk(t, d):
        c = t + d
        return struct.pack(">I", len(d)) + c + struct.pack(">I", zlib.crc32(c) & 0xFFFFFFFF)

    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(rows))
            + chunk(b"IEND", b""))


def save(path, px):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(png_bytes(px))
    print("wrote", path)


def tex(fn):
    return [[fn(x, y) for x in range(16)] for y in range(16)]


# Yellow wallpaper: warm chalky base, 2 px grasscloth ribs, damp blotches.
def wall(base, seed, stain, tint, rough=4):
    def f(x, y):
        d = 4 if x % 2 == 0 else -4
        d += int(h2(x, y, seed) * 6 - 3)
        d += int(vnoise(x, y, 8, seed + 2) * rough - rough // 2)
        if h2(x, y, seed + 3) > 0.93:
            d -= 7
        s = max(0.0, vnoise(x, y, 8, seed + 1) - 0.45) * stain
        k = s / stain
        return (cl(base[0] + d - s + tint[0] * k), cl(base[1] + d - s + tint[1] * k),
                cl(base[2] + d - s * 1.6 + tint[2] * k), 255)
    return f


# Moist carpet: 2 px loop pile, diagonal sheen, single dark fibres. No large
# blotches: an 8 px field on a 16 px face reads as a visible 2x2 tile grid.
def carpet(x, y):
    base = (140, 122, 72)
    d = int(h2((x + (y // 2 % 2) * 2) // 2, y // 2, 21) * 8 - 4)
    if h2(x, y, 22) > 0.85:
        d -= 8
    elif h2(x, y, 24) > 0.90:
        d += 5
    if (x + y) % 4 == 0 and h2(x, y, 25) > 0.5:
        d += 3
    return (cl(base[0] + d), cl(base[1] + d), cl(base[2] + d), 255)


# Ceiling tile: one seam per face on the north/west edge plus a lit bevel lip and
# a regular pinhole grid. The seam sits on x=0/y=0 only, so two stacked tiles
# show a single 1 px line instead of a doubled one.
def ceiling(x, y):
    base = (214, 210, 186)
    if x == 0 or y == 0:
        d = -20
    elif x == 1 or y == 1:
        d = 6
    else:
        d = int(h2(x, y, 32) * 4 - 2)
        if x % 4 == 1 and y % 4 == 1:
            d -= 10
        elif h2(x, y, 31) > 0.9:
            d -= 5
    return (cl(base[0] + d), cl(base[1] + d), cl(base[2] + d), 255)


# Fluorescent panel: recessed troffer. Two 2 px tubes with hot cores, a dark gap
# between them and a shadowed edge, so it reads as a fixture and not a flat blob.
PANEL_ROWS = [200, 226, 255, 240, 210, 190, 210, 240, 255, 226, 200, 186]


def panel(x, y):
    if x in (0, 15) or y in (0, 15):
        return (108, 106, 94, 255)
    if x in (1, 14) or y in (1, 14):
        return (150, 148, 128, 255)
    v = PANEL_ROWS[y - 2] - max(0, 2 - min(x - 2, 13 - x)) * 4
    return (cl(v), cl(v), cl(v - 6), 255)


# Almond water: Victoria's Kitchen bottle from the reference photo. 16 px cannot
# hold the label text, so the wordmark is a two-row dark block, the ribbon a
# light band and the almond flower two bright pixels.
ALMOND = [
    "................",
    ".....bbbbbb.....",
    ".....acbcbd.....",
    "......hggp......",
    "......gffp......",
    "......gffp......",
    ".....hefffi.....",
    "....heffffhi....",
    "....heffffhi....",
    "....hejjjjki....",
    "....hellllli....",
    "....hellllki....",
    "....hmmmmmmi....",
    "....hejjjnoi....",
    ".....hggggi.....",
    "................",
]
ALMOND_PAL = {
    ".": (0, 0, 0, 0),
    "a": (242, 242, 236, 255), "b": (216, 216, 208, 255),
    "c": (188, 188, 180, 255), "d": (150, 150, 142, 255),
    "e": (246, 238, 202, 255), "f": (236, 226, 178, 255),
    "g": (226, 214, 164, 255), "h": (200, 188, 138, 255),
    "i": (170, 158, 112, 255), "j": (240, 230, 186, 255),
    "k": (214, 202, 158, 255), "l": (120, 52, 34, 255),
    "m": (250, 240, 198, 255), "n": (252, 248, 232, 255),
    "o": (138, 90, 43, 255), "p": (188, 176, 132, 255),
}


def upscale(px, k):
    return [[px[y // k][x // k] for x in range(len(px[0]) * k)] for y in range(len(px) * k)]


def tile(px, n):
    h, w = len(px), len(px[0])
    return [[px[y % h][x % w] for x in range(w * n)] for y in range(h * n)]


def shrink(px, k):
    h, w = len(px), len(px[0])
    out = []
    for y in range(h // k):
        row = []
        for x in range(w // k):
            cell = [px[y * k + j][x * k + i] for j in range(k) for i in range(k)]
            row.append(tuple(sum(p[c] for p in cell) // len(cell) for c in range(4)))
        out.append(row)
    return out


def over(px, bg):
    return [[tuple(px[y][x][c] if px[y][x][3] else bg[c] for c in range(3)) + (255,) for x in range(len(px[0]))]
            for y in range(len(px))]


def seam_report(name, px):
    """Wrapped edge step against the mean interior step: a high ratio means the
    design breaks at the block border."""
    h, w = len(px), len(px[0])

    def step(a, b):
        return sum(abs(a[c] - b[c]) for c in range(3))

    wrap = (sum(step(px[y][w - 1], px[y][0]) for y in range(h))
            + sum(step(px[h - 1][x], px[0][x]) for x in range(w))) / (h + w)
    inner = (sum(step(px[y][x], px[y][x + 1]) for y in range(h) for x in range(w - 1))
             + sum(step(px[y][x], px[y + 1][x]) for y in range(h - 1) for x in range(w))) / (2 * h * w - h - w)
    print(f"  {name:18s} edge step {wrap:5.1f}  inner step {inner:5.1f}")


def main():
    wall_yellow = tex(wall((204, 196, 140), 11, 28, (6, 2, -10)))
    wall_red = tex(wall((126, 44, 38), 41, 18, (8, -4, -6), rough=2))
    textures = {
        "yellow_wallpaper": wall_yellow,
        "red_wallpaper": wall_red,
        "moist_carpet": tex(carpet),
        "ceiling_tile": tex(ceiling),
        "fluorescent_panel": tex(panel),
    }
    for name, px in textures.items():
        save(os.path.join(BLOCK, name + ".png"), px)

    almond = [[ALMOND_PAL[ch] for ch in row] for row in ALMOND]
    assert len(almond) == 16 and all(len(r) == 16 for r in almond), "almond map must be 16x16"
    assert all(almond[y][x][3] == 0 for y in (0, 15) for x in (0, 15)), "almond corners must be empty"
    save(os.path.join(ITEM, "almond_water.png"), almond)

    print("checks")
    for name, px in textures.items():
        seam_report(name, px)
    # The ceiling carries a seam by design; the opposite edge must stay clear or
    # two stacked blocks would show a double line.
    gap = sum(abs(ceiling(0, y)[c] - ceiling(15, y)[c]) for y in range(16) for c in range(3))
    print(f"  ceiling border gap {gap // 16} (must be > 40, dark seam vs lit edge)")
    assert gap // 16 > 40, "ceiling seam doubles at the block border"

    for name, px in textures.items():
        save(os.path.join(PREVIEW, name + "_3x3.png"), upscale(tile(px, 3), 4))
        save(os.path.join(PREVIEW, name + "_mip8.png"), upscale(shrink(px, 2), 8))
        save(os.path.join(PREVIEW, name + "_mip4.png"), upscale(shrink(px, 4), 16))
    save(os.path.join(PREVIEW, "almond_water_x12.png"), upscale(over(almond, (40, 40, 44)), 12))


if __name__ == "__main__":
    main()
