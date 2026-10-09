package io.github.brooswitminecraft.dynamicwhips;

import static io.github.brooswitminecraft.dynamicwhips.rope.gametest.GameTestSupport.simulateGravityEachTick;
import static io.github.brooswitminecraft.dynamicwhips.rope.gametest.GameTestSupport.spawnMockPlayer;

import java.util.UUID;

import io.github.brooswitminecraft.dynamicwhips.rope.PlayerRope;
import io.github.brooswitminecraft.dynamicwhips.rope.RopeConstants;
import io.github.brooswitminecraft.dynamicwhips.rope.RopeManager;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Headless coverage for MINECRAFT-86 acceptance criteria 1-4 and 8, run by {@code gradlew
 * runGameTestServer} and wired into CI (see {@code .github/workflows/ci.yml}, whose expected-test
 * count scans this whole module, not just one file). Every test here drives the REAL {@link
 * WhipItem#use} — never {@code RopeManager} directly — so these exercise the whip's own cooldown
 * and hold bookkeeping, not just the rope core underneath it (that is {@code RopeGameTests}' job).
 * In the same package as {@link WhipItem} and {@link WhipHoldState} deliberately, so tests can
 * read {@link WhipHoldState} directly instead of this story inventing a public test-only accessor
 * for it.
 *
 * <p>None of these tests have a real client, so none of them can send a real {@link
 * WhipHoldPingPayload} — a GameTest's mock player simply never pings unless a test calls {@link
 * WhipHoldState#ping} itself, which is exactly how {@link #wholeWhipArrestsAFallAndSwing} below
 * simulates "the player keeps holding the key" for longer than {@link WhipHoldState#TIMEOUT_TICKS}.
 * Letting a hold go quiet and time out on its own (no explicit release call needed) is in fact the
 * easiest way to GameTest the real mechanism — see {@link #blockHitAttachesHoldToKeepAnchorWithNoReel}.
 *
 * <p>Criterion 5 (entity tether) and criterion 6 (consistency with the shipped combat half,
 * unchanged by this story and still pinned by {@code WhipLogicTest}) are not covered here — see
 * {@code docs/whip.md} and the PR description for why.
 */
@GameTestHolder(DynamicWhipsMod.MODID)
@PrefixGameTestTemplate(false)
public final class WhipGameTests {
    private WhipGameTests() {
    }

    private static ServerPlayer spawnPlayerWithWhip(GameTestHelper helper, BlockPos relativeSpawn) {
        ServerPlayer player = spawnMockPlayer(helper, relativeSpawn);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(DynamicWhipsMod.LEATHER_WHIP.get()));
        return player;
    }

    /** Aims {@code player}'s eyes exactly at {@code targetBlock}'s center — the whip's reach check
     * is a look-ray clip, and a test must not depend on a mock player's default facing. */
    private static void lookAt(GameTestHelper helper, ServerPlayer player, BlockPos targetBlock) {
        player.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(helper.absolutePos(targetBlock)));
    }

    /**
     * Criteria 1-3 and 8: a whip hit on a block (the real {@link WhipItem#use}, not {@code
     * RopeManager} called directly) creates a hold-to-keep anchor whose length never changes
     * while held, and — since nothing pings on its behalf after that — {@link
     * WhipHoldState#tickTimeouts} detaches it once the silence passes {@link
     * WhipHoldState#TIMEOUT_TICKS}, the same mechanism that covers a real release, a switched-away
     * whip, or a dropped one (see {@link WhipHoldState}'s javadoc for why those three collapse
     * into one case). Structure: {@code lifecycle.nbt} (shared with {@code RopeGameTests}' own
     * lifecycle tests).
     */
    @GameTest(template = "lifecycle", timeoutTicks = 60)
    public static void blockHitAttachesHoldToKeepAnchorWithNoReel(GameTestHelper helper) {
        BlockPos anchorBlock = new BlockPos(2, 6, 2);
        helper.setBlock(anchorBlock, Blocks.STONE);
        ServerPlayer player = spawnPlayerWithWhip(helper, new BlockPos(2, 5, 2));
        lookAt(helper, player, anchorBlock);

        var whip = DynamicWhipsMod.LEATHER_WHIP.get();
        whip.use(helper.getLevel(), player, InteractionHand.MAIN_HAND);

        helper.assertTrue(player.getCooldowns().isOnCooldown(whip),
                "the cooldown must apply on the block anchor exactly as it does on an entity hit"
                        + " (criterion 2/6: one whip, one cooldown rule)");
        WhipHoldState.Hold hold = WhipHoldState.get(player.getUUID());
        helper.assertTrue(hold != null, "WhipHoldState recorded no hold after a block hit's use()");
        UUID ropeId = hold.ropeId();
        helper.assertTrue(RopeManager.get(ropeId) != null, "no rope was actually registered for the hold");

        double restLength = RopeManager.length(ropeId);
        helper.assertTrue(restLength <= WhipLogic.MAX_ANCHOR_ROPE_LENGTH + 1.0e-6,
                "whip anchor rope (" + restLength + ") exceeded its own documented maximum ("
                        + WhipLogic.MAX_ANCHOR_ROPE_LENGTH + ") — criterion 7");

        helper.runAfterDelay(2, () -> {
            // Still well inside the timeout window (nothing has pinged, but the grace period
            // from attach-time has not elapsed) — the hold and the rope must both still be alive,
            // and the rope's length must still be exactly what it was at attach (criterion 3).
            helper.assertTrue(RopeManager.get(ropeId) != null, "the hold timed out far too early");
            helper.assertTrue(Math.abs(RopeManager.length(ropeId) - restLength) < 1.0e-9,
                    "criterion 3: no reel in/out — rope length changed while held, with nothing in this test"
                            + " ever calling payOut/reelIn/adjustLength");

            helper.runAfterDelay(WhipHoldState.TIMEOUT_TICKS + 3, () -> {
                helper.assertTrue(RopeManager.get(ropeId) == null,
                        "a hold that receives no further pings must time out and detach its rope"
                                + " (criterion 2/8: this is the same mechanism a real release, a switched-away"
                                + " whip, or a dropped whip all go through)");
                helper.assertTrue(WhipHoldState.get(player.getUUID()) == null,
                        "a timed-out hold must also clear the whip's own hold bookkeeping");
                helper.succeed();
            });
        });
    }

    /**
     * Criterion 8: the rope core can tear a rope down with no detach call from the whip at all
     * (anchor block broken — {@code RopeManager#tickAll}, already covered for rope-core itself by
     * {@code RopeGameTests#breakingAnchorBlockDetachesRope}). This test is the whip's OWN half of
     * that gap: {@link WhipHoldState#tickTimeouts} must notice the rope is already gone and clear
     * the hold immediately — well before the ping-silence timeout would otherwise fire — so the
     * bookkeeping does not linger pointing at a rope that no longer exists.
     */
    @GameTest(template = "lifecycle", timeoutTicks = 60)
    public static void breakingAnchorBlockClearsWhipHoldImmediately(GameTestHelper helper) {
        BlockPos anchorBlock = new BlockPos(2, 6, 2);
        helper.setBlock(anchorBlock, Blocks.STONE);
        ServerPlayer player = spawnPlayerWithWhip(helper, new BlockPos(2, 5, 2));
        lookAt(helper, player, anchorBlock);

        DynamicWhipsMod.LEATHER_WHIP.get().use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        WhipHoldState.Hold hold = WhipHoldState.get(player.getUUID());
        helper.assertTrue(hold != null, "setup failed: no hold registered");
        UUID ropeId = hold.ropeId();

        // Keep pinging so the ONLY thing that could end this hold is the anchor breaking, not an
        // incidental ping-silence timeout racing it.
        helper.onEachTick(() -> WhipHoldState.ping(player.getUUID(), helper.getLevel().getGameTime()));

        helper.destroyBlock(anchorBlock);

        helper.runAfterDelay(3, () -> {
            helper.assertTrue(RopeManager.get(ropeId) == null,
                    "rope core should have torn the rope down once its anchor block is gone");
            helper.assertTrue(WhipHoldState.get(player.getUUID()) == null,
                    "the whip's own hold bookkeeping must be cleared once the anchor is gone, not left pointing"
                            + " at a rope that no longer exists");
            helper.succeed();
        });
    }

    /**
     * Criterion 4, the Definition of Done's own required demonstration: the whip's block anchor,
     * attached through the real {@link WhipItem#use} (not {@code RopeManager} called directly),
     * arrests a falling player and converts the fall into a swing. Same geometry and tolerances as
     * {@code RopeGameTests#fallArrestSwing} (that test's own result is what proves this geometry
     * and tolerance actually work) — this test's additions are going through the whip itself,
     * simulating a continuously-held key via repeated {@link WhipHoldState#ping} (standing in for
     * the real client's per-tick {@link WhipHoldPingPayload} this headless test cannot send), and
     * the same no-reel check as {@link #blockHitAttachesHoldToKeepAnchorWithNoReel}. Unlike {@code
     * RopeGameTests#catchOnObstruction}/{@code tunnellingThreshold}, this rig has no obstruction
     * post at all, so it does not inherit MINECRAFT-127's documented obstruction nondeterminism
     * (docs/rope-core.md section 2/3) — it stays required.
     */
    @GameTest(template = "fall_arrest_swing", timeoutTicks = 200)
    public static void wholeWhipArrestsAFallAndSwing(GameTestHelper helper) {
        BlockPos anchorBlock = new BlockPos(3, 12, 3);
        helper.setBlock(anchorBlock, Blocks.STONE);
        Vec3 anchorPos = Vec3.atCenterOf(helper.absolutePos(anchorBlock));

        BlockPos playerSpawn = new BlockPos(3, 11, 1);
        ServerPlayer player = spawnPlayerWithWhip(helper, playerSpawn);
        lookAt(helper, player, anchorBlock);
        simulateGravityEachTick(helper, player);
        double spawnY = player.position().y;

        DynamicWhipsMod.LEATHER_WHIP.get().use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        WhipHoldState.Hold hold = WhipHoldState.get(player.getUUID());
        helper.assertTrue(hold != null, "the whip failed to anchor: look-ray/reach geometry is wrong for this rig");
        UUID ropeId = hold.ropeId();

        // Stand-in for the real client's continuous WhipHoldPingPayload — this test needs the hold
        // to survive well past WhipHoldState.TIMEOUT_TICKS to observe the swing.
        helper.onEachTick(() -> WhipHoldState.ping(player.getUUID(), helper.getLevel().getGameTime()));

        double restLength = RopeManager.length(ropeId);
        // Same correction-lag tolerance as RopeGameTests#fallArrestSwing: the swing constraint is
        // re-applied once per server tick, one tick after this test's own gravity move, so a real
        // overshoot of up to one tick's fall distance at the speed reached right as the rope goes
        // taut is expected and correct, not a bug.
        double maxAllowed = restLength + RopeConstants.SWING_SLACK + 1.5;

        helper.runAfterDelay(160, () -> {
            PlayerRope rope = RopeManager.get(ropeId);
            helper.assertTrue(rope != null, "rope was torn down before the swing could be observed");

            helper.assertTrue(Math.abs(RopeManager.length(ropeId) - restLength) < 1.0e-9,
                    "criterion 3: no reel in/out — rope length must never change after attach");

            double distanceFromAnchor = anchorPos.distanceTo(player.position());
            helper.assertTrue(distanceFromAnchor <= maxAllowed,
                    "player fell past the rope's rest length (" + restLength + " blocks): measured "
                            + distanceFromAnchor + " — the whip's anchor failed to arrest the fall");

            double fallSpeed = -player.getDeltaMovement().y;
            helper.assertTrue(fallSpeed < 0.5,
                    "player is still falling fast (vy=" + player.getDeltaMovement().y
                            + ") long after the anchor should have arrested and converted the fall to a swing");

            helper.assertTrue(player.position().y > helper.absolutePos(new BlockPos(0, 1, 0)).getY() - 0.5,
                    "player reached the floor safety net: the whip's anchor did not catch at all");

            helper.assertTrue(spawnY - player.position().y > 0.5,
                    "player never actually fell from its spawn height — a frozen player trivially satisfies the"
                            + " other assertions above without proving the whip's anchor did anything");

            helper.succeed();
        });
    }
}
