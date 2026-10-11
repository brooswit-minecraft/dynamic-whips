package io.github.brooswitminecraft.dynamicwhips.rope;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RopeMathTest {
    @Test
    void rapierCoordinateGuardAcceptsOrdinaryAndBoundaryPositions() {
        double limit = RopeConstants.MAX_SAFE_RAPIER_COORDINATE;
        assertTrue(RapierCoordinateGuard.isSafe(0, 120, -48));
        assertTrue(RapierCoordinateGuard.isSafe(limit, -limit, limit));
    }

    @Test
    void rapierCoordinateGuardRejectsCoordinatesWhoseFloat32UlpIsTooCoarse() {
        double limit = RopeConstants.MAX_SAFE_RAPIER_COORDINATE;
        assertFalse(RapierCoordinateGuard.isSafe(limit + 0.01, 64, 0));
        assertFalse(RapierCoordinateGuard.isSafe(0, 64, -limit - 0.01));
        assertFalse(RapierCoordinateGuard.isSafe(Double.NaN, 64, 0));
    }
}
