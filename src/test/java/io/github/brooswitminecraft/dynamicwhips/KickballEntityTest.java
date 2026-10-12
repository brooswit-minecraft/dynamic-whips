package io.github.brooswitminecraft.dynamicwhips;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class KickballEntityTest {
    @Test
    void rollingMotionLosesEnergyOnGround() {
        KickballPhysics.Motion before = new KickballPhysics.Motion(1.0D, 0.0D, 0.5D);

        KickballPhysics.Motion after = KickballPhysics.applyGroundResponse(before, KickballPhysics.Motion.ZERO);

        assertTrue(after.horizontalDistanceSqr() < before.horizontalDistanceSqr());
        assertEquals(0.82D, after.x(), 1e-9);
        assertEquals(0.41D, after.z(), 1e-9);
    }

    @Test
    void reboundDecaysAndEventuallySettles() {
        KickballPhysics.Motion motion = KickballPhysics.Motion.ZERO;
        double previousRebound = Double.POSITIVE_INFINITY;
        for (double impactSpeed : new double[] {0.16D, 0.10D, 0.06D, 0.04D, 0.02D}) {
            KickballPhysics.Motion attempted = new KickballPhysics.Motion(0.0D, -impactSpeed, 0.0D);
            motion = KickballPhysics.applyGroundResponse(motion, attempted);
            assertTrue(motion.y() <= previousRebound);
            previousRebound = motion.y();
        }

        assertEquals(0.0D, motion.y(), 1e-9);
    }

    @Test
    void lowResidualGroundMotionIsClamped() {
        KickballPhysics.Motion settled = KickballPhysics.applyGroundResponse(
                new KickballPhysics.Motion(0.002D, 0.001D, -0.001D), KickballPhysics.Motion.ZERO);

        assertEquals(KickballPhysics.Motion.ZERO, settled);
    }
}
