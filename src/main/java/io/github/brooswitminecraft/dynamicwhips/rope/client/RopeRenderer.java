package io.github.brooswitminecraft.dynamicwhips.rope.client;

import java.util.UUID;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Draws every rope this client knows about as a line strip through its (interpolated) points.
 * MINECRAFT-85 acceptance criterion 3: the renderer reads purely from {@link ClientRopeState}, so
 * a second player watching someone else swing sees the same line the swinging player's own
 * client would draw for itself, built from the identical sync packets.
 */
public final class RopeRenderer {
    private static final int COLOR_R = 235, COLOR_G = 224, COLOR_B = 190, COLOR_A = 255;

    private RopeRenderer() {
    }

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        PoseStack poseStack = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        Matrix4f matrix = poseStack.last().pose();

        MultiBufferSource.BufferSource bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();
        VertexConsumer consumer = bufferSource.getBuffer(RenderType.lines());
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        for (UUID id : ClientRopeState.activeRopeIds()) {
            float[] points = ClientRopeState.interpolatedPoints(id, partialTick);
            if (points == null) {
                continue;
            }
            drawRope(consumer, matrix, points);
        }
        bufferSource.endBatch(RenderType.lines());

        poseStack.popPose();
    }

    private static void drawRope(VertexConsumer consumer, Matrix4f matrix, float[] points) {
        int count = points.length / 3;
        for (int i = 0; i + 1 < count; i++) {
            float x1 = points[i * 3], y1 = points[i * 3 + 1], z1 = points[i * 3 + 2];
            float x2 = points[(i + 1) * 3], y2 = points[(i + 1) * 3 + 1], z2 = points[(i + 1) * 3 + 2];
            float nx = x2 - x1, ny = y2 - y1, nz = z2 - z1;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len > 1.0e-5f) {
                nx /= len;
                ny /= len;
                nz /= len;
            }
            consumer.addVertex(matrix, x1, y1, z1).setColor(COLOR_R, COLOR_G, COLOR_B, COLOR_A)
                    .setNormal(nx, ny, nz);
            consumer.addVertex(matrix, x2, y2, z2).setColor(COLOR_R, COLOR_G, COLOR_B, COLOR_A)
                    .setNormal(nx, ny, nz);
        }
    }
}
