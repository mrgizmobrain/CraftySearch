package com.craftysearch.client.mixin;

import com.craftysearch.client.gui.PanelHooks;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Clicks on the panel aren't "outside" (as in {@link AbstractContainerScreenMixin}; recipe
 * book screens override hasClickedOutside without calling super). And while the panel is
 * open, the screen moves left to make room for it ({@link PanelHooks#shift}): the recipe book
 * and the screen's own position are both worked out from the screen width, so they're given
 * one narrower by twice the shift, which moves the inventory, the book and its tabs together.
 */
@Mixin(AbstractRecipeBookScreen.class)
abstract class AbstractRecipeBookScreenMixin {
    @Inject(method = "hasClickedOutside", at = @At("HEAD"), cancellable = true)
    private void craftysearch$insidePanel(double mouseX, double mouseY, int left, int top,
                                          CallbackInfoReturnable<Boolean> cir) {
        if (PanelHooks.isOverPanel((Screen) (Object) this, mouseX, mouseY)) cir.setReturnValue(false);
    }

    @ModifyArg(method = "init", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screens/recipebook/RecipeBookComponent;init(IILnet/minecraft/client/Minecraft;Z)V"),
            index = 0)
    private int craftysearch$shiftBook(int width) {
        return width - 2 * craftysearch$shift();
    }

    // Also when the recipe book button opens or closes the book, which places the screen again.
    @ModifyArg(method = {"init", "lambda$initButton$0"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screens/recipebook/RecipeBookComponent;updateScreenPosition(II)I"),
            index = 0)
    private int craftysearch$shiftScreen(int width) {
        return width - 2 * craftysearch$shift();
    }

    @Unique
    private int craftysearch$shift() {
        Screen self = (Screen) (Object) this;
        return PanelHooks.shift(self, self.width, ((AbstractContainerScreenAccessor) self).craftysearch$getImageWidth());
    }
}
