package io.github.brooswitminecraft.dynamicwhips.rope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import net.minecraft.world.phys.Vec3;

class RopeMathTest {
    private static final double SPACING = 0.5;

    @Test
    void findPivotIndexDefaultsToOneSegmentOutWhenStillSlack() {
        // The immediate last segment (index size-2 to the player) is far short of its rest
        // length: the rope overall is still slack, same shape as early free fall, regardless of
        // what the earlier points look like.
        List<Vector3d> points = List.of(
                new Vector3d(0, 10, 0),
                new Vector3d(0, 9.5, 0),
                new Vector3d(0, 9.0, 0));
        Vec3 player = new Vec3(0, 9.45, 0);

        int pivotIndex = RopeMath.findPivotIndex(points, player, SPACING);

        assertEquals(points.size() - 2, pivotIndex, "should not search past the default while still slack");
    }

    @Test
    void findPivotIndexWalksBackToATautStraightRunWithNoObstruction() {
        // A fully taut straight line, anchor to player, 4 segments of exactly SPACING each.
        List<Vector3d> points = new ArrayList<>();
        for (int i = 0; i <= 4; i++) {
            points.add(new Vector3d(0, 10 - i * SPACING, 0));
        }
        Vec3 player = new Vec3(0, 10 - 4 * SPACING, 0);

        int pivotIndex = RopeMath.findPivotIndex(points, player, SPACING);

        assertEquals(0, pivotIndex, "a fully taut straight chain should walk all the way back to the anchor");
    }

    @Test
    void findPivotIndexStopsAtARealBend() {
        // Every consecutive pair of points is exactly SPACING apart (matching Sable's own
        // fixed-segment-length rope), but the path bends 90 degrees at index 1 (simulating a
        // post), then runs a taut straight line of 2 segments down to the player.
        List<Vector3d> points = List.of(
                new Vector3d(0, 10, 0),
                new Vector3d(SPACING, 10, 0),
                new Vector3d(SPACING, 10 - SPACING, 0),
                new Vector3d(SPACING, 10 - 2 * SPACING, 0));
        Vec3 player = new Vec3(SPACING, 10 - 2 * SPACING, 0);

        int pivotIndex = RopeMath.findPivotIndex(points, player, SPACING);

        assertEquals(1, pivotIndex, "the pivot should land on the bend point, not the anchor behind it");
    }

    @Test
    void swingCorrectionDoesNothingInsideRadius() {
        Vec3 pivot = new Vec3(0, 0, 0);
        Vec3 player = new Vec3(0, -0.3, 0);
        assertNull(RopeMath.swingCorrection(pivot, player, Vec3.ZERO, 0.5));
    }

    @Test
    void swingCorrectionClampsToSphereAndRemovesOutwardVelocity() {
        Vec3 pivot = new Vec3(0, 0, 0);
        Vec3 player = new Vec3(0, -1.0, 0);
        Vec3 fallingVelocity = new Vec3(0, -1.0, 0);

        Vec3[] correction = RopeMath.swingCorrection(pivot, player, fallingVelocity, 0.5);

        assertNotNull(correction);
        assertEquals(0.5, correction[0].distanceTo(pivot), 1e-9, "position is clamped to the allowed radius");
        assertEquals(0.0, correction[1].y, 1e-9, "outward (downward, away from the pivot) velocity is removed");
    }
}
