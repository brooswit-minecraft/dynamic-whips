package io.github.brooswitminecraft.dynamicwhips.rope.client;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.github.brooswitminecraft.dynamicwhips.rope.net.RopeRemovePayload;
import io.github.brooswitminecraft.dynamicwhips.rope.net.RopeSyncPayload;

/**
 * Client-side cache of the last two point snapshots per rope, so {@link RopeRenderer} can
 * interpolate between {@link io.github.brooswitminecraft.dynamicwhips.rope.RopeConstants#SYNC_INTERVAL_TICKS}-tick
 * server updates instead of visibly snapping (MINECRAFT-85 acceptance criterion 3).
 */
public final class ClientRopeState {
    private record Snapshot(float[] previous, float[] current) {
    }

    private static final Map<UUID, Snapshot> ROPES = new ConcurrentHashMap<>();

    private ClientRopeState() {
    }

    public static void handleSync(RopeSyncPayload payload) {
        ROPES.compute(payload.ropeId(), (id, existing) -> {
            float[] previous = existing != null ? existing.current() : payload.points();
            return new Snapshot(previous, payload.points());
        });
    }

    public static void handleRemove(RopeRemovePayload payload) {
        ROPES.remove(payload.ropeId());
    }

    public static void clear() {
        ROPES.clear();
    }

    /** Linearly interpolated points for rendering, or null if nothing has arrived for this rope yet. */
    public static float[] interpolatedPoints(UUID ropeId, float partialTicks) {
        Snapshot snapshot = ROPES.get(ropeId);
        if (snapshot == null) {
            return null;
        }
        float[] a = snapshot.previous();
        float[] b = snapshot.current();
        if (a.length != b.length) {
            return b;
        }
        float[] out = new float[b.length];
        for (int i = 0; i < b.length; i++) {
            out[i] = a[i] + (b[i] - a[i]) * partialTicks;
        }
        return out;
    }

    public static Iterable<UUID> activeRopeIds() {
        return ROPES.keySet();
    }
}
