package io.github.brooswitminecraft.dynamicwhips;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.github.brooswitminecraft.dynamicwhips.rope.RopeManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;

/**
 * Tracks, per player, which rope (if any) their currently-held Leather Whip crack is anchored to
 * (MINECRAFT-86 criteria 2, 3 and 8).
 *
 * <p><strong>Deliberately NOT built on vanilla's use-item system</strong> ({@code
 * startUsingItem}/{@code isUsingItem}/{@code onStopUsing}) — an earlier revision of this class
 * was built that way, and a review caught the real problem: {@code LocalPlayer#aiStep} scales
 * movement input
 * ({@code leftImpulse}/{@code forwardImpulse}) to 20% unconditionally whenever {@code
 * isUsingItem()} is true (the bow/shield/eating draw-slowdown), with no NeoForge extension point
 * to opt out short of a Mixin this project does not otherwise use. That directly fights criterion
 * 3's "ordinary air control" and criterion 4's momentum traversal/careful descent. See {@code
 * docs/whip.md} section 1 for the full account, including the exact source lines.
 *
 * <p>Instead, the client sends a {@link WhipHoldPingPayload} every tick its use key is held with
 * a whip in hand ({@code WhipClientInput}), and {@link #tickTimeouts} treats an absence of pings
 * for {@link #TIMEOUT_TICKS} as "released." This one mechanism uniformly covers a normal release,
 * switching away from the whip, and dropping it — all three simply stop the client from pinging,
 * and look identical from here — which is simpler than the three separate vanilla hooks the
 * previous revision needed. {@link #tickTimeouts} also clears a hold the instant {@code
 * RopeManager} has already torn the rope down on its own (anchor block broken, chunk unload),
 * without waiting out the timeout.
 */
final class WhipHoldState {
    /**
     * Ticks of silence before a hold is treated as released. Must comfortably exceed one round
     * trip of ordinary network jitter (ticks are 50ms) so a brief hiccup never drops a genuinely
     * still-held anchor, while staying short enough that letting go feels responsive.
     */
    static final long TIMEOUT_TICKS = 5;

    record Hold(UUID ropeId, InteractionHand hand) {
    }

    private static final Map<UUID, Hold> HOLDS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_PING = new ConcurrentHashMap<>();

    private WhipHoldState() {
    }

    /** {@code gameTime} is the tick the anchor was created on — gives the hold one full timeout
     * window of grace before a first real ping has to arrive, absorbing ordinary latency between
     * the server attaching and the client's own next tick sending one. */
    static void start(UUID playerId, UUID ropeId, InteractionHand hand, long gameTime) {
        HOLDS.put(playerId, new Hold(ropeId, hand));
        LAST_PING.put(playerId, gameTime);
    }

    static Hold get(UUID playerId) {
        return HOLDS.get(playerId);
    }

    /** Refreshes the timeout clock for a player with a live hold; a silent no-op for a player
     * with none (an extra ping from a client that is simply holding the key with no active
     * anchor is expected and harmless). */
    static void ping(UUID playerId, long gameTime) {
        if (HOLDS.containsKey(playerId)) {
            LAST_PING.put(playerId, gameTime);
        }
    }

    /** Death, logout, dimension change, server stop (MINECRAFT-86 criterion 8): clear this
     * player's own hold bookkeeping alongside whatever tears the rope itself down. */
    static void clear(UUID playerId) {
        HOLDS.remove(playerId);
        LAST_PING.remove(playerId);
    }

    static void clearAll() {
        HOLDS.clear();
        LAST_PING.clear();
    }

    /**
     * Every server tick: detach any hold whose rope is already gone (the rope core tore it down
     * on its own — anchor block broken, chunk unload) immediately, and any hold whose client has
     * gone quiet for {@link #TIMEOUT_TICKS} — a normal release, a switched-away whip, and a
     * dropped whip are indistinguishable from here, since all three simply stop the pings.
     */
    static void tickTimeouts(MinecraftServer server) {
        if (HOLDS.isEmpty()) {
            return;
        }
        long now = server.overworld().getGameTime();
        for (Iterator<Map.Entry<UUID, Hold>> it = HOLDS.entrySet().iterator(); it.hasNext();) {
            Map.Entry<UUID, Hold> entry = it.next();
            UUID playerId = entry.getKey();
            Hold hold = entry.getValue();
            boolean ropeAlreadyGone = RopeManager.get(hold.ropeId()) == null;
            long lastPing = LAST_PING.getOrDefault(playerId, now);
            boolean timedOut = now - lastPing > TIMEOUT_TICKS;
            if (ropeAlreadyGone || timedOut) {
                if (!ropeAlreadyGone) {
                    RopeManager.detach(hold.ropeId());
                }
                it.remove();
                LAST_PING.remove(playerId);
            }
        }
    }
}
