package io.github.brooswitminecraft.dynamicwhips;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client-side half of {@link WhipHoldPingPayload} — see {@link WhipHoldState}'s javadoc for why
 * this exists instead of vanilla's use-item system. Only ever registered on the physical client
 * (see {@code DynamicWhipsMod}'s constructor): this class touches {@link Minecraft}, which does
 * not exist on a dedicated server.
 */
final class WhipClientInput {
    private WhipClientInput() {
    }

    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        Player player = client.player;
        if (player == null || client.options.keyUse == null || !client.options.keyUse.isDown()) {
            return;
        }
        if (isWhip(player, InteractionHand.MAIN_HAND) || isWhip(player, InteractionHand.OFF_HAND)) {
            PacketDistributor.sendToServer(new WhipHoldPingPayload());
        }
    }

    private static boolean isWhip(Player player, InteractionHand hand) {
        return player.getItemInHand(hand).getItem() instanceof WhipItem;
    }
}
