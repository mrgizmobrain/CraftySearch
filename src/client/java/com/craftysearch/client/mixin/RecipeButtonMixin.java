package com.craftysearch.client.mixin;

import com.craftysearch.client.recipe.CraftChains;
import com.craftysearch.client.recipe.RecipeStorage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tints and explains recipes whose ingredients are only in containers out of reach, and
 * recipes that need intermediates crafted first (spec 3.6).
 */
@Mixin(RecipeButton.class)
abstract class RecipeButtonMixin {
    @Unique private static final int OUT_OF_REACH_TINT = 0x70FFA000;
    @Unique private static final int INTERMEDIATES_TINT = 0x604090FF;

    // Between the background sprite and the result item, inside the same pose as the sprite.
    @Inject(method = "extractWidgetRenderState", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"))
    private void craftysearch$tint(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        RecipeButton self = (RecipeButton) (Object) this;
        // Out of reach wins: the click wouldn't work from here either way.
        int tint = RecipeStorage.isOutOfReach(self.getCollection()) ? OUT_OF_REACH_TINT
                : RecipeStorage.isViaIntermediates(self.getCollection()) ? INTERMEDIATES_TINT : 0;
        if (tint != 0) {
            g.fill(self.getX() + 1, self.getY() + 1, self.getX() + self.getWidth() - 1, self.getY() + self.getHeight() - 1, tint);
        }
    }

    @Inject(method = "getTooltipText", at = @At("RETURN"))
    private void craftysearch$explain(ItemStack stack, CallbackInfoReturnable<List<Component>> cir) {
        RecipeButton self = (RecipeButton) (Object) this;
        CraftChains.Chain chain = RecipeStorage.intermediatesFor(self.getCurrentRecipe());
        if (chain != null) {
            // One total per item, in crafting order, even if two steps make the same thing.
            Map<Item, Integer> totals = new LinkedHashMap<>();
            Map<Item, ItemStack> shown = new LinkedHashMap<>();
            for (CraftChains.Step step : chain.steps()) {
                totals.merge(step.result().getItem(), step.produced(), Integer::sum);
                shown.putIfAbsent(step.result().getItem(), step.result());
            }
            MutableComponent made = Component.empty();
            for (Map.Entry<Item, Integer> e : totals.entrySet()) {
                if (!made.getSiblings().isEmpty()) made.append(", ");
                made.append(e.getValue() + " ").append(shown.get(e.getKey()).getHoverName());
            }
            cir.getReturnValue().add(Component.translatable("craftysearch.recipe.via_intermediates", made).withStyle(ChatFormatting.AQUA));
        }
        if (RecipeStorage.isOutOfReach(self.getCollection())) {
            cir.getReturnValue().add(Component.translatable("craftysearch.recipe.out_of_reach").withStyle(ChatFormatting.GOLD));
        }
    }
}
