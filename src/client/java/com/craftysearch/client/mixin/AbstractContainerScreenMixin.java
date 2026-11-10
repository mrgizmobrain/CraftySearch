package com.craftysearch.client.mixin;

import com.craftysearch.client.background.BackgroundJobs;
import com.craftysearch.client.gui.PanelHooks;
import com.craftysearch.client.highlight.SlotHighlights;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractContainerScreen.class)
abstract class AbstractContainerScreenMixin {
    /** Clicks on the storage panel are outside the container image but must not drop the carried stack. */
    @Inject(method = "hasClickedOutside", at = @At("HEAD"), cancellable = true)
    private void craftysearch$insidePanel(double mouseX, double mouseY, int left, int top,
                                          CallbackInfoReturnable<Boolean> cir) {
        if (PanelHooks.isOverPanel((Screen) (Object) this, mouseX, mouseY)) cir.setReturnValue(false);
    }

    /**
     * Spec 3.5: a quick-move of an inventory stack with the put-away button, while the panel
     * shows, stores it instead. Every quick-move made through the screen passes here: a
     * shift-click, vanilla's shift-double-click (every stack of the item), and drags from
     * mods like Mouse Tweaks, which quick-move each slot they pass over.
     */
    @Inject(method = "slotClicked", at = @At("HEAD"), cancellable = true)
    private void craftysearch$quickMoveToStorage(@Nullable Slot slot, int slotId, int button, ContainerInput input, CallbackInfo ci) {
        if (PanelHooks.routeQuickMove((Screen) (Object) this, slot, button, input)) {
            ci.cancel();
            return;
        }
        // Decided here already, so the game mode hook leaves it alone (see PanelHooks.routeDraggedQuickMove).
        PanelHooks.beginScreenClick();
    }

    @Inject(method = "slotClicked", at = @At("RETURN"))
    private void craftysearch$screenClickDone(@Nullable Slot slot, int slotId, int button, ContainerInput input, CallbackInfo ci) {
        PanelHooks.endScreenClick();
    }

    /**
     * Before the item and its count are drawn, so the search highlight sits behind them. A
     * slot parking an item for the cursor during a background job isn't drawn at all; the
     * item is drawn on the cursor instead.
     */
    @Inject(method = "extractSlot", at = @At("HEAD"), cancellable = true)
    private void craftysearch$beforeSlot(GuiGraphicsExtractor g, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        if (BackgroundJobs.hidesSlot(slot)) {
            ci.cancel();
            return;
        }
        SlotHighlights.extractBehind((Screen) (Object) this, g, slot);
    }
}
