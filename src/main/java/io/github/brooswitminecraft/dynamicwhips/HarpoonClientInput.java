package io.github.brooswitminecraft.dynamicwhips;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client-side half of {@link HarpoonInputPayload} — see {@link HookClientInput}'s own javadoc for
 * why reel/pay-out needs a continuous per-tick payload rather than a single {@code use()} call;
 * the same reasoning applies verbatim to the harpoon once it is attached. Only ever registered on
 * the physical client.
 *
 * <p>Not covered by any GameTest, same caveat as {@code HookClientInput}: {@code HarpoonGameTests}
 * calls {@link HarpoonState#setInput} directly instead, simulating what this class's payload
 * would have caused the server to receive.
 */
final class HarpoonClientInput {
    private HarpoonClientInput() {
    }

    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        Player player = client.player;
        if (player == null) {
            return;
        }
        boolean holdingHarpoon = isHarpoon(player, InteractionHand.MAIN_HAND) || isHarpoon(player, InteractionHand.OFF_HAND);
        if (!holdingHarpoon) {
            return;
        }
        boolean reelIn = client.options.keyJump != null && client.options.keyJump.isDown();
        boolean payOut = client.options.keyShift != null && client.options.keyShift.isDown();
        PacketDistributor.sendToServer(new HarpoonInputPayload(reelIn, payOut));
    }

    private static boolean isHarpoon(Player player, InteractionHand hand) {
        return player.getItemInHand(hand).getItem() instanceof HarpoonItem;
    }
}
