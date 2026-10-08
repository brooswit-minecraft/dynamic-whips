import unittest

from check_neoforge_floor import floor_ok, template_uses_neo_version


class NeoForgeFloorTest(unittest.TestCase):
    def test_equal_or_older_floor_is_fine(self):
        self.assertTrue(floor_ok("21.1.250", "21.1.250"))
        self.assertTrue(floor_ok("21.1.200", "21.1.250"))

    def test_newer_floor_than_the_pack_fails(self):
        self.assertFalse(floor_ok("21.1.251", "21.1.250"))
        self.assertFalse(floor_ok("21.2.0", "21.1.250"))

    def test_numeric_not_lexicographic(self):
        self.assertTrue(floor_ok("21.1.99", "21.1.250"))
        self.assertFalse(floor_ok("21.1.1000", "21.1.250"))

    def test_template_must_derive_its_range_from_neo_version(self):
        good = '[[dependencies.${mod_id}]]\n    modId="neoforge"\n    type="required"\n    versionRange="[${neo_version},)"\n'
        bad = '[[dependencies.${mod_id}]]\n    modId="neoforge"\n    type="required"\n    versionRange="[21.1.251,)"\n'
        self.assertTrue(template_uses_neo_version(good))
        self.assertFalse(template_uses_neo_version(bad))


if __name__ == "__main__":
    unittest.main()
