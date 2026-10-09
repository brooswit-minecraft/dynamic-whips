bump: patch

### Fixed
- Rope core (MINECRAFT-85 re-review follow-up): `catchOnObstruction`'s `closestWithPost > 0.05`
  lower bound is removed (epic-authorised) — a flush, non-penetrating rest (`closest=0.0`,
  `clipped=false`) now correctly counts as a catch instead of a failure. Nothing else in that
  assertion, or the `clippedWithPost`/control-rig/frozen-guard assertions around it, changed.
  `catchOnObstruction` is now `required = true`.
- The `required = false` rationale above `catchOnObstruction` and the frozen-assertion's own
  failure message no longer blame "Sable's solver" for not stepping rope points — both were
  disproved by the `updatePose()` fix and now name the real, fixed cause. `tunnellingThreshold`
  stays `required = false`, re-justified on its own merits as a diagnostic sweep for the still-
  open criterion-5 question rather than a second gate duplicating `catchOnObstruction`.

### Docs
- `docs/rope-core.md`: records the epic decision above as a judgment call with its reasoning, not
  a quiet loosening; states plainly that criterion 5 (the tunnelling threshold) is still open and
  that the sweep's wider-spacing `caught=false, clipped=false` results most likely mean the rope
  misses the post geometrically, not that it tunnels; and flags that criterion 7's performance
  section still has no filled-in figures to mark stale, since the manual measurement procedure
  has never actually been run — any future measurement must anchor to 142 points (not 129) and
  account for `points()` calling `updatePose()` on every read with no per-tick cache.
