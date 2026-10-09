package io.github.brooswitminecraft.dynamicwhips;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server "I am still holding the whip's use input" heartbeat. See {@link
 * WhipHoldState}'s javadoc for why this exists instead of vanilla's use-item system — in short,
 * {@code LocalPlayer#aiStep} scales movement input to 20% unconditionally while
 * {@code isUsingItem()} is true, with no NeoForge opt-out, which would fight criteria 3/4's
 * "ordinary air control."
 *
 * <p>Sent every client tick the whip's use key is held with a whip in hand ({@code
 * WhipClientInput}), regardless of whether the client itself knows an anchor is live — only the
 * server knows that, and an extra ping for a player with no active hold is a cheap no-op
 * ({@link WhipHoldState#ping}). Empty payload: the sender (the connection's own player) and the
 * current tick (read server-side) are all the server needs.
 */
public record WhipHoldPingPayload() implements CustomPacketPayload {
    public static final Type<WhipHoldPingPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(DynamicWhipsMod.MODID, "whip_hold_ping"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WhipHoldPingPayload> STREAM_CODEC =
            StreamCodec.unit(new WhipHoldPingPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
