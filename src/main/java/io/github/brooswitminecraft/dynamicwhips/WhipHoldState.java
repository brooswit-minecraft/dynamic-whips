package io.github.brooswitminecraft.dynamicwhips;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.world.InteractionHand;

/**
 * Tracks, per player, which rope (if any) their currently-held Leather Whip crack is anchored to
 * (MINECRAFT-86 criteria 2 and 8). This is the WHIP's own bookkeeping, distinct from the rope
 * itself: {@link io.github.brooswitminecraft.dynamicwhips.rope.RopeManager} owns the rope's
 * lifecycle (anchor gone, player gone, etc. — see {@code docs/rope-core.md} section 4); this
 * class only tracks "is this player's whip currently the one holding that rope open," so a
 * release, an item switch, or a drop can detach it, and so a rope the core tore down out from
 * under a still-held whip (anchor block broken, chunk unload) can be told apart from a live hold
 * (see {@link WhipItem#onUseTick}).
 *
 * <p>A small, bounded, static registry, same pattern as {@code RopeSpike}'s own per-player map —
 * not per-item NBT or a capability, since the whip never outlives a single server session's
 * player set anyway.
 */
final class WhipHoldState {
    record Hold(UUID ropeId, InteractionHand hand) {
    }

    private static final Map<UUID, Hold> HOLDS = new ConcurrentHashMap<>();

    private WhipHoldState() {
    }

    static void start(UUID playerId, UUID ropeId, InteractionHand hand) {
        HOLDS.put(playerId, new Hold(ropeId, hand));
    }

    static Hold get(UUID playerId) {
        return HOLDS.get(playerId);
    }

    /** Clears and returns the player's hold, if any — the caller is responsible for detaching
     * the rope itself (this class never touches {@code RopeManager}). */
    static Hold end(UUID playerId) {
        return HOLDS.remove(playerId);
    }

    /** Death, logout, dimension change (MINECRAFT-86 criterion 8): clear this player's own hold
     * bookkeeping alongside whatever tears the rope itself down. */
    static void clear(UUID playerId) {
        HOLDS.remove(playerId);
    }

    /** Server stopping: every hold, everywhere. */
    static void clearAll() {
        HOLDS.clear();
    }
}
