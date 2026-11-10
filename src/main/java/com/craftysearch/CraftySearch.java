package com.craftysearch;

import com.craftysearch.network.ModPayloads;
import com.craftysearch.server.ServerHooks;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common entrypoint. Runs on both the client and the server (including the
 * singleplayer integrated server). The server part of Crafty Search lives here.
 */
public class CraftySearch implements ModInitializer {
    public static final String MOD_ID = "craftysearch";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        ServerConfig.load();
        ModPayloads.register();
        ServerHooks.register();
        LOGGER.info("Crafty Search loaded (common)");
    }
}
