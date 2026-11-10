package com.craftysearch.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server: send live data for containers within {@code radius} (capped by the
 * server) of these kinds. Sent after {@link HelloPayload} and again when the settings change.
 */
public record SubscribePayload(int radius, int kindsMask) implements CustomPacketPayload {
    public static final Type<SubscribePayload> TYPE = ModPayloads.type("subscribe");
    public static final StreamCodec<RegistryFriendlyByteBuf, SubscribePayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SubscribePayload::radius,
            ByteBufCodecs.VAR_INT, SubscribePayload::kindsMask,
            SubscribePayload::new);

    @Override
    public Type<SubscribePayload> type() {
        return TYPE;
    }
}
