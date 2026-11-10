package com.craftysearch.client.memory;

import com.craftysearch.CraftySearch;
import com.craftysearch.storage.ContainerKey;
import com.craftysearch.storage.ContainerKind;
import com.craftysearch.storage.ItemKey;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Client-side memory of container contents (spec 4.2), kept per server or world and per
 * dimension, keyed by block position or, for chest minecarts and boats, by entity UUID
 * (spec 5.1). Saved under {@code <game dir>/craftysearch/memory}.
 */
public final class ContainerMemory {
    /** 1: plain item IDs. 2: full items with components. 3: carts and boats ({@code "entity"}). */
    private static final int FORMAT_VERSION = 3;
    private static final int VALIDATE_INTERVAL_TICKS = 40;
    private static final int SAVE_INTERVAL_TICKS = 20 * 30;
    /**
     * Within this horizontal distance of where a cart was last seen, the client would have it
     * loaded if it were still there. Entities are tracked much further, so this is safe.
     */
    private static final int CART_CHECK_DISTANCE = 16;
    /** Entities arrive a little after the chunks when joining or changing dimension. */
    private static final int CART_CHECK_AFTER_SPAWN_TICKS = 100;

    private static final ContainerMemory INSTANCE = new ContainerMemory();

    private @Nullable Path worldDir;
    /** Item components (enchantments and the like) reference the server's registries. */
    private @Nullable RegistryOps<JsonElement> ops;
    private final Map<ResourceKey<Level>, Dimension> dimensions = new HashMap<>();
    private int version;
    private int ticks;

    public static ContainerMemory get() {
        return INSTANCE;
    }

    /** Bumped whenever an entry changes, so the panel knows to recount. */
    public int version() {
        return version;
    }

    public void open(Minecraft mc, HolderLookup.Provider registries) {
        close();
        worldDir = FabricLoader.getInstance().getGameDir()
                .resolve(CraftySearch.MOD_ID).resolve("memory").resolve(worldId(mc));
        ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        CraftySearch.LOGGER.debug("Container memory at {}", worldDir);
    }

    public void close() {
        saveDirty();
        dimensions.clear();
        worldDir = null;
        ops = null;
        version++;
    }

    public Collection<MemoryEntry> entries(ResourceKey<Level> dimension) {
        Dimension dim = dimension(dimension);
        return dim == null ? List.of() : dim.entries.values();
    }

    /** The remembered container {@code key} names exactly (a block's first position, or a cart), if any. */
    public @Nullable MemoryEntry entry(ResourceKey<Level> dimension, ContainerKey key) {
        Dimension dim = dimension(dimension);
        return dim == null ? null : dim.entries.get(key);
    }

    public void record(ResourceKey<Level> dimension, BlockPos pos, @Nullable BlockPos other,
                       ContainerKind kind, Object2IntMap<ItemKey> counts) {
        Dimension dim = dimension(dimension);
        if (dim == null) return;
        BlockPos first = other != null && other.compareTo(pos) < 0 ? other : pos;
        BlockPos second = first == pos ? other : pos;
        MemoryEntry before = dim.entries.get(ContainerKey.ofBlock(first));
        dim.removeAt(pos);
        if (other != null) dim.removeAt(other);
        MemoryEntry entry = new MemoryEntry(first, second, null, kind, System.currentTimeMillis(), counts);
        dim.put(entry);
        changed(dim, before, entry);
    }

    /** A chest minecart or boat, keyed by its UUID; {@code pos} is the block it's in now. */
    public void recordEntity(ResourceKey<Level> dimension, UUID entity, BlockPos pos,
                             ContainerKind kind, Object2IntMap<ItemKey> counts) {
        Dimension dim = dimension(dimension);
        if (dim == null) return;
        dim.missing.remove(entity);
        MemoryEntry before = dim.entries.get(ContainerKey.ofEntity(entity));
        MemoryEntry entry = new MemoryEntry(pos.immutable(), null, entity, kind, System.currentTimeMillis(), counts);
        dim.put(entry);
        changed(dim, before, entry);
    }

