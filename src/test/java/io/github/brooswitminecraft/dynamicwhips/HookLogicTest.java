package io.github.brooswitminecraft.dynamicwhips;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HookLogicTest {
    @Test
    void documentedTierMaxLengths() {
        assertEquals(16.0, HookLogic.Tier.IRON.maxLength(), 0.0);
        assertEquals(32.0, HookLogic.Tier.DIAMOND.maxLength(), 0.0);
        assertEquals(64.0, HookLogic.Tier.NETHERITE.maxLength(), 0.0);
    }

    /**
     * MINECRAFT-85's own {@code RopeConstants#MAX_POINTS} (142 points) was sized specifically for
     * the 64-block Netherite Hook at a documented default slack of 1.1x (docs/rope-core.md section
     * 1.6/5). Reusing a different slack here would silently invalidate that sizing — this pins the
     * exact arithmetic both this class and rope-core depend on staying in agreement.
     */
    @Test
    void netheriteAtAnchorSlackMatchesRopeCoreMaxPointsDerivation() {
        double straightLineTimesSlack = HookLogic.Tier.NETHERITE.maxLength() * HookLogic.ANCHOR_SLACK;
        int expectedMaxPoints = (int) Math.ceil(straightLineTimesSlack / HookLogic.SEGMENT_SPACING) + 1;
        assertEquals(142, expectedMaxPoints,
                "HookLogic.ANCHOR_SLACK/SEGMENT_SPACING no longer agree with rope-core's own"
                        + " RopeConstants.MAX_POINTS=142 derivation (docs/rope-core.md section 1.6/5) — a change"
                        + " to either constant here must be matched there, or a 64-block hook could lay out"
                        + " wider than the shipped SEGMENT_SPACING");
    }

    /**
     * Criterion 6, the hard-cap requirement: {@link HookLogic#tickPayOut} must never exceed a
     * tier's own maximum, across many repeated cycles, with exact (not merely "close enough")
     * equality once the cap is reached — no accumulated float/double drift.
     */
    @Test
    void payOutNeverExceedsTierMaxAcrossManyCycles() {
        for (HookLogic.Tier tier : HookLogic.Tier.values()) {
            double length = HookLogic.MIN_LENGTH;
            for (int i = 0; i < 10_000; i++) {
                double next = HookLogic.tickPayOut(length, tier);
                assertTrue(next <= tier.maxLength(),
                        tier + " exceeded its own max on cycle " + i + ": " + next);
                length = next;
            }
            assertEquals(tier.maxLength(), length, 0.0,
                    tier + " did not settle at EXACTLY its own max after 10000 pay-out cycles: " + length);
        }
    }

    @Test
    void reelInNeverGoesBelowMinLength() {
        for (HookLogic.Tier tier : HookLogic.Tier.values()) {
            double length = tier.maxLength();
            for (int i = 0; i < 10_000; i++) {
                double next = HookLogic.tickReelIn(length);
                assertTrue(next >= HookLogic.MIN_LENGTH,
                        tier + " went below MIN_LENGTH on cycle " + i + ": " + next);
                length = next;
            }
            assertEquals(HookLogic.MIN_LENGTH, length, 0.0,
                    tier + " did not settle at EXACTLY MIN_LENGTH after 10000 reel-in cycles: " + length);
        }
    }

    /**
     * The exact scenario criterion 6 names: alternating pay-out/reel-in cycles repeatedly crossing
     * a tier's own cap boundary, asserting EXACT cap equality every single time the cap is hit —
     * not merely "within tolerance" — which is what would catch a slow float/double drift a
     * tolerance-based assertion could hide.
     */
    @Test
    void repeatedPayOutReelInCyclesNeverDriftPastTheCap() {
        for (HookLogic.Tier tier : HookLogic.Tier.values()) {
            double length = tier.maxLength() - HookLogic.SEGMENT_SPACING / 2.0;
            for (int cycle = 0; cycle < 5_000; cycle++) {
                length = HookLogic.tickPayOut(length, tier);
                assertEquals(tier.maxLength(), length, 0.0, tier + " drifted on cycle " + cycle + " (pay-out side)");
                length = HookLogic.tickReelIn(length);
                length = HookLogic.tickPayOut(length, tier);
                assertEquals(tier.maxLength(), length, 0.0,
                        tier + " drifted on cycle " + cycle + " (after a reel-in/pay-out round trip)");
            }
        }
    }

    @Test
    void canPayOutAndCanReelInAgreeWithTheBoundaries() {
        for (HookLogic.Tier tier : HookLogic.Tier.values()) {
            assertTrue(HookLogic.canPayOut(HookLogic.MIN_LENGTH, tier));
            assertFalse(HookLogic.canPayOut(tier.maxLength(), tier));
            assertTrue(HookLogic.canReelIn(tier.maxLength()));
            assertFalse(HookLogic.canReelIn(HookLogic.MIN_LENGTH));
        }
    }
}
