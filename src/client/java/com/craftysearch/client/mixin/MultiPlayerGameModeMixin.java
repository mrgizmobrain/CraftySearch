package com.craftysearch.client.mixin;

import com.craftysearch.client.background.BackgroundJobs;
import com.craftysearch.client.gui.PanelHooks;
import com.craftysearch.client.live.CraftRouting;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sends recipe book clicks as craft requests when the server runs the mod, and guards hidden containers. */
@Mixin(MultiPlayerGameMode.class)
abstract class MultiPlayerGameModeMixin {
    @Inject(method = "handlePlaceRecipe", at = @At("HEAD"), cancellable = true)
    private void craftysearch$craftFromStorage(int containerId, RecipeDisplayId recipe, boolean useMaxItems, CallbackInfo ci) {
        if (CraftRouting.route(containerId, recipe, useMaxItems)) ci.cancel();
    }

    /**
     * Every container click passes here, the mod's and other mods' alike (spec 3.5): other
     * mods' quick-moves during a put-away drag are stored instead (IPN's swipe moving), and
     * other clicks wait while a hidden container is open.
     */
    @Inject(method = "handleContainerInput", at = @At("HEAD"), cancellable = true)
    private void craftysearch$guardHiddenContainer(int containerId, int slot, int button, ContainerInput input, Player player,
                                                   CallbackInfo ci) {
        if (BackgroundJobs.isOwnClick()) return;
        if (PanelHooks.routeDraggedQuickMove(containerId, slot, input) || BackgroundJobs.blocksForeignClick()) ci.cancel();
    }
}
