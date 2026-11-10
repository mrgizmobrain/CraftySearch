package com.craftysearch.client.mixin;

import com.craftysearch.client.recipe.RecipeStorage;
import net.minecraft.client.gui.screens.recipebook.CraftingRecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.world.entity.player.StackedItemContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Works out which craftable recipes can also be made from containers within reach. */
@Mixin(CraftingRecipeBookComponent.class)
abstract class CraftingRecipeBookComponentMixin {
    @Inject(method = "selectMatchingRecipes", at = @At("TAIL"))
    private void craftysearch$checkReach(RecipeCollection collection, StackedItemContents contents, CallbackInfo ci) {
        RecipeStorage.collectionSelected(collection);
    }
}
