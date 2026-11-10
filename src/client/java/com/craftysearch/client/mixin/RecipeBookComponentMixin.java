package com.craftysearch.client.mixin;

import com.craftysearch.client.background.InteractionHold;
import com.craftysearch.client.recipe.RecipeStorage;
import com.craftysearch.client.storage.StorageIndex;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.CraftingRecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Adds remembered storage to the crafting recipe book's ingredient counts, and holds the
 * book's layout still while the player clicks (see {@link InteractionHold}).
 */
@Mixin(RecipeBookComponent.class)
abstract class RecipeBookComponentMixin {
    // The menu is read through RecipeBookComponentInvoker, which also makes it settable.
    @Shadow protected Minecraft minecraft;
    @Shadow @Final private StackedItemContents stackedContents;
    @Shadow private int timesInventoryChanged;
    @Shadow private @Nullable RecipeDisplayId lastPlacedRecipe;

    @Shadow
    public abstract void recipesUpdated();

    /** -1 until the first count, which includes storage and always runs. */
    @Unique private int craftysearch$countedMemoryVersion = -1;
    /** A recipe book update (new recipes unlocked) arrived during a hold and waits for it to end. */
    @Unique private boolean craftysearch$recipesPending;

    @Inject(method = "updateStackedContents", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screens/recipebook/RecipeBookComponent;selectMatchingRecipes()V"))
    private void craftysearch$addStorage(CallbackInfo ci) {
        craftysearch$countedMemoryVersion = StorageIndex.version();
        if ((Object) this instanceof CraftingRecipeBookComponent) {
            RecipeStorage.addStorage(minecraft, ((RecipeBookComponentInvoker) (Object) this).craftysearch$getMenu(), stackedContents);
        } else {
            RecipeStorage.reset();
        }
    }

    /**
     * Vanilla only recounts when the inventory changes; recount when storage changes too.
     * While the player is clicking recipes (or a background job runs), skip the recount, so
     * craftable recipes don't reshuffle under the cursor. It catches up once the hold ends:
     * vanilla compares the inventory's change counter, which keeps counting meanwhile.
     */
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void craftysearch$recount(CallbackInfo ci) {
        if (InteractionHold.held() && craftysearch$countedMemoryVersion != -1) {
            ci.cancel();
            return;
        }
        if (craftysearch$recipesPending) {
            craftysearch$recipesPending = false;
            recipesUpdated();
        }
        if (craftysearch$countedMemoryVersion != StorageIndex.version()) {
            timesInventoryChanged = -1;
        }
    }

    /**
     * Vanilla ignores a click on a recipe it counts as not craftable when that was the last
     * one placed, so its ghost isn't sent twice. While the book holds still, or just after a
     * recipe ran out of direct ingredients, that count is behind, and repeated clicks were
     * dropped before the mod could craft via intermediates (Nov 9). For crafting, every click
     * goes through; one that can't craft just shows the ghost again.
     */
    @Inject(method = "tryPlaceRecipe", at = @At("HEAD"))
    private void craftysearch$alwaysTry(RecipeCollection collection, RecipeDisplayId recipe, boolean useMax,
                                        CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof CraftingRecipeBookComponent) lastPlacedRecipe = null;
    }

    /**
     * Crafting something new often unlocks recipes, and the update re-filters and re-tabs
     * the whole book. During a hold it waits, and runs from tick once the hold ends.
     */
    @Inject(method = "recipesUpdated", at = @At("HEAD"), cancellable = true)
    private void craftysearch$holdUnlocks(CallbackInfo ci) {
        if (InteractionHold.held()) {
            craftysearch$recipesPending = true;
            ci.cancel();
        }
    }
}
