bump: minor

### Added
- Leather Whip (MINECRAFT-86): a whip hit on a BLOCK now turns the hit point into a temporary
  rope anchor (`RopeManager#attachToPoint`) that lasts only while the player keeps holding the
  input. Releasing detaches immediately (an explicit packet, not a timeout); switching away from
  the whip or dropping it detaches immediately too (a server-side check, independent of the
  client); both fall back to a short ping-silence timeout if a packet is lost — see
  `docs/whip.md` section 1. The rope core tearing the anchor down on its own (anchor block
  broken, chunk unload, death, logout, dimension change, server stop) is covered too. No reel in
  or out: slack is fixed at attach time and the whip never calls
  `payOut`/`reelIn`/`adjustLength`. The entity-hit combat swing (reach, damage curve, cooldown)
  is unchanged — `WhipLogicTest` still pins it.
- `WhipHoldPingPayload` / `WhipReleasePayload` / `WhipClientInput` / `WhipHoldState`: a custom
  client→server heartbeat for "still holding," sent every client tick the use key is held with a
  whip in hand, plus an explicit release payload sent once on the key-up edge so letting go at
  the apex of a swing detaches immediately rather than waiting out the ping-silence timeout
  (`WhipHoldState.TIMEOUT_TICKS`, 5 ticks — now a fallback for a lost packet/disconnect, not the
  primary release path). `WhipHoldState#tickTimeouts` also independently re-checks every tick
  that the owning player still has a whip in the recorded hand, so switching items or dropping
  the whip detaches immediately without depending on the client sending anything at all.
  Deliberately NOT built on vanilla's `startUsingItem`/`isUsingItem` — review caught that
  `LocalPlayer#aiStep` scales movement input to 20% unconditionally while `isUsingItem()` is true
  (the bow/shield draw-slowdown), with no NeoForge opt-out short of a Mixin, which would have cut
  "ordinary air control" (criterion 3) and momentum traversal (criterion 4) to a fifth of normal
  for the whole hold. See `docs/whip.md` section 1 for the full account.
- `WhipLogic.ANCHOR_SLACK` / `MAX_ANCHOR_ROPE_LENGTH`: the anchor's fixed slack and the resulting
  maximum rope length, kept well under the cheapest (16-block) grappling hook so the whip stays a
  skill toy and a weapon rather than a grappling gun.
- `WhipGameTests`: headless coverage, through the real `WhipItem#use` (not `RopeManager` called
  directly), for the block anchor's attach/no-reel cycle, explicit release, switching items while
  still pinging (proving the server-side hand check catches it, not the timeout), ping-silence
  timeout, the anchor block breaking while held, and a fall-arrest-and-swing demonstration.
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
