package io.github.brooswitminecraft.dynamicwhips;

import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Entry point and registry for Dynamic Whips (MINECRAFT-67): the Leather Whip plus the Sable rope spike debug command. */
@Mod(DynamicWhipsMod.MODID)
public class DynamicWhipsMod {
    public static final String MODID = "dynamicwhips";

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);

    public static final DeferredItem<WhipItem> LEATHER_WHIP = ITEMS.register("leather_whip",
            () -> new WhipItem(new Item.Properties().stacksTo(1).durability(WhipLogic.DURABILITY)));

    public DynamicWhipsMod(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
        modEventBus.addListener(this::addCreative);
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> RopeSpike.register(e.getDispatcher()));
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> RopeSpike.tick(e.getServer()));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> RopeSpike.clear(e.getEntity().getUUID()));
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent e) -> RopeSpike.clearAll());
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.COMBAT) {
            event.accept(LEATHER_WHIP);
        }
    }
}
