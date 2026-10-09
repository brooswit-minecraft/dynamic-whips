package io.github.brooswitminecraft.dynamicwhips;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client-side half of {@link WhipHoldPingPayload}/{@link WhipReleasePayload} — see {@link
 * WhipHoldState}'s javadoc for why this exists instead of vanilla's use-item system. Only ever
 * registered on the physical client (see {@code DynamicWhipsMod}'s constructor): this class
 * touches {@link Minecraft}, which does not exist on a dedicated server.
 *
 * <p>Not covered by any GameTest: a GameTest's mock player never runs a real client tick loop, so
 * this class's own key-down/key-up detection is reasoned-about, not CI-asserted — see {@code
 * docs/whip.md} section 1's manual procedure for the real-client check.
 */
final class WhipClientInput {
    /** Whether last tick's check found the use key down with a whip in hand — the key-up EDGE
     * (true last tick, false now) is what triggers {@link WhipReleasePayload}, not merely "not
     * holding," so the payload is sent exactly once per release rather than every idle tick. */
    private static boolean wasHoldingWhip = false;

    private WhipClientInput() {
    }

    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        Player player = client.player;
        boolean holdingNow = player != null && client.options.keyUse != null && client.options.keyUse.isDown()
                && (isWhip(player, InteractionHand.MAIN_HAND) || isWhip(player, InteractionHand.OFF_HAND));
        if (holdingNow) {
            PacketDistributor.sendToServer(new WhipHoldPingPayload());
        } else if (wasHoldingWhip) {
            PacketDistributor.sendToServer(new WhipReleasePayload());
        }
        wasHoldingWhip = holdingNow;
    }

    private static boolean isWhip(Player player, InteractionHand hand) {
        return player.getItemInHand(hand).getItem() instanceof WhipItem;
    }
}
