import unittest

from next_version import bump_version, next_release, parse_bump


class NextVersionTest(unittest.TestCase):
    def test_bump_levels(self):
        self.assertEqual(bump_version("0.1.4", "patch"), "0.1.5")
        self.assertEqual(bump_version("0.1.4", "minor"), "0.2.0")
        self.assertEqual(bump_version("0.1.4", "major"), "1.0.0")

    def test_highest_bump_wins(self):
        frags = ["bump: patch\n\n### Fixed\n- a", "bump: minor\n\n### Added\n- b"]
        self.assertEqual(next_release("1.2.3", frags)[0], "1.3.0")

    def test_no_fragments_means_no_release(self):
        self.assertIsNone(next_release("1.2.3", []))

    def test_notes_drop_the_bump_line(self):
        _, notes = next_release("0.0.0", ["bump: minor\n\n### Added\n- thing\n"])
        self.assertEqual(notes, "### Added\n- thing")

    def test_missing_bump_is_an_error(self):
        with self.assertRaises(ValueError):
            parse_bump("### Added\n- thing")


if __name__ == "__main__":
    unittest.main()
