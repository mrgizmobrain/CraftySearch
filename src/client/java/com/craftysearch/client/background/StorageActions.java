package com.craftysearch.client.background;

import com.craftysearch.client.compat.IpnLockedSlots;
import com.craftysearch.client.config.ClientConfig;
import com.craftysearch.client.highlight.Highlights;
import com.craftysearch.client.live.ServerMode;
import com.craftysearch.client.live.ServerOutbox;
import com.craftysearch.client.memory.ContainerRecorder;
import com.craftysearch.client.memory.MemoryEntry;
import com.craftysearch.client.recipe.RecipeStorage;
import com.craftysearch.client.storage.StorageIndex;
import com.craftysearch.client.storage.StorageIndex.ItemTotal;
import com.craftysearch.network.OpenContainerPayload;
import com.craftysearch.network.PutAwayPayload;
import com.craftysearch.network.TakePayload;
import com.craftysearch.storage.ContainerKey;
import com.craftysearch.storage.ContainerKind;
import com.craftysearch.storage.ItemKey;
import com.craftysearch.storage.PutAwayMatch;
import com.craftysearch.storage.PutAwayRule;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * Taking from and putting away into storage from the panel (spec 3.4): payloads in server
 * mode, background jobs on containers within reach in client-only mode.
 */
public final class StorageActions {
    /** Main-inventory slot numbers, the three rows above the hotbar first. */
    private static final int[] MAIN_ROWS_FIRST = IntStream.concat(
            IntStream.range(Inventory.SELECTION_SIZE, Inventory.INVENTORY_SIZE), IntStream.range(0, Inventory.SELECTION_SIZE)).toArray();
    /** How far around the eyes a check of everything in reach looks for containers; reach itself is a little less. */
    private static final int CHECK_SEARCH_BLOCKS = 7;
    /** A stack just sent off by Ctrl+scroll isn't picked again while the server or a job is still moving it. */
    private static final long STACK_RESEND_MILLIS = 1000;
    private static final long[] STACK_SENT_AT = new long[Inventory.INVENTORY_SIZE];

    /** A remembered container, described for the item being put away. Room is estimated from memory. */
    private record Remembered(MemoryEntry entry, int match, boolean isCopperChest, boolean isEmpty, boolean hasRoom)
            implements PutAwayRule.Candidate {
        static Remembered of(Minecraft mc, MemoryEntry entry, ItemStack moving) {
            boolean partial = false;
            int usedSlots = 0;
            List<ItemStack> held = new ArrayList<>();
            for (Object2IntMap.Entry<ItemKey> e : entry.counts().object2IntEntrySet()) {
                ItemStack stack = e.getKey().stack();
                held.add(stack);
                int maxStack = Math.max(1, stack.getMaxStackSize());
                usedSlots += (e.getIntValue() + maxStack - 1) / maxStack;
                partial |= ItemStack.isSameItemSameComponents(stack, moving) && e.getIntValue() % maxStack != 0;
            }
            boolean room = partial || usedSlots < entry.kind().expectedSlots(entry.other() != null);
            boolean copper = !entry.isEntity() && ContainerKind.isCopperChest(mc.level.getBlockState(entry.pos()));
            return new Remembered(entry, PutAwayMatch.of(moving, held), copper, entry.counts().isEmpty(), room);
        }
    }

    private StorageActions() {
    }

    /** Highlights the containers holding the tile's item (spec 3.1). */
    public static void locate(Minecraft mc, ItemTotal total) {
        if (mc.player == null) return;
        if (total.sources().isEmpty()) {
            mc.player.sendOverlayMessage(Component.translatable("craftysearch.highlight.none", total.display().getHoverName()));
            return;
        }
        Highlights.show(mc, total.sources(), total.key());
        mc.player.sendOverlayMessage(Component.translatable("craftysearch.highlight.shown",
                total.sources().size(), total.display().getHoverName()));
    }

