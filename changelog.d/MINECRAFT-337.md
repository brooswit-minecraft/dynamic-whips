bump: patch

### Fixed
- Rope core (MINECRAFT-337): let ordinary `RopeManager.reelIn` continue through its existing
  paced native commits when the obstruction-pivot heuristic produces a false positive on an
  unobstructed deep-shaft rope. The conservative deferral remains for negative work queued by
  `RopeManager.adjustLength`, the only API path with the measured obstructed 64-block teardown.
