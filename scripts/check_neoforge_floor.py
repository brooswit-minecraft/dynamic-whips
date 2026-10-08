#!/usr/bin/env python3
"""Fail if this mod's minimum NeoForge is newer than the one the Sickos pack
runs. A mod that requires a newer loader than the server has crashes the whole
server at startup ("Mod X requires neoforge A or above, currently B").

The minimum comes from `neo_version` in gradle.properties, which the
neoforge.mods.toml template expands into its dependency range. The pack's
version comes from `neoforge` in the Sickos pack.toml (override the URL with
PACK_TOML_URL, or pass a local file as the first argument).
"""
import os
import re
import sys
import urllib.request
from pathlib import Path

PACK_URL = os.environ.get(
    "PACK_TOML_URL", "https://raw.githubusercontent.com/brooswit-minecraft/sickos/main/pack.toml")
ROOT = Path(__file__).resolve().parent.parent


def parse_version(text):
    return tuple(int(part) for part in text.strip().split("."))


def floor_ok(mod_floor, pack_version):
    """The mod may require anything up to, and including, the pack's NeoForge."""
    return parse_version(mod_floor) <= parse_version(pack_version)


def find(pattern, text, what):
    match = re.search(pattern, text, re.M)
    if not match:
        raise SystemExit(f"could not find {what}")
    return match.group(1)


def template_uses_neo_version(template_text):
    return re.search(r'modId="neoforge"[^\[]*versionRange="\[\$\{neo_version\},\)"', template_text, re.S) is not None


def main(argv):
    mod_floor = find(r"^neo_version=(\S+)", (ROOT / "gradle.properties").read_text(), "neo_version in gradle.properties")
    template = (ROOT / "src/main/templates/META-INF/neoforge.mods.toml").read_text()
    if not template_uses_neo_version(template):
        raise SystemExit("neoforge.mods.toml must declare its NeoForge range as [${neo_version},) so this check sees the real floor")
    if len(argv) > 1:
        pack_text = Path(argv[1]).read_text()
    else:
        with urllib.request.urlopen(PACK_URL, timeout=30) as response:
            pack_text = response.read().decode()
    pack_version = find(r'^neoforge\s*=\s*"([^"]+)"', pack_text, "neoforge in pack.toml")
    if not floor_ok(mod_floor, pack_version):
        raise SystemExit(f"FAIL: this mod requires NeoForge >= {mod_floor} but the Sickos pack runs {pack_version}; "
                         "the server would refuse to start. Lower neo_version in gradle.properties.")
    print(f"OK: mod requires NeoForge >= {mod_floor}, pack runs {pack_version}")


if __name__ == "__main__":
    main(sys.argv)
