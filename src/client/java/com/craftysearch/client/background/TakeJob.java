package com.craftysearch.client.background;

import com.craftysearch.client.config.ClientConfig;
import com.craftysearch.client.memory.ContainerRecorder;
import com.craftysearch.client.memory.MemoryEntry;
import com.craftysearch.storage.ItemKey;
import com.craftysearch.storage.KeepOne;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client-only taking from storage (spec 3.4) of a panel tile, from the containers within
 * reach that hold it, nearest first. A stack (or one, Nov 9) onto the cursor goes through an
 * empty inventory slot and is picked up once back in the screen's own menu. Counted (the
 * scroll wheel, spec 3.5) it takes exactly that many, into a given inventory slot first.
 * "All" takes stack after stack until the inventory is full, a throw drops items on the
 * ground straight from the container, and stacks of several tiles come in one go.
 */
final class TakeJob extends BackgroundJob {
    enum Mode {
        /** The biggest stack, onto the cursor or into the inventory. */
        STACK,
        /** One, onto the cursor (or one more onto a held stack of it). */
        ONE,
        /** As much as fits in the inventory. */
        ALL,
        /** Exactly {@code remaining}, into a given slot first. */
        COUNTED,
        /** {@code remaining} thrown on the ground. */
        THROW,
        /** Stacks of several tiles into the inventory, each container visited once for all of them (Nov 9). */
        STACKS
    }

    private final Mode mode;
    private final ItemKey tile;
    /** For {@link Mode#STACKS}: stacks still wanted of each tile. */
    private final Map<ItemKey, Integer> stacks;
    /** Counted takes over an inventory stack match it exactly; otherwise anything on the tile matches. */
    private final @Nullable ItemStack exact;
    private final boolean toCursor;
    private final int targetSlot;
    private int remaining;
    private int parkIndex = -1;
    private boolean taken;
    /** For {@link Mode#ALL}: the inventory filled up. */
    private boolean full;

    private TakeJob(List<MemoryEntry> containers, Mode mode, ItemKey tile, @Nullable ItemStack exact, boolean toCursor,
                    int targetSlot, int remaining, Map<ItemKey, Integer> stacks) {
        super(containers);
        this.mode = mode;
        this.tile = tile;
        this.exact = exact == null ? null : exact.copyWithCount(1);
        this.toCursor = toCursor;
        this.targetSlot = targetSlot;
        this.remaining = remaining;
        this.stacks = new LinkedHashMap<>(stacks);
    }

    /**
     * Stacks of several tiles into the inventory ({@code stacks}: how many of each), from
     * {@code containers} nearest first, so each tile's stacks come from its nearest containers
     * and each container opens once (Nov 9: a shift-drag over tiles opened a container per tile).
     */
    static TakeJob stacks(List<MemoryEntry> containers, Map<ItemKey, Integer> stacks) {
        ItemKey first = stacks.keySet().iterator().next();
        return new TakeJob(containers, Mode.STACKS, first, null, false, -1, 0, stacks);
    }

    /** A stack, one, or all that fits; only a stack or one goes onto the cursor. */
    static TakeJob take(List<MemoryEntry> containers, ItemKey tile, Mode mode, boolean toCursor) {
        return new TakeJob(containers, mode, tile, null, toCursor && mode != Mode.ALL, -1, 0, Map.of());
    }

    /** Exactly {@code amount}, into inventory slot {@code targetSlot} first (or -1). */
    static TakeJob counted(List<MemoryEntry> containers, ItemKey tile, @Nullable ItemStack exact, int amount, int targetSlot) {
        return new TakeJob(containers, Mode.COUNTED, tile, exact, false, targetSlot, amount, Map.of());
    }

    /** {@code amount} thrown on the ground in front of the player. */
    static TakeJob throwing(List<MemoryEntry> containers, ItemKey tile, int amount) {
        return new TakeJob(containers, Mode.THROW, tile, null, false, -1, amount, Map.of());
    }

    @Override
    Component progress(int index, int total, boolean finishing) {
        String key = mode == Mode.THROW ? "craftysearch.storage.progress_throw" : "craftysearch.storage.progress_take";
        return Component.translatable(key, index, total);
    }

    /** Headed for the cursor, so it's drawn there while it waits in the parking slot. */
    @Override
    int handSlot() {
        return toCursor ? parkIndex : -1;
    }

    @Override
    ItemStack headingToCursor() {
        return toCursor ? tile.stack() : ItemStack.EMPTY;
    }

    /** A plain shift-click moves the stack itself; anything else for the cursor needs a slot to put it in. */
    private boolean needsParking() {
        return mode == Mode.ONE || (mode == Mode.STACK && toCursor);
    }

