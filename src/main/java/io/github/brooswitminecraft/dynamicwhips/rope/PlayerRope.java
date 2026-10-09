package io.github.brooswitminecraft.dynamicwhips.rope;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.joml.Vector3d;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import dev.ryanhcode.sable.api.physics.object.rope.RopeHandle;
import dev.ryanhcode.sable.api.physics.object.rope.RopePhysicsObject;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
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
    private static final Logger LOGGER = LogUtils.getLogger();
    private final UUID id = UUID.randomUUID();
    private final UUID ownerId;
    private final RopeAnchor anchor;
    private final RopePhysicsObject rope;
    private final SubLevelPhysicsSystem system;
    /**
     * The spacing this rope's points were ACTUALLY laid out at, not the spacing {@link #create}
     * was asked for: {@code pointCount} is clamped to {@link RopeConstants#MIN_POINTS}..
     * {@link RopeConstants#MAX_POINTS} before {@link RopeMath#layOutPoints} spreads it over
     * {@code straightLine * slack}, so the real spacing is {@code straightLine * slack /
     * (pointCount - 1)} and only equals the requested spacing when the clamp didn't bite. Every
     * consumer below ({@link #restLength}, {@link #tick}'s {@code allowedRadius}, {@link #payOut},
     * {@link #adjustLength}) needs the real number, since it's what Sable's solver actually built.
     */
    private final double segmentSpacing;
    private int pointCount;
    /**
     * MINECRAFT-190: whole segments queued to add ({@code > 0}) or remove ({@code < 0}) at the
     * anchor end, not yet turned into a real {@code RopePhysicsObject} point. {@link #payOut} and
     * {@link #reelIn} only ever change this counter (and the physically-real {@link
     * #firstSegmentLength} stub below) — see {@link #commitPendingSegment} for why the actual
     * native {@code addPoint}/{@code removeFirstPoint} call is throttled to at most one every
     * {@link RopeConstants#STRUCTURAL_COMMIT_INTERVAL_TICKS} ticks instead of happening here
     * directly.
     */
    private int pendingSegments;
    /**
     * MINECRAFT-190: the CURRENT, physically real length of the segment between the fixed anchor
     * attachment and {@code points().get(0)}, set via {@code RopeHandle#setFirstSegmentLength}
     * (an API that existed, unused, before this fix — decompiling the shipped Sable jar found no
     * other caller of it anywhere). Always either {@code 0.0} (steady state, or a shrink is
     * pending) or {@link #segmentSpacing} (a grow is pending) — see {@link #payOut}/{@link
     * #reelIn}. This is the ONE piece of pending growth that is ever physically real before a
     * commit, and is what lets {@link #tick}'s {@code allowedRadius} grow immediately on the
     * first {@link #payOut} call rather than waiting for a throttled structural commit.
     */
    private double firstSegmentLength;
    /** MINECRAFT-190: incremented once per {@link #tick} call; the clock {@link
     * #commitPendingSegment}'s throttle reads against. Not wall-clock or server game-time — this
     * rope's own tick count is all the throttle needs. */
    private long localTick;
    /** MINECRAFT-190: the {@link #localTick} value at the last structural commit. Initialized far
     * enough in the past that the very first commit is never blocked by the throttle. */
    private long lastStructuralCommitTick = Long.MIN_VALUE / 2;
    /** MINECRAFT-190: the level seen by the most recent {@link #tick} call — used by {@link
     * #commitPendingSegment}'s obstruction guard. Null until the first tick; that guard fails
     * OPEN (allows the removal) when null, matching this mod's behavior before the guard existed. */
    private ServerLevel lastKnownLevel;

    private PlayerRope(UUID ownerId, RopeAnchor anchor, RopePhysicsObject rope, SubLevelPhysicsSystem system,
            double actualSegmentSpacing, int pointCount) {
        this.ownerId = ownerId;
        this.anchor = anchor;
        this.rope = rope;
        this.system = system;
        this.segmentSpacing = actualSegmentSpacing;
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
        // The requested spacing above only survives the clamp when MIN_POINTS..MAX_POINTS didn't
        // bite; what layOutPoints below actually builds is this, and it's what every downstream
        // consumer (restLength, allowedRadius, payOut, adjustLength) must use instead.
        double actualSpacing = (straightLine * slack) / (pointCount - 1);

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
        return new PlayerRope(player.getUUID(), anchor, rope, system, actualSpacing, pointCount);
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

    /**
     * Current rope points, server-authoritative, for the sync packet and for GameTest assertions.
     *
     * <p>{@code rope.getPoints()} (Sable's own API) returns a view onto a field that is ONLY ever
     * populated by {@code RopeHandle#readPose}, which Sable's own {@code RopePhysicsObject
     * #updatePose()} calls — and nothing in this mod, nor the MINECRAFT-67 spike, ever called
     * {@code updatePose()}. Verified by disassembling the shipped Sable jar (only the bytecode is
     * available): {@code getPoints()} is a one-line field return with no refresh of its own, and
     * nothing else in {@code RopePhysicsObject} (not {@code onAddition}, not {@code wakeUp})
     * calls {@code updatePose()} either — see docs/rope-core.md's CI history for the exact
     * decompiled bytecode. Without this call, every read of the rope's points is the creation-time
     * layout forever, which is exactly the "frozen rope" MINECRAFT-85 escalated to the epic as an
     * unresolved question about Sable's solver; it turned out to be a bug in how THIS mod read
     * Sable's own state, not a question about the solver at all.
     */
    public List<Vector3d> points() {
        rope.updatePose();
        return rope.getPoints();
    }

    /**
     * Configured rest length: this rope's ACTUAL segment spacing (see {@link #segmentSpacing},
     * which can differ from {@link RopeConstants#SEGMENT_SPACING} when the point-count clamp
     * bit) times the number of segments, PLUS {@link #pendingSegments} not yet turned into real
     * {@code RopePhysicsObject} points (MINECRAFT-190). This is the rope's TARGET maximum
     * extension — what {@link RopeManager#length} reports, and what climbs immediately on every
     * {@link #payOut}/{@link #reelIn} call exactly as it always did — not necessarily what is
     * physically reachable THIS tick if a structural commit is still pending; see {@link #tick}'s
     * {@code allowedRadius} for the physically-real number. Not the rope's current (possibly
     * slack) drawn length either — see {@link #currentDrawnLength()} for that.
     */
    public double restLength() {
        return segmentSpacing * (pointCount - 1 + pendingSegments);
    }

    /** Sum of point-to-point distances right now, bends included; used for performance reporting. */
    public double currentDrawnLength() {
        return RopeMath.polylineLength(points());
    }

    public int pointCount() {
        return pointCount;
    }

    /**
     * Grows the rope by one segment at the anchor end (MINECRAFT-85 acceptance criterion 6's
     * pay-out primitive). No-op once {@link #restLength()} (including anything already queued)
     * would reach {@link RopeConstants#MAX_POINTS}' worth of length.
     *
     * <p>MINECRAFT-190 rewrite. The previous implementation called {@code
     * RopePhysicsObject#addPoint} directly, every call: decompiling the shipped Sable jar shows
     * {@code addPoint} always prepends the new point at array index 0 AND THEN re-fires {@code
     * setAttachment(START, <the original fixed anchor Vec3>)} — so whatever position the caller
     * passed in was immediately overridden; the native layer snaps the brand-new point back onto
     * the literal fixed anchor location, collapsing the "extra" segment to zero usable length.
     * The OLD point (now at index 1) is left physically unconstrained but with nothing pulling it
     * outward, so {@link RopeMath#findPivotIndex}'s taut-chain walk never recognized it as
     * reachable for a plain, unobstructed hang — this was MINECRAFT-189 bug 1, the player's real
     * distance from the anchor never following {@code RopeManager.length()}'s own nominal number.
     *
     * <p>The fix uses {@code RopeHandle#setFirstSegmentLength} — present in the Sable API but,
     * per the same decompilation, called by nothing in this codebase before now — to grow the
     * segment between the fixed anchor attachment and {@code points().get(0)} to a full {@link
     * #segmentSpacing} immediately. That stretch is real and physical (not subject to the
     * re-pinning above, since {@code setFirstSegmentLength} never touches {@code setAttachment}
     * at all), so {@link #tick}'s {@code allowedRadius} can include it the moment the pivot walk
     * reaches the anchor-pinned point — see that method. Only turning this stub into a REAL,
     * permanent point (promoting it via {@link #commitPendingSegment}, which still calls {@code
     * addPoint} exactly as before — now safe because the OLD point has already been pushed to the
     * correct distance by the stub, so the re-pinned NEW point and the promoted old one end up
     * properly, uniformly spaced) is throttled, to fix MINECRAFT-189 bug 2 (see
     * {@link RopeConstants#STRUCTURAL_COMMIT_INTERVAL_TICKS}); the physical reachability fix above
     * does not depend on that throttle at all.
     */
    public void payOut() {
        // Point-count based, like the original contract, projected forward by any already-queued
        // growth — NOT a restLength()/MAX_LENGTH comparison, which would misfire once this rope's
        // own actual segmentSpacing differs from RopeConstants.SEGMENT_SPACING (see that field's
        // javadoc on the MAX_POINTS clamp widening it for long ropes).
        if (pointCount + Math.max(pendingSegments, 0) >= RopeConstants.MAX_POINTS) {
            return;
        }
        pendingSegments++;
        syncFirstSegmentLength();
        commitPendingSegment();
    }

    /**
     * Shrinks the rope by one segment at the anchor end (MINECRAFT-85 acceptance criterion 6's
     * reel-in primitive). No-op once {@link #restLength()} (including anything already queued)
     * would reach {@link RopeConstants#MIN_POINTS}' worth of length. See {@link #payOut}'s javadoc
     * for the MINECRAFT-190 rewrite this mirrors.
     */
    public void reelIn() {
        if (pointCount + Math.min(pendingSegments, 0) <= RopeConstants.MIN_POINTS) {
            return;
        }
        pendingSegments--;
        syncFirstSegmentLength();
        commitPendingSegment();
    }

    /**
     * Sets a new target length, reachable via however many {@link #payOut}/{@link #reelIn}
     * segment-equivalents that takes — queued on {@link #pendingSegments} in one step, never by
     * looping a call to either in a single invocation.
     *
     * <p>MINECRAFT-190 bug 5 fix. The previous implementation looped {@link #payOut}/{@link
     * #reelIn} directly — for a 64-block rope reeled to half length, roughly 71 synchronous
     * {@code RopePhysicsObject#removeFirstPoint} native calls in a single server tick — and that
     * is exactly what MINECRAFT-178's CI measured tearing the rope down entirely (4 of 4 runs;
     * {@code RopeManager#get} returned null afterward in every one). This method only ever
     * changes {@link #pendingSegments} by the full delta at once (an O(1) counter update, no
     * native call at all) and nudges {@link #firstSegmentLength} to match; {@link #tick}'s own
     * per-tick {@link #commitPendingSegment} call is what drains that backlog toward real points,
     * at the same throttled, known-safe cadence {@link #payOut}/{@link #reelIn} already use — so
     * a huge adjustLength delta costs exactly the same ONE native mutation per tick a huge run of
     * individual payOut calls would, never more.
     */
    public void adjustLength(double target) {
        double clamped = Math.max(segmentSpacing * (RopeConstants.MIN_POINTS - 1),
                Math.min(RopeConstants.MAX_LENGTH, target));
        int deltaSegments = (int) Math.round((clamped - restLength()) / segmentSpacing);
        if (deltaSegments == 0) {
            return;
        }
        int minPending = RopeConstants.MIN_POINTS - pointCount;
        int maxPending = RopeConstants.MAX_POINTS - pointCount;
        pendingSegments = Math.max(minPending, Math.min(maxPending, pendingSegments + deltaSegments));
        syncFirstSegmentLength();
    }

    /**
     * Sends {@link #firstSegmentLength} (derived purely from {@link #pendingSegments}'s sign — see
     * the field's own javadoc) to the native layer. Called whenever either changes; never leaves
     * the two out of sync.
     */
    private void syncFirstSegmentLength() {
        firstSegmentLength = pendingSegments > 0 ? segmentSpacing : 0.0;
        rope.setFirstSegmentLength(firstSegmentLength);
    }

    /**
     * Turns at most ONE queued {@link #pendingSegments} unit into a real {@code
     * RopePhysicsObject} point, if {@link RopeConstants#STRUCTURAL_COMMIT_INTERVAL_TICKS} ticks
     * have passed since the last such commit on this rope. See {@link #payOut}'s javadoc for why
     * this specific native call ({@code addPoint}/{@code removeFirstPoint}) is the one being
     * throttled, not the physical stub growth above it.
     */
    private void commitPendingSegment() {
        if (pendingSegments == 0) {
            return;
        }
        if (localTick - lastStructuralCommitTick < RopeConstants.STRUCTURAL_COMMIT_INTERVAL_TICKS) {
            return;
        }
        if (pendingSegments < 0 && ropeIsNearSolidObstruction()) {
            // MINECRAFT-190 bug 5, round 2 (see docs/rope-core.md section 11.3's own revision):
            // pacing the structural commit to once every STRUCTURAL_COMMIT_INTERVAL_TICKS, on its
            // own, did NOT stop the 64-block obstructed reel-in teardown — CI still tore the rope
            // down with this exact pacing already in place, just later than the old synchronous
            // loop did. This guard instead defers EVERY reel-in commit (not just the one about to
            // remove a point) for as long as ANY of this rope's own chain points (excluding the
            // anchor-end point itself, which sits at the fixed anchor attachment and is very
            // often inside or against a solid block by ordinary design — that is not what this
            // guard means by "obstructed") is resting near a solid block — i.e. for as long as
            // this rope is genuinely caught on something, matching the exact scenario MINECRAFT-
            // 178 measured breaking. Growth (addPoint) is never guarded, since bug 5 was only
            // ever observed on reel-in. A rope permanently caught right at its current length
            // could in principle never fully drain while this guard holds — an intentional
            // tradeoff (stuck-but-alive) over the alternative this is fixing (torn down).
            LOGGER.debug("[rope-core] {} deferring reel-in commit: rope is resting against a solid"
                    + " obstruction (MINECRAFT-190 bug 5 guard)", id);
            return;
        }
        if (pendingSegments > 0) {
            // firstSegmentLength is segmentSpacing right now (pendingSegments > 0): points().get(0)
            // has already been pushed that far from the fixed anchor by setFirstSegmentLength
            // above. Promoting it means prepending a new point at the anchor's own position — it
            // will be re-pinned there by Sable's own setAttachment re-fire inside addPoint
            // regardless of what we pass, same as the old implementation — which leaves the OLD
            // point(now index 1) at exactly segmentSpacing from the new anchor-pinned point: a
            // real, uniformly-spaced, properly reachable segment, not a collapsed one.
            Vector3d anchorPoint = points().get(0);
            rope.addPoint(anchorPoint);
            pointCount++;
            pendingSegments--;
        } else {
            rope.removeFirstPoint();
            pointCount--;
            pendingSegments++;
        }
        lastStructuralCommitTick = localTick;
        syncFirstSegmentLength();
    }

    /**
     * True if any of this rope's chain points OTHER THAN the anchor-end one (index 0 — see {@link
     * #commitPendingSegment}'s own javadoc for why that one is excluded) is within one block of a
     * solid, motion-blocking block: close enough to plausibly be the point of contact a catch on
     * an obstruction settles at, not merely "somewhere in the same chunk as a wall". Fails OPEN
     * (returns false, i.e. "safe to remove") if {@link #lastKnownLevel} is still null (no {@link
     * #tick} call has happened yet), matching this mod's behavior before this guard existed in
     * that narrow window.
     */
    private boolean ropeIsNearSolidObstruction() {
        if (lastKnownLevel == null) {
            return false;
        }
        List<Vector3d> current = points();
        for (int i = 1; i < current.size(); i++) {
            Vector3d p = current.get(i);
            BlockPos center = BlockPos.containing(p.x, p.y, p.z);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos probe = center.offset(dx, dy, dz);
                        if (lastKnownLevel.getBlockState(probe).blocksMotion()) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * Re-pins both ends (the anchor end only moves for an {@link RopeAnchor.EntityAnchor}; a
     * world point never changes after creation, so re-sending it is harmless but skipped) and
     * applies the swing constraint to {@code player}: see {@link RopeMath#swingCorrection}.
     */
    void tick(ServerLevel level, ServerPlayer player) {
        localTick++;
        lastKnownLevel = level;
        // MINECRAFT-190: drains at most one queued payOut/reelIn/adjustLength segment into a real
        // point per call, throttled — see commitPendingSegment's own javadoc. Runs even if nothing
        // called payOut/reelIn this specific tick (adjustLength may have queued a large backlog
        // with no further caller activity at all), which is what lets a huge adjustLength delta
        // keep draining on its own instead of getting stuck.
        commitPendingSegment();

        // Defensive: re-assert awake every tick in case the solver puts an apparently-settled
        // rope back to sleep (a real player's own weight/movement keeps a sleeping object's
        // owning body moving enough to avoid this in normal play; a kinematic END pin alone may
        // not). Cheap relative to the rest of this method.
        rope.wakeUp();

        if (anchor instanceof RopeAnchor.EntityAnchor) {
            Vec3 anchorPos = anchor.currentPosition(level);
            rope.setAttachment(RopeHandle.AttachmentPoint.START, RopeMath.toVector3d(anchorPos), null);
        }

        List<Vector3d> points = points();
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
        // MINECRAFT-190 bug 1 fix: when the taut chain reaches all the way back to the
        // anchor-pinned point (pivotIndex == 0), the physically-real stretch firstSegmentLength
        // already holds between the fixed anchor and that point (see payOut/syncFirstSegmentLength)
        // is additional genuinely reachable distance — invisible to points.size() because it is
        // Sable's own attachment-to-point[0] gap, not an array element — so it must be added here
        // or a pending payOut never lets the player actually move until the next throttled
        // structural commit. At any other pivotIndex (an obstruction bent the chain before
        // reaching the anchor), that stub sits beyond the pivot and is correctly not reachable.
        double allowedRadius = (points.size() - 1 - pivotIndex) * segmentSpacing
                + (pivotIndex == 0 ? firstSegmentLength : 0.0);
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
        List<Vector3d> points = points();
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
