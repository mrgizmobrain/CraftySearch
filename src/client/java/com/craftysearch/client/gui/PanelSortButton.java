package com.craftysearch.client.gui;

import com.craftysearch.client.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.Locale;

/**
 * Cycles how the panel orders its tiles: most first (#), by name (A), by mod (@, as in the
 * search). Sits at the end of the search row; the choice is saved, so every panel keeps it.
 */
final class PanelSortButton extends AbstractButton {
    static final int SIZE = 14;
    private static final int LABEL_COLOR = 0xFF404040;

    private ClientConfig.@Nullable PanelSort tooltipFor;

    PanelSortButton() {
        super(0, 0, SIZE, SIZE, Component.translatable("craftysearch.panel.sort"));
    }

    @Override
    public void onPress(InputWithModifiers input) {
        ClientConfig config = ClientConfig.get();
        config.panelSort = config.panelSort.next();
        ClientConfig.save();
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // Drawn like the panel toggle and the recipe book button (Nov 8).
        GuiDraw.button(g, getX(), getY(), SIZE, SIZE, isHovered());
        ClientConfig.PanelSort sort = ClientConfig.get().panelSort;
        // Rebuilt only when the sort changes, not every frame.
        if (sort != tooltipFor) {
            tooltipFor = sort;
            setTooltip(Tooltip.create(Component.translatable("craftysearch.panel.sort." + sort.name().toLowerCase(Locale.ROOT))));
        }
        String label = switch (sort) {
            case COUNT -> "#";
            case NAME -> "A";
            case MOD -> "@";
        };
        Font font = Minecraft.getInstance().font;
        // Dark on the light face, as container titles are.
        g.text(font, label, getX() + (getWidth() - font.width(label) + 1) / 2, getY() + (getHeight() - font.lineHeight) / 2 + 1,
                LABEL_COLOR, false);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
