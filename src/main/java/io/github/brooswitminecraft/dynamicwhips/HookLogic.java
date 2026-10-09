package io.github.brooswitminecraft.dynamicwhips;

/**
 * Pure grappling-hook tuning, free of Minecraft types so it can be unit tested (MINECRAFT-87
 * criterion 1): the three tiers' maximum rope length and the shared pay-out/reel-in stepping rule
 * every tier uses. Spec of record: Confluence "Dynamic Whips — External Contractor Handoff", page
 * 49872898, section "2. Grappling Hooks".
 */
public final class HookLogic {
    /** The three material tiers, differing only in maximum rope length per the spec's own table. */
    public enum Tier {
        IRON(16.0),
        DIAMOND(32.0),
        NETHERITE(64.0);

        private final double maxLength;

        Tier(double maxLength) {
            this.maxLength = maxLength;
        }

        /** Longest rope this tier's hook will ever lay out, in blocks (the spec's own table). */
        public double maxLength() {
            return maxLength;
        }
    }

    /**
     * Slack multiplier used for every hook's attach, matching {@code RopeGameTests#buildCatchRig}'s
     * own rig and the whip's {@code WhipLogic#ANCHOR_SLACK} (docs/rope-core.md section 1.5) — the
     * same value {@code RopeConstants#MAX_POINTS} (rope-core, 142 points) was itself sized against
     * for the 64-block Netherite Hook. Reusing a DIFFERENT slack here would silently invalidate that
     * sizing (the Netherite Hook's actual rope could then lay out wider than the shipped
     * {@code SEGMENT_SPACING}); {@code HookLogicTest#netheriteAtAnchorSlackStaysWithinRopeCoreMaxLength}
     * pins the arithmetic this depends on.
     */
    public static final double ANCHOR_SLACK = 1.1;

    /**
     * Shortest a hook rope can ever be reeled to. Not zero: leaving at least one rope segment of
     * slack means the player is never pulled flush onto the anchor block itself (and matches
     * {@code RopeConstants#MIN_POINTS}'s own "an anchor, a bend, and the player end" floor one
     * segment up — see {@link #SEGMENT_SPACING}).
     */
    public static final double MIN_LENGTH = 1.0;

    /**
     * Mirrors rope-core's own {@code RopeConstants#SEGMENT_SPACING} (0.5 blocks) — duplicated here
     * rather than imported, the same way {@link #ANCHOR_SLACK} duplicates the whip's own constant,
     * to keep this class Minecraft-free (it must stay plain-JUnit testable, criterion 1). This is
     * the size of one {@code RopeManager#payOut}/{@code #reelIn} step at the shipped spacing, which
     * is what {@link #canPayOut}/{@link #canReelIn} gate against and what {@code HookState#tickAll}
     * actually calls once per tick of held input.
     *
     * <p><strong>Known, bounded real-world slop (not unbounded drift):</strong> {@code HookState}
     * gates each tick's pay-out against the length AS OF THE START of that tick, then calls
     * {@code RopeManager#payOut} — which always advances by the rope's own ACTUAL segment spacing
     * (docs/rope-core.md section 1.6), not this constant. For every attach distance this story's
     * items ever use (slack {@link #ANCHOR_SLACK} up to the 64-block Netherite max), rope-core's own
     * {@code RopeConstants#MAX_POINTS} javadoc guarantees the actual spacing stays at or under this
     * value, so a tier's enforced cap can overshoot its nominal {@link Tier#maxLength()} by AT MOST
     * one segment (≤0.5 blocks) before the next tick's gate stops further growth — not by an
     * unbounded or growing amount. {@link #tickPayOut}/{@link #tickReelIn} below pin the idealized
     * (exactly-this-spacing) case bit-exactly; the GameTest-level real-rope check
     * (@{code HookGameTests#hardCapNeverExceededAcrossManyRealCycles}) allows this same one-segment
     * tolerance explicitly rather than silently assuming it away.
     */
    public static final double SEGMENT_SPACING = 0.5;

    private static final double EPSILON = 1.0e-6;

    private HookLogic() {
    }

    /** True once {@code currentLength} has reached (or passed) {@code tier}'s own maximum. */
    public static boolean canPayOut(double currentLength, Tier tier) {
        return currentLength < tier.maxLength() - EPSILON;
    }

    /** True once {@code currentLength} has reached (or passed) {@link #MIN_LENGTH}. */
    public static boolean canReelIn(double currentLength) {
        return currentLength > MIN_LENGTH + EPSILON;
    }

    /**
     * Pure simulation of one tick's worth of held pay-out input, mirroring exactly what
     * {@code HookState#tickAll} does each tick it is gated to run: advance by one segment
     * ({@link #SEGMENT_SPACING}) when {@link #canPayOut} allows it, a no-op otherwise. Used both by
     * {@code HookLogicTest} (criterion 6's hard-cap unit test) and as the specification this
     * doc-comment holds {@code HookState}'s real, Minecraft-touching code to.
     */
    public static double tickPayOut(double currentLength, Tier tier) {
        return canPayOut(currentLength, tier) ? Math.min(currentLength + SEGMENT_SPACING, tier.maxLength())
                : currentLength;
    }

    /** As {@link #tickPayOut}, for held reel-in input. */
    public static double tickReelIn(double currentLength) {
        return canReelIn(currentLength) ? Math.max(currentLength - SEGMENT_SPACING, MIN_LENGTH) : currentLength;
    }
}
