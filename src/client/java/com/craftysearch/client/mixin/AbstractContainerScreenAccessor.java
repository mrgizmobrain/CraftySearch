package com.craftysearch.client.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The container image bounds, needed to place the storage panel to its right. */
@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {
    @Accessor("leftPos")
    int craftysearch$getLeftPos();

    @Accessor("topPos")
    int craftysearch$getTopPos();

    @Accessor("imageWidth")
    int craftysearch$getImageWidth();

    @Accessor("imageHeight")
    int craftysearch$getImageHeight();

    /** The slot under the mouse, as of the last frame. */
    @Accessor("hoveredSlot")
    Slot craftysearch$getHoveredSlot();

    /** The slot at a position, worked out now: for drags and scrolls between frames. */
    @Invoker("getHoveredSlot")
    @Nullable Slot craftysearch$slotAt(double x, double y);

    /** Swaps in a reopened crafting table's menu, so the screen (and its recipe book layout) stays. */
    @Mutable
    @Accessor("menu")
    void craftysearch$setMenu(AbstractContainerMenu menu);
}
