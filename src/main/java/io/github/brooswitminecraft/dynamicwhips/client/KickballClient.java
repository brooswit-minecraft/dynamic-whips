package io.github.brooswitminecraft.dynamicwhips.client;

import io.github.brooswitminecraft.dynamicwhips.DynamicWhipsMod;
import io.github.brooswitminecraft.dynamicwhips.KickballEntity;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Client-only renderer registration for the shared-loader-backed Kickball model. */
public final class KickballClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("DynamicWhips/KickballClient");
    private static boolean smokeReported;

    private KickballClient() { }

    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(DynamicWhipsMod.KICKBALL.get(), KickballRenderer::new);
    }

    /**
     * CI-only smoke hook: the first GUI frame proves that the client reached its render loop after
     * common setup loaded the shared-loader-backed model. The property makes the hook inert during
     * normal play and lets the headless workflow stop deterministically after one frame.
     */
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (!Boolean.getBoolean("dynamicwhips.clientSmoke") || smokeReported) return;
        try {
            DynamicWhipsMod.kickballItemModel();
            smokeReported = true;
            LOGGER.info("KICKBALL_CLIENT_RENDER_SMOKE_SUCCESS model=shared-loader frame=gui");
            net.minecraft.client.Minecraft.getInstance().stop();
        } catch (IllegalStateException ignored) {
            // Common setup has not completed yet; the next frame will retry.
        }
    }
}
