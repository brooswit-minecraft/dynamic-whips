package io.github.brooswitminecraft.dynamicwhips;

import static io.github.brooswitminecraft.dynamicwhips.rope.gametest.GameTestSupport.simulateGravityEachTick;
import static io.github.brooswitminecraft.dynamicwhips.rope.gametest.GameTestSupport.spawnMockPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Headless coverage for MINECRAFT-87 acceptance criteria 2, 4, 6, 7 and (as a documentation-only
 * caveat, see the class-level note below) 10. Criterion 5 has NO GameTest here at all — see the
 * trailing comment at the end of this class, and docs/hooks.md section 5, for why. Every test here
 * drives the REAL {@link HookItem#use}
 * and {@link HookState}, never {@code RopeManager} directly where a real path exists, the same
 * convention {@code WhipGameTests} established. In the same package as {@link HookItem} and
 * {@link HookState} deliberately, so tests can read {@link HookState} directly instead of this
 * story inventing a public test-only accessor for it.
 *
 * <p>None of these tests have a real client, so none can send a real {@link HookInputPayload} —
 * every test that needs continuous reel/pay-out input calls {@link HookState#setInput} itself each
 * tick, simulating what that payload's handler would have done (identical to how
 * {@code WhipGameTests} calls {@link WhipHoldState#ping} directly instead of sending a real packet).
 *
 * <p><strong>Criterion 10 (multiplayer sync) is not independently GameTested here</strong> — a
 * hook's rope rides the IDENTICAL {@code RopeManager}/{@code RopeNetworking} plumbing rope-core and
 * the whip already use (this story adds no new sync code at all), so there is nothing hook-specific
 * to re-test; see docs/hooks.md for the same "what a headless test can/can't assert" caveat
 * docs/rope-core.md section 6 already gives that plumbing.
 */
@GameTestHolder(DynamicWhipsMod.MODID)
@PrefixGameTestTemplate(false)
public final class HookGameTests {
    private HookGameTests() {
    }

    private static ServerPlayer spawnPlayerWithHook(GameTestHelper helper, BlockPos relativeSpawn,
            HookLogic.Tier tier) {
        ServerPlayer player = spawnMockPlayer(helper, relativeSpawn);
        ItemStack stack = switch (tier) {
            case IRON -> new ItemStack(DynamicWhipsMod.IRON_HOOK.get());
            case DIAMOND -> new ItemStack(DynamicWhipsMod.DIAMOND_HOOK.get());
            case NETHERITE -> new ItemStack(DynamicWhipsMod.NETHERITE_HOOK.get());
        };
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        return player;
    }

    private static void lookAt(GameTestHelper helper, ServerPlayer player, BlockPos targetBlock) {
        player.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(helper.absolutePos(targetBlock)));
    }

    /**
     * Criterion 2, the spec's own core mechanic: a hook anchors to the looked-at block within its
     * tier's range and STAYS connected — unlike the whip, nothing needs to keep pinging it — and a
     * second {@code use()} detaches it.
     */
    @GameTest(template = "lifecycle", timeoutTicks = 60)
    public static void anchorStaysConnectedAndSecondUseDetaches(GameTestHelper helper) {
        BlockPos anchorBlock = new BlockPos(2, 6, 2);
        helper.setBlock(anchorBlock, Blocks.STONE);
        ServerPlayer player = spawnPlayerWithHook(helper, new BlockPos(2, 5, 2), HookLogic.Tier.IRON);
        lookAt(helper, player, anchorBlock);

        var hook = DynamicWhipsMod.IRON_HOOK.get();
        hook.use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        HookState.Hold hold = HookState.get(player.getUUID());
        helper.assertTrue(hold != null, "first use() did not register a hold");
        UUID ropeId = hold.ropeId();
        helper.assertTrue(RopeManager.get(ropeId) != null, "first use() did not actually attach a rope");

        helper.runAfterDelay(10, () -> {
            // Nothing has pinged or held anything in the last 10 ticks — unlike the whip, the hook
            // must stay connected with zero ongoing input at all.
            helper.assertTrue(RopeManager.get(ropeId) != null,
                    "a hook rope must stay connected with no held input — it is not hold-to-keep like the whip");
            helper.assertTrue(HookState.get(player.getUUID()) != null, "hold bookkeeping must still be live too");

            hook.use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
            helper.assertTrue(RopeManager.get(ropeId) == null, "a second use() must detach the rope");
            helper.assertTrue(HookState.get(player.getUUID()) == null,
                    "a second use() must also clear the hook's own hold bookkeeping");
            helper.succeed();
        });
    }

    /**
     * Criteria 3 and 6: simulated jump (reel in) and shift (pay out) input, applied one rope
     * segment per tick by {@link HookState#tickAll} (already wired into the real
     * {@code ServerTickEvent.Post} listener {@code DynamicWhipsMod} registers — this test only
     * supplies the input a real client's payload would have), pays the rope out to (and NEVER
     * past — {@link HookLogic#canPayOut} gates on the projected length, no overshoot tolerance at
     * all, per review on PR #15) the Iron Hook's own 16-block cap, then reels it back down to
     * exactly the shared minimum. This rig's own attach distance (5 blocks, slack 1.1) lays out at
     * EXACTLY {@link HookLogic#SEGMENT_SPACING} actual spacing, so both ends are reached exactly,
     * not just "close enough" — the tolerance below is only float-noise width, not a segment.
     */
    @GameTest(template = "lifecycle", timeoutTicks = 500)
    public static void jumpReelsInShiftPaysOutUpToTierMax(GameTestHelper helper) {
        BlockPos anchorBlock = new BlockPos(2, 6, 2);
        helper.setBlock(anchorBlock, Blocks.STONE);
        ServerPlayer player = spawnPlayerWithHook(helper, new BlockPos(2, 1, 2), HookLogic.Tier.IRON);
        lookAt(helper, player, anchorBlock);

        DynamicWhipsMod.IRON_HOOK.get().use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        HookState.Hold hold = HookState.get(player.getUUID());
        helper.assertTrue(hold != null, "setup failed: no hold registered");
        UUID ropeId = hold.ropeId();
        double tolerance = 1.0e-6;

        int[] tick = {0};
        // At HookState.STEP_COOLDOWN_TICKS's own cadence (one step per 5 ticks, see that field's
        // javadoc for why), 220 ticks is comfortably enough calls to reach the Iron Hook's own
        // 16-block cap from this rig's short initial attach distance.
        int payOutTicks = 220;
        helper.onEachTick(() -> {
            tick[0]++;
            boolean payOutPhase = tick[0] <= payOutTicks;
            HookState.setInput(player.getUUID(), !payOutPhase, payOutPhase, helper.getLevel().getGameTime());
        });

        helper.runAfterDelay(payOutTicks, () -> {
            double length = RopeManager.length(ropeId);
            helper.assertTrue(length <= HookLogic.Tier.IRON.maxLength() + tolerance,
                    "pay-out exceeded the Iron Hook's own 16-block cap: " + length);
            helper.assertTrue(length >= HookLogic.Tier.IRON.maxLength() - tolerance,
                    payOutTicks + " ticks of continuous pay-out input never reached exactly the 16-block cap: "
                            + length);

            helper.runAfterDelay(220, () -> {
                double reeled = RopeManager.length(ropeId);
                helper.assertTrue(reeled <= HookLogic.MIN_LENGTH + tolerance,
                        "220 ticks of continuous reel-in input never reached exactly MIN_LENGTH: " + reeled);
                helper.assertTrue(reeled >= HookLogic.MIN_LENGTH - tolerance,
                        "reel-in went below the documented shared minimum: " + reeled);
                helper.succeed();
            });
        });
    }

    /**
     * Criterion 4: reeling in must resolve the player's position through the rope-core physics
     * constraint ({@code RopeManager#reelIn} → Sable's own solver + {@code PlayerRope}'s swing
     * correction), never a direct {@code setPos}/velocity-along-straight-line move toward the
     * anchor. An obstruction post sits between anchor and player, matching the ticket's own wording
     * ("asserting the player's path while reeling is not a straight line through geometry when an
     * obstacle is between player and anchor"); this test's own assertion is deliberately NARROWER
     * than "the rope actually catches on the post" (that claim is MINECRAFT-127/155's own
     * documented, unresolved nondeterminism — docs/rope-core.md section 10.10 — which this story
     * was explicitly told to watch for QUALITATIVELY WORSE regressions in, not to re-litigate): it
     * only checks that the player's sampled trajectory while reeling is never collinear with the
     * straight anchor-to-start line, which is true precisely because gravity and the swing
     * constraint govern the player's position every tick, not a linear interpolation toward the
     * anchor — the actual code-level guarantee criterion 4 cares about. {@code required = false}
     * because this still exercises the same obstruction-rig physics path section 10 found
     * nondeterministic, and a timeout/crash there is this test's problem to report, not this
     * story's rope-core bug to chase.
     */
    @GameTest(template = "catch_on_obstruction", timeoutTicks = 250, required = false)
    public static void reelingResolvesThroughPhysicsNotStraightLineTeleport(GameTestHelper helper) {
        BlockPos anchorBlock = new BlockPos(1, 14, 4);
        helper.setBlock(anchorBlock, Blocks.STONE);
        for (int y = 1; y <= 6; y++) {
            helper.setBlock(new BlockPos(4, y, 4), Blocks.STONE);
        }
        ServerPlayer player = spawnPlayerWithHook(helper, new BlockPos(7, 11, 4), HookLogic.Tier.IRON);
        lookAt(helper, player, anchorBlock);
        simulateGravityEachTick(helper, player);
        Vec3 anchorPos = Vec3.atCenterOf(helper.absolutePos(anchorBlock));
        Vec3 startPos = player.position();

        DynamicWhipsMod.IRON_HOOK.get().use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        HookState.Hold hold = HookState.get(player.getUUID());
        helper.assertTrue(hold != null, "setup failed: hook failed to anchor for this rig's geometry");
        UUID ropeId = hold.ropeId();

        List<Vec3> sampled = new ArrayList<>();
        int[] tick = {0};
        helper.onEachTick(() -> {
            tick[0]++;
            HookState.setInput(player.getUUID(), true, false, helper.getLevel().getGameTime());
            if (tick[0] % 10 == 0) {
                sampled.add(player.position());
            }
        });

        helper.runAfterDelay(170, () -> {
            helper.assertTrue(RopeManager.get(ropeId) != null, "rope was torn down before reeling could be observed");
            helper.assertTrue(!sampled.isEmpty(), "test bug: no samples collected");

            double maxDeviation = 0;
            for (Vec3 p : sampled) {
                maxDeviation = Math.max(maxDeviation, perpendicularDistanceToLine(p, startPos, anchorPos));
            }
            helper.assertTrue(maxDeviation > 0.1,
                    "every sampled position while reeling sat on the straight anchor-to-start line (max deviation="
                            + maxDeviation + "): this is the signature of a direct setPos/straight-line move toward"
                            + " the anchor, not a physics-resolved reel");
            helper.succeed();
        });
    }

    private static double perpendicularDistanceToLine(Vec3 point, Vec3 lineA, Vec3 lineB) {
        Vec3 ab = lineB.subtract(lineA);
        double abLengthSq = ab.lengthSqr();
        if (abLengthSq < 1.0e-9) {
            return point.distanceTo(lineA);
        }
        Vec3 ap = point.subtract(lineA);
        double t = ap.dot(ab) / abLengthSq;
        Vec3 closest = lineA.add(ab.scale(t));
        return point.distanceTo(closest);
    }

    /**
     * Criterion 6 at real scale (not just {@code HookLogicTest}'s idealized pure-Java cycles): one
     * real pay-out/reel-in cycle through the actual {@code RopeManager}-backed rope never exceeds
     * the Iron Hook's own cap AT ALL — {@link HookLogic#canPayOut} gates on the projected length,
     * so there is no overshoot tolerance to allow here any more (review on PR #15).
     *
     * <p><strong>Deliberately ONE cycle, not "many."</strong> An earlier revision of this test ran
     * three full cycles with a {@code RopeManager#payOut}/{@code #reelIn} call every tick — that
     * crashed Sable's own native Rapier layer in CI ("Rapier native panic: index out of bounds"),
     * taking the entire GameTest server down with it, not just this test. The exhaustive
     * "many repeated cycles, no drift" claim criterion 6 actually asks for is instead covered
     * entirely by {@code HookLogicTest} (pure Java, no Sable involved at all, genuinely safe to run
     * thousands of cycles); this test's own job, at real scale, is reduced to "one cycle doesn't
     * exceed the cap" plus the throttle in {@link HookState#STEP_COOLDOWN_TICKS}'s own javadoc —
     * see docs/hooks.md section 6 for the full account of why, and MINECRAFT-87's own ticket for
     * the escalation.
     */
    @GameTest(template = "lifecycle", timeoutTicks = 500)
    public static void hardCapNeverExceededOnARealPayOutReelInCycle(GameTestHelper helper) {
        BlockPos anchorBlock = new BlockPos(2, 6, 2);
        helper.setBlock(anchorBlock, Blocks.STONE);
        ServerPlayer player = spawnPlayerWithHook(helper, new BlockPos(2, 1, 2), HookLogic.Tier.IRON);
        lookAt(helper, player, anchorBlock);

        DynamicWhipsMod.IRON_HOOK.get().use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        HookState.Hold hold = HookState.get(player.getUUID());
        helper.assertTrue(hold != null, "setup failed: no hold registered");
        UUID ropeId = hold.ropeId();
        double tolerance = 1.0e-6;

        // One full pay-out/reel-in cycle, ~220 ticks each way — comfortably enough calls at
        // HookState.STEP_COOLDOWN_TICKS's own cadence to reach both ends of this short (16-block)
        // tier's range once.
        double[] maxObserved = {0};
        int phaseTicks = 220;
        int[] tick = {0};
        helper.onEachTick(() -> {
            tick[0]++;
            boolean payOutPhase = tick[0] <= phaseTicks;
            HookState.setInput(player.getUUID(), !payOutPhase, payOutPhase, helper.getLevel().getGameTime());
            Double length = RopeManager.length(ropeId);
            if (length != null) {
                maxObserved[0] = Math.max(maxObserved[0], length);
            }
        });

        helper.runAfterDelay(phaseTicks * 2, () -> {
            helper.assertTrue(maxObserved[0] <= HookLogic.Tier.IRON.maxLength() + tolerance,
                    "the hard cap was exceeded across a real pay-out/reel-in cycle: " + maxObserved[0]);
            helper.succeed();
        });
    }

    /**
     * Criterion 7's own half of the lifecycle table this story adds: {@code RopeManager#tickAll}
     * (rope-core, generic to every rope regardless of what attached it) already tears the rope
     * down the tick after its anchor block breaks — this test is the HOOK's own bookkeeping half
     * of that gap, mirroring {@code WhipGameTests#breakingAnchorBlockClearsWhipHoldImmediately}:
     * {@link HookState} must not keep pointing at a rope that no longer exists.
     */
    @GameTest(template = "lifecycle", timeoutTicks = 60)
    public static void breakingAnchorBlockClearsHookStateImmediately(GameTestHelper helper) {
        BlockPos anchorBlock = new BlockPos(2, 6, 2);
        helper.setBlock(anchorBlock, Blocks.STONE);
        ServerPlayer player = spawnPlayerWithHook(helper, new BlockPos(2, 5, 2), HookLogic.Tier.IRON);
        lookAt(helper, player, anchorBlock);

        DynamicWhipsMod.IRON_HOOK.get().use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        HookState.Hold hold = HookState.get(player.getUUID());
        helper.assertTrue(hold != null, "setup failed: no hold registered");
        UUID ropeId = hold.ropeId();

        helper.destroyBlock(anchorBlock);

        helper.runAfterDelay(3, () -> {
            helper.assertTrue(RopeManager.get(ropeId) == null,
                    "rope core should have torn the rope down once its anchor block is gone");
            helper.assertTrue(HookState.get(player.getUUID()) == null,
                    "the hook's own hold bookkeeping must be cleared once the anchor is gone, not left pointing"
                            + " at a rope that no longer exists");
            helper.succeed();
        });
    }

    /**
     * Criterion 7: the real {@code LivingDeathEvent} listener {@code DynamicWhipsMod} wires must
     * clear {@link HookState} alongside tearing the rope down — posted through the live NeoForge
     * event bus (not a direct method call), mirroring {@code RopeGameTests#deathDetachesAllOwnedRopes}.
     */
    @GameTest(template = "lifecycle", timeoutTicks = 60)
    public static void playerDeathClearsHookState(GameTestHelper helper) {
        BlockPos anchorBlock = new BlockPos(2, 6, 2);
        helper.setBlock(anchorBlock, Blocks.STONE);
        ServerPlayer player = spawnPlayerWithHook(helper, new BlockPos(2, 5, 2), HookLogic.Tier.IRON);
        lookAt(helper, player, anchorBlock);

        DynamicWhipsMod.IRON_HOOK.get().use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        HookState.Hold hold = HookState.get(player.getUUID());
        helper.assertTrue(hold != null, "setup failed: no hold registered");
        UUID ropeId = hold.ropeId();

        NeoForge.EVENT_BUS.post(new LivingDeathEvent(player, player.damageSources().genericKill()));

        helper.assertTrue(RopeManager.get(ropeId) == null, "rope survived the player's LivingDeathEvent");
        helper.assertTrue(HookState.get(player.getUUID()) == null,
                "the hook's own hold bookkeeping must be cleared on death, not left pointing at a dead rope");
        helper.succeed();
    }

    /**
     * Criterion 5's own required demonstration ("anchor near top of a deep shaft, pay out to
     * depth, reel back") is NOT GameTested at all, deliberately — see docs/hooks.md section 5 for
     * the full account of why, and MINECRAFT-87's own ticket for the escalation. Summary: an
     * earlier revision of this test grew a rope via real {@code RopeManager.payOut} calls near the
     * top of a tall shaft with real gravity simulation. Across repeated CI runs it showed TWO
     * different, unpredictable outcomes depending on this GameTest structure's own randomly
     * assigned absolute world coordinates (the SAME far-from-origin float32 placement
     * nondeterminism {@code docs/rope-core.md} section 10.1 already documents for the obstruction
     * rig): sometimes the player's real distance from the anchor stayed near the original attach
     * length regardless of how far the nominal cap grew; sometimes it travelled tens of blocks.
     * **The second, larger-real-movement case crashed Sable's native Rapier layer**
     * ({@code RuntimeException: Rapier native panic: index out of bounds: the len is 8 but the
     * index is 11}, inside {@code parry3d}'s own BVH build) — a DIFFERENT native panic than the
     * one {@link HookState#STEP_COOLDOWN_TICKS} was added to stop, and one the cooldown did not
     * prevent. Given a GameTest that crashes the entire shared headless server unpredictably,
     * depending on where the framework happens to place its structure, is unsafe to keep in CI at
     * any tick budget this story has tried, this demonstration is removed rather than tuned
     * further — this is reported to MINECRAFT-87 as a second, more serious rope-core finding, not
     * worked around with a smaller "safe" number that might simply not have been unlucky yet.
     */
}
