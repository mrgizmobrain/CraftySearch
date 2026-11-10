package com.craftysearch.client.live;

import com.craftysearch.CraftySearch;
import com.craftysearch.client.config.ClientConfig;
import com.craftysearch.network.HelloPayload;
import com.craftysearch.network.ModPayloads;
import com.craftysearch.network.StorageUpdatePayload;
import com.craftysearch.network.SubscribePayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * Whether the server runs Crafty Search (spec 4.1). Set by the server's hello on join and
 * cleared on disconnect; without it the client stays in client-only mode.
 */
public final class ServerMode {
    private static boolean active;
    private static int maxRadius;
    private static boolean allowCrafting;
    private static boolean allowTakeAndPutAway;
    private static boolean allowRemoteOpen;

    private ServerMode() {
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(HelloPayload.TYPE, (payload, context) -> onHello(payload));
        ClientPlayNetworking.registerGlobalReceiver(StorageUpdatePayload.TYPE,
                (payload, context) -> LiveStorage.get().apply(context.client(), payload));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> ServerOutbox.tick());
    }

    public static boolean active() {
        return active;
    }

    public static boolean craftingAllowed() {
        return active && allowCrafting;
    }

    public static boolean takeAndPutAwayAllowed() {
        return active && allowTakeAndPutAway;
    }

    /** The server opens containers anywhere within the radius for a right-click on a tile. */
    public static boolean remoteOpenAllowed() {
        return active && allowRemoteOpen;
    }

    /** The client's radius, capped by the server's. */
    public static int radius() {
        return Math.min(ClientConfig.get().radius, maxRadius);
    }

    private static void onHello(HelloPayload hello) {
        if (hello.protocolVersion() != ModPayloads.PROTOCOL_VERSION) {
            CraftySearch.LOGGER.warn("Server runs Crafty Search protocol {}, this client {}; using client-only mode",
                    hello.protocolVersion(), ModPayloads.PROTOCOL_VERSION);
            return;
        }
        active = true;
        maxRadius = hello.maxRadius();
        allowCrafting = hello.allowCrafting();
        allowTakeAndPutAway = hello.allowTakeAndPutAway();
        allowRemoteOpen = hello.allowRemoteOpen();
        LiveStorage.get().clear();
        subscribe();
    }

    /** Asks for live data with the current radius and container kinds. Call again when they change. */
    public static void subscribe() {
        if (active) ClientPlayNetworking.send(new SubscribePayload(radius(), ClientConfig.get().kindsMask()));
    }

    public static void reset() {
        active = false;
        LiveStorage.get().clear();
        ServerOutbox.clear();
    }
}
