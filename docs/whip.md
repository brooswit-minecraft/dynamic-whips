# Leather Whip: hold-to-keep block anchor (MINECRAFT-86 / MINECRAFT-137)

This covers the whip's second half: a block hit becomes a temporary rope anchor that lasts only
while the player holds the input. It builds entirely on the rope core (`docs/rope-core.md`,
MINECRAFT-85) through `RopeManager` — this story adds no physics of its own, only the whip-side
input model (`WhipItem`) and bookkeeping (`WhipHoldState`). The entity-hit combat swing (reach,
damage curve, cooldown) is MINECRAFT-68's shipped behavior, unchanged.

## 1. Input model: why `startUsingItem` / `onUseTick` / `onStopUsing`

A short click and a held click look identical at the moment `WhipItem#use` fires — Minecraft's own
input model only distinguishes "held" from "released" through one specific hook family:
`getUseDuration` (set to 72000, the same "effectively unbounded" convention the bow and trident
use, so the hold never expires on its own), `getUseAnimation`, `onUseTick` (fires every server
tick while held), and `onStopUsing`.

**`onStopUsing`, not vanilla's `releaseUsing`, is what detaches the rope.** Reading
`LivingEntity#stopUsingItem` / `#updatingUsingItem` directly: the explicit release packet calls
`releaseUsingItem()`, which calls both `Item#releaseUsing` and then `stopUsingItem()`. But
switching hotbar slots (`ServerGamePacketListenerImpl#handleSetCarriedItem`) and a held stack no
longer matching what's in hand (dropping it, `updatingUsingItem`'s own mismatch check) both call
`stopUsingItem()` **directly**, never going through `releaseUsingItem()` — so `releaseUsing` would
simply never fire for those two paths. `stopUsingItem()` calls NeoForge's `IItemExtension#onStopUsing`
unconditionally on every one of those paths, which is why `WhipItem` detaches from there instead.

**`onUseTick`'s own job**: notice when the rope core has torn the rope down out from under a still
-held whip (anchor block broken, chunk unload, server stop — all driven from `RopeManager#tickAll`
with no detach call from the whip at all) and let go (`player.stopUsingItem()`) the next tick,
instead of leaving the player visibly "holding" a dead anchor until they separately release.

## 2. Chain-crack / cooldown rule (criterion 2)

The cooldown (`WhipLogic.COOLDOWN_TICKS`) is applied unconditionally at the very top of `use()`,
exactly where the shipped combat swing already applied it — covering the entity hit, the block
anchor, and a total miss identically, and unchanged from before this story. Since the game itself
refuses to call `use()` again while the item is on cooldown, attach can never repeat faster than
once per cooldown no matter how quickly a player attaches and releases: holding the anchor open
costs nothing extra, but re-cracking it (attach → release → attach again) cannot bypass the
cooldown either. Durability is charged once per successful anchor, mirroring the existing per-hit
cost on the entity branch, not once per tick held.

## 3. No reel in, no reel out (criterion 3)

`WhipItem` never calls `RopeManager.payOut`, `reelIn`, or `adjustLength` — grep confirms it, and
`WhipGameTests#blockHitAttachesHoldToKeepAnchorWithNoReel` / `#wholeWhipArrestsAFallAndSwing` both
assert `RopeManager.length(ropeId)` is bit-for-bit unchanged across several ticks of a live hold,
which is the strongest evidence a headless test can offer that nothing changes the rope's length.

## 4. Tuning vs. the grappling hooks (criterion 7)

