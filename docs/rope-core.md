# Rope core: player-attached Sable rope (MINECRAFT-85 / MINECRAFT-90)

This builds on the spike in `docs/rope-spike.md` (read that first — it covers how Sable's
`RopePhysicsObject` works and why the player must be coupled to it manually). This document
covers what this story's implementation actually does, what the headless GameTests in
`RopeGameTests` (`src/main/java/.../rope/gametest/RopeGameTests.java`) assert versus what is
inferred by reading code, and the open questions a human still needs to settle.

**How to read the "result" sections below:** every one is written to be filled in from a real CI
run of `.github/workflows/ci.yml`'s "Run GameTests headless" step, not reasoned about. Until that
run has actually happened for a given commit, treat any specific number here as **PENDING —
awaiting first green/red CI run**, and check the PR's Checks tab for the authoritative, current
result rather than trusting a stale copy of this file.

## 0. CI history (read this before trusting any green run)

The first two CI runs on this PR did not exercise `RopeGameTests` at all, and both are worth
recording because neither failed loudly:

1. **Run 1** (`build.gradle` config error): `gameTestServer { setForceExit false }` called a
   method that does not exist on ModDevGradle's `RunModel` — the build failed evaluating
   `build.gradle` before any task ran. Fixed by removing that line (it came from stale guidance,
   not this plugin's actual DSL).
2. **Run 2** (silent false-green): with that fixed, `./gradlew runGameTestServer` reported
   `BUILD SUCCESSFUL` — but the dedicated server's own log showed it crashed during mod loading
   (`Mod dynamicwhips requires sable 2.0.5 or above / Currently, sable is not installed`) before a
   single GameTest could register. Sable is `compileOnly` (correct for the shipped jar, which must
   not bundle or depend on Sable at the Gradle-dependency level), and that also meant it was absent
   from every dev run — and the `gameTestServer` Gradle task still exits 0, because its exit code
   is the count of failed *required tests*, and zero tests ran. **A green `runGameTestServer`
   step does not by itself mean any test executed** — always check the step's own log for the
   test registration/run lines (or an explicit failure like this one), not just the job's pass/fail
   badge.
3. **Run 3** (first attempted fix, also wrong): added Sable to `additionalRuntimeClasspath`. Same
   crash. That configuration puts a jar on the raw Java classpath, which is correct for a plain
   library but does nothing for FML's own mod-file discovery on 1.21.1 — Sable is a real mod (its
   own `neoforge.mods.toml`), not a library, so FML never found it there either.
