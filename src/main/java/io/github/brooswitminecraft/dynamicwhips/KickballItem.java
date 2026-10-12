package io.github.brooswitminecraft.dynamicwhips;

import net.minecraft.world.item.Item;

/**
 * The craftable Kickball item. Its use behavior is intentionally added with the parallel
 * in-world entity foundation rather than duplicating or guessing that entity's spawn contract.
 */
public class KickballItem extends Item {
    public KickballItem(Properties properties) {
        super(properties);
    }
}
