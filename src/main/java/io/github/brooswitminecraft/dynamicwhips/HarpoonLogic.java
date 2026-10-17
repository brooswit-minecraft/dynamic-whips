package io.github.brooswitminecraft.dynamicwhips;

/**
 * Pure harpoon tuning and the mass-vs-pull-direction rule (MINECRAFT-88), free of Minecraft types
 * so it can be unit tested the same way {@code HookLogic}/{@code WhipLogic} are.
 *
 * <p><strong>Eligibility (AC1):</strong> a harpoon only ever anchors to a living entity it actually
 * hits — never a block. Unlike the grappling hooks (block-only, no combat role) or the whip (both),
 * the harpoon is explicitly a weapon/control tool aimed at a target, not a traversal anchor; giving
 * it a block-anchor fallback would just re-create a worse grappling hook. A thrown harpoon that
 * hits no living entity (misses, or strikes a block/the ground first) never creates a rope at all —
 * {@code HarpoonEntity} lets it fall and despawn like any other thrown item, exactly like a vanilla
 * thrown trident that sticks in a block. {@code HarpoonEntity}/{@code HarpoonItem} enforce this;
 * this class only holds the numbers and the pure mass rule, same split {@code HookLogic}/
 * {@code HookItem} already use.
 *
 * <p><strong>The mass rule (AC4):</strong> {@link #pullBalance} derives, from the target's own
 * bounding-box volume (a real, per-entity physical quantity every {@code LivingEntity} already
 * has — never a hardcoded per-mob list) and whether it is currently grounded, a signed "which end
 * yields" value in [-1, 1]: close to {@code +1} means the target is light enough to be pulled
 * bodily to the player (AC3's headline behavior); close to {@code -1} means the target is heavy
 * enough, and unanchored enough, to instead pull the PLAYER toward it (AC4's "interesting" case —
 * intended, not a bug, and part of what makes the harpoon feel like a weapon rather than a
 * grappling hook, AC6); close to {@code 0} is a genuine tug-of-war where the rope's tension pulls
 * BOTH ends part-way toward each other rather than yanking either one fully — the chosen reading
 * of "resists moving at all" (see {@link #classify}'s own javadoc for why this, and not a literal
 * zero-movement freeze, was picked).
 */
public final class HarpoonLogic {
    /**
     * Longest a harpoon can be thrown and still rope a hit target, in blocks. Chosen below the
     * cheapest grappling hook (Iron, 16 blocks — see {@code HookLogic.Tier#IRON}) so the harpoon
     * can never out-range even the weakest dedicated traversal tool; it does not need to, since it
     * is a weapon, not a traversal item (AC6).
     */
    public static final double RANGE = 12.0;

    /** Damage (half hearts) on a direct hit, applied once at impact, before any rope exists.
     * Between the whip's {@code MIN_DAMAGE}/{@code MAX_DAMAGE} (2.0-6.0): a harpoon always hits at
     * its own fixed, thrown-weapon force, unlike the whip's distance-scaled melee crack. */
    public static final float DAMAGE = 5.0F;

    /**
     * Ticks before another harpoon can be thrown (4 seconds). Deliberately heavier than the
     * whip's 30-tick (1.5s) cooldown: the harpoon is a committed, reload-style throw (closer to a
     * vanilla trident) rather than a quick melee crack, and AC6 wants it to read as a weapon
     * decision, not a spammable traversal tool.
     */
    public static final int COOLDOWN_TICKS = 80;

    /**
     * Durability for a direct hit (one point charged per successful throw, mirroring the whip's
     * per-swing cost). Lower than the Leather Whip's 80: the harpoon is not consumed outright
     * (same non-consumable item, {@code stacksTo(1)}, as every other rope tool here), but a
     * committed combat throw that can anchor a heavy target and yank the thrower around is priced
     * as the riskier, more punishing tool of the set.
     */
    public static final int DURABILITY = 32;

    /**
     * Slack multiplier for the harpoon's entity-anchor attach, slightly looser than the hooks'/
     * whip's shared 1.1x ({@code HookLogic#ANCHOR_SLACK}): the anchor itself (a living target) is
     * moving the instant the rope is created, unlike a fixed block, so a little extra give avoids
     * an immediately-taut, jerk-on-attach rope.
     */
    public static final double ANCHOR_SLACK = 1.15;

    /** As {@code HookLogic#MIN_LENGTH}: the shortest the harpoon's rope can ever be reeled to. */
    public static final double MIN_LENGTH = 1.0;

    /** Duplicated from rope-core's {@code RopeConstants#SEGMENT_SPACING} for the same
     * Minecraft-free-testability reason {@code HookLogic#SEGMENT_SPACING} already documents. */
    public static final double SEGMENT_SPACING = 0.5;

    /**
     * Reference bounding-box volume this rule calibrates against: roughly a vanilla player's own
     * hitbox (0.6 x 0.6 x 1.8 blocks). A target at exactly this volume and airborne produces a
     * {@link #pullBalance} of 0 — a fair, even tug-of-war between two player-sized bodies.
     */
    static final double REFERENCE_VOLUME = 0.6 * 0.6 * 1.8;

