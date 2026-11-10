package com.craftysearch.client.gui;

import com.craftysearch.client.config.ClientConfig;
import com.craftysearch.client.config.ClientConfig.IngredientSourceOrder;
import com.craftysearch.client.live.ServerMode;
import com.craftysearch.storage.PutAwayRule;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.function.IntConsumer;

/** In-game editor for {@code craftysearch-client.json}: the main page. */
public final class ConfigScreen extends SettingsScreen {
    public ConfigScreen(Screen parent) {
        super(parent, "title", null);
    }

    @Override
    protected void addSettings(ClientConfig c) {
        add(new IntSlider("craftysearch.config.radius", ClientConfig.MIN_RADIUS, ClientConfig.MAX_RADIUS, c.radius, v -> c.radius = v));
        add(new IntSlider("craftysearch.config.highlight_duration", 1, 60, c.highlightDurationSeconds,
                v -> c.highlightDurationSeconds = v));
        add(toggle("panel_in_inventory", c.showPanelInInventory, v -> c.showPanelInInventory = v));
        add(toggle("panel_in_crafting_table", c.showPanelInCraftingTable, v -> c.showPanelInCraftingTable = v));
        add(choice("ingredient_order", "order", IngredientSourceOrder.values(), c.ingredientSourceOrder, v -> c.ingredientSourceOrder = v));
        add(toggle("craft_to_inventory", c.craftToInventory, v -> c.craftToInventory = v));
        // "Store with" is on the Mouse page (Nov 9) and "Your inventory" on the Containers page (Nov 7).
        add(choice("put_away", PutAwayRule.Fallback.values(), c.putAway.fallback, v -> c.putAway.fallback = v));
        add(toggle("craft_intermediates", c.craftIntermediates, v -> c.craftIntermediates = v));
        add(toggle("remember_search", c.rememberSearch, v -> c.rememberSearch = v));
        add(choice("markers", ClientConfig.Markers.values(), c.highlightMarkers, v -> c.highlightMarkers = v));
        add(toggle("keep_one", c.keepOne, v -> c.keepOne = v));
        add(toggle("only_takeable", c.onlyTakeable, v -> c.onlyTakeable = v));

        // More settings on their own pages, so every row fits at large GUI scales.
        gap(6);
        add(page("containers_button", ContainersConfigScreen::new));
        add(page("client_only_button", ClientOnlyConfigScreen::new));
        add(page("mouse_button", MouseConfigScreen::new));
    }

    @Override
    protected void saved() {
        // The radius and container kinds may have changed.
        ServerMode.subscribe();
    }

    private static final class IntSlider extends AbstractSliderButton {
        private final String key;
        private final int min;
        private final int max;
        private final IntConsumer onChange;

        IntSlider(String key, int min, int max, int value, IntConsumer onChange) {
            super(0, 0, COLUMN_WIDTH, 20, Component.empty(), (double) (value - min) / (max - min));
            this.key = key;
            this.min = min;
            this.max = max;
            this.onChange = onChange;
            updateMessage();
        }

        private int intValue() {
            return Mth.clamp((int) Math.round(min + value * (max - min)), min, max);
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable(key, intValue()));
        }

        @Override
        protected void applyValue() {
            onChange.accept(intValue());
        }
    }
}
