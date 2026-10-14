package io.github.brooswitminecraft.dynamicwhips;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * The craftable Kickball item. Using it places the generic in-world Kickball entity supplied by
 * the entity foundation; gameplay such as scoring and controls remains outside this item.
 */
public class KickballItem extends Item {
    public KickballItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide) {
            KickballEntity kickball = new KickballEntity(DynamicWhipsMod.KICKBALL_ENTITY.get(), level);
            kickball.moveTo(player.getX(), player.getY() + player.getBbHeight(), player.getZ(), player.getYRot(), 0.0F);
            level.addFreshEntity(kickball);
            if (!player.getAbilities().instabuild) {
                stack.shrink(1);
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }
}
