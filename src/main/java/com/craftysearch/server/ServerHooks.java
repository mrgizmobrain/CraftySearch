package com.craftysearch.server;

import com.craftysearch.ServerConfig;
import com.craftysearch.network.CraftRequestPayload;
import com.craftysearch.network.HelloPayload;
import com.craftysearch.network.ModPayloads;
import com.craftysearch.network.OpenContainerPayload;
import com.craftysearch.network.PutAwayPayload;
import com.craftysearch.network.SubscribePayload;
import com.craftysearch.network.TakePayload;
import com.craftysearch.server.protection.Protection;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

/** Wires up server mode: the handshake, live data and crafting requests. */
public final class ServerHooks {
    private ServerHooks() {
    }

    public static void register() {
        // Only clients with the mod register the hello channel, so canSend doubles as the handshake.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerConfig config = ServerConfig.get();
            if (config.enabled && ServerPlayNetworking.canSend(handler.player, HelloPayload.TYPE)) {
                ServerPlayNetworking.send(handler.player,
                        new HelloPayload(ModPayloads.PROTOCOL_VERSION, config.maxRadius, config.allowCrafting,
                                config.allowTakeAndPutAway, config.allowRemoteOpen));
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            StorageSync.remove(handler.player);
            StorageCrafting.forget(handler.player);
            StorageTransfer.forget(handler.player);
        });
        // Receivers run on the server thread.
        ServerPlayNetworking.registerGlobalReceiver(SubscribePayload.TYPE, (payload, context) -> {
            if (ServerConfig.get().enabled) StorageSync.subscribe(context.player(), payload);
        });
        ServerPlayNetworking.registerGlobalReceiver(CraftRequestPayload.TYPE,
                (payload, context) -> StorageCrafting.handle(context.server(), context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(TakePayload.TYPE,
                (payload, context) -> StorageTransfer.take(context.server(), context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(PutAwayPayload.TYPE,
                (payload, context) -> StorageTransfer.putAway(context.server(), context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(OpenContainerPayload.TYPE,
                (payload, context) -> RemoteOpen.open(context.player(), payload));
        ServerTickEvents.END_SERVER_TICK.register(StorageSync::tick);
        // Read the config again for every server start: in singleplayer that's every world joined,
        // so editing craftysearch-server.json and rejoining is enough.
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            ServerConfig.load();
            Protection.init();
        });
        // Singleplayer reuses the JVM between worlds.
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> StorageSync.clear());
    }
}
