package io.github.brooswitminecraft.dynamicwhips;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Headless coverage for the generic, non-player entity collision transfer seam. */
@GameTestHolder(DynamicWhipsMod.MODID)
@PrefixGameTestTemplate(false)
public final class KickballGameTests {
    private static final double EPSILON = 1.0E-6D;

    private KickballGameTests() {
    }

    @GameTest(template = "lifecycle", timeoutTicks = 20)
    public static void movingEntityVelocityScalesAndDoesNotRepeat(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        KickballEntity slowBall = newBall(level, 2.0D, 2.0D, 2.0D);
        ItemEntity slowCollider = newCollider(level, 2.0D, 2.0D, 2.0D, 0.1D);
        slowBall.push(slowCollider);
        slowBall.push(slowCollider);
        helper.assertTrue(Math.abs(slowBall.getDeltaMovement().x - 0.2D) < EPSILON,
                "slow non-player contact must transfer exactly 2x once, got " + slowBall.getDeltaMovement());

        KickballEntity fastBall = newBall(level, 4.0D, 2.0D, 2.0D);
        ItemEntity fastCollider = newCollider(level, 4.0D, 2.0D, 2.0D, 0.4D);
        fastBall.push(fastCollider);
        helper.assertTrue(Math.abs(fastBall.getDeltaMovement().x - 0.8D) < EPSILON,
                "faster non-player contact must scale proportionally, got " + fastBall.getDeltaMovement());
        helper.succeed();
    }

    private static KickballEntity newBall(ServerLevel level, double x, double y, double z) {
        KickballEntity ball = new KickballEntity(DynamicWhipsMod.KICKBALL.get(), level);
        ball.setPos(x, y, z);
        level.addFreshEntity(ball);
        return ball;
    }

    private static ItemEntity newCollider(ServerLevel level, double x, double y, double z,
            double speed) {
        ItemEntity collider = new ItemEntity(level, x, y, z, new ItemStack(Items.STONE));
        collider.setDeltaMovement(new Vec3(speed, 0.0D, 0.0D));
        level.addFreshEntity(collider);
        return collider;
    }
}
