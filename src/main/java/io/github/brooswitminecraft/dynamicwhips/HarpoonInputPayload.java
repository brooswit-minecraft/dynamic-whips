package io.github.brooswitminecraft.dynamicwhips;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -> server "this is the jump/shift state right now" heartbeat for an equipped, currently
 * attached harpoon (MINECRAFT-88) — the exact same role {@link HookInputPayload} plays for a
 * grappling hook, duplicated rather than shared for the same reason {@code HookInputPayload}
 * itself is its own type and not a generic one: each item's own client/server pair stays a single,
 * self-contained story to read.
 */
public record HarpoonInputPayload(boolean reelIn, boolean payOut) implements CustomPacketPayload {
    public static final Type<HarpoonInputPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(DynamicWhipsMod.MODID, "harpoon_input"));

    public static final StreamCodec<RegistryFriendlyByteBuf, HarpoonInputPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBoolean(payload.reelIn());
                buf.writeBoolean(payload.payOut());
            },
            buf -> new HarpoonInputPayload(buf.readBoolean(), buf.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
