package io.github.brooswitminecraft.dynamicwhips;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client-side half of {@link HookInputPayload} — see {@link HookState}'s javadoc for why reel/
 * pay-out needs its own continuous per-tick payload rather than a single {@code use()} call: jump
 * and shift are held, not clicked, and only a real client can read their live state. Only ever
 * registered on the physical client (see {@code DynamicWhipsMod}'s constructor): this class
 * touches {@link Minecraft}, which does not exist on a dedicated server.
 *
 * <p>Not covered by any GameTest, same caveat as {@code WhipClientInput}: a GameTest's mock player
 * never runs a real client tick loop, so this class's own key-state reads are reasoned-about, not
 * CI-asserted. {@code HookGameTests} calls {@link HookState#setInput} directly instead, simulating
 * what this class's payload would have caused the server to receive.
 */
final class HookClientInput {
    private HookClientInput() {
    }

    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        Player player = client.player;
        if (player == null) {
            return;
        }
        boolean holdingHook = isHook(player, InteractionHand.MAIN_HAND) || isHook(player, InteractionHand.OFF_HAND);
        if (!holdingHook) {
            return;
        }
        boolean reelIn = client.options.keyJump != null && client.options.keyJump.isDown();
        boolean payOut = client.options.keyShift != null && client.options.keyShift.isDown();
        PacketDistributor.sendToServer(new HookInputPayload(reelIn, payOut));
    }

    private static boolean isHook(Player player, InteractionHand hand) {
        return player.getItemInHand(hand).getItem() instanceof HookItem;
    }
}
