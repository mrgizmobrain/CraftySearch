package com.craftysearch.client.gui;

import com.craftysearch.client.config.ClientConfig;
import com.craftysearch.client.live.ServerMode;
import com.craftysearch.storage.ContainerKind;
import net.minecraft.client.gui.screens.Screen;

/** Which container kinds count as storage, and whether your inventory and shulker box contents count in the totals. */
public final class ContainersConfigScreen extends SettingsScreen {
    public ContainersConfigScreen(Screen parent) {
        super(parent, "containers.title", null);
    }

    @Override
    protected void addSettings(ClientConfig c) {
        for (ContainerKind kind : ContainerKind.values()) {
            add(toggle("container." + kind.id(), c.includes(kind), on -> {
                c.includeContainerTypes.remove(kind.id());
                if (on) c.includeContainerTypes.add(kind.id());
            }));
        }
        // Your inventory counts in the totals like one more container (moved here from the main page, Nov 7),
        // and so does what's inside shulker boxes (Nov 8).
        add(toggle("include_inventory", c.includeInventory, v -> c.includeInventory = v));
        add(toggle("shulker_contents", c.shulkerContents, v -> c.shulkerContents = v));
    }

    @Override
    protected void saved() {
        // The container kinds may have changed.
        ServerMode.subscribe();
    }
}
