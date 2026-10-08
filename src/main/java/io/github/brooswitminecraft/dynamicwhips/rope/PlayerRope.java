package io.github.brooswitminecraft.dynamicwhips.rope;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.joml.Vector3d;

import dev.ryanhcode.sable.api.physics.object.rope.RopeHandle;
import dev.ryanhcode.sable.api.physics.object.rope.RopePhysicsObject;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * One live rope: a Sable {@code RopePhysicsObject} whose {@code START} is this mod's own
 * {@link RopeAnchor} (a world point or, for the harpoon, an entity — Sable supports neither
 * natively, see docs/rope-spike.md section 1) and whose {@code END} is pinned to a player every
 * tick, with the resulting swing constraint applied to that player (MINECRAFT-85's central gap).
 *
 * <p>Not thread safe; every method here runs on the server tick thread, same as the
 * {@code ServerTickEvent.Post} hook that drives it.
 */
public final class PlayerRope {
    private final UUID id = UUID.randomUUID();
    private final UUID ownerId;
    private final RopeAnchor anchor;
    private final RopePhysicsObject rope;
    private final SubLevelPhysicsSystem system;
    private final double segmentSpacing;
    private int pointCount;

    private PlayerRope(UUID ownerId, RopeAnchor anchor, RopePhysicsObject rope, SubLevelPhysicsSystem system,
            double segmentSpacing, int pointCount) {
        this.ownerId = ownerId;
        this.anchor = anchor;
        this.rope = rope;
        this.system = system;
        this.segmentSpacing = segmentSpacing;
        this.pointCount = pointCount;
    }

    /** Shipped entry point: every real caller uses {@link RopeConstants#SEGMENT_SPACING}. */
    static PlayerRope create(ServerPlayer player, RopeAnchor anchor, double slack) {
        return create(player, anchor, slack, RopeConstants.SEGMENT_SPACING);
    }

    /**
     * Creates and registers a new rope between {@code anchor} and {@code player}'s current
     * position, laid out dense enough to use {@code segmentSpacing} (clamped to
     * {@link RopeConstants#MIN_POINTS}..{@link RopeConstants#MAX_POINTS} points), overshooting the
     * straight-line distance by {@code slack} so the rope has room to bend before going taut.
     *
     * <p>{@code segmentSpacing} is a parameter, not always {@link RopeConstants#SEGMENT_SPACING},
     * only so {@code RopeGameTests#tunnellingThreshold} can sweep it to find the spacing at which
     * tunnelling through a block-sized obstruction starts (MINECRAFT-85 acceptance criterion 5);
     * every shipped caller goes through the single-argument {@link #create(ServerPlayer,
     * RopeAnchor, double)} overload above, which always uses the named constant.
     *
     * @return null if Sable has no physics system in this dimension.
     */
    static PlayerRope create(ServerPlayer player, RopeAnchor anchor, double slack, double segmentSpacing) {
        ServerLevel level = player.serverLevel();
        SubLevelPhysicsSystem system = SubLevelPhysicsSystem.get(level);
        if (system == null) {
            return null;
        }
        Vec3 anchorPos = anchor.currentPosition(level);
        Vec3 playerPos = player.getBoundingBox().getCenter();
        double straightLine = Math.max(anchorPos.distanceTo(playerPos), 1.0e-3);
        int pointCount = (int) Math.ceil((straightLine * slack) / segmentSpacing) + 1;
        pointCount = Math.max(RopeConstants.MIN_POINTS, Math.min(RopeConstants.MAX_POINTS, pointCount));

        List<Vector3d> points = RopeMath.layOutPoints(anchorPos, playerPos, slack, pointCount);
        RopePhysicsObject rope = new RopePhysicsObject(points, RopeConstants.COLLISION_RADIUS);
        system.addObject(rope);
        rope.setAttachment(RopeHandle.AttachmentPoint.START, RopeMath.toVector3d(anchorPos), null);
        rope.setAttachment(RopeHandle.AttachmentPoint.END, RopeMath.toVector3d(playerPos), null);
        // Rapier scene objects can start (or fall back) asleep, in which case the solver never
        // steps them — getPoints() then keeps returning this exact initial layout forever. Neither
        // this mod nor the MINECRAFT-67 spike called this before a GameTest caught the rope's
        // points sitting frozen at their creation-time layout after 170 ticks; see
        // docs/rope-core.md's CI history.
        rope.wakeUp();
        return new PlayerRope(player.getUUID(), anchor, rope, system, segmentSpacing, pointCount);
    }

    public UUID id() {
        return id;
    }

    public UUID ownerId() {
        return ownerId;
    }

    public RopeAnchor anchor() {
        return anchor;
    }

    /** False once this rope must be torn down: the anchor is gone, or Sable itself dropped the object. */
    boolean isLive(ServerLevel level) {
        return rope.isActive() && !anchor.isGone(level);
    }

    void removeFromPhysics() {
        system.removeObject(rope);
    }

    /** Current rope points, server-authoritative, for the sync packet and for GameTest assertions. */
    public List<Vector3d> points() {
        return rope.getPoints();
    }

    /**
     * Configured rest length: this rope's segment spacing (normally
     * {@link RopeConstants#SEGMENT_SPACING}) times the number of segments. This is the rope's
     * maximum extension, not its current (possibly slack) drawn length — see
     * {@link #currentDrawnLength()} for that.
     */
    public double restLength() {
        return segmentSpacing * (pointCount - 1);
    }

    /** Sum of point-to-point distances right now, bends included; used for performance reporting. */
    public double currentDrawnLength() {
        return RopeMath.polylineLength(rope.getPoints());
    }

    public int pointCount() {
        return pointCount;
    }

    /**
     * Grows the rope by one segment at the anchor end (MINECRAFT-85 acceptance criterion 6's
     * pay-out primitive). No item calls this yet. No-op at {@link RopeConstants#MAX_POINTS}.
     */
    public void payOut() {
        if (pointCount >= RopeConstants.MAX_POINTS) {
            return;
        }
        List<Vector3d> current = rope.getPoints();
        Vector3d first = current.get(0);
        Vector3d second = current.get(1);
        Vector3d extended = new Vector3d(first).add(new Vector3d(first).sub(second).normalize(segmentSpacing));
        rope.addPoint(extended);
        pointCount++;
    }

    /**
     * Shrinks the rope by one segment at the anchor end (MINECRAFT-85 acceptance criterion 6's
     * reel-in primitive). No item calls this yet. No-op at {@link RopeConstants#MIN_POINTS}.
     */
    public void reelIn() {
        if (pointCount <= RopeConstants.MIN_POINTS) {
            return;
        }
        rope.removeFirstPoint();
        pointCount--;
    }

    /** Repeated {@link #payOut()}/{@link #reelIn()} until {@link #restLength()} matches {@code target}. */
    public void adjustLength(double target) {
        double clamped = Math.max(segmentSpacing * (RopeConstants.MIN_POINTS - 1),
                Math.min(RopeConstants.MAX_LENGTH, target));
        while (restLength() < clamped - segmentSpacing / 2 && pointCount < RopeConstants.MAX_POINTS) {
            payOut();
        }
        while (restLength() > clamped + segmentSpacing / 2 && pointCount > RopeConstants.MIN_POINTS) {
            reelIn();
        }
    }

    /**
     * Re-pins both ends (the anchor end only moves for an {@link RopeAnchor.EntityAnchor}; a
     * world point never changes after creation, so re-sending it is harmless but skipped) and
     * applies the swing constraint to {@code player}: see {@link RopeMath#swingCorrection}.
     */
    void tick(ServerLevel level, ServerPlayer player) {
        // Defensive: re-assert awake every tick in case the solver puts an apparently-settled
        // rope back to sleep (a real player's own weight/movement keeps a sleeping object's
        // owning body moving enough to avoid this in normal play; a kinematic END pin alone may
        // not). Cheap relative to the rest of this method.
        rope.wakeUp();

        if (anchor instanceof RopeAnchor.EntityAnchor) {
            Vec3 anchorPos = anchor.currentPosition(level);
            rope.setAttachment(RopeHandle.AttachmentPoint.START, RopeMath.toVector3d(anchorPos), null);
        }

        List<Vector3d> points = rope.getPoints();
        if (points.size() < 2) {
            return;
        }
        Vec3 playerPos = player.getBoundingBox().getCenter();
        // The pivot is wherever the chain's own last real bend is, found dynamically each tick:
        // Sable's solver has already bent everything before it around any obstruction, so
        // clamping the player to (segments between pivot and player) rest-lengths from THIS point
        // — not a fixed one segment out — is what turns a bent chain into a swing around the
        // obstruction instead of a swing only around the anchor. See RopeMath#findPivotIndex.
        int pivotIndex = RopeMath.findPivotIndex(points, playerPos, segmentSpacing);
        Vec3 pivot = RopeMath.toVec3(points.get(pivotIndex));
        double allowedRadius = (points.size() - 1 - pivotIndex) * segmentSpacing;
        Vec3 velocity = player.getDeltaMovement();

        Vec3[] correction = RopeMath.swingCorrection(pivot, playerPos, velocity, allowedRadius);
        if (correction != null) {
            Vec3 correctedCenter = correction[0];
            Vec3 feetOffset = playerPos.subtract(player.position());
            player.setPos(correctedCenter.x - feetOffset.x, correctedCenter.y - feetOffset.y, correctedCenter.z - feetOffset.z);
            player.setDeltaMovement(correction[1]);
            player.fallDistance = 0;
            playerPos = correctedCenter;
        }

        rope.setAttachment(RopeHandle.AttachmentPoint.END, RopeMath.toVector3d(playerPos), null);
    }

    /** Snapshot of points as a flat float array for the sync packet: 3 floats per point. */
    public float[] pointsAsFloats() {
        List<Vector3d> points = rope.getPoints();
        float[] out = new float[points.size() * 3];
        for (int i = 0; i < points.size(); i++) {
            Vector3d p = points.get(i);
            out[i * 3] = (float) p.x;
            out[i * 3 + 1] = (float) p.y;
            out[i * 3 + 2] = (float) p.z;
        }
        return out;
    }

    static List<Vector3d> copyPoints(RopePhysicsObject rope) {
        return new ArrayList<>(rope.getPoints());
    }
}
