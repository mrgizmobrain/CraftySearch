package com.craftysearch.client.gui;

import com.craftysearch.client.config.ClientConfig;
import net.minecraft.client.gui.screens.Screen;

/** Settings that only matter on servers without Crafty Search: memory and background container access. */
public final class ClientOnlyConfigScreen extends SettingsScreen {
    public ClientOnlyConfigScreen(Screen parent) {
        super(parent, "client_only.title", "client_only.note");
    }

    @Override
    protected void addSettings(ClientConfig c) {
        add(toggle("memory", c.memory.enabled, v -> c.memory.enabled = v));
        add(toggle("show_stale_age", c.memory.showStaleAge, v -> c.memory.showStaleAge = v));
        add(choice("speed", ClientConfig.Speed.values(), c.clientOnly.speed, v -> c.clientOnly.speed = v));
        // "Exact amounts" is gone (Nov 9): exact takes cost a few clicks now, and repeated clicks come as one.
        add(toggle("scan_stacked_carts", c.clientOnly.scanStackedCarts, v -> c.clientOnly.scanStackedCarts = v));
        add(toggle("mute_sounds", c.clientOnly.muteOwnContainerSounds, v -> c.clientOnly.muteOwnContainerSounds = v));
        add(toggle("hide_lids", c.clientOnly.hideOwnContainerAnimations, v -> c.clientOnly.hideOwnContainerAnimations = v));
        add(toggle("extra_block_reach", c.clientOnly.extraBlockReach, v -> c.clientOnly.extraBlockReach = v));
        add(toggle("extra_cart_reach", c.clientOnly.extraCartReach, v -> c.clientOnly.extraCartReach = v));
    }
}
