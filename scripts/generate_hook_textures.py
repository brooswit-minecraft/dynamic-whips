#!/usr/bin/env python3
"""Generates placeholder 16x16 item icons for the three grappling hook tiers.

The Confluence spec (page 49872898) explicitly leaves "final names, art, animation, sounds, and
input polish" to the team — these are simple, reviewable, regenerable placeholders (a hook-shaped
silhouette in each tier's own material color), not final art. Run with:
    python3 scripts/generate_hook_textures.py
"""
import os

from PIL import Image

OUT_DIR = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources",
                        "assets", "dynamicwhips", "textures", "item")

# (handle color, hook-head color) per tier, chosen to read clearly against vanilla's own
# inventory background and to echo each material's own vanilla item-icon palette.
TIERS = {
    "iron_hook": ((108, 108, 108), (216, 216, 216)),
    "diamond_hook": ((60, 160, 170), (110, 226, 236)),
    "netherite_hook": ((54, 44, 52), (92, 78, 90)),
}

# A simple diagonal hook silhouette: a handle (bottom-left) and a curled hook head (top-right),
# drawn as explicit pixel coordinates so the shape is reviewable as source, not opaque binary.
HANDLE_PIXELS = [(2, 13), (3, 12), (4, 11), (5, 10), (6, 9)]
HEAD_PIXELS = [
    (7, 8), (8, 7), (9, 6), (10, 5), (11, 5), (12, 6), (12, 7), (11, 8), (10, 8), (9, 7),
]


def build(handle_color, head_color):
    image = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for xy in HANDLE_PIXELS:
        image.putpixel(xy, (*handle_color, 255))
    for xy in HEAD_PIXELS:
        image.putpixel(xy, (*head_color, 255))
    return image


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    for name, (handle_color, head_color) in TIERS.items():
        path = os.path.join(OUT_DIR, f"{name}.png")
        build(handle_color, head_color).save(path)
        print(f"wrote {path}")


if __name__ == "__main__":
    main()
