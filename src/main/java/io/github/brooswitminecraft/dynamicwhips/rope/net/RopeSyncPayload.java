package io.github.brooswitminecraft.dynamicwhips.rope.net;

import java.util.UUID;

import io.github.brooswitminecraft.dynamicwhips.DynamicWhipsMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server-to-client rope points (MINECRAFT-85 acceptance criterion 3): the owning rope's id, its
 * owning player's id (so a client watching a stranger swing knows whose rope this is), and the
 * point list flattened to 3 floats per point. See docs/rope-core.md for why this shape and this
 * mod's {@link io.github.brooswitminecraft.dynamicwhips.rope.RopeConstants#SYNC_INTERVAL_TICKS}
 * update rate were chosen over, say, delta compression.
 */
public record RopeSyncPayload(UUID ropeId, UUID ownerId, float[] points) implements CustomPacketPayload {
    public static final Type<RopeSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(DynamicWhipsMod.MODID, "rope_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RopeSyncPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeUUID(payload.ropeId());
                buf.writeUUID(payload.ownerId());
                buf.writeVarInt(payload.points().length);
                for (float f : payload.points()) {
                    buf.writeFloat(f);
                }
            },
            buf -> {
                UUID ropeId = buf.readUUID();
                UUID ownerId = buf.readUUID();
                int count = buf.readVarInt();
                float[] points = new float[count];
                for (int i = 0; i < count; i++) {
                    points[i] = buf.readFloat();
                }
                return new RopeSyncPayload(ropeId, ownerId, points);
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
