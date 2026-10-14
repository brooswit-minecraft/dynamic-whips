bump: patch

### Fixed
- WorldPoint ropes now detach safely when their anchor chunk is no longer loaded, without a
  block-state lookup reviving that chunk (MINECRAFT-274).

### Added
- Required 64-block deep-shaft GameTest coverage for public WorldPoint pay-out followed by
  reel-in, asserting material real player traversal rather than nominal rope length alone.

### Docs
- Documented the anchor-unload lifecycle guard and its headless-GameTest limitation in
  `docs/rope-core.md`.
