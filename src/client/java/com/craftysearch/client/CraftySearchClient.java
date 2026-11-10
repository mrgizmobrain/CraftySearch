package com.craftysearch.client;

import com.craftysearch.CraftySearch;
import com.craftysearch.client.background.BackgroundJobs;
import com.craftysearch.client.config.ClientConfig;
import com.craftysearch.client.gui.PanelHooks;
import com.craftysearch.client.gui.StorageScreen;
import com.craftysearch.client.highlight.HighlightMarkers;
import com.craftysearch.client.highlight.Highlights;
import com.craftysearch.client.highlight.SlotHighlights;
import com.craftysearch.client.live.ServerMode;
import com.craftysearch.client.memory.ContainerMemory;
import com.craftysearch.client.memory.ContainerRecorder;
import com.craftysearch.client.recipe.CraftChains;
import com.craftysearch.client.recipe.RecipeStorage;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Client entrypoint. Storage panel, recipe book integration and the
 * client-only crafting fallback live under this package.
 */
public class CraftySearchClient implements ClientModInitializer {
    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(CraftySearch.MOD_ID, "main"));

    public static final KeyMapping OPEN_STORAGE = new KeyMapping(
            "key.craftysearch.open_storage", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, CATEGORY);

    @Override
    public void onInitializeClient() {
        ClientConfig.load();
        KeyMappingHelper.registerKeyMapping(OPEN_STORAGE);
        ContainerRecorder.register();
        PanelHooks.register();
        SlotHighlights.register();
        HighlightMarkers.register();
        ServerMode.register();
        BackgroundJobs.register();

        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> ContainerMemory.get().open(mc, handler.registryAccess()));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
            ContainerMemory.get().close();
            ServerMode.reset();
            BackgroundJobs.reset();
            Highlights.clear();
            // Both hold on to the world through their recipe index; let it be freed.
            RecipeStorage.reset();
            CraftChains.clearCache();
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> ContainerMemory.get().close());
        ClientTickEvents.END_CLIENT_TICK.register(CraftySearchClient::tick);

        CraftySearch.LOGGER.info("Crafty Search loaded (client)");
    }

    private static void tick(Minecraft mc) {
        while (OPEN_STORAGE.consumeClick()) {
            if (mc.gui.screen() == null && mc.player != null) mc.gui.setScreen(new StorageScreen());
        }
        ContainerMemory.get().tick(mc);
        Highlights.tick(mc);
    }
}
