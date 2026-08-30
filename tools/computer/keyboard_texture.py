#!/usr/bin/env python3
"""The keyboard block's placeholder textures (ROADMAP §9 U4.3, the keyboard block). Script-drawn from scratch
like the bridge and cash register placeholders, so nothing about the third-party-art rule is engaged; [name]'s
to replace whenever she likes.

    python3 tools/computer/keyboard_texture.py

Writes keyboard_top.png and keyboard_side.png into src/main/resources/assets/virtualminecraft/textures/block/.

Orientation note for the top texture: the model maps the up face with default uv, so v=0 (the TOP rows of the
PNG) is the block's NORTH edge — and facing=north means the player who placed it stands to the north. The space
bar therefore sits in the top rows of the image, nearest the typist, even though that looks upside-down in an
image viewer.
"""
from pathlib import Path

from PIL import Image, ImageDraw

BLOCK = Path(__file__).resolve().parents[2] / "src/main/resources/assets/virtualminecraft/textures/block"

BED = (0x2A, 0x2A, 0x2E)          # the case around the keys
KEY = (0x9A, 0x9A, 0xA2)          # keycap top
KEY_EDGE = (0x6E, 0x6E, 0x76)     # keycap shadow


def top():
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    # the model's up face maps uv (1,4)..(15,12): the keyboard's whole top lives in that window
    d.rectangle((1, 4, 14, 11), fill=BED)
    # space bar row nearest the typist (v min = north = the placing player's side)
    d.rectangle((4, 5, 10, 5), fill=KEY)
    d.point((11, 5), fill=KEY_EDGE)  # a stub of Enter
    d.point((2, 5), fill=KEY_EDGE)   # ...and of a modifier
    # three rows of keys, 1px caps with 1px gaps
    for ky in (7, 9, 11):
        for kx in range(2, 14, 2):
            d.point((kx, ky), fill=KEY)
            d.point((kx + 1, ky), fill=KEY_EDGE)
    im.save(BLOCK / "keyboard_top.png")


def side():
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    # the model uses rows 14..15 for the 2px-tall sides and rows 4..12 for the underside
    d.rectangle((0, 0, 15, 15), fill=BED)
    d.rectangle((0, 14, 15, 14), fill=(0x3A, 0x3A, 0x40, 255))  # a lighter top edge so the slab reads as bevelled
    im.save(BLOCK / "keyboard_side.png")


if __name__ == "__main__":
    top()
    side()
    print("wrote", BLOCK / "keyboard_top.png", "and keyboard_side.png")
