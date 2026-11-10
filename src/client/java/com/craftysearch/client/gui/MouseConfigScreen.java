package com.craftysearch.client.gui;

import com.craftysearch.client.config.ClientConfig;
import net.minecraft.client.gui.screens.Screen;

/** The scroll wheel, shift-drag and the clicks on storage (spec 3.5). */
public final class MouseConfigScreen extends SettingsScreen {
    public MouseConfigScreen(Screen parent) {
        super(parent, "mouse.title", null);
    }

    @Override
    protected void addSettings(ClientConfig config) {
        ClientConfig.Mouse c = config.mouse;
        add(choice("scroll_direction", ClientConfig.ScrollDirection.values(), c.scrollDirection, v -> c.scrollDirection = v));
        add(toggle("shift_drag", c.shiftDrag, v -> c.shiftDrag = v));
        add(toggle("scroll_over_inventory", c.scrollOverInventory, v -> c.scrollOverInventory = v));
        add(toggle("scroll_over_tiles", c.scrollOverTiles, v -> c.scrollOverTiles = v));
        // Right-click and shift-right-click each pick from the same four actions.
        add(choice("right_click", "tile_click", ClientConfig.TileClick.values(), c.rightClick, v -> c.rightClick = v));
        add(choice("shift_right_click", "tile_click", ClientConfig.TileClick.values(), c.shiftRightClick, v -> c.shiftRightClick = v));
        // Moved here from the main page (Nov 9).
        add(choice("shift_click", ClientConfig.ShiftClick.values(), config.putAway.shiftClick, v -> config.putAway.shiftClick = v));
    }
}
