package io.github.brooswitminecraft.dynamicwhips.rope;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * The non-player end of a rope: a fixed world point (whip anchor, grappling hook) or a moving
 * entity (harpoon). Sable itself only ever attaches a rope's {@code START} to a world position or
 * a sub level (docs/rope-spike.md section 1); an entity anchor is this mod's own addition, built
 * the same way the player END is pinned — read the entity's position every tick and re-issue
 * {@code setAttachment} (see {@link PlayerRope}).
 */
public sealed interface RopeAnchor {
    /** Current world position of this anchor, or null if it no longer exists (detach the rope). */
    Vec3 currentPosition(ServerLevel level);

    /** True once this anchor can no longer be resolved and the rope holding it must be dropped. */
    boolean isGone(ServerLevel level);

    record WorldPoint(Vec3 position, BlockPos anchorBlock) implements RopeAnchor {
        @Override
        public Vec3 currentPosition(ServerLevel level) {
            return position;
        }

        /**
         * Gone once the anchor block is broken (MINECRAFT-85 acceptance criterion 4). A world
         * point anchor has no other failure mode: it does not move and is not itself removed by
         * chunk unload (only the rope's own Sable-side object is, which {@link RopeManager}
         * checks separately via {@code isActive()}).
         */
        @Override
        public boolean isGone(ServerLevel level) {
            return level.getBlockState(anchorBlock).isAir();
        }
    }

    record EntityAnchor(Entity entity) implements RopeAnchor {
        @Override
        public Vec3 currentPosition(ServerLevel level) {
            return entity.getBoundingBox().getCenter();
        }

        @Override
        public boolean isGone(ServerLevel level) {
            return !entity.isAlive();
        }
    }
}
