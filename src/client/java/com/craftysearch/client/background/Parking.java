package com.craftysearch.client.background;

import com.craftysearch.client.compat.IpnLockedSlots;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Helpers for moving items through an empty inventory slot. A background container has its
 * own cursor, and vanilla drops a cursor item if a container closes with no room, so items
 * travel between menus via a "parking" slot instead of the cursor.
 */
final class Parking {
    private static final int HOTBAR_SIZE = Inventory.SELECTION_SIZE;
    /** The hotbar and the three rows above it. */
    private static final int MAIN_SIZE = Inventory.INVENTORY_SIZE;

    private Parking() {
    }

    /**
     * An empty main-inventory slot, the rows above the hotbar first, or -1. Never one IPN has
     * locked: IPN may stop clicks there, which would strand the item on a hidden cursor.
     */
    static int emptySlot(Inventory inventory) {
        for (int i = HOTBAR_SIZE; i < MAIN_SIZE; i++) {
            if (inventory.getItem(i).isEmpty() && !IpnLockedSlots.isLocked(i)) return i;
        }
        for (int i = 0; i < HOTBAR_SIZE; i++) {
            if (inventory.getItem(i).isEmpty() && !IpnLockedSlots.isLocked(i)) return i;
        }
        return -1;
    }

    /**
     * A main-inventory slot of {@code menu} with room for one more {@code item}: inventory
     * slot {@code preferred} if it has room (or -1 for none), then a stack of it, then an
     * empty slot. Null if there's no room. In the order vanilla's quick-move fills them, from
     * the end of the menu: the hotbar right to left, then the rows above from the bottom
     * (Nov 8: in menu order the rows above filled first).
     */
    static @Nullable Slot roomFor(AbstractContainerMenu menu, ItemStack item, int preferred) {
        Slot stack = null;
        Slot empty = null;
        for (int i = menu.slots.size() - 1; i >= 0; i--) {
            Slot slot = menu.slots.get(i);
            if (!(slot.container instanceof Inventory) || slot.getContainerSlot() >= MAIN_SIZE) continue;
            if (IpnLockedSlots.isLocked(slot.getContainerSlot())) continue;
            ItemStack there = slot.getItem();
            boolean room = there.isEmpty() ? slot.mayPlace(item)
                    : ItemStack.isSameItemSameComponents(there, item) && there.getCount() < slot.getMaxStackSize(there);
            if (!room) continue;
            if (slot.getContainerSlot() == preferred) return slot;
            if (there.isEmpty()) {
                if (empty == null) empty = slot;
            } else if (stack == null) {
                stack = slot;
            }
        }
        return stack != null ? stack : empty;
    }

    /** The menu's slot showing inventory slot {@code index}, or null. */
    static @Nullable Slot slotFor(AbstractContainerMenu menu, int index) {
        for (Slot slot : menu.slots) {
            if (slot.container instanceof Inventory && slot.getContainerSlot() == index) return slot;
        }
        return null;
    }

    /**
     * "Leave one": moves all but one of {@code source}'s stack into the inventory in a few
     * clicks: pick it up, put one back, drop the rest where there's room, and anything that
     * didn't fit back on the one left. All in one tick, so the hidden container's cursor is
     * never left holding it. Returns how many moved.
     */
    static int moveAllButOne(Minecraft mc, AbstractContainerMenu container, Slot source) {
        return moveToInventory(mc, container, source, 1);
    }

    /**
     * A container stack into the inventory, as a shift-click would move it. Vanilla's
     * quick-move fills the first empty slot, locked by IPN or not, so while any slot is locked
     * the stack goes by hand to slots {@link #roomFor} allows. Returns how many moved.
     */
    static int quickMoveOut(Minecraft mc, AbstractContainerMenu container, Slot source) {
        if (IpnLockedSlots.anyLocked()) return moveToInventory(mc, container, source, 0);
        ItemStack before = source.getItem().copy();
        click(mc, container, source, 0, ContainerInput.QUICK_MOVE);
        ItemStack after = source.getItem();
        return after.isEmpty() || !ItemStack.isSameItemSameComponents(after, before) ? before.getCount()
                : before.getCount() - after.getCount();
    }

    /** All but {@code keep} (0 or 1) of the stack into the inventory, by hand; see {@link #moveAllButOne}. */
    private static int moveToInventory(Minecraft mc, AbstractContainerMenu container, Slot source, int keep) {
        ItemStack item = source.getItem().copyWithCount(1);
        int before = source.getItem().getCount();
        if (before <= keep) return 0;
        click(mc, container, source, 0, ContainerInput.PICKUP);
        if (keep > 0) click(mc, container, source, 1, ContainerInput.PICKUP);
        // A partial stack takes only part of it, so a few drops may be needed.
        for (int i = 0; i < MAIN_SIZE && !container.getCarried().isEmpty(); i++) {
            Slot target = roomFor(container, item, -1);
            if (target == null) break;
            click(mc, container, target, 0, ContainerInput.PICKUP);
        }
        if (!container.getCarried().isEmpty()) click(mc, container, source, 0, ContainerInput.PICKUP);
        return before - source.getItem().getCount();
    }

    /**
     * Throws {@code amount} of {@code slot}'s stack on the ground, in as few clicks as it
     * takes: the whole stack at once, one at a time, or (for most of a big stack) picking it
     * up, putting back what stays one at a time and throwing the rest. Returns how many went.
     */
    static int drop(Minecraft mc, AbstractContainerMenu menu, Slot slot, int amount) {
        ItemStack before = slot.getItem().copy();
        int count = before.getCount();
        if (amount <= 0 || count == 0) return 0;
        if (amount >= count) {
            click(mc, menu, slot, 1, ContainerInput.THROW);
        } else if (amount <= count - amount + 2) {
            for (int i = 0; i < amount; i++) click(mc, menu, slot, 0, ContainerInput.THROW);
        } else {
            click(mc, menu, slot, 0, ContainerInput.PICKUP);
            for (int i = 0; i < count - amount; i++) click(mc, menu, slot, 1, ContainerInput.PICKUP);
            clickOutside(mc, menu, 0);
        }
        ItemStack after = slot.getItem();
        return after.isEmpty() || !ItemStack.isSameItemSameComponents(after, before) ? count : count - after.getCount();
    }

    private static boolean clicking;

    /** A click in {@code menu}, predicted locally and sent to the server like a player's. */
    static void click(Minecraft mc, AbstractContainerMenu menu, Slot slot, int button, ContainerInput input) {
        clicking = true;
        try {
            mc.gameMode.handleContainerInput(menu.containerId, slot.index, button, input, mc.player);
        } finally {
            clicking = false;
        }
    }

    /** A click outside the window: button 0 throws the cursor stack, 1 one of it. */
    private static void clickOutside(Minecraft mc, AbstractContainerMenu menu, int button) {
        clicking = true;
        try {
            mc.gameMode.handleContainerInput(menu.containerId, AbstractContainerMenu.SLOT_CLICKED_OUTSIDE, button,
                    ContainerInput.PICKUP, mc.player);
        } finally {
            clicking = false;
        }
    }

    /** True during the mod's own clicks, which the click guard lets through. */
    static boolean isOwnClick() {
        return clicking;
    }
}
