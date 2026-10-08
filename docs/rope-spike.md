# Rope spike: can Dynamic Whips build on Sable ropes? (MINECRAFT-67)

**Verdict: INCONCLUSIVE, leaning GO for the physics, with two gaps Dynamic Whips must fill itself (player coupling and rendering/sync).**

Everything below comes from reading the Sable 2.0.5 jar (`sable-neoforge-1.21.1-2.0.5.jar`, the version the Sickos pack pins) at the bytecode level: there is no source or javadoc in the jar, and no JDK/decompiler was used on this machine. The Rapier solver itself is a native library (`sable_rapier_binaries`), so none of its behaviour was read. Nothing here has been run in a game. The spike code in this PR is the test harness for the open questions; the exact tests are listed at the end.

## 1. How to create a rope

Public API, package `dev.ryanhcode.sable.api.physics.object.rope`, wired exactly as Sable's own `/sable spawn rope_test` command does it (`SableSpawnCommands.executeSpawnRopeTestCommand`):

```java
SubLevelPhysicsSystem system = SubLevelPhysicsSystem.get(serverLevel);          // null if Sable has no system here
RopePhysicsObject rope = new RopePhysicsObject(pointsCollection, 0.25);         // Collection<Vector3d>, collision radius
system.addObject(rope);                                                         // onAddition -> pipeline.addRope -> RopeHandle
rope.setAttachment(RopeHandle.AttachmentPoint.START, worldPos, null);           // null sub level = fixed world point
```

Facts established from the bytecode:

- A rope is an ordered list of points (`rope.getPoints()`, an unmodifiable `ObjectList<Vector3d>`). Sable's test command makes 10 points and radius 0.25.
- **All segments have the same rest length**, taken from the distance between points 0 and 1 at creation (`RapierRopeHandle.create` calls `Rapier3D.createRope(scene, radius, distance(p0,p1), double[] positions, count)`). So total length is fixed by spacing times (n-1) and the spawn layout must be evenly spaced.
- `AttachmentPoint.START` / `END` pin an end either to a world position (`sublevel == null`) or to a Sable `ServerSubLevel` (a physics body, id from `Rapier3D.getID`). `setAttachment` can be called repeatedly to move the pin.
- `RopeHandle` also exposes `addPoint` / `removeFirstPoint` / `setFirstSegmentLength` (grow, shrink, and pay out or reel in at the START end) and `wakeUp`. Reel in/out for hooks maps onto `setFirstSegmentLength` plus `removeFirstPoint`/`addPoint`; the START end is the one that moves, so the hook anchor should be START and the player END, or the roles swapped with the player at START.
- `RopePhysicsObject.updatePose()` is run for every arbitrary object each physics tick (`SubLevelPhysicsSystem.updateAllPoses`), so `getPoints()` on the server thread is current after each tick.
- Tick hooks exist: `ForgeSablePrePhysicsTickEvent` / `ForgeSablePostPhysicsTickEvent` (NeoForge events carrying the `SubLevelPhysicsSystem` and time step).
- Lifetime: `onUnloaded` and `onRemoved` both call `remove()`. Ropes are **not persisted**; they vanish on chunk unload or restart. That suits tool ropes.

## 2. Does intermediate collision with world blocks work?

Evidence it is intended and plausible (not proof):

