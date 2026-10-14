package io.github.brooswitminecraft.dynamicwhips.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.brooswitminecraft.dynamicwhips.DynamicWhipsMod;
import io.github.brooswitminecraft.dynamicwhips.KickballEntity;
import io.github.brooswitminecraft.magicavoxelloader.vox.MinecraftItemModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;

/** Renders converted loader elements through Minecraft's native block-model renderer. */
public final class KickballRenderer extends EntityRenderer<KickballEntity> {
    public KickballRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(KickballEntity entity, float yaw, float partialTick, PoseStack pose,
            MultiBufferSource buffers, int light) {
        MinecraftItemModel model = DynamicWhipsMod.kickballItemModel();
        pose.pushPose();
        pose.translate(-0.375D, -0.375D, -0.375D);
        pose.scale(0.75F, 0.75F, 0.75F);
        for (MinecraftItemModel.Element element : model.elements()) {
            int[] from = element.from();
            int[] to = element.to();
            pose.pushPose();
            pose.translate(from[0] / 16.0F, from[1] / 16.0F, from[2] / 16.0F);
            pose.scale((to[0] - from[0]) / 16.0F, (to[1] - from[1]) / 16.0F, (to[2] - from[2]) / 16.0F);
            Minecraft.getInstance().getBlockRenderer().renderSingleBlock(
                    Blocks.RED_CONCRETE.defaultBlockState(), pose, buffers, light, OverlayTexture.NO_OVERLAY);
            pose.popPose();
        }
        pose.popPose();
        super.render(entity, yaw, partialTick, pose, buffers, light);
    }

    @Override
    public ResourceLocation getTextureLocation(KickballEntity entity) {
        return ResourceLocation.withDefaultNamespace("textures/block/red_concrete.png");
    }
}
