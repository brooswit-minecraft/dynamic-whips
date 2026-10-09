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
     * <p><strong>The hard cap is strict — no overshoot, by design, not by luck.</strong> An earlier
     * revision gated {@link #canPayOut} on the CURRENT length alone (current {@code <} max), which
     * let a tier's enforced cap overshoot its nominal {@link Tier#maxLength()} by up to one segment
     * before the next tick's gate caught up — review (PR #15) correctly rejected this: the ticket
     * says hard limit, not "within one segment." {@link #canPayOut} now gates on the PROJECTED
     * length (current + this constant), refusing the step outright whenever taking it could
     * exceed the cap. This is sound because {@code RopeManager#payOut} always advances by the
     * rope's own ACTUAL segment spacing (docs/rope-core.md section 1.6), which rope-core's own
     * {@code RopeConstants#MAX_POINTS} javadoc guarantees stays AT OR UNDER this constant for every
     * attach distance this story's items use (slack {@link #ANCHOR_SLACK} up to the 64-block
     * Netherite max) — so refusing a step whose WORST-CASE size would overshoot also refuses every
     * step whose real size would have. The one honest cost: when a tier's max is not an exact
     * multiple of the rope's own actual spacing from its attach-time length, pay-out can stop one
     * segment SHORT of the nominal cap rather than reaching it exactly —
     * {@code HookLogicTest#payOutNeverOvershootsFromANonAlignedStartingLength} pins that this is
     * the traded-off behavior, not a bug.
     */
    public static final double SEGMENT_SPACING = 0.5;

    private static final double EPSILON = 1.0e-6;

    private HookLogic() {
    }

    /** True only if pay-out by one more segment could NOT push {@code currentLength} past
     * {@code tier}'s own maximum — the hard limit, with no overshoot tolerance at all. */
    public static boolean canPayOut(double currentLength, Tier tier) {
        return currentLength + SEGMENT_SPACING <= tier.maxLength() + EPSILON;
    }

    /** As {@link #canPayOut}, symmetric for reel-in against {@link #MIN_LENGTH}. */
    public static boolean canReelIn(double currentLength) {
        return currentLength - SEGMENT_SPACING >= MIN_LENGTH - EPSILON;
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
