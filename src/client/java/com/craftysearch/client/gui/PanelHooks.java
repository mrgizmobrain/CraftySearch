package com.craftysearch.client.gui;

import com.craftysearch.client.background.BackgroundJobs;
import com.craftysearch.client.background.InteractionHold;
import com.craftysearch.client.background.StorageActions;
import com.craftysearch.client.compat.IpnLockedSlots;
import com.craftysearch.client.config.ClientConfig;
import com.craftysearch.client.mixin.AbstractContainerScreenAccessor;
import com.craftysearch.client.mixin.AbstractRecipeBookScreenInvoker;
import com.craftysearch.storage.ItemKey;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.navigation.ScreenPosition;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.Map;
import java.util.WeakHashMap;

/** Attaches the storage panel and its toggle button to the inventory and crafting table screens. */
public final class PanelHooks {
    private static final int PREFERRED_COLS = 6;
    private static final int MIN_COLS = 2;
    private static final int GAP = 2;

    private static final Map<Screen, Attached> ATTACHED = new WeakHashMap<>();
    /** A little over vanilla's 250 ms, so its double-clicks always count here too. */
    private static final long DOUBLE_CLICK_MILLIS = 300;
    /** Vanilla's recipe book: below this screen width it covers the inventory instead of sitting beside it. */
    private static final int BOOK_BESIDE_MIN_WIDTH = 379;
    private static final int BOOK_WIDTH = 147;
    private static final int BOOK_OFFSET = 86;
    /** Kept free at the screen's left edge when shifting. */
    private static final int EDGE_MARGIN = 2;

    /** A screen to lay out again on the next tick, after the panel opened or closed on it. */
    private static @Nullable Screen pendingRelayout;

    /** The last stack a shift-click stored, for recognising a shift-double-click. */
    private static ItemStack lastStored = ItemStack.EMPTY;
    private static int lastStoredSlot = -1;
    private static long lastStoredAt;
    private static boolean storedAll;

    private record Attached(Site site, StoragePanel panel, PanelToggleButton toggle) {
    }

    /** Where the panel is shown. Each site remembers its own open state, like the recipe book. */
    private enum Site {
        INVENTORY {
            @Override boolean enabled(ClientConfig c) { return c.showPanelInInventory; }
            @Override boolean open(ClientConfig c) { return c.panelOpen.inventory; }
            @Override void setOpen(ClientConfig c, boolean open) { c.panelOpen.inventory = open; }
            // Right of the recipe book button, above the main inventory.
            @Override ScreenPosition toggle(ScreenPosition recipeButton, int left, int imageWidth) {
                return new ScreenPosition(recipeButton.x() + PanelToggleButton.WIDTH + 2, recipeButton.y());
            }
        },
        CRAFTING_TABLE {
            @Override boolean enabled(ClientConfig c) { return c.showPanelInCraftingTable; }
            @Override boolean open(ClientConfig c) { return c.panelOpen.craftingTable; }
            @Override void setOpen(ClientConfig c, boolean open) { c.panelOpen.craftingTable = open; }
            // The recipe book button mirrored: level with it, as far from the right edge as it is from
            // the left, right of the result slot (Nov 8; below the book button it touched the "Inventory" label).
            @Override ScreenPosition toggle(ScreenPosition recipeButton, int left, int imageWidth) {
                int fromLeft = recipeButton.x() - left;
                return new ScreenPosition(left + imageWidth - fromLeft - PanelToggleButton.WIDTH, recipeButton.y());
            }
        };

        abstract boolean enabled(ClientConfig config);
        abstract boolean open(ClientConfig config);
        abstract void setOpen(ClientConfig config, boolean open);
        abstract ScreenPosition toggle(ScreenPosition recipeButton, int left, int imageWidth);

        static @Nullable Site of(Screen screen) {
            if (screen instanceof InventoryScreen) return INVENTORY;
            if (screen instanceof CraftingScreen) return CRAFTING_TABLE;
            return null;
        }
    }

