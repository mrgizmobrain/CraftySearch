package com.craftysearch.client.storage;

import com.craftysearch.CraftySearch;
import com.craftysearch.client.config.ClientConfig;
import com.craftysearch.client.live.LiveStorage;
import com.craftysearch.client.live.ServerMode;
import com.craftysearch.client.memory.ContainerMemory;
import com.craftysearch.client.memory.MemoryEntry;
import com.craftysearch.client.recipe.RecipeStorage;
import com.craftysearch.storage.ContainerKey;
import com.craftysearch.storage.ItemKey;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ObjLongConsumer;

/**
 * Adds up the player's inventory and the remembered containers within the radius. Items
 * are grouped by {@link ItemKey#normalized}: enchantments, potions and names split tiles,
 * durability doesn't.
 */
public final class StorageIndex {
    private StorageIndex() {
    }

    /**
     * One tile in the storage panel.
     *
     * @param key            the item, with durability and repair cost dropped
     * @param total          what the tile shows: storage and what's inside shulker boxes, plus the
     *                       inventory when {@code includeInventory} is on
     * @param inInventory    how many the player carries loose, counted in {@code total} or not
     * @param inStorage      how many lie loose in containers: what can be taken or crafted with
     * @param inBoxes        how many are inside shulker boxes (in containers, and in the inventory when
     *                       it's counted), with {@code shulkerContents} on: counted and shown, never moved
     * @param sources        remembered containers holding this item, loose or in a box, nearest first
     * @param looseContainers how many of {@code sources} hold it loose
     * @param boxContainers  how many of {@code sources} hold it inside shulker boxes (a container may count in both)
     * @param oldestSeenMillis when the stalest of those containers was last seen, or 0 if none
     * @param suspectContainers how many of {@code sources} someone else has used since they were read
     *                       (client-only mode), so their counts may be off
     */
    public record ItemTotal(ItemKey key, long total, long inInventory, long inStorage, long inBoxes,
                            List<MemoryEntry> sources, int looseContainers, int boxContainers, long oldestSeenMillis,
                            int suspectContainers) {
        public ItemStack display() {
            return key.stack();
        }
    }

    /** What a tile sorts by. Kept between recounts (Nov 10): working out names is most of a recount's cost. */
    private record SortInfo(String name, String enchantments, String mod) {
        static SortInfo of(ItemKey key) {
            ItemStack stack = key.stack();
            return new SortInfo(stack.getHoverName().getString(), SearchFilter.sortKey(stack),
                    BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace());
        }
    }

    private static final int SORT_INFO_LIMIT = 20_000;
    private static final Map<ItemKey, SortInfo> SORT_INFO = new HashMap<>();
    /** The language the kept names are in. */
    private static @Nullable Language sortLanguage;

    private static final long REPORT_INTERVAL_MILLIS = 60_000;
    /** A recount this long is a visible hitch in the frame it runs in. */
    private static final double SLOW_MILLIS = 10.0;
    private static long timedNanos;
    private static long slowestNanos;
    private static int timedRecounts;
    private static long timedSince;

    /** Drops the kept names on leaving a world: their items hold on to that world's registries. */
    public static void clearCache() {
        SORT_INFO.clear();
        sortLanguage = null;
    }

    /** Every tile's totals, in the panel's order. */
    public static List<ItemTotal> compute(Minecraft mc) {
        if (mc.player == null || mc.level == null) return List.of();
        long start = System.nanoTime();
        List<ItemTotal> totals = count(mc);
        time(System.nanoTime() - start, totals.size());
        return totals;
    }

    /** Once a minute, how long recounts took: in the debug log, or as a warning when one was slow enough to see. */
    private static void time(long nanos, int tiles) {
        timedNanos += nanos;
        timedRecounts++;
        slowestNanos = Math.max(slowestNanos, nanos);
        long now = System.currentTimeMillis();
        if (timedSince == 0) timedSince = now;
        if (now - timedSince < REPORT_INTERVAL_MILLIS) return;
        String average = String.format(Locale.ROOT, "%.2f", timedNanos / 1e6 / timedRecounts);
        String slowest = String.format(Locale.ROOT, "%.2f", slowestNanos / 1e6);
        if (slowestNanos / 1e6 >= SLOW_MILLIS) {
            CraftySearch.LOGGER.warn("Storage recounts are slow: {} in the last minute, {} ms on average, {} ms the slowest, {} tiles",
                    timedRecounts, average, slowest, tiles);
        } else {
            CraftySearch.LOGGER.debug("Storage recounts: {} in the last minute, {} ms on average, {} ms the slowest, {} tiles",
                    timedRecounts, average, slowest, tiles);
        }
        timedSince = now;
        timedNanos = 0;
        slowestNanos = 0;
        timedRecounts = 0;
    }