    @Override
    Result prepare(Minecraft mc, AbstractContainerMenu menu) {
        if ((mode == Mode.COUNTED || mode == Mode.ALL || mode == Mode.STACKS)
                && Parking.roomFor(menu, exact != null ? exact : tile.stack(), targetSlot) == null) {
            return Result.fail("craftysearch.storage.no_space");
        }
        if (needsParking()) {
            parkIndex = Parking.emptySlot(mc.player.getInventory());
            if (parkIndex < 0) return Result.fail("craftysearch.storage.need_free_slot");
        }
        return Result.NEXT;
    }

    @Override
    Result work(Minecraft mc, AbstractContainerMenu container) {
        List<Slot> slots = ContainerRecorder.containerSlots(container);
        boolean[] kept = keptSlots(slots);
        return switch (mode) {
            case COUNTED -> takeCounted(mc, container, slots, kept);
            case ALL -> takeAll(mc, container, slots, kept);
            case THROW -> throwOut(mc, container, slots, kept);
            case STACKS -> takeStacks(mc, container, slots, kept);
            case STACK, ONE -> takeStack(mc, container, slots, kept);
        };
    }

    /** One stack per step, of the first tile still wanted that this container has. */
    private Result takeStacks(Minecraft mc, AbstractContainerMenu container, List<Slot> slots, boolean[] kept) {
        for (Map.Entry<ItemKey, Integer> wanted : stacks.entrySet()) {
            if (wanted.getValue() <= 0) continue;
            int index = biggestMatch(slots, kept, wanted.getKey());
            if (index < 0) continue;
            Slot slot = slots.get(index);
            int moved = kept[index] ? Parking.moveAllButOne(mc, container, slot) : Parking.quickMoveOut(mc, container, slot);
            if (moved <= 0) {
                full = true;
                return taken ? Result.DONE_NO_MESSAGE : Result.fail("craftysearch.storage.no_space");
            }
            taken = true;
            wanted.setValue(wanted.getValue() - 1);
            return stacksLeft() ? Result.AGAIN : Result.DONE_NO_MESSAGE;
        }
        return Result.NEXT;
    }

    private boolean stacksLeft() {
        return stacks.values().stream().anyMatch(n -> n > 0);
    }

    private Result takeStack(Minecraft mc, AbstractContainerMenu container, List<Slot> slots, boolean[] kept) {
        int index = biggestMatch(slots, kept);
        if (index < 0) return Result.NEXT;
        Slot slot = slots.get(index);
        boolean keepHere = kept[index];
        if (!needsParking()) {
            // A shift-click would move the whole stack, the kept one too; otherwise a shift-click,
            // or by hand around IPN's locked slots.
            int moved = keepHere ? Parking.moveAllButOne(mc, container, slot) : Parking.quickMoveOut(mc, container, slot);
            if (moved <= 0) return Result.fail("craftysearch.storage.no_space");
            taken = true;
            return Result.DONE_NO_MESSAGE;
        }
        Slot park = Parking.slotFor(container, parkIndex);
        if (park == null || park.hasItem()) return Result.fail("craftysearch.storage.need_free_slot");
        Parking.click(mc, container, slot, 0, ContainerInput.PICKUP);
        if (mode == Mode.ONE) {
            // One into the parking slot, the rest back where it was (a kept slot holds at least two).
            Parking.click(mc, container, park, 1, ContainerInput.PICKUP);
            Parking.click(mc, container, slot, 0, ContainerInput.PICKUP);
        } else {
            // A whole stack puts the kept one back first.
            if (keepHere) Parking.click(mc, container, slot, 1, ContainerInput.PICKUP);
            Parking.click(mc, container, park, 0, ContainerInput.PICKUP);
        }
        if (!container.getCarried().isEmpty()) {
            // Didn't fit after all: put it back rather than leave it on a cursor that could drop it.
            Parking.click(mc, container, slot, 0, ContainerInput.PICKUP);
            return Result.fail("craftysearch.storage.need_free_slot");
        }
        taken = true;
        return Result.DONE_NO_MESSAGE;
    }

    /** One stack per step, as a shift-click moves it, until the inventory is full or the container has no more. */
    private Result takeAll(Minecraft mc, AbstractContainerMenu container, List<Slot> slots, boolean[] kept) {
        int index = biggestMatch(slots, kept);
        if (index < 0) return Result.NEXT;
        Slot slot = slots.get(index);
        int want = KeepOne.takeable(slot.getItem(), kept[index]);
        int moved = kept[index] ? Parking.moveAllButOne(mc, container, slot) : Parking.quickMoveOut(mc, container, slot);
        if (moved > 0) taken = true;
        if (moved < want) {
            full = true;
            return taken ? Result.DONE_NO_MESSAGE : Result.fail("craftysearch.storage.no_space");
        }
        return Result.AGAIN;
    }

