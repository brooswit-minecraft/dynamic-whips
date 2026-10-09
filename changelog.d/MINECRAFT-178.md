bump: patch

### Added
- `RopeGameTests#hookScalePenetrationDepth`: samples penetration depth (signed, section-10 metric)
  for a near-origin 1-wide post and 3x3 wall at hook scale — 16, 32 and 64 blocks (64 at slack 1.1
  = 142 points, the Netherite Hook's own documented worked example) — at 50, 100, 170, 300 and 500
  ticks, directly comparable to section 10.8's ~7-block-rig numbers.
- `RopeGameTests#hookScaleSwingConstraintHeld`: reports whether the player swing constraint still
  holds (distance from anchor within rest length + tolerance) at each hook length, alongside a
  control (no-obstacle) rig's own resting distance.
- `RopeGameTests#hookScaleReelInWhileObstructed`: records penetration before and after reeling a
  hook-scale rope in to half its length (`RopeManager#adjustLength`) while obstructed — the
  shortening behaviour grappling hooks actually use.
- `RopeGameTests#hookScaleFarFromOrigin`: a deliberate far-coordinate (x=8,000,000) vs near-origin
  comparison at the 64-block length, for both the post and the wall, in the same run.
- All four are `required = false` (measurement, not a new gate), per the ticket's own scope.

### Docs
- `docs/rope-core.md`: new subsection with the measured hook-scale numbers, each labelled
  CI-asserted vs inferred, and a review of prior sections' certainty language against them.
