package com.craftysearch.client.mixin;

import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Set;

/** The collection's craftable set, so recipes craftable via intermediates can be added to it (spec 3.6). */
@Mixin(RecipeCollection.class)
public interface RecipeCollectionAccessor {
    @Accessor("craftable")
    Set<RecipeDisplayId> craftysearch$getCraftable();
}
