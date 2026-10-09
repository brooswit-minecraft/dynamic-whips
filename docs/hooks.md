# Grappling hooks: Iron, Diamond, Netherite (MINECRAFT-87 / MINECRAFT-179)

Spec of record: Confluence "Dynamic Whips — External Contractor Handoff", page 49872898, section
"2. Grappling Hooks". This builds entirely on the rope core (`docs/rope-core.md`, MINECRAFT-85)
through `RopeManager` — no new rope physics of its own — the same relationship `docs/whip.md`
documents for the Leather Whip, read first if you haven't: the input-model split between a real
client (which can read continuous key state) and a headless GameTest (which cannot) is identical
in spirit here, just for jump/shift instead of the use key.

## 0. BLOCKING FINDING: `RopeManager.payOut`/`reelIn` do not move the player for a world-point anchor

**Escalated to MINECRAFT-87 (this story's epic). Read this before trusting anything criteria 3, 4
or 5 below claim — they are written around this gap, not in ignorance of it.**

Two separate, now-resolved sub-findings, both discovered by this story actually exercising
`RopeManager.payOut`/`reelIn` for the first time against a real rope (rope-core's own doc records
these primitives as "exercised indirectly through `adjustLength` but no item wires them up yet" —
this story is the first real caller):

1. **A real crash, now mitigated by this story.** Calling `RopeManager.payOut`/`reelIn` once every
   server tick while input stayed held crashed Sable's own native Rapier layer in CI
   (`RuntimeException: Rapier native panic: index out of bounds: the len is 8 but the index is 9`),
   taking down the ENTIRE headless GameTest server (all tests in the run, not just this story's).
   `HookState.STEP_COOLDOWN_TICKS` (one step per 5 ticks instead of every tick) stopped the crash in
   every subsequent CI run — a mitigation on this story's own call CADENCE, not a change to
   rope-core, and not a synthetic replacement for the real primitive (every step is still a real
   `RopeManager` call). The exact mechanism (why 5 ticks apart never crashes but 1 tick apart
   reliably does) was not identified further — this is reported as a found-and-worked-around
   stability risk, not a root-caused one.
2. **A real functional gap that turned out to be NONDETERMINISTIC, not a clean "never works."**
   With the crash out of the way, CI shows `RopeManager.payOut`/`reelIn` DO change
   `RopeManager.length(ropeId)` (the rope's own nominal rest-length number) correctly — it climbs
   cleanly toward each tier's own cap. Whether the PLAYER'S OWN actual distance from the anchor
   follows it turned out to depend on something this story did not control: in one CI run, Iron's
   nominal cap reached 16.1 while the player's measured distance stayed at 2.69 blocks, and
   Netherite's nominal cap reached 63.1 while the player's distance stayed at 2.59 — consistent
   with the hypothesis that growth "at the anchor end" (`PlayerRope#payOut`'s own javadoc) never
   enters `RopeMath#findPivotIndex`'s taut-chain walk for a plain `RopeAnchor.WorldPoint`. **A
   later CI run, on unchanged code, contradicted this**: the Netherite case instead measured a
   real distance of 19.3 blocks — genuine, substantial movement. The most likely explanation,
   read from `docs/rope-core.md` section 10.1 (already documented there for the obstruction rig,
   not invented here): this GameTest structure is placed at a random, large, far-from-origin
   absolute world coordinate every run, and Sable's rope solver is independently known to behave
   very differently at those magnitudes (float32 precision noise) than near the origin. This
   story's own pay-out/reel-in behavior for a world-point anchor may be subject to the SAME
   placement-dependent nondeterminism, not a single, clean "doesn't work" rule — unresolved here.
3. **A second, more severe native crash, found when real movement DOES happen, that
   {@code STEP_COOLDOWN_TICKS} does NOT prevent.** The CI run that measured 19.3 blocks of real
   movement crashed shortly after:
   `RuntimeException: Rapier native panic: index out of bounds: the len is 8 but the index is 11`,
   this time inside `parry3d`'s own BVH (bounding-volume-hierarchy) build rather than the physics
   step itself — a DIFFERENT panic from finding 1's (`len is 8 but the index is 9`), though both
   share the suspicious `len is 8`. Both were reproduced on a rope grown via repeated real
   `RopeManager.payOut` calls; this one additionally needed the player to actually be moving a
   meaningful real distance under the grown rope's constraint. Because this is unpredictable
   (same code, same tick budget, different outcome depending on the GameTest framework's own
   random structure placement) and a crash takes down the entire shared headless server, not just
   one test, no tick budget this story tried is safe to ship as a required OR optional GameTest —
   see section 5.

**What this means for this PR:** `HookItem`/`HookState`/`HookLogic` consume exactly the documented
`RopeManager` API (`attachToPoint`, `payOut`, `reelIn`) precisely as the ticket instructed, and
build no parallel/synthetic mechanism of their own — this is a rope-core-level gap (and, per
finding 3, a rope-core-level stability risk), not a defect in how this story calls it. Per the
ticket's own instruction to stop and report rather than work around a defect of this kind,
criterion 5's own GameTest demonstration is REMOVED rather than tuned to a smaller number that
might simply not have been unlucky yet — see section 5. Criteria 3 and 6 are still accurately
described below (the input plumbing and the nominal cap arithmetic both genuinely work, at the
bounded, safe call volumes `HookGameTests` actually exercises) — "the player actually travels, and
travels safely" is the open, escalated question.

## 1. One shared implementation, tier as data (criterion 1)

`HookLogic` is a single, Minecraft-free class (no Minecraft imports at all — plain-JUnit testable,
`HookLogicTest`) holding the tier table (`HookLogic.Tier`: `IRON(16.0)`, `DIAMOND(32.0)`,
`NETHERITE(64.0)` — the spec's own table, nothing else), the shared pay-out/reel-in step size
(`SEGMENT_SPACING`, mirroring rope-core's own `RopeConstants.SEGMENT_SPACING` — duplicated rather
than imported, to keep this class Minecraft-free, the same way `WhipLogic.ANCHOR_SLACK` duplicates
rather than imports), the shared minimum length (`MIN_LENGTH`), and the shared anchor slack
(`ANCHOR_SLACK = 1.1`, matching `WhipLogic.ANCHOR_SLACK` and the exact slack rope-core's own
`RopeConstants.MAX_POINTS` (142 points) was sized against for the 64-block Netherite Hook —
`HookLogicTest#netheriteAtAnchorSlackMatchesRopeCoreMaxPointsDerivation` pins the arithmetic this
depends on). `HookItem` is one class parameterized by `HookLogic.Tier` at construction; three
`DeferredItem<HookItem>` registrations in `DynamicWhipsMod` (`iron_hook`, `diamond_hook`,
`netherite_hook`) are the only per-tier code anywhere in this mod.

## 2. Anchor / detach, stays connected (criterion 2)

`HookItem#use`: if this player already has a hold (`HookState.get`), detach it — right-click
again detaches, per the spec, regardless of which hand or tier. Otherwise, clip a look ray at this
tier's own `maxLength()` (no entity branch at all — the spec gives the hook no combat role) and,
if it lands on a block, `RopeManager.attachToPoint` at `HookLogic.ANCHOR_SLACK` and record the hold.

**Deliberately NOT hold-to-keep, unlike the whip.** The spec is explicit: "unlike the whip, a hook
remains connected after firing." `HookState` has no ping-silence timeout at all (contrast
`WhipHoldState.TIMEOUT_TICKS`) — a hold simply lives until an explicit second `use()`, or a
rope-core lifecycle event tears the rope down generically (see section 5 below). `HookGameTests#
anchorStaysConnectedAndSecondUseDetaches` asserts the rope survives 10 untouched ticks with zero
held input, the direct opposite of the whip's own equivalent test.

## 3. Jump/shift input, server-validated (criterion 3)

A real client's jump/shift state can only be read continuously by the client itself (same
reasoning `docs/whip.md` section 1 gives for the use key and vanilla's `isUsingItem()` slowdown,
though jump/shift carry no such slowdown — there was no need to avoid vanilla's own input system
here the way the whip had to). `HookClientInput` sends a `HookInputPayload(reelIn, payOut)` every
client tick a hook is held, in either hand, regardless of whether that player's hook is actually
attached (a no-op server-side if not — `HookState.setInput`). The payload carries only two
booleans, never a length: `HookState.tickAll` is what advances the rope, exactly one
`RopeManager.payOut`/`reelIn` segment per tick of unambiguous input, gated by
`HookLogic.canPayOut`/`canReelIn` — a client cannot request an arbitrary length no matter what it
sends (criterion 3's own "do not trust client lengths").

**Input staleness, not a connection timeout.** Because a hold itself never times out (section 2),
something else has to stop a stale `true` reel/pay-out flag from running forever once the client
stops confirming it (switched away from the hook, disconnected). `HookState.tickAll` treats
`INPUT_STALE_TICKS` (5, same value and reasoning as `WhipHoldState.TIMEOUT_TICKS`) of missing
payloads as "no input right now" and simply does nothing, rather than requiring an explicit "stop"
message — this self-heals on ordinary packet loss and on a complete disconnect alike.

**Not covered by any GameTest, same caveat as `WhipClientInput`:** `HookClientInput`'s own
key-state reads cannot run inside a GameTest's mock player. `HookGameTests` calls
`HookState.setInput` directly wherever continuous input is needed, simulating what a real payload
would have caused the server to receive — never exercising `HookClientInput` or the payload
handler wiring itself. Reasoned confidence only for that one piece, mirroring the payload's own
byte-for-byte structural similarity to the already-reviewed `WhipHoldPingPayload`.

**What is and isn't knowingly met here.** "Input is client-side, sent via payload, server
validates, never trusts a client length" — all genuinely true and CI-verified
(`HookGameTests#jumpReelsInShiftPaysOutUpToTierMax`). "Up to the tier maximum and no further" is
ALSO true of the number `RopeManager.length()` reports. What is explicitly, knowingly NOT met is
the spec's own implicit assumption that reaching that number means the PLAYER has actually
travelled that far — section 0's gap means it does not, for a world-point anchor. Pending the
epic's decision on that gap.

## 4. Physics-resolved reeling, not teleportation (criterion 4)

`HookState.tickAll` calls `RopeManager.payOut`/`reelIn` — never `player.setPos` or any
velocity-along-straight-line move toward the anchor. Those two `RopeManager` methods only change
how many points the rope has; the player's actual position every tick is still entirely owned by
`PlayerRope#tick`'s existing swing correction (rope-core, unchanged by this story) — gravity,
momentum and the pivot-clamped radius, exactly as the whip's own anchor already relies on.

`HookGameTests#reelingResolvesThroughPhysicsNotStraightLineTeleport` builds an obstruction rig (a
stone post between anchor and player, matching `RopeGameTests#buildCatchRig`'s own geometry) and
drives a real reel-in through `HookState`, sampling the player's position periodically. **Its
assertion is deliberately narrower than "the rope actually catches on the post":** it only checks
that the sampled trajectory is never collinear with the straight anchor-to-start line, which
follows from gravity and the swing constraint governing position every tick regardless of whether
the post itself resolves collision cleanly. The stronger "does it actually catch" question is
`docs/rope-core.md` section 10.10's own open, unresolved finding (a real, bounded penetration at
rig scale, mechanism and hook-scale effect explicitly left open there) — this story's ticket says
to watch for QUALITATIVELY WORSE regressions there (rope passing cleanly through, swing constraint
lost entirely), not to re-settle that question, which is why this test's own criterion is narrower
and marked `required = false` (it still exercises the same obstruction-rig physics path section 10
found nondeterministic, so a flake there is this test's own risk to report, not a rope-core
regression to chase).

**MINECRAFT-178 (sibling task, PR #14 into MINECRAFT-87) is the one measuring hook-scale (16/32/64
block) collision behavior against an obstruction directly** — at the time of writing, its own
`docs/rope-core.md` subsection is not yet filled in (its PR is still open). Read its PR/doc
directly for the real numbers rather than any hook-scale collision claim inferred here; nothing in
this story invents a number MINECRAFT-178 is the one actually measuring.

## 5. Representative case: deep shaft, pay out and reel back (criterion 5) — BLOCKED, removed, see section 0

**This criterion is not currently satisfiable, and — unlike criteria this doc marks "known gap,
pinned as a canary" elsewhere — this one has NO GameTest at all, not even a `required = false`
one.** The reason is stronger than "the feature doesn't work": section 0 findings 2 and 3 show the
real-rope behavior here is NONDETERMINISTIC (sometimes the player barely moves, sometimes it moves
tens of blocks, depending on this GameTest structure's own randomly assigned, far-from-origin world
placement — the same class of float32-precision effect `docs/rope-core.md` section 10.1 already
documents), and the "moves tens of blocks" outcome crashed Sable's native Rapier layer inside
`parry3d`'s BVH build. A test that sometimes passes, sometimes documents a gap, and sometimes
crashes the entire shared headless GameTest server depending on placement luck is not safe to ship
at ANY tick budget or `required` setting this story tried — keeping it in, even as `required =
false`, would mean every future CI run on this repository carries a real, unquantified chance of
taking down its own GameTest job for a reason that has nothing to do with whatever else that run
is testing. Removed rather than tuned smaller: see `HookGameTests`' own trailing comment (where the
method used to be) for the exact crash and the two contradicting CI measurements that led here.

**What criterion 5 needs to actually become satisfiable:** rope-core (MINECRAFT-85) needs to
either fix `PlayerRope#payOut`/`reelIn` so growth at the anchor end reliably becomes part of the
taut chain `RopeMath#findPivotIndex` can reach for a plain `WorldPoint` anchor (closing finding 2),
or diagnose and fix whatever in Sable/the `parry3d` BVH build panics once a dynamically-grown rope
actually moves a real distance (closing finding 3) — ideally BOTH, since finding 3 means even a
correct fix for finding 2 would need to be proven not to trigger the crash it currently seems to
correlate with. Neither is this story's call to make (rope-core is a different story's surface,
and the ticket's own standing rule is to report a foundation defect rather than route around it
with new physics) — both are recorded here, and on MINECRAFT-87, for whoever picks this up next.

## 6. Hard cap (criterion 6)

`HookLogic.tickPayOut`/`tickReelIn` are pure functions gated by `canPayOut`/`canReelIn`, unit
tested (`HookLogicTest`) with thousands of repeated cycles, including cycles that repeatedly cross
a tier's own cap boundary, asserting EXACT (not tolerance-based) equality to the cap every time it
is reached — no accumulated float/double drift, because `Math.min`/`Math.max` against an exact
double cap value is itself exact.

**The hard cap is strict — no overshoot tolerance, by design (review on PR #15).** An earlier
revision of `HookLogic.canPayOut` gated on the CURRENT length alone, which let the real,
`RopeManager`-backed path overshoot a tier's nominal max by up to one rope segment before the next
tick's gate caught up — the ticket says hard limit, not "within one segment," and review correctly
rejected the looser version. `canPayOut` now gates on the PROJECTED length (current +
`SEGMENT_SPACING`), refusing a step outright whenever taking it could exceed the cap. This is
sound against the REAL `RopeManager.payOut` (which advances by the rope's own ACTUAL segment
spacing, `docs/rope-core.md` section 1.6, not always exactly `HookLogic.SEGMENT_SPACING`) because
rope-core's own `RopeConstants.MAX_POINTS` javadoc guarantees the actual spacing stays AT OR UNDER
`HookLogic.SEGMENT_SPACING` for every attach distance this story's items use — refusing on the
worst-case step size also refuses every real step that would have overshot.

**The one honest cost of a strict gate: pay-out can stop one segment SHORT of the nominal cap
instead of reaching it exactly, when the cap isn't a whole number of segments from the attach-time
length.** `HookLogicTest#payOutNeverOvershootsFromANonAlignedStartingLength` pins this as the
deliberate trade-off it is, not a bug — a hard limit that is sometimes slightly short is correct;
a soft limit that is sometimes slightly over is not. `HookGameTests#hardCapNeverExceededOnARealPayOutReelInCycle`
asserts the real, `RopeManager`-backed path never exceeds the cap at all (not "within one segment")
across one real pay-out/reel-in cycle — deliberately ONE, not "many": see
`HookState#STEP_COOLDOWN_TICKS`'s own javadoc and section 0 above for the native-panic crash an
earlier, three-cycle, every-tick revision of this test caused. The exhaustive "many repeated
cycles" claim criterion 6 actually asks for stays covered by `HookLogicTest` alone, which never
touches Sable at all and is genuinely safe to run thousands of cycles in.

## 7. Lifecycle (criterion 7)

| Event | Rope torn down by | Hook's own hold cleared by |
|---|---|---|
| Right-click again (explicit detach) | `HookItem#use`'s own `RopeManager.detach` call | same `use()` call, `HookState.clear` |
| Anchor block broken / chunk unload | `RopeManager#tickAll` (rope-core, generic — MINECRAFT-85) | `HookState.tickAll` notices the rope is already gone and clears the hold the same tick it next runs |
| Player death | `DynamicWhipsMod`'s `LivingDeathEvent` listener → `RopeManager#detachAllOwnedBy` | same listener now also calls `HookState.clear` |
| Logout | `PlayerLoggedOutEvent` listener → `detachAllOwnedBy` | same listener also calls `HookState.clear` |
| Dimension change | `PlayerChangedDimensionEvent` listener → `detachAllOwnedBy` | same listener also calls `HookState.clear` |
| Server stop | `ServerStoppingEvent` listener → `RopeManager#clearAll` | same listener also calls `HookState.clearAll` |

No ping-silence row exists here at all (contrast `docs/whip.md` section 5's table) — a hook has no
such mechanism, by design (section 2).

**CI-asserted** (`HookGameTests`): anchor-block-broken (`breakingAnchorBlockClearsHookStateImmediately`,
proving `HookState` notices without any ping of its own, since none exists) and death
(`playerDeathClearsHookState`, through the real event bus). **Not independently GameTested, same
caveat `docs/rope-core.md` section 4 and `docs/whip.md` section 5 already give their own identical
paths:** logout, dimension change and server stop are wired the identical two-call pattern
(`RopeManager` call + `HookState.clear`/`clearAll`), which a headless GameTest server cannot drive
a real connection through — read-the-code confidence, not CI-asserted confidence. Chunk unload is
rope-core's own existing, already-documented gap (same doc, same section), unchanged by this story.

## 8. Items, assets, recipes (criterion 9)

Items: `iron_hook`, `diamond_hook`, `netherite_hook`, all `stacksTo(1)`, registered in
`CreativeModeTabs.TOOLS_AND_UTILITIES`. **No durability cost.** This is a deliberate choice, not an
oversight: `HookState`/`RopeManager` already validate every reel/pay-out server-side every tick
(section 3), so there is no exploit a durability cost would be defending against, and a traversal
tool whose entire cost is paid once at crafting time (tier progression, see below) is consistent
with vanilla's own spyglass/compass pattern for a validated, non-combat utility item — a pickaxe's
durability defends against free, unlimited world-destructive use in a way that doesn't apply here.

Models/textures/lang: placeholder art (`scripts/generate_hook_textures.py` — a simple
hook-silhouette shape in each tier's own material color, regenerable, reviewable as source rather
than an opaque binary blob, the same reasoning `generate_gametest_structures.py` already uses for
this repo's GameTest structures). The spec explicitly leaves "final names, art, animation, sounds"
to the team (its own "Team-owned decisions" list) — this is not claimed as final art.

### Recipe rationale

The ticket's own suggested starting point, taken as-is because nothing in testing this story
surfaced a reason to deviate:

- **Iron Hook** = 2 iron ingots + 1 tripwire hook + 1 lead + 1 string
  (`data/dynamicwhips/recipe/iron_hook.json`). A tripwire hook is itself iron ingot + stick +
  string, and a lead is vanilla's own closest existing "rope" item — using both as components
  (rather than inventing a new "rope" item) keeps the recipe's own cost legible in terms of
  existing vanilla material value, and mirrors the ticket's literal "iron ingots + tripwire
  hook/string + lead-style rope" wording directly instead of approximating it.
- **Diamond Hook** = 4 diamonds + 1 Iron Hook (`diamond_hook.json`). A flat upgrade over the Iron
  Hook (not rebuilt from base materials) — the cost of DOUBLING the tier's own reach (16 → 32
  blocks) is purely the diamond premium, which reads as proportionate: diamond gear is already the
  mid-tier cost benchmark across vanilla's own tool/armor progression.
- **Netherite Hook** = Diamond Hook + netherite ingot, via a smithing table with the vanilla
  netherite upgrade smithing template (`netherite_hook.json`, `minecraft:smithing_transform`) —
  the EXACT vanilla pattern every other netherite tool/armor upgrade uses, not a crafting-table
  recipe. This was the ticket's own explicit suggestion ("via smithing table, vanilla netherite
  upgrade pattern") and there is no reason to deviate: it keeps the Netherite Hook's own cost
  legible against every other netherite upgrade a player has already seen, and a netherite ingot
  (itself requiring a Nether trip and ancient debris) is a cost commensurate with what the spec
  calls "large-scale traversal gear" and 64 blocks of guaranteed-safe descent is worth.

## 9. Changelog

`changelog.d/MINECRAFT-179.md`, per `changelog.d/README.md`'s format; no version bump performed
directly (the release gate computes it from the fragment's own declared `bump:` level at merge
time).

## 10. Multiplayer sync (criterion 10)

**No new sync code at all.** A hook's rope is registered through the exact same
`RopeManager.attachToPoint`/`RopeNetworking` plumbing rope-core and the whip's own anchor already
use — `RopeManager` has no concept of "whip" or "hook," only "a rope某 player owns." The audience
(`PacketDistributor.sendToPlayersTrackingEntityAndSelf`), rate (5 Hz, `SYNC_INTERVAL_TICKS`) and
shape (`RopeSyncPayload`'s flattened float array) are all `docs/rope-core.md` section 6's own,
already-shipped design — a second player watching someone else swing a grappling hook sees the
identical sync stream a second player watching a whip anchor already does, because it is the same
code path.

**What a headless test can and cannot assert, same caveat `docs/rope-core.md` section 6 already
gives:** a GameTest has no second real client to observe a sync packet's visual effect on, so this
was never independently re-verified for hooks specifically — there is nothing hook-specific to
verify, since no hook-specific sync code exists. The one way to actually confirm this is the same
manual two-client procedure `docs/rope-core.md`/`docs/whip.md` already describe for their own
anchors, substituting a grappling hook for the whip/anchor item in hand — not run here, per this
story's own scope (no local builds/multi-client sessions available under the no-local-builds
policy).
