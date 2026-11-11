package com.craftysearch.server;

import com.craftysearch.ServerConfig;
import com.craftysearch.network.PutAwayPayload;
import com.craftysearch.network.TakePayload;
import com.craftysearch.server.StorageScanner.Found;
import com.craftysearch.storage.ContainerKind;
import com.craftysearch.storage.ItemKey;
import com.craftysearch.storage.PutAwayMatch;
import com.craftysearch.storage.PutAwayRule;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Taking from and putting away into storage from the panel, in server mode (spec 3.4).
 * Items move straight between containers and the player's cursor or inventory.
 */
public final class StorageTransfer {
    private static final int MAX_REQUESTS_PER_TICK = 4;
    /** Per player: the tick last counted, and how many requests came in it. */
    private static final Map<UUID, long[]> REQUESTS_THIS_TICK = new HashMap<>();

    /** A put-away target, described for the item being stored. */
    private record Target(Found found, int match, boolean isCopperChest, boolean isEmpty, boolean hasRoom)
            implements PutAwayRule.Candidate {
        static Target of(ServerLevel level, Found found, ItemStack moving) {
            boolean empty = true;
            boolean room = false;
            List<ItemStack> held = new ArrayList<>();
            for (Container container : found.halves()) {
                for (int i = 0; i < container.getContainerSize(); i++) {
                    ItemStack stack = container.getItem(i);
                    if (stack.isEmpty()) {
                        room |= container.canPlaceItem(i, moving);
                        continue;
                    }
                    empty = false;
                    held.add(stack);
                    room |= ItemStack.isSameItemSameComponents(stack, moving) && stack.getCount() < stack.getMaxStackSize();
                }
            }
            return new Target(found, PutAwayMatch.of(moving, held), found.isCopperChest(level), empty, room);
        }
    }

    private StorageTransfer() {
    }

    public static void forget(ServerPlayer player) {
        REQUESTS_THIS_TICK.remove(player.getUUID());
    }

    /**
     * Takes each requested item in turn. The containers are found once for the whole request;
     * a shift-drag over tiles sends many items at once (Nov 9). At most one message is shown.
     */
    public static void take(MinecraftServer server, ServerPlayer player, TakePayload request) {
        if (!allowed(server, player) || request.items().isEmpty()) return;
        boolean toCursor = request.has(TakePayload.TO_CURSOR);
        if (toCursor && request.items().size() > 1) return;
        StorageSync.changedBy(player);

        List<Found> found = nearestFirst(player, request.radius(), ContainerKind.fromMask(request.kindsMask()));
        KeptSlots kept = KeptSlots.of(found, request.has(TakePayload.KEEP_ONE));
        String problem = null;
        for (ItemStack item : request.items()) {
            if (item.isEmpty()) continue;
            String result = takeOne(player, request, item, found, kept);
            if (problem == null) problem = result;
        }
        if (problem != null) message(player, problem);
    }

