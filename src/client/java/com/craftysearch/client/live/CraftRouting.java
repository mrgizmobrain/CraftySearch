package com.craftysearch.client.live;

import com.craftysearch.client.background.ClientCrafting;
import com.craftysearch.client.background.InteractionHold;
import com.craftysearch.client.compat.IpnLockedSlots;
import com.craftysearch.client.config.ClientConfig;
import com.craftysearch.client.recipe.CraftChains;
import com.craftysearch.client.recipe.RecipeStorage;
import com.craftysearch.network.CraftRequestPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;

import java.util.List;

/**
 * Recipe book clicks in the inventory and crafting table: a craft request in server mode,
 * otherwise client-only crafting from storage within reach.
 */
public final class CraftRouting {
    private CraftRouting() {
    }

    /** Returns true if the click was handled here, so vanilla's packet should be skipped. */
    public static boolean route(int containerId, RecipeDisplayId recipe, boolean useMaxItems) {
        // Rapid recipe clicks should keep landing on the same recipe, so hold the book's layout.
        InteractionHold.clicked();
        if (!ServerMode.active()) return ClientCrafting.route(containerId, recipe, useMaxItems);

        Minecraft mc = Minecraft.getInstance();
        if (!ServerMode.craftingAllowed() || mc.player == null) return false;
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (!(menu instanceof AbstractCraftingMenu crafting) || menu.containerId != containerId) return false;

        ClientConfig config = ClientConfig.get();
        int flags = 0;
        if (useMaxItems) flags |= CraftRequestPayload.USE_MAX;
        if (config.craftToInventory) flags |= CraftRequestPayload.CRAFT_TO_INVENTORY;
        if (config.ingredientSourceOrder == ClientConfig.IngredientSourceOrder.NEAREST_FIRST) {
            flags |= CraftRequestPayload.NEAREST_FIRST;
        }
        if (config.keepOne) flags |= CraftRequestPayload.KEEP_ONE;
        // Paced like takes: the server handles one craft request per player per tick and drops a second.
        ServerOutbox.send(new CraftRequestPayload(containerId, recipe, flags, ServerMode.radius(), config.kindsMask(),
                intermediates(mc, crafting, recipe, useMaxItems), IpnLockedSlots.mask()));
        return true;
    }

    /**
     * Spec 3.6: the steps to craft first, planned now from the live data; empty for a direct
     * craft. Planned for every click, not only recipes the book shows as needing them: the book
     * holds still while the player clicks, so a recipe whose ingredients just ran out still
     * looked direct and its clicks failed until the hold ended (Nov 9).
     */
    private static List<CraftRequestPayload.Step> intermediates(Minecraft mc, AbstractCraftingMenu menu,
                                                                RecipeDisplayId recipe, boolean useMax) {
        CraftChains.Chain chain = RecipeStorage.planChain(mc, menu, recipe, useMax);
        if (chain == null) return List.of();
        return chain.steps().stream().map(step -> new CraftRequestPayload.Step(step.recipe(), step.times())).toList();
    }
}
