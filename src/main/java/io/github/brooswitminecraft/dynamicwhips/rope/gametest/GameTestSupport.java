package io.github.brooswitminecraft.dynamicwhips.rope.gametest;

import com.mojang.authlib.GameProfile;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import io.netty.channel.embedded.EmbeddedChannel;

/**
 * Mock-player helpers shared by every GameTest in this mod (rope-core's {@code RopeGameTests} and
 * the whip's own {@code WhipGameTests}) — extracted from {@code RopeGameTests} (MINECRAFT-85) when
 * MINECRAFT-86 needed the identical setup for the Leather Whip's own tests, rather than duplicate
 * it a second time. See {@link #spawnMockPlayer} for why this exact recipe is needed instead of
 * either of {@code GameTestHelper}'s own mock-player methods.
 */
public final class GameTestSupport {
    private GameTestSupport() {
    }

    /**
     * Builds a real, logged-in {@link ServerPlayer} by replicating {@code
     * GameTestHelper#makeMockServerPlayerInLevel()}'s own recipe by hand (profile, a mock {@link
     * Connection} backed by a Netty {@link EmbeddedChannel}, {@code PlayerList#placeNewPlayer})
     * with one addition: {@link NetworkRegistry#configureMockConnection} marks that connection as
     * fully NeoForge-compatible BEFORE {@code placeNewPlayer} fires any join listeners. Three other
     * approaches were tried first and ruled out — see {@code docs/rope-core.md}'s CI history for
     * the full chain: the vanilla helper method itself (its connection is never marked compatible,
     * so a mod's own join broadcast — Sable's included — gets refused and crashes the test);
     * {@code GameTestHelper#makeMockPlayer(GameType)} (its return type is not actually a
     * {@code ServerPlayer}, despite appearances); and a hand-built {@code ServerPlayer} added via
     * {@code ServerLevel#addFreshEntity} with no connection at all (vanilla's own chunk-tracking
     * code assumes every player in a level has one and crashes the whole server, not just one
     * test, the moment it ticks).
     */
    public static ServerPlayer spawnMockPlayer(GameTestHelper helper, BlockPos relativeSpawn) {
        return spawnMockPlayerAtAbsolute(helper, Vec3.atBottomCenterOf(helper.absolutePos(relativeSpawn)));
    }

    /**
     * As {@link #spawnMockPlayer}, but at an explicit ABSOLUTE {@link BlockPos} instead of one
     * relative to the test's own structure — used by RopeGameTests' near-origin probes to place a
     * player far from wherever the GameTest framework landed the structure (MINECRAFT-127).
     */
    public static ServerPlayer spawnMockPlayerAtAbsolute(GameTestHelper helper, BlockPos absoluteSpawn) {
        return spawnMockPlayerAtAbsolute(helper, Vec3.atBottomCenterOf(absoluteSpawn));
    }

    public static ServerPlayer spawnMockPlayerAtAbsolute(GameTestHelper helper, Vec3 spawn) {
        GameProfile profile = new GameProfile(UUID.randomUUID(), "test-mock-player");
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
        ServerPlayer player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), profile,
                ClientInformation.createDefault());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        // The one addition over the vanilla recipe: mark this mock connection as fully
        // NeoForge-compatible BEFORE placeNewPlayer below fires any join listeners. Without this,
        // any mod (Sable included) that broadcasts data to a newly joined player on a channel this
        // connection never negotiated (it skipped the real client handshake entirely) gets
        // refused by NetworkRegistry's checkPacket and crashes the test.
        NetworkRegistry.configureMockConnection(connection);
        helper.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);

        player.moveTo(spawn.x, spawn.y, spawn.z, player.getYRot(), player.getXRot());
        player.setDeltaMovement(Vec3.ZERO);
        return player;
    }

    /**
     * Drives free fall on {@code player} every tick for the rest of this test. A real client
     * normally sends the movement packets that make a player fall at all — the server does not
     * independently simulate player physics the way it does for mob AI — so without this, a mock
     * player built by {@link #spawnMockPlayer} never moves even one block in any number of ticks.
     * Vanilla's own gravity constant and terminal velocity, since nothing else in this mod needs
     * those named separately.
     */
    public static void simulateGravityEachTick(GameTestHelper helper, ServerPlayer player) {
        helper.onEachTick(() -> {
            if (!player.isAlive()) {
                return;
            }
            Vec3 velocity = player.getDeltaMovement();
            double fallSpeed = Math.max(velocity.y - 0.08, -3.92);
            player.setDeltaMovement(velocity.x, fallSpeed, velocity.z);
            player.move(MoverType.SELF, player.getDeltaMovement());
        });
    }
}