    public void tick(Minecraft mc) {
        if (worldDir == null || mc.level == null || mc.player == null) return;
        ticks++;
        if (ticks % VALIDATE_INTERVAL_TICKS == 0) {
            forgetRemovedContainers(mc.level, mc.player);
        }
        if (ticks % SAVE_INTERVAL_TICKS == 0) {
            saveDirty();
        }
    }

    /**
     * Drops entries whose block is loaded but no longer the container that was recorded.
     * Carts and boats follow their entity while the client has it; one that isn't where it
     * was last seen, with the player close by, is forgotten after two checks in a row.
     */
    private void forgetRemovedContainers(Level level, Player player) {
        Dimension dim = dimension(level.dimension());
        if (dim == null || dim.entries.isEmpty()) return;
        List<MemoryEntry> gone = new ArrayList<>();
        List<MemoryEntry> moved = new ArrayList<>();
        for (MemoryEntry entry : dim.entries.values()) {
            if (entry.isEntity()) {
                checkCart(dim, level, player, entry, gone, moved);
                continue;
            }
            if (!level.isLoaded(entry.pos())) continue;
            BlockState state = level.getBlockState(entry.pos());
            if (ContainerKind.of(state) != entry.kind()) {
                gone.add(entry);
            } else if (entry.other() != null && !entry.other().equals(ContainerKind.otherHalf(entry.pos(), state))) {
                gone.add(entry);
            }
        }
        // A moved cart keeps its contents; only the place to look changes, so the panel needn't recount.
        for (MemoryEntry entry : moved) dim.put(entry);
        if (!moved.isEmpty()) dim.dirty = true;
        if (gone.isEmpty()) return;
        for (MemoryEntry entry : gone) dim.remove(entry.key());
        changed(dim);
    }

    private static void checkCart(Dimension dim, Level level, Player player, MemoryEntry entry,
                                  List<MemoryEntry> gone, List<MemoryEntry> moved) {
        UUID uuid = entry.entity();
        Entity cart = entry.findEntity(level);
        if (cart != null) {
            dim.missing.remove(uuid);
            if (!cart.blockPosition().equals(entry.pos())) moved.add(entry.withPos(cart.blockPosition()));
            return;
        }
        BlockPos pos = entry.pos();
        double dx = player.getX() - (pos.getX() + 0.5);
        double dz = player.getZ() - (pos.getZ() + 0.5);
        boolean shouldSee = player.tickCount > CART_CHECK_AFTER_SPAWN_TICKS && level.isLoaded(pos)
                && dx * dx + dz * dz <= CART_CHECK_DISTANCE * CART_CHECK_DISTANCE;
        if (!shouldSee) {
            dim.missing.remove(uuid);
        } else if (!dim.missing.add(uuid)) {
            gone.add(entry);
        }
    }

    private void changed(Dimension dim) {
        dim.dirty = true;
        version++;
    }

    /**
     * A container was recorded again. An open container is recorded every half second, so
     * when its contents are the same as before only the "last seen" time changes, and the
     * panel and recipe book needn't recount.
     */
    private void changed(Dimension dim, @Nullable MemoryEntry before, MemoryEntry after) {
        boolean same = before != null && before.kind() == after.kind() && Objects.equals(before.other(), after.other())
                && before.counts().equals(after.counts());
        if (same) dim.dirty = true;
        else changed(dim);
    }

    private @Nullable Dimension dimension(ResourceKey<Level> key) {
        if (worldDir == null || ops == null) return null;
        Path dir = worldDir;
        RegistryOps<JsonElement> registryOps = ops;
        return dimensions.computeIfAbsent(key, k -> load(dir.resolve(fileName(k)), registryOps));
    }

    public void saveDirty() {
        if (ops == null) return;
        for (Dimension dim : dimensions.values()) {
            if (dim.dirty) {
                save(dim, ops);
            }
        }
    }

    // ---- Persistence ----