    private PanelHooks() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register(PanelHooks::afterInit);
        // Laying a screen out again rebuilds its widgets, so it waits until no click or frame is going through them.
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            Screen screen = pendingRelayout;
            pendingRelayout = null;
            if (screen != null && mc.gui.screen() == screen) screen.resize(screen.width, screen.height);
        });
    }

    /**
     * How far left a recipe book screen moves while its panel is open, so the inventory (with
     * the recipe book, if open) and the panel sit centred together and the right side is left
     * to recipe viewers like JEI (Nov 8). Worked out from the screen's size alone, with room
     * for the recipe book whether or not it's open, so opening the book doesn't change it.
     * Used by {@code AbstractRecipeBookScreenMixin} when the screen is placed.
     */
    public static int shift(Screen screen, int width, int imageWidth) {
        Site site = Site.of(screen);
        ClientConfig config = ClientConfig.get();
        if (site == null || !site.enabled(config) || !site.open(config)) return 0;
        int panelSpace = GAP + StoragePanel.widthFor(PREFERRED_COLS);
        boolean bookBeside = width >= BOOK_BESIDE_MIN_WIDTH;
        int leftmost = bookBeside ? (width - BOOK_WIDTH) / 2 - BOOK_OFFSET : (width - imageWidth) / 2;
        int shift = Math.clamp(panelSpace / 2, 0, Math.max(0, leftmost - EDGE_MARGIN));
        // If the panel won't fit even so, the screen stays where vanilla puts it.
        int panelX = (width - imageWidth) / 2 - shift + imageWidth + GAP;
        return StoragePanel.colsFor(width - panelX - GAP) >= MIN_COLS ? shift : 0;
    }

    /**
     * Where the storage panel is on {@code screen}, or null while it isn't showing. JEI and REI
     * put their item list right of the inventory too, and keep clear of this (Nov 8).
     */
    public static @Nullable ScreenRectangle panelArea(@Nullable Screen screen) {
        Attached attached = screen == null ? null : ATTACHED.get(screen);
        if (attached == null || !attached.panel.isVisible()) return null;
        return attached.panel.area();
    }

    /** Used by the click-outside mixins so clicks on the panel don't drop the carried stack. */
    public static boolean isOverPanel(Screen screen, double mouseX, double mouseY) {
        Attached attached = ATTACHED.get(screen);
        return attached != null && (attached.panel.isMouseOver(mouseX, mouseY) || attached.toggle.isMouseOver(mouseX, mouseY));
    }

    private static void afterInit(Minecraft mc, Screen screen, int width, int height) {
        Site site = Site.of(screen);
        if (site == null || !site.enabled(ClientConfig.get())) return;

        // init runs again on resize and recipe book changes; keep the search text across it.
        // A new screen starts from the last search when rememberSearch is on.
        Attached previous = ATTACHED.get(screen);
        StoragePanel panel = new StoragePanel(mc.font, previous == null ? StoragePanel.initialQuery() : previous.panel.query(), true);
        PanelToggleButton toggle = new PanelToggleButton(() -> {
            ClientConfig config = ClientConfig.get();
            site.setOpen(config, !site.open(config));
            ClientConfig.save();
            // The screen moves over for the panel (see shift), which takes laying it out again.
            pendingRelayout = screen;
        });
        Attached attached = new Attached(site, panel, toggle);
        ATTACHED.put(screen, attached);

        Screens.getWidgets(screen).addAll(panel.widgets());
        Screens.getWidgets(screen).add(toggle);
        layout(screen, attached);

        // The recipe book moves the screen without re-initialising it, so lay out every frame.
        ScreenEvents.beforeExtract(screen).register((s, g, mouseX, mouseY, partialTick) -> layout(s, attached));
        AbstractContainerScreen<?> container = (AbstractContainerScreen<?>) screen;
        ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> !panel.keyPressed(event));
        ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> {
            // Spec 3.4: dropping the held stack on the panel puts it away; right-click stores one.
            // Clicking the held item's own tile takes more of it instead, so rapid clicks keep taking.
            // Where items can't be moved, the click goes on to the tile, which shows where it is.
            ItemStack held = BackgroundJobs.heldItem(container.getMenu());
            // The scrollbar is for scrolling, held item or not.
            if (panel.isMouseOver(event.x(), event.y()) && !held.isEmpty() && StorageActions.canMoveItems()
                    && !panel.isOverScrollbar(event.x(), event.y())) {
                ItemKey tile = panel.tileAt(event.x(), event.y());
                // A right-click set to open a container can't open one while holding something: it stores one, as anywhere.
                boolean opens = event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT
                        && StorageGridWidget.rightClickAction(event.hasShiftDown()) == ClientConfig.TileClick.OPEN;
                if (tile == null || !tile.matchesTile(held) || opens) {
                    InteractionHold.clicked();
                    StorageActions.putAwayCursor(mc, event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT);
                    return false;
                }
            }
            // Shift-clicking an inventory stack goes on to vanilla, whose quick-move routeQuickMove turns into put-away.
            PanelMouse.pressed(container, panel, event);
            panel.mouseClicked(event.x(), event.y());
            return true;
        });
        ScreenMouseEvents.allowMouseDrag(screen).register((s, event, dx, dy) -> {
            PanelMouse.dragged(container, panel, event.x(), event.y());
            panel.mouseDragged(event.y());
            return true;
        });
        ScreenMouseEvents.allowMouseRelease(screen).register((s, event) -> {
            PanelMouse.released();
            panel.mouseReleased();
            return true;
        });
        // Taking the scroll here keeps Mouse Tweaks' wheel tweak (an after-scroll listener) from also acting on it.
        ScreenMouseEvents.allowMouseScroll(screen).register((s, mouseX, mouseY, scrollX, scrollY) ->
                !PanelMouse.scrolled(container, panel, mouseX, mouseY, scrollX, scrollY));
    }

    /**
     * Spec 3.4 and 3.5: a quick-move of a main-inventory stack (hotbar and the rows above) with
     * the button set to put away, while the panel shows, stores it. Returns true if it did, so
     * the quick-move itself is skipped. Vanilla treats shift with either button the same, so
     * the other button keeps vanilla's shift-click.
     */
    public static boolean routeQuickMove(Screen screen, @Nullable Slot slot, int button, ContainerInput input) {
        if (input != ContainerInput.QUICK_MOVE || slot == null || !(slot.container instanceof Inventory)) return false;
        Attached attached = ATTACHED.get(screen);
        if (attached == null || !attached.panel.isVisible() || !StorageActions.canMoveItems()) return false;
        int wanted = ClientConfig.get().putAway.shiftClick.button();
        if (wanted < 0 || button != wanted) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !((AbstractContainerScreen<?>) screen).getMenu().getCarried().isEmpty()) return false;
        int index = slot.getContainerSlot();
        if (index >= mc.player.getInventory().getNonEquipmentItems().size()) return false;
        // A stack IPN has locked is left to vanilla's quick-move, which IPN guards itself.
        if (IpnLockedSlots.isLocked(index)) return false;
        // A drag (this mod's or Mouse Tweaks') reaching a stack it has stored already.
        if (PanelMouse.storedInThisDrag(index)) return true;

        // A shift-double-click stores every stack of the item. Vanilla works that out on the second
        // click from what the slot holds, but by then the server has often emptied it, so it's
        // recognised here: the same slot again, shortly after.
        long now = System.currentTimeMillis();
        boolean recent = now - lastStoredAt < DOUBLE_CLICK_MILLIS && !lastStored.isEmpty();
        if (recent && index == lastStoredSlot && !storedAll) {
            storedAll = true;
            InteractionHold.clicked();
            putAwayAll(mc, lastStored, index);
            return true;
        }
        // Vanilla's own clicks on each matching stack for that double-click: already handled.
        if (recent && storedAll && ItemStack.isSameItemSameComponents(slot.getItem(), lastStored)) return true;
        if (!slot.hasItem()) return false;

        lastStored = slot.getItem().copy();
        lastStoredSlot = index;
        lastStoredAt = now;
        storedAll = false;
        InteractionHold.clicked();
        StorageActions.putAwaySlot(mc, index);
        PanelMouse.stored(index);
        return true;
    }

    /**
     * True for a press that {@link #routeQuickMove} will turn into a put-away request: Shift with
     * the put-away button on a main-inventory slot, the panel showing. While a background job
     * runs, such a press may go through, since it only queues a request (Nov 9). The slot may
     * already be empty: the second click of a shift-double-click still stores the rest.
     */
    public static boolean queuesPutAway(Screen screen, MouseButtonEvent event) {
        Attached attached = ATTACHED.get(screen);
        if (attached == null || !attached.panel.isVisible() || !event.hasShiftDown() || !StorageActions.canMoveItems()) return false;
        if (event.button() != ClientConfig.get().putAway.shiftClick.button()) return false;
        Slot slot = ((AbstractContainerScreenAccessor) screen).craftysearch$slotAt(event.x(), event.y());
        if (slot == null || !(slot.container instanceof Inventory)) return false;
        int index = slot.getContainerSlot();
        return index < Inventory.INVENTORY_SIZE && !IpnLockedSlots.isLocked(index);
    }

    /** True while a click made through the screen is on its way to the game, already past {@link #routeQuickMove}. */
    private static boolean inScreenClick;

    public static void beginScreenClick() {
        inScreenClick = true;
    }

    public static void endScreenClick() {
        inScreenClick = false;
    }

    /**
     * A quick-move another mod sends straight to the game, past the screen, during a drag
     * that's putting inventory stacks away: IPN's swipe moving does this for each slot it
     * passes (Nov 8). The drag stores that stack (or has), so the quick-move, which would only
     * move it within the inventory, is dropped. Shares the drag's record with
     * {@link #routeQuickMove}, so each stack is stored once. Returns true if it was taken over.
     */
    public static boolean routeDraggedQuickMove(int containerId, int slotNum, ContainerInput input) {
        if (input != ContainerInput.QUICK_MOVE || inScreenClick || !PanelMouse.storingDrag()) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !(mc.gui.screen() instanceof AbstractContainerScreen<?> screen)) return false;
        Attached attached = ATTACHED.get(screen);
        AbstractContainerMenu menu = screen.getMenu();
        if (attached == null || !attached.panel.isVisible() || !StorageActions.canMoveItems()) return false;
        // A click meant for another menu (a hidden container) is the background job guard's business.
        if (menu.containerId != containerId || slotNum < 0 || slotNum >= menu.slots.size()) return false;
        Slot slot = menu.slots.get(slotNum);
        if (!(slot.container instanceof Inventory) || slot.getContainerSlot() >= mc.player.getInventory().getNonEquipmentItems().size()) {
            return false;
        }
        if (!menu.getCarried().isEmpty()) return false;
        int index = slot.getContainerSlot();
        if (IpnLockedSlots.isLocked(index)) return false;
        if (slot.hasItem() && !PanelMouse.storedInThisDrag(index)) {
            InteractionHold.clicked();
            StorageActions.putAwaySlot(mc, index);
            PanelMouse.stored(index);
        }
        return true;
    }

    /** Every other main-inventory stack of {@code item}, as vanilla's shift-double-click would move. */
    private static void putAwayAll(Minecraft mc, ItemStack item, int alreadyStored) {
        Inventory inventory = mc.player.getInventory();
        int size = inventory.getNonEquipmentItems().size();
        for (int i = 0; i < size; i++) {
            if (i != alreadyStored && ItemStack.isSameItemSameComponents(inventory.getItem(i), item)) StorageActions.putAwaySlot(mc, i);
        }
    }

    private static void layout(Screen screen, Attached attached) {
        AbstractContainerScreenAccessor container = (AbstractContainerScreenAccessor) screen;
        int left = container.craftysearch$getLeftPos();
        int top = container.craftysearch$getTopPos();

        ScreenPosition recipeButton = ((AbstractRecipeBookScreenInvoker) screen).craftysearch$getRecipeBookButtonPosition();
        ScreenPosition togglePos = attached.site.toggle(recipeButton, left, container.craftysearch$getImageWidth());
        attached.toggle.setX(togglePos.x());
        attached.toggle.setY(togglePos.y());

        int panelX = left + container.craftysearch$getImageWidth() + GAP;
        int cols = Math.min(PREFERRED_COLS, StoragePanel.colsFor(screen.width - panelX - GAP));
        boolean fits = cols >= MIN_COLS;
        // Just opened: it shows once the screen has moved over for it, rather than jumping.
        boolean open = attached.site.open(ClientConfig.get()) && pendingRelayout != screen;

        attached.panel.setVisible(open && fits);
        if (open && fits) {
            attached.panel.setBounds(panelX, top, cols, container.craftysearch$getImageHeight());
        }
        attached.toggle.setTooltipKey(
                !fits ? "craftysearch.panel.toggle.no_space" : open ? "craftysearch.panel.toggle.hide" : "craftysearch.panel.toggle.show");
    }
}
