bump: patch

### Fixed
- `PlayerRope#payOut`/`reelIn` for a `RopeAnchor.WorldPoint` anchor: the player's REAL distance
  from the anchor now actually grows with `RopeManager.length()`'s own nominal number for a plain,
  unobstructed hang (MINECRAFT-189 bug 1) — root cause was `RopePhysicsObject#addPoint` always
  re-pinning the newest point to the fixed anchor location, collapsing the "extra" segment to
  zero usable length; fixed via `RopeHandle#setFirstSegmentLength`, an existing-but-previously-
  unused Sable API.
- Calling `RopeManager.payOut`/`reelIn` every single server tick no longer risks the native
  Rapier panic MINECRAFT-179's CI hit (MINECRAFT-189 bug 2, MITIGATED not root-caused — the exact
  native mechanism is still unidentified): the structural native mutation
  (`addPoint`/`removeFirstPoint`) is now paced inside `PlayerRope` itself, at most once every
  `RopeConstants#STRUCTURAL_COMMIT_INTERVAL_TICKS` ticks, regardless of caller cadence — no
  caller-side throttle required or relied upon any more.
- `RopeManager#adjustLength` reeling a 64-block rope to half length while obstructed no longer
  tears the rope down in the one CI-measured scenario this PR exercises (MINECRAFT-189 bug 5, the
  most severe finding): the previous implementation looped ~71 synchronous native point-removal
  calls in one tick; it now queues the delta and defers the throttled per-tick drain above while
  the chain is genuinely bent around an obstruction (`PlayerRope#ropeIsBentOnObstruction`). A
  rope that stays caught on an obstruction for its entire remaining life can, by design, never
  fully reel in past that point — documented, not hidden.

### Added
- `RopeGameTests#payOutGrowsAllowedRadiusForUnobstructedHang` and `#perTickPayOutAndReelInDoNotPanic`:
  `required = true` — reliable, no obstruction-timing dependency.
- `RopeGameTests#reelInCompletesNearGroundWhenUnobstructed`: `required = true` — regression guard
  proving the bug-5 guard does not stall ordinary reel-in for a rope merely resting near terrain.
- `RopeGameTests#reelInHalfLengthWhileObstructedAt64BlocksSurvives`: `required = false` — carries
  the same obstruction-timing nondeterminism `catchOnObstruction`/docs/rope-core.md section 10
  already document; see that section for how many runs this PR actually cites.

### Docs
- `docs/rope-core.md` section 11: root cause (read from decompiled Sable bytecode), the fix, CI
  evidence, the bounded backlog-latency tradeoff this introduces, and an honest investigation of
  whether bugs 3/4 (64-block collision/swing-constraint nondeterminism) share a mechanism with
  this fix (they do not appear to — both are observed in code paths this PR does not touch).