    /**
     * Reads a dimension's file. An entry that can't be read is skipped, so one bad entry
     * doesn't lose the rest; the file is backed up first if anything was skipped or it can't
     * be read at all, since the next save rewrites it with only what was read.
     */
    private static Dimension load(Path file, RegistryOps<JsonElement> ops) {
        Dimension dim = new Dimension(file);
        if (!Files.exists(file)) return dim;
        int skipped = 0;
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            for (JsonElement element : root.getAsJsonArray("entries")) {
                try {
                    MemoryEntry entry = readEntry(element.getAsJsonObject(), ops);
                    if (entry != null) dim.put(entry);
                } catch (RuntimeException e) {
                    skipped++;
                    CraftySearch.LOGGER.debug("Skipped an unreadable container memory entry in {}", file, e);
                }
            }
        } catch (IOException | RuntimeException e) {
            // RuntimeException covers malformed JSON (JsonParseException, IllegalStateException, ...).
            CraftySearch.LOGGER.warn("Could not read container memory {}", file, e);
            backUp(file);
            return dim;
        }
        if (skipped > 0) {
            CraftySearch.LOGGER.warn("Skipped {} unreadable entries in container memory {}", skipped, file);
            backUp(file);
        }
        return dim;
    }

    /** Keeps a copy of a memory file that couldn't be read in full, next to it. */
    private static void backUp(Path file) {
        Path copy = file.resolveSibling(file.getFileName() + ".broken-" + System.currentTimeMillis());
        try {
            Files.copy(file, copy);
            CraftySearch.LOGGER.warn("Kept a copy of it as {}", copy.getFileName());
        } catch (IOException e) {
            CraftySearch.LOGGER.warn("Could not back up {}", file, e);
        }
    }

    private static void save(Dimension dim, RegistryOps<JsonElement> ops) {
        JsonObject root = new JsonObject();
        root.addProperty("version", FORMAT_VERSION);
        JsonArray entries = new JsonArray();
        // Encoding every item is the slow part of a save, so each entry keeps its JSON until it changes.
        for (Map.Entry<ContainerKey, MemoryEntry> e : dim.entries.entrySet()) {
            entries.add(dim.encoded.computeIfAbsent(e.getKey(), key -> writeEntry(e.getValue(), ops)));
        }
        root.add("entries", entries);
        try {
            Files.createDirectories(dim.file.getParent());
            Path tmp = dim.file.resolveSibling(dim.file.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp)) {
                writer.write(root.toString());
            }
            Files.move(tmp, dim.file, StandardCopyOption.REPLACE_EXISTING);
            dim.dirty = false;
        } catch (IOException e) {
            CraftySearch.LOGGER.warn("Could not save container memory {}", dim.file, e);
        }
    }

    /**
     * v2: {@code "items": [{"stack": <vanilla item JSON, components included>, "count": n}, ...]}.
     * v3: a cart or boat has {@code "entity": "<uuid>"}, and {@code "pos"} is where it was last seen.
     */
    private static JsonObject writeEntry(MemoryEntry entry, RegistryOps<JsonElement> ops) {
        JsonObject json = new JsonObject();
        json.add("pos", writePos(entry.pos()));
        if (entry.other() != null) json.add("other", writePos(entry.other()));
        if (entry.entity() != null) json.addProperty("entity", entry.entity().toString());
        json.addProperty("kind", entry.kind().id());
        json.addProperty("seen", entry.lastSeenMillis());
        JsonArray items = new JsonArray();
        for (Object2IntMap.Entry<ItemKey> e : entry.counts().object2IntEntrySet()) {
            ItemStack.CODEC.encodeStart(ops, e.getKey().stack())
                    .resultOrPartial(error -> CraftySearch.LOGGER.debug("Could not save item: {}", error))
                    .ifPresent(stack -> {
                        JsonObject item = new JsonObject();
                        item.add("stack", stack);
                        item.addProperty("count", e.getIntValue());
                        items.add(item);
                    });
        }
        json.add("items", items);
        return json;
    }

    private static @Nullable MemoryEntry readEntry(JsonObject json, RegistryOps<JsonElement> ops) {
        ContainerKind kind = ContainerKind.byId(json.get("kind").getAsString());
        if (kind == null) return null;
        Object2IntMap<ItemKey> counts = new Object2IntOpenHashMap<>();
        JsonElement items = json.get("items");
        if (items.isJsonArray()) {
            for (JsonElement element : items.getAsJsonArray()) {
                JsonObject item = element.getAsJsonObject();
                // Partial results keep the item when one component (say, an enchantment from a
                // removed data pack) no longer decodes. Unknown items are dropped.
                ItemStack.CODEC.parse(ops, item.get("stack"))
                        .resultOrPartial(error -> CraftySearch.LOGGER.debug("Could not load item: {}", error))
                        .ifPresent(stack -> counts.mergeInt(ItemKey.exact(stack), item.get("count").getAsInt(), Integer::sum));
            }
        } else {
            // v1 stored plain item IDs and counts.
            for (Map.Entry<String, JsonElement> e : items.getAsJsonObject().entrySet()) {
                Identifier id = Identifier.tryParse(e.getKey());
                Optional<Item> item = id == null ? Optional.empty() : BuiltInRegistries.ITEM.getOptional(id);
                item.ifPresent(i -> counts.mergeInt(ItemKey.exact(new ItemStack(i)), e.getValue().getAsInt(), Integer::sum));
            }
        }
        BlockPos other = json.has("other") ? readPos(json.getAsJsonArray("other")) : null;
        // v1 and v2 only had blocks.
        UUID entity = json.has("entity") ? UUID.fromString(json.get("entity").getAsString()) : null;
        if ((entity != null) != kind.isEntity()) return null;
        return new MemoryEntry(readPos(json.getAsJsonArray("pos")), other, entity, kind, json.get("seen").getAsLong(), counts);
    }

    private static JsonArray writePos(BlockPos pos) {
        JsonArray array = new JsonArray();
        array.add(pos.getX());
        array.add(pos.getY());
        array.add(pos.getZ());
        return array;
    }

    private static BlockPos readPos(JsonArray array) {
        if (array.size() != 3) throw new JsonParseException("Expected [x, y, z]");
        return new BlockPos(array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt());
    }

    // ---- Naming ----

    /** A folder name for the current world: the save folder in singleplayer, the address in multiplayer. */
    private static String worldId(Minecraft mc) {
        IntegratedServer server = mc.getSingleplayerServer();
        if (server != null) {
            Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            return "local/" + sanitize(String.valueOf(root.getFileName()));
        }
        ServerData data = mc.getCurrentServer();
        if (data != null) {
            return "server/" + sanitize(data.ip);
        }
        return "unknown";
    }

    private static String fileName(ResourceKey<Level> dimension) {
        Identifier id = dimension.identifier();
        return sanitize(id.getNamespace() + "_" + id.getPath()) + ".json";
    }

    private static String sanitize(String name) {
        String cleaned = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        return cleaned.isEmpty() || cleaned.chars().allMatch(c -> c == '.') ? "_" : cleaned;
    }

    private static final class Dimension {
        final Path file;
        final Map<ContainerKey, MemoryEntry> entries = new HashMap<>();
        /** Every block position (both halves of a double chest) to its entry's key. Carts aren't in it. */
        final Map<BlockPos, ContainerKey> index = new HashMap<>();
        /** Carts found missing at the last check; a second miss in a row forgets them. Not saved. */
        final Set<UUID> missing = new HashSet<>();
        /** Each entry's JSON from the last save, until the entry changes. */
        final Map<ContainerKey, JsonObject> encoded = new HashMap<>();
        boolean dirty;

        Dimension(Path file) {
            this.file = file;
        }

        void put(MemoryEntry entry) {
            ContainerKey key = entry.key();
            encoded.remove(key);
            if (entry.isEntity()) {
                entries.put(key, entry);
                return;
            }
            removeAt(entry.pos());
            if (entry.other() != null) removeAt(entry.other());
            entries.put(key, entry);
            index.put(entry.pos(), key);
            if (entry.other() != null) index.put(entry.other(), key);
        }

        void removeAt(BlockPos pos) {
            ContainerKey key = index.get(pos);
            if (key != null) remove(key);
        }

        void remove(ContainerKey key) {
            encoded.remove(key);
            MemoryEntry entry = entries.remove(key);
            if (entry == null || entry.isEntity()) return;
            index.remove(entry.pos());
            if (entry.other() != null) index.remove(entry.other());
        }
    }
}
