package com.craftysearch.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Vanilla's GUI look drawn with fills, pixel for pixel as vanilla's own textures have it, so
 * the mod ships no textures (Nov 8: matched against the inventory background and the recipe
 * book button). Ends are exclusive, as in {@code fill}.
 */
final class GuiDraw {
    private static final int BACKGROUND = 0xFFC6C6C6;
    private static final int LIGHT = 0xFFFFFFFF;
    private static final int SHADOW = 0xFF555555;
    private static final int OUTLINE = 0xFF000000;
    private static final int SLOT = 0xFF8B8B8B;
    private static final int SLOT_SHADOW = 0xFF373737;
    /** The recipe book button while hovered. */
    private static final int HOVER_OUTLINE = 0xFF00073E;
    private static final int HOVER_FACE = 0xFF8892C9;
    private static final int HOVER_SHADOW = 0xFF343E75;

    private GuiDraw() {
    }

    /**
     * A container background, as the inventory's: a black outline with its corners cut away
     * (two pixels top left, three top right and bottom left, two bottom right), a two-pixel
     * white highlight along the top and left and a shadow along the bottom and right, each
     * with vanilla's inner corner pixel.
     */
    static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        int r = x + w;
        int b = y + h;
        // Outline.
        g.fill(x + 2, y, r - 3, y + 1, OUTLINE);
        g.fill(x + 1, y + 1, x + 2, y + 2, OUTLINE);
        g.fill(r - 3, y + 1, r - 2, y + 2, OUTLINE);
        g.fill(r - 2, y + 2, r - 1, y + 3, OUTLINE);
        g.fill(x, y + 2, x + 1, b - 3, OUTLINE);
        g.fill(r - 1, y + 3, r, b - 2, OUTLINE);
        g.fill(x + 1, b - 3, x + 2, b - 2, OUTLINE);
        g.fill(x + 2, b - 2, x + 3, b - 1, OUTLINE);
        g.fill(r - 2, b - 2, r - 1, b - 1, OUTLINE);
        g.fill(x + 3, b - 1, r - 2, b, OUTLINE);
        // Background, inside the outline.
        g.fill(x + 2, y + 1, r - 3, y + 2, BACKGROUND);
        g.fill(x + 1, y + 2, r - 2, y + 3, BACKGROUND);
        g.fill(x + 1, y + 3, r - 1, b - 3, BACKGROUND);
        g.fill(x + 2, b - 3, r - 1, b - 2, BACKGROUND);
        g.fill(x + 3, b - 2, r - 2, b - 1, BACKGROUND);
        // Highlight along the top and left.
        g.fill(x + 2, y + 1, r - 3, y + 2, LIGHT);
        g.fill(x + 1, y + 2, r - 3, y + 3, LIGHT);
        g.fill(x + 1, y + 3, x + 3, b - 3, LIGHT);
        g.fill(x + 3, y + 3, x + 4, y + 4, LIGHT);
        // Shadow along the bottom and right.
        g.fill(r - 3, y + 3, r - 1, b - 3, SHADOW);
        g.fill(x + 3, b - 3, r - 1, b - 2, SHADOW);
        g.fill(x + 3, b - 2, r - 2, b - 1, SHADOW);
        g.fill(r - 4, b - 4, r - 3, b - 3, SHADOW);
    }

    static void slot(GuiGraphicsExtractor g, int x, int y) {
        inset(g, x, y, 18, 18);
    }

    /** Sunk in like a slot, any size: also the creative inventory's scrollbar track. */
    static void inset(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, SLOT);
        g.fill(x, y, x + w - 1, y + 1, SLOT_SHADOW);
        g.fill(x, y, x + 1, y + h - 1, SLOT_SHADOW);
        g.fill(x + 1, y + h - 1, x + w, y + h, LIGHT);
        g.fill(x + w - 1, y + 1, x + w, y + h, LIGHT);
    }

    /**
     * A button as the recipe book button is drawn, any size: an outline with two-pixel cut
     * corners, a one-pixel highlight and shadow, and a light grey face; blue while hovered.
     */
    static void button(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean hovered) {
        int outline = hovered ? HOVER_OUTLINE : OUTLINE;
        int shadow = hovered ? HOVER_SHADOW : SHADOW;
        int r = x + w;
        int b = y + h;
        g.fill(x + 2, y, r - 2, y + 1, outline);
        g.fill(x + 2, b - 1, r - 2, b, outline);
        g.fill(x, y + 2, x + 1, b - 2, outline);
        g.fill(r - 1, y + 2, r, b - 2, outline);
        g.fill(x + 1, y + 1, x + 2, y + 2, outline);
        g.fill(r - 2, y + 1, r - 1, y + 2, outline);
        g.fill(x + 1, b - 2, x + 2, b - 1, outline);
        g.fill(r - 2, b - 2, r - 1, b - 1, outline);
        g.fill(x + 2, y + 2, r - 2, b - 2, hovered ? HOVER_FACE : BACKGROUND);
        g.fill(x + 2, y + 1, r - 2, y + 2, LIGHT);
        g.fill(x + 1, y + 2, x + 2, b - 2, LIGHT);
        g.fill(x + 2, b - 2, r - 2, b - 1, shadow);
        g.fill(r - 2, y + 2, r - 1, b - 2, shadow);
    }
}
