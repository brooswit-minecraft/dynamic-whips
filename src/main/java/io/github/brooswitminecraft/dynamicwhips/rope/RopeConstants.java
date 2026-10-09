package io.github.brooswitminecraft.dynamicwhips.rope;

/**
 * Every number that shapes a Dynamic Whips rope, named and justified in one place
 * (MINECRAFT-85 acceptance criterion 5). See {@code docs/rope-core.md} for the measurements
 * behind these choices.
 */
public final class RopeConstants {
    /**
     * Per-point collision radius passed to {@code RopePhysicsObject}. Matches the Sable spike's
     * own debug command (docs/rope-spike.md section 1), which is the only value anyone has
     * exercised against Sable's native Rapier solver.
     */
    public static final double COLLISION_RADIUS = 0.25;

    /**
     * Target spacing between rope points, in blocks. The spike's section 2 predicted tunnelling
     * through thin obstacles once spacing exceeds about 2x the collision radius, and recommended
     * staying near half a block for hook-scale lengths; docs/rope-core.md records the GameTest
     * result at this spacing and at the wider spacings that were tried and failed.
     */
    public static final double SEGMENT_SPACING = 0.5;

    /** Fewest points a rope can have: an anchor, a bend, and the player end. */
    public static final int MIN_POINTS = 3;

    /**
     * Most points any rope in this mod will ever need. Sized for the longest planned consumer,
     * the 64-block Netherite Hook (MINECRAFT-67's Confluence spec), laid out at {@link
     * #SEGMENT_SPACING} with the debug command's own documented default slack of 1.1x (see
     * {@code RopeSpike}): {@code ceil(64 * 1.1 / 0.5) + 1 = 142} points. 129 (the spike's own
     * estimate, {@code 64 / 0.5 + 1}, with no slack allowance at all) clamped every rope at that
     * length and slack to an ACTUAL spacing of 0.55 — wider than {@link #SEGMENT_SPACING}'s own
     * 0.5 — silently, because the point-count clamp bites before the spacing is ever computed.
     * 142 is the smallest value at which the Netherite Hook at its documented default slack keeps
     * its actual spacing at or under {@link #SEGMENT_SPACING}; a caller asking for a longer rope,
     * or more slack than 1.1x, still clamps and still gets an honestly-computed wider actual
     * spacing (see {@code PlayerRope#segmentSpacing}), which is the deliberate tradeoff. NOT
     * claimed as "tunnelling-safe": docs/rope-core.md section 10.10 (MINECRAFT-155) found a
     * permanent, nonzero penetration at this exact spacing in the near-origin diagnostic rig,
     * independent of point count (whether that is genuine tunnelling or a stable soft-contact
     * equilibrium is explicitly left open there) — keeping the actual spacing at or under 0.5
     * avoids one additional, SEPARATE failure mode (missing a thin obstacle by being laid out too
     * coarse to ever reach it; see section 10.9's negative control), not that penetration itself.
     */
    public static final int MAX_POINTS = 142;

    /**
     * Longest rope this core will construct, in blocks. Derived from {@link #MAX_POINTS} and
     * {@link #SEGMENT_SPACING} rather than stated independently, so the two can never drift apart.
     */
    public static final double MAX_LENGTH = (MAX_POINTS - 1) * SEGMENT_SPACING;

    /**
     * Sable's own Rapier solver substeps and timestep are fixed by Sable, not exposed for this
     * mod to tune (confirmed from the Sable 2.0.5 jar: {@code SubLevelPhysicsSystem} carries its
     * own {@code substepsPerTick}/{@code substepTimeStep} fields with no public setter). The one
     * timestep this mod does control is how often it re-applies the player swing constraint,
     * which is every server tick ({@link #CONSTRAINT_INTERVAL_TICKS}) so the correction never
     * lags more than one tick behind Sable's own pose update.
     */
    public static final int CONSTRAINT_INTERVAL_TICKS = 1;

    /**
     * How often an active rope's points are sent to tracking clients, in server ticks. 4 ticks is
     * 5 Hz: a swinging rope's silhouette changes slowly relative to camera motion, so clients
     * interpolate between updates (see RopeRenderer); 5 Hz keeps the packet rate an order of
     * magnitude below the chunk-tracking player cap this mod expects (a handful of ropes per
     * player, never one per tick) while staying visually smooth once interpolated. Chosen over
     * 20 Hz (the spike's particle rate) because particles are forgiving of jitter and a line
     * render is not: sending every tick multiplies bandwidth for a smoothness gain the client
     * interpolator already supplies cheaply.
     */
    public static final int SYNC_INTERVAL_TICKS = 4;

    /**
     * Fraction of a rope point's radius the player's swing correction is allowed to overshoot
     * before being clamped back to the sphere of radius "remaining rope length" around the
     * pivot. A small slack (not 0) avoids re-triggering the correction every tick from floating
     * point noise once the player is resting exactly at full extension.
     */
    public static final double SWING_SLACK = 0.02;

    private RopeConstants() {
    }
}
