package io.github.brooswitminecraft.dynamicwhips;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class KickballEntityTest {
    @Test
    void rollingMotionLosesEnergyOnGround() {
        Vec3 before = new Vec3(1.0D, 0.0D, 0.5D);

        Vec3 after = KickballEntity.applyGroundResponse(before, Vec3.ZERO);

        assertTrue(after.horizontalDistanceSqr() < before.horizontalDistanceSqr());
        assertEquals(0.82D, after.x, 1e-9);
        assertEquals(0.41D, after.z, 1e-9);
    }

    @Test
    void reboundDecaysAndEventuallySettles() {
        Vec3 motion = Vec3.ZERO;
        double previousRebound = Double.POSITIVE_INFINITY;
        for (double impactSpeed : new double[] {0.16D, 0.10D, 0.06D, 0.04D, 0.02D}) {
            Vec3 attempted = new Vec3(0.0D, -impactSpeed, 0.0D);
            motion = KickballEntity.applyGroundResponse(motion, attempted);
            assertTrue(motion.y <= previousRebound);
            previousRebound = motion.y;
        }

        assertEquals(0.0D, motion.y, 1e-9);
    }

    @Test
    void lowResidualGroundMotionIsClamped() {
        Vec3 settled = KickballEntity.applyGroundResponse(new Vec3(0.002D, 0.001D, -0.001D), Vec3.ZERO);

        assertEquals(Vec3.ZERO, settled);
    }
}
