package com.craftysearch.client.gui;

import com.craftysearch.client.background.BackgroundJobs;
import com.craftysearch.client.background.InteractionHold;
import com.craftysearch.client.background.StorageActions;
import com.craftysearch.client.config.ClientConfig;
import com.craftysearch.client.highlight.Highlights;
import com.craftysearch.client.live.ServerMode;
import com.craftysearch.client.storage.SearchFilter;
import com.craftysearch.client.storage.StorageIndex;
import com.craftysearch.client.storage.StorageIndex.ItemTotal;
import com.craftysearch.storage.ItemKey;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The scrollable item grid of the storage panel. It also draws the panel background
 * behind itself and the search box, because it's added to the screen first.
 */
final class StorageGridWidget extends AbstractWidget {
    static final int SLOT = 18;
    /** The creative inventory's scrollbar: a 14-pixel track sunk in like a slot, with a 12 by 15 thumb (Nov 8). */
    private static final int SCROLLBAR_GAP = 2;
    private static final int TRACK_WIDTH = 14;
    private static final int THUMB_WIDTH = 12;
    private static final int THUMB_HEIGHT = 15;
    /** Room the scrollbar takes beside the grid. */
    static final int SCROLLBAR_WIDTH = SCROLLBAR_GAP + TRACK_WIDTH;
    private static final Identifier SCROLLER = Identifier.withDefaultNamespace("container/creative_inventory/scroller");
    private static final Identifier SCROLLER_DISABLED = Identifier.withDefaultNamespace("container/creative_inventory/scroller_disabled");
    /** Vanilla's slot hover, behind and in front of the item, 4 pixels round it. */
    private static final Identifier HIGHLIGHT_BACK = Identifier.withDefaultNamespace("container/slot_highlight_back");
    private static final Identifier HIGHLIGHT_FRONT = Identifier.withDefaultNamespace("container/slot_highlight_front");
    private static final int HIGHLIGHT_SIZE = 24;
    private static final long RECOUNT_INTERVAL_MILLIS = 1000;
    /** Slot-coloured veil over a tile that has run out. */
    private static final int EMPTY_TILE_COLOR = 0xB08B8B8B;
    private static final int LABEL_COLOR = 0xFF404040;
    /** The corner mark on a tile whose count may be off, in the colour its container is outlined with. */
    private static final int SUSPECT_COLOR = Highlights.SUSPECT_COLOR;

    private final Font font;
    private final Supplier<String> query;
    /** False on the storage screen, which has no item cursor: takes go into the inventory. */
    private final boolean hasCursor;
    private int cols = 1;
    private int rows = 1;
    private int scrollRow;
    private int panelX, panelY, panelW, panelH;

    private List<ItemTotal> totals = List.of();
    private List<ItemTotal> shown = List.of();
    private int countedVersion = -1;
    private long countedAt;
    private ClientConfig.@Nullable PanelSort sortedBy;
    private String filteredQuery = "";

    StorageGridWidget(Font font, Supplier<String> query, boolean hasCursor) {
        super(0, 0, SLOT, SLOT, Component.translatable("craftysearch.panel.title"));
        this.font = font;
        this.query = query;
        this.hasCursor = hasCursor;
    }

    void setLayout(int x, int y, int cols, int rows) {
        this.cols = cols;
        this.rows = rows;
        setX(x);
        setY(y);
        setWidth(cols * SLOT);
        setHeight(rows * SLOT);
        clampScroll();
    }

    void setPanelBounds(int x, int y, int w, int h) {
        panelX = x;
        panelY = y;
        panelW = w;
        panelH = h;
    }