    /** How much a click on a tile takes. */
    public enum TakeMode {
        /** A stack (spec 3.4). */
        STACK,
        /** One: right-click with "Take one" (Nov 9). */
        ONE,
        /** As much as fits in the inventory: shift-right-click with "Take all" (Nov 9). */
        ALL
    }

    /**
     * Takes some of a tile: onto the cursor with {@code toCursor} (never all of it), otherwise
     * into the inventory. Taking more while holding something goes into the inventory, except
     * that "Take one" adds to a held stack of the same item.
     */
    public static void take(Minecraft mc, ItemTotal total, TakeMode mode, boolean toCursor) {
        LocalPlayer player = mc.player;
        if (player == null) return;
        // Items are never taken out of shulker boxes (Nov 8). With none loose, the box holding the
        // most of them comes out instead, as a box tile's own take; none in storage, it shows where.
        // Just the one box: all of a box tile would be every box packed the same way.
        if (onlyInBoxes(total)) {
            ItemTotal box = boxHolding(mc, total.key());
            if (box == null) locate(mc, total);
            else take(mc, box, mode == TakeMode.ALL ? TakeMode.STACK : mode, toCursor && mode != TakeMode.ALL);
            return;
        }
        if (!allowedInServerMode(player)) return;
        ItemKey tile = total.key();
        boolean cursor = toCursor && cursorTakes(player.containerMenu.getCarried(), tile, mode);
        if (mode == TakeMode.ONE && !cursor) {
            takeCounted(mc, tile, null, 1, -1);
            return;
        }
        if (ServerMode.active()) {
            int flags = (cursor ? TakePayload.TO_CURSOR : 0) | (mode == TakeMode.ALL ? TakePayload.ALL : 0) | keepOneFlag();
            ServerOutbox.send(new TakePayload(total.display(), flags, ServerMode.radius(), ClientConfig.get().kindsMask(),
                    mode == TakeMode.ONE ? 1 : 0, TakePayload.NO_SLOT, IpnLockedSlots.mask()));
            return;
        }
        if (mode == TakeMode.STACK && !cursor) {
            // Stacks into the inventory queued in a row are one job (Nov 9).
            BackgroundJobs.submit(mc, new StackTakes(tile));
            return;
        }
        TakeJob.Mode jobMode = switch (mode) {
            case STACK -> TakeJob.Mode.STACK;
            case ONE -> TakeJob.Mode.ONE;
            case ALL -> TakeJob.Mode.ALL;
        };
        BackgroundJobs.submit(mc, m -> {
            List<MemoryEntry> inReach = takeSources(m, tile);
            if (inReach == null) return null;
            // A queued take may find the cursor holding the previous one; then it goes into the inventory.
            boolean queuedCursor = cursor && cursorTakes(m.player.containerMenu.getCarried(), tile, mode);
            if (mode == TakeMode.ONE && !queuedCursor) return TakeJob.counted(inReach, tile, null, 1, -1);
            return TakeJob.take(inReach, tile, jobMode, queuedCursor);
        });
    }

    /**
     * Client-only stacks into the inventory, of one tile or several (a shift-drag over tiles,
     * repeated shift-clicks). Each one queued behind a running job joins the last if that's
     * one of these too, so the job visits the containers holding any of them, nearest first,
     * opening each once.
     */
    private static final class StackTakes implements BackgroundJobs.Request {
        private final Map<ItemKey, Integer> stacks = new LinkedHashMap<>();

        StackTakes(ItemKey tile) {
            stacks.put(tile, 1);
        }

        @Override
        public @Nullable BackgroundJob create(Minecraft mc) {
            LocalPlayer player = mc.player;
            if (player == null || mc.level == null) return null;
            Map<ItemKey, Integer> wanted = new LinkedHashMap<>();
            Set<MemoryEntry> containers = new LinkedHashSet<>();
            for (Map.Entry<ItemKey, Integer> e : stacks.entrySet()) {
                // Each tile says for itself why it can't come (none left, out of reach, only kept ones).
                List<MemoryEntry> inReach = takeSources(mc, e.getKey());
                if (inReach == null) continue;
                wanted.put(e.getKey(), e.getValue());
                containers.addAll(inReach);
            }
            if (wanted.isEmpty()) return null;
            BlockPos at = player.blockPosition();
            List<MemoryEntry> nearestFirst = containers.stream()
                    .sorted(Comparator.comparingDouble(e -> e.distanceSqr(mc.level, at))).toList();
            return TakeJob.stacks(nearestFirst, wanted);
        }

