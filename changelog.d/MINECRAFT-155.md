bump: patch

### Fixed
- `RopeGameTests#fallArrestStabilityNearOriginVsAtStructure` (MINECRAFT-127's `fallArrestSwing`
  near-origin probe): the at-structure rig passed the structure-RELATIVE anchor `BlockPos` to
  `RopeManager#attachToPoint` instead of `helper.absolutePos(...)` of it, so
  `RopeAnchor.WorldPoint#isGone` read air at that bare relative position (almost always true at
  this structure's real, far world location) and tore the rope down on the first tick — producing
  the constant, unarrested-free-fall numbers docs/rope-core.md section 10.4 flagged as suspect.
  Fixed to translate through `helper.absolutePos` like every other anchor `BlockPos` in this file.

### Added
- `RopeGameTests#tickCountVsPenetrationDepth`: samples near-origin post/wall penetration depth at
  50, 100, 170, 300 and 500 ticks in one run, discriminating slow-but-genuine contact resolution
  (depth shrinking toward zero) from a permanent tunnelling defect (depth plateauing nonzero) —
  the follow-up experiment docs/rope-core.md section 10.1c named but did not build.
- `RopeGameTests#tunnellingThresholdNegativeControl`: for each spacing `tunnellingThreshold`
  sweeps, builds a companion unmissable 3x3-wall rig at the same spacing, so a geometric miss and
  a genuine tunnel can be told apart at a given spacing — criterion 5's missing negative control.

### Docs
- `docs/rope-core.md` section 10: new subsections recording the above experiments' results and an
  updated, non-overclaimed headline verdict (see the PR description for the final wording, which
  depends on this PR's own CI run numbers).
