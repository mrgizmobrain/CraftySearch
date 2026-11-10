package com.craftysearch.server;

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
 * data). Every few ticks it rescans and sends only the containers that changed.
 */
public final class StorageSync {
    private static final int INTERVAL_TICKS = 10;
    private static final int CONTAINERS_PER_PAYLOAD = 64;
    private static final Map<UUID, Subscription> SUBSCRIPTIONS = new HashMap<>();

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
    }

    public static void clear() {
        SUBSCRIPTIONS.clear();
    }

    public static void tick(MinecraftServer server) {
        if (SUBSCRIPTIONS.isEmpty() || server.getTickCount() % INTERVAL_TICKS != 0) return;
        for (Map.Entry<UUID, Subscription> entry : SUBSCRIPTIONS.entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null) sync(player, entry.getValue());
        }
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
