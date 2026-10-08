package io.github.brooswitminecraft.dynamicwhips;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A ranged melee weapon: use (right click) cracks the whip at the first living entity along the
 * look vector within {@link WhipLogic#REACH}. Resolved on the server only and blocked by blocks.
 */
public class WhipItem extends Item {
    public WhipItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        player.getCooldowns().addCooldown(this, WhipLogic.COOLDOWN_TICKS);
        if (level.isClientSide) {
            return InteractionResultHolder.sidedSuccess(stack, true);
        }
        level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.PLAYER_ATTACK_SWEEP,
                SoundSource.PLAYERS, 1.0F, 1.4F);
        EntityHitResult hit = findTarget(level, player);
        if (hit != null && hit.getEntity() instanceof LivingEntity target) {
            double distance = player.getEyePosition().distanceTo(hit.getLocation());
            if (target.hurt(player.damageSources().playerAttack(player), WhipLogic.damageAt(distance))) {
                stack.hurtAndBreak(1, player, LivingEntity.getSlotForHand(hand));
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, false);
    }

    /** First living entity on the look ray within reach, or null if a block is hit first. */
    private static EntityHitResult findTarget(Level level, Player player) {
        Vec3 start = player.getEyePosition();
        Vec3 end = start.add(player.getLookAngle().scale(WhipLogic.REACH));
        // A block in the way shortens the ray, so nothing behind a wall can be hit.
        var blockHit = level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (blockHit.getType() != HitResult.Type.MISS) {
            end = blockHit.getLocation();
        }
        AABB sweep = player.getBoundingBox().expandTowards(end.subtract(start)).inflate(1.0);
        return ProjectileUtil.getEntityHitResult(player, start, end, sweep,
                e -> e instanceof LivingEntity && !e.isSpectator() && e.isPickable(), WhipLogic.REACH * WhipLogic.REACH);
    }
}
