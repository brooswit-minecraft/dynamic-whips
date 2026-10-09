package io.github.brooswitminecraft.dynamicwhips;

/**
 * Pure whip tuning and damage rules, free of Minecraft types so they can be unit tested. Every
 * number that shapes how the Leather Whip feels lives here.
 */
public final class WhipLogic {
    /** Blocks the whip reaches from the eyes. Vanilla melee reaches about 3. */
    public static final double REACH = 5.0;
    /** Damage (half hearts) of a hit at point blank. */
    public static final float MIN_DAMAGE = 2.0F;
    /** Damage of a hit at full reach: the tip of a whip cracks hardest. Just under an iron sword. */
    public static final float MAX_DAMAGE = 6.0F;
    /** Ticks before the next swing (1.5 s), slower than a sword so range is not free. */
    public static final int COOLDOWN_TICKS = 30;
    /** Vanilla leather chestplate durability (5 * 16), plenty for a starter tool. */
    public static final int DURABILITY = 80;

    /**
     * Slack multiplier for the whip's hold-to-keep block anchor (MINECRAFT-86 criterion 3/7):
     * the rope is laid out at {@code straightLineDistance * ANCHOR_SLACK} and never changes after
     * that — no reel in, no reel out, matching {@code RopeGameTests#buildCatchRig}'s own rig
     * slack and the Netherite Hook's documented default (docs/rope-core.md section 1.5).
     */
    public static final double ANCHOR_SLACK = 1.1;

    /**
     * Longest rope the whip's own block anchor can ever lay out, in blocks: at most {@link
     * #REACH} away, times {@link #ANCHOR_SLACK}. Must stay well under even the cheapest (16-block
     * Iron) grappling hook from the Confluence spec ("Dynamic Whips — External Contractor
     * Handoff", page 49872898) — the whip is a skill toy and a weapon, not a grappling gun
     * (criterion 7); {@code WhipLogicTest} pins that this constant stays far below 16.
     */
    public static final double MAX_ANCHOR_ROPE_LENGTH = REACH * ANCHOR_SLACK;

    private WhipLogic() {
    }

    /**
     * Damage for a hit at the given distance from the eyes: linear from MIN_DAMAGE at 0 blocks to
     * MAX_DAMAGE at REACH (2.0, 2.8, 3.6, 4.4, 5.2, 6.0 at 0 through 5 blocks). Out of reach, negative
     * or NaN distances do nothing (0).
     */
    public static float damageAt(double distance) {
        if (!(distance >= 0) || distance > REACH) {
            return 0.0F;
        }
        return (float) (MIN_DAMAGE + (MAX_DAMAGE - MIN_DAMAGE) * (distance / REACH));
    }
}