`WhipLogic.ANCHOR_SLACK = 1.1` (the rope-core default, matching `RopeGameTests#buildCatchRig`'s
own rig and the Netherite Hook's documented default) and `REACH = 5.0` (unchanged) give
`MAX_ANCHOR_ROPE_LENGTH = 5.5` blocks — pinned by `WhipLogicTest#anchorRopeStaysMuchShorterThanTheCheapestGrapplingHook`
to stay well under even the cheapest (16-block Iron) grappling hook from the Confluence spec, with
no reel control at all. The whip stays a skill toy and a weapon, not a grappling gun.

## 5. Lifecycle (criterion 8)

| Event | Rope torn down by | Whip's own hold cleared by |
|---|---|---|
| Release the input | `onStopUsing` → `RopeManager#detach` | `onStopUsing` → `WhipHoldState#end` |
| Switch hotbar slot while holding | same (`stopUsingItem()`'s stack-mismatch path calls `onStopUsing`) | same |
| Drop the whip while holding | same (next tick's stack-mismatch check) | same |
| Anchor block broken / chunk unload | `RopeManager#tickAll` (rope-core, MINECRAFT-85) | `WhipItem#onUseTick` notices the rope is gone and calls `stopUsingItem()` |
| Player death | `DynamicWhipsMod`'s `LivingDeathEvent` listener → `RopeManager#detachAllOwnedBy` | same listener now also calls `WhipHoldState#clear` |
| Logout | `PlayerLoggedOutEvent` listener → `detachAllOwnedBy` | same listener now also calls `WhipHoldState#clear` |
| Dimension change | `PlayerChangedDimensionEvent` listener → `detachAllOwnedBy` | same listener now also calls `WhipHoldState#clear` |
| Server stop | `ServerStoppingEvent` listener → `RopeManager#clearAll` | same listener now also calls `WhipHoldState#clearAll` |

CI-asserted (`WhipGameTests`): release, switch-away, and anchor-block-broken. Not independently
GameTested (same caveat as rope-core's own doc, `docs/rope-core.md` section 4): death, logout,
dimension change and server stop are wired identically to the CI-asserted paths (same two method
calls, same event-listener pattern) — read-the-code confidence, not CI-asserted confidence.

## 6. Cut from scope: entity tether (criterion 5)

The spec offers this conditionally ("if kept attached to an entity") and MINECRAFT-86 explicitly
ranks it lower priority than the block anchor. **Cut from this story.** MINECRAFT-68's shipped
combat half already treats every entity hit on the look ray as an instant, reviewed-and-approved
swing (damage + cooldown + durability, no hold). Layering a hold-to-tether mode onto that same
input would require deciding, for every entity hit, whether to swing instantly or start a hold —
a redesign of the entity branch's own meaning, which risks re-litigating combat behavior this
story was explicitly told not to touch ("one whip, one set of numbers," criterion 6). Flagging
this explicitly here and in the PR description/ticket rather than leaving it ambiguous, per the
ticket's own instruction.

## 7. Manual procedure: the felt "skill toy" quality

CI can assert the mechanics (attach, no-reel, detach, fall-arrest) but not whether the swing
*feels* like a skill toy rather than a grapple — that is a subjective, human judgment call outside
headless CI's reach, same category as rope-core's own section 6 (packet rate) and section 8
(performance) manual procedures.

1. Join a dev server with this mod (and Sable) loaded, survival mode, at ordinary world
   coordinates (not a GameTest structure's own coordinates — see `docs/rope-core.md` section 3.5
   for why that matters for float precision).
2. Stand near a cliff or tower with a stone ledge or post within 5 blocks reach below/beside you.
   Right-click the stone (not an entity) and hold the button.
3. Walk off the edge while holding. You should swing around the anchor point under gravity and
   momentum alone — no input moves you directly toward the anchor, and letting go at the bottom
   or top of the swing's arc should feel like a deliberate timing choice, not automatic.
4. Try arresting a straight fall (anchor directly above where you jump from), a careful descent
   (anchor above and slightly to the side, releasing partway down to drop the rest), and momentum
   traversal (swinging from one anchor, releasing mid-swing to carry momentum toward a second
   anchor point). Record whether each feels achievable with practice, not automatic.
5. Compare the felt "ceiling" of what the whip lets you cross versus a grappling hook (even a
   placeholder single right-click-to-attach-and-stay test item, if one exists) — the whip's hold
   requirement and short/fixed rope should make it noticeably more effortful and limited.

*(Not run here — this procedure is for a human to follow once the mod is loaded somewhere with a
real client; nothing in this story's own scope required running it.)*
