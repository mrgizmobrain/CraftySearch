package com.craftysearch.client.gui;

import com.craftysearch.client.background.StorageActions;
import com.craftysearch.client.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

/**
 * Cycles how the panel orders its tiles: most first (#), by name (A), by mod (@, as in the
 * search). Sits at the end of the search row; the choice is saved, so every panel keeps it.
 * In client-only mode, holding Shift turns it into a button that checks every container within
 * reach (Nov 11), shown as a circular arrow, with a tooltip saying so. The sort has no tooltip:
 * its letter says enough, and Shift shows the other use.
 */
final class PanelSortButton extends AbstractButton {
    static final int SIZE = 14;
    private static final int LABEL_COLOR = 0xFF404040;
    /**
     * The check's icon, drawn pixel by pixel to fill the button's 10 by 10 face (a ↻ glyph came
     * out tiny). Nine rows, leaving one free above the bottom edge (ten touched it).
     */
    private static final String[] CHECK_ICON = {
            ".....#..",
            ".....##.",
            "..######",
            ".#...##.",
            "#....#..",
            "#......#",
            "#......#",
            ".#....#.",
            "..####..",
    };

    /** Whether the tooltip now is the check's. */
    private boolean checkTooltip;

    PanelSortButton() {
        super(0, 0, SIZE, SIZE, Component.translatable("craftysearch.panel.sort"));
    }

    @Override
    public void onPress(InputWithModifiers input) {
        if (input.hasShiftDown() && StorageActions.canCheckAll()) {
            StorageActions.checkAll(Minecraft.getInstance());
            return;
        }
        ClientConfig config = ClientConfig.get();
        config.panelSort = config.panelSort.next();
        ClientConfig.save();
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // Drawn like the panel toggle and the recipe book button (Nov 8).
        GuiDraw.button(g, getX(), getY(), SIZE, SIZE, isHovered());
        Minecraft mc = Minecraft.getInstance();
        // Whenever Shift is held, not only over the button (the user's choice, Nov 11).
        boolean checking = mc.hasShiftDown() && StorageActions.canCheckAll();
        if (checking != checkTooltip) {
            checkTooltip = checking;
            setTooltip(checking ? Tooltip.create(Component.translatable("craftysearch.panel.check_all")) : null);
        }
        if (checking) {
            drawCheckIcon(g, getX() + (SIZE - CHECK_ICON[0].length()) / 2, getY() + (SIZE - CHECK_ICON.length) / 2);
            return;
        }
        String label = switch (ClientConfig.get().panelSort) {
            case COUNT -> "#";
            case NAME -> "A";
            case MOD -> "@";
        };
        Font font = mc.font;
        // Dark on the light face, as container titles are.
        g.text(font, label, getX() + (getWidth() - font.width(label) + 1) / 2, getY() + (getHeight() - font.lineHeight) / 2 + 1,
                LABEL_COLOR, false);
    }

    private static void drawCheckIcon(GuiGraphicsExtractor g, int x, int y) {
        for (int row = 0; row < CHECK_ICON.length; row++) {
            String line = CHECK_ICON[row];
            for (int col = 0; col < line.length(); col++) {
                if (line.charAt(col) == '#') g.fill(x + col, y + row, x + col + 1, y + row + 1, LABEL_COLOR);
            }
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
