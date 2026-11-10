package com.craftysearch.client.mixin;

import com.craftysearch.client.background.BackgroundJobs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Inventory Profiles Next's slot overlays (Nov 8): "highlight focused items" (green squares),
 * auto-refill markers (purple arrows) and locked slots (the yellow highlight while Alt is
 * held). Each draws over the slots of the player's open menu, placed by that menu's slots.
 * While a client-only job has a hidden container open, that menu is the container's, not the
 * screen's, so they landed in random places. They hold off while a job runs or waits in the
 * queue (holding off only while a container was open made them flash between containers).
 * Applies only when IPN is installed; if IPN renames these, nothing breaks and the old glitch
 * is back.
 */
@Pseudo
@Mixin(targets = {
        "org.anti_ad.mc.ipnext.event.SlotHighlightHandler",
        "org.anti_ad.mc.ipnext.event.autorefill.AutoRefillHandler",
        "org.anti_ad.mc.ipnext.event.LockSlotsHandler"
}, remap = false)
abstract class IpnSlotHighlightMixin {
    @Inject(method = {"onBackgroundRender", "onForegroundRender", "onPostRender", "drawBackground", "drawForeground", "drawConfig"},
            at = @At("HEAD"), cancellable = true, require = 0)
    private void craftysearch$notOverHiddenMenu(CallbackInfo ci) {
        if (BackgroundJobs.hidesSlotOverlays()) ci.cancel();
    }
}