- `RopePhysicsObject.getBoundingBox` expands to every point +/- `collisionRadius`.
- `PhysicsChunkTicketManager.wouldBeLoaded(level, ArbitraryPhysicsObject)` expands that box by 1, converts to chunk bounds, and checks the world chunks under it are loaded. `SubLevelPhysicsSystem.handleBlockChange` wakes arbitrary objects whose box contains a changed block (`wakeUpObjectsAt`). Both mean world block colliders are uploaded to Rapier around ropes, which only makes sense if rope points collide with them. Sable's own world physics (sub levels landing on terrain) uses the same voxel collider path.
- The rope is a particle chain with a per-point `collisionRadius` (0.25 in Sable's test), so a 1 block post is 2 to 4 times the particle diameter. That matches the spec's argument that Minecraft obstacles are large relative to segments.

Not verifiable without running:

- That rope points collide with static *world* blocks and not only with sub-level bodies. Strongly implied above, but the solver is native code.
- Whether segments (as opposed to points) collide. If only the points do, a segment can still cut a corner when spacing exceeds about 2x radius. The spike uses 16 segments over the rope length, so spacing is 1 to 2 blocks for a 16 to 32 block rope, which is far above 0.5 diameter. **Expect tunnelling through thin obstacles at hook-scale lengths unless spacing is held near 0.5 block** (about 32 points per 16 blocks, 128 per 64 blocks for the netherite hook). This is the main risk to the "use the physical rope directly" plan.

## 3. The acceptance scenario

Anchored to a building, post below and between, player falls, rope catches and swings around the post.

Needs verifying in game (test plan below). Predicted: with dense enough spacing the particles pile on the post and the rope bends, but **nothing in Sable transfers rope tension to the player**. The player is not a physics body in Sable; there is no entity attachment (only world point or sub level). So the player must be coupled by Dynamic Whips:

- Pin the rope END to the player every tick (what the spike does). This makes the player a kinematic anchor for the rope: the rope bends around the post visually, but the player is **not** pulled back by the rope, and the rope's real length is not enforced on the player.
- To get the swing, Dynamic Whips must read the points each tick and apply the constraint to the player itself: take the last rope point that is not in line of sight (the effective pivot), and clamp or project the player onto the sphere of radius (remaining rope length) around that pivot, removing the outward velocity component. That is the same maths as a hand-rolled raycast-and-pivot rope, except the pivots come from the solver. Rope points could also be sampled for tension (distance between the last two points versus rest length) to scale the pull.

So Sable ropes can supply "where does the rope bend around obstacles" for free, but not the swing force. Whether that is worth it versus a raycast pivot rope is the real decision, and depends on section 2's tunnelling result.

## 4. Stability and performance

Not measurable here. What the code shows: each rope is a native Rapier scene object; every `getPoints()` refresh is one JNI `queryRope` returning a flat `double[]`; ropes wake and sleep (`wakeUp`, `wakeUpObjectsAt`). Costs to measure: point count (up to about 128 for the 64 block hook at 0.5 spacing), number of simultaneous ropes (every player with a whip or hook), and per-tick `setAttachment` calls from the END pin. Test: `/dynamicwhips rope anchor` with 1, 5 and 20 players, 17 points now and (once spacing is a parameter) 33 and 129 points, watching tick time with `/tick query` or spark.

Known fragility to test: pinning the END to a fast moving player every tick is a moving kinematic constraint and can inject energy into a taut rope (jitter or explosive correction). The `slack` argument on the debug command exists to compare taut and slack ropes.

## 5. Multiplayer sync

Finding (negative): **Sable has no rope rendering or networking.** The only classes that mention ropes are the physics pipeline API, the Rapier handle, the `rope_test` command and unrelated entity-leash rendering. There is no packet carrying rope points to clients and no client renderer. Ropes exist server-side only and must be synced and drawn by Dynamic Whips: a custom packet with the point list (about 3 floats per point, 33 to 129 points, at 20 Hz or delta-compressed and sent only to nearby tracking players) and a client renderer (a ribbon or line strip through the interpolated points). The spike substitutes server-side `CRIT` particles at every rope point every other tick, which any nearby player sees, as the cheap debug visual.

## 6. Dependency and licence notes

- Sable is `PolyForm Shield 1.0.0` licensed (its mod metadata and `LICENSE.md`). Compiling against it and requiring it at runtime is what every addon does; the Shield licence restricts offering a *competing product*. Worth a quick human check before Dynamic Whips is published on Modrinth.
- Required dependency declared in `neoforge.mods.toml`: `sable [2.0.5,)`, the pack pin. Sable needs NeoForge `[21.1.228,)`; the scaffold is on 21.1.250.
- Sable is not on a maven repository in the sibling mods' setup, so the build downloads the pinned Modrinth jar and verifies its sha512 (the same hash as `sickos/mods/sable.pw.toml`), mirroring how dynamic-vehicles consumes dynamic-terrain. `compileOnly`: the dev run configs do not launch Sable, so the debug command can be exercised only in a real instance (see below).

## 7. Tests that settle the open questions

Run in a modded 1.21.1 instance with Sable 2.0.5 and this mod's jar from CI (op player):

1. **World collision exists.** Stand beside a wall, look at a point on the far side of a 1 block pillar, `/dynamicwhips rope anchor 1.5`. Walk so the straight line passes through the pillar. Pass: the particle chain bends around the pillar and `max deviation` in the action bar rises well above 0. Fail: particles pass through the block.
2. **Acceptance scenario.** Anchor to the side of a tall building, place a 1 block post below and between, then fall from the roof level. Pass: the chain wraps the post while you fall. (Player pull-back is not expected from the spike; section 3.)
3. **Tunnelling threshold.** Repeat test 1 at slack 1.0 versus 3.0 and across rope lengths 8, 16, 32. Record the longest segment length that still catches a 1 block pillar and a 1 block thin slab or fence.
4. **Stability.** Run the rope taut and swing/sprint while pinned; watch for jitter or explosions in the point positions and in the log lines tagged `[rope-spike]`.
5. **Performance.** With 1, 5 and 20 players each anchored, compare MSPT versus baseline.
6. **Multiplayer.** Second client in view range should see the particle chain; confirm no rope data otherwise (expected).
