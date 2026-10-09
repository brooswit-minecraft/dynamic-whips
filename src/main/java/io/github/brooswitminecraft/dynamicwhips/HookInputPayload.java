package io.github.brooswitminecraft.dynamicwhips;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server "this is the jump/shift state right now" heartbeat for an equipped grappling
 * hook (MINECRAFT-87 criterion 3): {@link HookClientInput} sends one every client tick a hook is
 * held, regardless of whether that player's hook is actually attached — only the server knows
 * that, and an extra ping for a player with no active hold is a cheap no-op
 * ({@link HookState#setInput}). Carries only booleans, never a length: the server is what advances
 * the rope by exactly one segment per tick of unambiguous input ({@link HookState#tickAll}), so a
 * malicious or buggy client cannot request an arbitrary length (criterion 3's own "do not trust
 * client lengths").
 */
public record HookInputPayload(boolean reelIn, boolean payOut) implements CustomPacketPayload {
    public static final Type<HookInputPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(DynamicWhipsMod.MODID, "hook_input"));

    public static final StreamCodec<RegistryFriendlyByteBuf, HookInputPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBoolean(payload.reelIn());
                buf.writeBoolean(payload.payOut());
            },
            buf -> new HookInputPayload(buf.readBoolean(), buf.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
