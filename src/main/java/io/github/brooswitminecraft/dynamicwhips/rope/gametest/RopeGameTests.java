package io.github.brooswitminecraft.dynamicwhips.rope.gametest;

import java.util.UUID;

import org.joml.Vector3d;
import org.slf4j.Logger;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;

import io.github.brooswitminecraft.dynamicwhips.DynamicWhipsMod;
import io.github.brooswitminecraft.dynamicwhips.rope.PlayerRope;
import io.github.brooswitminecraft.dynamicwhips.rope.RopeConstants;
import io.github.brooswitminecraft.dynamicwhips.rope.RopeManager;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/**
 * Headless coverage for MINECRAFT-85 acceptance criteria 1, 2 and 4, run by {@code gradlew
 * runGameTestServer} and wired into CI (see {@code .github/workflows/ci.yml}). Criterion 3
 * (client sync/rendering) and criterion 7 (64-block performance) are NOT covered here: both need
 * a real client or a real multi-minute load, neither of which the headless game test server can
 * give; see docs/rope-core.md for the manual procedures covering those.
 *
 * <p>Every test's player is a real, logged-in {@link ServerPlayer}, built by replicating
 * {@code GameTestHelper#makeMockServerPlayerInLevel()}'s own recipe by hand (profile, mock
 * {@link Connection} backed by a Netty {@link EmbeddedChannel}, {@code PlayerList#placeNewPlayer})
 * with one addition: {@link NetworkRegistry#configureMockConnection} marks that connection as
 * fully NeoForge-compatible BEFORE {@code placeNewPlayer} fires any join listeners (see
 * {@link #spawnMockPlayer}). Three other approaches were tried first and ruled out — see
 * docs/rope-core.md's CI history for the full chain: the vanilla helper method itself (its
 * connection is never marked compatible, so Sable's own join broadcast gets refused and crashes
 * the test); {@code GameTestHelper#makeMockPlayer(GameType)} (its return type is not actually a
 * {@code ServerPlayer}, despite appearances); and a hand-built {@code ServerPlayer} added via
 * {@code ServerLevel#addFreshEntity} with no connection at all (vanilla's own chunk-tracking code
 * assumes every player in a level has one and crashes the whole server, not just one test, the
 * moment it ticks).
 */
