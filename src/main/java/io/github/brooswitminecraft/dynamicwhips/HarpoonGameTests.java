package io.github.brooswitminecraft.dynamicwhips;

import static io.github.brooswitminecraft.dynamicwhips.rope.gametest.GameTestSupport.spawnMockPlayer;

import java.util.UUID;

import io.github.brooswitminecraft.dynamicwhips.rope.RopeManager;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Headless coverage for MINECRAFT-88 acceptance criteria 2, 3, 4 and 7. Drives the real {@link
 * HarpoonItem#use}, the real thrown {@link HarpoonEntity} (ticked by the GameTest server exactly
 * like any other entity — nothing here calls its hit logic directly), and the real {@link
 * HarpoonState}, the same "exercise the shipped path, not a test-only shortcut" convention {@code
 * HookGameTests}/{@code WhipGameTests} already use. Continuous reel input is simulated via {@link
 * HarpoonState#setInput} exactly as {@code HookGameTests} does for {@link HookInputPayload} — see
 * that class's own javadoc for why (no real client in a GameTest).
 *
 * <p>Target entities have {@code setNoAi(true)} so any motion observed is the rope's own physics
 * correction, never the mob's own AI wandering/fleeing.
 */
@net.neoforged.neoforge.gametest.GameTestHolder(DynamicWhipsMod.MODID)
@net.neoforged.neoforge.gametest.PrefixGameTestTemplate(false)
public final class HarpoonGameTests {
    private HarpoonGameTests() {
    }

    private static ServerPlayer spawnPlayerWithHarpoon(GameTestHelper helper, BlockPos relativeSpawn) {
        ServerPlayer player = spawnMockPlayer(helper, relativeSpawn);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(DynamicWhipsMod.HARPOON_ITEM.get()));
        return player;
    }

    /**
     * A chicken is the "light" weight class for {@link HarpoonLogic#pullBalance} (small bounding
     * box), but a vanilla chicken's own 4 HP does not survive {@link HarpoonLogic#DAMAGE} (5),
     * and {@link HarpoonEntity#onHitEntity} correctly refuses to attach a rope to a target it just
     * killed (AC1's no-dangling-rope rule applies to a lethal hit too, not only a miss — see
     * {@link #lethalHitCreatesNoStaleRope}, which exercises exactly that path). Every test here
     * that needs the target to survive the initial hit so it can actually be reeled bumps its max
     * health first; this does not change the chicken's bounding box, so its weight class for
     * {@code pullBalance} is unaffected.
     */
    private static Chicken spawnLightTarget(GameTestHelper helper, BlockPos relativeSpawn) {
        Chicken chicken = spawnFragileLightTarget(helper, relativeSpawn);
        var maxHealth = chicken.getAttribute(Attributes.MAX_HEALTH);
        if (maxHealth != null) {
            maxHealth.setBaseValue(20.0);
        }
        chicken.setHealth(chicken.getMaxHealth());
        return chicken;
    }

    /** As {@link #spawnLightTarget}, but with the vanilla 4 HP a chicken actually ships with —
     * for {@link #lethalHitCreatesNoStaleRope}, which deliberately wants the hit to be lethal. */
    private static Chicken spawnFragileLightTarget(GameTestHelper helper, BlockPos relativeSpawn) {
        Chicken chicken = new Chicken(EntityType.CHICKEN, helper.getLevel());
        chicken.setNoAi(true);
        Vec3 pos = Vec3.atBottomCenterOf(helper.absolutePos(relativeSpawn));
        chicken.moveTo(pos.x, pos.y, pos.z, 0.0F, 0.0F);
        helper.getLevel().addFreshEntity(chicken);
        return chicken;
    }

    private static IronGolem spawnHeavyTarget(GameTestHelper helper, BlockPos relativeSpawn) {
        IronGolem golem = new IronGolem(EntityType.IRON_GOLEM, helper.getLevel());
        golem.setNoAi(true);
        Vec3 pos = Vec3.atBottomCenterOf(helper.absolutePos(relativeSpawn));
        golem.moveTo(pos.x, pos.y, pos.z, 0.0F, 0.0F);
        helper.getLevel().addFreshEntity(golem);
        return golem;
    }

    private static void lookAt(ServerPlayer player, Vec3 target) {
        player.lookAt(EntityAnchorArgument.Anchor.EYES, target);
    }

    private static void throwHarpoon(GameTestHelper helper, ServerPlayer player) {
        DynamicWhipsMod.HARPOON_ITEM.get().use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
    }

    /**
     * AC1/AC2: a harpoon thrown at an eligible (living) target within range establishes a real
     * rope — {@link HarpoonState} and {@link RopeManager} both agree a rope now exists, exactly
     * what a second player's own client would see synced via rope-core's existing, unmodified
     * sync plumbing (AC10 — not independently re-tested here, same reasoning {@code HookGameTests}
     * documents for its own criterion 10).
     */
    @GameTest(template = "lifecycle", timeoutTicks = 100)
    public static void successfulHitEstablishesARope(GameTestHelper helper) {
        ServerPlayer player = spawnPlayerWithHarpoon(helper, new BlockPos(1, 1, 2));
        Chicken target = spawnLightTarget(helper, new BlockPos(3, 1, 2));
        lookAt(player, target.getEyePosition());
        throwHarpoon(helper, player);

        helper.runAfterDelay(15, () -> {
            HarpoonState.Hold hold = HarpoonState.get(player.getUUID());
            helper.assertTrue(hold != null, "throwing the harpoon at an in-range living target did not"
                    + " register a hold");
            helper.assertTrue(RopeManager.get(hold.ropeId()) != null,
                    "HarpoonState has a hold but RopeManager has no matching rope");
            helper.succeed();
        });
    }

    /** AC1's own "no dangling rope" half: a harpoon thrown with nothing in front of it never
     * creates a rope at all. */
    @GameTest(template = "lifecycle", timeoutTicks = 100)
    public static void missedThrowCreatesNoRope(GameTestHelper helper) {
        ServerPlayer player = spawnPlayerWithHarpoon(helper, new BlockPos(1, 1, 2));
        lookAt(player, player.getEyePosition().add(0, 0, 1)); // aimed at open air, nothing to hit
        throwHarpoon(helper, player);

        helper.runAfterDelay(30, () -> {
            // Not RopeManager.activeRopeCount() == 0: other HarpoonGameTests/RopeGameTests run
            // concurrently in the same CI batch and legitimately hold their own live ropes, so a
            // global count is not evidence about THIS throw. HarpoonEntity#attachRope always
            // calls HarpoonState#start in the same breath as RopeManager#attachToEntityWithPull
            // (no path creates one without the other), so the absence of a hold for this exact
            // player is the ownership-specific proof that this throw created no rope at all.
            helper.assertTrue(HarpoonState.get(player.getUUID()) == null,
                    "a harpoon thrown at nothing must never register a hold or leave a dangling rope"
                            + " behind");
            helper.succeed();
        });
    }

    /**
     * AC1's no-dangling-rope rule also covers a LETHAL hit, not only a miss: {@link
     * HarpoonEntity#onHitEntity} applies damage before checking {@code target.isAlive()}, so a
     * one-shot kill must leave neither a {@link HarpoonState} hold nor a rope behind, exactly like
     * a miss does above. Uses {@link #spawnFragileLightTarget} (vanilla 4 HP) deliberately, unlike
     * every other test here.
     */
    @GameTest(template = "lifecycle", timeoutTicks = 100)
    public static void lethalHitCreatesNoStaleRope(GameTestHelper helper) {
        ServerPlayer player = spawnPlayerWithHarpoon(helper, new BlockPos(1, 1, 2));
        Chicken target = spawnFragileLightTarget(helper, new BlockPos(3, 1, 2));
        lookAt(player, target.getEyePosition());
        throwHarpoon(helper, player);

        helper.runAfterDelay(15, () -> {
            helper.assertTrue(!target.isAlive(), "setup failed: the fragile target survived the hit, so"
                    + " this test is not exercising the lethal-hit path");
            // Same ownership-specific reasoning as missedThrowCreatesNoRope above.
            helper.assertTrue(HarpoonState.get(player.getUUID()) == null,
                    "a harpoon that kills its target on impact must never register a hold or leave a"
                            + " dangling rope behind");
            helper.succeed();
        });
    }

    /** AC3: a light target is reeled in, ending up close to the player. */
    @GameTest(template = "lifecycle", timeoutTicks = 200)
    public static void reelingDragsALightTargetToThePlayer(GameTestHelper helper) {
        ServerPlayer player = spawnPlayerWithHarpoon(helper, new BlockPos(1, 1, 2));
        Chicken target = spawnLightTarget(helper, new BlockPos(3, 1, 2));
        lookAt(player, target.getEyePosition());
        throwHarpoon(helper, player);

        helper.runAfterDelay(15, () -> {
            HarpoonState.Hold hold = HarpoonState.get(player.getUUID());
            helper.assertTrue(hold != null, "setup failed: harpoon did not attach to the light target");
            UUID ropeId = hold.ropeId();
            double startDistance = player.position().distanceTo(target.position());

            int[] tick = {0};
            helper.onEachTick(() -> {
                tick[0]++;
                HarpoonState.setInput(player.getUUID(), true, false, helper.getLevel().getGameTime());
            });

            helper.runAfterDelay(150, () -> {
                helper.assertTrue(RopeManager.get(ropeId) != null, "rope was torn down before reeling finished");
                double endDistance = player.position().distanceTo(target.position());
                helper.assertTrue(endDistance < startDistance * 0.5,
                        "a light target should have been reeled substantially closer to the player: started at "
                                + startDistance + ", ended at " + endDistance);
                helper.succeed();
            });
        });
    }

    /**
     * AC4: the SAME reel input against a heavy, grounded target must not drag it the way the
     * light target above is dragged — either the target barely moves (resist) or the player moves
     * toward it instead, but the target is never simply hauled to the player's feet.
     */
    @GameTest(template = "lifecycle", timeoutTicks = 200)
    public static void reelingDoesNotSimplyDragAHeavyGroundedTarget(GameTestHelper helper) {
        ServerPlayer player = spawnPlayerWithHarpoon(helper, new BlockPos(1, 1, 2));
        IronGolem target = spawnHeavyTarget(helper, new BlockPos(3, 1, 2));
        lookAt(player, target.getEyePosition());
        throwHarpoon(helper, player);

        helper.runAfterDelay(15, () -> {
            HarpoonState.Hold hold = HarpoonState.get(player.getUUID());
            helper.assertTrue(hold != null, "setup failed: harpoon did not attach to the heavy target");
            UUID ropeId = hold.ropeId();
            Vec3 targetStart = target.position();

            helper.onEachTick(() -> HarpoonState.setInput(player.getUUID(), true, false,
                    helper.getLevel().getGameTime()));

            helper.runAfterDelay(150, () -> {
                helper.assertTrue(RopeManager.get(ropeId) != null, "rope was torn down before reeling finished");
                double targetMoved = target.position().distanceTo(targetStart);
                helper.assertTrue(targetMoved < 1.0,
                        "a heavy, grounded target must not be simply hauled to the player — it moved "
                                + targetMoved + " blocks");
                helper.succeed();
            });
        });
    }

    /**
     * AC7, the entity-anchor-specific lifecycle case the ticket calls out by name: the target
     * dying mid-reel must not leave a dangling rope or a stale {@link HarpoonState} hold. Rope-
     * core's own generic teardown ({@code RopeAnchor.EntityAnchor#isGone} -> {@code
     * RopeManager#tickAll}) does the actual detach; this test is the HARPOON's own bookkeeping
     * half, mirroring {@code HookGameTests#breakingAnchorBlockClearsHookStateImmediately} for an
     * entity anchor instead of a broken block.
     */
    @GameTest(template = "lifecycle", timeoutTicks = 100)
    public static void targetDyingMidReelDoesNotLeakARope(GameTestHelper helper) {
        ServerPlayer player = spawnPlayerWithHarpoon(helper, new BlockPos(1, 1, 2));
        Chicken target = spawnLightTarget(helper, new BlockPos(3, 1, 2));
        lookAt(player, target.getEyePosition());
        throwHarpoon(helper, player);

        helper.runAfterDelay(15, () -> {
            HarpoonState.Hold hold = HarpoonState.get(player.getUUID());
            helper.assertTrue(hold != null, "setup failed: harpoon did not attach to the target");
            UUID ropeId = hold.ropeId();
            helper.assertTrue(RopeManager.get(ropeId) != null, "setup failed: no live rope");

            helper.onEachTick(() -> HarpoonState.setInput(player.getUUID(), true, false,
                    helper.getLevel().getGameTime()));

            helper.runAfterDelay(5, () -> {
                target.kill();

                helper.runAfterDelay(5, () -> {
                    helper.assertTrue(RopeManager.get(ropeId) == null,
                            "rope-core should have torn the rope down once its anchor entity died");
                    helper.assertTrue(HarpoonState.get(player.getUUID()) == null,
                            "the harpoon's own hold bookkeeping must be cleared once the target is dead, not left"
                                    + " pointing at a rope that no longer exists");
                    helper.succeed();
                });
            });
        });
    }
}
