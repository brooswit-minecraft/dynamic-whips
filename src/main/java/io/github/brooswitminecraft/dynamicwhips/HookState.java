package io.github.brooswitminecraft.dynamicwhips;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.github.brooswitminecraft.dynamicwhips.rope.RopeManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;

/**
 * Tracks, per player, which rope (if any) their equipped grappling hook is anchored to, and the
 * most recent reel-in/pay-out input that hook's client reported (MINECRAFT-87 criteria 2, 3, 4
 * and 6).
 *
 * <p>Unlike {@link WhipHoldState}, a hook does NOT need to be held to stay connected — the spec is
 * explicit ("unlike the whip, a hook remains connected after firing; right-click again detaches
 * it") — so there is no ping-silence timeout here at all: a hold simply lives until an explicit
 * second {@code use()} detaches it, or the rope core itself tears the rope down (anchor broken,
 * chunk unload, death, logout, dimension change, server stop — all already generic in
 * {@code RopeManager}; {@code DynamicWhipsMod} clears this class's own bookkeeping on the same
 * events, same pattern as {@link WhipHoldState}).
 *
 * <p>What DOES need staleness handling is the continuous reel/pay-out input itself
 * ({@link HookInputPayload}): the client only sends a flag, never a length (criterion 3 — "the
 * server validates, do not trust client lengths"), and the server must stop honoring a "still
 * reeling" flag once the client stops confirming it (switched away from the hook entirely, lost
 * connection, etc.) — otherwise a single stale `true` would reel/pay-out forever. {@link
 * #tickAll} treats {@link #INPUT_STALE_TICKS} of silence as "no input right now," decaying
 * automatically rather than requiring an explicit "stop" message, the same reasoning
 * {@link WhipHoldState#TIMEOUT_TICKS} uses for its own silence case.
 */
final class HookState {
    /**
     * Ticks of missing {@link HookInputPayload} before the last-known reel/pay-out flags are
     * treated as stale (no input). A real client sends one every tick while holding a hook
     * ({@link HookClientInput}), so this only ever matters once that stops — switching away from
     * the hook, a lost connection, or ordinary packet loss — at which point honoring a lingering
     * `true` would reel or pay out forever with no client input backing it. Same value as
     * {@link WhipHoldState#TIMEOUT_TICKS} for the same reason: comfortably more than one tick of
     * network jitter, short enough that stopping still feels immediate.
     */
    static final long INPUT_STALE_TICKS = 5;

    /**
     * Minimum ticks between successive {@code RopeManager#payOut}/{@code #reelIn} calls on the
     * SAME rope. Found necessary empirically, not chosen for feel: calling either one every single
     * tick while input stayed held crashed Sable's own native Rapier layer in CI
     * ("Rapier native panic: index out of bounds") after a small number of calls — see
     * `docs/hooks.md` section 3 for the full account and why this is a rope-core-level stability
     * question reported to the epic, not something this story fixes. This cooldown is this story's
     * own mitigation on this story's own call pattern (not a rope-core change, and not a synthetic
     * replacement for the real primitive — every call here is still a real {@code RopeManager}
     * call), chosen conservatively; it has not been tuned for feel because safety came first.
     */
    static final long STEP_COOLDOWN_TICKS = 5;

    record Hold(UUID ropeId, HookLogic.Tier tier, InteractionHand hand) {
    }

    private record Input(boolean reelIn, boolean payOut, long gameTime) {
    }

    private static final Map<UUID, Hold> HOLDS = new ConcurrentHashMap<>();
    private static final Map<UUID, Input> INPUT = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_STEP = new ConcurrentHashMap<>();

    private HookState() {
    }

    static void start(UUID playerId, UUID ropeId, HookLogic.Tier tier, InteractionHand hand) {
        HOLDS.put(playerId, new Hold(ropeId, tier, hand));
    }

    static Hold get(UUID playerId) {
        return HOLDS.get(playerId);
    }

    /** Death, logout, dimension change, server stop: clear this player's own bookkeeping alongside
     * whatever tears the rope itself down (mirrors {@link WhipHoldState#clear}). */
    static void clear(UUID playerId) {
        HOLDS.remove(playerId);
        INPUT.remove(playerId);
        LAST_STEP.remove(playerId);
    }

    static void clearAll() {
        HOLDS.clear();
        INPUT.clear();
        LAST_STEP.clear();
    }

    /** Recorded by {@code HookInputPayload}'s server-side handler every time one arrives. A silent
     * no-op for a player with no active hold, same reasoning as {@link WhipHoldState#ping}. */
    static void setInput(UUID playerId, boolean reelIn, boolean payOut, long gameTime) {
        if (HOLDS.containsKey(playerId)) {
            INPUT.put(playerId, new Input(reelIn, payOut, gameTime));
        }
    }

    /**
     * Every server tick: drop any hold whose rope is already gone (rope-core tore it down on its
     * own), then apply at most one {@code RopeManager#payOut}/{@code #reelIn} step — exactly one
     * rope segment — for any hold with fresh, unambiguous input (see {@link #INPUT_STALE_TICKS}
     * for "fresh"; both-held or neither-held is treated as no input, so the two inputs cannot
     * fight each other within a single tick). {@link HookLogic#canPayOut}/{@link
     * HookLogic#canReelIn} gate the tier cap and the shared minimum (criterion 6) — see
     * {@link HookLogic#SEGMENT_SPACING}'s javadoc for the known, bounded (≤ one segment) slop this
     * gate carries in exchange for never touching {@code PlayerRope} directly.
     */
    static void tickAll(MinecraftServer server) {
        if (HOLDS.isEmpty()) {
            return;
        }
        long now = server.overworld().getGameTime();
        for (Iterator<Map.Entry<UUID, Hold>> it = HOLDS.entrySet().iterator(); it.hasNext();) {
            Map.Entry<UUID, Hold> entry = it.next();
            UUID playerId = entry.getKey();
            Hold hold = entry.getValue();
            if (RopeManager.get(hold.ropeId()) == null) {
                it.remove();
                INPUT.remove(playerId);
                continue;
            }
            Input input = INPUT.get(playerId);
            if (input == null || now - input.gameTime() > INPUT_STALE_TICKS) {
                continue;
            }
            if (input.reelIn() == input.payOut()) {
                continue;
            }
            Long lastStep = LAST_STEP.get(playerId);
            if (lastStep != null && now - lastStep < STEP_COOLDOWN_TICKS) {
                continue;
            }
            Double current = RopeManager.length(hold.ropeId());
            if (current == null) {
                continue;
            }
            if (input.reelIn() && HookLogic.canReelIn(current)) {
                RopeManager.reelIn(hold.ropeId());
                LAST_STEP.put(playerId, now);
            } else if (input.payOut() && HookLogic.canPayOut(current, hold.tier())) {
                RopeManager.payOut(hold.ropeId());
                LAST_STEP.put(playerId, now);
            }
        }
    }
}
