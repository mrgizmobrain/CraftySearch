package com.craftysearch.client.mixin;

import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.world.inventory.RecipeBookMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The recipe book's top-left corner (its own isMouseOver always answers false, so the mod
 * works out from these whether a click landed on the book), and its menu, which a reopened
 * crafting table replaces in place.
 */
@Mixin(RecipeBookComponent.class)
public interface RecipeBookComponentInvoker {
    @Invoker("getXOrigin")
    int craftysearch$getXOrigin();

    @Invoker("getYOrigin")
    int craftysearch$getYOrigin();

    @Accessor("menu")
    RecipeBookMenu craftysearch$getMenu();

    @Mutable
    @Accessor("menu")
    void craftysearch$setMenu(RecipeBookMenu menu);
}
