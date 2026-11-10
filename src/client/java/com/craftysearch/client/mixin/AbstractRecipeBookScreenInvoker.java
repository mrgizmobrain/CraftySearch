package com.craftysearch.client.mixin;

import net.minecraft.client.gui.navigation.ScreenPosition;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Where the recipe book button is, so the panel toggle sits next to it, and the book itself. */
@Mixin(AbstractRecipeBookScreen.class)
public interface AbstractRecipeBookScreenInvoker {
    @Invoker("getRecipeBookButtonPosition")
    ScreenPosition craftysearch$getRecipeBookButtonPosition();

    @Accessor("recipeBookComponent")
    RecipeBookComponent<?> craftysearch$getRecipeBookComponent();
}
