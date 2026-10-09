#!/usr/bin/env python3
"""Generates the minimal structure .nbt templates RopeGameTests needs.

A GameTest's structure template only has to declare the play-field's size and
any fixed blocks (a floor, a post); everything else (the mock player, the
rope) is spawned by the test method itself. Hand-authoring these in-game with
a structure block and exporting them is the normal path; this script does the
same job byte-for-byte so the templates are reviewable source instead of
opaque binary blobs, and regenerable if a test's dimensions change.

Run with: python3 scripts/generate_gametest_structures.py
Output: src/main/resources/data/dynamicwhips/structure/*.nbt (gzip'd NBT,
the same format `/structure save` writes, DataVersion pinned to 1.21.1).
"""
import gzip
import os
import struct

DATA_VERSION = 3955  # Minecraft 1.21.1

TAG_END = 0
TAG_BYTE = 1
TAG_INT = 3
TAG_STRING = 8
TAG_LIST = 9
TAG_COMPOUND = 10
TAG_INT_ARRAY = 11


class Writer:
    def __init__(self):
        self.buf = bytearray()

    def byte(self, v):
        self.buf += struct.pack(">b", v)

    def ushort(self, v):
        self.buf += struct.pack(">H", v)

    def int(self, v):
        self.buf += struct.pack(">i", v)

    def string(self, s):
        encoded = s.encode("utf-8")
        self.ushort(len(encoded))
        self.buf += encoded

    def tag_header(self, tag_type, name):
        self.byte(tag_type)
        self.string(name)


def write_value(w, tag_type, value):
    if tag_type == TAG_INT:
        w.int(value)
    elif tag_type == TAG_STRING:
        w.string(value)
    elif tag_type == TAG_COMPOUND:
        write_compound_body(w, value)
    elif tag_type == TAG_LIST:
        element_type, items = value
        w.byte(element_type)
        w.int(len(items))
        for item in items:
            write_value(w, element_type, item)
    elif tag_type == TAG_INT_ARRAY:
        w.int(len(value))
        for v in value:
            w.int(v)
    else:
        raise ValueError(f"unsupported tag type {tag_type}")


def write_compound_body(w, entries):
    """entries: list of (tag_type, name, value), in insertion order."""
    for tag_type, name, value in entries:
        w.tag_header(tag_type, name)
        write_value(w, tag_type, value)
    w.byte(TAG_END)


def write_root_compound(entries):
    w = Writer()
    # Root tag: TAG_Compound with empty name, per the structure NBT format.
    w.tag_header(TAG_COMPOUND, "")
    write_compound_body(w, entries)
    return bytes(w.buf)


def block_entry(pos, palette_index):
    # A single item of a TAG_Compound list: just the compound's own entries,
    # not wrapped in the (tag_type, name, value) shape a named field uses.
    return [
        (TAG_LIST, "pos", (TAG_INT, list(pos))),
        (TAG_INT, "state", palette_index),
    ]


def palette_entry(name):
    return [
        (TAG_STRING, "Name", name),
    ]


def structure(size, blocks, palette_names):
    """blocks: list of (pos, palette_index) using indices into palette_names."""
    palette = [palette_entry(name) for name in palette_names]
    block_list = [block_entry(pos, idx) for pos, idx in blocks]
    entries = [
        (TAG_INT, "DataVersion", DATA_VERSION),
        (TAG_LIST, "size", (TAG_INT, list(size))),
        (TAG_LIST, "entities", (TAG_COMPOUND, [])),
        (TAG_LIST, "blocks", (TAG_COMPOUND, block_list)),
        (TAG_LIST, "palette", (TAG_COMPOUND, palette)),
    ]
    return write_root_compound(entries)


def solid_floor(size_x, size_z, y, block="minecraft:stone"):
    """One Y-layer of `block` covering the whole X/Z footprint, index 0 in the palette."""
    return [((x, y, z), 0) for x in range(size_x) for z in range(size_z)]


def write_structure(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with gzip.GzipFile(path, "wb", mtime=0) as f:
        f.write(data)
    print(f"wrote {path} ({len(data)} raw bytes)")


def main():
    out_dir = os.path.join(
        os.path.dirname(__file__), "..",
        "src", "main", "resources", "data", "dynamicwhips", "structure",
    )

    # fall_arrest_swing (criterion 1): a 7x14x7 shaft, stone floor at y=0 as a
    # safety net (the test fails anyway if the rope lets the player reach it),
    # otherwise air. The test method places the anchor block and spawns the
    # mock player itself at relative coordinates inside this volume.
    size = (7, 14, 7)
    blocks = solid_floor(size[0], size[2], 0)
    write_structure(
        os.path.join(out_dir, "fall_arrest_swing.nbt"),
        structure(size, blocks, ["minecraft:stone"]),
    )

    # catch_on_obstruction (criterion 2, the spec scenario): two 9-wide bays
    # side by side. Bay 0 (x 0-8) gets the post; bay 1 (x 9-17) is an
    # unobstructed control with identical anchor/player geometry, so a pass
    # distinguishes "the rope caught on the post" from "that's just where an
    # unobstructed pendulum ends up" (see RopeGameTests#catchOnObstruction).
    size = (18, 16, 9)
    blocks = solid_floor(size[0], size[2], 0)
    write_structure(
        os.path.join(out_dir, "catch_on_obstruction.nbt"),
        structure(size, blocks, ["minecraft:stone"]),
    )

    # lifecycle (criterion 4): small shaft, one stone anchor block the test
    # breaks mid-test, stone floor.
    size = (5, 8, 5)
    blocks = solid_floor(size[0], size[2], 0)
    write_structure(
        os.path.join(out_dir, "lifecycle.nbt"),
        structure(size, blocks, ["minecraft:stone"]),
    )

    # tunnelling_threshold (criterion 5): three copies of the catch_on_obstruction rig
    # side by side (9-block-wide bays at x-origin 0, 9, 18), far enough apart that
    # Sable's per-rope physics objects cannot interact across bays. RopeGameTests runs
    # one rope per bay at a different segment spacing and checks which ones still catch
    # the post, to find the spacing where tunnelling starts.
    bay_width = 9
    bay_count = 3
    size = (bay_width * bay_count, 16, 9)
    blocks = solid_floor(size[0], size[2], 0)
    write_structure(
        os.path.join(out_dir, "tunnelling_threshold.nbt"),
        structure(size, blocks, ["minecraft:stone"]),
    )

    # hook_shaft: an earlier MINECRAFT-87 revision used this for a deep-shaft pay-out/reel-in
    # GameTest, removed after it crashed Sable's native Rapier layer unpredictably depending on
    # this structure's own randomly assigned world placement (see docs/hooks.md section 5 and
    # HookGameTests' own trailing comment). No test references this template any more; not
    # regenerated.


if __name__ == "__main__":
    main()
