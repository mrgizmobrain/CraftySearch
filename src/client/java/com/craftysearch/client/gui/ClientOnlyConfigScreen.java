package com.craftysearch.client.gui;

import com.craftysearch.client.config.ClientConfig;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;

/** Settings that only matter on servers without Crafty Search: memory and background container access. */
public final class ClientOnlyConfigScreen extends SettingsScreen {
    public ClientOnlyConfigScreen(Screen parent) {
        super(parent, "client_only.title", "client_only.note");
    }

    @Override
    protected void addSettings(ClientConfig c) {
        ClientConfig.ClientOnly o = c.clientOnly;
        // One switch over the options anti-cheat may flag (Nov 11). The page is laid out again on a change,
        // so those show what's in effect, greyed out, while it's on.
        add(toggle("anti_cheat_safe", o.antiCheatSafe, v -> {
            o.antiCheatSafe = v;
            rebuildWidgets();
        }));
        add(toggle("memory", c.memory.enabled, v -> c.memory.enabled = v));
        add(toggle("show_stale_age", c.memory.showStaleAge, v -> c.memory.showStaleAge = v));
        add(risky(o, choice("speed", ClientConfig.Speed.values(), o.fast() ? ClientConfig.Speed.FAST : ClientConfig.Speed.NORMAL, v -> o.speed = v)));
        // "Exact amounts" is gone (Nov 9): exact takes cost a few clicks now, and repeated clicks come as one.
        add(risky(o, toggle("scan_stacked_carts", o.scansStackedCarts(), v -> o.scanStackedCarts = v)));
        add(toggle("mute_sounds", o.muteOwnContainerSounds, v -> o.muteOwnContainerSounds = v));
        add(toggle("hide_lids", o.hideOwnContainerAnimations, v -> o.hideOwnContainerAnimations = v));
        add(risky(o, toggle("extra_block_reach", o.reachesFurtherForBlocks(), v -> o.extraBlockReach = v)));
        add(risky(o, toggle("extra_cart_reach", o.reachesFurtherForCarts(), v -> o.extraCartReach = v)));
        add(risky(o, toggle("recheck_used", o.rechecksUsed(), v -> o.recheckUsed = v)));
    }

    /** A setting "Anti-cheat safe" holds at its safe value: it can't be changed while that's on, and keeps its own value for later. */
    private static AbstractWidget risky(ClientConfig.ClientOnly o, AbstractWidget widget) {
        widget.active = !o.antiCheatSafe;
        return widget;
    }
}
