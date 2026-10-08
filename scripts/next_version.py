#!/usr/bin/env python3
"""Compute the next release version from changelog.d fragments.

Usage: next_version.py <last-version> <fragment>...
Prints "<next-version>" on the first line and the combined release notes on
the remaining lines. Prints nothing and exits 0 when no fragment is given.

Each fragment starts with `bump: major|minor|patch`; the highest bump wins.
"""
import re
import sys

LEVELS = {"patch": 0, "minor": 1, "major": 2}
BUMP_RE = re.compile(r"^bump:\s*(major|minor|patch)\s*$", re.M)


def parse_bump(text):
    match = BUMP_RE.search(text)
    if not match:
        raise ValueError("fragment has no valid 'bump:' line")
    return match.group(1)


def bump_version(version, level):
    major, minor, patch = (int(p) for p in version.split("."))
    if level == "major":
        return f"{major + 1}.0.0"
    if level == "minor":
        return f"{major}.{minor + 1}.0"
    return f"{major}.{minor}.{patch + 1}"


def notes_of(text):
    return BUMP_RE.sub("", text, count=1).strip()


def next_release(last_version, fragments):
    """fragments: list of fragment texts. Returns (version, notes) or None."""
    if not fragments:
        return None
    level = max((parse_bump(f) for f in fragments), key=LEVELS.get)
    return bump_version(last_version, level), "\n\n".join(notes_of(f) for f in fragments)


def main(argv):
    last, paths = argv[1], argv[2:]
    texts = [open(p, encoding="utf-8").read() for p in paths]
    release = next_release(last, texts)
    if release:
        print(release[0])
        print(release[1])


if __name__ == "__main__":
    main(sys.argv)
