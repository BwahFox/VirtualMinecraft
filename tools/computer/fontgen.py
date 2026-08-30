#!/usr/bin/env python3
"""Generates the Computer's two built-in bitmap fonts (ROADMAP §7h §3) into src/main/resources/virtualminecraft/:
font8x16.bin (256 glyphs, 16 rows of 1 byte, bit 7 = leftmost pixel) rendered from DejaVu Sans Mono (Bitstream
Vera licence: free to embed and derive), and font6x8.bin (256 glyphs, 8 rows, 6 pixels in the top bits) from
PIL's built-in bitmap font. Codepoints 0-255 are Latin-1; 0x80-0x9F carry the box-drawing/blocks set instead:
─│┌┐└┘├┤┬┴┼ ░▒▓█ ▀▄▌▐ so a text UI can draw frames without Unicode."""
import pathlib
from PIL import Image, ImageDraw, ImageFont

OUT = pathlib.Path(__file__).resolve().parents[2] / "src/main/resources/virtualminecraft"
BOX = "─│┌┐└┘├┤┬┴┼░▒▓█▀▄▌▐"  # 19 glyphs at 0x80..0x92
candidates = ["/usr/share/fonts/TTF/DejaVuSansMono.ttf", "/usr/share/fonts/dejavu/DejaVuSansMono.ttf",
              "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf"]

def glyph_char(cp):
    if 0x80 <= cp < 0x80 + len(BOX):
        return BOX[cp - 0x80]
    if cp < 0x20 or 0x80 <= cp < 0xA0:
        return " "
    return chr(cp)

def render(font, w, h, yoff):
    data = bytearray()
    for cp in range(256):
        img = Image.new("L", (w, h), 0)
        d = ImageDraw.Draw(img)
        ch = glyph_char(cp)
        d.text((0, yoff), ch, font=font, fill=255)
        px = img.load()
        for y in range(h):
            b = 0
            for x in range(w):
                if px[x, y] > 96:
                    b |= 0x80 >> x
            data.append(b)
    return bytes(data)

ttf = next((c for c in candidates if pathlib.Path(c).exists()), None)
if ttf:
    big = ImageFont.truetype(ttf, 14)
    print("8x16 from", ttf)
else:
    big = ImageFont.load_default()
    print("8x16 from PIL default (DejaVu not found)")
(OUT / "font8x16.bin").write_bytes(render(big, 8, 16, -1))
small = ImageFont.load_default()
(OUT / "font6x8.bin").write_bytes(render(small, 6, 8, -2))
print("wrote", OUT / "font8x16.bin", OUT / "font6x8.bin")
