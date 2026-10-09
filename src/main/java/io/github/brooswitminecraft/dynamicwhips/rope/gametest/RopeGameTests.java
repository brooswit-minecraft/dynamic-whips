package io.github.brooswitminecraft.dynamicwhips.rope.gametest;

import java.util.ArrayList;
import java.util.List;
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
import net.minecraft.world.entity.MoverType;
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
        simulateGravityEachTick(helper, player);
        double spawnY = player.position().y;

        double slack = 1.2;
        UUID ropeId = RopeManager.attachToPoint(player, anchorPos, helper.absolutePos(anchorBlock), slack);
        helper.assertTrue(ropeId != null, "rope attach failed: no Sable physics system in the game test level");

        double restLength = RopeManager.length(ropeId);
        // Correction tolerance, not just floating-point slack: the constraint is re-applied once
        // per server tick (RopeConstants#CONSTRAINT_INTERVAL_TICKS), one tick after
        // simulateGravityEachTick's own move each tick, so a real overshoot of up to one tick's
        // fall distance at the speed the player has reached by the time the rope engages is
        // expected and correct, not a bug — found via a measured ~1-block overshoot in CI before
        // this tolerance was widened (see docs/rope-core.md's CI history).
        double maxAllowed = restLength + RopeConstants.SWING_SLACK + 1.5;

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

            helper.assertTrue(spawnY - player.position().y > 0.5,
                    "player never actually fell from its spawn height (spawnY=" + spawnY + ", now="
                            + player.position().y + ") — a frozen player trivially satisfies the other"
                            + " assertions above without proving the rope did anything");

            helper.succeed();
        });
    }

    /** The post column's top block's Y (relative): tall enough that the straight anchor-to-player
     * line, even before any falling, already passes through it — see {@link #buildCatchRig}. */
    private static final int POST_TOP_Y = 13;

    /**
     * Criterion 2, THE SPEC SCENARIO: an anchor above, a block-sized post below and between the
     * anchor and the player, falling player. Structure: {@code catch_on_obstruction.nbt}, two
     * 9x16x9 bays side by side. Bay 0 (x 0-8) gets the post; bay 1 (x 9-17), built by the same
     * {@link #buildCatchRig} with identical anchor/player geometry, has no post at all — a
     * negative control. Without it, a passing player-position check can't tell "the post caught
     * it" apart from "that's just where an unobstructed pendulum ends up" (this test's own first
     * version made exactly that mistake; see docs/rope-core.md's CI history for the full review
     * that found it and why the old y-clamped distance metric was also unsound — it measured
     * horizontal distance to the post's column for any point above it, not actual proximity).
     *
     * <p>Pass condition, all CI-asserted: (a) the unobstructed anchor-to-player line, computed
     * directly from their known positions (not inferred from the rope's own settled state — see
     * {@link #buildCatchRig}), demonstrably crosses the post's height range at its x, proving the
     * geometry forces an intersection rather than coincidentally missing it; (b) the real post's
     * rope has no point clipping inside its solid block (no tunnelling) but does have one within
     * {@code 2 * COLLISION_RADIUS} of its surface (a real catch); (c) the player's final position
     * differs measurably (more than 1 block) between the obstructed and the control (no-post)
     * bay. Per the ticket: if (a) holds but (b) or (c) fails, that IS the acceptance-criterion-2
     * result — report it, do not add a raycast-and-pivot fallback.
     */
    // required = true: this test's prior failures were never a collision-behavior result — they
    // were this mod's own read bug (PlayerRope never called RopePhysicsObject#updatePose(), so
    // getPoints() returned the creation-time layout forever; see docs/rope-core.md section 1.6).
    // With that fixed, the rope demonstrably catches on the post (closestWithPost=0.0,
    // clippedWithPost=false, >1-block divergence from the unobstructed control — see
    // docs/rope-core.md section 2), so this is a real, trustworthy CI gate again.
    @GameTest(template = "catch_on_obstruction", timeoutTicks = 200, required = true)
    public static void catchOnObstruction(GameTestHelper helper) {
        int bayWidth = 9;
        CatchRig withPost = buildCatchRig(helper, 0, true);
        CatchRig control = buildCatchRig(helper, bayWidth, false);

        // Proves the geometry really forces an intersection — computed directly from the anchor,
        // post and initial player position, not from reading the rope back at the end of the
        // test. Reading it back instead was tried first and rejected: by the time the pendulum
        // has settled, an UNOBSTRUCTED swing may have moved entirely past the post's x-region
        // (both ends left of it, say), so "no rope point is near the post" at that point proves
        // nothing about whether the straight line crossed it earlier — exactly the kind of
        // post-hoc, timing-dependent reasoning this test exists to rule out.
        double t = (withPost.postCenterXAbsolute() - withPost.anchorPos().x)
                / (withPost.initialPlayerPos().x - withPost.anchorPos().x);
        double crossingY = withPost.anchorPos().y + t * (withPost.initialPlayerPos().y - withPost.anchorPos().y);
        double postMinY = helper.absolutePos(withPost.postBase()).getY();
        double postMaxY = postMinY + (POST_TOP_Y - withPost.postBase().getY() + 1);
        helper.assertTrue(t > 0.0 && t < 1.0,
                "the post's x is not between the anchor's and the player's initial x (t=" + t
                        + "): the geometry doesn't place it on the straight-line path at all");
        helper.assertTrue(crossingY >= postMinY && crossingY <= postMaxY,
                "the unobstructed anchor-to-player line crosses the post's x at y=" + crossingY
                        + ", outside the post's own height range [" + postMinY + ", " + postMaxY + "]: the geometry"
                        + " does not actually force the straight line through the post, so a pass would not be"
                        + " meaningful evidence of collision");

        helper.runAfterDelay(170, () -> {
            PlayerRope ropeWithPost = RopeManager.get(withPost.ropeId());
            helper.assertTrue(ropeWithPost != null, "rope (with post) was torn down before the catch could be observed");
            helper.assertTrue(RopeManager.get(control.ropeId()) != null, "control rope was torn down before it could be observed");

            double closestWithPost = closestPointToColumn(ropeWithPost, withPost.postBase(), POST_TOP_Y, helper);
            boolean clippedWithPost = anyPointInsideColumn(ropeWithPost, withPost.postBase(), POST_TOP_Y, helper);
            double withPostPlayerX = withPost.player().position().x - withPost.bayOriginX();
            double controlPlayerX = control.player().position().x - control.bayOriginX();

            LOGGER.info("[rope-core] catchOnObstruction diagnostics: closestWithPost={} clippedWithPost={}"
                            + " withPostPlayerX={} controlPlayerX={}",
                    closestWithPost, clippedWithPost, withPostPlayerX, controlPlayerX);
            if (clippedWithPost) {
                // One-shot extra detail to actually diagnose a clip instead of guessing at it:
                // every solver point's position (bay-relative) and its own distance to the post,
                // so a clip can be told apart from a point legitimately resting at the surface.
                List<Vector3d> judged = collisionJudgedPoints(ropeWithPost);
                StringBuilder dump = new StringBuilder(
                        "[rope-core] catchOnObstruction clip detail (x is bay-relative; y,z are absolute world"
                                + " coordinates / distanceToColumn): ");
                for (int i = 0; i < judged.size(); i++) {
                    Vector3d p = judged.get(i);
                    Vec3 abs = new Vec3(p.x, p.y, p.z);
                    double d = distanceToColumn(abs, withPost.postBase(), POST_TOP_Y, helper);
                    dump.append(i).append(":(").append(p.x - withPost.bayOriginX()).append(',').append(p.y)
                            .append(',').append(p.z).append(")/").append(d).append(' ');
                }
                LOGGER.info(dump.toString());
            }

            // Distinguishes "the solver moved these points and they ended up somewhere bad" from
            // "the solver never moved these points at all" — found necessary after a first run of
            // this rewritten test clipped at every spacing, including ones that should reliably
            // catch; it turned out every judged point was still bit-for-bit at its creation-time
            // layout 170 ticks later. The cause was this mod's own read path: PlayerRope never
            // called RopePhysicsObject#updatePose() before reading points, so getPoints() returned
            // the creation-time layout forever regardless of RopeHandle#wakeUp — see
            // docs/rope-core.md section 1.6. That is fixed now; this check stays as a regression
            // canary for the same bug class, not as evidence about Sable's own solver.
            List<Vector3d> initialJudged = withPost.initialPoints().size() < 2 ? withPost.initialPoints()
                    : withPost.initialPoints().subList(0, withPost.initialPoints().size() - 1);
            boolean frozen = pointsEffectivelyIdentical(initialJudged, collisionJudgedPoints(ropeWithPost));
            helper.assertFalse(frozen,
                    "every judged rope point is still at its creation-time layout 170 ticks later"
                            + " (RopeHandle#wakeUp was called at creation and every tick): this is the signature of"
                            + " PlayerRope reading points without first calling RopePhysicsObject#updatePose() (see"
                            + " docs/rope-core.md section 1.6), NOT a Sable-solver or CI-environment limitation —"
                            + " that read bug is fixed, so seeing this again means it or one like it has regressed");

            // EPIC DECISION (docs/rope-core.md section 2): a flush, non-penetrating rest
            // (closest=0.0, clipped=false) counts as a catch. The old `> 0.05` lower bound is
            // removed — it duplicated this clippedWithPost guard and read the cleanest possible
            // solver outcome (resolved exactly at the surface) as a failure. Nothing else about
            // this assertion's scope changes: clippedWithPost and the COLLISION_RADIUS * 2 upper
            // bound both stay.
            helper.assertFalse(clippedWithPost,
                    "a rope point ended up INSIDE the post's solid block instead of being stopped by it — tunnelling,"
                            + " not catching");

            helper.assertTrue(!clippedWithPost && closestWithPost <= RopeConstants.COLLISION_RADIUS * 2,
                    "no rope point settled near (and not inside) the post's surface (closest=" + closestWithPost
                            + "): the rope passed through the obstruction instead of catching on it");

            double playerXDifference = Math.abs(withPostPlayerX - controlPlayerX);
            helper.assertTrue(playerXDifference > 1.0,
                    "the post made no measurable difference to where the player ended up (with-post x=" + withPostPlayerX
                            + ", control x=" + controlPlayerX + ") — can't distinguish a catch from a free swing");

            helper.succeed();
        });
    }

    private record CatchRig(UUID ropeId, BlockPos postBase, ServerPlayer player, double bayOriginX,
            Vec3 anchorPos, Vec3 initialPlayerPos, double postCenterXAbsolute, List<Vector3d> initialPoints) {
    }

    /**
     * Builds one catch-on-obstruction rig at bay x-origin {@code x0}: anchor at relative
     * {@code (x0+1, 14, 4)}, player spawn at {@code (x0+7, 11, 4)}, and — if {@code withPost} —
     * a stone column at {@code (x0+4, 1..POST_TOP_Y, 4)}. The straight line from anchor to
     * player's initial position already crosses the column's x at a y inside its height range
     * (checked directly in {@code catchOnObstruction}, not assumed), so the post intersects the
     * unobstructed chord before the player even starts falling, and continues to as the player
     * falls further (every point below the anchor only moves the chord deeper into the column's
     * range, never out of it). {@code withPost = false} skips placing the blocks but returns the
     * same {@code postBase}, so the caller can measure the identical (now empty) region as a
     * control.
     */
    private static CatchRig buildCatchRig(GameTestHelper helper, int x0, boolean withPost) {
        return buildCatchRig(helper, x0, withPost, RopeConstants.SEGMENT_SPACING);
    }

    /**
     * As the 3-argument overload, but at an explicit {@code segmentSpacing} instead of the
     * shipped {@link RopeConstants#SEGMENT_SPACING} — used only by {@link #tunnellingThreshold}
     * to sweep spacing across otherwise-identical rigs (criterion 5).
     */
    private static CatchRig buildCatchRig(GameTestHelper helper, int x0, boolean withPost, double segmentSpacing) {
        BlockPos anchorBlock = new BlockPos(x0 + 1, 14, 4);
        helper.setBlock(anchorBlock, Blocks.STONE);
        Vec3 anchorPos = Vec3.atCenterOf(helper.absolutePos(anchorBlock));

        BlockPos postBase = new BlockPos(x0 + 4, 1, 4);
        if (withPost) {
            for (int y = postBase.getY(); y <= POST_TOP_Y; y++) {
                helper.setBlock(new BlockPos(postBase.getX(), y, postBase.getZ()), Blocks.STONE);
            }
        }

        BlockPos playerSpawn = new BlockPos(x0 + 7, 11, 4);
        ServerPlayer player = spawnMockPlayer(helper, playerSpawn);
        Vec3 initialPlayerPos = player.getBoundingBox().getCenter();
        simulateGravityEachTick(helper, player);

        // Low slack relative to fallArrestSwing's 1.2 (over a much shorter anchor-player
        // distance): this rig's anchor-to-player distance is ~6.7 blocks, so even a modest slack
        // fraction is a couple of absolute blocks of rope the player must fall through before the
        // rope goes taut at all. Too much slack here was tried first (1.3) and the constraint
        // never visibly engaged within the test's tick budget — see docs/rope-core.md's CI history.
        double slack = 1.1;
        UUID ropeId = RopeManager.attachToPointWithSpacing(player, anchorPos, helper.absolutePos(anchorBlock),
                slack, segmentSpacing);
        helper.assertTrue(ropeId != null, "rope attach failed: no Sable physics system in the game test level");

        double bayOriginX = helper.absolutePos(new BlockPos(x0, 0, 0)).getX();
        double postCenterXAbsolute = helper.absolutePos(postBase).getX() + 0.5;
        // Snapshot right after creation, copied (Vector3d is mutable and rope.points() may be a
        // live view) — compared later against the same rope's points well into the test, to tell
        // "the solver moved these points" apart from "they never moved at all". See
        // catchOnObstruction's frozen-rope check and docs/rope-core.md's CI history for why this
        // check exists: a first run of this rewritten test clipped at every spacing including
        // ones that should reliably catch, and the actual cause turned out to be that Sable's
        // solver was not stepping this rope's points at all, not a collision/geometry failure.
        List<Vector3d> initialPoints = new ArrayList<>();
        for (Vector3d p : RopeManager.get(ropeId).points()) {
            initialPoints.add(new Vector3d(p));
        }
        return new CatchRig(ropeId, postBase, player, bayOriginX, anchorPos, initialPlayerPos, postCenterXAbsolute,
                initialPoints);
    }

    /** True 3D distance from {@code point} to the column's block AABB (0 if on or inside it) — not
     * the y-clamped, effectively-horizontal-only metric an earlier version of this test used. */
    private static double distanceToColumn(Vec3 point, BlockPos columnBase, int topY, GameTestHelper helper) {
        Vec3 min = Vec3.atLowerCornerOf(helper.absolutePos(columnBase));
        Vec3 max = min.add(1, topY - columnBase.getY() + 1, 1);
        double dx = Math.max(Math.max(min.x - point.x, point.x - max.x), 0);
        double dy = Math.max(Math.max(min.y - point.y, point.y - max.y), 0);
        double dz = Math.max(Math.max(min.z - point.z, point.z - max.z), 0);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Strictly inside the column's AABB — distinguishes tunnelling (clipped through) from a
     * genuine catch (stopped at the surface, distance > 0 but small). */
    private static boolean isInsideColumn(Vec3 point, BlockPos columnBase, int topY, GameTestHelper helper) {
        Vec3 min = Vec3.atLowerCornerOf(helper.absolutePos(columnBase));
        Vec3 max = min.add(1, topY - columnBase.getY() + 1, 1);
        return point.x > min.x && point.x < max.x && point.y > min.y && point.y < max.y
                && point.z > min.z && point.z < max.z;
    }

    /**
     * Rope points to actually judge collision by: every point except the last. The last point is
     * the END attachment, kinematically forced to the player's own position every tick
     * ({@code PlayerRope#tick}) — it is wherever the player's body is, not something Sable's own
     * solver collision-resolves, so it can read as "inside" the post whenever the player's body
     * happens to overlap that space without that meaning anything about whether the ROPE itself
     * collides with the obstruction. Found via a first run of this test where every spacing,
     * including ones that should tunnel, reported clipped=true; the diagnostic log pinned it to
     * the END point specifically, not the solver's own free points. See docs/rope-core.md's CI
     * history.
     */
    /** True if every point in {@code later} is within 1mm of the point at the same index in
     * {@code initial} — i.e. the rope's own solver has not visibly moved any of them. */
    private static boolean pointsEffectivelyIdentical(List<Vector3d> initial, List<Vector3d> later) {
        if (initial.size() != later.size()) {
            return false;
        }
        for (int i = 0; i < initial.size(); i++) {
            if (initial.get(i).distance(later.get(i)) > 0.001) {
                return false;
            }
        }
        return true;
    }

    private static List<Vector3d> collisionJudgedPoints(PlayerRope rope) {
        List<Vector3d> points = rope.points();
        return points.size() < 2 ? points : points.subList(0, points.size() - 1);
    }

    private static double closestPointToColumn(PlayerRope rope, BlockPos columnBase, int topY, GameTestHelper helper) {
        double closest = Double.MAX_VALUE;
        for (Vector3d p : collisionJudgedPoints(rope)) {
            closest = Math.min(closest, distanceToColumn(new Vec3(p.x, p.y, p.z), columnBase, topY, helper));
        }
        return closest;
    }

    private static boolean anyPointInsideColumn(PlayerRope rope, BlockPos columnBase, int topY, GameTestHelper helper) {
        for (Vector3d p : collisionJudgedPoints(rope)) {
            if (isInsideColumn(new Vec3(p.x, p.y, p.z), columnBase, topY, helper)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Criterion 5: find the segment spacing at which tunnelling through a block-sized
     * obstruction starts. Three independent copies of {@link #buildCatchRig}'s geometry
     * ({@code tunnelling_threshold.nbt}, three 9-block-wide bays far enough apart that Sable's
     * per-rope physics objects cannot interact across bays), one rope per bay at
     * {@link RopeConstants#SEGMENT_SPACING} (the shipped value), 2x and 4x that, using the same
     * AABB distance metric {@code catchOnObstruction} does (see that method's javadoc for why the
     * original y-clamped version was unsound). Logged, not asserted on failure for the wider
     * spacings: this test's job is to report the threshold in docs/rope-core.md, not to gate CI
     * on a spacing nothing ships with — only the shipped spacing (bay 0) is required to still
     * catch, and to not simply clip through (tunnel).
     */
    // required = false, but not for the old reason (the frozen-rope read bug is fixed — see
    // catchOnObstruction's comment and docs/rope-core.md section 1.6). This test's own shipped-
    // spacing assertions below (not frozen, and caught) are just as trustworthy now as
    // catchOnObstruction's, which already gates CI on that same rig and result. Left optional
    // on its own merits instead: this method's real job is the diagnostic sweep across spacings
    // for criterion 5's still-open tunnelling-threshold question (docs/rope-core.md section 3),
    // which is exploratory reporting, not a second required gate duplicating catchOnObstruction.
    @GameTest(template = "tunnelling_threshold", timeoutTicks = 200, required = false)
    public static void tunnellingThreshold(GameTestHelper helper) {
        double[] spacings = {
                RopeConstants.SEGMENT_SPACING,
                RopeConstants.SEGMENT_SPACING * 2,
                RopeConstants.SEGMENT_SPACING * 4,
        };
        int bayWidth = 9;
        CatchRig[] rigs = new CatchRig[spacings.length];
        for (int bay = 0; bay < spacings.length; bay++) {
            rigs[bay] = buildCatchRig(helper, bay * bayWidth, true, spacings[bay]);
        }

        helper.runAfterDelay(170, () -> {
            StringBuilder report = new StringBuilder("tunnelling threshold sweep: ");
            boolean shippedSpacingCaught = false;
            boolean shippedSpacingFrozen = false;
            for (int bay = 0; bay < spacings.length; bay++) {
                CatchRig rig = rigs[bay];
                PlayerRope rope = RopeManager.get(rig.ropeId());
                boolean clipped = false;
                boolean caught = false;
                boolean frozen = false;
                double closest = Double.MAX_VALUE;
                if (rope != null) {
                    closest = closestPointToColumn(rope, rig.postBase(), POST_TOP_Y, helper);
                    clipped = anyPointInsideColumn(rope, rig.postBase(), POST_TOP_Y, helper);
                    caught = !clipped && closest <= RopeConstants.COLLISION_RADIUS * 2;
                    List<Vector3d> initialJudged = rig.initialPoints().size() < 2 ? rig.initialPoints()
                            : rig.initialPoints().subList(0, rig.initialPoints().size() - 1);
                    frozen = pointsEffectivelyIdentical(initialJudged, collisionJudgedPoints(rope));
                }
                report.append("spacing=").append(spacings[bay]).append(" caught=").append(caught)
                        .append(" clipped=").append(clipped).append(" closest=").append(closest)
                        .append(" frozen=").append(frozen).append("; ");
                if (bay == 0) {
                    shippedSpacingCaught = caught;
                    shippedSpacingFrozen = frozen;
                }
            }
            LOGGER.info("[rope-core] {}", report);

            // Same honesty fix as catchOnObstruction (docs/rope-core.md sections 2-3): a frozen
            // rope is not evidence of tunnelling, it is evidence this test cannot observe
            // tunnelling at all. Checked BEFORE the catch assertion below, which would otherwise
            // assert the exact "tunnelled through" claim this guard exists to rule out.
            helper.assertFalse(shippedSpacingFrozen,
                    "every judged rope point in the SHIPPED-spacing bay (RopeConstants.SEGMENT_SPACING="
                            + RopeConstants.SEGMENT_SPACING + ") is still at its creation-time layout 170 ticks"
                            + " later -- this is NOT evidence the shipped spacing tunnels, it is evidence this"
                            + " GameTest cannot currently tell; see the [rope-core] log line above for the full"
                            + " sweep");
            helper.assertTrue(shippedSpacingCaught,
                    "the SHIPPED spacing (RopeConstants.SEGMENT_SPACING=" + RopeConstants.SEGMENT_SPACING
                            + ") tunnelled through, or clipped inside, the post — see the [rope-core] log line above"
                            + " for the full sweep");
            helper.succeed();
        });
    }

    /**
     * Criterion 1 review fix: {@code PlayerRope}'s segment spacing must be the ACTUAL spacing
     * Sable laid the rope out at (after the {@code MIN_POINTS}/{@code MAX_POINTS} clamp), not the
     * requested spacing {@code PlayerRope#create} was given — see {@code PlayerRope#segmentSpacing}
     * and {@code RopeConstants#MAX_POINTS}'s javadoc for the worked example (a 64-block rope at
     * slack 1.1 used to clamp to an actual spacing of 0.55, while {@code restLength()} still
     * reported the requested 64.0). Reproduced here at a tiny scale, with an explicit requested
     * {@code segmentSpacing} via {@link RopeManager#attachToPointWithSpacing} instead of a
     * 64-block rig, so both clamp directions fit inside the small {@code lifecycle} structure:
     * the clamp only depends on the ratio {@code straightLine * slack / requestedSpacing}, never
     * on the absolute distance, so a coarse-relative-to-distance request clamps {@code pointCount}
     * UP to {@code MIN_POINTS} exactly as a long rope with normal spacing would, and a
     * fine-relative-to-distance request clamps it DOWN to {@code MAX_POINTS} exactly as the
     * 64-block hook does.
     */
    @GameTest(template = "lifecycle", timeoutTicks = 20)
    public static void segmentSpacingReflectsActualLayoutAfterClamp(GameTestHelper helper) {
        // MIN_POINTS clamp: requesting a spacing much coarser than the anchor-player distance
        // would naively compute pointCount < MIN_POINTS; clamped up, so the ACTUAL spacing ends
        // up SMALLER than what was requested.
        ServerPlayer shortPlayer = spawnMockPlayer(helper, new BlockPos(2, 2, 2));
        BlockPos shortAnchorBlock = new BlockPos(2, 3, 2);
        helper.setBlock(shortAnchorBlock, Blocks.STONE);
        double shortSlack = 1.0;
        double requestedSpacingShort = 5.0;
        UUID shortRopeId = RopeManager.attachToPointWithSpacing(shortPlayer,
                Vec3.atCenterOf(helper.absolutePos(shortAnchorBlock)), helper.absolutePos(shortAnchorBlock),
                shortSlack, requestedSpacingShort);
        helper.assertTrue(shortRopeId != null, "short rig: rope attach failed");
        PlayerRope shortRope = RopeManager.get(shortRopeId);
        helper.assertTrue(shortRope.pointCount() == RopeConstants.MIN_POINTS,
                "expected the MIN_POINTS clamp to bite for this rig; got pointCount=" + shortRope.pointCount());
        double actualShortSpacing = shortRope.restLength() / (shortRope.pointCount() - 1);
        helper.assertTrue(actualShortSpacing < requestedSpacingShort,
                "a clamped-UP pointCount must make the ACTUAL spacing SMALLER than the requested "
                        + requestedSpacingShort + ", but restLength()/segments reports " + actualShortSpacing);

        // MAX_POINTS clamp: the review's own 64-block/slack-1.1 worked example, reproduced with a
        // tiny requested spacing instead of a 64-block rig. Clamped down, so the ACTUAL spacing
        // ends up LARGER than what was requested — exactly the divergence restLength() used to
        // hide by reporting the unclamped requested value instead.
        ServerPlayer longPlayer = spawnMockPlayer(helper, new BlockPos(2, 1, 2));
        BlockPos longAnchorBlock = new BlockPos(2, 7, 2);
        helper.setBlock(longAnchorBlock, Blocks.STONE);
        double longSlack = 1.1;
        double requestedSpacingLong = 0.01;
        UUID longRopeId = RopeManager.attachToPointWithSpacing(longPlayer,
                Vec3.atCenterOf(helper.absolutePos(longAnchorBlock)), helper.absolutePos(longAnchorBlock),
                longSlack, requestedSpacingLong);
        helper.assertTrue(longRopeId != null, "long rig: rope attach failed");
        PlayerRope longRope = RopeManager.get(longRopeId);
        helper.assertTrue(longRope.pointCount() == RopeConstants.MAX_POINTS,
                "expected the MAX_POINTS clamp to bite for this rig; got pointCount=" + longRope.pointCount());
        double actualLongSpacing = longRope.restLength() / (longRope.pointCount() - 1);
        helper.assertTrue(actualLongSpacing > requestedSpacingLong,
                "a clamped-DOWN pointCount must make the ACTUAL spacing LARGER than the requested "
                        + requestedSpacingLong + ", but restLength()/segments reports " + actualLongSpacing);

        // The actual review bug: restLength() must match the rope's real laid-out length
        // (straightLine * slack, computed independently of PlayerRope's own bookkeeping), not
        // pointCount derived from the unclamped requested spacing.
        double straightLine = Vec3.atCenterOf(helper.absolutePos(longAnchorBlock))
                .distanceTo(longPlayer.getBoundingBox().getCenter());
        double expectedRestLength = straightLine * longSlack;
        helper.assertTrue(Math.abs(longRope.restLength() - expectedRestLength) < 0.01,
                "restLength() (" + longRope.restLength() + ") must match the rope's real laid-out length ("
                        + expectedRestLength + "), not a value derived from the unclamped requested spacing");

        helper.succeed();
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

    /**
     * Drives free fall on {@code player} every tick for the rest of this test. A real client
     * normally sends the movement packets that make a player fall at all — the server does not
     * independently simulate player physics the way it does for mob AI — so without this, a mock
     * player built by {@link #spawnMockPlayer} never moves even one block in any number of ticks
     * (found via the diagnostic log line in {@code catchOnObstruction}: the player's Y position
     * was bit-for-bit identical to its spawn Y after 170 ticks). Vanilla's own gravity constant
     * and terminal velocity, since nothing else in this mod needs those named separately.
     */
    private static void simulateGravityEachTick(GameTestHelper helper, ServerPlayer player) {
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
