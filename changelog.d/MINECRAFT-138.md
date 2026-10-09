bump: patch

### Fixed
- Rope core diagnosis (MINECRAFT-127): `RopeGameTests#distanceToColumn` now returns a SIGNED
  distance to the post's AABB (negative = penetration depth) instead of clamping every inside
  case to `0.0`. The old clamp made a tunnelling point (`clippedWithPost=true`) log the exact
  same `closest=0.0` a clean, flush catch does — not a measurement of distance-to-surface once
  the point is inside. `closestPointToColumn`/`tunnellingThreshold`'s sweep now read the signed
  value; no assertion's pass/fail threshold changed, since every outside-the-box case is
  numerically identical to before.

### Added
- `RopeGameTests#closestDistanceMetricIsDefensible`: hand-computed geometry assertions pinning
  `distanceToColumn`/`isInsideColumn` against an outside case, an on-the-face case, and a
  penetrating case that must not report `0.0` — the defect-3 regression canary.
- `RopeGameTests#controlRigStabilityNearOriginVsAtStructure` (required=false): builds the same
  control-only catch rig both at this structure's own (previously measured far-from-origin)
  coordinates and at hardcoded near-origin absolute coordinates in the same run, logging both
  resting-x values to test the float32-ulp rig-instability hypothesis empirically.
