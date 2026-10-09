package io.github.brooswitminecraft.dynamicwhips;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.joml.Vector3d;
import org.slf4j.Logger;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.logging.LogUtils;

import dev.ryanhcode.sable.api.physics.object.rope.RopeHandle;
import dev.ryanhcode.sable.api.physics.object.rope.RopePhysicsObject;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * MINECRAFT-67 spike: a debug command that anchors a Sable rope at the block the player looks at
 * and pins the rope's free end to the player every server tick. Rope points are drawn with server
 * side particles, because Sable ships no rope renderer or rope network sync (see docs/rope-spike.md).
 *
 * <pre>
 * /dynamicwhips rope anchor [slack]   anchor a rope at the looked-at block (slack multiplier 1.0-3.0, default 1.1)
 * /dynamicwhips rope clear            remove your rope
 * </pre>
 */
final class RopeSpike {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int POINTS = 17;
    private static final double COLLISION_RADIUS = 0.25;
    private static final double MAX_REACH = 32.0;

    private record Active(RopePhysicsObject rope, SubLevelPhysicsSystem system, Vec3 anchor) {}

    private static final Map<UUID, Active> ROPES = new ConcurrentHashMap<>();

    private RopeSpike() {}

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("dynamicwhips").requires(s -> s.hasPermission(2))
                .then(Commands.literal("rope")
                        .then(Commands.literal("anchor")
                                .executes(ctx -> anchor(ctx, 1.1))
                                .then(Commands.argument("slack", DoubleArgumentType.doubleArg(1.0, 3.0))
                                        .executes(ctx -> anchor(ctx, DoubleArgumentType.getDouble(ctx, "slack")))))
                        .then(Commands.literal("clear").executes(RopeSpike::clearCommand))));
    }

    private static int anchor(CommandContext<CommandSourceStack> ctx, double slack) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Players only."));
            return 0;
        }
        HitResult hit = player.pick(MAX_REACH, 1.0f, false);
        if (hit.getType() != HitResult.Type.BLOCK) {
            source.sendFailure(Component.literal("Look at a block within " + (int) MAX_REACH + " blocks."));
            return 0;
        }
        BlockHitResult blockHit = (BlockHitResult) hit;
        SubLevelPhysicsSystem system = SubLevelPhysicsSystem.get(player.level());
        if (system == null) {
            source.sendFailure(Component.literal("Sable has no physics system in this dimension."));
            return 0;
        }
        clear(player.getUUID());

        // Start just off the hit face so the first rope point is not inside the block.
        Vec3 normal = Vec3.atLowerCornerOf(blockHit.getDirection().getNormal());
        Vec3 anchor = blockHit.getLocation().add(normal.scale(COLLISION_RADIUS + 0.05));
        Vec3 target = player.getBoundingBox().getCenter();
        Vec3 line = target.subtract(anchor);
        double spacing = line.length() * slack / (POINTS - 1);
        Vec3 dir = line.normalize();

        // Sable derives every segment length from the distance between the first two points, so the
        // points are laid out evenly along the line to the player, overshooting it by the slack.
        java.util.List<Vector3d> points = new java.util.ArrayList<>(POINTS);
        for (int i = 0; i < POINTS; i++) {
            Vec3 p = anchor.add(dir.scale(i * spacing));
            points.add(new Vector3d(p.x, p.y, p.z));
        }
        RopePhysicsObject rope = new RopePhysicsObject(points, COLLISION_RADIUS);
        system.addObject(rope);
        rope.setAttachment(RopeHandle.AttachmentPoint.START, new Vector3d(anchor.x, anchor.y, anchor.z), null);
        ROPES.put(player.getUUID(), new Active(rope, system, anchor));
        source.sendSuccess(() -> Component.literal(String.format(
                "Rope anchored at %.1f %.1f %.1f: %d points, %.2f block segments (%.1f total), radius %.2f.",
                anchor.x, anchor.y, anchor.z, POINTS, spacing, spacing * (POINTS - 1), COLLISION_RADIUS)), false);
        return 1;
    }

    private static int clearCommand(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player != null) {
            clear(player.getUUID());
        }
        return 1;
    }

    static void clear(UUID id) {
        Active active = ROPES.remove(id);
        if (active != null) {
            active.system().removeObject(active.rope());
        }
    }

    static void clearAll() {
        for (UUID id : ROPES.keySet()) {
            clear(id);
        }
    }

    static void tick(MinecraftServer server) {
        if (ROPES.isEmpty()) {
            return;
        }
        long time = server.overworld().getGameTime();
        for (Iterator<Map.Entry<UUID, Active>> it = ROPES.entrySet().iterator(); it.hasNext();) {
            Map.Entry<UUID, Active> entry = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            Active active = entry.getValue();
            if (player == null || !active.rope().isActive()) {
                continue;
            }
            // Pin the free end to the player. Sable has no entity attachment, only a world point
            // or a sub level, so the pin is refreshed every tick.
            Vec3 c = player.getBoundingBox().getCenter();
            active.rope().setAttachment(RopeHandle.AttachmentPoint.END, new Vector3d(c.x, c.y, c.z), null);
            // getPoints() is a view onto a field Sable only refreshes via updatePose() — this spike
            // and PlayerRope both read it raw for a long time, which is why the rope particles here
            // and RopeGameTests#catchOnObstruction's point dump both looked frozen at creation-time
            // layout. See docs/rope-core.md's CI history and PlayerRope#points() javadoc.
            active.rope().updatePose();

            ServerLevel level = player.serverLevel();
            if (time % 2 == 0) {
                for (Vector3d p : active.rope().getPoints()) {
                    level.sendParticles(ParticleTypes.CRIT, p.x, p.y, p.z, 1, 0, 0, 0, 0);
                }
            }
            if (time % 20 == 0) {
                report(player, active, c);
            }
        }
    }

    /** Max distance of any rope point from the straight anchor-to-player line, and the polyline length. */
    private static void report(ServerPlayer player, Active active, Vec3 playerCenter) {
        Vector3d a = new Vector3d(active.anchor().x, active.anchor().y, active.anchor().z);
        Vector3d b = new Vector3d(playerCenter.x, playerCenter.y, playerCenter.z);
        Vector3d ab = new Vector3d(b).sub(a);
        double abLen2 = Math.max(ab.lengthSquared(), 1.0e-9);
        double maxDeviation = 0;
        double length = 0;
        Vector3d prev = null;
        for (Vector3d p : active.rope().getPoints()) {
            double t = Math.max(0, Math.min(1, new Vector3d(p).sub(a).dot(ab) / abLen2));
            Vector3d closest = new Vector3d(ab).mul(t).add(a);
            maxDeviation = Math.max(maxDeviation, p.distance(closest));
            if (prev != null) {
                length += p.distance(prev);
            }
            prev = p;
        }
        String msg = String.format("rope: straight %.2f, rope length %.2f, max deviation from straight line %.2f",
                a.distance(b), length, maxDeviation);
        player.displayClientMessage(Component.literal(msg), true);
        LOGGER.info("[rope-spike] {}: {}", player.getGameProfile().getName(), msg);
    }
}