4. **Fix that actually worked**: a `copySableToRunMods` task copies the downloaded Sable jar into
   the run directory's own `mods/` folder before every `run*` task, the same way a player installs
   it by hand — `ModsFolderLocator` (FML's normal mod-discovery path) picks it up from there. See
   `build.gradle`.
5. **Run 4** (first real GameTest execution — Sable now loads): all 6 tests registered and
   ticked, then all 6 failed identically: `Payload sable:dimension_physics may not be sent to the
   client!`, thrown from inside `PlayerList#placeNewPlayer`, called by
   `GameTestHelper#makeMockServerPlayerInLevel()`. That method runs the full real player-join
   login pipeline, which fires every mod's join listeners — including Sable's own broadcast of its
   physics data to the newly joined player. The fake connection that pipeline builds never
   negotiates mod network channels the way a real client does, so NeoForge's `NetworkRegistry`
   refuses to send it.
6. **Run 5** (tried: catch the exception and recover the already-placed player) — did not work.
   Wrapping the `makeMockServerPlayerInLevel()` call in a try/catch and recovering the player via
   `helper.getLevel().players()` still left every test reported as failed with the identical
   message: whatever ends up swallowing or re-raising that exception along the way, catching it at
   the call site is not sufficient to stop the GameTest framework itself from recording the test
   as failed.
7. **Run 6** (tried: `helper.makeMockPlayer(GameType.SURVIVAL)` cast to `ServerPlayer`) — did not
   work either, but for a different, simpler reason: the cast itself fails.
   `ClassCastException: class net.minecraft.gametest.framework.GameTestHelper$1 cannot be cast to
   class net.minecraft.server.level.ServerPlayer`. `makeMockPlayer`'s declared return type
   (`Player`) is not a lie of convenience — the concrete object really is an internal
   `GameTestHelper`-only mock type, not a `ServerPlayer`, so it can never satisfy
   `RopeManager`'s `ServerPlayer`-typed API.
8. **Fix that actually worked**: build the `ServerPlayer` by hand —
   `new ServerPlayer(server, level, gameProfile, ClientInformation.createDefault())` — and add it
   with `ServerLevel#addFreshEntity`, the same ordinary entity-add any mob uses. This skips
   `PlayerList` entirely, so there is no login pipeline to fire Sable's join broadcast, and no
   `ClassCastException` because the object is a real `ServerPlayer`. The one thing it does not
   have is a network connection (`player.connection` stays null, since nothing ever runs the
   handshake that creates one) — `RopeNetworking#sendSync` now checks for that explicitly and
   falls back to `sendToPlayersTrackingEntity` (skipping the "and self" send) when it is null, so
   the automatic per-tick sync this story's own lifecycle code triggers doesn't NPE against a mock
   player with no connection. See `RopeGameTests#spawnMockPlayer`.

Once a run shows GameTests actually registering and ticking, the sections below get filled in
from its log.

## 1. The central gap: coupling a player to a Sable rope

Sable's `RopePhysicsObject` only pins its `START`/`END` to a world point or a sub level — never to
an entity, and it applies no force back to anything. `PlayerRope` (the class) closes this gap:

- The rope's `END` attachment is re-pinned to the player's bounding-box center every tick (a
  kinematic follow, matching the spike's own debug command).
- Separately, `PlayerRope#tick` reads the rope's own second-to-last point (`points.size() - 2`) as
  the **pivot** — the point closest to the player that Sable's solver has already bent around any
  obstruction — and clamps the player onto the sphere of radius `SEGMENT_SPACING` around that
  pivot, removing only the outward-radial component of velocity (`RopeMath#swingCorrection`).
  Inside the radius, nothing happens: the player free-falls exactly as if there were no rope,
  satisfying "gravity and momentum only, no reel control, no teleport toward the anchor."

Using the solver's own second-to-last point as the pivot (rather than the raw anchor) is what
turns "the chain visually bends around a post" into "the player actually swings around the post":
see acceptance criterion 2 below for whether that bend actually happens at all.

## 2. Catch-on-obstruction result (criterion 2 — the spec scenario)

**GameTest:** `RopeGameTests#catchOnObstruction`, structure `catch_on_obstruction.nbt`. Anchor
above, a one-block-wide stone post standing between the anchor and the player's fall line, player
dropped from the opposite side. Asserts, after 170 ticks: (a) at least one of the rope's own points
settles within `2 * COLLISION_RADIUS` of the post, and (b) the player's final position is on the
post's side of the shaft rather than hanging straight under the anchor.

**Result: PENDING — awaiting first CI run of this PR.** Per the ticket, if this comes back RED,
that is the criterion-2 answer, not a bug to work around: do not add a raycast-and-pivot fallback.
A red result here means Sable's rope does not actually collide with world blocks the way
`docs/rope-spike.md` section 2 predicted from the bytecode (the bounding-box/chunk-loading
evidence was suggestive, never proof) — report that finding on MINECRAFT-90 verbatim from the CI
log, not as "it probably doesn't work."

*(Fill in once CI has run: "GREEN at commit `<sha>`, see run `<url>`" or "RED at commit `<sha>`:
<the exact assertion failure message from the CI log>".)*

## 3. Tunnelling threshold (criterion 5)

**GameTest:** `RopeGameTests#tunnellingThreshold`, structure `tunnelling_threshold.nbt`. Three
copies of the catch-on-obstruction rig, far enough apart that Sable's per-rope physics objects
cannot interact across bays, one rope per bay at `SEGMENT_SPACING` (the shipped 0.5-block value),
`2x` (1.0) and `4x` (2.0) that. The test requires the shipped spacing to still catch the post; the
wider spacings are logged (`[rope-core] tunnelling threshold sweep: ...`) but not asserted on,
since nothing ships at those spacings.

**Result: PENDING — awaiting first CI run.** Read the `[rope-core] tunnelling threshold sweep`
log line from the "Run GameTests headless" step and transcribe the three `caught=`/`closest=`
values here:

- spacing 0.5 (shipped): *pending*
- spacing 1.0: *pending*
- spacing 2.0: *pending*

The spike's own prediction (section 2) was that tunnelling starts once spacing exceeds roughly
`2x` the collision radius (i.e. somewhere around 1.0 block, since `COLLISION_RADIUS = 0.25`) —
this sweep is what either confirms or corrects that number against the actual Rapier solver.

## 4. Lifecycle (criterion 4)

Covered by three GameTests (`detachOnRequestRemovesRope`, `breakingAnchorBlockDetachesRope`,
`deathDetachesAllOwnedRopes`), each a headless, CI-run assertion — not inferred from reading code:

- **Detach on request**: `RopeManager#detach` removes the rope from the registry and Sable's own
  physics system synchronously.
- **Anchor block broken**: `RopeAnchor.WorldPoint#isGone` checks the block is air; `RopeManager#tickAll`
  (which runs every server tick via the `ServerTickEvent.Post` hook) tears the rope down the next
  tick with no explicit action from the caller.
- **Player death**: `DynamicWhipsMod` wires `LivingDeathEvent` to `RopeManager#detachAllOwnedBy`.
  The GameTest posts a real `LivingDeathEvent` through the live NeoForge event bus (not a direct
  method call) so it exercises that wiring itself, not just the underlying API.

**Not covered by a GameTest, and why:** logout (`PlayerLoggedOutEvent`), dimension change
(`PlayerChangedDimensionEvent`) and server stop (`ServerStoppingEvent`) are wired the identical way
(`DynamicWhipsMod` → `RopeManager#detachAllOwnedBy` / `#clearAll`), but a headless GameTest server
has no real client connection to log out, no second dimension transition to drive through a mock
player, and asserting "server stop cleaned up" is inherently untestable from inside the server
being stopped. These three are read-the-code confidence, not CI-asserted confidence: the wiring is
structurally identical to the three paths that ARE asserted (same `RopeManager` methods, same
event-bus pattern), so the main residual risk is NeoForge firing one of these events differently
than expected — not a logic error in `RopeManager` itself.

**Chunk unload**: `PlayerRope#isLive` also checks `rope.isActive()` — Sable's own `onUnloaded()`
deactivates the physics object, which `RopeManager#tickAll` notices and tears the mod's own side
down on the same pass as the anchor-gone check. Not independently GameTested (would need to force
a chunk unload under a live rope inside the test's own loaded region, which the test framework
keeps loaded by construction); read-the-code confidence only, same caveat as above.

## 5. Named constants (criterion 5)

All in `RopeConstants`, each with its rationale in its own javadoc: `COLLISION_RADIUS` (0.25, from
the spike's own exercised value), `SEGMENT_SPACING` (0.5, from the spike's tunnelling prediction —
see section 3 above for the actual measured threshold), `MIN_POINTS`/`MAX_POINTS`/`MAX_LENGTH`
(129 points derived from the 64-block Netherite Hook spec), `CONSTRAINT_INTERVAL_TICKS` (1 — Sable
owns the physics timestep itself; this is only how often *this mod's* player-coupling correction
re-applies), `SYNC_INTERVAL_TICKS` (4, i.e. 5 Hz — see section 6), `SWING_SLACK` (0.02, floating
point noise tolerance at full extension).

## 6. Packet design rationale (criterion 3)

Two payloads, both in `rope/net/`: `RopeSyncPayload` (rope id, owner id, flattened `float[3n]`
point list, server→client) and `RopeRemovePayload` (rope id only, sent on any teardown).

- **Shape**: a flat float array rather than a structured per-point record, because the point
  count varies per rope (up to 129 for a 64-block rope) and NeoForge's `StreamCodec` has no
  built-in variable-length record list primitive cheaper than hand-rolling the same
  varint-length-then-loop this uses. Floats, not doubles: rope points are a rendering concern at
  that stage, and a swinging rope's visual deviation from float precision is far below one pixel
  at any realistic render distance.
- **Audience**: `PacketDistributor.sendToPlayersTrackingEntityAndSelf(owner, ...)` — every client
  tracking the owning player, plus the owner's own client, so a second player watching someone else
  swing sees the identical sync stream the swinging player's own client receives. This directly
  targets "correct for a second player watching someone else swing."
- **Rate**: `SYNC_INTERVAL_TICKS = 4` (5 Hz), chosen over the spike's 20 Hz particle rate because a
  line renderer interpolates cheaply between updates (`ClientRopeState` keeps the previous and
  current snapshot and linearly interpolates by partial tick in `RopeRenderer`), while a swinging
  rope's silhouette changes slowly relative to camera motion. 5 Hz is an order of magnitude below
  the packet budget this mod expects (a handful of ropes per player, never one per tick), and the
  interpolator hides the coarser update rate visually. Not independently GameTested — this is a
  design choice justified here, not a behavior the headless server can observe (there is no real
  client in a GameTest run to measure visual smoothness against).

## 7. Internal API for future callers (criterion 6)

`RopeManager` is the only class whip/hook/harpoon items should ever touch:
`attachToPoint(ServerPlayer, Vec3, BlockPos, slack)` (whip anchor, grappling hook),
`attachToEntity(ServerPlayer, Entity, slack)` (harpoon), `detach(ropeId)`, `length(ropeId)`,
`adjustLength(ropeId, newLength)`, `payOut(ropeId)`, `reelIn(ropeId)`. `payOut`/`reelIn` exist and
are exercised indirectly through `adjustLength` but no item wires them up yet, per the ticket's
explicit scope. `attachToPointWithSpacing` is test-support only (see section 3) — not for item use.

## 8. Performance at 64 blocks (criterion 7)

**Not GameTested.** A meaningful tick-cost measurement needs either a long-running load (tens of
seconds of steady-state ticking with `/tick query` or a profiler attached) or multiple concurrent
simulated players, neither of which fits inside a single headless GameTest's tick budget or
produces a trustworthy number under CI's shared, variable-load runners. Guessing a number here
would violate the ticket's own instruction not to assert a performance claim nobody measured.

**Manual procedure for a human to run, once, in a real instance:**

1. Join a dev server with this mod and Sable loaded.
2. Anchor a 64-block rope (129 points at `SEGMENT_SPACING`) to a fixed point using the existing
   `/dynamicwhips rope anchor <slack>` debug command (extend it with a length argument if it does
   not already accept one at the commit under test — check before assuming).
3. With 1 rope active, run `/tick query` (or attach spark) for 60 seconds at rest, then again while
   actively swinging, and record average and p99 MSPT versus a no-rope baseline.
4. Repeat with 5 and 20 concurrent 64-block ropes (multiple players or, for a rough proxy, multiple
   ropes anchored to the same player's position) to find the point count at which MSPT crosses an
   unacceptable threshold (e.g. sustained server tick > 50ms).
5. Record: segment count per rope (129 at the shipped spacing), ropes active, MSPT at each count,
   and the ceiling found.

*(Fill in once a human has actually run this procedure — do not estimate.)*

## 9. Unsettled / open questions

- Whether Sable's rope truly collides with world blocks at all (section 2) — this PR's CI run is
  the first real evidence either way; the spike's bytecode reading was never proof.
- The exact tunnelling threshold (section 3) — pending the sweep's first CI run.
- Logout / dimension-change / server-stop teardown (section 4) is wired identically to the three
  CI-asserted paths but is not itself CI-asserted; see that section for why.
- Criterion 7's performance numbers are a documented manual procedure, not a CI result (section 8).
- `RopeAnchor.EntityAnchor` (the harpoon's future anchor type) is implemented and used by
  `attachToEntity`, but has no GameTest of its own — no consumer exists yet to motivate one, and
  the ticket's scope is explicitly "do not wire any item up."