    /** Recounts at most once a second, or right away when storage or the sort changes. */
    private void refresh() {
        long now = System.currentTimeMillis();
        int version = StorageIndex.version();
        ClientConfig.PanelSort sort = ClientConfig.get().panelSort;
        // A new sort re-sorts at once, like a new search, even while clicks hold the layout still.
        boolean newSort = sort != sortedBy;
        boolean recount = newSort || version != countedVersion || now - countedAt > RECOUNT_INTERVAL_MILLIS;
        if (recount) {
            totals = StorageIndex.compute(Minecraft.getInstance());
            countedVersion = version;
            countedAt = now;
            sortedBy = sort;
        }
        String q = query.get();
        boolean newQuery = newSort || !q.equals(filteredQuery);
        if (recount || newQuery) {
            if (newQuery) scrollRow = 0;
            filteredQuery = q;
            var filter = SearchFilter.parse(q);
            List<ItemTotal> list = new ArrayList<>();
            for (ItemTotal t : totals) {
                if (filter.test(t.display())) list.add(t);
            }
            // While the player is clicking, tiles stay where they are; a new search always re-sorts.
            shown = InteractionHold.held() && !newQuery && !shown.isEmpty() ? keepOrder(shown, list) : list;
            clampScroll();
        }
    }

    /**
     * The fresh totals in the previous order. A tile that ran out stays as an empty
     * placeholder, and new tiles go at the end, so nothing under the cursor moves.
     */
    private static List<ItemTotal> keepOrder(List<ItemTotal> previous, List<ItemTotal> fresh) {
        Map<ItemKey, ItemTotal> byKey = new LinkedHashMap<>();
        for (ItemTotal t : fresh) byKey.put(t.key(), t);
        List<ItemTotal> result = new ArrayList<>(fresh.size() + 4);
        for (ItemTotal old : previous) {
            ItemTotal now = byKey.remove(old.key());
            result.add(now != null ? now : new ItemTotal(old.key(), 0, 0, 0, 0, List.of(), 0, 0, 0, 0));
        }
        result.addAll(byKey.values());
        return result;
    }

    /** The tile under the mouse, or null. */
    @Nullable ItemKey tileAt(double mouseX, double mouseY) {
        int index = indexAt(mouseX, mouseY);
        return index < 0 ? null : shown.get(index).key();
    }

    /** The tile under the mouse with its totals, or null. */
    @Nullable ItemTotal totalAt(double mouseX, double mouseY) {
        int index = indexAt(mouseX, mouseY);
        return index < 0 ? null : shown.get(index);
    }

    private int maxScroll() {
        int totalRows = (shown.size() + cols - 1) / cols;
        return Math.max(0, totalRows - rows);
    }

    private void clampScroll() {
        scrollRow = Mth.clamp(scrollRow, 0, maxScroll());
    }

    void scroll(double amount) {
        scrollRow = Mth.clamp(scrollRow - (int) Math.signum(amount), 0, maxScroll());
    }

