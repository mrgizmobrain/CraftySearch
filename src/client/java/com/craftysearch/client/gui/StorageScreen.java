package com.craftysearch.client.gui;

import com.craftysearch.client.CraftySearchClient;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/** The storage panel as a full screen, opened with the keybind from anywhere in the world. */
public final class StorageScreen extends Screen {
    private static final int MAX_COLS = 14;
    private static final int MAX_HEIGHT = 280;
    private static final int TOP = 26;

    private String query = "";
    private StoragePanel panel;

    public StorageScreen() {
        super(Component.translatable("craftysearch.screen.title"));
    }

    @Override
    protected void init() {
        // Keep the search text when the window is resized; a fresh screen starts from the last search, if remembered.
        query = panel != null ? panel.query() : StoragePanel.initialQuery();
        int cols = Mth.clamp(StoragePanel.colsFor(width - 40), 2, MAX_COLS);
        int panelHeight = Math.min(height - TOP - 32, MAX_HEIGHT);
        int panelX = (width - StoragePanel.widthFor(cols)) / 2;

        panel = new StoragePanel(font, query, false);
        panel.setBounds(panelX, TOP, cols, panelHeight);
        for (AbstractWidget widget : panel.widgets()) addRenderableWidget(widget);
        addRenderableWidget(Button.builder(Component.translatable("craftysearch.screen.settings"),
                        b -> minecraft.gui.setScreen(new ConfigScreen(this)))
                .bounds(width / 2 - 50, TOP + panelHeight + 6, 100, 20)
                .build());
        setInitialFocus(panel.search());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(font, title, width / 2, TOP - 14, 0xFFFFFFFF);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!panel.search().isFocused() && CraftySearchClient.OPEN_STORAGE.matches(event)) {
            onClose();
            return true;
        }
        if (!panel.search().isFocused() && panel.throwPressed(event)) return true;
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return PanelMouse.scrolled(null, panel, mouseX, mouseY, scrollX, scrollY)
                || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // Shift-dragging over tiles takes a stack of each (spec 3.5).
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        PanelMouse.pressed(null, panel, event);
        panel.mouseClicked(event.x(), event.y());
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        PanelMouse.dragged(null, panel, event.x(), event.y());
        panel.mouseDragged(event.y());
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        PanelMouse.released();
        panel.mouseReleased();
        return super.mouseReleased(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Clicks here keep working while a background job runs, so they can be queued. */
    public boolean isOverPanel(double mouseX, double mouseY) {
        return panel != null && panel.isMouseOver(mouseX, mouseY);
    }
}
