package io.github.brooswitminecraft.dynamicwhips.rope;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import io.github.brooswitminecraft.dynamicwhips.rope.net.RopeNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Server-side registry and public API for ropes attached to players: MINECRAFT-85 acceptance
 * criterion 6. The three future callers (whip anchor, grappling hooks, harpoon) are expected to
 * go through this class and never touch {@link PlayerRope} or Sable directly.
 *
 * <p>Lifecycle (criterion 4) is driven entirely from {@link #tickAll} plus the explicit teardown
 * entry points below, which {@code DynamicWhipsMod} wires to the matching NeoForge events. No
 * rope outlives its owning player's session, its anchor, or the server.
 */
public final class RopeManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<UUID, PlayerRope> ROPES = new ConcurrentHashMap<>();
    private static final Map<UUID, Set<UUID>> BY_OWNER = new ConcurrentHashMap<>();

    private RopeManager() {
    }

    /** Attach a rope between {@code player} and a fixed world point (whip anchor, grappling hook). */
    public static UUID attachToPoint(ServerPlayer player, Vec3 worldPoint, BlockPos anchorBlock, double slack) {
        return register(player, PlayerRope.create(player, new RopeAnchor.WorldPoint(worldPoint, anchorBlock), slack));
    }

    /** Attach a rope between {@code player} and a moving entity (the harpoon's target). */
    public static UUID attachToEntity(ServerPlayer player, Entity target, double slack) {
        return register(player, PlayerRope.create(player, new RopeAnchor.EntityAnchor(target), slack));
    }

    /**
     * Test-support only: as {@link #attachToPoint}, but with an explicit segment spacing instead
     * of the shipped {@link RopeConstants#SEGMENT_SPACING}. Exists so {@code
     * RopeGameTests#tunnellingThreshold} can sweep spacing to find where tunnelling through a
     * block-sized obstruction starts (MINECRAFT-85 acceptance criterion 5); no shipped caller
     * should use this — use {@link #attachToPoint} instead.
     */
    public static UUID attachToPointWithSpacing(ServerPlayer player, Vec3 worldPoint, BlockPos anchorBlock,
            double slack, double segmentSpacing) {
        return register(player, PlayerRope.create(player, new RopeAnchor.WorldPoint(worldPoint, anchorBlock),
                slack, segmentSpacing));
    }

    private static UUID register(ServerPlayer player, PlayerRope rope) {
        if (rope == null) {
            return null;
        }
        ROPES.put(rope.id(), rope);
        BY_OWNER.computeIfAbsent(player.getUUID(), id -> ConcurrentHashMap.newKeySet()).add(rope.id());
        return rope.id();
    }

    /** Detach on request (criterion 4): whip input released, hook right-clicked again, etc. */
    public static void detach(UUID ropeId) {
        PlayerRope rope = ROPES.remove(ropeId);
        if (rope == null) {
            return;
        }
        rope.removeFromPhysics();
        Set<UUID> owned = BY_OWNER.get(rope.ownerId());
        if (owned != null) {
            owned.remove(ropeId);
        }
        RopeNetworking.sendRemove(rope);
    }

    /** Every rope {@code playerId} owns, detached: death, logout, dimension change. */
    public static void detachAllOwnedBy(UUID playerId) {
        Set<UUID> owned = BY_OWNER.remove(playerId);
        if (owned == null) {
            return;
        }
        for (UUID ropeId : owned) {
            PlayerRope rope = ROPES.remove(ropeId);
            if (rope != null) {
                rope.removeFromPhysics();
                RopeNetworking.sendRemove(rope);
            }
        }
    }

    /** Server stopping: every rope, everywhere, with no per-owner bookkeeping left behind. */
    public static void clearAll() {
        for (PlayerRope rope : ROPES.values()) {
            rope.removeFromPhysics();
        }
        ROPES.clear();
        BY_OWNER.clear();
    }

    public static Double length(UUID ropeId) {
        PlayerRope rope = ROPES.get(ropeId);
        return rope == null ? null : rope.restLength();
    }

    public static void adjustLength(UUID ropeId, double newLength) {
        PlayerRope rope = ROPES.get(ropeId);
        if (rope != null) {
            rope.adjustLength(newLength);
        }
    }

    public static void payOut(UUID ropeId) {
        PlayerRope rope = ROPES.get(ropeId);
        if (rope != null) {
            rope.payOut();
        }
    }

    public static void reelIn(UUID ropeId) {
        PlayerRope rope = ROPES.get(ropeId);
        if (rope != null) {
            rope.reelIn();
        }
    }

    /** Test/diagnostic access: the live rope object for {@code ropeId}, or null. GameTests use this. */
    public static PlayerRope get(UUID ropeId) {
        return ROPES.get(ropeId);
    }

    public static int activeRopeCount() {
        return ROPES.size();
    }

    /**
     * Runs every rope's physics-coupling tick (anchor/player pin + swing constraint), then every
     * {@link RopeConstants#SYNC_INTERVAL_TICKS} ticks sends updated points to tracking clients.
     * Detaches anything whose owner is gone, whose anchor is gone, or that Sable itself dropped
     * (chunk unload — see {@link PlayerRope#isLive}).
     */
    public static void tickAll(MinecraftServer server) {
        if (ROPES.isEmpty()) {
            return;
        }
        boolean sync = server.overworld().getGameTime() % RopeConstants.SYNC_INTERVAL_TICKS == 0;
        for (Iterator<Map.Entry<UUID, PlayerRope>> it = ROPES.entrySet().iterator(); it.hasNext();) {
            Map.Entry<UUID, PlayerRope> entry = it.next();
            PlayerRope rope = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(rope.ownerId());
            if (player == null) {
                it.remove();
                removeFromOwnerIndex(rope);
                rope.removeFromPhysics();
                continue;
            }
            ServerLevel level = player.serverLevel();
            if (!rope.isLive(level)) {
                LOGGER.debug("[rope-core] {} detached: anchor gone or Sable dropped the object", rope.id());
                it.remove();
                removeFromOwnerIndex(rope);
                rope.removeFromPhysics();
                RopeNetworking.sendRemove(rope);
                continue;
            }
            rope.tick(level, player);
            if (sync) {
                RopeNetworking.sendSync(rope, player);
            }
        }
    }

    private static void removeFromOwnerIndex(PlayerRope rope) {
        Set<UUID> owned = BY_OWNER.get(rope.ownerId());
        if (owned != null) {
            owned.remove(rope.id());
        }
    }
}
