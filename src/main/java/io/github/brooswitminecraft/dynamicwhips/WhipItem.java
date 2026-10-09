package io.github.brooswitminecraft.dynamicwhips;

import java.util.UUID;

import io.github.brooswitminecraft.dynamicwhips.rope.RopeManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A ranged melee weapon (MINECRAFT-68, unchanged) AND, as of MINECRAFT-86, a hold-to-keep rope
 * anchor: {@link #use} cracks the whip along the look vector within {@link WhipLogic#REACH},
 * clipped against blocks first — the SAME clip the shipped combat half already did; this class
 * only adds a branch on its result, it never duplicates the clip itself (see {@link #findHit}).
 *
 * <ul>
 *   <li>First a living entity on the ray: the exact, unchanged instant combat swing (reach,
 *       damage curve and cooldown are untouched — {@code WhipLogicTest} still pins them).</li>
 *   <li>Otherwise, a block the clip actually stopped on: that point becomes a temporary rope
 *       anchor ({@link RopeManager#attachToPoint}) that lasts only while the player keeps holding
 *       the input. {@link #onStopUsing} tears it down the instant the hold ends, for any reason.</li>
 * </ul>
 *
 * <p><strong>Why {@code startUsingItem} / {@code onUseTick} / {@code onStopUsing}, not a custom
 * per-tick input poll:</strong> a short click and a held click look identical at the moment
 * {@link #use} fires. Minecraft's own input model distinguishes them only through this exact hook
 * family: {@link #getUseDuration} large enough to never expire on its own while held, {@link
 * #getUseAnimation} for the held pose, {@link #onUseTick} firing every server tick while held, and
 * {@link #onStopUsing} firing once the hold ends for ANY reason — the vanilla release packet,
 * switching hotbar slots, or dropping the item (see {@code LivingEntity#stopUsingItem} /
 * {@code #updatingUsingItem}: switching slots or a stack mismatch calls {@code stopUsingItem()}
 * directly, never going through {@code releaseUsingItem()} at all). Vanilla's own {@code
 * Item#releaseUsing} fires ONLY on the explicit release packet, so it would miss both of those —
 * this class detaches from NeoForge's {@code IItemExtension#onStopUsing} instead, which {@code
 * stopUsingItem()} calls unconditionally on every one of those paths.</p>
 *
 * <p><strong>Chain-crack / cooldown rule (criterion 2):</strong> the cooldown is applied
 * unconditionally at the very top of {@link #use}, exactly where the shipped combat swing already
 * applied it — covering the entity hit, the block anchor, and a total miss identically, exactly as
 * before this story. Since the game itself never calls {@link #use} again while the item is on
 * cooldown, attach can never happen more often than once per cooldown no matter how quickly a
 * player attaches and releases — holding the anchor open costs nothing extra, but re-cracking it
 * does not bypass {@link WhipLogic#COOLDOWN_TICKS} either. Durability is charged once per
 * successful anchor (mirroring the existing per-hit durability cost on the entity branch), not
 * once per tick held.</p>
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

        HitResult hit = findHit(level, player);
        if (hit instanceof EntityHitResult entityHit && entityHit.getEntity() instanceof LivingEntity target) {
            double distance = player.getEyePosition().distanceTo(entityHit.getLocation());
            if (target.hurt(player.damageSources().playerAttack(player), WhipLogic.damageAt(distance))) {
                stack.hurtAndBreak(1, player, LivingEntity.getSlotForHand(hand));
            }
            return InteractionResultHolder.sidedSuccess(stack, false);
        }

        if (hit instanceof BlockHitResult blockHit && blockHit.getType() == HitResult.Type.BLOCK
                && player instanceof ServerPlayer serverPlayer) {
            UUID ropeId = RopeManager.attachToPoint(serverPlayer, blockHit.getLocation(), blockHit.getBlockPos(),
                    WhipLogic.ANCHOR_SLACK);
            if (ropeId != null) {
                stack.hurtAndBreak(1, player, LivingEntity.getSlotForHand(hand));
                WhipHoldState.start(player.getUUID(), ropeId, hand);
                player.startUsingItem(hand);
            }
            return InteractionResultHolder.sidedSuccess(stack, false);
        }

        return InteractionResultHolder.sidedSuccess(stack, false);
    }

    @Override
    public void onUseTick(Level level, LivingEntity livingEntity, ItemStack stack, int remainingUseDuration) {
        if (level.isClientSide || !(livingEntity instanceof ServerPlayer player)) {
            return;
        }
        WhipHoldState.Hold hold = WhipHoldState.get(player.getUUID());
        // The rope core (RopeManager#tickAll) can tear the rope down out from under a still-held
        // whip with no detach call from this class at all — anchor block broken, chunk unload,
        // server stop (docs/rope-core.md section 4). Noticing that here lets go of the item the
        // next tick instead of leaving the player visibly "holding" an anchor that no longer
        // exists until they happen to release on their own.
        if (hold != null && RopeManager.get(hold.ropeId()) == null) {
            player.stopUsingItem();
        }
    }

    @Override
    public void onStopUsing(ItemStack stack, LivingEntity livingEntity, int count) {
        if (livingEntity.level().isClientSide || !(livingEntity instanceof ServerPlayer player)) {
            return;
        }
        WhipHoldState.Hold hold = WhipHoldState.end(player.getUUID());
        if (hold != null) {
            RopeManager.detach(hold.ropeId());
        }
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        // Effectively unbounded — vanilla's own convention for a hold-until-release item (bow,
        // trident both use 72000). The hold must last exactly as long as the player holds the
        // input; onStopUsing, not this duration running out, is what ends it.
        return 72000;
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.SPEAR;
    }

    /**
     * The first living entity OR the first block on the look ray within {@link WhipLogic#REACH}
     * — the same block-vs-entity clip the original combat-only version used (a block in the way
     * shortens the ray, so nothing behind a wall can be hit), now also returning the block hit
     * itself instead of discarding it.
     */
    private static HitResult findHit(Level level, Player player) {
        Vec3 start = player.getEyePosition();
        Vec3 end = start.add(player.getLookAngle().scale(WhipLogic.REACH));
        BlockHitResult blockHit = level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        Vec3 clippedEnd = blockHit.getType() != HitResult.Type.MISS ? blockHit.getLocation() : end;
        AABB sweep = player.getBoundingBox().expandTowards(clippedEnd.subtract(start)).inflate(1.0);
        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(player, start, clippedEnd, sweep,
                e -> e instanceof LivingEntity && !e.isSpectator() && e.isPickable(), WhipLogic.REACH * WhipLogic.REACH);
        return entityHit != null ? entityHit : blockHit;
    }
}