    private static List<ItemTotal> count(Minecraft mc) {
        ClientConfig config = ClientConfig.get();
        boolean countInventory = config.includeInventory;
        boolean lookInBoxes = config.shulkerContents;
        boolean onlyTakeable = onlyTakeable();
        Map<ItemKey, Builder> builders = new LinkedHashMap<>();

        for (ItemStack stack : mc.player.getInventory().getNonEquipmentItems()) {
            if (stack.isEmpty()) continue;
            builders.computeIfAbsent(ItemKey.normalized(stack), Builder::new).inventory += stack.getCount();
            // Boxes the player carries count with the inventory (not when only what storage gives counts).
            if (lookInBoxes && countInventory && !onlyTakeable) {
                forEachInBox(stack, stack.getCount(),
                        (inner, count) -> builders.computeIfAbsent(ItemKey.normalized(inner), Builder::new).boxes += count);
            }
        }

        List<MemoryEntry> entries = nearbyEntries(mc);
        // Client-only takes reach only as far as the player's hand (spec 3.4).
        if (onlyTakeable && !ServerMode.active()) entries = entries.stream().filter(e -> RecipeStorage.isReachable(mc.player, e)).toList();
        for (MemoryEntry entry : entries) {
            // With "Leave one" on, the one each container keeps can't be taken.
            Object2IntMap<ItemKey> counts = onlyTakeable ? entry.takeableCounts() : entry.counts();
            for (Object2IntMap.Entry<ItemKey> e : counts.object2IntEntrySet()) {
                Builder b = builders.computeIfAbsent(e.getKey().normalized(), Builder::new);
                b.storage += e.getIntValue();
                b.looseIn(entry);
                if (lookInBoxes) {
                    forEachInBox(e.getKey().stack(), e.getIntValue(), (inner, count) -> {
                        Builder in = builders.computeIfAbsent(ItemKey.normalized(inner), Builder::new);
                        in.boxes += count;
                        in.boxedIn(entry);
                    });
                }
            }
        }

        // Names and enchantments are worked out once per tile, not in every comparison or every
        // recount: a big storage has thousands of tiles, recounted every second while the panel shows.
        Language language = Language.getInstance();
        if (language != sortLanguage || SORT_INFO.size() > SORT_INFO_LIMIT) {
            SORT_INFO.clear();
            sortLanguage = language;
        }
        List<Sorted> sorted = new ArrayList<>(builders.size());
        for (Builder b : builders.values()) {
            // Without the inventory, or showing only what can be taken, items only in the inventory get no tile.
            if ((!countInventory || onlyTakeable) && b.storage == 0 && b.boxes == 0) continue;
            sorted.add(new Sorted(b.build(countInventory), SORT_INFO.computeIfAbsent(b.key, SortInfo::of)));
        }
        sorted.sort(order(ClientConfig.get().panelSort));
        return sorted.stream().map(Sorted::total).toList();
    }

