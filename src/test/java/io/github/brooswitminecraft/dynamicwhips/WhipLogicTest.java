package io.github.brooswitminecraft.dynamicwhips;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WhipLogicTest {
    @Test
    void pointBlankDoesMinimumDamage() {
        assertEquals(WhipLogic.MIN_DAMAGE, WhipLogic.damageAt(0), 1e-6);
    }

    @Test
    void fullReachDoesMaximumDamage() {
        assertEquals(WhipLogic.MAX_DAMAGE, WhipLogic.damageAt(WhipLogic.REACH), 1e-6);
    }

    @Test
    void damageGrowsWithDistance() {
        float previous = -1;
        for (double d = 0; d <= WhipLogic.REACH; d += 0.25) {
            float damage = WhipLogic.damageAt(d);
            assertTrue(damage > previous, "damage at " + d);
            previous = damage;
        }
    }

    @Test
    void documentedWholeBlockValues() {
        float[] expected = {2.0F, 2.8F, 3.6F, 4.4F, 5.2F, 6.0F};
        for (int d = 0; d < expected.length; d++) {
            assertEquals(expected[d], WhipLogic.damageAt(d), 1e-5, "damage at " + d);
        }
    }

    @Test
    void outOfReachOrInvalidDistanceDoesNothing() {
        assertEquals(0.0F, WhipLogic.damageAt(WhipLogic.REACH + 0.01));
        assertEquals(0.0F, WhipLogic.damageAt(-1));
        assertEquals(0.0F, WhipLogic.damageAt(Double.NaN));
        assertEquals(0.0F, WhipLogic.damageAt(Double.POSITIVE_INFINITY));
    }

    /**
     * MINECRAFT-86 criterion 7: the whip's hold-to-keep anchor must stay a skill toy, not a
     * grappling gun. The cheapest grappling hook in the spec of record (Confluence "Dynamic
     * Whips — External Contractor Handoff", page 49872898) is the 16-block Iron Hook — the whip's
     * own maximum rope length must stay well under even that, with no reel control at all.
     */
    @Test
    void anchorRopeStaysMuchShorterThanTheCheapestGrapplingHook() {
        double cheapestGrapplingHookLength = 16.0;
        assertTrue(WhipLogic.MAX_ANCHOR_ROPE_LENGTH < cheapestGrapplingHookLength,
                "whip anchor rope (" + WhipLogic.MAX_ANCHOR_ROPE_LENGTH + ") must stay well under even the"
                        + " cheapest (16-block Iron) grappling hook");
        assertEquals(WhipLogic.REACH * WhipLogic.ANCHOR_SLACK, WhipLogic.MAX_ANCHOR_ROPE_LENGTH, 1e-9);
    }
}
