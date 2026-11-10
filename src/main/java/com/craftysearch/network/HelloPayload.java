package com.craftysearch.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client on join: this server runs Crafty Search, with these limits. The protocol
 * version comes first, and a client on another version skips the rest unread, so the rest
 * can change in later versions without that client failing to read it (which would
 * disconnect it); it just stays in client-only mode.
 */
public record HelloPayload(int protocolVersion, int maxRadius, boolean allowCrafting, boolean allowTakeAndPutAway,
                           boolean allowRemoteOpen)
        implements CustomPacketPayload {
    public static final Type<HelloPayload> TYPE = ModPayloads.type("hello");
    public static final StreamCodec<RegistryFriendlyByteBuf, HelloPayload> CODEC = StreamCodec.of(
            (buf, hello) -> {
                buf.writeVarInt(hello.protocolVersion());
                buf.writeVarInt(hello.maxRadius());
                buf.writeBoolean(hello.allowCrafting());
                buf.writeBoolean(hello.allowTakeAndPutAway());
                buf.writeBoolean(hello.allowRemoteOpen());
            },
            buf -> {
                int version = buf.readVarInt();
                if (version != ModPayloads.PROTOCOL_VERSION) {
                    buf.skipBytes(buf.readableBytes());
                    return new HelloPayload(version, 0, false, false, false);
                }
                return new HelloPayload(version, buf.readVarInt(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean());
            });

    @Override
    public Type<HelloPayload> type() {
        return TYPE;
    }
}
