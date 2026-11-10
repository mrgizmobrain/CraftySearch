package com.craftysearch.client.compat;

import com.craftysearch.client.gui.PanelHooks;
import com.craftysearch.client.mixin.AbstractContainerScreenAccessor;
import me.shedaniel.math.Rectangle;
import me.shedaniel.rei.api.client.plugins.REIClientPlugin;
import me.shedaniel.rei.api.client.registry.screen.DisplayBoundsProvider;
import me.shedaniel.rei.api.client.registry.screen.ExclusionZones;
import me.shedaniel.rei.api.client.registry.screen.ScreenRegistry;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;

import java.util.Collection;
import java.util.List;

/**
 * REI (entrypoint {@code rei_client}, loaded only when REI is). Its entry list goes right of
 * the inventory, where the storage panel is (Nov 8): the screen's bounds include the panel,
 * so the list starts at the panel's right edge, and the panel is an exclusion zone as well.
 */
public final class ReiIntegration implements REIClientPlugin {
    /** Above REI's own bounds for container screens, which have -10. */
    private static final double PRIORITY = 10;

    @Override
    public void registerExclusionZones(ExclusionZones zones) {
        // The screens the panel attaches to.
        zones.register(InventoryScreen.class, ReiIntegration::panel);
        zones.register(CraftingScreen.class, ReiIntegration::panel);
    }

    @Override
    public void registerScreens(ScreenRegistry registry) {
        registry.registerDecider(new WithPanel());
    }

    private static Collection<Rectangle> panel(Screen screen) {
        ScreenRectangle area = PanelHooks.panelArea(screen);
        return area == null ? List.of() : List.of(new Rectangle(area.left(), area.top(), area.width(), area.height()));
    }

    /** The inventory and, while it shows, the storage panel beside it. */
    private static final class WithPanel implements DisplayBoundsProvider<AbstractContainerScreen<?>> {
        @Override
        public <R extends Screen> boolean isHandingScreen(Class<R> screen) {
            return InventoryScreen.class.isAssignableFrom(screen) || CraftingScreen.class.isAssignableFrom(screen);
        }

        @Override
        public Rectangle getScreenBounds(AbstractContainerScreen<?> screen) {
            AbstractContainerScreenAccessor container = (AbstractContainerScreenAccessor) screen;
            Rectangle bounds = new Rectangle(container.craftysearch$getLeftPos(), container.craftysearch$getTopPos(),
                    container.craftysearch$getImageWidth(), container.craftysearch$getImageHeight());
            ScreenRectangle area = PanelHooks.panelArea(screen);
            return area == null ? bounds : bounds.union(new Rectangle(area.left(), area.top(), area.width(), area.height()));
        }

        @Override
        public double getPriority() {
            return PRIORITY;
        }
    }
}