    /**
     * How much a HEAVY (negative-balance) target's pull on the player is damped once that target
     * is grounded. AC4's "anchored/constrained state" signal: a heavy target already braced
     * against the ground resists being used to yank the player around far more than the same mass
     * airborne (mid-air, mid-fall, swimming, flying) would — see {@link #pullBalance}'s own
     * javadoc. Deliberately NOT applied symmetrically to a light (positive-balance) target: a
     * light target standing on the ground should still get pulled bodily to the player (a chicken
     * does not "brace" meaningfully), so damping only kicks in on the side of the rule where mass
     * is already working against the pull.
     */
    static final double GROUNDED_BRACE_DAMPING = 0.75;

    /** {@link #classify} threshold: at or above this, the target yields enough to call it PULL_TARGET. */
    static final double PULL_TARGET_THRESHOLD = 0.3;

    /** {@link #classify} threshold: at or below this, the player yields enough to call it PULL_PLAYER. */
    static final double PULL_PLAYER_THRESHOLD = -0.3;

    private static final double EPSILON = 1.0e-6;

    /** The three qualitative outcomes {@link #classify} names from a {@link #pullBalance} value;
     * {@link #targetYieldFraction} is the actual continuous number the rope-core physics consumes. */
    public enum PullOutcome {
        /** The target is light enough to be pulled bodily toward the player — AC3's default. */
        PULL_TARGET,
        /** The target is heavy/unanchored enough that the player instead moves toward it — AC4. */
        PULL_PLAYER,
        /** Neither end fully yields: the rope pulls both part-way toward each other. */
        RESIST
    }

    private HarpoonLogic() {
    }

    /**
     * The signed "which end yields" rule (AC4), in [-1, 1]. {@code targetVolume} is the target's
     * own {@code getBoundingBox()} volume (width * width * height); {@code targetGrounded} is its
     * {@code onGround()} state right now. Derived purely from these two real, per-entity physical
     * quantities — no per-mob special-casing anywhere in this method.
     *
     * <p>{@code raw = clamp(REFERENCE_VOLUME / targetVolume - 1, -1, 1)}: a target far lighter
     * than the reference clamps to {@code +1} (fully pulled to the player); far heavier clamps to
     * {@code -1} (the player is instead pulled to it); a target at exactly the reference volume
     * gives {@code 0}. {@link #GROUNDED_BRACE_DAMPING} then pulls a negative (heavy) result toward
     * 0 when the target is grounded — a braced heavy target resists the yank far more than the
     * same mass airborne — and leaves a non-negative (light) result alone.
     */
    public static double pullBalance(double targetVolume, boolean targetGrounded) {
        double safeVolume = targetVolume > EPSILON ? targetVolume : EPSILON;
        double raw = clamp(REFERENCE_VOLUME / safeVolume - 1.0, -1.0, 1.0);
        if (raw < 0 && targetGrounded) {
            raw *= (1.0 - GROUNDED_BRACE_DAMPING);
        }
        return clamp(raw, -1.0, 1.0);
    }

    /** Qualitative reading of {@link #pullBalance}'s continuous value, per {@link #PULL_TARGET_THRESHOLD}/
     * {@link #PULL_PLAYER_THRESHOLD}. */
    public static PullOutcome classify(double pullBalance) {
        if (pullBalance >= PULL_TARGET_THRESHOLD) {
            return PullOutcome.PULL_TARGET;
        }
        if (pullBalance <= PULL_PLAYER_THRESHOLD) {
            return PullOutcome.PULL_PLAYER;
        }
        return PullOutcome.RESIST;
    }

    /**
     * Maps {@link #pullBalance}'s [-1, 1] value onto [0, 1]: the fraction of a taut rope's
     * correction {@code PlayerRope#tick} applies to the TARGET end rather than the player end (see
     * {@code RopeManager#attachToEntityWithPull}). {@code 1.0} (balance {@code +1}) is "pull the
     * target fully"; {@code 0.0} (balance {@code -1}) is "pull the player fully"; {@code 0.5}
     * (balance {@code 0}, {@link PullOutcome#RESIST}) splits the correction evenly — both ends
     * move part-way toward each other, which is this rule's deliberate reading of "resists moving
     * at all": a visibly weaker, shared give rather than either body being fully yanked, not a
     * literal zero-movement freeze (a true freeze would mean silently violating the rope's own
     * length constraint every tick it held, which is worse than being merely less dramatic).
     */
    public static double targetYieldFraction(double pullBalance) {
        return clamp((pullBalance + 1.0) / 2.0, 0.0, 1.0);
    }

    /** As {@code HookLogic#capInitialAttachLength}: the greatest initial rest length the harpoon's
     * rope may ever be built at, independent of the real anchor/player distance and slack. */
    public static double capInitialAttachLength(double uncappedLength) {
        return Math.min(uncappedLength, RANGE);
    }

    /** As {@code HookLogic#canPayOut}, capped at {@link #RANGE} instead of a per-tier maximum. */
    public static boolean canPayOut(double currentLength) {
        return currentLength + SEGMENT_SPACING <= RANGE + EPSILON;
    }

    /** As {@code HookLogic#canReelIn}. */
    public static boolean canReelIn(double currentLength) {
        return currentLength - SEGMENT_SPACING >= MIN_LENGTH - EPSILON;
    }

    /** As {@code HookLogic#tickPayOut}. */
    public static double tickPayOut(double currentLength) {
        return canPayOut(currentLength) ? Math.min(currentLength + SEGMENT_SPACING, RANGE) : currentLength;
    }

    /** As {@code HookLogic#tickReelIn}. */
    public static double tickReelIn(double currentLength) {
        return canReelIn(currentLength) ? Math.max(currentLength - SEGMENT_SPACING, MIN_LENGTH) : currentLength;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