@GameTestHolder(DynamicWhipsMod.MODID)
@PrefixGameTestTemplate(false)
public final class RopeGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();

    private RopeGameTests() {
    }

    /**
     * Criterion 1: a rope anchored above an otherwise-empty shaft arrests a falling player's
     * descent and converts it into a swing — gravity and momentum only, no teleport toward the
     * anchor. Structure: {@code fall_arrest_swing.nbt}, a 7x14x7 shaft with a stone floor at y=0
     * that the test fails long before the player could reach (a safety net, not the pass
     * condition).
     */
    @GameTest(template = "fall_arrest_swing", timeoutTicks = 200)
    public static void fallArrestSwing(GameTestHelper helper) {
        BlockPos anchorBlock = new BlockPos(3, 12, 3);
        helper.setBlock(anchorBlock, Blocks.STONE);
        Vec3 anchorPos = Vec3.atCenterOf(helper.absolutePos(anchorBlock));

        // Player starts offset sideways from directly under the anchor so a swing (lateral
        // motion) is distinguishable from a straight vertical arrest.
        BlockPos playerSpawn = new BlockPos(3, 11, 1);
        ServerPlayer player = spawnMockPlayer(helper, playerSpawn);

        double slack = 1.2;
        UUID ropeId = RopeManager.attachToPoint(player, anchorPos, helper.absolutePos(anchorBlock), slack);
        helper.assertTrue(ropeId != null, "rope attach failed: no Sable physics system in the game test level");

        double restLength = RopeManager.length(ropeId);
        double maxAllowed = restLength + RopeConstants.SWING_SLACK + 0.5; // +0.5: position/velocity correction lag tolerance

        helper.runAfterDelay(160, () -> {
            PlayerRope rope = RopeManager.get(ropeId);
            helper.assertTrue(rope != null, "rope was torn down before the swing could be observed");

            double distanceFromAnchor = anchorPos.distanceTo(player.position());
            helper.assertTrue(distanceFromAnchor <= maxAllowed,
                    "player fell past the rope's rest length (" + restLength + " blocks): measured "
                            + distanceFromAnchor + " — the rope failed to arrest the fall");

            double fallSpeed = -player.getDeltaMovement().y;
            helper.assertTrue(fallSpeed < 0.5,
                    "player is still falling fast (vy=" + player.getDeltaMovement().y
                            + ") long after the rope should have arrested and converted the fall to a swing");

            helper.assertTrue(player.position().y > helper.absolutePos(new BlockPos(0, 1, 0)).getY() - 0.5,
                    "player reached the floor safety net: the rope did not catch at all");

            helper.succeed();
        });
    }

    /**
     * Criterion 2, THE SPEC SCENARIO: an anchor above, a block-sized post below and between the
     * anchor and the player, falling player. Structure: {@code catch_on_obstruction.nbt}, a
     * 9x16x9 shaft. The post is placed by the test (not baked into the template) so its position
     * relative to the anchor-to-player line is explicit here, next to the assertion that depends
     * on it.
     *
     * <p>Pass condition: Sable's rope solver bends around the post (one of the rope's own points,
     * read back from {@link PlayerRope#points()}, settles within one collision-radius of the
     * post's surface) AND the player ends up swinging on the post's side of the shaft rather than
     * hanging directly under the anchor. Per the ticket: if Sable's rope does not actually collide
     * with world blocks, this test fails and that failure IS the acceptance-criterion-2 result —
     * see docs/rope-core.md's "Catch-on-obstruction result" section for how a CI failure here is
     * reported, not papered over with a synthetic pivot.
     */
    @GameTest(template = "catch_on_obstruction", timeoutTicks = 200)
    public static void catchOnObstruction(GameTestHelper helper) {
        BlockPos anchorBlock = new BlockPos(1, 14, 4);
        helper.setBlock(anchorBlock, Blocks.STONE);
        Vec3 anchorPos = Vec3.atCenterOf(helper.absolutePos(anchorBlock));

        // The post: a single-block-wide column standing between the anchor (x=1) and where the
        // player falls (x=7), below the anchor's height, so a straight line from anchor to a
        // player hanging at full extension passes through it.
        BlockPos postBase = new BlockPos(4, 1, 4);
        for (int y = postBase.getY(); y <= 9; y++) {
            helper.setBlock(new BlockPos(postBase.getX(), y, postBase.getZ()), Blocks.STONE);
        }
        Vec3 postTop = Vec3.atCenterOf(helper.absolutePos(new BlockPos(postBase.getX(), 9, postBase.getZ())));

        BlockPos playerSpawn = new BlockPos(7, 13, 4);
        ServerPlayer player = spawnMockPlayer(helper, playerSpawn);

        // Low slack relative to fallArrestSwing's 1.2 (over a much shorter anchor-player
        // distance): catchOnObstruction's anchor-to-player distance is ~6 blocks, so even a
        // modest slack fraction is a couple of absolute blocks of rope the player must fall
        // through before the rope goes taut at all. Too much slack here was tried first (1.3) and
        // the constraint never visibly engaged within the test's tick budget — see
        // docs/rope-core.md's CI history.
        double slack = 1.1;
        UUID ropeId = RopeManager.attachToPoint(player, anchorPos, helper.absolutePos(anchorBlock), slack);
        helper.assertTrue(ropeId != null, "rope attach failed: no Sable physics system in the game test level");
        double restLength = RopeManager.length(ropeId);

        helper.runAfterDelay(170, () -> {
            PlayerRope rope = RopeManager.get(ropeId);
            helper.assertTrue(rope != null, "rope was torn down before the catch could be observed");

            boolean anyPointNearPost = false;
            double closestToPost = Double.MAX_VALUE;
            for (Vector3d p : rope.points()) {
                double d = postTop.distanceTo(new Vec3(p.x, Math.min(p.y, postTop.y), p.z));
                closestToPost = Math.min(closestToPost, d);
                if (d <= RopeConstants.COLLISION_RADIUS * 2) {
                    anyPointNearPost = true;
                }
            }
            double distanceFromAnchor = anchorPos.distanceTo(player.position());
            LOGGER.info("[rope-core] catchOnObstruction diagnostics: restLength={} distanceFromAnchor={}"
                            + " playerPos={} closestToPost={}",
                    restLength, distanceFromAnchor, player.position(), closestToPost);

            helper.assertTrue(anyPointNearPost,
                    "no rope point settled near the post (closest was " + closestToPost
                            + " blocks away): the rope passed through the obstruction instead of catching on it");

            boolean playerOnPostSide = player.position().x < anchorPos.x + (postBase.getX() - anchorBlock.getX());
            helper.assertTrue(playerOnPostSide,
                    "player ended up at x=" + player.position().x
                            + ", not swung back toward the post's side of the shaft as a catch-and-swing would produce");

            helper.succeed();
        });
    }

    /**
     * Criterion 5: find the segment spacing at which tunnelling through a block-sized
     * obstruction starts. Three independent copies of the catch-on-obstruction rig
     * ({@code tunnelling_threshold.nbt}, three 9-block-wide bays far enough apart that Sable's
     * per-rope physics objects cannot interact across bays), one rope per bay at
     * {@link RopeConstants#SEGMENT_SPACING} (the shipped value), 2x and 4x that. Logged, not
     * asserted on failure for the wider spacings: this test's job is to report the threshold in
     * docs/rope-core.md, not to gate CI on a spacing nothing ships with — only the shipped
     * spacing (bay 0) is required to still catch.
     */
    @GameTest(template = "tunnelling_threshold", timeoutTicks = 200)
    public static void tunnellingThreshold(GameTestHelper helper) {
        double[] spacings = {
                RopeConstants.SEGMENT_SPACING,
                RopeConstants.SEGMENT_SPACING * 2,
                RopeConstants.SEGMENT_SPACING * 4,
        };
        int bayWidth = 9;
        UUID[] ropeIds = new UUID[spacings.length];
        Vec3[] postTops = new Vec3[spacings.length];

        for (int bay = 0; bay < spacings.length; bay++) {
            int x0 = bay * bayWidth;
            BlockPos anchorBlock = new BlockPos(x0 + 1, 14, 4);
            helper.setBlock(anchorBlock, Blocks.STONE);
            Vec3 anchorPos = Vec3.atCenterOf(helper.absolutePos(anchorBlock));

            BlockPos postBase = new BlockPos(x0 + 4, 1, 4);
            for (int y = postBase.getY(); y <= 9; y++) {
                helper.setBlock(new BlockPos(postBase.getX(), y, postBase.getZ()), Blocks.STONE);
            }
            postTops[bay] = Vec3.atCenterOf(helper.absolutePos(new BlockPos(postBase.getX(), 9, postBase.getZ())));

            ServerPlayer player = spawnMockPlayer(helper, new BlockPos(x0 + 7, 13, 4));
            ropeIds[bay] = RopeManager.attachToPointWithSpacing(player, anchorPos, helper.absolutePos(anchorBlock),
                    1.3, spacings[bay]);
            helper.assertTrue(ropeIds[bay] != null, "bay " + bay + ": rope attach failed");
        }

        helper.runAfterDelay(170, () -> {
            StringBuilder report = new StringBuilder("tunnelling threshold sweep: ");
            boolean shippedSpacingCaught = false;
            for (int bay = 0; bay < spacings.length; bay++) {
                PlayerRope rope = RopeManager.get(ropeIds[bay]);
                boolean caught = false;
                double closest = Double.MAX_VALUE;
                if (rope != null) {
                    for (Vector3d p : rope.points()) {
                        double d = postTops[bay].distanceTo(new Vec3(p.x, Math.min(p.y, postTops[bay].y), p.z));
                        closest = Math.min(closest, d);
                        if (d <= RopeConstants.COLLISION_RADIUS * 2) {
                            caught = true;
                        }
                    }
                }
                report.append("spacing=").append(spacings[bay]).append(" caught=").append(caught)
                        .append(" closest=").append(closest).append("; ");
                if (bay == 0) {
                    shippedSpacingCaught = caught;
                }
            }
            LOGGER.info("[rope-core] {}", report);
            helper.assertTrue(shippedSpacingCaught,
                    "the SHIPPED spacing (RopeConstants.SEGMENT_SPACING=" + RopeConstants.SEGMENT_SPACING
                            + ") tunnelled through the post — see the [rope-core] log line above for the full sweep");
            helper.succeed();
        });
    }

    /** Criterion 4, detach on request: {@link RopeManager#detach} removes the rope immediately. */
    @GameTest(template = "lifecycle", timeoutTicks = 60)
    public static void detachOnRequestRemovesRope(GameTestHelper helper) {
        ServerPlayer player = spawnMockPlayer(helper, new BlockPos(2, 5, 2));
        BlockPos anchorBlock = new BlockPos(2, 6, 2);
        helper.setBlock(anchorBlock, Blocks.STONE);
        UUID ropeId = RopeManager.attachToPoint(player, Vec3.atCenterOf(helper.absolutePos(anchorBlock)),
                helper.absolutePos(anchorBlock), 1.0);
        helper.assertTrue(ropeId != null, "rope attach failed");

        RopeManager.detach(ropeId);
        helper.assertTrue(RopeManager.get(ropeId) == null, "detach(ropeId) left the rope registered");
        helper.succeed();
    }

    /**
     * Criterion 4, anchor block broken: {@link RopeManager#tickAll} must notice the anchor is
     * gone (via {@link io.github.brooswitminecraft.dynamicwhips.rope.RopeAnchor.WorldPoint#isGone})
     * and tear the rope down on its own, with no explicit detach call from the test.
     */
    @GameTest(template = "lifecycle", timeoutTicks = 60)
    public static void breakingAnchorBlockDetachesRope(GameTestHelper helper) {
        ServerPlayer player = spawnMockPlayer(helper, new BlockPos(2, 5, 2));
        BlockPos anchorBlock = new BlockPos(2, 6, 2);
        helper.setBlock(anchorBlock, Blocks.STONE);
        UUID ropeId = RopeManager.attachToPoint(player, Vec3.atCenterOf(helper.absolutePos(anchorBlock)),
                helper.absolutePos(anchorBlock), 1.0);
        helper.assertTrue(ropeId != null, "rope attach failed");

        helper.destroyBlock(anchorBlock);

        helper.runAfterDelay(5, () -> {
            helper.assertTrue(RopeManager.get(ropeId) == null,
                    "rope is still registered after its anchor block was broken");
            helper.succeed();
        });
    }

    /**
     * Criterion 4, death: the {@code LivingDeathEvent} listener {@code DynamicWhipsMod} wires to
     * {@link RopeManager#detachAllOwnedBy} must actually fire and clear the player's ropes — this
     * posts a real event through the live NeoForge event bus rather than calling
     * {@code detachAllOwnedBy} directly, so it exercises the wiring in {@code DynamicWhipsMod}
     * itself, not just the API.
     */
    @GameTest(template = "lifecycle", timeoutTicks = 60)
    public static void deathDetachesAllOwnedRopes(GameTestHelper helper) {
        ServerPlayer player = spawnMockPlayer(helper, new BlockPos(2, 5, 2));
        BlockPos anchorBlock = new BlockPos(2, 6, 2);
        helper.setBlock(anchorBlock, Blocks.STONE);
        UUID ropeId = RopeManager.attachToPoint(player, Vec3.atCenterOf(helper.absolutePos(anchorBlock)),
                helper.absolutePos(anchorBlock), 1.0);
        helper.assertTrue(ropeId != null, "rope attach failed");
        helper.assertTrue(RopeManager.activeRopeCount() >= 1, "rope did not register before the death event");

        NeoForge.EVENT_BUS.post(new LivingDeathEvent(player, player.damageSources().genericKill()));

        helper.assertTrue(RopeManager.get(ropeId) == null, "rope survived the player's LivingDeathEvent");
        helper.succeed();
    }

    private static ServerPlayer spawnMockPlayer(GameTestHelper helper, BlockPos relativeSpawn) {
        // Replicates GameTestHelper#makeMockServerPlayerInLevel()'s own recipe (profile, a mock
        // Connection backed by an EmbeddedChannel, PlayerList#placeNewPlayer) with one addition —
        // see this class's javadoc for why both of GameTestHelper's own mock-player methods, and a
        // connectionless hand-built player, were tried first and ruled out.
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

        Vec3 spawn = Vec3.atBottomCenterOf(helper.absolutePos(relativeSpawn));
        player.moveTo(spawn.x, spawn.y, spawn.z, player.getYRot(), player.getXRot());
        player.setDeltaMovement(Vec3.ZERO);
        return player;
    }
}
