bump: patch

### Fixed
- `PlayerRope#payOut`/`reelIn` for a `RopeAnchor.WorldPoint` anchor: the player's REAL distance
  from the anchor now actually grows with `RopeManager.length()`'s own nominal number for a plain,
  unobstructed hang (MINECRAFT-189 bug 1) — root cause was `RopePhysicsObject#addPoint` always
  re-pinning the newest point to the fixed anchor location, collapsing the "extra" segment to
  zero usable length; fixed via `RopeHandle#setFirstSegmentLength`, an existing-but-previously-
  unused Sable API.
- Calling `RopeManager.payOut`/`reelIn` every single server tick no longer risks the native
  Rapier panic MINECRAFT-179's CI hit (MINECRAFT-189 bug 2): the structural native mutation
  (`addPoint`/`removeFirstPoint`) is now paced inside `PlayerRope` itself, at most once every
  `RopeConstants#STRUCTURAL_COMMIT_INTERVAL_TICKS` ticks, regardless of caller cadence — no
  caller-side throttle required or relied upon any more.
- `RopeManager#adjustLength` reeling a 64-block rope to half length while obstructed no longer
  tears the rope down (MINECRAFT-189 bug 5, the most severe finding): the previous implementation
  looped ~71 synchronous native point-removal calls in one tick; it now queues the delta and lets
  the same throttled per-tick drain above apply it gradually.

### Added
- `RopeGameTests#payOutGrowsAllowedRadiusForUnobstructedHang`,
  `#perTickPayOutAndReelInDoNotPanic`, `#reelInHalfLengthWhileObstructedAt64BlocksSurvives`: CI
  coverage for the three fixes above, all `required = false` for now (see docs/rope-core.md
  section 11.5 on why).

### Docs
- `docs/rope-core.md` section 11: root cause (read from decompiled Sable bytecode), the fix, CI
  evidence, the bounded backlog-latency tradeoff this introduces, and an honest investigation of
  whether bugs 3/4 (64-block collision/swing-constraint nondeterminism) share a mechanism with
  this fix (they do not appear to — both are observed in code paths this PR does not touch).
