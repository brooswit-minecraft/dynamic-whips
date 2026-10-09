bump: minor

### Added
- Leather Whip (MINECRAFT-86): a whip hit on a BLOCK now turns the hit point into a temporary
  rope anchor (`RopeManager#attachToPoint`) that lasts only while the player keeps holding the
  input. Release, switching away from the whip, and dropping it all detach it (all three are
  indistinguishable "the client stopped pinging" from the server's point of view — see
  `docs/whip.md` section 1), as does the rope core tearing the anchor down on its own (anchor
  block broken, chunk unload, death, logout, dimension change, server stop). No reel in or out:
  slack is fixed at attach time and the whip never calls `payOut`/`reelIn`/`adjustLength`. The
  entity-hit combat swing (reach, damage curve, cooldown) is unchanged — `WhipLogicTest` still
  pins it.
- `WhipHoldPingPayload` / `WhipClientInput` / `WhipHoldState`: a custom client→server heartbeat
  for "still holding," sent every client tick the use key is held with a whip in hand, with a
  server-side timeout (`WhipHoldState.TIMEOUT_TICKS`, 5 ticks) standing in for "released."
  Deliberately NOT built on vanilla's `startUsingItem`/`isUsingItem` — review caught that
  `LocalPlayer#aiStep` scales movement input to 20% unconditionally while `isUsingItem()` is true
  (the bow/shield draw-slowdown), with no NeoForge opt-out short of a Mixin, which would have cut
  "ordinary air control" (criterion 3) and momentum traversal (criterion 4) to a fifth of normal
  for the whole hold. See `docs/whip.md` section 1 for the full account.
- `WhipLogic.ANCHOR_SLACK` / `MAX_ANCHOR_ROPE_LENGTH`: the anchor's fixed slack and the resulting
  maximum rope length, kept well under the cheapest (16-block) grappling hook so the whip stays a
  skill toy and a weapon rather than a grappling gun.
- `WhipGameTests`: headless coverage, through the real `WhipItem#use` (not `RopeManager` called
  directly), for the block anchor's attach/no-reel/ping-timeout-detach cycle, the anchor block
  breaking while held, and a fall-arrest-and-swing demonstration.
- `docs/whip.md`: the acceptance-criteria-to-evidence table and the manual procedures for what
  CI cannot cover headless (a real client's steering while holding, entity tether — cut, see
  below — and the felt "skill toy" quality of the swing).

### Changed
- `RopeGameTests`' mock-player/gravity-simulation helpers moved to a shared `GameTestSupport`
  class (`rope/gametest/`) so `WhipGameTests` does not duplicate them.
- CI's GameTest step now counts `@GameTest` methods across the whole `dynamicwhips` source tree
  instead of one named file, since there are now two GameTest classes.

### Removed
- Entity tether (criterion 5, explicitly lower priority in both the spec and MINECRAFT-86): cut
  from this story. The shipped combat half already treats every entity hit as an instant,
  reviewed-and-approved swing; layering a hold-to-tether mode onto that same input would mean
  redesigning when an entity hit swings versus holds, which risks re-litigating the approved
  combat behavior this story was told not to touch. See `docs/whip.md` and the PR description.
