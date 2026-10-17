package io.github.brooswitminecraft.dynamicwhips;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.github.brooswitminecraft.dynamicwhips.rope.RopeManager;
import net.minecraft.server.MinecraftServer;

/**
 * Tracks, per player, which rope (if any) a thrown-and-stuck harpoon attached, and the most
 * recent reel-in/pay-out input that player's held {@link HarpoonItem} reported (MINECRAFT-88).
 * Deliberately the same shape as {@code HookState} (a hook-style "stays connected, right-click
 * again detaches" hold, plus a continuous jump/shift reel input with the same staleness decay) —
 * the harpoon's rope is created by a thrown {@link HarpoonEntity} instead of a raycast, but once
 * attached it is reeled exactly like a grappling hook; duplicating that proven mechanic here is
 * simpler and safer than inventing a second one. See {@code HookState}'s own javadoc for why a
 * stale-input decay exists at all; the identical reasoning applies verbatim here.
 */
final class HarpoonState {
    /** As {@code HookState#INPUT_STALE_TICKS}. */
    static final long INPUT_STALE_TICKS = 5;
    /** As {@code HookState#STEP_COOLDOWN_TICKS}: the same empirically-required Sable pacing. */
    static final long STEP_COOLDOWN_TICKS = 5;

    record Hold(UUID ropeId) {
    }

    private record Input(boolean reelIn, boolean payOut, long gameTime) {
    }

    private static final Map<UUID, Hold> HOLDS = new ConcurrentHashMap<>();
    private static final Map<UUID, Input> INPUT = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_STEP = new ConcurrentHashMap<>();

    private HarpoonState() {
    }

    static void start(UUID playerId, UUID ropeId) {
        HOLDS.put(playerId, new Hold(ropeId));
    }

    static Hold get(UUID playerId) {
        return HOLDS.get(playerId);
    }

    /** Death, logout, dimension change, server stop, or target death (caught the next tick via
     * {@link #tickAll}'s own {@code RopeManager#get} check): clear this player's bookkeeping. */
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

    /** Recorded by {@link HarpoonInputPayload}'s server-side handler every time one arrives. */
    static void setInput(UUID playerId, boolean reelIn, boolean payOut, long gameTime) {
        if (HOLDS.containsKey(playerId)) {
            INPUT.put(playerId, new Input(reelIn, payOut, gameTime));
        }
    }

    /**
     * Every server tick: drop any hold whose rope is already gone — rope-core tore it down on its
     * own, which is exactly what happens the tick after the target dies ({@code
     * RopeAnchor.EntityAnchor#isGone} returns true once {@code entity.isAlive()} is false, and
     * {@code RopeManager#tickAll} detaches on that same tick) — then apply at most one {@code
     * RopeManager#payOut}/{@code #reelIn} step for any hold with fresh, unambiguous input. See
     * {@code HookState#tickAll}'s own javadoc; this is the identical algorithm against
     * {@link HarpoonLogic#canPayOut}/{@link HarpoonLogic#canReelIn} instead of a per-tier gate.
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
                LAST_STEP.remove(playerId);
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
            if (input.reelIn() && HarpoonLogic.canReelIn(current)) {
                RopeManager.reelIn(hold.ropeId());
                LAST_STEP.put(playerId, now);
            } else if (input.payOut() && HarpoonLogic.canPayOut(current)) {
                RopeManager.payOut(hold.ropeId());
                LAST_STEP.put(playerId, now);
            }
        }
    }
}
