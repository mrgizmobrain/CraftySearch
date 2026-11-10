package com.craftysearch.client.mixin;

import com.craftysearch.client.background.BackgroundJobs;
import com.craftysearch.client.live.ServerOutbox;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Inventory Profiles Next's inventory actions (Nov 8): sorting (its key and its buttons), move
 * matching, throwing and its scroll wheel. Each works out a run of clicks from the player's
 * open menu. While a client-only job runs, that menu is a hidden container (IPN would sort
 * the chest being read), and the mod's click guard drops most of the clicks, so a sort would
 * stop halfway. While put-away and take requests wait for the server, the inventory is about
 * to change under it. So they wait, with a word for the ones a player starts by key or button.
 * Applies only when IPN is installed; if IPN renames these, nothing breaks.
 */
@Pseudo
@Mixin(targets = "org.anti_ad.mc.ipnext.inventory.GeneralInventoryActions", remap = false)
abstract class IpnActionsMixin {
    @Inject(method = {"doSort", "doSortInColumns", "doSortInRows", "doMoveMatch", "doMoveMatchCrafting", "doThrowOfType",
            "doThrowMatch"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void craftysearch$waitForStorage(CallbackInfo ci) {
        if (!craftysearch$busy()) return;
        ci.cancel();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.sendOverlayMessage(Component.translatable("craftysearch.background.wait_for_storage"));
    }

    @Inject(method = {"scrollToPlayer", "scrollToChest", "scrollUp", "scrollDown"}, at = @At("HEAD"), cancellable = true,
            require = 0)
    private void craftysearch$scrollWaits(CallbackInfo ci) {
        if (craftysearch$busy()) ci.cancel();
    }

    @Unique
    private static boolean craftysearch$busy() {
        return BackgroundJobs.isBusy() || BackgroundJobs.screenShowsAnotherMenu() || ServerOutbox.isBusy();
    }
}
