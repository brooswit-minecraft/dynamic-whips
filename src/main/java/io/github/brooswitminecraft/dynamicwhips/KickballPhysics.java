package io.github.brooswitminecraft.dynamicwhips;

/** Pure motion response used by Kickball's post-collision settling hook. */
final class KickballPhysics {
    static final double ROLLING_FRICTION = 0.82D;
    static final double BOUNCE_RESTITUTION = 0.45D;
    static final double MIN_LINEAR_SPEED = 0.003D;
    static final double MIN_BOUNCE_SPEED = 0.02D;

    private KickballPhysics() {
    }

    record Motion(double x, double y, double z) {
        static final Motion ZERO = new Motion(0.0D, 0.0D, 0.0D);

        double horizontalDistanceSqr() {
            return x * x + z * z;
        }
    }

    static Motion applyGroundResponse(Motion motion, Motion attemptedMotion) {
        Motion damped = new Motion(motion.x * ROLLING_FRICTION, motion.y, motion.z * ROLLING_FRICTION);
        if (attemptedMotion.y < -MIN_BOUNCE_SPEED) {
            double rebound = -attemptedMotion.y * BOUNCE_RESTITUTION;
            damped = new Motion(damped.x, rebound >= MIN_BOUNCE_SPEED ? rebound : 0.0D, damped.z);
        } else if (Math.abs(damped.y) < MIN_LINEAR_SPEED) {
            damped = new Motion(damped.x, 0.0D, damped.z);
        }

        if (damped.horizontalDistanceSqr() < MIN_LINEAR_SPEED * MIN_LINEAR_SPEED) {
            damped = new Motion(0.0D, damped.y, 0.0D);
        }
        return damped;
    }
}
