package com.craftysearch.client.mixin;

import com.craftysearch.client.background.BackgroundAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks for background container access. Each one runs just after vanilla hops from the
 * network thread to the client thread, so the mod's state is only touched on one thread.
 */
@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerMixin {
    @Unique private static final String ON_CLIENT_THREAD =
            "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V";

    // The minecraft field is declared in the parent class, which @Shadow can't reach.
    @Inject(method = "handleOpenScreen", cancellable = true,
            at = @At(value = "INVOKE", target = ON_CLIENT_THREAD, shift = At.Shift.AFTER))
    private void craftysearch$hideBackgroundScreen(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
        if (BackgroundAccess.onOpenScreen(Minecraft.getInstance(), packet)) ci.cancel();
    }

    @Inject(method = "handleContainerClose", cancellable = true,
            at = @At(value = "INVOKE", target = ON_CLIENT_THREAD, shift = At.Shift.AFTER))
    private void craftysearch$keepCraftingScreen(ClientboundContainerClosePacket packet, CallbackInfo ci) {
        if (BackgroundAccess.onContainerClose(packet)) ci.cancel();
    }

    @Inject(method = "handleSoundEvent", cancellable = true,
            at = @At(value = "INVOKE", target = ON_CLIENT_THREAD, shift = At.Shift.AFTER))
    private void craftysearch$muteBackgroundContainer(ClientboundSoundPacket packet, CallbackInfo ci) {
        if (BackgroundAccess.shouldMuteSound(packet.getX(), packet.getY(), packet.getZ())) ci.cancel();
    }

    @Inject(method = "handleBlockEvent", cancellable = true,
            at = @At(value = "INVOKE", target = ON_CLIENT_THREAD, shift = At.Shift.AFTER))
    private void craftysearch$hideBackgroundLid(ClientboundBlockEventPacket packet, CallbackInfo ci) {
        if (BackgroundAccess.shouldHideBlockEvent(packet.getPos())) ci.cancel();
    }
}
