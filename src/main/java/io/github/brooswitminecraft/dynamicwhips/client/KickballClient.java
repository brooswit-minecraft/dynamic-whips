package io.github.brooswitminecraft.dynamicwhips.client;

import io.github.brooswitminecraft.dynamicwhips.DynamicWhipsMod;
import io.github.brooswitminecraft.dynamicwhips.KickballEntity;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** Client-only renderer registration for the shared-loader-backed Kickball model. */
public final class KickballClient {
    private KickballClient() { }

    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(DynamicWhipsMod.KICKBALL.get(), KickballRenderer::new);
    }
}
