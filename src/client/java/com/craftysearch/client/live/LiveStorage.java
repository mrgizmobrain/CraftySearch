package com.craftysearch.client.live;

import com.craftysearch.client.memory.MemoryEntry;
import com.craftysearch.network.StorageUpdatePayload;
import com.craftysearch.storage.ContainerKey;
import com.craftysearch.storage.ContainerKind;
import com.craftysearch.storage.ItemKey;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The server's live view of nearby containers in server mode. Entries use the same shape as
 * remembered ones, so the panel, recipe book and highlights work on either.
 */
public final class LiveStorage {
    private static final LiveStorage INSTANCE = new LiveStorage();

    private final Map<ContainerKey, MemoryEntry> entries = new HashMap<>();
    private @Nullable ResourceKey<Level> dimension;
    private int version;

    public static LiveStorage get() {
        return INSTANCE;
    }

    public int version() {
        return version;
    }

    /** Entries for {@code dimension}, or none if the data is for another one. */
    public Collection<MemoryEntry> entries(ResourceKey<Level> dimension) {
        return dimension.equals(this.dimension) ? entries.values() : List.of();
    }

    /** The live data for one container, if it's in range. */
    public @Nullable MemoryEntry entry(ResourceKey<Level> dimension, ContainerKey key) {
        return dimension.equals(this.dimension) ? entries.get(key) : null;
    }

    void apply(Minecraft mc, StorageUpdatePayload update) {
        if (mc.level == null) return;
        if (update.reset()) {
            entries.clear();
            dimension = mc.level.dimension();
        }
        for (ContainerKey key : update.removed()) entries.remove(key);
        long now = System.currentTimeMillis();
        ContainerKind[] kinds = ContainerKind.values();
        for (StorageUpdatePayload.Container container : update.changed()) {
            if (container.kind() < 0 || container.kind() >= kinds.length) continue;
            Object2IntMap<ItemKey> counts = new Object2IntOpenHashMap<>();
            for (StorageUpdatePayload.ItemCount item : container.items()) {
                counts.mergeInt(ItemKey.exact(item.stack()), item.count(), Integer::sum);
            }
            MemoryEntry entry = new MemoryEntry(container.pos(), container.other().orElse(null),
                    container.key().entity(), kinds[container.kind()], now, counts);
            entries.put(entry.key(), entry);
        }
        version++;
    }

    void clear() {
        entries.clear();
        dimension = null;
        version++;
    }
}
