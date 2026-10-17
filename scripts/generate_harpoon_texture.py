#!/usr/bin/env python3
"""Generates a placeholder 16x16 item icon for the harpoon (MINECRAFT-88).

Same spirit as scripts/generate_hook_textures.py: a simple, reviewable, regenerable placeholder
(a spear-like silhouette — straight shaft and a barbed, arrow-like tip, deliberately distinct from
the hooks' curled-hook-head silhouette so the two item families read differently at a glance), not
final art. Run with:
    python3 scripts/generate_harpoon_texture.py
"""
import os

from PIL import Image

OUT_DIR = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources",
                        "assets", "dynamicwhips", "textures", "item")

SHAFT_COLOR = (120, 84, 48)      # wooden shaft
HEAD_COLOR = (200, 200, 208)     # steel head
ROPE_COLOR = (170, 140, 90)      # coiled rope at the grip end

SHAFT_PIXELS = [(3, 12), (4, 11), (5, 10), (6, 9), (7, 8), (8, 7), (9, 6), (10, 5)]
HEAD_PIXELS = [
    (11, 4), (12, 3), (13, 2), (12, 4), (13, 4), (11, 5), (10, 4),
]
ROPE_PIXELS = [(2, 13), (3, 13), (2, 12), (1, 13)]


def build():
    image = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for xy in ROPE_PIXELS:
        image.putpixel(xy, (*ROPE_COLOR, 255))
    for xy in SHAFT_PIXELS:
        image.putpixel(xy, (*SHAFT_COLOR, 255))
    for xy in HEAD_PIXELS:
        image.putpixel(xy, (*HEAD_COLOR, 255))
    return image


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    path = os.path.join(OUT_DIR, "harpoon.png")
    build().save(path)
    print(f"wrote {path}")


if __name__ == "__main__":
    main()
