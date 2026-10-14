package io.github.brooswitminecraft.dynamicwhips.rope;

/**
 * Dependency-free admission check for coordinates supplied to Sable's float32 native rope
 * broad phase. Kept outside {@link RopeMath} so its regression test can run on the plain JUnit
 * runtime, which intentionally does not load Minecraft classes.
 */
final class RapierCoordinateGuard {
    private RapierCoordinateGuard() {
    }

    static boolean isSafe(double x, double y, double z) {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
                && Math.abs(x) <= RopeConstants.MAX_SAFE_RAPIER_COORDINATE
                && Math.abs(y) <= RopeConstants.MAX_SAFE_RAPIER_COORDINATE
                && Math.abs(z) <= RopeConstants.MAX_SAFE_RAPIER_COORDINATE;
    }
}
