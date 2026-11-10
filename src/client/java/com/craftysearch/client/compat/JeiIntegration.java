package com.craftysearch.client.compat;

import com.craftysearch.CraftySearch;
import com.craftysearch.client.gui.PanelHooks;
import com.craftysearch.client.mixin.AbstractContainerScreenAccessor;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.handlers.IGlobalGuiHandler;
import mezz.jei.api.gui.handlers.IGuiProperties;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.resources.Identifier;

import java.util.Collection;
import java.util.List;

/**
 * JEI (entrypoint {@code jei_mod_plugin}, loaded only when JEI is). Its ingredient list goes
 * right of the inventory, where the storage panel is (Nov 8): the inventory's GUI area
 * includes the panel, so the list starts at the panel's right edge, and the panel is an
 * extra area as well. JEI picks the screen handler registered closest to the screen's class,
 * so these come before its own for container screens.
 */
@JeiPlugin
public final class JeiIntegration implements IModPlugin {
    private static final Identifier ID = Identifier.fromNamespaceAndPath(CraftySearch.MOD_ID, "storage_panel");

    /** JEI compares these field by field to notice the screen moving. */
    private record Properties(Class<? extends Screen> screenClass, int guiLeft, int guiTop, int guiXSize, int guiYSize,
                              int screenWidth, int screenHeight) implements IGuiProperties {
    }

    @Override
    public Identifier getPluginUid() {
        return ID;
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registration.addGuiScreenHandler(InventoryScreen.class, JeiIntegration::withPanel);
        registration.addGuiScreenHandler(CraftingScreen.class, JeiIntegration::withPanel);
        // Asked for whatever screen JEI shows on; the panel only exists on the inventory and crafting table.
        registration.addGlobalGuiHandler(new IGlobalGuiHandler() {
            @Override
            public Collection<Rect2i> getGuiExtraAreas() {
                ScreenRectangle area = PanelHooks.panelArea(Minecraft.getInstance().gui.screen());
                return area == null ? List.of() : List.of(new Rect2i(area.left(), area.top(), area.width(), area.height()));
            }
        });
    }

    /** The inventory and, while it shows, the storage panel beside it. */
    private static IGuiProperties withPanel(AbstractContainerScreen<?> screen) {
        AbstractContainerScreenAccessor container = (AbstractContainerScreenAccessor) screen;
        int left = container.craftysearch$getLeftPos();
        int top = container.craftysearch$getTopPos();
        int right = left + container.craftysearch$getImageWidth();
        int bottom = top + container.craftysearch$getImageHeight();
        ScreenRectangle area = PanelHooks.panelArea(screen);
        if (area != null) {
            left = Math.min(left, area.left());
            top = Math.min(top, area.top());
            right = Math.max(right, area.right());
            bottom = Math.max(bottom, area.bottom());
        }
        return new Properties(screen.getClass(), left, top, right - left, bottom - top, screen.width, screen.height);
    }
}
