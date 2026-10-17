package io.github.brooswitminecraft.dynamicwhips.rope;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3d;

import net.minecraft.world.phys.Vec3;

/** Conversions and the swing-constraint maths shared by {@link PlayerRope}, free of Sable types so it is unit testable. */
final class RopeMath {
    private RopeMath() {
    }

    static Vector3d toVector3d(Vec3 v) {
        return new Vector3d(v.x, v.y, v.z);
    }

    static Vec3 toVec3(Vector3d v) {
        return new Vec3(v.x, v.y, v.z);
    }

    /** Evenly spaced points from {@code start} to {@code end}, overshooting by {@code slack}. */
    static List<Vector3d> layOutPoints(Vec3 start, Vec3 end, double slack, int pointCount) {
        List<Vector3d> points = new ArrayList<>(pointCount);
        Vec3 line = end.subtract(start);
        double lineLength = Math.max(line.length(), 1.0e-6);
        Vec3 dir = line.scale(1.0 / lineLength);
        double spacing = (lineLength * slack) / (pointCount - 1);
        for (int i = 0; i < pointCount; i++) {
            Vec3 p = start.add(dir.scale(i * spacing));
            points.add(new Vector3d(p.x, p.y, p.z));
        }
        return points;
    }

    /**
     * Finds the rope's active swing pivot: the point nearest the player beyond which the chain is
     * a straight, taut line all the way to {@code playerPos}. Walking from the player end toward
     * the anchor, a taut run has straight-line distance to the player approximately equal to the
     * number of segments times {@code segmentSpacing} (the rope's own rest length budget for that
     * run); a real bend — the rope resting against an obstruction — makes the straight-line
     * distance measurably shorter than that budget, since the obstruction pushes the chain off the
     * direct line. The last point for which the taut condition still holds, walking backward, is
     * the obstruction's contact point: the correct axis to swing the player around. With no
     * obstruction at all, this returns {@code points.size() - 2} (one segment out from the
     * player), the same fixed pivot an earlier version of this method always used.
     *
     * <p>Deliberately only searches past that default when the immediate last segment is already
     * near-taut: while the rope is still slack overall (criterion 1's free-fall phase, before the
     * player has fallen far enough to use up the slack), intermediate points sag under gravity in
     * a normal catenary curve that would otherwise look like a false "bend" to the same straight-
     * line-distance test a real obstruction triggers, and would engage the swing constraint before
     * the rope is genuinely under tension.
     *
     * @return the index into {@code points} of the pivot.
     */
    static int findPivotIndex(List<Vector3d> points, Vec3 playerPos, double segmentSpacing) {
        int pivotIndex = points.size() - 2;
        double tolerance = segmentSpacing * 0.5;
        double lastSegmentDistance = toVec3(points.get(pivotIndex)).distanceTo(playerPos);
        if (lastSegmentDistance < segmentSpacing - tolerance) {
            return pivotIndex;
        }
        double remaining = segmentSpacing;
        for (int i = points.size() - 3; i >= 0; i--) {
            remaining += segmentSpacing;
            double straightDistance = toVec3(points.get(i)).distanceTo(playerPos);
            if (straightDistance >= remaining - tolerance) {
                pivotIndex = i;
            } else {
                break;
            }
        }
        return pivotIndex;
    }

    /**
     * The pendulum correction for a player at {@code playerPos} moving at {@code velocity}, given
     * that the rope's solver has already bent its chain to {@code pivot} and the last live segment
     * between {@code pivot} and the player must not exceed {@code allowedRadius}.
     *
     * <p>Gravity and momentum only (MINECRAFT-85 acceptance criterion 1): inside the radius nothing
     * happens. Outside it, the player's position is projected back onto the sphere around the pivot
     * and only the outward radial component of velocity is removed, so a falling player is converted
     * into a swing rather than teleported toward the anchor.
     *
     * @return the corrected {@code [position, velocity]} pair, or null if no correction is needed.
     */
    static Vec3[] swingCorrection(Vec3 pivot, Vec3 playerPos, Vec3 velocity, double allowedRadius) {
        Vec3 fromPivot = playerPos.subtract(pivot);
        double distance = fromPivot.length();
        if (distance <= allowedRadius + RopeConstants.SWING_SLACK || distance < 1.0e-6) {
            return null;
        }
        Vec3 outward = fromPivot.scale(1.0 / distance);
        Vec3 correctedPos = pivot.add(outward.scale(allowedRadius));
        double outwardSpeed = velocity.dot(outward);
        Vec3 correctedVelocity = outwardSpeed > 0 ? velocity.subtract(outward.scale(outwardSpeed)) : velocity;
        return new Vec3[] {correctedPos, correctedVelocity};
    }

