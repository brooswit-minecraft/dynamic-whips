package io.github.brooswitminecraft.dynamicwhips;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HarpoonLogicTest {
    /** A small, chicken-scale volume: far below the player-sized reference. */
    private static final double LIGHT_VOLUME = 0.4 * 0.4 * 0.7;
    /** A large, ravager-scale volume: far above the player-sized reference. */
    private static final double HEAVY_VOLUME = 1.95 * 1.95 * 2.2;

    @Test
    void documentedTuning() {
        assertTrue(HarpoonLogic.RANGE > 0 && HarpoonLogic.RANGE < HookLogic.Tier.IRON.maxLength(),
                "AC6: the harpoon must stay below even the cheapest grappling hook's range, so it never"
                        + " doubles as a better traversal tool");
        assertTrue(HarpoonLogic.DAMAGE > 0);
        assertTrue(HarpoonLogic.COOLDOWN_TICKS > WhipLogic.COOLDOWN_TICKS,
                "the harpoon is a committed, reload-style throw and should cool down slower than the whip's crack");
        assertTrue(HarpoonLogic.DURABILITY > 0 && HarpoonLogic.DURABILITY < WhipLogic.DURABILITY);
    }

    /** AC4's headline case: a light target, not grounded, is pulled fully to the player. */
    @Test
    void lightAirborneTargetIsPulledToThePlayer() {
        double balance = HarpoonLogic.pullBalance(LIGHT_VOLUME, false);
        assertEquals(1.0, balance, 0.0, "a far-lighter-than-reference target must clamp to the full +1 pull");
        assertEquals(HarpoonLogic.PullOutcome.PULL_TARGET, HarpoonLogic.classify(balance));
        assertEquals(1.0, HarpoonLogic.targetYieldFraction(balance), 0.0);
    }

    /** AC4's "interesting" case: a heavy, airborne (unanchored) target instead pulls the player. */
    @Test
    void heavyAirborneTargetPullsThePlayerInstead() {
        double balance = HarpoonLogic.pullBalance(HEAVY_VOLUME, false);
        assertTrue(balance < HarpoonLogic.PULL_PLAYER_THRESHOLD,
                "a far-heavier-than-reference, unanchored target must fall below the PULL_PLAYER threshold: "
                        + balance);
        assertEquals(HarpoonLogic.PullOutcome.PULL_PLAYER, HarpoonLogic.classify(balance));
        assertTrue(HarpoonLogic.targetYieldFraction(balance) < 0.5,
                "a PULL_PLAYER balance must send LESS than half the rope's correction to the target end");
    }

    /** AC4's grounded/anchored signal: the SAME heavy mass, braced on the ground, resists far more
     * than it did airborne — moved toward RESIST rather than staying at the airborne extreme. */
    @Test
    void groundedHeavyTargetResistsMoreThanAirborne() {
        double airborne = HarpoonLogic.pullBalance(HEAVY_VOLUME, false);
        double grounded = HarpoonLogic.pullBalance(HEAVY_VOLUME, true);
        assertTrue(grounded > airborne, "bracing against the ground must damp the pull toward 0, not away from it");
        assertEquals(HarpoonLogic.PullOutcome.RESIST, HarpoonLogic.classify(grounded),
                "a heavy, grounded/braced target is this rule's RESIST case: " + grounded);
    }

    /** The grounded damping must NOT penalize a light target — it should still be pulled in. */
    @Test
    void groundedLightTargetIsStillPulledToThePlayer() {
        double balance = HarpoonLogic.pullBalance(LIGHT_VOLUME, true);
        assertEquals(HarpoonLogic.PullOutcome.PULL_TARGET, HarpoonLogic.classify(balance),
                "a light target standing on the ground should still be yanked to the player, unaffected by"
                        + " GROUNDED_BRACE_DAMPING, which only damps the heavy/negative side of the rule");
    }

    @Test
    void playerSizedAirborneTargetIsAFairTugOfWar() {
        double balance = HarpoonLogic.pullBalance(HarpoonLogic.REFERENCE_VOLUME, false);
        assertEquals(0.0, balance, 1.0e-9);
        assertEquals(HarpoonLogic.PullOutcome.RESIST, HarpoonLogic.classify(balance));
        assertEquals(0.5, HarpoonLogic.targetYieldFraction(balance), 1.0e-9);
    }

    @Test
    void capInitialAttachLengthClampsToRange() {
        double uncapped = HarpoonLogic.RANGE * HarpoonLogic.ANCHOR_SLACK;
        assertTrue(uncapped > HarpoonLogic.RANGE, "test bug: this scenario must itself be an overshoot");
        assertEquals(HarpoonLogic.RANGE, HarpoonLogic.capInitialAttachLength(uncapped), 0.0);
    }

    @Test
    void capInitialAttachLengthLeavesAShortAttachUnchanged() {
        double uncapped = (HarpoonLogic.RANGE / 4.0) * HarpoonLogic.ANCHOR_SLACK;
        assertEquals(uncapped, HarpoonLogic.capInitialAttachLength(uncapped), 0.0);
    }

    @Test
    void payOutNeverExceedsRangeAcrossManyCycles() {
        double length = HarpoonLogic.MIN_LENGTH;
        for (int i = 0; i < 10_000; i++) {
            double next = HarpoonLogic.tickPayOut(length);
            assertTrue(next <= HarpoonLogic.RANGE, "exceeded RANGE on cycle " + i + ": " + next);
            length = next;
        }
        assertEquals(HarpoonLogic.RANGE, length, 0.0);
    }

    @Test
    void reelInNeverGoesBelowMinLength() {
        double length = HarpoonLogic.RANGE;
        for (int i = 0; i < 10_000; i++) {
            double next = HarpoonLogic.tickReelIn(length);
            assertTrue(next >= HarpoonLogic.MIN_LENGTH, "went below MIN_LENGTH on cycle " + i + ": " + next);
            length = next;
        }
        assertEquals(HarpoonLogic.MIN_LENGTH, length, 0.0);
    }

    @Test
    void canPayOutAndCanReelInAgreeWithTheBoundaries() {
        assertTrue(HarpoonLogic.canPayOut(HarpoonLogic.MIN_LENGTH));
        assertFalse(HarpoonLogic.canPayOut(HarpoonLogic.RANGE));
        assertTrue(HarpoonLogic.canReelIn(HarpoonLogic.RANGE));
        assertFalse(HarpoonLogic.canReelIn(HarpoonLogic.MIN_LENGTH));
    }
}