    private int indexAt(double mouseX, double mouseY) {
        if (!isMouseOver(mouseX, mouseY)) return -1;
        int col = (int) (mouseX - getX()) / SLOT;
        int row = (int) (mouseY - getY()) / SLOT;
        int index = (scrollRow + row) * cols + col;
        return col < cols && index < shown.size() ? index : -1;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        refresh();
        GuiDraw.panel(g, panelX, panelY, panelW, panelH);

        int hovered = indexAt(mouseX, mouseY);
        int hoverX = 0;
        int hoverY = 0;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                int x = getX() + col * SLOT;
                int y = getY() + row * SLOT;
                GuiDraw.slot(g, x, y);
                int index = (scrollRow + row) * cols + col;
                if (index >= shown.size()) continue;
                ItemTotal total = shown.get(index);
                if (index == hovered) {
                    hoverX = x - 3;
                    hoverY = y - 3;
                    g.blitSprite(RenderPipelines.GUI_TEXTURED, HIGHLIGHT_BACK, hoverX, hoverY, HIGHLIGHT_SIZE, HIGHLIGHT_SIZE);
                }
                g.item(total.display(), x + 1, y + 1);
                if (total.total() > 0) {
                    g.itemDecorations(font, total.display(), x + 1, y + 1, StorageIndex.formatCount(total.total()));
                    // Someone else used one of its containers since it was read: a small mark in the corner.
                    if (total.suspectContainers() > 0) g.fill(x + 1, y + 1, x + 4, y + 4, SUSPECT_COLOR);
                } else {
                    // A placeholder kept in place while the player clicks: none left.
                    g.fill(x + 1, y + 1, x + 17, y + 17, EMPTY_TILE_COLOR);
                }
            }
        }
        // After every slot, as vanilla does, so the glow lies over the neighbours too.
        if (hovered >= 0) g.blitSprite(RenderPipelines.GUI_TEXTURED, HIGHLIGHT_FRONT, hoverX, hoverY, HIGHLIGHT_SIZE, HIGHLIGHT_SIZE);
        if (shown.isEmpty()) extractEmptyHint(g);
        extractScrollbar(g);
        extractFooter(g);
        ItemStack held = heldItem();
        boolean overPanel = mouseX >= panelX && mouseX < panelX + panelW && mouseY >= panelY && mouseY < panelY + panelH;
        boolean overOwnTile = hovered >= 0 && shown.get(hovered).key().matchesTile(held);
        // Where items can't be moved, a held stack can't be put away, so it gets no hint (Nov 7).
        if (!held.isEmpty() && overPanel && !overOwnTile && StorageActions.canMoveItems()) {
            g.setComponentTooltipForNextFrame(font, List.of(
                    Component.translatable("craftysearch.tooltip.put_away"),
                    Component.translatable("craftysearch.tooltip.put_away_one").withStyle(ChatFormatting.GRAY)), mouseX, mouseY);
        } else if (hovered >= 0) {
            g.setComponentTooltipForNextFrame(font, tooltip(shown.get(hovered)), mouseX, mouseY);
        }
    }

    /**
     * What the player holds, including an item a running take is bringing to the cursor.
     * With one, a click on the panel puts it away, except on its own tile (handled by PanelHooks).
     */
    private ItemStack heldItem() {
        Minecraft mc = Minecraft.getInstance();
        if (!hasCursor || mc.player == null) return ItemStack.EMPTY;
        return BackgroundJobs.heldItem(mc.player.containerMenu);
    }

    /** Says why the grid is empty, centred over the empty slots, so a first look at the panel isn't a puzzle. */
    private void extractEmptyHint(GuiGraphicsExtractor g) {
        String key;
        if (!filteredQuery.isBlank()) key = "craftysearch.panel.empty.no_match";
        else if (ServerMode.active()) key = "craftysearch.panel.empty.live";
        else if (!ClientConfig.get().memory.enabled) key = "craftysearch.panel.empty.memory_off";
        // Containers are remembered, just none within reach ("Only takeable").
        else if (StorageIndex.onlyTakeable() && !StorageIndex.nearbyEntries(Minecraft.getInstance()).isEmpty()) {
            key = "craftysearch.panel.empty.none_in_reach";
        } else key = "craftysearch.panel.empty.open_containers";
        List<FormattedCharSequence> lines = font.split(Component.translatable(key), Math.max(SLOT, getWidth() - 4));
        int y = getY() + (getHeight() - lines.size() * font.lineHeight) / 2;
        for (FormattedCharSequence line : lines) {
            g.text(font, line, getX() + (getWidth() - font.width(line)) / 2, y, LABEL_COLOR, false);
            y += font.lineHeight;
        }
    }

    /** The creative inventory's scrollbar; its thumb is greyed out when there's nothing to scroll. */
    private void extractScrollbar(GuiGraphicsExtractor g) {
        int x = trackX();
        GuiDraw.inset(g, x, getY(), TRACK_WIDTH, getHeight());
        int max = maxScroll();
        int thumbY = getY() + 1 + (max == 0 ? 0 : thumbTravel() * scrollRow / max);
        g.blitSprite(RenderPipelines.GUI_TEXTURED, max == 0 ? SCROLLER_DISABLED : SCROLLER, x + 1, thumbY, THUMB_WIDTH, THUMB_HEIGHT);
    }

    private int trackX() {
        return getRight() + SCROLLBAR_GAP;
    }

    /** How far the thumb moves inside the track. */
    private int thumbTravel() {
        return Math.max(0, getHeight() - 2 - THUMB_HEIGHT);
    }

    boolean isOverScrollbar(double mouseX, double mouseY) {
        return visible && mouseX >= trackX() && mouseX < trackX() + TRACK_WIDTH && mouseY >= getY() && mouseY < getBottom();
    }

    /** Scrolls so the thumb's middle is at {@code mouseY}, as dragging the creative inventory's does. */
    void scrollTo(double mouseY) {
        int max = maxScroll();
        int travel = thumbTravel();
        if (max == 0 || travel == 0) return;
        double fraction = (mouseY - getY() - 1 - THUMB_HEIGHT / 2.0) / travel;
        scrollRow = Mth.clamp((int) Math.round(fraction * max), 0, max);
    }

    private void extractFooter(GuiGraphicsExtractor g) {
        int x = getX();
        int y = getBottom() + 3;
        int maxWidth = panelX + panelW - 4 - x;
        String key = ServerMode.active() ? "craftysearch.panel.footer_live" : "craftysearch.panel.footer";
        String full = Component.translatable(key, shown.size(), StorageIndex.radius()).getString();
        String text = font.width(full) <= maxWidth ? full : String.valueOf(shown.size());
        g.text(font, text, x, y, LABEL_COLOR, false);
    }

    /**
     * Vanilla's item tooltip, then two short lines of counts. The controls only show while Ctrl
     * is held, so the tooltip doesn't cover the inventory (Nov 7).
     */
    private List<Component> tooltip(ItemTotal total) {
        // Vanilla's tooltip lists enchantments, potion effects and lore.
        List<Component> lines = new ArrayList<>(Screen.getTooltipFromItem(Minecraft.getInstance(), total.display()));
        ClientConfig config = ClientConfig.get();
        if (total.inInventory() == 0) {
            lines.add(Component.translatable("craftysearch.tooltip.total", total.total()).withStyle(ChatFormatting.GRAY));
        } else {
            // With the inventory left out of the totals, still say how many you carry.
            String key = config.includeInventory ? "craftysearch.tooltip.total_inventory" : "craftysearch.tooltip.total_inventory_uncounted";
            lines.add(Component.translatable(key, total.total(), total.inInventory()).withStyle(ChatFormatting.GRAY));
        }
        if (total.sources().isEmpty()) return lines;

        // Loose and boxed each with their own containers (Nov 8: one line added them together).
        boolean onlyInBoxes = StorageActions.onlyInBoxes(total);
        if (total.inStorage() > 0) {
            if (config.memory.showStaleAge && !ServerMode.active()) {
                // Live data from the server is never stale.
                lines.add(Component.translatable("craftysearch.tooltip.storage_seen", total.inStorage(), total.looseContainers(),
                        formatAge(System.currentTimeMillis() - total.oldestSeenMillis())).withStyle(ChatFormatting.GRAY));
            } else {
                lines.add(Component.translatable("craftysearch.tooltip.storage", total.inStorage(), total.looseContainers())
                        .withStyle(ChatFormatting.GRAY));
            }
        }
        if (total.inBoxes() > 0) {
            // Boxes the player carries are in no container.
            Component boxed = total.boxContainers() > 0
                    ? Component.translatable("craftysearch.tooltip.in_boxes_containers", total.inBoxes(), total.boxContainers())
                    : Component.translatable("craftysearch.tooltip.in_boxes", total.inBoxes());
            lines.add(boxed.copy().withStyle(ChatFormatting.GRAY));
        }
        if (total.suspectContainers() > 0 && !ServerMode.active()) {
            // Client-only memory can't see what others do; it only saw them open the container.
            lines.add(Component.translatable("craftysearch.tooltip.suspect", total.suspectContainers()).withStyle(ChatFormatting.GOLD));
        }
        if (!StorageActions.canMoveItems()) {
            // A click can only show where it is, so that one control shows without Ctrl (Nov 7);
            // opening the container still works from a right-click set to it, where a server turned
            // taking off. With "All interactions" off that shows where too, so the one line says it all.
            lines.add(control("craftysearch.tooltip.click_locates"));
            if (!ServerMode.active()) return lines;
            if (config.mouse.rightClick == ClientConfig.TileClick.OPEN) lines.add(rightClickLine(false));
            if (config.mouse.shiftRightClick == ClientConfig.TileClick.OPEN) lines.add(rightClickLine(true));
            return lines;
        }
        if (!Minecraft.getInstance().hasControlDown()) {
            lines.add(control("craftysearch.tooltip.hold_for_controls"));
            return lines;
        }
        // Items never come out of a box; a take brings out the box holding the most of them (Nov 8).
        lines.add(control(onlyInBoxes ? "craftysearch.tooltip.take_box" : "craftysearch.tooltip.take"));
        if (hasCursor && !onlyInBoxes) lines.add(control("craftysearch.tooltip.take_to_inventory"));
        lines.add(rightClickLine(false));
        lines.add(rightClickLine(true));
        if (onlyInBoxes) {
            lines.add(control("craftysearch.tooltip.locate"));
            return lines;
        }
        if (config.mouse.scrollOverTiles) {
            String key = config.mouse.scrollDirection == ClientConfig.ScrollDirection.DOWN_TAKES
                    ? "craftysearch.tooltip.scroll_down_takes" : "craftysearch.tooltip.scroll_up_takes";
            lines.add(control(key));
            lines.add(control("craftysearch.tooltip.ctrl_scroll"));
        }
        // The drop key as bound, Q unless changed.
        Component drop = Minecraft.getInstance().options.keyDrop.getTranslatedKeyMessage();
        lines.add(Component.translatable("craftysearch.tooltip.throw", drop).withStyle(ChatFormatting.DARK_GRAY));
        lines.add(control("craftysearch.tooltip.locate"));
        return lines;
    }

    private static Component control(String key) {
        return Component.translatable(key).withStyle(ChatFormatting.DARK_GRAY);
    }

    /** "Right-click: take one", "Shift-right-click: show where", and so on, as set. */
    private static Component rightClickLine(boolean shift) {
        Component action = Component.translatable(
                "craftysearch.tooltip.action." + rightClickAction(shift).name().toLowerCase(Locale.ROOT));
        return Component.translatable(shift ? "craftysearch.tooltip.shift_right_click" : "craftysearch.tooltip.right_click", action)
                .withStyle(ChatFormatting.DARK_GRAY);
    }

    /** Left, right and middle clicks all do something here. */
    @Override
    protected boolean isValidClickButton(MouseButtonInfo info) {
        return info.button() <= GLFW.GLFW_MOUSE_BUTTON_MIDDLE;
    }

    /**
     * Spec 3.4: click takes a stack onto the cursor, Shift-click a stack into the inventory.
     * Right-click opens the container holding it or takes one, and Shift-right-click shows where
     * it is or takes all that fits, as set (Nov 9). Middle-click or Alt-click always shows where
     * it is. Without a cursor (the storage screen), takes go into the inventory. Where items
     * can't be moved (the server turned it off), every click shows where it is, except that
     * right-click can still open the container.
     */
    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        int index = indexAt(event.x(), event.y());
        if (index < 0) return;
        ItemTotal total = shown.get(index);
        Minecraft mc = Minecraft.getInstance();
        InteractionHold.clicked();
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_MIDDLE || event.hasAltDown()) {
            StorageActions.locate(mc, total);
            return;
        }
        boolean shift = event.hasShiftDown();
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            if (StorageActions.canMoveItems()) StorageActions.take(mc, total, StorageActions.TakeMode.STACK, hasCursor && !shift);
            else StorageActions.locate(mc, total);
            return;
        }
        // Shift sends a take into the inventory, as Shift-click does.
        switch (rightClickAction(shift)) {
            case OPEN -> StorageActions.open(mc, total);
            case SHOW_WHERE -> StorageActions.locate(mc, total);
            case TAKE_ONE -> {
                if (StorageActions.canMoveItems()) StorageActions.take(mc, total, StorageActions.TakeMode.ONE, hasCursor && !shift);
                else StorageActions.locate(mc, total);
            }
            case TAKE_ALL -> {
                if (StorageActions.canMoveItems()) StorageActions.take(mc, total, StorageActions.TakeMode.ALL, false);
                else StorageActions.locate(mc, total);
            }
        }
    }

    /** What a right-click (or with {@code shift}, a shift-right-click) on a tile is set to do. */
    static ClientConfig.TileClick rightClickAction(boolean shift) {
        ClientConfig.Mouse mouse = ClientConfig.get().mouse;
        return shift ? mouse.shiftRightClick : mouse.rightClick;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!isMouseOver(mouseX, mouseY)) return false;
        scroll(scrollY);
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }

    private static String formatAge(long millis) {
        long seconds = Math.max(0, millis / 1000);
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return seconds / 60 + "m";
        if (seconds < 86400) return seconds / 3600 + "h";
        return seconds / 86400 + "d";
    }
}
