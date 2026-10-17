package io.github.brooswitminecraft.dynamicwhips;

import io.github.brooswitminecraft.dynamicwhips.rope.RopeManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * The harpoon (MINECRAFT-88): a weapon/control tool, not a traversal item (AC6) — {@link #use}
 * either throws a {@link HarpoonEntity} along the player's look direction, exactly like a vanilla
 * thrown trident, or — if this player already has an active harpoon hold from an earlier throw —
 * detaches it instead, the same "right-click again detaches" idiom {@code HookItem} already
 * established. Reel-in/pay-out of an attached rope is NOT driven from here: see
 * {@link HarpoonClientInput}/{@link HarpoonInputPayload}/{@link HarpoonState#tickAll}, the same
 * continuous-input split {@code HookItem}'s own javadoc explains.
 *
 * <p>Not consumed on throw (same non-consumable {@code stacksTo(1)} item every rope tool here is):
 * only durability is charged, once per throw, mirroring the whip's and hooks' own per-use cost.
 */
public class HarpoonItem extends Item {
    public HarpoonItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }
        if (level.isClientSide) {
            return InteractionResultHolder.sidedSuccess(stack, true);
        }

        HarpoonState.Hold existing = HarpoonState.get(serverPlayer.getUUID());
        if (existing != null) {
            RopeManager.detach(existing.ropeId());
            HarpoonState.clear(serverPlayer.getUUID());
            level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ITEM_BREAK,
                    SoundSource.PLAYERS, 0.6F, 1.6F);
            return InteractionResultHolder.sidedSuccess(stack, false);
        }

        player.getCooldowns().addCooldown(this, HarpoonLogic.COOLDOWN_TICKS);
        level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.TRIDENT_THROW,
                SoundSource.PLAYERS, 1.0F, 1.0F);

        HarpoonEntity harpoon = new HarpoonEntity(level, serverPlayer);
        harpoon.setItem(stack.copyWithCount(1));
        harpoon.shootFromRotation(serverPlayer, serverPlayer.getXRot(), serverPlayer.getYRot(), 0.0F, 2.2F, 1.0F);
        level.addFreshEntity(harpoon);
        stack.hurtAndBreak(1, player, LivingEntity.getSlotForHand(hand));

        return InteractionResultHolder.sidedSuccess(stack, false);
    }
}
