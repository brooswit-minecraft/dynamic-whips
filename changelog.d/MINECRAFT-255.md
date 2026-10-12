bump: patch

### Fixed
- Rope core (MINECRAFT-255): refuse creating a Sable/Rapier rope when either endpoint is
  non-finite or outside the conservative ±1,000,000 float32-coordinate envelope, preventing the
  placement-dependent `parry3d` BVH native panic from receiving the known unsafe geometry.

### Added
- Focused unit and required GameTest coverage for the coordinate guard, including the public
  `RopeManager` rejection path before a native Sable rope object is created.

### Docs
- `docs/rope-core.md` section 12 records the observed second native panic, the guard's float32
  rationale, and the remaining upstream uncertainty.
