package com.craftysearch.server;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Filling the main inventory around slots the client says are locked (Inventory Profiles
 * Next's, Nov 8), for items taken or pulled from storage. Vanilla's own fill uses the first
 * empty slot, locked or not. With nothing locked, vanilla's fill is used as before.
 */
final class LockedSlots {
    /** The hotbar and the three rows above it. */
    private static final int MAIN_SIZE = Inventory.INVENTORY_SIZE;

    private LockedSlots() {
    }

    static boolean isLocked(long locked, int slot) {
        return slot >= 0 && slot < MAIN_SIZE && (locked & (1L << slot)) != 0;
    }

    /** Adds what fits of {@code stack} to the inventory: onto matching stacks first, then into empty slots, skipping locked ones. */
    static void add(Inventory inventory, ItemStack stack, long locked) {
        if (locked == 0) {
            inventory.add(stack);
            return;
        }
        int size = Math.min(MAIN_SIZE, inventory.getNonEquipmentItems().size());
        for (int i = 0; i < size && !stack.isEmpty(); i++) {
            if (isLocked(locked, i)) continue;
            ItemStack there = inventory.getItem(i);
            if (there.isEmpty() || !ItemStack.isSameItemSameComponents(there, stack)) continue;
            int move = Math.min(stack.getCount(), there.getMaxStackSize() - there.getCount());
            if (move <= 0) continue;
            there.grow(move);
            stack.shrink(move);
        }
        for (int i = 0; i < size && !stack.isEmpty(); i++) {
            if (isLocked(locked, i) || !inventory.getItem(i).isEmpty()) continue;
            inventory.setItem(i, stack.split(Math.min(stack.getCount(), stack.getMaxStackSize())));
        }
        inventory.setChanged();
    }

    /** How many of {@code stack} the main inventory can take, outside locked slots. */
    static int spaceFor(Inventory inventory, ItemStack stack, long locked) {
        int space = 0;
        int size = Math.min(MAIN_SIZE, inventory.getNonEquipmentItems().size());
        for (int i = 0; i < size; i++) {
            if (isLocked(locked, i)) continue;
            ItemStack slot = inventory.getItem(i);
            if (slot.isEmpty()) space += stack.getMaxStackSize();
            else if (ItemStack.isSameItemSameComponents(slot, stack)) space += Math.max(0, slot.getMaxStackSize() - slot.getCount());
        }
        return space;
    }
}