    /** From the biggest stack each step, straight onto the ground. */
    private Result throwOut(Minecraft mc, AbstractContainerMenu container, List<Slot> slots, boolean[] kept) {
        int index = biggestMatch(slots, kept);
        if (index < 0) return Result.NEXT;
        Slot slot = slots.get(index);
        int dropped = Parking.drop(mc, container, slot, Math.min(remaining, KeepOne.takeable(slot.getItem(), kept[index])));
        if (dropped <= 0) return Result.NEXT;
        taken = true;
        remaining -= dropped;
        return remaining <= 0 ? Result.DONE_NO_MESSAGE : Result.AGAIN;
    }

    /**
     * For each matching stack: pick it up, drop what's wanted where there's room (the target
     * slot first), and put the rest back. A drop places all that fits when that's no more
     * than wanted, otherwise one item at a time. All in one tick, so nothing can interrupt
     * while the hidden container's cursor holds the stack.
     */
    private Result takeCounted(Minecraft mc, AbstractContainerMenu container, List<Slot> slots, boolean[] kept) {
        for (int s = 0; s < slots.size(); s++) {
            Slot source = slots.get(s);
            if (remaining == 0) break;
            if (!matches(source.getItem())) continue;
            ItemStack item = source.getItem().copyWithCount(1);
            int want = Math.min(remaining, KeepOne.takeable(source.getItem(), kept[s]));
            if (want <= 0) continue;
            Parking.click(mc, container, source, 0, ContainerInput.PICKUP);
            int moved = 0;
            while (moved < want && !container.getCarried().isEmpty()) {
                Slot target = Parking.roomFor(container, item, targetSlot);
                if (target == null) break;
                int carried = container.getCarried().getCount();
                int space = target.getMaxStackSize(item) - target.getItem().getCount();
                if (Math.min(carried, space) <= want - moved) {
                    Parking.click(mc, container, target, 0, ContainerInput.PICKUP);
                    int placed = carried - container.getCarried().getCount();
                    if (placed <= 0) break;
                    moved += placed;
                } else {
                    Parking.click(mc, container, target, 1, ContainerInput.PICKUP);
                    moved++;
                }
            }
            if (!container.getCarried().isEmpty()) Parking.click(mc, container, source, 0, ContainerInput.PICKUP);
            remaining -= moved;
            if (moved > 0) taken = true;
            if (moved < want) return taken ? Result.DONE_NO_MESSAGE : Result.fail("craftysearch.storage.no_space");
        }
        return remaining == 0 ? Result.DONE_NO_MESSAGE : Result.NEXT;
    }

    private boolean matches(ItemStack stack) {
        return exact != null ? ItemStack.isSameItemSameComponents(stack, exact) : tile.matchesTile(stack);
    }

    @Override
    boolean satisfied() {
        return switch (mode) {
            case COUNTED, THROW -> remaining <= 0;
            case ALL -> full;
            case STACKS -> full || !stacksLeft();
            case STACK, ONE -> taken;
        };
    }

    @Override
    Result finish(Minecraft mc, AbstractContainerMenu menu, long now) {
        if (!taken) return Result.fail("craftysearch.storage.gone");
        if (toCursor) toCursor(mc, menu);
        return Result.done(null);
    }

    /** From the parking slot onto the cursor, or onto the stack already held if it's the same item and there's room. */
    private void toCursor(Minecraft mc, AbstractContainerMenu menu) {
        Slot park = Parking.slotFor(menu, parkIndex);
        if (park == null || !park.hasItem()) return;
        ItemStack held = menu.getCarried();
        ItemStack parked = park.getItem();
        if (held.isEmpty()) {
            Parking.click(mc, menu, park, 0, ContainerInput.PICKUP);
        } else if (ItemStack.isSameItemSameComponents(held, parked)
                && held.getCount() + parked.getCount() <= park.getMaxStackSize(parked)) {
            // The held stack goes onto the parked one, then the lot is picked up.
            Parking.click(mc, menu, park, 0, ContainerInput.PICKUP);
            Parking.click(mc, menu, park, 0, ContainerInput.PICKUP);
        }
        // Otherwise it stays in the inventory.
    }

    /** The matching slot with the most to take, or -1. */
    private int biggestMatch(List<Slot> slots, boolean[] kept) {
        return biggestMatch(slots, kept, tile);
    }

    private static int biggestMatch(List<Slot> slots, boolean[] kept, ItemKey tile) {
        int best = -1;
        int bestCount = 0;
        for (int i = 0; i < slots.size(); i++) {
            ItemStack stack = slots.get(i).getItem();
            if (!tile.matchesTile(stack)) continue;
            int count = KeepOne.takeable(stack, kept[i]);
            if (count > bestCount) {
                best = i;
                bestCount = count;
            }
        }
        return best;
    }

    /** The slots that keep one with "Leave one" on; none otherwise. */
    static boolean[] keptSlots(List<Slot> slots) {
        if (!ClientConfig.get().keepOne) return new boolean[slots.size()];
        return KeepOne.keptSlots(slots.stream().map(Slot::getItem).toList());
    }
}
