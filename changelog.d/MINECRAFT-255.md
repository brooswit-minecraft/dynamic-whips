bump: patch

### Fixed
- Rope core (MINECRAFT-255): prevent dynamic Sable/Rapier rope resizing when any current point is
  non-finite or outside the conservative ±1,000,000 float32-coordinate envelope. This prevents
  the placement-dependent `parry3d` BVH native panic's known unsafe growth path without rejecting
  established static ropes at far GameTest placements.

### Added
- Focused unit and required GameTest coverage for the coordinate guard, including public
  `RopeManager` resize refusal at an already-far GameTest placement.

### Docs
- `docs/rope-core.md` section 12 records the observed second native panic, the guard's float32
  rationale, and the remaining upstream uncertainty.
