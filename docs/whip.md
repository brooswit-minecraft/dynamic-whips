# Leather Whip: hold-to-keep block anchor (MINECRAFT-86 / MINECRAFT-137)

This covers the whip's second half: a block hit becomes a temporary rope anchor that lasts only
while the player holds the input. It builds entirely on the rope core (`docs/rope-core.md`,
MINECRAFT-85) through `RopeManager` — this story adds no physics of its own, only the whip-side
input model (`WhipItem`, `WhipHoldState`, `WhipHoldPingPayload`, `WhipClientInput`). The entity-hit
combat swing (reach, damage curve, cooldown) is MINECRAFT-68's shipped behavior, unchanged.

## 1. Input model: a custom heartbeat, NOT vanilla's use-item system

**Revision history matters here.** The first version of this story used vanilla's
`startUsingItem`/`isUsingItem`/`onStopUsing` hooks (the same family the bow and trident use) to
detect "still holding." A review caught a real problem with that, verified directly against the
decompiled 1.21.1/NeoForge sources rather than taken on recollection:

```java
// net/minecraft/client/player/LocalPlayer.java, LocalPlayer#aiStep
net.neoforged.neoforge.client.ClientHooks.onMovementInputUpdate(this, this.input);
this.minecraft.getTutorial().onInput(this.input);
if (this.isUsingItem() && !this.isPassenger()) {
    this.input.leftImpulse *= 0.2F;
    this.input.forwardImpulse *= 0.2F;
    this.sprintTriggerTime = 0;
}
```

Whenever `isUsingItem()` is true — which `startUsingItem` makes true for as long as the hold lasts
— the client scales its own movement input to 20%, unconditionally, with no item-level override
anywhere in `IItemExtension`/`IItemStackExtension`/`IClientItemExtensions`. This is the same
slowdown a drawn bow or a raised shield gives you, and it is hardcoded: `MovementInputUpdateEvent`
(the one NeoForge hook that touches `Input` before this code runs) fires *before* this block, not
instead of it, so modifying input from that event does not prevent the subsequent `*= 0.2F`. Short
of a Mixin (this project uses none), there is no supported way to opt a specific item out of this
behavior while still using vanilla's use-item system.

Criterion 3 says "all movement comes from gravity, momentum, **ordinary air control**, and
releasing at the right moment"; criterion 4 asks for careful descent and momentum traversal. Both
are defeated by cutting the player's own steering input to a fifth of normal for the entire hold.

**The fix: a custom client→server heartbeat instead of vanilla's use-item system.**
`WhipClientInput#onClientTick` (client-only, registered on `NeoForge.EVENT_BUS` only when
`FMLEnvironment.dist.isClient()`) sends an empty `WhipHoldPingPayload` every client tick the use
key (`Minecraft.options.keyUse`) is held with a whip in either hand — regardless of whether the
client itself knows an anchor is live, since only the server knows that; an extra ping for a
player with no active hold is a cheap no-op. The server never calls `startUsingItem`, so
`isUsingItem()` stays false for the whole hold and the slowdown above never triggers at all.
`WhipHoldState#tickTimeouts` (run every server tick, same `ServerTickEvent.Post` hook `RopeManager`
already uses) treats `TIMEOUT_TICKS` (5 ticks / 250ms) of silence as "released." A normal release,
switching away from the whip, and dropping it are **indistinguishable from the server's point of
view** under this design — all three simply stop the client from pinging — which is simpler than
the three separate vanilla hooks the previous revision needed to tell them apart.

`WhipHoldState#tickTimeouts` also clears a hold the instant `RopeManager` has already torn the rope
down on its own (anchor block broken, chunk unload), without waiting out the full timeout.

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

`WhipItem`/`WhipHoldState`/`WhipClientInput`/`WhipHoldPingPayload` never call `RopeManager.payOut`,
`reelIn`, or `adjustLength` — grep confirms it, and `WhipGameTests#blockHitAttachesHoldToKeepAnchorWithNoReel`
/ `#wholeWhipArrestsAFallAndSwing` both assert `RopeManager.length(ropeId)` is bit-for-bit
unchanged across several ticks of a live hold, which is the strongest evidence a headless test can
offer that nothing changes the rope's length.

## 4. Tuning vs. the grappling hooks (criterion 7)