        @Override
        public boolean absorb(BackgroundJobs.Request next) {
            if (!(next instanceof StackTakes other)) return false;
            other.stacks.forEach((tile, n) -> stacks.merge(tile, n, Integer::sum));
            return true;
        }
    }

    /** Whether a take can go onto the cursor holding {@code held}: an empty one, or one more onto a stack of it. */
    private static boolean cursorTakes(ItemStack held, ItemKey tile, TakeMode mode) {
        if (mode == TakeMode.ALL) return false;
        if (held.isEmpty()) return true;
        return mode == TakeMode.ONE && tile.matchesTile(held) && held.getCount() < held.getMaxStackSize();
    }

    /**
     * The drop key over a tile (Nov 9): throws one, or with Ctrl a stack, straight from storage
     * onto the ground in front of the player. A tile only inside shulker boxes shows where it is
     * instead; a whole box isn't thrown for it.
     */
    public static void throwOut(Minecraft mc, ItemTotal total, boolean stack) {
        LocalPlayer player = mc.player;
        if (player == null) return;
        if (total.inStorage() == 0) {
            locate(mc, total);
            return;
        }
        if (!allowedInServerMode(player)) return;
        ItemKey tile = total.key();
        if (ServerMode.active()) {
            ServerOutbox.send(new TakePayload(total.display(), TakePayload.DROP | keepOneFlag(), ServerMode.radius(),
                    ClientConfig.get().kindsMask(), stack ? 0 : 1, TakePayload.NO_SLOT, 0L));
            return;
        }
        BackgroundJobs.submit(mc, new ThrowRequest(tile, stack ? Math.max(1, tile.stack().getMaxStackSize()) : 1));
    }

    /** A queued throw; more presses on the same tile in a row add to it. */
    private static final class ThrowRequest implements BackgroundJobs.Request {
        private final ItemKey tile;
        private int amount;

        ThrowRequest(ItemKey tile, int amount) {
            this.tile = tile;
            this.amount = amount;
        }

        @Override
        public @Nullable BackgroundJob create(Minecraft mc) {
            List<MemoryEntry> inReach = takeSources(mc, tile);
            return inReach == null ? null : TakeJob.throwing(inReach, tile, amount);
        }

        @Override
        public boolean absorb(BackgroundJobs.Request next) {
            if (!(next instanceof ThrowRequest other) || !other.tile.equals(tile)) return false;
            amount += other.amount;
            return true;
        }
    }

