package com.craftysearch.network;

import com.craftysearch.storage.ContainerKey;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server: open container {@code key} as if the player had right-clicked it, from
 * anywhere within {@code radius} (a right-click on a panel tile, Nov 9). The server checks it
 * the way it checks containers for live data: the container kinds, claims and locks.
 */
public record OpenContainerPayload(ContainerKey key, int radius, int kindsMask) implements CustomPacketPayload {
    public static final Type<OpenContainerPayload> TYPE = ModPayloads.type("open");
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenContainerPayload> CODEC = StreamCodec.composite(
            ContainerKey.STREAM_CODEC, OpenContainerPayload::key,
            ByteBufCodecs.VAR_INT, OpenContainerPayload::radius,
            ByteBufCodecs.VAR_INT, OpenContainerPayload::kindsMask,
            OpenContainerPayload::new);

    @Override
    public Type<OpenContainerPayload> type() {
        return TYPE;
    }
}
