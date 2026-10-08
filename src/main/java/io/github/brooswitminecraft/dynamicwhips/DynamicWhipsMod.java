package io.github.brooswitminecraft.dynamicwhips;

import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Entry point for Dynamic Whips (MINECRAFT-67). Currently only the Sable rope spike debug command. */
@Mod(DynamicWhipsMod.MODID)
public class DynamicWhipsMod {
    public static final String MODID = "dynamicwhips";

    public DynamicWhipsMod() {
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> RopeSpike.register(e.getDispatcher()));
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> RopeSpike.tick(e.getServer()));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> RopeSpike.clear(e.getEntity().getUUID()));
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent e) -> RopeSpike.clearAll());
    }
}
