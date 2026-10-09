package io.github.brooswitminecraft.dynamicwhips;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.github.brooswitminecraft.dynamicwhips.rope.RopeManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;

/**
 * Tracks, per player, which rope (if any) their currently-held Leather Whip crack is anchored to
 * (MINECRAFT-86 criteria 2, 3 and 8).
 *
 * <p><strong>Deliberately NOT built on vanilla's use-item system</strong> ({@code
 * startUsingItem}/{@code isUsingItem}/{@code onStopUsing}) — an earlier revision of this class
 * was built that way, and a review caught the real problem: {@code LocalPlayer#aiStep} scales
 * movement input ({@code leftImpulse}/{@code forwardImpulse}) to 20% unconditionally whenever {@code
 * isUsingItem()} is true (the bow/shield/eating draw-slowdown), with no NeoForge extension point
 * to opt out short of a Mixin this project does not otherwise use. That directly fights criterion
 * 3's "ordinary air control" and criterion 4's momentum traversal/careful descent. See {@code
 * docs/whip.md} section 1 for the full account, including the exact source lines.
 *
 * <p>Instead, the client sends a {@link WhipHoldPingPayload} every tick its use key is held with
 * a whip in hand ({@code WhipClientInput}), and {@link #tickTimeouts} treats an absence of pings
 * for {@link #TIMEOUT_TICKS} as "released" — the fallback for lost packets or a disconnect, not
 * the primary release path (see below).
 *
 * <p><strong>Two things a review added on top of the ping/timeout design, both load-bearing:</strong>
 * <ol>
 *   <li><strong>Immediate, explicit release.</strong> A pure timeout would let the rope keep
 *   constraining the player for up to {@link #TIMEOUT_TICKS} after the key goes up — well past
 *   the apex of a swing, defeating "releasing at the right moment" (criteria 2/4). On the
 *   client's own key-up edge, {@code WhipClientInput} sends a {@code WhipReleasePayload}, whose
 *   handler calls {@link #releaseNow} — detaches synchronously, in the same tick the server
 *   receives it, no timeout wait at all.</li>
 *   <li><strong>Server authority over "still holding."</strong> {@link #ping} trusts the client
 *   that it is still holding a whip; a client that kept pinging while holding anything else (or
 *   nothing) would keep the rope alive until the timeout, or forever if it never stops pinging.
 *   {@link #tickTimeouts} independently checks, every tick, that the owning {@link ServerPlayer}
 *   still has a {@link WhipItem} in the exact hand ({@link Hold#hand()}) recorded at attach —
 *   switching items or dropping the whip is caught here immediately, with no dependence on the
 *   client sending anything at all (ping, release, or otherwise).</li>
 * </ol>
 *
 * <p>{@link #tickTimeouts} also clears a hold the instant {@code RopeManager} has already torn
 * the rope down on its own (anchor block broken, chunk unload), without waiting out the timeout.
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
     * The client's own key-up edge ({@code WhipReleasePayload}): detaches synchronously, in
     * whatever tick the server receives it — no timeout wait, so letting go at the apex of a
     * swing actually lets go right then (criteria 2/4).
     */
    static void releaseNow(UUID playerId) {
        Hold hold = HOLDS.remove(playerId);
        LAST_PING.remove(playerId);
        if (hold != null) {
            RopeManager.detach(hold.ropeId());
        }
    }

    /**
     * Every server tick: detach any hold whose rope is already gone (the rope core tore it down
     * on its own — anchor block broken, chunk unload) immediately; detach any hold whose owning
     * player no longer actually has a {@link WhipItem} in the recorded hand immediately (server
     * authority over "still holding" — does not depend on the client sending anything at all);
     * and, as a fallback for a lost {@code WhipReleasePayload} or a disconnect rather than the
     * primary release path, detach any hold whose client has gone quiet for {@link
     * #TIMEOUT_TICKS}.
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
            boolean switchedAway = !ropeAlreadyGone && !stillHoldingWhip(server, playerId, hold);
            long lastPing = LAST_PING.getOrDefault(playerId, now);
            boolean timedOut = now - lastPing > TIMEOUT_TICKS;
            if (ropeAlreadyGone || switchedAway || timedOut) {
                if (!ropeAlreadyGone) {
                    RopeManager.detach(hold.ropeId());
                }
                it.remove();
                LAST_PING.remove(playerId);
            }
        }
    }

    /** False if the player is gone (another path already handles that) or no longer has a whip
     * in the exact hand {@code hold} was created for — catches switching items or dropping the
     * whip immediately, independent of whether the client ever says anything about it. */
    private static boolean stillHoldingWhip(MinecraftServer server, UUID playerId, Hold hold) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        return player != null && player.getItemInHand(hold.hand()).getItem() instanceof WhipItem;
    }
}
