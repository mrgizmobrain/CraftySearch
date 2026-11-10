package com.craftysearch.client.gui;

import com.craftysearch.client.background.InteractionHold;
import com.craftysearch.client.background.StorageActions;
import com.craftysearch.client.config.ClientConfig;
import com.craftysearch.client.storage.StorageIndex.ItemTotal;
import com.craftysearch.storage.ItemKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jspecify.annotations.Nullable;

import java.util.List;

/** The storage panel: a search box over an item grid. Used beside containers and in {@link StorageScreen}. */
public final class StoragePanel {
    static final int PAD = 5;
    private static final int SEARCH_HEIGHT = 14;
    private static final int SEARCH_GAP = 3;
    private static final int FOOTER_HEIGHT = 11;
    private static final int SCROLLBAR_WIDTH = StorageGridWidget.SCROLLBAR_WIDTH;
    private static final int SORT_GAP = 2;

    /** The last search typed in any panel, for {@code rememberSearch}. Kept for the session only. */
    private static String lastQuery = "";

    private final Font font;
    private final EditBox search;
    private final PanelSortButton sort = new PanelSortButton();
    private final StorageGridWidget grid;
    private int x, y, width, height;
    private boolean visible = true;
    private boolean draggingScrollbar;
    /** The search box's inner width the hint was last fitted to. */
    private int hintFittedTo = -1;

    /** {@code hasCursor}: the screen has an item cursor, so takes can land on it and the panel accepts put-away. */
    public StoragePanel(Font font, String query, boolean hasCursor) {
        this.font = font;
        search = new EditBox(font, 0, 0, 80, SEARCH_HEIGHT, Component.translatable("craftysearch.panel.search"));
        search.setHint(Component.translatable("craftysearch.panel.search_hint").withStyle(EditBox.SEARCH_HINT_STYLE));
        search.setMaxLength(100);
        search.setValue(query);
        search.setResponder(text -> lastQuery = text);
        grid = new StorageGridWidget(font, search::getValue, hasCursor);
    }

    /** What a new panel's search starts with: the last search with {@code rememberSearch}, otherwise nothing. */
    static String initialQuery() {
        return ClientConfig.get().rememberSearch ? lastQuery : "";
    }

    static int widthFor(int cols) {
        return 2 * PAD + cols * StorageGridWidget.SLOT + SCROLLBAR_WIDTH;
    }

    static int colsFor(int availableWidth) {
        return (availableWidth - 2 * PAD - SCROLLBAR_WIDTH) / StorageGridWidget.SLOT;
    }

    void setBounds(int x, int y, int cols, int height) {
        this.x = x;
        this.y = y;
        this.width = widthFor(cols);
        this.height = height;
        // The sort button takes the end of the search row.
        search.setX(x + PAD);
        search.setY(y + PAD);
        search.setWidth(width - 2 * PAD - PanelSortButton.SIZE - SORT_GAP);
        fitHint();
        sort.setX(x + width - PAD - PanelSortButton.SIZE);
        sort.setY(y + PAD);
        int gridTop = y + PAD + SEARCH_HEIGHT + SEARCH_GAP;
        int rows = Math.max(1, (y + height - PAD - FOOTER_HEIGHT - gridTop) / StorageGridWidget.SLOT);
        grid.setLayout(x + PAD, gridTop, cols, rows);
        grid.setPanelBounds(x, y, width, height);
    }

    /**
     * The edit box doesn't clip its hint, so a hint wider than the box runs over its border
     * (Nov 7). A narrow panel gets the short one. Layout runs every frame; this only works
     * it out again when the width changes.
     */
    private void fitHint() {
        int inner = search.getInnerWidth();
        if (inner == hintFittedTo) return;
        hintFittedTo = inner;
        MutableComponent hint = Component.translatable("craftysearch.panel.search_hint").withStyle(EditBox.SEARCH_HINT_STYLE);
        // A text's width includes the spacing after its last character.
        if (font.width(hint) - 1 > inner) {
            hint = Component.translatable("craftysearch.panel.search_hint_short").withStyle(EditBox.SEARCH_HINT_STYLE);
        }
        search.setHint(hint);
    }

    void setVisible(boolean visible) {
        this.visible = visible;
        grid.visible = visible;
        sort.visible = visible;
        search.setVisible(visible);
        if (!visible) search.setFocused(false);
    }

    boolean isVisible() {
        return visible;
    }

    /** The whole panel: search row, grid, scrollbar and footer. */
    ScreenRectangle area() {
        return new ScreenRectangle(x, y, width, height);
    }

    /** The widgets to add to a screen, background-drawing grid first. */
    List<AbstractWidget> widgets() {
        return List.of(grid, search, sort);
    }

    EditBox search() {
        return search;
    }

    String query() {
        return search.getValue();
    }

    /** The tile under the mouse, or null over empty space or outside the grid. */
    @Nullable ItemKey tileAt(double mouseX, double mouseY) {
        return visible ? grid.tileAt(mouseX, mouseY) : null;
    }

    /** The tile under the mouse with its totals, or null over empty space or outside the grid. */
    @Nullable ItemTotal totalAt(double mouseX, double mouseY) {
        return visible ? grid.totalAt(mouseX, mouseY) : null;
    }

    /** Scrolls the grid, wherever the mouse is over the panel. */
    void scrollGrid(double amount) {
        if (visible) grid.scroll(amount);
    }

    boolean isMouseOver(double mouseX, double mouseY) {
        return visible && mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    /**
     * Sends a key to the search box while it's focused, so typing doesn't trigger container
     * shortcuts like the inventory key or hotbar swaps; otherwise the drop key over a tile
     * throws from storage. Returns true if the key was used.
     */
    boolean keyPressed(KeyEvent event) {
        if (!visible || event.isEscape()) return false;
        if (!search.isFocused()) return throwPressed(event);
        search.keyPressed(event);
        return true;
    }

    /**
     * The drop key (Q) over a tile throws one straight from storage, Ctrl+Q a stack, as they
     * do over an inventory slot (Nov 9). Returns true if it did.
     */
    boolean throwPressed(KeyEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (!visible || !mc.options.keyDrop.matches(event) || !StorageActions.canMoveItems()) return false;
        ItemTotal total = totalAt(mc.mouseHandler.getScaledXPos(mc.getWindow()), mc.mouseHandler.getScaledYPos(mc.getWindow()));
        if (total == null || total.total() <= 0) return false;
        InteractionHold.clicked();
        StorageActions.throwOut(mc, total, event.hasControlDown());
        return true;
    }

    /** Unfocuses the search box when the click lands somewhere else; a click on the scrollbar starts dragging it. */
    void mouseClicked(double mouseX, double mouseY) {
        if (!search.isMouseOver(mouseX, mouseY)) search.setFocused(false);
        draggingScrollbar = grid.isOverScrollbar(mouseX, mouseY);
        if (draggingScrollbar) grid.scrollTo(mouseY);
    }

    boolean isOverScrollbar(double mouseX, double mouseY) {
        return visible && grid.isOverScrollbar(mouseX, mouseY);
    }

    void mouseDragged(double mouseY) {
        if (draggingScrollbar) grid.scrollTo(mouseY);
    }

    void mouseReleased() {
        draggingScrollbar = false;
    }
}
