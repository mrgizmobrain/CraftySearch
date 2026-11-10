package com.craftysearch.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Shows or hides the storage panel, next to the recipe book button: the same size and look
 * (Nov 8), with a chest where that button has its book.
 */
final class PanelToggleButton extends AbstractButton {
    static final int WIDTH = 20;
    static final int HEIGHT = 18;
    private static final ItemStack ICON = new ItemStack(Items.CHEST);

    private final Runnable onPress;
    private String tooltipKey = "";

    PanelToggleButton(Runnable onPress) {
        super(0, 0, WIDTH, HEIGHT, Component.translatable("craftysearch.panel.toggle"));
        this.onPress = onPress;
    }

    /** Called every frame, so the tooltip is only rebuilt when the text changes. */
    void setTooltipKey(String key) {
        if (key.equals(tooltipKey)) return;
        tooltipKey = key;
        setTooltip(Tooltip.create(Component.translatable(key)));
    }

    @Override
    public void onPress(InputWithModifiers input) {
        onPress.run();
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // Blue only under the mouse, as the recipe book button: a click leaves the button focused.
        GuiDraw.button(g, getX(), getY(), WIDTH, HEIGHT, isHovered());
        // Full item size (scaled down to the face it looked small and lost its top). A chest is shorter
        // than a block, so it sits low in its square: 1 pixel up centres it in the frame (2 was too high; Nov 8).
        g.item(ICON, getX() + 2, getY());
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
