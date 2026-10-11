package io.github.brooswitminecraft.dynamicwhips.rope;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class RopeMathTest {
    @Test
    void rapierCoordinateGuardAcceptsOrdinaryAndBoundaryPositions() {
        double limit = RopeConstants.MAX_SAFE_RAPIER_COORDINATE;
        assertTrue(RopeMath.isSafeForRapier(new Vec3(0, 120, -48)));
        assertTrue(RopeMath.isSafeForRapier(new Vec3(limit, -limit, limit)));
    }

    @Test
    void rapierCoordinateGuardRejectsCoordinatesWhoseFloat32UlpIsTooCoarse() {
        double limit = RopeConstants.MAX_SAFE_RAPIER_COORDINATE;
        assertFalse(RopeMath.isSafeForRapier(new Vec3(limit + 0.01, 64, 0)));
        assertFalse(RopeMath.isSafeForRapier(new Vec3(0, 64, -limit - 0.01)));
        assertFalse(RopeMath.isSafeForRapier(new Vec3(Double.NaN, 64, 0)));
    }
}
