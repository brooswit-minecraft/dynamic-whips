package io.github.brooswitminecraft.dynamicwhips;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * A small server-authoritative playground ball with ordinary vanilla gravity and terrain collision.
 *
 * <p>This class deliberately owns only the foundation movement lifecycle.  MINECRAFT-280 can add
 * collision-transfer through {@link #onExternalCollision(Entity)}, while MINECRAFT-281 can tune
 * settling through {@link #afterVanillaMove(Vec3)}.  Keeping those hooks separate prevents the
 * foundation from duplicating either later behavior.</p>
 */
public class KickballEntity extends Entity {
    private static final double GRAVITY_PER_TICK = 0.08D;

    public KickballEntity(EntityType<? extends KickballEntity> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        // The foundation has no persistent or synchronized gameplay state.
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        // Motion and position are persisted by Entity; later behavior owns any additional state.
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        // Motion and position are persisted by Entity; later behavior owns any additional state.
    }

    @Override
    public boolean isPickable() {
        return !isRemoved();
    }

    @Override
    public boolean isPushable() {
        return true;
    }

    @Override
    public void push(Entity collider) {
        // Entity-to-entity contact is intentionally a seam, not foundation physics: the collision
        // story owns the 2x velocity-transfer rule and must not be combined with vanilla pushing.
        onExternalCollision(collider);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) {
            return;
        }

        Vec3 motion = getDeltaMovement();
        if (!isNoGravity()) {
            motion = motion.add(0.0D, -gravityPerTick(), 0.0D);
        }
        setDeltaMovement(motion);
        move(MoverType.SELF, motion);
        afterVanillaMove(motion);
    }

    /** Gravity hook kept distinct from damping so later physics can tune it without replacing tick(). */
    protected double gravityPerTick() {
        return GRAVITY_PER_TICK;
    }

    /**
     * Extension point for a later collision-transfer implementation.  This foundation never
     * changes ball motion when another entity contacts it.
     */
    protected void onExternalCollision(Entity collider) {
    }

    /** Extension point for later damping/bounce settling after vanilla collision resolution. */
    protected void afterVanillaMove(Vec3 attemptedMotion) {
        if (onGround()) {
            setDeltaMovement(applyGroundResponse(getDeltaMovement(), attemptedMotion));
        }
    }

    /**
     * Applies terrain response after {@link #move(MoverType, Vec3)} has resolved a collision.
     * Keeping this calculation independent of the entity makes the settling contract directly
     * testable without replacing the game's collision resolver.
     */
    static Vec3 applyGroundResponse(Vec3 motion, Vec3 attemptedMotion) {
        KickballPhysics.Motion result = KickballPhysics.applyGroundResponse(
                new KickballPhysics.Motion(motion.x, motion.y, motion.z),
                new KickballPhysics.Motion(attemptedMotion.x, attemptedMotion.y, attemptedMotion.z));
        return new Vec3(result.x(), result.y(), result.z());
    }
}
