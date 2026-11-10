package com.craftysearch.network;

import com.craftysearch.CraftySearch;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * The server-mode protocol (spec 4.1). The server greets clients that have the mod with
 * {@link HelloPayload}; the client subscribes to live data with {@link SubscribePayload} and
 * gets {@link StorageUpdatePayload}s; recipe clicks become {@link CraftRequestPayload}s.
 */
public final class ModPayloads {
    /**
     * Bump on any incompatible change; mismatched clients stay in client-only mode.
     * 2: take and put away. 3: put away from a slot. 4: chest minecarts and boats (container keys).
     * 5: intermediate steps in craft requests. 6: exact amounts and target slots for take and put away.
     * 7: the "Leave one" flag on take and craft requests. 8: locked inventory slots on take and craft requests.
     * 9: several slots in one put-away request. 10: several items in one take request.
     * 11: opening a container from the panel; take all, throw, and more than a stack per take.
     */
    public static final int PROTOCOL_VERSION = 11;

    private ModPayloads() {
    }

    static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String path) {
        return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(CraftySearch.MOD_ID, path));
    }

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(HelloPayload.TYPE, HelloPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(StorageUpdatePayload.TYPE, StorageUpdatePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SubscribePayload.TYPE, SubscribePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(CraftRequestPayload.TYPE, CraftRequestPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(TakePayload.TYPE, TakePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(PutAwayPayload.TYPE, PutAwayPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(OpenContainerPayload.TYPE, OpenContainerPayload.CODEC);
    }
}