    /** Takes one item of the request; returns what went wrong (a message key under craftysearch.storage), or null. */
    private static @Nullable String takeOne(ServerPlayer player, TakePayload request, ItemStack item, List<Found> found,
                                            KeptSlots kept) {
        AbstractContainerMenu menu = player.containerMenu;
        boolean toCursor = request.has(TakePayload.TO_CURSOR);
        ItemKey tile = ItemKey.normalized(item);
        // Onto a held stack ("Take one" again on its tile, Nov 9), only what stacks with it.
        ItemStack held = toCursor ? menu.getCarried() : ItemStack.EMPTY;
        // The cursor holds something else: the click that sent this saw an empty cursor, so it's stale.
        if (!held.isEmpty() && !tile.matchesTile(held)) return null;
        boolean exact = request.has(TakePayload.EXACT) || !held.isEmpty();
        ItemStack wanted = held.isEmpty() ? item : held;
        // The first matching stack there's some to take of decides the exact item; only stacks that stack with it are taken.
        ItemStack template = null;
        boolean anyMatch = false;
        for (Found f : found) {
            for (Container container : f.halves()) {
                for (int i = 0; i < container.getContainerSize() && template == null; i++) {
                    ItemStack stack = container.getItem(i);
                    if (exact ? !ItemStack.isSameItemSameComponents(stack, wanted) : !tile.matchesTile(stack)) continue;
                    anyMatch = true;
                    if (kept.takeable(container, i) > 0) template = stack.copyWithCount(1);
                }
            }
        }
        if (template == null) {
            // With "Leave one" on, all that's left may be the one each container keeps.
            return anyMatch ? "kept_one" : "gone";
        }
        int total = 0;
        for (Found f : found) {
            for (Container container : f.halves()) {
                for (int i = 0; i < container.getContainerSize(); i++) {
                    if (ItemStack.isSameItemSameComponents(template, container.getItem(i))) total += kept.takeable(container, i);
                }
            }
        }

        int maxStack = template.getMaxStackSize();
        // A counted take (the scroll wheel, Ctrl+scroll, "Take one", a throw) may be several stacks;
        // take all is as much as fits; otherwise one stack.
        int amount;
        if (request.has(TakePayload.ALL)) amount = total;
        else if (request.amount() > 0) amount = Math.min(total, request.amount());
        else amount = Math.min(total, maxStack);
        long locked = request.lockedSlots();
        boolean drop = request.has(TakePayload.DROP);
        if (toCursor) {
            amount = Math.min(amount, maxStack - held.getCount());
            if (amount <= 0) return null;
        } else if (!drop) {
            amount = Math.min(amount, LockedSlots.spaceFor(player.getInventory(), template, locked));
            if (amount <= 0) return "no_space";
        }

        ItemStack taken = collect(found, kept, template, amount);
        if (toCursor) {
            if (held.isEmpty()) menu.setCarried(taken);
            else held.grow(taken.getCount());
            return null;
        }
        // In stacks no bigger than the item allows: an unstackable item never shares a slot.
        while (!taken.isEmpty()) {
            ItemStack part = taken.split(Math.min(taken.getCount(), maxStack));
            if (drop) {
                // As the drop key throws it from the hand.
                player.drop(part, false, true);
                continue;
            }
            // The scroll wheel over an inventory stack fills that stack first (spec 3.5), unless it's locked.
            if (!LockedSlots.isLocked(locked, request.targetSlot())) intoSlot(player.getInventory(), request.targetSlot(), part);
            LockedSlots.add(player.getInventory(), part, locked);
            // Only if the space check was wrong: back into storage rather than on the ground.
            if (!part.isEmpty()) {
                part = ContainerInsert.insert(found.stream().filter(f -> f.kind().acceptsPutAway())
                        .flatMap(f -> f.halves().stream()).toList(), part);
            }
            if (!part.isEmpty()) player.getInventory().placeItemBackInInventory(part);
        }
        return null;
    }

    /**
     * Stores the stack from each requested slot in turn. The containers are found once for the
     * whole request; each item is matched to them afresh, as the earlier ones fill them up.
     */
    public static void putAway(MinecraftServer server, ServerPlayer player, PutAwayPayload request) {
        if (!allowed(server, player) || request.slots().isEmpty()) return;
        StorageSync.changedBy(player);
        Set<ContainerKind> kinds = ContainerKind.fromMask(request.kindsMask()).stream()
                .filter(ContainerKind::acceptsPutAway).collect(Collectors.toSet());
        List<Found> found = nearestFirst(player, request.radius(), kinds);
        boolean noRoom = false;
        for (int slot : request.slots()) {
            noRoom |= !putAwaySlot(player, slot, request.amount(), found, request.fallbackMode());
        }
        if (noRoom) message(player, "no_room");
    }

