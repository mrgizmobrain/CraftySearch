package com.craftysearch.server;

import com.craftysearch.CraftySearch;
import com.craftysearch.ServerConfig;
import com.craftysearch.network.StorageUpdatePayload;
import com.craftysearch.network.SubscribePayload;
import com.craftysearch.server.StorageScanner.Found;
import com.craftysearch.storage.ContainerKey;
import com.craftysearch.storage.ContainerKind;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps each subscribed client's view of nearby containers up to date (spec 4.1, live
 * data). Every few ticks it rescans and sends only the containers that changed; a player
 * the mod just moved items for is rescanned in the same tick.
 */
public final class StorageSync {
    private static final int INTERVAL_TICKS = 10;
    private static final int CONTAINERS_PER_PAYLOAD = 64;
    private static final int REPORT_INTERVAL_TICKS = 20 * 60;
    /** A tick's scans taking this long (a tenth of a tick) are worth a line in the log. */
    private static final double SLOW_MILLIS = 5.0;
    private static final Map<UUID, Subscription> SUBSCRIPTIONS = new HashMap<>();
    /** Players the mod moved items for this tick. */
    private static final Set<UUID> CHANGED = new HashSet<>();
    private static long timedNanos;
    private static long slowestNanos;
    private static int timedScans;
    private static int timedSince;

    private static final class Subscription {
        final int radius;
        final Set<ContainerKind> kinds;
        @Nullable ResourceKey<Level> dimension;
        /** Content hash last sent per container. */
        final Map<ContainerKey, Integer> sent = new HashMap<>();

        Subscription(int radius, Set<ContainerKind> kinds) {
            this.radius = radius;
            this.kinds = kinds;
        }
    }

    private StorageSync() {
    }

    public static int clampRadius(int radius) {
        return Math.clamp(radius, 0, ServerConfig.get().maxRadius);
    }

    /** A new subscription starts empty, so the next sync sends everything with {@code reset}. */
    public static void subscribe(ServerPlayer player, SubscribePayload payload) {
        SUBSCRIPTIONS.put(player.getUUID(),
                new Subscription(clampRadius(payload.radius()), ContainerKind.fromMask(payload.kindsMask())));
    }

    public static void remove(ServerPlayer player) {
        SUBSCRIPTIONS.remove(player.getUUID());
        CHANGED.remove(player.getUUID());
    }

    public static void clear() {
        SUBSCRIPTIONS.clear();
        CHANGED.clear();
        timedSince = 0;
    }

    /**
     * The mod just moved items for this player (a take, a put-away, a craft): their update goes
     * out at the end of this tick instead of at the next interval, so the panel's counts change
     * with the click rather than up to half a second later (Nov 10).
     */
    public static void changedBy(ServerPlayer player) {
        if (SUBSCRIPTIONS.containsKey(player.getUUID())) CHANGED.add(player.getUUID());
    }

    public static void tick(MinecraftServer server) {
        if (SUBSCRIPTIONS.isEmpty()) return;
        boolean everyone = server.getTickCount() % INTERVAL_TICKS == 0;
        if (!everyone && CHANGED.isEmpty()) return;
        long start = System.nanoTime();
        int synced = 0;
        for (Map.Entry<UUID, Subscription> entry : SUBSCRIPTIONS.entrySet()) {
            if (!everyone && !CHANGED.contains(entry.getKey())) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) continue;
            sync(player, entry.getValue());
            synced++;
        }
        CHANGED.clear();
        if (synced > 0) time(server, System.nanoTime() - start, synced);
    }

    /**
     * Once a minute, how long the scans took: in the debug log, or as a warning when one tick's
     * scans took long enough to matter, so a server with a lot of storage in range can tell.
     */
    private static void time(MinecraftServer server, long nanos, int synced) {
        timedNanos += nanos;
        timedScans += synced;
        slowestNanos = Math.max(slowestNanos, nanos);
        if (server.getTickCount() - timedSince < REPORT_INTERVAL_TICKS) return;
        double average = timedNanos / 1e6 / Math.max(1, timedScans);
        double slowest = slowestNanos / 1e6;
        if (slowest >= SLOW_MILLIS) {
            CraftySearch.LOGGER.warn("Storage scans are slow: {} in the last minute, {} ms each on average, {} ms in the slowest tick."
                    + " A smaller maxRadius in craftysearch-server.json makes them cheaper.",
                    timedScans, String.format("%.2f", average), String.format("%.2f", slowest));
        } else {
            CraftySearch.LOGGER.debug("Storage scans: {} in the last minute, {} ms each on average, {} ms in the slowest tick",
                    timedScans, String.format("%.2f", average), String.format("%.2f", slowest));
        }
        timedSince = server.getTickCount();
        timedNanos = 0;
        timedScans = 0;
        slowestNanos = 0;
    }

    private static void sync(ServerPlayer player, Subscription sub) {
        ResourceKey<Level> dimension = player.level().dimension();
        boolean reset = !dimension.equals(sub.dimension);
        if (reset) {
            sub.sent.clear();
            sub.dimension = dimension;
        }

        Set<ContainerKey> present = new HashSet<>();
        List<StorageUpdatePayload.Container> changed = new ArrayList<>();
        for (Found found : StorageScanner.scan(player, sub.radius, sub.kinds)) {
            ContainerKey key = found.key();
            present.add(key);
            int hash = StorageScanner.contentHash(found);
            Integer previous = sub.sent.put(key, hash);
            if (previous == null || previous != hash) {
                changed.add(new StorageUpdatePayload.Container(key, found.pos(), Optional.ofNullable(found.other()),
                        found.kind().ordinal(), StorageScanner.contents(found)));
            }
        }
        List<ContainerKey> removed = new ArrayList<>();
        sub.sent.keySet().removeIf(key -> {
            if (present.contains(key)) return false;
            removed.add(key);
            return true;
        });
        if (!reset && removed.isEmpty() && changed.isEmpty()) return;

        // Split big updates so no single payload gets near the packet size limit.
        int start = 0;
        do {
            int end = Math.min(start + CONTAINERS_PER_PAYLOAD, changed.size());
            boolean first = start == 0;
            ServerPlayNetworking.send(player, new StorageUpdatePayload(first && reset,
                    first ? removed : List.of(), List.copyOf(changed.subList(start, end))));
            start = end;
        } while (start < changed.size());
    }
}
