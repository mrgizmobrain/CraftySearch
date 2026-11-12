package com.craftysearch.client.gui;

import com.craftysearch.client.background.StorageActions;
import com.craftysearch.client.config.ClientConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jspecify.annotations.Nullable;

import java.util.Locale;

/**
 * Cycles how the panel orders its tiles: most first (#), by name (A), by mod (@, as in the
 * search). Sits at the end of the search row; the choice is saved, so every panel keeps it.
 * In client-only mode, Shift-click checks every container within reach instead (Nov 11): with
 * Shift held over it, it shows ↻. Only over it, so shift-clicking stacks doesn't keep
 * changing it.
 */
final class PanelSortButton extends AbstractButton {
    static final int SIZE = 14;
    private static final int LABEL_COLOR = 0xFF404040;

    /** What the tooltip was last built for: the sort, or checking, and whether the Shift-click line shows. */
    private @Nullable String tooltipFor;

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
        ClientConfig.PanelSort sort = ClientConfig.get().panelSort;
        boolean canCheck = StorageActions.canCheckAll();
        boolean checking = canCheck && isHovered() && mc.hasShiftDown();
        String key = checking ? "craftysearch.panel.check_all" : "craftysearch.panel.sort." + sort.name().toLowerCase(Locale.ROOT);
        // Rebuilt only when it changes, not every frame.
        String state = key + canCheck;
        if (!state.equals(tooltipFor)) {
            tooltipFor = state;
            MutableComponent text = Component.translatable(key);
            if (canCheck && !checking) {
                text.append("\n").append(Component.translatable("craftysearch.panel.sort.check_all_hint").withStyle(ChatFormatting.GRAY));
            }
            setTooltip(Tooltip.create(text));
        }
        String label = checking ? "↻" : switch (sort) {
            case COUNT -> "#";
            case NAME -> "A";
            case MOD -> "@";
        };
        Font font = mc.font;
        // Dark on the light face, as container titles are.
        g.text(font, label, getX() + (getWidth() - font.width(label) + 1) / 2, getY() + (getHeight() - font.lineHeight) / 2 + 1,
                LABEL_COLOR, false);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
