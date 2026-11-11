package com.craftysearch.client.gui;

import com.craftysearch.client.background.BackgroundJobs;
import com.craftysearch.client.background.InteractionHold;
import com.craftysearch.client.background.StorageActions;
import com.craftysearch.client.config.ClientConfig;
import com.craftysearch.client.mixin.AbstractContainerScreenAccessor;
import com.craftysearch.client.storage.StorageIndex.ItemTotal;
import com.craftysearch.storage.ItemKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * The scroll wheel and shift-drag on storage (spec 3.5), for the screens with a panel and
 * the storage screen ({@code screen} null: no inventory slots there). Scroll down takes from
 * storage and up puts away, by default, one item per notch (with Ctrl a stack), over inventory
 * stacks and tiles alike; Shift+scroll scrolls the grid.
 */
final class PanelMouse {
    private enum Drag { INVENTORY, TILES }

    private static @Nullable Drag drag;
    /** Tiles or inventory slots this drag has done already, once each. */
    private static final Set<Object> DONE = new HashSet<>();
    /** Wheel movement toward the next notch, for smooth wheels and touchpads, per target. */
    private static double scrollLeft;
    private static @Nullable Object scrollTarget;

    private PanelMouse() {
    }

    /**
     * A press: a shift-press on a tile, or with the panel showing on or beside the inventory
     * slots with the put-away button, starts a drag. The press itself is done the usual way: the grid
     * takes, the screen's quick-move puts away. Mouse Tweaks may drag over the same slots
     * (with the left button only); its quick-moves and this drag share {@link #DONE}, so
     * each stack is stored once whoever gets there first.
     */
    static void pressed(@Nullable AbstractContainerScreen<?> screen, StoragePanel panel, MouseButtonEvent event) {
        drag = null;
        DONE.clear();
        PanelHooks.endScreenClick();
        ClientConfig config = ClientConfig.get();
        if (!config.mouse.shiftDrag || !event.hasShiftDown() || !panel.isVisible() || !StorageActions.canMoveItems()) return;
        if (panel.isMouseOver(event.x(), event.y())) {
            ItemTotal total = panel.totalAt(event.x(), event.y());
            if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && total != null) {
                drag = Drag.TILES;
                DONE.add(total.key());
            }
            return;
        }
        if (screen == null || event.button() != config.putAway.shiftClick.button()) return;
        if (!BackgroundJobs.heldItem(screen.getMenu()).isEmpty()) return;
        // A pressed stack is stored by the screen's quick-move right after this, which records it.
        // The drag may also start on an empty slot or beside the slots and store what it passes over
        // (Nov 10: it only started on a stack). Not on a button or the recipe book, and not on a
        // filled slot outside the inventory, such as the crafting result, whose shift-click is its own.
        Slot slot = ((AbstractContainerScreenAccessor) screen).craftysearch$slotAt(event.x(), event.y());
        boolean onWidget = slot == null && screen.getChildAt(event.x(), event.y()).isPresent();
        boolean otherStack = slot != null && slot.hasItem() && !isMainInventory(slot);
        if (!onWidget && !otherStack) drag = Drag.INVENTORY;
    }

    private static boolean isMainInventory(Slot slot) {
        return slot.container instanceof Inventory && slot.getContainerSlot() < Inventory.INVENTORY_SIZE;
    }

    /** True while a drag over inventory stacks is putting them away. */
    static boolean storingDrag() {
        return drag == Drag.INVENTORY;
    }

    /** True if this drag over inventory stacks has stored {@code index} already. */
    static boolean storedInThisDrag(int index) {
        return drag == Drag.INVENTORY && DONE.contains(index);
    }

    /** A quick-move stored {@code index}: during a drag, that stack is done. */
    static void stored(int index) {
        if (drag == Drag.INVENTORY) DONE.add(index);
    }

    /** Over each new tile, a stack of it into the inventory; over each new inventory stack, put away. */
    static void dragged(@Nullable AbstractContainerScreen<?> screen, StoragePanel panel, double x, double y) {
        Drag d = drag;
        if (d == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (d == Drag.TILES) {
            ItemTotal total = panel.totalAt(x, y);
            // A tile only inside shulker boxes brings out its box, as a click does.
            if (total != null && total.total() > 0 && DONE.add(total.key())) {
                InteractionHold.clicked();
                StorageActions.take(mc, total, StorageActions.TakeMode.STACK, false);
            }
        } else if (screen != null) {
            int index = inventoryStackAt(screen, x, y);
            if (index >= 0 && DONE.add(index)) {
                InteractionHold.clicked();
                StorageActions.putAwaySlot(mc, index);
            }
        }
    }

    static void released() {
        drag = null;
        DONE.clear();
    }

    /**
     * Returns true if the scroll was used here. Over the panel: on a tile, items move; with
     * Shift, or over the scrollbar, the search box, the footer or an empty slot, the grid
     * scrolls. Over an inventory stack (panel showing), items move between it and storage.
     * The cursor stack is never touched, so nothing moves while one is held.
     */
    static boolean scrolled(@Nullable AbstractContainerScreen<?> screen, StoragePanel panel,
                            double x, double y, double scrollX, double scrollY) {
        if (!panel.isVisible()) return false;
        Minecraft mc = Minecraft.getInstance();
        ClientConfig.Mouse config = ClientConfig.get().mouse;
        boolean shift = mc.hasShiftDown();
        // Ctrl+scroll moves a stack per notch (Nov 9).
        boolean stacks = mc.hasControlDown();
        // Shift+wheel arrives as sideways scrolling on some systems.
        double amount = scrollY != 0 ? scrollY : shift ? scrollX : 0;
        if (amount == 0) return false;
        boolean holding = screen != null && !BackgroundJobs.heldItem(screen.getMenu()).isEmpty();
        boolean canMove = StorageActions.canMoveItems();

        if (panel.isMouseOver(x, y)) {
            ItemTotal total = panel.totalAt(x, y);
            if (shift || holding || !canMove || !config.scrollOverTiles || total == null) {
                panel.scrollGrid(amount);
                return true;
            }
            int notches = notches(total.key(), amount);
            if (notches == 0) return true;
            // A tile scrolled empty stays in place as a placeholder while the scrolling goes on, and more
            // notches on it do nothing rather than scroll the grid under the mouse (Nov 9); scrolling the
            // other way still puts it back.
            InteractionHold.clicked();
            int n = Math.abs(notches);
            if (takes(config, notches)) {
                // One item per notch, with Ctrl a stack (Nov 9); a tile only inside shulker boxes has none loose to give.
                if (total.inStorage() > 0) StorageActions.takeCounted(mc, total.key(), null, n * perNotch(total.display(), stacks), -1);
            } else if (stacks) {
                StorageActions.putAwayTileStacks(mc, total.key(), n);
            } else {
                StorageActions.putAwayTile(mc, total.key(), n);
            }
            return true;
        }

        if (screen == null || shift || holding || !canMove || !config.scrollOverInventory || mc.player == null) return false;
        int index = inventoryStackAt(screen, x, y);
        if (index < 0) return false;
        ItemStack stack = mc.player.getInventory().getItem(index);
        int notches = notches(index, amount);
        if (notches == 0) return true;
        InteractionHold.clicked();
        int n = Math.abs(notches);
        if (takes(config, notches)) StorageActions.takeCounted(mc, ItemKey.normalized(stack), stack, n * perNotch(stack, stacks), index);
        else if (stacks) StorageActions.putAwaySlot(mc, index);
        else StorageActions.putAwayCounted(mc, index, n);
        return true;
    }

    /** One item per notch, or with Ctrl a stack. */
    private static int perNotch(ItemStack item, boolean stacks) {
        return stacks ? Math.max(1, item.getMaxStackSize()) : 1;
    }

    /** Whole notches so far toward {@code target}, positive for up; what's left of a notch carries over. */
    private static int notches(Object target, double amount) {
        if (!Objects.equals(target, scrollTarget)) {
            scrollTarget = target;
            scrollLeft = 0;
        }
        scrollLeft += amount;
        int whole = (int) scrollLeft;
        scrollLeft -= whole;
        return whole;
    }

    private static boolean takes(ClientConfig.Mouse config, int notches) {
        return (config.scrollDirection == ClientConfig.ScrollDirection.DOWN_TAKES) == (notches < 0);
    }

    /** The main-inventory slot (hotbar and the rows above) with a stack at the mouse, or -1. */
    private static int inventoryStackAt(AbstractContainerScreen<?> screen, double x, double y) {
        Slot slot = ((AbstractContainerScreenAccessor) screen).craftysearch$slotAt(x, y);
        if (slot == null || !slot.hasItem() || !(slot.container instanceof Inventory)) return -1;
        int index = slot.getContainerSlot();
        return index < Inventory.INVENTORY_SIZE ? index : -1;
    }
}