    /** False if some of the stack didn't fit anywhere (it stays where it was). */
    private static boolean putAwaySlot(ServerPlayer player, int slot, int amount, List<Found> found, PutAwayRule.Fallback fallback) {
        AbstractContainerMenu menu = player.containerMenu;
        Inventory inventory = player.getInventory();
        boolean fromCursor = slot == PutAwayPayload.CURSOR;
        // Main inventory only (hotbar and the rows above it), never armour or the offhand.
        if (!fromCursor && (slot < 0 || slot >= inventory.getNonEquipmentItems().size())) return true;
        ItemStack source = fromCursor ? menu.getCarried() : inventory.getItem(slot);
        if (source.isEmpty()) return true;

        int count = amount > 0 ? Math.min(amount, source.getCount()) : source.getCount();
        ItemStack moving = source.split(count);
        ServerLevel level = player.level();
        ItemStack probe = moving;
        List<Target> targets = found.stream().map(f -> Target.of(level, f, probe)).toList();

        for (Target target : PutAwayRule.order(targets, fallback)) {
            // Both halves of a double chest together, so partial stacks in either fill up first.
            moving = ContainerInsert.insert(target.found().halves(), moving);
            if (moving.isEmpty()) break;
        }
        boolean fitted = moving.isEmpty();
        if (!fitted) {
            // Nothing had room: it goes back where it came from, never on the ground.
            if (source.isEmpty()) source = moving;
            else source.grow(moving.getCount());
        }
        if (fromCursor) {
            menu.setCarried(source);
        } else {
            inventory.setItem(slot, source);
            inventory.setChanged();
        }
        return fitted;
    }

    private static boolean allowed(MinecraftServer server, ServerPlayer player) {
        ServerConfig config = ServerConfig.get();
        if (!config.enabled || player.isSpectator()) return false;
        if (!config.allowTakeAndPutAway) {
            message(player, "disabled");
            return false;
        }
        // A few requests per tick: a quick shift-drag or scroll can send several, and the client
        // already spaces them out; more than that would let a spamming client cost a lot.
        long tick = server.getTickCount();
        long[] seen = REQUESTS_THIS_TICK.computeIfAbsent(player.getUUID(), id -> new long[] {-1, 0});
        if (seen[0] != tick) {
            seen[0] = tick;
            seen[1] = 0;
        }
        return ++seen[1] <= MAX_REQUESTS_PER_TICK;
    }

    /** Moves what fits of {@code stack} into main-inventory slot {@code slot}, if it's a real slot. */
    private static void intoSlot(Inventory inventory, int slot, ItemStack stack) {
        if (slot < 0 || slot >= inventory.getNonEquipmentItems().size() || stack.isEmpty()) return;
        ItemStack there = inventory.getItem(slot);
        if (there.isEmpty()) {
            inventory.setItem(slot, stack.split(Math.min(stack.getCount(), stack.getMaxStackSize())));
        } else if (ItemStack.isSameItemSameComponents(there, stack)) {
            int move = Math.min(stack.getCount(), there.getMaxStackSize() - there.getCount());
            if (move <= 0) return;
            there.grow(move);
            stack.shrink(move);
        } else {
            return;
        }
        inventory.setChanged();
    }

    private static List<Found> nearestFirst(ServerPlayer player, int radius, Set<ContainerKind> kinds) {
        List<Found> found = StorageScanner.scan(player, StorageSync.clampRadius(radius), kinds);
        Vec3 eye = player.getEyePosition();
        found.sort(Comparator.comparingDouble(f -> f.distanceSqr(eye)));
        return found;
    }

    private static ItemStack collect(List<Found> found, KeptSlots kept, ItemStack template, int amount) {
        ItemStack result = ItemStack.EMPTY;
        for (Found f : found) {
            for (Container container : f.halves()) {
                for (int i = 0; i < container.getContainerSize() && result.getCount() < amount; i++) {
                    ItemStack stack = container.getItem(i);
                    if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, template)) continue;
                    int takeable = kept.takeable(container, i);
                    if (takeable <= 0) continue;
                    ItemStack part = container.removeItem(i, Math.min(takeable, amount - result.getCount()));
                    container.setChanged();
                    if (result.isEmpty()) result = part;
                    else result.grow(part.getCount());
                }
            }
        }
        return result;
    }

    private static void message(ServerPlayer player, String key) {
        player.sendOverlayMessage(Component.translatable("craftysearch.storage." + key));
    }
}