    /**
     * MINECRAFT-88's rope-core extension: as {@link #swingCorrection}, but for a rope whose
     * non-player end is itself a LIVING ENTITY that can be moved, not a fixed world point. Splits
     * the correction for being outside {@code allowedRadius} between {@code playerPos} and
     * {@code entityPos} instead of pulling the player alone — {@code targetYieldFraction} (see
     * {@code HarpoonLogic#targetYieldFraction}) is the share of the closing distance the ENTITY
     * absorbs; the player absorbs the rest. Each end only loses the outward-radial component of
     * its own velocity, exactly as {@link #swingCorrection} already does for the player alone.
     *
     * <p>This did not exist before MINECRAFT-88 because nothing before the harpoon ever needed an
     * entity anchor to move at all: {@link PlayerRope#tick} read an {@code EntityAnchor}'s position
     * every tick only to re-pin the rope's own {@code START} attachment, never to push or pull the
     * entity itself (confirmed by reading that method in full, and by {@code docs/rope-core.md}
     * having zero mentions of mass or tension before this story) — the whip and every grappling
     * hook anchor to a fixed block, where "which end moves" has only ever had one possible answer.
     *
     * <p>Deliberately a simpler two-point model than {@link #swingCorrection}'s own pivot-aware one:
     * it measures the straight-line distance between the player and the entity directly rather
     * than walking {@link #findPivotIndex}'s chain-bend search, so a harpoon rope bent around an
     * obstruction is not specifically modeled here. Reasonable for a weapon-range (12-block) rope
     * primarily used in short, direct lines of sight; a harpoon rope resting against terrain still
     * gets the ordinary Sable collision response on its own points, it just does not get the
     * hooks'/whip's obstruction-aware pivot swing for the ENTITY end. Documented simplification,
     * not a silent gap.
     *
     * @return {@code [newPlayerPos, newPlayerVelocity, newEntityPos, newEntityVelocity]}, or null
     *     if the player and entity are already within {@code allowedRadius} of each other.
     */
    static Vec3[] splitPull(Vec3 playerPos, Vec3 playerVelocity, Vec3 entityPos, Vec3 entityVelocity,
            double allowedRadius, double targetYieldFraction) {
        Vec3 delta = playerPos.subtract(entityPos);
        double distance = delta.length();
        if (distance <= allowedRadius + RopeConstants.SWING_SLACK || distance < 1.0e-6) {
            return null;
        }
        double excess = distance - allowedRadius;
        Vec3 towardPlayer = delta.scale(1.0 / distance);
        double playerShare = 1.0 - targetYieldFraction;
        double entityShare = targetYieldFraction;

        Vec3 newPlayerPos = playerPos.subtract(towardPlayer.scale(excess * playerShare));
        Vec3 newEntityPos = entityPos.add(towardPlayer.scale(excess * entityShare));

        double playerOutwardSpeed = playerVelocity.dot(towardPlayer);
        Vec3 newPlayerVelocity = playerOutwardSpeed > 0
                ? playerVelocity.subtract(towardPlayer.scale(playerOutwardSpeed)) : playerVelocity;
        Vec3 towardEntity = towardPlayer.scale(-1.0);
        double entityOutwardSpeed = entityVelocity.dot(towardEntity);
        Vec3 newEntityVelocity = entityOutwardSpeed > 0
                ? entityVelocity.subtract(towardEntity.scale(entityOutwardSpeed)) : entityVelocity;

        return new Vec3[] {newPlayerPos, newPlayerVelocity, newEntityPos, newEntityVelocity};
    }

    /** Sum of point-to-point distances: the rope's actual drawn length right now, bends included. */
    static double polylineLength(List<Vector3d> points) {
        double length = 0;
        Vector3d prev = null;
        for (Vector3d p : points) {
            if (prev != null) {
                length += p.distance(prev);
            }
            prev = p;
        }
        return length;
    }
}
