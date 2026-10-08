package io.github.brooswitminecraft.dynamicwhips.rope.net;

import io.github.brooswitminecraft.dynamicwhips.rope.PlayerRope;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/** Server-side dispatch for the rope payloads. Kept separate from {@link PlayerRope} so that class stays Sable-only. */
public final class RopeNetworking {
    private RopeNetworking() {
    }

    /**
     * Sends this rope's current points to every player tracking {@code owner} and to {@code owner}
     * themselves, so a second player watching someone else swing sees the same points the first
     * player's own client would (MINECRAFT-85 acceptance criterion 3).
     */
    public static void sendSync(PlayerRope rope, ServerPlayer owner) {
        RopeSyncPayload payload = new RopeSyncPayload(rope.id(), rope.ownerId(), rope.pointsAsFloats());
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(owner, payload);
    }

    public static void sendRemove(PlayerRope rope) {
        RopeRemovePayload payload = new RopeRemovePayload(rope.id());
        PacketDistributor.sendToAllPlayers(payload);
    }
}
