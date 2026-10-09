package io.github.brooswitminecraft.dynamicwhips.rope.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.joml.Vector3d;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import io.github.brooswitminecraft.dynamicwhips.DynamicWhipsMod;
import io.github.brooswitminecraft.dynamicwhips.rope.PlayerRope;
import io.github.brooswitminecraft.dynamicwhips.rope.RopeAnchor;
import io.github.brooswitminecraft.dynamicwhips.rope.RopeConstants;
import io.github.brooswitminecraft.dynamicwhips.rope.RopeManager;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static io.github.brooswitminecraft.dynamicwhips.rope.gametest.GameTestSupport.simulateGravityEachTick;
import static io.github.brooswitminecraft.dynamicwhips.rope.gametest.GameTestSupport.spawnMockPlayerAtAbsolute;
import static io.github.brooswitminecraft.dynamicwhips.rope.gametest.GameTestSupport.spawnMockPlayer;

/**
 * Headless coverage for MINECRAFT-85 acceptance criteria 1, 2 and 4, run by {@code gradlew
 * runGameTestServer} and wired into CI (see {@code .github/workflows/ci.yml}). Criterion 3
 * (client sync/rendering) and criterion 7 (64-block performance) are NOT covered here: both need
 * a real client or a real multi-minute load, neither of which the headless game test server can
 * give; see docs/rope-core.md for the manual procedures covering those.
 *
 * <p>Every test's player is a real, logged-in {@link ServerPlayer} built by {@link
 * GameTestSupport#spawnMockPlayer} — see that class's javadoc for the full recipe and why both of
 * {@code GameTestHelper}'s own mock-player methods, and a connectionless hand-built player, were
 * tried first and ruled out.
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
    // required = true: the earlier FROZEN results were this mod's own read bug (PlayerRope never
    // called RopePhysicsObject#updatePose(), so getPoints() returned the creation-time layout
    // forever; see docs/rope-core.md section 1.6) — fixed, not a collision result. Since that fix,
    // this test has failed once in four runs on identical code with a genuine
    // clippedWithPost=true: a real, observed tunnelling event, not noise. required = true by epic
    // decision BECAUSE that failure is an observed tunnelling event rather than noise —
    // intermittent red is expected until MINECRAFT-127 diagnoses the nondeterminism, and is never
    // grounds to loosen this assertion or revert this flag. See docs/rope-core.md section 2 for
    // the numbers.
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

            // MINECRAFT-127 hypothesis (b): this structure's own absolute x, measured fresh every
            // run — docs/rope-core.md section 1.6 recorded |x| ~ 8.16e6 in one prior run without
            // re-measuring it on every run since. Logged here so every CI run this PR collects
            // (criterion 4) carries its own measurement alongside the resting-position numbers.
            double structureAbsoluteX = helper.absolutePos(BlockPos.ZERO).getX();
            LOGGER.info("[rope-core] catchOnObstruction diagnostics: closestWithPost={} clippedWithPost={}"
                            + " withPostPlayerX={} controlPlayerX={} structureAbsoluteX={}",
                    closestWithPost, clippedWithPost, withPostPlayerX, controlPlayerX, structureAbsoluteX);
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

    /**
     * SIGNED 3D distance from {@code point} to the column's block AABB surface: positive outside
     * (the ordinary gap to the nearest face), exactly 0 on a face, and NEGATIVE inside (how far
     * {@code point} would have to travel to reach the nearest face, i.e. penetration depth).
     *
     * <p>MINECRAFT-127 defect 3: the previous version of this method clamped every per-axis term
     * at 0 before combining them, which is the textbook UNSIGNED point-to-AABB distance — correct
     * for a point outside the box, but it also reports exactly 0.0 for every point anywhere
     * inside the box, shallow or deep. That collapses "resting flush at the surface" and "tunnelled
     * a full block deep" onto the identical bit pattern, which is exactly what let a penetrating
     * run (clippedWithPost=true) log the same closest=0.0 a clean catch does — not a measurement
     * of distance-to-surface at all once the point is inside. The signed version below still
     * returns the same positive value for every outside case (unchanged behaviour, unchanged call
     * sites), but now returns a genuine, non-zero, negative penetration depth for an inside point,
     * so {@code closestPointToColumn} can no longer silently conflate the two. See
     * {@link #closestDistanceMetricIsDefensible} for the hand-computed cases this is pinned
     * against, including one that must NOT report 0.0.
     */
    private static double distanceToColumn(Vec3 point, BlockPos columnBase, int topY, GameTestHelper helper) {
        return distanceToColumnAbsolute(point, helper.absolutePos(columnBase), topY - columnBase.getY() + 1);
    }

    /**
     * As {@link #distanceToColumn}, but against an ABSOLUTE lower-corner {@code BlockPos} and an
     * explicit height instead of a structure-relative {@code columnBase}/{@code topY} pair — used
     * by {@link #postRigStabilityNearOriginVsAtStructure} to judge a column built at hardcoded
     * absolute world coordinates, which {@code helper.absolutePos} must not be applied to.
     */
    private static double distanceToColumnAbsolute(Vec3 point, BlockPos absoluteMinCorner, int heightBlocks) {
        return distanceToColumnAbsolute(point, absoluteMinCorner, heightBlocks, 1);
    }

    /** As the 3-argument overload, but with an explicit horizontal width (x and z) instead of the
     * usual single-block column — used by {@link #postRigStabilityNearOriginVsAtStructure}'s
     * unmissable 3x3 positive-control wall. {@code absoluteMinCorner} is still the x/z MINIMUM
     * corner (not centered), same convention as every other caller. */
    private static double distanceToColumnAbsolute(Vec3 point, BlockPos absoluteMinCorner, int heightBlocks,
            int widthBlocks) {
        Vec3 min = Vec3.atLowerCornerOf(absoluteMinCorner);
        Vec3 max = min.add(widthBlocks, heightBlocks, widthBlocks);
        double dx = Math.max(Math.max(min.x - point.x, point.x - max.x), 0);
        double dy = Math.max(Math.max(min.y - point.y, point.y - max.y), 0);
        double dz = Math.max(Math.max(min.z - point.z, point.z - max.z), 0);
        if (dx > 0 || dy > 0 || dz > 0) {
            // Outside on at least one axis: identical to the old unsigned metric.
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        // Inside (or exactly on the surface) on every axis: the negative distance to the NEAREST
        // face, i.e. how deep point has penetrated. Each term is >= 0 because point is within
        // [min,max] on that axis here; the smallest of the three is the cheapest way out.
        double insideX = Math.min(point.x - min.x, max.x - point.x);
        double insideY = Math.min(point.y - min.y, max.y - point.y);
        double insideZ = Math.min(point.z - min.z, max.z - point.z);
        return -Math.min(insideX, Math.min(insideY, insideZ));
    }

    /** Strictly inside the column's AABB — distinguishes tunnelling (clipped through) from a
     * genuine catch (stopped at the surface, distance > 0 but small). */
    private static boolean isInsideColumn(Vec3 point, BlockPos columnBase, int topY, GameTestHelper helper) {
        return isInsideColumnAbsolute(point, helper.absolutePos(columnBase), topY - columnBase.getY() + 1);
    }

    /** As {@link #isInsideColumn}, but against an absolute lower-corner and explicit height — see
     * {@link #distanceToColumnAbsolute}. */
    private static boolean isInsideColumnAbsolute(Vec3 point, BlockPos absoluteMinCorner, int heightBlocks) {
        return isInsideColumnAbsolute(point, absoluteMinCorner, heightBlocks, 1);
    }

    /** As the 3-argument overload, with an explicit horizontal width — see the matching
     * {@link #distanceToColumnAbsolute} overload. */
    private static boolean isInsideColumnAbsolute(Vec3 point, BlockPos absoluteMinCorner, int heightBlocks,
            int widthBlocks) {
        Vec3 min = Vec3.atLowerCornerOf(absoluteMinCorner);
        Vec3 max = min.add(widthBlocks, heightBlocks, widthBlocks);
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

    /** The judged point with the smallest (most negative, if any is inside) signed distance to
     * the column — see {@link #distanceToColumn}. */
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
     * MINECRAFT-127 criterion 3: pins {@link #distanceToColumn}/{@link #isInsideColumn} — the
     * metric {@code catchOnObstruction} and {@code tunnellingThreshold} both read their
     * {@code closest}/{@code clipped} results from — against hand-computed geometry, independent
     * of Sable, the solver, or any rope at all. No physics system needed (unlike an earlier,
     * abandoned attempt at a plain-JUnit {@code RopeMathTest}; see docs/rope-core.md section 1.5
     * for why that approach hit a dead end on this project's test-runner setup), so this runs as an
     * ordinary GameTest against the minimal {@code lifecycle} structure, needing only a column of
     * blocks this method places itself.
     *
     * <p>Three cases, each a point placed at an exact, independently-computed offset from a
     * 1x4x1 column's known AABB: (a) 1.3 blocks outside the column on the x axis — must report a
     * POSITIVE distance equal to 1.3 and {@code isInsideColumn=false}; (b) exactly on the column's
     * +x face — must report distance 0.0 (not inside, not outside) and {@code isInsideColumn=false}
     * (the boundary itself is excluded by design — see that method's strict inequalities); (c) 0.1
     * blocks inside the column from its +x face (and further than that from every other face) —
     * the actual defect-3 case: must report a NEGATIVE distance (penetration depth, -0.1) and
     * {@code isInsideColumn=true}. Case (c) is the one the old unsigned metric could not pass: it
     * clamped every inside case to exactly 0.0, indistinguishable from case (b)'s genuine flush
     * contact — which is precisely the "closest=0.0 while clipped=true" signature MINECRAFT-127
     * was filed to chase down. This assertion fails immediately if that collapse regresses.
     */
    @GameTest(template = "lifecycle", timeoutTicks = 20)
    public static void closestDistanceMetricIsDefensible(GameTestHelper helper) {
        BlockPos columnBase = new BlockPos(2, 1, 2);
        int topY = 4;
        for (int y = columnBase.getY(); y <= topY; y++) {
            helper.setBlock(new BlockPos(columnBase.getX(), y, columnBase.getZ()), Blocks.STONE);
        }
        Vec3 min = Vec3.atLowerCornerOf(helper.absolutePos(columnBase));

        // (a) 1.3 blocks outside the -x face, centered on the column in y and z so only the x axis
        // contributes: a pure, hand-computable Euclidean distance.
        Vec3 outside = new Vec3(min.x - 1.3, min.y + 1.5, min.z + 0.5);
        double outsideDistance = distanceToColumn(outside, columnBase, topY, helper);
        helper.assertTrue(Math.abs(outsideDistance - 1.3) < 1.0e-6,
                "hand-computed case (a): expected distance 1.3 for a point 1.3 blocks outside the -x"
                        + " face, got " + outsideDistance);
        helper.assertFalse(isInsideColumn(outside, columnBase, topY, helper),
                "hand-computed case (a): a point 1.3 blocks outside the column must not read as inside");

        // (b) exactly on the +x face: distance must be bit-exact 0.0, and NOT "inside" (the
        // boundary itself is excluded — isInsideColumn's own strict inequalities).
        Vec3 onFace = new Vec3(min.x + 1.0, min.y + 1.5, min.z + 0.5);
        double onFaceDistance = distanceToColumn(onFace, columnBase, topY, helper);
        helper.assertTrue(onFaceDistance == 0.0,
                "hand-computed case (b): expected bit-exact 0.0 for a point exactly on the +x face,"
                        + " got " + onFaceDistance);
        helper.assertFalse(isInsideColumn(onFace, columnBase, topY, helper),
                "hand-computed case (b): a point exactly on the column's face must not read as inside");

        // (c) THE DEFECT-3 CASE: 0.1 blocks inside the +x face (and comfortably further than that
        // from every other face), so the nearest exit is the +x face at depth 0.1. Must report a
        // NEGATIVE, non-zero distance — this is exactly what the old unsigned/clamped-at-0 metric
        // could not do, reporting 0.0 here indistinguishable from case (b)'s genuine flush contact.
        Vec3 penetrating = new Vec3(min.x + 0.9, min.y + 1.5, min.z + 0.5);
        double penetratingDistance = distanceToColumn(penetrating, columnBase, topY, helper);
        helper.assertTrue(penetratingDistance != 0.0,
                "hand-computed case (c): a point 0.1 blocks INSIDE the column must not report 0.0 —"
                        + " that is the exact closest=0.0-while-clipped=true collapse MINECRAFT-127 was"
                        + " filed over");
        helper.assertTrue(penetratingDistance < 0.0,
                "hand-computed case (c): a penetrating point must report a NEGATIVE distance"
                        + " (penetration depth), got " + penetratingDistance);
        helper.assertTrue(Math.abs(penetratingDistance - (-0.1)) < 1.0e-6,
                "hand-computed case (c): expected penetration depth -0.1 (0.1 blocks inside the +x"
                        + " face, the nearest exit), got " + penetratingDistance);
        helper.assertTrue(isInsideColumn(penetrating, columnBase, topY, helper),
                "hand-computed case (c): a point 0.1 blocks inside the column must read as inside");

        helper.succeed();
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
    // catchOnObstruction's comment and docs/rope-core.md section 1.6). This test's shipped-
    // spacing assertions below share catchOnObstruction's own rig and result, and that result is
    // nondeterministic (docs/rope-core.md sections 2 and 3) — neither test is a settled gate.
    // Left optional on its own merits instead: this method's real job is the diagnostic sweep
    // across spacings for criterion 5's still-open tunnelling-threshold question (docs/rope-core.md
    // section 3),
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
     * MINECRAFT-127 defect 2 (rig instability), hypothesis (b): docs/rope-core.md recorded the
     * test structure landing at |x| ~ 8.16e6 in a previous run and reasoned away, without testing,
     * whether float32's coarse ulp out there (~0.5 block at that magnitude) destabilizes the
     * unobstructed control rig's resting position — which has independently been measured at 7.11,
     * 0.50 and -3.76 blocks across four CI runs on identical code (docs/rope-core.md section 2).
     * This builds the SAME control-only (no post) rig geometry {@link #buildCatchRig} does, TWICE,
     * in the same test and the same run: once the ordinary way (wherever this structure actually
     * lands), and once at hardcoded absolute world coordinates near the origin (chunk force-loaded
     * explicitly with {@code ServerLevel#setChunkForced}, since nothing else guarantees a chunk
     * far from this structure's own bounding box is loaded) — same anchor-to-player geometry,
     * same slack, same gravity simulation, same tick count. If the near-origin copy's resting x is
     * stable across the 5 CI runs MINECRAFT-127 collects while the at-structure copy keeps
     * wandering the way section 2 recorded, that is real evidence for hypothesis (b). If both wander
     * by comparable amounts, that rules hypothesis (b) out as the explanation and points at
     * something tick-order- or solver-timing-related instead — see the [rope-core] log line this
     * emits either way. Required = false: this is a diagnostic measurement for the doc, not a gate.
     */
    @GameTest(template = "catch_on_obstruction", timeoutTicks = 200, required = false)
    public static void controlRigStabilityNearOriginVsAtStructure(GameTestHelper helper) {
        // At-structure copy: exactly buildCatchRig(helper, 0, false), the same control rig
        // catchOnObstruction itself builds, wherever this structure's StructureBlock actually
        // landed it (previously measured at |x| ~ 8.16e6 — docs/rope-core.md section 1.6). Only
        // bay 0 of this template is used; bay 1 (x0=9) is left untouched.
        CatchRig atStructure = buildCatchRig(helper, 0, false);

        // Near-origin copy: the IDENTICAL relative geometry (anchor at relative (1, 14, 4), player
        // at relative (7, 11, 4), slack 1.1 — see buildCatchRig), but built at hardcoded ABSOLUTE
        // world coordinates close to (0, 100, 0) instead of wherever the structure landed. Force-
        // load the chunk explicitly: this location is far outside the structure's own bounding
        // box, which is the only region the GameTest framework guarantees stays loaded.
        BlockPos originAnchorAbsolute = new BlockPos(1, 114, 4);
        BlockPos originPlayerSpawnAbsolute = new BlockPos(7, 111, 4);
        helper.getLevel().setChunkForced(originAnchorAbsolute.getX() >> 4, originAnchorAbsolute.getZ() >> 4, true);
        helper.getLevel().setBlock(originAnchorAbsolute, Blocks.STONE.defaultBlockState(), 3);
        Vec3 originAnchorPos = Vec3.atCenterOf(originAnchorAbsolute);
        ServerPlayer originPlayer = spawnMockPlayerAtAbsolute(helper, originPlayerSpawnAbsolute);
        simulateGravityEachTick(helper, originPlayer);
        UUID originRopeId = RopeManager.attachToPoint(originPlayer, originAnchorPos, originAnchorAbsolute, 1.1);
        helper.assertTrue(originRopeId != null, "near-origin rig: rope attach failed — no Sable physics"
                + " system at a force-loaded chunk far from this structure's own bounding box");

        helper.runAfterDelay(170, () -> {
            double atStructureRestingX = atStructure.player().position().x - atStructure.bayOriginX();
            double originRestingX = originPlayer.position().x - originAnchorAbsolute.getX();
            LOGGER.info("[rope-core] controlRigStabilityNearOriginVsAtStructure: atStructureX={}"
                            + " (structure origin absolute x={}) originX={} (near-origin anchor absolute x={})",
                    atStructureRestingX, atStructure.bayOriginX(), originRestingX, originAnchorAbsolute.getX());
            helper.getLevel().setChunkForced(originAnchorAbsolute.getX() >> 4, originAnchorAbsolute.getZ() >> 4,
                    false);
            helper.succeed();
        });
    }

    /**
     * MINECRAFT-127 PR #8 review (round 2): round 1's near-origin with-post probe's deterministic
     * clip is also consistent with a DIFFERENT explanation the review correctly named — the post
     * at a {@code setChunkForced} location might simply be an unregistered collider (never
     * uploaded to Rapier as world geometry at all), in which case the rope free-falls along a
     * purely kinematic path that happens to sit inside the post's space, deterministically, for
     * reasons that have nothing to do with tunnelling. The frozen check rules out "never stepped
     * at all" but NOT this: a rope moved by gravity/kinematics alone, never touching the post
     * collider, also reports {@code frozen=false}.
     *
     * <p>Two more measurements, same run, settle this: (1) a near-origin CONTROL (no post, offset
     * one bay over) — if the post is a real, engaged collider, {@code originPlayerX} and
     * {@code originControlPlayerX} should differ measurably (the same {@code > 1.0} threshold
     * {@code catchOnObstruction} itself uses), the same way the at-structure with-post/control
     * comparison already does; if the post were never actually colliding, the with-post and
     * control runs would be indistinguishable kinematically. (2) a near-origin POSITIVE CONTROL —
     * a 3x3 (not 1x1) stone wall, unmissable by the classic per-step-exceeds-obstacle-thickness
     * tunnelling mechanism regardless of spacing, at a THIRD near-origin location — if blocks
     * collide with the rope at all at a force-loaded chunk, this must catch cleanly; if even this
     * fails to catch, that is strong evidence against world colliders being registered at
     * {@code setChunkForced} locations at all, which would retract 10.1b's conclusion rather than
     * confirm it.
     */
    @GameTest(template = "catch_on_obstruction", timeoutTicks = 200, required = false)
    public static void postRigStabilityNearOriginVsAtStructure(GameTestHelper helper) {
        CatchRig atStructure = buildCatchRig(helper, 0, true);

        // Near-origin WITH-POST copy: anchor at relative (1, 14, 4), post at relative (4, 1..13, 4),
        // player at relative (7, 11, 4) — buildCatchRig's own geometry — translated to hardcoded
        // absolute coordinates near (0, 100, 100) instead of wherever the structure landed. z=104
        // (not the z=4 controlRigStabilityNearOriginVsAtStructure uses) keeps the two tests'
        // force-loaded chunks from ever touching in the same run. Both chunks this test's full
        // x-range (1..25) spans are force-loaded up front, once, below.
        int originZ = 104;
        helper.getLevel().setChunkForced(0, originZ >> 4, true);
        helper.getLevel().setChunkForced(1, originZ >> 4, true);

        BlockPos originAnchorAbsolute = new BlockPos(1, 114, originZ);
        BlockPos originPostBaseAbsolute = new BlockPos(4, 101, originZ);
        int originPostHeight = POST_TOP_Y - 1 + 1; // same height buildCatchRig's post uses (1..POST_TOP_Y)
        BlockPos originPlayerSpawnAbsolute = new BlockPos(7, 111, originZ);
        helper.getLevel().setBlock(originAnchorAbsolute, Blocks.STONE.defaultBlockState(), 3);
        for (int y = originPostBaseAbsolute.getY(); y < originPostBaseAbsolute.getY() + originPostHeight; y++) {
            helper.getLevel().setBlock(new BlockPos(originPostBaseAbsolute.getX(), y, originPostBaseAbsolute.getZ()),
                    Blocks.STONE.defaultBlockState(), 3);
        }
        Vec3 originAnchorPos = Vec3.atCenterOf(originAnchorAbsolute);
        ServerPlayer originPlayer = spawnMockPlayerAtAbsolute(helper, originPlayerSpawnAbsolute);
        simulateGravityEachTick(helper, originPlayer);
        UUID originRopeId = RopeManager.attachToPoint(originPlayer, originAnchorPos, originAnchorAbsolute, 1.1);
        helper.assertTrue(originRopeId != null, "near-origin with-post rig: rope attach failed");
        // Frozen-rope regression canary (docs/rope-core.md section 1.6), applied to the NEAR-ORIGIN
        // copy specifically: chunk force-loading is not proven equivalent to however the GameTest
        // framework's own structure placement readies a chunk for Sable's physics, so a near-origin
        // clip must be checked against "did this rope's points move at all" before it is trusted as
        // a real tunnelling observation rather than a frozen-at-creation-layout artifact of this
        // test's own setup (a frozen rope's points sit at the creation-time straight-line layout,
        // which by the SAME geometry proof catchOnObstruction uses already intersects the post).
        List<Vector3d> originInitialPoints = new ArrayList<>();
        for (Vector3d p : RopeManager.get(originRopeId).points()) {
            originInitialPoints.add(new Vector3d(p));
        }

        // Near-origin CONTROL (no post), one bay (9 blocks) over: identical geometry otherwise.
        int controlX0 = 9;
        BlockPos originControlAnchorAbsolute = new BlockPos(controlX0 + 1, 114, originZ);
        BlockPos originControlPlayerSpawnAbsolute = new BlockPos(controlX0 + 7, 111, originZ);
        helper.getLevel().setBlock(originControlAnchorAbsolute, Blocks.STONE.defaultBlockState(), 3);
        Vec3 originControlAnchorPos = Vec3.atCenterOf(originControlAnchorAbsolute);
        ServerPlayer originControlPlayer = spawnMockPlayerAtAbsolute(helper, originControlPlayerSpawnAbsolute);
        simulateGravityEachTick(helper, originControlPlayer);
        UUID originControlRopeId = RopeManager.attachToPoint(originControlPlayer, originControlAnchorPos,
                originControlAnchorAbsolute, 1.1);
        helper.assertTrue(originControlRopeId != null, "near-origin control rig: rope attach failed");

        // Near-origin POSITIVE CONTROL, another bay over: the SAME anchor/player geometry, but a
        // 3x3 (x and z) wall instead of a 1x1 post — unmissable by the classic
        // per-step-exceeds-obstacle-thickness tunnelling mechanism regardless of spacing. If world
        // colliders are registered at a setChunkForced location at all, this must catch cleanly.
        int wallX0 = 18;
        BlockPos wallAnchorAbsolute = new BlockPos(wallX0 + 1, 114, originZ);
        BlockPos wallBaseAbsolute = new BlockPos(wallX0 + 4, 101, originZ);
        BlockPos wallPlayerSpawnAbsolute = new BlockPos(wallX0 + 7, 111, originZ);
        helper.getLevel().setBlock(wallAnchorAbsolute, Blocks.STONE.defaultBlockState(), 3);
        for (int y = wallBaseAbsolute.getY(); y < wallBaseAbsolute.getY() + originPostHeight; y++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    helper.getLevel().setBlock(
                            new BlockPos(wallBaseAbsolute.getX() + dx, y, wallBaseAbsolute.getZ() + dz),
                            Blocks.STONE.defaultBlockState(), 3);
                }
            }
        }
        Vec3 wallAnchorPos = Vec3.atCenterOf(wallAnchorAbsolute);
        ServerPlayer wallPlayer = spawnMockPlayerAtAbsolute(helper, wallPlayerSpawnAbsolute);
        simulateGravityEachTick(helper, wallPlayer);
        UUID wallRopeId = RopeManager.attachToPoint(wallPlayer, wallAnchorPos, wallAnchorAbsolute, 1.1);
        helper.assertTrue(wallRopeId != null, "near-origin positive-control rig: rope attach failed");

        helper.runAfterDelay(170, () -> {
            double atStructureClosest = closestPointToColumn(RopeManager.get(atStructure.ropeId()),
                    atStructure.postBase(), POST_TOP_Y, helper);
            boolean atStructureClipped = anyPointInsideColumn(RopeManager.get(atStructure.ropeId()),
                    atStructure.postBase(), POST_TOP_Y, helper);
            double atStructurePlayerX = atStructure.player().position().x - atStructure.bayOriginX();

            PlayerRope originRope = RopeManager.get(originRopeId);
            double originClosest = Double.MAX_VALUE;
            boolean originClipped = false;
            boolean originFrozen = false;
            if (originRope != null) {
                List<Vector3d> judged = collisionJudgedPoints(originRope);
                for (Vector3d p : judged) {
                    Vec3 abs = new Vec3(p.x, p.y, p.z);
                    originClosest = Math.min(originClosest,
                            distanceToColumnAbsolute(abs, originPostBaseAbsolute, originPostHeight));
                    originClipped = originClipped || isInsideColumnAbsolute(abs, originPostBaseAbsolute, originPostHeight);
                }
                List<Vector3d> originInitialJudged = originInitialPoints.size() < 2 ? originInitialPoints
                        : originInitialPoints.subList(0, originInitialPoints.size() - 1);
                originFrozen = pointsEffectivelyIdentical(originInitialJudged, judged);
            }
            double originPlayerX = originPlayer.position().x - originAnchorAbsolute.getX();
            double originControlPlayerX = originControlPlayer.position().x - originControlAnchorAbsolute.getX();

            // The wall's AABB is 3 wide on x and z (not the usual 1), so its min corner is shifted
            // -1 on both those axes from the center column wallBaseAbsolute names.
            BlockPos wallMin = new BlockPos(wallBaseAbsolute.getX() - 1, wallBaseAbsolute.getY(),
                    wallBaseAbsolute.getZ() - 1);
            PlayerRope wallRope = RopeManager.get(wallRopeId);
            boolean wallClipped = false;
            double wallClosest = Double.MAX_VALUE;
            if (wallRope != null) {
                for (Vector3d p : collisionJudgedPoints(wallRope)) {
                    Vec3 abs = new Vec3(p.x, p.y, p.z);
                    wallClosest = Math.min(wallClosest,
                            distanceToColumnAbsolute(abs, wallMin, originPostHeight, 3));
                    wallClipped = wallClipped || isInsideColumnAbsolute(abs, wallMin, originPostHeight, 3);
                }
            }
            double wallPlayerX = wallPlayer.position().x - wallAnchorAbsolute.getX();

            LOGGER.info("[rope-core] postRigStabilityNearOriginVsAtStructure: atStructureClosest={}"
                            + " atStructureClipped={} atStructurePlayerX={} originClosest={} originClipped={}"
                            + " originFrozen={} originControlPlayerX={} wallClipped={} wallClosest={}"
                            + " wallPlayerX={} originPlayerX={}",
                    atStructureClosest, atStructureClipped, atStructurePlayerX, originClosest, originClipped,
                    originFrozen, originControlPlayerX, wallClipped, wallClosest, wallPlayerX, originPlayerX);
            helper.assertFalse(originFrozen,
                    "near-origin with-post rig: every judged point is still at its creation-time layout 170"
                            + " ticks later -- this chunk-force-loaded setup is NOT stepping the rope at all, so"
                            + " originClipped/originClosest above are a frozen-layout artifact of THIS TEST, not a"
                            + " tunnelling observation (the creation-time layout intersects the post by the same"
                            + " geometry proof catchOnObstruction uses, so a frozen rope clips every time by"
                            + " construction) -- do not read this run's origin numbers as physics evidence if this"
                            + " fires");
            // PR #8 review round 2: rules out "the post is an unregistered collider at this
            // force-loaded location, and the rope's kinematic path just happens to sit inside its
            // space." If the post were never actually colliding, with-post and control would be
            // kinematically indistinguishable; a measurable difference means the post IS engaging.
            helper.assertTrue(Math.abs(originPlayerX - originControlPlayerX) > 1.0,
                    "near-origin with-post vs near-origin control made no measurable difference (with-post x="
                            + originPlayerX + ", control x=" + originControlPlayerX + ") -- consistent with the"
                            + " post never actually colliding at this force-loaded location at all, which would"
                            + " retract this test's tunnelling finding rather than confirm it");
            // The positive control: an unmissable 3x3 wall must catch cleanly if world colliders
            // are registered at a setChunkForced location at all. A clip here means even an
            // obstacle no per-step-distance argument could explain away still tunnels -- itself
            // worth knowing; a miss (not clipped, but closest far from the surface) would instead
            // point at the force-loaded chunk not registering colliders at all.
            helper.assertFalse(wallClipped,
                    "near-origin POSITIVE CONTROL (3x3 wall, unmissable by spacing/per-step arguments) was"
                            + " tunnelled through -- wallClosest=" + wallClosest + ", wallPlayerX=" + wallPlayerX);
            helper.getLevel().setChunkForced(0, originZ >> 4, false);
            helper.getLevel().setChunkForced(1, originZ >> 4, false);
            helper.succeed();
        });
    }

    /**
     * MINECRAFT-155 criterion 1 (10.1c's own named next step): does the near-origin post/wall
     * penetration depth measured at a single fixed 170-tick sample (10.1b/10.1c) keep shrinking
     * toward zero as more ticks are given to resolve, or does it plateau at the SAME nonzero depth
     * regardless of tick count? The first is slow-but-genuine contact resolution (not a permanent
     * tunnelling defect — a resolution-speed finding with real but different gameplay implications);
     * the second is a genuine, permanent tunnelling defect. Reuses
     * {@link #postRigStabilityNearOriginVsAtStructure}'s near-origin WITH-POST and near-origin
     * POSITIVE CONTROL (3x3 wall) rig geometry verbatim (same relative anchor/post/player offsets,
     * same slack) so these numbers are directly comparable to 10.1b/10.1c's own 170-tick sample,
     * and samples BOTH rigs' penetration depth at 50, 100, 170, 300 and 500 ticks in the SAME run
     * rather than rebuilding the rig per sample (which would reintroduce exactly the placement-noise
     * confound section 10.1 diagnosed). Required = false: this is the diagnostic experiment the doc
     * reports, not a new gate.
     */
    @GameTest(template = "catch_on_obstruction", timeoutTicks = 550, required = false)
    public static void tickCountVsPenetrationDepth(GameTestHelper helper) {
        int originZ = 304;
        helper.getLevel().setChunkForced(0, originZ >> 4, true);
        helper.getLevel().setChunkForced(1, originZ >> 4, true);

        BlockPos postAnchorAbsolute = new BlockPos(1, 114, originZ);
        BlockPos postBaseAbsolute = new BlockPos(4, 101, originZ);
        int columnHeight = POST_TOP_Y;
        BlockPos postPlayerSpawnAbsolute = new BlockPos(7, 111, originZ);
        helper.getLevel().setBlock(postAnchorAbsolute, Blocks.STONE.defaultBlockState(), 3);
        for (int y = postBaseAbsolute.getY(); y < postBaseAbsolute.getY() + columnHeight; y++) {
            helper.getLevel().setBlock(new BlockPos(postBaseAbsolute.getX(), y, postBaseAbsolute.getZ()),
                    Blocks.STONE.defaultBlockState(), 3);
        }
        ServerPlayer postPlayer = spawnMockPlayerAtAbsolute(helper, postPlayerSpawnAbsolute);
        simulateGravityEachTick(helper, postPlayer);
        UUID postRopeId = RopeManager.attachToPoint(postPlayer, Vec3.atCenterOf(postAnchorAbsolute),
                postAnchorAbsolute, 1.1);
        helper.assertTrue(postRopeId != null, "tick-sweep post rig: rope attach failed");

        int wallX0 = 18;
        BlockPos wallAnchorAbsolute = new BlockPos(wallX0 + 1, 114, originZ);
        BlockPos wallBaseAbsolute = new BlockPos(wallX0 + 4, 101, originZ);
        BlockPos wallPlayerSpawnAbsolute = new BlockPos(wallX0 + 7, 111, originZ);
        helper.getLevel().setBlock(wallAnchorAbsolute, Blocks.STONE.defaultBlockState(), 3);
        for (int y = wallBaseAbsolute.getY(); y < wallBaseAbsolute.getY() + columnHeight; y++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    helper.getLevel().setBlock(
                            new BlockPos(wallBaseAbsolute.getX() + dx, y, wallBaseAbsolute.getZ() + dz),
                            Blocks.STONE.defaultBlockState(), 3);
                }
            }
        }
        ServerPlayer wallPlayer = spawnMockPlayerAtAbsolute(helper, wallPlayerSpawnAbsolute);
        simulateGravityEachTick(helper, wallPlayer);
        UUID wallRopeId = RopeManager.attachToPoint(wallPlayer, Vec3.atCenterOf(wallAnchorAbsolute),
                wallAnchorAbsolute, 1.1);
        helper.assertTrue(wallRopeId != null, "tick-sweep wall rig: rope attach failed");

        BlockPos wallMin = new BlockPos(wallBaseAbsolute.getX() - 1, wallBaseAbsolute.getY(),
                wallBaseAbsolute.getZ() - 1);

        int[] tickCounts = {50, 100, 170, 300, 500};
        StringBuilder series = new StringBuilder("tick-count-vs-penetration-depth sweep: ");
        for (int tickCount : tickCounts) {
            helper.runAfterDelay(tickCount, () -> {
                PlayerRope postRope = RopeManager.get(postRopeId);
                PlayerRope wallRope = RopeManager.get(wallRopeId);
                double postDepth = Double.NaN;
                double wallDepth = Double.NaN;
                if (postRope != null) {
                    postDepth = Double.MAX_VALUE;
                    for (Vector3d p : collisionJudgedPoints(postRope)) {
                        postDepth = Math.min(postDepth,
                                distanceToColumnAbsolute(new Vec3(p.x, p.y, p.z), postBaseAbsolute, columnHeight));
                    }
                }
                if (wallRope != null) {
                    wallDepth = Double.MAX_VALUE;
                    for (Vector3d p : collisionJudgedPoints(wallRope)) {
                        wallDepth = Math.min(wallDepth,
                                distanceToColumnAbsolute(new Vec3(p.x, p.y, p.z), wallMin, columnHeight, 3));
                    }
                }
                LOGGER.info("[rope-core] tickCountVsPenetrationDepth @ t={}: postDepth={} wallDepth={}"
                                + " (postRopeActive={} wallRopeActive={})",
                        tickCount, postDepth, wallDepth, postRope != null, wallRope != null);
                series.append("t=").append(tickCount).append(" postDepth=").append(postDepth)
                        .append(" wallDepth=").append(wallDepth).append("; ");
                if (tickCount == tickCounts[tickCounts.length - 1]) {
                    LOGGER.info("[rope-core] {}", series);
                    helper.getLevel().setChunkForced(0, originZ >> 4, false);
                    helper.getLevel().setChunkForced(1, originZ >> 4, false);
                    helper.succeed();
                }
            });
        }
    }

    /**
     * Criterion 5 negative control: {@link #tunnellingThreshold}'s existing sweep (sections 3 and
     * 10.5) cannot tell "the rope geometrically MISSED the post at this spacing" apart from "the
     * rope TUNNELLED through it" — both read as {@code clipped=false, caught=false}, and section 3
     * already recorded exactly this kind of ambiguous miss (closest equal to the bay's own
     * spacing, i.e. the rope resting at its own unbent layout rather than actually reaching the
     * post). This builds, for the SAME three spacings {@link #tunnellingThreshold} sweeps, a
     * companion rig at each spacing with a 3x3 wall (10.1c's own unmissable positive control)
     * instead of the usual 1-wide post, same near-origin/force-loaded-chunk pattern as
     * {@link #postRigStabilityNearOriginVsAtStructure}. The wall occupies every (x,z) the matching
     * 1-wide post's own column does, plus its immediate neighbors, so any straight-line path proven
     * (by the same geometry argument {@code catchOnObstruction} uses) to cross the post's column
     * necessarily crosses the wall's wider AABB too, at every one of the three spacings — spacing
     * only changes how finely the rope's points subdivide that line, never whether the line itself
     * intersects the column. A wall that catches cleanly at a spacing where the matching narrow post
     * was MISSED (not clipped, closest far from the surface) means that spacing genuinely missed the
     * post geometrically — not evidence of tunnelling at that spacing. A narrow post that clips
     * while its matching wall catches cleanly is the opposite, clean case: a real tunnel at that
     * spacing. A wall that itself clips is a different finding (contact resolution, same ambiguity
     * 10.1c already raised), reported as such rather than folded into the spacing question.
     * Required = false: diagnostic sweep, not a gate.
     */
    @GameTest(template = "catch_on_obstruction", timeoutTicks = 200, required = false)
    public static void tunnellingThresholdNegativeControl(GameTestHelper helper) {
        double[] spacings = {
                RopeConstants.SEGMENT_SPACING,
                RopeConstants.SEGMENT_SPACING * 2,
                RopeConstants.SEGMENT_SPACING * 4,
        };
        int bayWidth = 9;
        int originZ = 404;
        int maxChunkX = (spacings.length * 2 * bayWidth) >> 4;
        for (int cx = 0; cx <= maxChunkX; cx++) {
            helper.getLevel().setChunkForced(cx, originZ >> 4, true);
        }

        UUID[] postRopeIds = new UUID[spacings.length];
        UUID[] wallRopeIds = new UUID[spacings.length];
        BlockPos[] postBases = new BlockPos[spacings.length];
        BlockPos[] wallMins = new BlockPos[spacings.length];

        for (int i = 0; i < spacings.length; i++) {
            int postX0 = i * 2 * bayWidth;
            BlockPos postAnchorAbsolute = new BlockPos(postX0 + 1, 114, originZ);
            BlockPos postBaseAbsolute = new BlockPos(postX0 + 4, 101, originZ);
            BlockPos postPlayerSpawnAbsolute = new BlockPos(postX0 + 7, 111, originZ);
            helper.getLevel().setBlock(postAnchorAbsolute, Blocks.STONE.defaultBlockState(), 3);
            for (int y = postBaseAbsolute.getY(); y < postBaseAbsolute.getY() + POST_TOP_Y; y++) {
                helper.getLevel().setBlock(new BlockPos(postBaseAbsolute.getX(), y, postBaseAbsolute.getZ()),
                        Blocks.STONE.defaultBlockState(), 3);
            }
            ServerPlayer postPlayer = spawnMockPlayerAtAbsolute(helper, postPlayerSpawnAbsolute);
            simulateGravityEachTick(helper, postPlayer);
            UUID postRopeId = RopeManager.attachToPointWithSpacing(postPlayer, Vec3.atCenterOf(postAnchorAbsolute),
                    postAnchorAbsolute, 1.1, spacings[i]);
            helper.assertTrue(postRopeId != null, "negative-control sweep: post rig " + i + " attach failed");

            int wallX0 = postX0 + bayWidth;
            BlockPos wallAnchorAbsolute = new BlockPos(wallX0 + 1, 114, originZ);
            BlockPos wallBaseAbsolute = new BlockPos(wallX0 + 4, 101, originZ);
            BlockPos wallPlayerSpawnAbsolute = new BlockPos(wallX0 + 7, 111, originZ);
            helper.getLevel().setBlock(wallAnchorAbsolute, Blocks.STONE.defaultBlockState(), 3);
            for (int y = wallBaseAbsolute.getY(); y < wallBaseAbsolute.getY() + POST_TOP_Y; y++) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        helper.getLevel().setBlock(
                                new BlockPos(wallBaseAbsolute.getX() + dx, y, wallBaseAbsolute.getZ() + dz),
                                Blocks.STONE.defaultBlockState(), 3);
                    }
                }
            }
            ServerPlayer wallPlayer = spawnMockPlayerAtAbsolute(helper, wallPlayerSpawnAbsolute);
            simulateGravityEachTick(helper, wallPlayer);
            UUID wallRopeId = RopeManager.attachToPointWithSpacing(wallPlayer, Vec3.atCenterOf(wallAnchorAbsolute),
                    wallAnchorAbsolute, 1.1, spacings[i]);
            helper.assertTrue(wallRopeId != null, "negative-control sweep: wall rig " + i + " attach failed");

            postRopeIds[i] = postRopeId;
            wallRopeIds[i] = wallRopeId;
            postBases[i] = postBaseAbsolute;
            wallMins[i] = new BlockPos(wallBaseAbsolute.getX() - 1, wallBaseAbsolute.getY(),
                    wallBaseAbsolute.getZ() - 1);
        }

        helper.runAfterDelay(170, () -> {
            StringBuilder report = new StringBuilder("tunnelling-threshold negative-control sweep: ");
            for (int i = 0; i < spacings.length; i++) {
                PlayerRope postRope = RopeManager.get(postRopeIds[i]);
                PlayerRope wallRope = RopeManager.get(wallRopeIds[i]);
                double postClosest = Double.MAX_VALUE;
                boolean postClipped = false;
                if (postRope != null) {
                    for (Vector3d p : collisionJudgedPoints(postRope)) {
                        Vec3 abs = new Vec3(p.x, p.y, p.z);
                        postClosest = Math.min(postClosest, distanceToColumnAbsolute(abs, postBases[i], POST_TOP_Y));
                        postClipped = postClipped || isInsideColumnAbsolute(abs, postBases[i], POST_TOP_Y);
                    }
                }
                double wallClosest = Double.MAX_VALUE;
                boolean wallClipped = false;
                if (wallRope != null) {
                    for (Vector3d p : collisionJudgedPoints(wallRope)) {
                        Vec3 abs = new Vec3(p.x, p.y, p.z);
                        wallClosest = Math.min(wallClosest, distanceToColumnAbsolute(abs, wallMins[i], POST_TOP_Y, 3));
                        wallClipped = wallClipped || isInsideColumnAbsolute(abs, wallMins[i], POST_TOP_Y, 3);
                    }
                }
                boolean postCaught = !postClipped && postClosest <= RopeConstants.COLLISION_RADIUS * 2;
                boolean wallCaught = !wallClipped && wallClosest <= RopeConstants.COLLISION_RADIUS * 2;
                report.append("spacing=").append(spacings[i])
                        .append(" postClipped=").append(postClipped).append(" postClosest=").append(postClosest)
                        .append(" postCaught=").append(postCaught)
                        .append(" wallClipped=").append(wallClipped).append(" wallClosest=").append(wallClosest)
                        .append(" wallCaught=").append(wallCaught).append("; ");
            }
            LOGGER.info("[rope-core] {}", report);
            for (int cx = 0; cx <= maxChunkX; cx++) {
                helper.getLevel().setChunkForced(cx, originZ >> 4, false);
            }
            helper.succeed();
        });
    }

    /**
     * MINECRAFT-127 PR #8 review: the same near-origin-vs-at-structure A/B
     * {@link #controlRigStabilityNearOriginVsAtStructure} ran for the catch rig, but for
     * {@code fallArrestSwing}'s own geometry (addendum criterion 1/4 — run 37967852128's flake),
     * so that flake's stability can be examined directly instead of only noting it "did not
     * reproduce." Builds {@code fallArrestSwing}'s exact relative geometry (anchor 3 up and across
     * a shaft, player spawn offset sideways, slack 1.2) both at this structure's own placement and
     * at hardcoded near-origin coordinates, and logs each copy's final distance-from-anchor and
     * fall-from-spawn after the same 160-tick delay {@code fallArrestSwing} itself uses. Required
     * = false: diagnostic measurement, not a gate — {@code fallArrestSwing} itself is still the
     * real, required assertion on this behaviour.
     *
     * <p>MINECRAFT-155 found and fixed the actual bug docs/rope-core.md section 10.4 flagged
     * (constant {@code atStructureDistance=10.688779163215974}/{@code atStructureFall=9.0} every
     * run, matching an unarrested free fall to the shaft's floor safety net): the at-structure
     * rig's call to {@link RopeManager#attachToPoint} passed the STRUCTURE-RELATIVE
     * {@code atStructureAnchorBlock} as the anchor-tracking {@code BlockPos} instead of
     * {@code helper.absolutePos(atStructureAnchorBlock)} — every other anchor BlockPos in this
     * file (e.g. {@code fallArrestSwing}'s own, {@code buildCatchRig}'s) is translated through
     * {@code helper.absolutePos} first. {@link RopeAnchor.WorldPoint#isGone} reads
     * {@code level.getBlockState(anchorBlock)} at that BlockPos directly, with no translation of
     * its own; a bare relative BlockPos at this structure's actual (far, random — see 10.1) world
     * location is essentially always air, so {@code isGone} returned true on the very first tick
     * and {@link RopeManager#tickAll} tore the rope down immediately — the player then fell,
     * completely unarrested, straight to the floor safety net, which is exactly the constant
     * ~9-block fall and ~10.69 anchor distance 10.4 recorded. 10.4's own hypothesis (two
     * {@code fall_arrest_swing} template instances interacting) was never the cause; the bug was
     * local to this probe's own call, not a cross-rig interaction, and nothing about
     * {@code fallArrestSwing} itself (which already used {@code helper.absolutePos} correctly) was
     * ever wrong. See docs/rope-core.md section 10.7 for the post-fix numbers.
     */
    @GameTest(template = "fall_arrest_swing", timeoutTicks = 200, required = false)
    public static void fallArrestStabilityNearOriginVsAtStructure(GameTestHelper helper) {
        BlockPos atStructureAnchorBlock = new BlockPos(3, 12, 3);
        helper.setBlock(atStructureAnchorBlock, Blocks.STONE);
        Vec3 atStructureAnchorPos = Vec3.atCenterOf(helper.absolutePos(atStructureAnchorBlock));
        ServerPlayer atStructurePlayer = spawnMockPlayer(helper, new BlockPos(3, 11, 1));
        simulateGravityEachTick(helper, atStructurePlayer);
        double atStructureSpawnY = atStructurePlayer.position().y;
        UUID atStructureRopeId = RopeManager.attachToPoint(atStructurePlayer, atStructureAnchorPos,
                helper.absolutePos(atStructureAnchorBlock), 1.2);
        helper.assertTrue(atStructureRopeId != null, "at-structure fall-arrest rig: rope attach failed");

        // Near-origin copy of the IDENTICAL relative geometry, offset in z from both catch-rig
        // probes above so none of the three tests' force-loaded chunks ever overlap in one run.
        int originZ = 204;
        BlockPos originAnchorAbsolute = new BlockPos(3, 112, originZ);
        BlockPos originPlayerSpawnAbsolute = new BlockPos(3, 111, originZ - 2);
        helper.getLevel().setChunkForced(originAnchorAbsolute.getX() >> 4, originAnchorAbsolute.getZ() >> 4, true);
        helper.getLevel().setBlock(originAnchorAbsolute, Blocks.STONE.defaultBlockState(), 3);
        Vec3 originAnchorPos = Vec3.atCenterOf(originAnchorAbsolute);
        ServerPlayer originPlayer = spawnMockPlayerAtAbsolute(helper, originPlayerSpawnAbsolute);
        simulateGravityEachTick(helper, originPlayer);
        double originSpawnY = originPlayer.position().y;
        UUID originRopeId = RopeManager.attachToPoint(originPlayer, originAnchorPos, originAnchorAbsolute, 1.2);
        helper.assertTrue(originRopeId != null, "near-origin fall-arrest rig: rope attach failed");

        helper.runAfterDelay(160, () -> {
            double atStructureDistance = atStructureAnchorPos.distanceTo(atStructurePlayer.position());
            double atStructureFall = atStructureSpawnY - atStructurePlayer.position().y;
            double originDistance = originAnchorPos.distanceTo(originPlayer.position());
            double originFall = originSpawnY - originPlayer.position().y;
            LOGGER.info("[rope-core] fallArrestStabilityNearOriginVsAtStructure: atStructureDistance={}"
                            + " atStructureFall={} originDistance={} originFall={}",
                    atStructureDistance, atStructureFall, originDistance, originFall);
            helper.getLevel().setChunkForced(originAnchorAbsolute.getX() >> 4, originAnchorAbsolute.getZ() >> 4,
                    false);
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

    // spawnMockPlayer / simulateGravityEachTick moved to GameTestSupport (MINECRAFT-86): the
    // whip's own WhipGameTests needs the identical mock-player setup, so this shares one copy
    // instead of duplicating it. See GameTestSupport's javadoc for the full rationale.

    // ================================================================================
    // MINECRAFT-190: payOut/reelIn actually move the player for a world-point anchor (bug 1), no
    // per-tick Rapier native panic (bug 2), and a 64-block obstructed reel-in via adjustLength no
    // longer tears the rope down (bug 5). See PlayerRope#payOut/#reelIn/#adjustLength/#tick for
    // the fix itself and docs/rope-core.md section 11 for the full writeup.
    // ================================================================================

    /**
     * Bug 1. MINECRAFT-179's own CI measured this exact failure: a nominal rest length climbing
     * cleanly toward a tier cap while the player's REAL distance from the anchor stayed pinned
     * near its starting value (Iron 16.1 nominal vs 2.69 real; Netherite 63.1 nominal vs 2.59
     * real). This builds a plain, unobstructed vertical hang — anchor fixed above, player
     * directly below, no obstruction at all, same shaft {@code fall_arrest_swing} uses for room
     * to fall — then calls {@code RopeManager#payOut} once a tick for 100 ticks (20x {@link
     * RopeConstants#STRUCTURAL_COMMIT_INTERVAL_TICKS}, so several real structural commits happen,
     * not just the one immediately-physical stub growth) and asserts the player's MEASURED
     * distance from the anchor — not {@code RopeManager.length()}'s own nominal number, which
     * already climbed correctly even before this fix — grows well past its starting value.
     *
     * <p>{@code required = true} (PR #16 review): reliable across this PR's own CI runs with no
     * physics-timing dependency on an obstruction ever resolving one way or another (unlike
     * {@code catchOnObstruction}'s own documented nondeterminism) — a plain, deterministic
     * vertical fall under a fixed throttle cadence.
     */
    @GameTest(template = "fall_arrest_swing", timeoutTicks = 180, required = true)
    public static void payOutGrowsAllowedRadiusForUnobstructedHang(GameTestHelper helper) {
        BlockPos anchorBlock = new BlockPos(3, 12, 3);
        helper.setBlock(anchorBlock, Blocks.STONE);
        Vec3 anchorPos = Vec3.atCenterOf(helper.absolutePos(anchorBlock));

        BlockPos playerSpawn = new BlockPos(3, 11, 3);
        ServerPlayer player = spawnMockPlayer(helper, playerSpawn);
        simulateGravityEachTick(helper, player);

        double slack = 1.0;
        UUID ropeId = RopeManager.attachToPoint(player, anchorPos, helper.absolutePos(anchorBlock), slack);
        helper.assertTrue(ropeId != null, "rope attach failed: no Sable physics system in the game test level");

        double initialRestLength = RopeManager.length(ropeId);
        double initialDistance = anchorPos.distanceTo(player.position());

        int payOutCalls = 100;
        for (int i = 1; i <= payOutCalls; i++) {
            helper.runAfterDelay(i, () -> RopeManager.payOut(ropeId));
        }

        helper.runAfterDelay(payOutCalls + 40, () -> {
            PlayerRope rope = RopeManager.get(ropeId);
            helper.assertTrue(rope != null, "rope was torn down during repeated payOut calls");
            double restLength = rope.restLength();
            double distance = anchorPos.distanceTo(player.position());
            LOGGER.info("[rope-core] payOutGrowsAllowedRadiusForUnobstructedHang: initialRestLength={}"
                            + " finalRestLength={} initialDistance={} finalDistance={}",
                    initialRestLength, restLength, initialDistance, distance);
            helper.assertTrue(restLength > initialRestLength + 5.0,
                    "restLength did not grow as expected from 100 payOut calls: initial=" + initialRestLength
                            + " final=" + restLength);
            // THE bug-1 assertion: the player's REAL distance must follow the nominal growth, not
            // stay pinned near its starting value the way MINECRAFT-189 bug 1 measured.
            helper.assertTrue(distance > initialDistance + 3.0,
                    "payOut grew the nominal restLength (" + restLength + ") but the player's real distance"
                            + " from the anchor did not follow (initial=" + initialDistance + ", final=" + distance
                            + ") -- this is MINECRAFT-189 bug 1");
            helper.succeed();
        });
    }

    /**
     * Bug 2. MINECRAFT-179's CI crashed Sable's native Rapier layer entirely ({@code
     * RuntimeException: Rapier native panic: index out of bounds: the len is 8 but the index is
     * 9}) calling {@code RopeManager.payOut}/{@code reelIn} once every server tick; the only known
     * mitigation on record was a caller-side 5-tick throttle. This test is the literal repro: it
     * calls {@code RopeManager#payOut} EVERY SINGLE TICK (no throttle at this call site at all)
     * for 200 ticks, then {@code reelIn} every single tick for 200 more — if the fix did not
     * actually move the structural native mutation's own pacing inside {@code PlayerRope} itself
     * (see {@link RopeConstants#STRUCTURAL_COMMIT_INTERVAL_TICKS} and {@code
     * PlayerRope#commitPendingSegment}), this is expected to crash the whole GameTest server
     * exactly as it did before, not just fail this one test's own assertion.
     *
     * <p>{@code required = true} (PR #16 review): a native crash takes down the whole GameTest
     * server regardless of this flag, so there is no meaningful "optional" version of this test —
     * either every required test in the run fails too (crash) or this one passes; it is never a
     * source of its own, independent flakiness the way an obstruction-timing test can be.
     */
    @GameTest(template = "lifecycle", timeoutTicks = 440, required = true)
    public static void perTickPayOutAndReelInDoNotPanic(GameTestHelper helper) {
        ServerPlayer player = spawnMockPlayer(helper, new BlockPos(2, 5, 2));
        BlockPos anchorBlock = new BlockPos(2, 6, 2);
        helper.setBlock(anchorBlock, Blocks.STONE);
        UUID ropeId = RopeManager.attachToPoint(player, Vec3.atCenterOf(helper.absolutePos(anchorBlock)),
                helper.absolutePos(anchorBlock), 1.0);
        helper.assertTrue(ropeId != null, "rope attach failed");

        int phaseTicks = 200;
        for (int i = 1; i <= phaseTicks; i++) {
            helper.runAfterDelay(i, () -> RopeManager.payOut(ropeId));
        }
        for (int i = 1; i <= phaseTicks; i++) {
            helper.runAfterDelay(phaseTicks + i, () -> RopeManager.reelIn(ropeId));
        }

        helper.runAfterDelay(2 * phaseTicks + 20, () -> {
            PlayerRope rope = RopeManager.get(ropeId);
            double restLength = rope != null ? rope.restLength() : Double.NaN;
            LOGGER.info("[rope-core] perTickPayOutAndReelInDoNotPanic: survived {} ticks of payOut then {}"
                            + " ticks of reelIn, every single tick, no caller throttle; rope alive={} finalRestLength={}",
                    phaseTicks, phaseTicks, rope != null, restLength);
            helper.assertTrue(rope != null,
                    "rope was torn down after " + (2 * phaseTicks) + " ticks of payOut/reelIn called every"
                            + " single tick with no caller-side throttle -- MINECRAFT-189 bug 2. (A genuine native"
                            + " panic would have killed the whole GameTest server well before this assertion runs,"
                            + " not merely failed it.)");
            helper.succeed();
        });
    }

    /**
     * PR #16 review, round 1. The first version of the bug-5 obstruction guard
     * ({@code PlayerRope#ropeIsNearSolidObstruction}, since replaced) deferred every reel-in
     * commit while ANY chain point was within one block of ANY solid, motion-blocking block —
     * which would stall reel-in for a rope simply resting on or brushing the ground, the ORDINARY
     * resting state for a grappling hook, not an obstruction in any useful sense. The replacement
     * ({@code PlayerRope#ropeIsBentOnObstruction}) only looks at whether {@code findPivotIndex}
     * found a genuine interior bend. This test is the regression guard for that distinction: a
     * plain vertical hang settling just above a solid floor — close to a solid block, but with
     * the chain itself staying straight, no obstruction in its path — must still reel in ALL THE
     * WAY to its target length, not merely survive while stalled partway.
     *
     * <p>Deliberately built from absolute, near-origin coordinates on the bare level (the
     * {@code lifecycle} template is only present to satisfy {@code @GameTest}'s own requirement
     * for one), the same recipe {@link #reelInHalfLengthWhileObstructedAt64BlocksSurvives} uses,
     * rather than reusing {@code fall_arrest_swing}: this file already has three other tests
     * sharing that one template in a single CI run, and section 10.4's own CI history records a
     * suspected (never fully diagnosed) cross-instance interaction between multiple uses of the
     * same template in one run — not a risk worth taking on for a brand-new, unrelated test.
     */
    @GameTest(template = "lifecycle", timeoutTicks = 260, required = true)
    public static void reelInCompletesNearGroundWhenUnobstructed(GameTestHelper helper) {
        int originX = 0;
        int originY = 150;
        int originZ = 9000;
        BlockPos anchorAbsolute = new BlockPos(originX, originY, originZ);
        Vec3 anchorPos = Vec3.atCenterOf(anchorAbsolute);
        helper.getLevel().setChunkForced(originX >> 4, originZ >> 4, true);
        helper.getLevel().setBlock(anchorAbsolute, Blocks.STONE.defaultBlockState(), 3);
        // A floor 10 blocks straight down from the anchor -- the player settles just above it,
        // close to a solid block, but the anchor stays directly overhead the whole time, so the
        // chain itself never has anything to bend around.
        helper.getLevel().setBlock(new BlockPos(originX, originY - 10, originZ), Blocks.STONE.defaultBlockState(), 3);

        Vec3 playerSpawn = new Vec3(originX + 0.5, originY - 1, originZ + 0.5);
        ServerPlayer player = spawnMockPlayerAtAbsolute(helper, playerSpawn);
        simulateGravityEachTick(helper, player);

        double slack = 1.0;
        UUID ropeId = RopeManager.attachToPoint(player, anchorPos, anchorAbsolute, slack);
        helper.assertTrue(ropeId != null, "rope attach failed: no Sable physics system in the game test level");

        double initialRestLength = RopeManager.length(ropeId);
        double targetRestLength = initialRestLength / 2.0;

        helper.runAfterDelay(100, () -> RopeManager.adjustLength(ropeId, targetRestLength));

        helper.runAfterDelay(240, () -> {
            PlayerRope rope = RopeManager.get(ropeId);
            helper.assertTrue(rope != null, "rope was torn down during an unobstructed near-ground reel-in");
            double restLength = rope.restLength();
            LOGGER.info("[rope-core] reelInCompletesNearGroundWhenUnobstructed: initialRestLength={}"
                            + " targetRestLength={} finalRestLength={}",
                    initialRestLength, targetRestLength, restLength);
            helper.assertTrue(restLength < targetRestLength + 1.0,
                    "reel-in near (but not obstructed by) solid ground stalled short of its target: initial="
                            + initialRestLength + " target=" + targetRestLength + " final=" + restLength
                            + " -- the obstruction guard may be misfiring for ordinary terrain proximity");
            helper.getLevel().setChunkForced(originX >> 4, originZ >> 4, false);
            helper.succeed();
        });
    }

    /**
     * Bug 5, the most severe finding in this ticket. MINECRAFT-178's CI measured reeling a
     * 64-block rope to half length via {@code RopeManager#adjustLength} while OBSTRUCTED tearing
     * the rope down entirely — {@code RopeManager#get} returned null afterward, 4 of 4 runs; 16
     * and 32-block ropes survived the identical operation. Root cause (not diagnosed by
     * MINECRAFT-178, whose own scope was measurement only): the old {@code adjustLength} looped
     * {@code payOut}/{@code reelIn} directly, meaning roughly 71 synchronous native {@code
     * RopePhysicsObject#removeFirstPoint} calls in a single server tick for this exact scenario —
     * the same native structural mutation bug 2's per-tick panic already implicated, just enough
     * of them in one tick to corrupt the object outright rather than merely crash on the next one.
     *
     * <p>This reproduces MINECRAFT-178's own near-origin, force-loaded-chunk rig methodology
     * (docs/rope-core.md sections 10/11) at the identical 64-block/1.1-slack scale, settles 170
     * ticks, calls {@code adjustLength(length / 2)} while obstructed by a post, then samples 340
     * ticks later (MINECRAFT-178's own sampling delay). ASSERTS exactly two things: the rope is
     * still alive ({@code RopeManager#get} non-null), and {@code restLength()} has measurably
     * drained most of the way toward the new target rather than being frozen at the old length.
     * It does NOT assert the rope ever reaches the target exactly, and does NOT by itself prove
     * this scenario is settled — MINECRAFT-178 needed 4 runs to characterize its own failure as
     * reliable (4/4); see the PR description for how many runs of THIS test's own result are
     * being cited before this is reported as more than a single-run result. {@code required =
     * false}: unlike {@code payOutGrowsAllowedRadiusForUnobstructedHang}/{@code
     * perTickPayOutAndReelInDoNotPanic} (both flipped to {@code required = true} — see their own
     * javadoc), this test's own obstruction geometry inherits the same physics-timing
     * nondeterminism {@code catchOnObstruction}/section 10 already document, so a flake here is
     * this test's own risk to report, not grounds to gate the build on yet.
     */
    @GameTest(template = "lifecycle", timeoutTicks = 560, required = false)
    public static void reelInHalfLengthWhileObstructedAt64BlocksSurvives(GameTestHelper helper) {
        double length = 64.0;
        double slack = 1.1;
        double drop = 6.0;
        int originX = 0;
        int originY = 150;
        int originZ = 5000;

        double dx = Math.sqrt(Math.max(length * length - drop * drop, 1.0));
        BlockPos anchorAbsolute = new BlockPos(originX, originY, originZ);
        Vec3 anchorPos = Vec3.atCenterOf(anchorAbsolute);
        Vec3 playerSpawn = new Vec3(originX + dx, originY - drop, originZ + 0.5);

        int chunkMax = ((int) Math.ceil(dx) + 10) >> 4;
        for (int cx = 0; cx <= chunkMax; cx++) {
            helper.getLevel().setChunkForced(cx, originZ >> 4, true);
        }

        helper.getLevel().setBlock(anchorAbsolute, Blocks.STONE.defaultBlockState(), 3);
        ServerPlayer player = spawnMockPlayerAtAbsolute(helper, playerSpawn);
        simulateGravityEachTick(helper, player);

        UUID ropeId = RopeManager.attachToPoint(player, anchorPos, anchorAbsolute, slack);
        helper.assertTrue(ropeId != null, "64-block rig: rope attach failed -- no Sable physics system");

        // An obstruction between anchor and player, same near-origin methodology section 10/11
        // use. Its exact collision outcome (catch vs. penetration depth) is MINECRAFT-178's own
        // open, separately-tracked finding, not this test's point — this test's own question is
        // only whether the ROPE OBJECT SURVIVES adjustLength while touching something.
        int postX = originX + (int) Math.round(dx / 2);
        int postYBase = originY - (int) Math.ceil(drop) - 4;
        int postHeight = (int) Math.ceil(drop) + 6;
        for (int y = postYBase; y < postYBase + postHeight; y++) {
            helper.getLevel().setBlock(new BlockPos(postX, y, originZ), Blocks.STONE.defaultBlockState(), 3);
        }

        helper.runAfterDelay(170, () -> {
            PlayerRope settled = RopeManager.get(ropeId);
            helper.assertTrue(settled != null, "64-block rig: rope was torn down before reel-in was even attempted");
            LOGGER.info("[rope-core] reelInHalfLengthWhileObstructedAt64BlocksSurvives: restLength before"
                    + " reel-in={}", settled.restLength());
            RopeManager.adjustLength(ropeId, length / 2.0);
        });

        helper.runAfterDelay(510, () -> {
            PlayerRope rope = RopeManager.get(ropeId);
            helper.assertTrue(rope != null,
                    "64-block rope was torn down after adjustLength(length/2) while obstructed -- this is"
                            + " MINECRAFT-189 bug 5, the exact MINECRAFT-178 finding this fix targets");
            double restLength = rope.restLength();
            LOGGER.info("[rope-core] reelInHalfLengthWhileObstructedAt64BlocksSurvives: restLength 340 ticks"
                    + " after reel-in call={}", restLength);
            helper.assertTrue(restLength < length - 10.0,
                    "adjustLength(length/2) has not measurably drained toward the new target 340 ticks later"
                            + " (restLength still " + restLength + " of an original " + length
                            + ") -- the throttled backlog drain may be stuck");
            for (int cx = 0; cx <= chunkMax; cx++) {
                helper.getLevel().setChunkForced(cx, originZ >> 4, false);
            }
            helper.succeed();
        });
    }
}
