#!/usr/bin/env python3
"""The tier ladder's textures (ROADMAP §9 U3b): the two extra cases are derived from the Computer's beige
textures, the twelve part items are drawn from scratch. Rerun after touching the Computer's textures.

    python3 tools/computer/textures.py

Writes into src/main/resources/assets/virtualminecraft/textures/{block,item}/.
"""
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2] / "src/main/resources/assets/virtualminecraft/textures"
BLOCK = ROOT / "block"
ITEM = ROOT / "item"

BEIGE_EDGE = (0xB2, 0xA8, 0x92)
LED_GREEN = {(0x48, 0xC8, 0x60), (0x96, 0xFF, 0xAA)}


def recolour(im, fn):
    out = im.copy()
    px = out.load()
    for y in range(out.height):
        for x in range(out.width):
            r, g, b, a = px[x, y]
            px[x, y] = (*fn(r, g, b, x, y), a)
    return out


def basic(r, g, b, x, y):
    """A warmer, browner beige: the breadbin. The LED goes red."""
    if (r, g, b) in LED_GREEN:
        return (0xE0, 0x40, 0x40) if r == 0x48 else (0xFF, 0x90, 0x90)
    return (min(255, int(r * 1.0)), int(g * 0.90), int(b * 0.72))


def advanced(r, g, b, x, y):
    """A dark grey tower. The LED goes blue."""
    if (r, g, b) in LED_GREEN:
        return (0x40, 0x90, 0xFF) if r == 0x48 else (0xA0, 0xD0, 0xFF)
    v = (r + g + b) // 3
    v = int(v * 0.42) + 18
    return (v, v, v + 4)


def cases():
    for face in ("front", "side", "top", "back"):
        src = Image.open(BLOCK / f"lua_computer_{face}.png").convert("RGBA")
        b = recolour(src, basic)
        a = recolour(src, advanced)
        if face == "front":
            d = ImageDraw.Draw(b)
            # the Basic Computer's keyboard strip over the vent block: brown keys on a darker bed
            d.rectangle((2, 11, 13, 14), fill=(0x5A, 0x48, 0x38, 255))
            for ky in (12, 14):
                for kx in range(3, 13, 2):
                    d.point((kx, ky), fill=(0x8A, 0x76, 0x60, 255))
            d = ImageDraw.Draw(a)
            # the Advanced Computer's second drive slot under the first
            d.rectangle((2, 6, 13, 7), fill=(0x1A, 0x1A, 0x1E, 255))
            d.rectangle((3, 6, 12, 6), fill=(0x30, 0x30, 0x36, 255))
        b.save(BLOCK / f"basic_computer_{face}.png")
        a.save(BLOCK / f"advanced_computer_{face}.png")


BADGE = [(0xC0, 0x70, 0x30), (0xC8, 0xC8, 0xD0), (0xF0, 0xC0, 0x40)]  # I bronze, II silver, III gold


def badge(d, level):
    c = BADGE[level - 1]
    d.rectangle((12, 12, 15, 15), fill=(*c, 255))
    d.rectangle((12, 12, 15, 15), outline=(0x20, 0x20, 0x20, 255))


def ram(level):
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rectangle((1, 4, 14, 10), fill=(0x2E, 0x7D, 0x32, 255), outline=(0x1B, 0x4D, 0x1E, 255))
    for cx in range(3, 12, 3):
        d.rectangle((cx, 6, cx + 1, 8), fill=(0x10, 0x10, 0x10, 255))
    for x in range(2, 14):
        d.point((x, 11), fill=(0xD4, 0xAF, 0x37, 255) if x % 2 == 0 else (0xB8, 0x90, 0x20, 255))
    badge(d, level)
    return im


def cpu(level):
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    for i in range(3, 13, 2):
        d.point((i, 1), fill=(0xC0, 0xC0, 0xC0, 255))
        d.point((i, 14), fill=(0xC0, 0xC0, 0xC0, 255))
        d.point((1, i), fill=(0xC0, 0xC0, 0xC0, 255))
        d.point((14, i), fill=(0xC0, 0xC0, 0xC0, 255))
    d.rectangle((2, 2, 13, 13), fill=(0x3A, 0x3A, 0x40, 255), outline=(0x18, 0x18, 0x1C, 255))
    d.rectangle((5, 5, 10, 10), fill=(0x58, 0x58, 0x62, 255))
    d.point((6, 6), fill=(0x80, 0x80, 0x8A, 255))
    badge(d, level)
    return im


def graphics(level):
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rectangle((1, 2, 2, 13), fill=(0xA0, 0xA0, 0xA8, 255))  # the bracket
    d.rectangle((3, 3, 14, 11), fill=(0x2E, 0x7D, 0x32, 255), outline=(0x1B, 0x4D, 0x1E, 255))
    d.rectangle((5, 5, 8, 8), fill=(0x10, 0x10, 0x10, 255))  # the chip
    d.ellipse((9, 4, 13, 8), fill=(0x50, 0x50, 0x58, 255), outline=(0x30, 0x30, 0x34, 255))  # the fan
    for x in range(4, 14):
        d.point((x, 12), fill=(0xD4, 0xAF, 0x37, 255) if x % 2 == 0 else (0xB8, 0x90, 0x20, 255))
    badge(d, level)
    return im


def drive(level):
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rectangle((1, 3, 14, 12), fill=(0x8A, 0x8A, 0x90, 255), outline=(0x50, 0x50, 0x56, 255))
    d.ellipse((3, 4, 10, 11), fill=(0x40, 0x40, 0x46, 255), outline=(0x28, 0x28, 0x2C, 255))
    d.point((6, 7), fill=(0xA0, 0xA0, 0xA8, 255))
    d.rectangle((11, 5, 13, 10), fill=(0xD8, 0xD8, 0xDC, 255))  # the label
    badge(d, level)
    return im


def parts():
    for name, fn in (("ram", ram), ("cpu", cpu), ("graphics", graphics), ("drive", drive)):
        for level in (1, 2, 3):
            fn(level).save(ITEM / f"{name}_{level}.png")


if __name__ == "__main__":
    cases()
    parts()
    print("wrote", BLOCK, ITEM)