`WhipLogic.ANCHOR_SLACK = 1.1` (the rope-core default, matching `RopeGameTests#buildCatchRig`'s
own rig and the Netherite Hook's documented default) and `REACH = 5.0` (unchanged) give
`MAX_ANCHOR_ROPE_LENGTH = 5.5` blocks — pinned by `WhipLogicTest#anchorRopeStaysMuchShorterThanTheCheapestGrapplingHook`
to stay well under even the cheapest (16-block Iron) grappling hook from the Confluence spec, with
no reel control at all. The whip stays a skill toy and a weapon, not a grappling gun.

## 5. Lifecycle (criterion 8)

| Event | Rope torn down by | Whip's own hold cleared by |
|---|---|---|
| Release the input, switch items while holding, or drop the whip while holding | `WhipHoldState#tickTimeouts` detects `TIMEOUT_TICKS` of ping silence → `RopeManager#detach` | same `tickTimeouts` pass |
| Anchor block broken / chunk unload | `RopeManager#tickAll` (rope-core, MINECRAFT-85) | `WhipHoldState#tickTimeouts` notices the rope is already gone and clears the hold the same tick, without waiting for the ping timeout |
| Player death | `DynamicWhipsMod`'s `LivingDeathEvent` listener → `RopeManager#detachAllOwnedBy` | same listener also calls `WhipHoldState#clear` |
| Logout | `PlayerLoggedOutEvent` listener → `detachAllOwnedBy` | same listener also calls `WhipHoldState#clear` |
| Dimension change | `PlayerChangedDimensionEvent` listener → `detachAllOwnedBy` | same listener also calls `WhipHoldState#clear` |
| Server stop | `ServerStoppingEvent` listener → `RopeManager#clearAll` | same listener also calls `WhipHoldState#clearAll` |

CI-asserted (`WhipGameTests`): ping-silence timeout (covering release/switch/drop as one
mechanism) and anchor-block-broken. Not independently GameTested (same caveat as rope-core's own
doc, `docs/rope-core.md` section 4): death, logout, dimension change and server stop are wired
identically to the CI-asserted paths (same two method calls, same event-listener pattern) —
read-the-code confidence, not CI-asserted confidence. Also not CI-asserted: the real client↔server
packet path itself (`WhipClientInput` sending, the server registering `WhipHoldPingPayload`'s
handler) — every GameTest drives `WhipHoldState.ping`/`WhipItem#use` directly with a mock player
and no real connection's packet pipeline, so the wiring in `DynamicWhipsMod#registerPayloads` and
`WhipClientInput` is reasoned-about (it mirrors the existing `RopeSyncPayload`/`RopeRemovePayload`
pattern byte for byte), not independently tested.

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

## 7. Manual procedure: steering while holding, and the felt "skill toy" quality

CI can assert the mechanics (attach, no-reel, timeout-detach, fall-arrest) but not whether a real
client's movement actually stays at full strength while holding, or whether the swing *feels* like
a skill toy rather than a grapple — both need a real client, which headless CI does not have.

1. Join a dev server with this mod (and Sable) loaded, survival mode, at ordinary world
   coordinates (not a GameTest structure's own coordinates — see `docs/rope-core.md` section 3.5
   for why that matters for float precision).
2. **Steering check (the review's own concern):** stand near a cliff or tower with a stone ledge
   or post within 5 blocks reach below/beside you. Right-click the stone and hold the button. Try
   moving with WASD while holding — your horizontal speed should feel completely normal, NOT
   noticeably slowed, unlike drawing a bow. This is the direct check that `WhipClientInput`'s
   heartbeat is actually in effect instead of vanilla's `isUsingItem()` slowdown; the attach itself
   working is not enough to show this.
3. Walk off the edge while holding. You should swing around the anchor point under gravity and
   momentum alone — no input moves you directly toward the anchor, and letting go at the bottom
   or top of the swing's arc should feel like a deliberate timing choice, not automatic.
4. Try arresting a straight fall (anchor directly above where you jump from), a careful descent
   (anchor above and slightly to the side, releasing partway down to drop the rest), and momentum
   traversal (swinging from one anchor, releasing mid-swing to carry momentum toward a second
   anchor point). Record whether each feels achievable with practice, not automatic, and whether
   steering still feels normal throughout (not just at the moment of attach).
5. Let go, then immediately try moving normally — there should be no lingering slowdown and no
   delay before the rope visually disappears (within `WhipHoldState.TIMEOUT_TICKS`, ~250ms).
6. Compare the felt "ceiling" of what the whip lets you cross versus a grappling hook (even a
   placeholder single right-click-to-attach-and-stay test item, if one exists) — the whip's hold
   requirement, short/fixed rope, and lack of reel control should make it noticeably more
   effortful and limited.

*(Not run here — this procedure is for a human to follow once the mod is loaded somewhere with a
real client; nothing in this story's own scope required running it.)*
