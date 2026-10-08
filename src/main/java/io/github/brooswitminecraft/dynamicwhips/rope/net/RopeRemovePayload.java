package io.github.brooswitminecraft.dynamicwhips.rope.net;

import java.util.UUID;

import io.github.brooswitminecraft.dynamicwhips.DynamicWhipsMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Tells clients a rope is gone (detached, owner left, anchor broken) so they stop rendering it. */
public record RopeRemovePayload(UUID ropeId) implements CustomPacketPayload {
    public static final Type<RopeRemovePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(DynamicWhipsMod.MODID, "rope_remove"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RopeRemovePayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> buf.writeUUID(payload.ropeId()),
            buf -> new RopeRemovePayload(buf.readUUID()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
