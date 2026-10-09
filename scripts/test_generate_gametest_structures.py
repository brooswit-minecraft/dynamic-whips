import importlib.util
import unittest
from pathlib import Path

_SPEC = importlib.util.spec_from_file_location(
    "generate_gametest_structures",
    Path(__file__).resolve().parent / "generate_gametest_structures.py",
)
gen = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(gen)


class GenerateGametestStructuresTest(unittest.TestCase):
    def test_solid_floor_covers_whole_footprint(self):
        blocks = gen.solid_floor(3, 4, 0)
        self.assertEqual(len(blocks), 12)
        self.assertTrue(all(pos[1] == 0 for pos, _ in blocks))
        self.assertEqual({(x, z) for (x, _, z), _ in blocks},
                          {(x, z) for x in range(3) for z in range(4)})

    def test_structure_bytes_are_deterministic(self):
        blocks = gen.solid_floor(2, 2, 0)
        first = gen.structure((2, 3, 2), blocks, ["minecraft:stone"])
        second = gen.structure((2, 3, 2), blocks, ["minecraft:stone"])
        self.assertEqual(first, second)

    def test_structure_root_tag_is_unnamed_compound(self):
        data = gen.structure((1, 1, 1), [], ["minecraft:air"])
        # TAG_Compound (0x0a), then a 2-byte big-endian name length of 0 (unnamed root).
        self.assertEqual(data[:3], b"\x0a\x00\x00")

    def test_structure_declares_pinned_data_version(self):
        data = gen.structure((1, 1, 1), [], ["minecraft:air"])
        # TAG_Int (0x03) field named "DataVersion" (11 chars) must appear near the start.
        name_field = b"\x03\x00\x0bDataVersion"
        self.assertIn(name_field, data)


if __name__ == "__main__":
    unittest.main()
