package io.github.brooswitminecraft.dynamicwhips;

import java.util.UUID;

import io.github.brooswitminecraft.dynamicwhips.rope.RopeManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * One grappling hook tier (MINECRAFT-87 criterion 2): {@link #use} either anchors to the block the
 * player is looking at within this tier's own {@link HookLogic.Tier#maxLength()} — a block clip,
 * exactly like {@code WhipItem}'s own reach check, but with no entity branch at all, since the
 * spec draws no combat use for a hook — or, if this player already has a hold from an earlier
 * {@code use()}, detaches it instead (right-click again detaches, per the spec). Unlike
 * {@code WhipItem}, nothing here is held-to-keep: once attached, the rope survives this item being
 * put away entirely (see {@link HookState}'s javadoc) until it is explicitly detached or a
 * rope-core lifecycle event tears it down.
 *
 * <p>Reel-in/pay-out (criterion 3) is NOT driven from here: a real client's jump/shift state is
 * only readable continuously via {@link HookClientInput}'s per-tick {@link HookInputPayload},
 * applied server-side by {@link HookState#tickAll} — the same "a GameTest can't send a real key
 * event" split {@code WhipItem}/{@code WhipHoldState}/{@code WhipClientInput} already uses (see
 * docs/whip.md section 1).
 */
public class HookItem extends Item {
    private final HookLogic.Tier tier;

    public HookItem(HookLogic.Tier tier, Properties properties) {
        super(properties);
        this.tier = tier;
    }

    public HookLogic.Tier tier() {
        return tier;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            return InteractionResultHolder.sidedSuccess(stack, true);
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResultHolder.sidedSuccess(stack, false);
        }

        HookState.Hold existing = HookState.get(serverPlayer.getUUID());
        if (existing != null) {
            RopeManager.detach(existing.ropeId());
            HookState.clear(serverPlayer.getUUID());
            level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ITEM_BREAK,
                    SoundSource.PLAYERS, 0.6F, 1.6F);
            return InteractionResultHolder.sidedSuccess(stack, false);
        }

        BlockHitResult hit = findBlockHit(level, player, tier.maxLength());
        if (hit.getType() == HitResult.Type.BLOCK) {
            UUID ropeId = RopeManager.attachToPoint(serverPlayer, hit.getLocation(), hit.getBlockPos(),
                    HookLogic.ANCHOR_SLACK);
            if (ropeId != null) {
                HookState.start(serverPlayer.getUUID(), ropeId, tier, hand);
                level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.FISHING_BOBBER_THROW,
                        SoundSource.PLAYERS, 1.0F, 0.8F);
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, false);
    }

    /** The first block the look ray hits within {@code reach} — no entity branch (the spec gives
     * the hook no combat role; blocks are the only valid anchor). */
    private static BlockHitResult findBlockHit(Level level, Player player, double reach) {
        Vec3 start = player.getEyePosition();
        Vec3 end = start.add(player.getLookAngle().scale(reach));
        return level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
    }
}
