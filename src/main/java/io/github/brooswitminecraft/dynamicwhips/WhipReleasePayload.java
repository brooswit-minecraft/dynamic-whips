package io.github.brooswitminecraft.dynamicwhips;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server "the use key just went up" — the immediate, explicit half of releasing a whip
 * anchor (see {@link WhipHoldState}'s javadoc). Sent once, on the key-up edge, by {@code
 * WhipClientInput}; the server detaches synchronously on receipt ({@link
 * WhipHoldState#releaseNow}) rather than waiting for {@link WhipHoldPingPayload}'s absence to
 * time out — a pure timeout would let the rope keep constraining the player for up to {@link
 * WhipHoldState#TIMEOUT_TICKS} past the moment of release, well past the apex of a swing.
 */
public record WhipReleasePayload() implements CustomPacketPayload {
    public static final Type<WhipReleasePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(DynamicWhipsMod.MODID, "whip_release"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WhipReleasePayload> STREAM_CODEC =
            StreamCodec.unit(new WhipReleasePayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