    /**
     * Right-click with "Open" (Nov 9): opens the nearest container holding the tile, one someone
     * else has used first, then one with it loose before one with it in a shulker box. In server mode the server opens it from anywhere
     * within the radius (if it allows that); otherwise it's a plain right-click on one within
     * reach. Its slots are marked once it's open; nothing is outlined. Closing
     * it goes back to the world, as closing any container does.
     */
    public static void open(Minecraft mc, ItemTotal total) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;
        if (total.sources().isEmpty()) {
            player.sendOverlayMessage(Component.translatable("craftysearch.highlight.none", total.display().getHoverName()));
            return;
        }
        // The held stack would be left behind on the menu being replaced.
        if (!player.containerMenu.getCarried().isEmpty()) return;
        ItemKey tile = total.key();
        boolean remote = ServerMode.remoteOpenAllowed();
        // With "All interactions" off the mod clicks nothing for the player, a container included.
        if (!ServerMode.active() && !ClientConfig.get().actsClientOnly()) {
            locate(mc, total);
            return;
        }
        if (!remote && BackgroundJobs.isBusy()) {
            player.sendOverlayMessage(Component.translatable("craftysearch.background.wait_for_storage"));
            return;
        }
        List<MemoryEntry> inReach = remote ? total.sources()
                : total.sources().stream().filter(e -> RecipeStorage.isReachable(player, e)).toList();
        // One someone else has used comes first (Nov 11): opening it reads it again, which is what the tile's mark asks for.
        List<MemoryEntry> used = inReach.stream().filter(MemoryEntry::suspect).toList();
        List<MemoryEntry> loose = inReach.stream()
                .filter(e -> e.counts().keySet().stream().anyMatch(key -> tile.matchesTile(key.stack()))).toList();
        List<MemoryEntry> candidates = !used.isEmpty() ? used : !loose.isEmpty() ? loose : inReach;
        BlockPos at = player.blockPosition();
        MemoryEntry target = candidates.stream()
                .min(Comparator.comparingDouble(e -> e.distanceSqr(mc.level, at))).orElse(null);
        Entity entity = target != null && target.isEntity() ? target.findEntity(mc.level) : null;
        if (target == null || (target.isEntity() && entity == null)) {
            locate(mc, total);
            player.sendOverlayMessage(Component.translatable("craftysearch.storage.out_of_reach", total.display().getHoverName()));
            return;
        }
        // No outlines (Nov 11: the other containers were outlined, which read as a mistake); "Show where"
        // is for that. The search is still set, so the item's slots are marked in the screen that opens.
        Highlights.show(mc, List.of(), tile);
        // The screen that opens is this container's, so it's recorded (and marked) as such.
        ContainerRecorder.expectOpening(target.key());
        if (remote) {
            ServerOutbox.send(new OpenContainerPayload(target.key(), ServerMode.radius(), ClientConfig.get().kindsMask()));
        } else if (entity != null) {
            BackgroundAccess.useEntity(mc, entity);
        } else {
            BackgroundAccess.useBlock(mc, target.pos());
        }
    }

    /** Whether a shift-click on the panel's sort button can check every container within reach: client-only mode, allowed to act. */
    public static boolean canCheckAll() {
        return !ServerMode.active() && ClientConfig.get().actsClientOnly();
    }

    /**
     * Shift-click on the panel's sort button (Nov 11, client-only): reads every container within
     * reach, remembered or not, nearest first. The panel then catches up with what went on unseen
     * (carts, hoppers and automation give no sign of use at all), and a new place is learned in
     * one go. One that doesn't open is left out; another press while it runs adds nothing.
     */
    public static void checkAll(Minecraft mc) {
        if (mc.player == null || !canCheckAll() || BackgroundJobs.checkingAll()) return;
        BackgroundJobs.submit(mc, new CheckAll());
    }

    /** A queued check of everything in reach, planned when its turn comes. */
    static final class CheckAll implements BackgroundJobs.Request {
        @Override
        public @Nullable BackgroundJob create(Minecraft mc) {
            List<MemoryEntry> targets = containersInReach(mc);
            if (targets.isEmpty()) {
                if (mc.player != null) mc.player.sendOverlayMessage(Component.translatable("craftysearch.storage.none_to_check"));
                return null;
            }
            return ScanJob.checkingAll(targets);
        }
    }

    /**
     * The containers of the included kinds within reach, nearest first: the remembered ones, and
     * blocks, carts and boats not remembered yet. Chests that can't open (a block on top, a cat
     * on the lid) are left out rather than tried.
     */
    private static List<MemoryEntry> containersInReach(Minecraft mc) {
        LocalPlayer player = mc.player;
        Level level = mc.level;
        if (player == null || level == null) return List.of();
        ClientConfig config = ClientConfig.get();
        Map<ContainerKey, MemoryEntry> found = new LinkedHashMap<>();
        Set<BlockPos> covered = new HashSet<>();
        for (MemoryEntry entry : StorageIndex.nearbyEntries(mc)) {
            if (!entry.isEntity()) {
                covered.add(entry.pos());
                if (entry.other() != null) covered.add(entry.other());
            }
            if (RecipeStorage.isReachable(player, entry) && !chestBlocked(level, entry.kind(), entry.pos(), entry.other())) {
                found.put(entry.key(), entry);
            }
        }
        // Whether each is within reach is up to RecipeStorage; this only bounds the search.
        BlockPos eyes = BlockPos.containing(player.getEyePosition());
        BlockPos from = eyes.offset(-CHECK_SEARCH_BLOCKS, -CHECK_SEARCH_BLOCKS, -CHECK_SEARCH_BLOCKS);
        BlockPos to = eyes.offset(CHECK_SEARCH_BLOCKS, CHECK_SEARCH_BLOCKS, CHECK_SEARCH_BLOCKS);
        for (BlockPos p : BlockPos.betweenClosed(from, to)) {
            if (covered.contains(p)) continue;
            BlockState state = level.getBlockState(p);
            ContainerKind kind = ContainerKind.of(state);
            if (kind == null || !config.includes(kind)) continue;
            BlockPos pos = p.immutable();
            BlockPos other = ContainerKind.otherHalf(pos, state);
            covered.add(pos);
            if (other != null) covered.add(other);
            // A double chest is clicked on a half within reach.
            BlockPos click = RecipeStorage.canReach(player, pos) ? pos
                    : other != null && RecipeStorage.canReach(player, other) ? other : null;
            if (click == null || chestBlocked(level, kind, pos, other)) continue;
            BlockPos second = click == pos ? other : pos;
            found.put(ContainerKey.ofBlock(click), new MemoryEntry(click, second, null, kind, 0, new Object2IntOpenHashMap<>()));
        }
        for (Entity entity : level.getEntities((Entity) null, player.getBoundingBox().inflate(CHECK_SEARCH_BLOCKS),
                e -> ContainerKind.of(e) != null)) {
            ContainerKind kind = ContainerKind.of(entity);
            ContainerKey key = ContainerKey.ofEntity(entity.getUUID());
            if (kind == null || !config.includes(kind) || found.containsKey(key) || !RecipeStorage.canReach(player, entity)) continue;
            found.put(key, new MemoryEntry(entity.blockPosition(), null, entity.getUUID(), kind, 0, new Object2IntOpenHashMap<>()));
        }
        BlockPos at = player.blockPosition();
        return found.values().stream().sorted(Comparator.comparingDouble(e -> e.distanceSqr(level, at))).toList();
    }

    /** A chest that can't open now: a solid block, or a cat, on either half's lid. */
    private static boolean chestBlocked(Level level, ContainerKind kind, BlockPos pos, @Nullable BlockPos other) {
        if (kind != ContainerKind.CHEST) return false;
        return ChestBlock.isChestBlockedAt(level, pos) || (other != null && ChestBlock.isChestBlockedAt(level, other));
    }

    /**
     * Takes exactly {@code amount} of a tile into the inventory, into main-inventory slot
     * {@code targetSlot} first (or -1): the scroll wheel (spec 3.5). With {@code exact}, only
     * items that stack with it, so the stack under the mouse grows.
     */
    public static void takeCounted(Minecraft mc, ItemKey tile, @Nullable ItemStack exact, int amount, int targetSlot) {
        LocalPlayer player = mc.player;
        if (player == null || amount <= 0 || !allowedInServerMode(player)) return;
        if (ServerMode.active()) {
            ItemStack item = exact != null ? exact.copyWithCount(1) : tile.stack();
            ServerOutbox.send(new TakePayload(item, (exact != null ? TakePayload.EXACT : 0) | keepOneFlag(), ServerMode.radius(),
                    ClientConfig.get().kindsMask(), amount, targetSlot, IpnLockedSlots.mask()));
            return;
        }
        BackgroundJobs.submit(mc, new CountedTake(tile, exact, targetSlot, amount));
    }

    /**
     * True when a take of the tile can only bring out a shulker box: none of it lies loose in
     * storage, or with "Leave one" on, only the one each container keeps (Nov 9: a click then
     * said only the kept ones were left, though boxes held plenty).
     */
    public static boolean onlyInBoxes(ItemTotal total) {
        if (total.inBoxes() <= 0) return false;
        if (total.inStorage() <= 0) return true;
        if (!ClientConfig.get().keepOne) return false;
        for (MemoryEntry entry : total.sources()) {
            for (ItemKey key : entry.takeableCounts().keySet()) {
                if (total.key().matchesTile(key.stack())) return false;
            }
        }
        return true;
    }

    /** The shulker box lying loose in storage that holds the most of {@code tile}, as its own panel tile, or null. */
    private static @Nullable ItemTotal boxHolding(Minecraft mc, ItemKey tile) {
        ItemTotal best = null;
        long bestCount = 0;
        for (ItemTotal candidate : StorageIndex.compute(mc)) {
            if (candidate.inStorage() <= 0) continue;
            long[] count = {0};
            StorageIndex.forEachInBox(candidate.display(), 1, (inner, n) -> {
                if (tile.matchesTile(inner)) count[0] += n;
            });
            if (count[0] > bestCount) {
                best = candidate;
                bestCount = count[0];
            }
        }
        return best;
    }

    private static int keepOneFlag() {
        return ClientConfig.get().keepOne ? TakePayload.KEEP_ONE : 0;
    }

    /** A queued counted take; more notches on the same target in a row add to it. */
    private static final class CountedTake implements BackgroundJobs.Request {
        private final ItemKey tile;
        private final @Nullable ItemStack exact;
        private final int targetSlot;
        private int amount;

        CountedTake(ItemKey tile, @Nullable ItemStack exact, int targetSlot, int amount) {
            this.tile = tile;
            this.exact = exact;
            this.targetSlot = targetSlot;
            this.amount = amount;
        }

        @Override
        public @Nullable BackgroundJob create(Minecraft mc) {
            List<MemoryEntry> inReach = takeSources(mc, tile);
            return inReach == null ? null : TakeJob.counted(inReach, tile, exact, amount, targetSlot);
        }

        @Override
        public boolean absorb(BackgroundJobs.Request next) {
            if (!(next instanceof CountedTake other) || other.targetSlot != targetSlot || !other.tile.equals(tile)) return false;
            if ((exact == null) != (other.exact == null) || (exact != null && !ItemStack.isSameItemSameComponents(exact, other.exact))) {
                return false;
            }
            amount += other.amount;
            return true;
        }
    }

    /**
     * The containers within reach that hold the tile, from its current totals, which earlier
     * queued jobs may have changed. Null after telling the player why there are none.
     */
    private static @Nullable List<MemoryEntry> takeSources(Minecraft mc, ItemKey tile) {
        LocalPlayer player = mc.player;
        if (player == null) return null;
        ItemTotal total = StorageIndex.compute(mc).stream().filter(t -> t.key().equals(tile)).findFirst().orElse(null);
        if (total == null) {
            player.sendOverlayMessage(Component.translatable("craftysearch.storage.gone"));
            return null;
        }
        if (total.sources().isEmpty()) {
            player.sendOverlayMessage(Component.translatable("craftysearch.highlight.none", total.display().getHoverName()));
            return null;
        }
        List<MemoryEntry> inReach = total.sources().stream().filter(e -> RecipeStorage.isReachable(player, e)).toList();
        if (inReach.isEmpty()) {
            // Spec 3.4: beyond reach in client-only mode, show where it is instead.
            locate(mc, total);
            player.sendOverlayMessage(Component.translatable("craftysearch.storage.out_of_reach", total.display().getHoverName()));
            return null;
        }
        // With "Leave one" on, a container down to its kept one isn't worth opening.
        List<MemoryEntry> withSome = inReach.stream().filter(e -> e.takeableCounts().keySet().stream()
                .anyMatch(key -> tile.matchesTile(key.stack()))).toList();
        if (withSome.isEmpty()) {
            player.sendOverlayMessage(Component.translatable("craftysearch.storage.kept_one"));
            return null;
        }
        return withSome;
    }

    /** Stores the cursor stack, or one item of it. */
    public static void putAwayCursor(Minecraft mc, boolean one) {
        putAway(mc, PutAwayPayload.CURSOR, one ? 1 : PutAwayPayload.WHOLE_STACK);
    }

    /** Stores the whole stack in main-inventory slot {@code inventorySlot} (a shift-click). */
    public static void putAwaySlot(Minecraft mc, int inventorySlot) {
        putAway(mc, inventorySlot, PutAwayPayload.WHOLE_STACK);
    }

    /** Stores {@code amount} items from main-inventory slot {@code inventorySlot}: the scroll wheel (spec 3.5). */
    public static void putAwayCounted(Minecraft mc, int inventorySlot, int amount) {
        if (amount > 0) putAway(mc, inventorySlot, amount);
    }

    /**
     * Stores {@code amount} of a tile's item from the inventory: the scroll wheel over a tile.
     * They come from the smallest stack of it, the rows above the hotbar first, so full stacks
     * and the hotbar stay as they are. Nothing happens if the inventory has none.
     */
    public static void putAwayTile(Minecraft mc, ItemKey tile, int amount) {
        if (mc.player == null) return;
        Inventory inventory = mc.player.getInventory();
        int best = -1;
        for (int i : MAIN_ROWS_FIRST) {
            ItemStack stack = inventory.getItem(i);
            if (!tile.matchesTile(stack) || IpnLockedSlots.isLocked(i)) continue;
            if (best < 0 || stack.getCount() < inventory.getItem(best).getCount()) best = i;
        }
        if (best >= 0) putAwayCounted(mc, best, amount);
    }

    /**
     * Ctrl+scroll over a tile (Nov 9): stores {@code stacks} whole stacks of it, the smallest
     * first, the rows above the hotbar before the hotbar.
     */
    public static void putAwayTileStacks(Minecraft mc, ItemKey tile, int stacks) {
        if (mc.player == null) return;
        Inventory inventory = mc.player.getInventory();
        long now = System.currentTimeMillis();
        List<Integer> slots = new ArrayList<>();
        for (int i : MAIN_ROWS_FIRST) {
            if (tile.matchesTile(inventory.getItem(i)) && !IpnLockedSlots.isLocked(i) && now - STACK_SENT_AT[i] > STACK_RESEND_MILLIS) {
                slots.add(i);
            }
        }
        // A stable sort: equal stacks keep the rows-above-first order.
        slots.sort(Comparator.comparingInt(i -> inventory.getItem(i).getCount()));
        for (int i = 0; i < Math.min(stacks, slots.size()); i++) {
            int slot = slots.get(i);
            STACK_SENT_AT[slot] = now;
            putAwaySlot(mc, slot);
        }
    }

    private static void putAway(Minecraft mc, int inventorySlot, int amount) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || !allowedInServerMode(player)) return;
        // Every put-away from a slot passes here: a stack IPN has locked stays put.
        if (inventorySlot != PutAwayPayload.CURSOR && IpnLockedSlots.isLocked(inventorySlot)) return;
        // Behind a running take, the stack may still be on its way to the cursor; the queued request checks again.
        if (!BackgroundJobs.isBusy() && stackAt(player, inventorySlot).isEmpty()) return;
        ClientConfig config = ClientConfig.get();
        if (ServerMode.active()) {
            ServerOutbox.send(new PutAwayPayload(inventorySlot, amount, config.putAway.fallback.ordinal(),
                    ServerMode.radius(), config.kindsMask()));
            return;
        }
        BackgroundJobs.submit(mc, new PutAwayRequest(inventorySlot, amount, stackAt(player, inventorySlot).copyWithCount(1)));
    }

    /**
     * A queued put-away. For exact amounts, more notches on the same slot in a row add to it;
     * whole stacks of the same item from other slots in a row join it, so one job stores them
     * all (Nov 9: a shift-double-click opened the container once per stack).
     */
    private static final class PutAwayRequest implements BackgroundJobs.Request {
        private final List<Integer> slots = new ArrayList<>();
        /** What the first slot held when asked; empty if it was still on its way there. */
        private final ItemStack item;
        private int amount;

        PutAwayRequest(int inventorySlot, int amount, ItemStack item) {
            this.slots.add(inventorySlot);
            this.amount = amount;
            this.item = item;
        }

        @Override
        public @Nullable BackgroundJob create(Minecraft mc) {
            return putAwayJob(mc, slots, amount);
        }

        @Override
        public boolean absorb(BackgroundJobs.Request next) {
            if (!(next instanceof PutAwayRequest other)) return false;
            boolean whole = amount == PutAwayPayload.WHOLE_STACK && other.amount == PutAwayPayload.WHOLE_STACK;
            if (!whole) {
                if (!other.slots.equals(slots) || amount == PutAwayPayload.WHOLE_STACK || other.amount == PutAwayPayload.WHOLE_STACK) {
                    return false;
                }
                amount += other.amount;
                return true;
            }
            if (slots.contains(PutAwayPayload.CURSOR) || other.slots.contains(PutAwayPayload.CURSOR)
                    || item.isEmpty() || !ItemStack.isSameItemSameComponents(item, other.item)) return false;
            for (int slot : other.slots) {
                if (!slots.contains(slot)) slots.add(slot);
            }
            return true;
        }
    }

    /**
     * Plans a client-only put-away with the stacks as they are when the job starts. Several
     * slots only come with whole stacks of one item; any that no longer hold it are left out.
     */
    private static @Nullable BackgroundJob putAwayJob(Minecraft mc, List<Integer> slots, int amount) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return null;
        ItemStack first = ItemStack.EMPTY;
        List<Integer> sources = new ArrayList<>();
        for (int slot : slots) {
            ItemStack stack = stackAt(player, slot);
            if (stack.isEmpty() || (!first.isEmpty() && !ItemStack.isSameItemSameComponents(stack, first))) continue;
            if (first.isEmpty()) first = stack;
            sources.add(slot);
        }
        if (sources.isEmpty()) return null;
        ItemStack moving = first;
        int inventorySlot = sources.getFirst();
        List<Remembered> candidates = StorageIndex.nearbyEntries(mc).stream()
                .filter(e -> e.kind().acceptsPutAway() && RecipeStorage.isReachable(player, e))
                .map(e -> Remembered.of(mc, e, moving))
                .toList();
        List<MemoryEntry> order = PutAwayRule.order(candidates, ClientConfig.get().putAway.fallback).stream()
                .map(Remembered::entry).toList();
        if (order.isEmpty()) {
            player.sendOverlayMessage(Component.translatable("craftysearch.storage.no_room_in_reach"));
            return null;
        }
        List<Integer> jobSources = inventorySlot == PutAwayPayload.CURSOR ? List.of(PutAwayJob.CURSOR) : sources;
        int count = amount == PutAwayPayload.WHOLE_STACK ? PutAwayJob.WHOLE_STACK : Math.min(amount, moving.getCount());
        return new PutAwayJob(order, jobSources, count);
    }

    /**
     * False when taking and putting away can't happen at all: the server turned them off, or
     * "All interactions" is off (or remembers no containers). Shift-clicks, drags and the scroll wheel then
     * keep doing what vanilla (or Mouse Tweaks) would.
     */
    public static boolean canMoveItems() {
        return ServerMode.active() ? ServerMode.takeAndPutAwayAllowed() : ClientConfig.get().actsClientOnly();
    }

    /** In server mode, false (after telling the player) when the server turned taking and putting away off. */
    private static boolean allowedInServerMode(LocalPlayer player) {
        if (!ServerMode.active() || ServerMode.takeAndPutAwayAllowed()) return true;
        player.sendOverlayMessage(Component.translatable("craftysearch.storage.disabled"));
        return false;
    }

    private static ItemStack stackAt(LocalPlayer player, int inventorySlot) {
        return inventorySlot == PutAwayPayload.CURSOR ? player.containerMenu.getCarried() : player.getInventory().getItem(inventorySlot);
    }
}
