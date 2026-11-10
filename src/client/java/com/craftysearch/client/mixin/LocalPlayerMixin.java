package com.craftysearch.client.mixin;

import com.craftysearch.client.memory.ContainerRecorder;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The inventory key while riding a vehicle with its own inventory asks the server to open
 * that instead. For a chest boat, that's the boat's chest, so the recorder can match it.
 */
@Mixin(LocalPlayer.class)
abstract class LocalPlayerMixin {
    @Inject(method = "sendOpenInventory", at = @At("HEAD"))
    private void craftysearch$openingVehicleInventory(CallbackInfo ci) {
        ContainerRecorder.openingVehicleInventory(((LocalPlayer) (Object) this).getVehicle());
    }
}