    /** Enchanted copies share a name, so ties are broken by enchantment, to keep tiles from swapping places. */
    private static Comparator<Sorted> order(ClientConfig.PanelSort sort) {
        Comparator<Sorted> byCount = Comparator.comparingLong((Sorted s) -> s.total().total()).reversed();
        Comparator<Sorted> byName = Comparator.comparing((Sorted s) -> s.info().name(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(s -> s.info().enchantments());
        return switch (sort) {
            case COUNT -> byCount.thenComparing(byName);
            case NAME -> byName.thenComparing(byCount);
            case MOD -> Comparator.comparing((Sorted s) -> s.info().mod()).thenComparing(byName).thenComparing(byCount);
        };
    }

    private record Sorted(ItemTotal total, SortInfo info) {
    }

    /** A count for a tile or a label: 999, then 1.2k, then 3.4M. */
    public static String formatCount(long count) {
        if (count < 1000) return Long.toString(count);
        if (count < 1_000_000) return trim(count / 1000.0) + "k";
        return trim(count / 1_000_000.0) + "M";
    }

    private static String trim(double value) {
        return value >= 100 ? Long.toString((long) value) : String.format(Locale.ROOT, "%.1f", value).replace(".0", "");
    }

    /** What's known about one container now: the server's live data in server mode, otherwise memory. */
    public static @Nullable MemoryEntry currentEntry(Minecraft mc, ContainerKey key) {
        if (mc.level == null) return null;
        if (ServerMode.active()) return LiveStorage.get().entry(mc.level.dimension(), key);
        return ClientConfig.get().memory.enabled ? ContainerMemory.get().entry(mc.level.dimension(), key) : null;
    }

    /**
     * Containers of the included kinds within the radius, nearest first: the server's live
     * data in server mode, otherwise remembered ones (none when memory is off).
     */
    public static List<MemoryEntry> nearbyEntries(Minecraft mc) {
        ClientConfig config = ClientConfig.get();
        if (mc.player == null || mc.level == null) return List.of();
        Collection<MemoryEntry> source;
        if (ServerMode.active()) {
            source = LiveStorage.get().entries(mc.level.dimension());
        } else if (config.memory.enabled) {
            source = ContainerMemory.get().entries(mc.level.dimension());
        } else {
            return List.of();
        }
        BlockPos center = mc.player.blockPosition();
        int radius = radius();
        Level level = mc.level;
        // Carts and boats count where they are now, when the client has them loaded.
        List<Map.Entry<MemoryEntry, Double>> nearby = new ArrayList<>();
        for (MemoryEntry entry : source) {
            if (config.includes(entry.kind()) && entry.isWithin(level, center, radius)) {
                nearby.add(Map.entry(entry, entry.distanceSqr(level, center)));
            }
        }
        nearby.sort(Map.Entry.comparingByValue());
        return nearby.stream().map(Map.Entry::getKey).toList();
    }

    /**
     * With {@code onlyTakeable} on (Nov 9), the panel counts only what a take could bring out:
     * in client-only mode only containers within reach, and with "Leave one" on not the one
     * each container keeps. Off where nothing can be taken at all (the server turned it off),
     * since the panel then only shows where things are.
     */
    public static boolean onlyTakeable() {
        ClientConfig config = ClientConfig.get();
        if (!config.onlyTakeable) return false;
        return ServerMode.active() ? ServerMode.takeAndPutAwayAllowed() : config.actsClientOnly();
    }

    /** The radius in use: the client's setting, capped by the server's in server mode. */
    public static int radius() {
        return ServerMode.active() ? ServerMode.radius() : ClientConfig.get().radius;
    }

    /** Changes whenever remembered or live storage changes, so views know to recount. */
    public static int version() {
        return ContainerMemory.get().version() + LiveStorage.get().version();
    }

    /**
     * The items inside a shulker box (or any item carrying a container's contents), times
     * {@code copies} of it: identical boxes are one key with a count.
     */
    public static void forEachInBox(ItemStack box, int copies, ObjLongConsumer<ItemStack> action) {
        ItemContainerContents contents = box.get(DataComponents.CONTAINER);
        if (contents == null || copies <= 0) return;
        contents.nonEmptyItemCopyStream().forEach(inner -> action.accept(inner, (long) inner.getCount() * copies));
    }

    /** How many of a tile's item a container holds inside its shulker boxes, with {@code shulkerContents} on. */
    public static long inBoxes(MemoryEntry entry, ItemKey tile) {
        if (!ClientConfig.get().shulkerContents) return 0;
        long[] count = {0};
        for (Object2IntMap.Entry<ItemKey> e : entry.counts().object2IntEntrySet()) {
            forEachInBox(e.getKey().stack(), e.getIntValue(), (inner, n) -> {
                if (tile.matchesTile(inner)) count[0] += n;
            });
        }
        return count[0];
    }

    private static final class Builder {
        final ItemKey key;
        long inventory;
        long storage;
        long boxes;
        final List<MemoryEntry> sources = new ArrayList<>();
        int looseContainers;
        int boxContainers;
        int suspectContainers;
        private @Nullable MemoryEntry lastLoose;
        private @Nullable MemoryEntry lastBoxed;
        long oldestSeen;

        Builder(ItemKey key) {
            this.key = key;
        }

        /** Counted loose in {@code entry}. */
        void looseIn(MemoryEntry entry) {
            seenIn(entry);
            if (lastLoose != entry) {
                lastLoose = entry;
                looseContainers++;
            }
        }

        /** Counted inside a shulker box in {@code entry}. */
        void boxedIn(MemoryEntry entry) {
            seenIn(entry);
            if (lastBoxed != entry) {
                lastBoxed = entry;
                boxContainers++;
            }
        }

        /**
         * Worn copies of one tool, or several boxes holding the item, in the same chest map to
         * one tile; the chest is listed once. Containers are gone through one at a time, so the
         * last one listed is the only one that can repeat.
         */
        private void seenIn(MemoryEntry entry) {
            if (sources.isEmpty() || sources.getLast() != entry) {
                sources.add(entry);
                if (entry.suspect()) suspectContainers++;
            }
            oldestSeen = oldestSeen == 0 ? entry.lastSeenMillis() : Math.min(oldestSeen, entry.lastSeenMillis());
        }

        ItemTotal build(boolean countInventory) {
            long total = (countInventory ? inventory : 0) + storage + boxes;
            return new ItemTotal(key, total, inventory, storage, boxes, List.copyOf(sources), looseContainers, boxContainers,
                    oldestSeen, suspectContainers);
        }
    }
}
