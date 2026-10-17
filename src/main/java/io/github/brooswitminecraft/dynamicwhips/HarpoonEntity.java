package io.github.brooswitminecraft.dynamicwhips;

import java.util.UUID;

import io.github.brooswitminecraft.dynamicwhips.rope.RopeManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;

/**
 * The thrown harpoon (MINECRAFT-88, AC1): an ordinary vanilla {@code ThrowableItemProjectile} —
 * the same base class/idiom vanilla's own snowball/egg/trident use; nothing in this repo had a
 * throwable-item entity before this story ({@code KickballEntity} is a placed, server-ticked ball
 * with no throw arc at all, a different pattern entirely).
 *
 * <p><strong>Eligibility and the no-dangling-rope rule (AC1):</strong> only {@link #onHitEntity}
 * ever creates a rope, and only when ALL of the following hold: the thrower is still a connected
 * {@code ServerPlayer}, the struck entity is a living, non-spectator, pickable target, and the
 * straight-line distance from thrower to target at the moment of impact is within {@link
 * HarpoonLogic#RANGE}. {@link #onHitBlock} never attaches anything at all — a harpoon that
 * strikes terrain, or that flies far enough past {@link HarpoonLogic#RANGE} that the hit would be
 * outside the weapon's own effective range, simply discards itself, exactly like a vanilla thrown
 * trident sticking in a wall: no dangling rope is ever created for a failed or out-of-range throw.
 */
public class HarpoonEntity extends ThrowableItemProjectile {
    public HarpoonEntity(EntityType<? extends HarpoonEntity> type, Level level) {
        super(type, level);
    }

    public HarpoonEntity(Level level, LivingEntity shooter) {
        super(DynamicWhipsMod.HARPOON_ENTITY.get(), shooter, level);
    }

    @Override
    protected Item getDefaultItem() {
        return DynamicWhipsMod.HARPOON_ITEM.get();
    }

    @Override
    protected void onHitEntity(EntityHitResult result) {
        super.onHitEntity(result);
        if (level().isClientSide) {
            return;
        }
        Entity hitEntity = result.getEntity();
        Entity owner = getOwner();
        if (!(owner instanceof ServerPlayer player) || !player.isAlive()) {
            discard();
            return;
        }
        if (hitEntity instanceof LivingEntity target && target.isAlive() && !target.isSpectator()
                && target.isPickable()) {
            double distance = player.position().distanceTo(target.getBoundingBox().getCenter());
            target.hurt(player.damageSources().thrown(this, player), HarpoonLogic.DAMAGE);
            if (target.isAlive() && distance <= HarpoonLogic.RANGE) {
                attachRope(player, target);
            }
        }
        discard();
    }

    @Override
    protected void onHitBlock(BlockHitResult result) {
        super.onHitBlock(result);
        if (!level().isClientSide) {
            // AC1: a harpoon that strikes terrain instead of a living target never attaches a
            // rope — it just falls/despawns like any other thrown item, matching a vanilla thrown
            // trident sticking into a block it cannot rope.
            discard();
        }
    }

    /**
     * AC2/AC3/AC4: establishes the physical rope connection and, via {@link
     * RopeManager#attachToEntityWithPull}, the mass-vs-pull-direction rule's own share of the
     * rope's taut correction — derived purely from the target's own bounding-box volume (a real,
     * per-entity physical quantity, never a hardcoded per-mob list) and its current grounded
     * state, exactly as {@link HarpoonLogic#pullBalance}'s own javadoc documents.
     */
    private void attachRope(ServerPlayer player, LivingEntity target) {
        double volume = target.getBbWidth() * target.getBbWidth() * target.getBbHeight();
        double balance = HarpoonLogic.pullBalance(volume, target.onGround());
        double yieldFraction = HarpoonLogic.targetYieldFraction(balance);
        UUID ropeId = RopeManager.attachToEntityWithPull(player, target, HarpoonLogic.ANCHOR_SLACK, yieldFraction);
        if (ropeId != null) {
            HarpoonState.start(player.getUUID(), ropeId);
        }
    }
}
