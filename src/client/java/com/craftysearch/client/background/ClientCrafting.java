package com.craftysearch.client.background;

import com.craftysearch.client.config.ClientConfig;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import org.jspecify.annotations.Nullable;

/**
 * Client-only crafting from storage (spec 4.2): plans a recipe click against containers
 * within reach and runs it as a {@link CraftJob}. Clicks made while a job runs are queued
 * and planned when their turn comes.
 */
public final class ClientCrafting {
    private ClientCrafting() {
    }

    /**
     * A recipe book click in client-only mode. Returns true if it's handled here, so
     * vanilla's place-recipe packet is skipped; false leaves the click to vanilla, which
     * also shows the ghost recipe when the ingredients aren't there.
     */
    public static boolean route(int containerId, RecipeDisplayId recipeId, boolean useMax) {
        Minecraft mc = Minecraft.getInstance();
        // "All interactions" off: the recipe book is vanilla's.
        if (!ClientConfig.get().actsClientOnly()) return false;
        // Behind a running job, the open menu may be a hidden container: queue it and plan it later.
        if (BackgroundJobs.isBusy()) {
            BackgroundJobs.submit(mc, new QueuedCraft(recipeId, useMax));
            return true;
        }
        LocalPlayer player = mc.player;
        if (player == null || !(player.containerMenu instanceof AbstractCraftingMenu menu) || menu.containerId != containerId) {
            return false;
        }
        RecipeDisplayEntry entry = findEntry(player.getRecipeBook(), recipeId);
        if (entry == null || entry.craftingRequirements().isEmpty()) return false;

        ClientConfig config = ClientConfig.get();
        CraftPlanner.Result result = plan(mc, menu, entry, useMax, 1);
        CraftPlanner.Plan plan = result.plan();
        if (plan == null) {
            // "missing" is the normal case of clicking a recipe to see its ghost; no message for that.
            if (result.error() != null && !"missing".equals(result.error())) message(player, result.error());
            return false;
        }
        // Vanilla can do this one itself, unless there are intermediates to craft first.
        if (!plan.needsStorage() && plan.steps().isEmpty() && !config.craftToInventory) return false;
        return BackgroundJobs.start(mc, new CraftJob(plan, recipeId, useMax, config.craftToInventory))
                != BackgroundJobs.Start.NOT_APPLICABLE;
    }

    /**
     * A queued click. Plain clicks on the same recipe in a row are one request (Nov 9), so
     * rapid clicks behind a running job become one job: each container opened once for all
     * of their ingredients, and the crafts done a grid-full at a time.
     */
    private static final class QueuedCraft implements BackgroundJobs.Request {
        private final RecipeDisplayId recipeId;
        private final boolean useMax;
        private int clicks = 1;

        QueuedCraft(RecipeDisplayId recipeId, boolean useMax) {
            this.recipeId = recipeId;
            this.useMax = useMax;
        }

        @Override
        public @Nullable BackgroundJob create(Minecraft mc) {
            return queued(mc, recipeId, useMax, clicks);
        }

        @Override
        public boolean absorb(BackgroundJobs.Request next) {
            if (!(next instanceof QueuedCraft other) || useMax || other.useMax || !other.recipeId.equals(recipeId)) return false;
            clicks += other.clicks;
            return true;
        }
    }

    /** A queued click (or several on one recipe), planned now against whatever earlier jobs left behind. */
    private static @Nullable BackgroundJob queued(Minecraft mc, RecipeDisplayId recipeId, boolean useMax, int clicks) {
        LocalPlayer player = mc.player;
        if (player == null || !(player.containerMenu instanceof AbstractCraftingMenu menu)) return null;
        RecipeDisplayEntry entry = findEntry(player.getRecipeBook(), recipeId);
        if (entry == null || entry.craftingRequirements().isEmpty()) return null;
        ClientConfig config = ClientConfig.get();
        CraftPlanner.Result result = plan(mc, menu, entry, useMax, clicks);
        CraftPlanner.Plan plan = result.plan();
        if (plan == null) {
            message(player, result.error());
            return null;
        }
        if (!plan.needsStorage() && plan.steps().isEmpty() && !config.craftToInventory) {
            // What vanilla would have done with the clicks.
            for (int i = 0; i < Math.min(clicks, Math.max(1, plan.perRound())); i++) {
                mc.getConnection().send(new ServerboundPlaceRecipePacket(menu.containerId, recipeId, useMax));
            }
            return null;
        }
        return new CraftJob(plan, recipeId, useMax, config.craftToInventory);
    }

    private static CraftPlanner.Result plan(Minecraft mc, AbstractCraftingMenu menu, RecipeDisplayEntry entry, boolean useMax,
                                            int clicks) {
        ClientConfig config = ClientConfig.get();
        boolean nearestFirst = config.ingredientSourceOrder == ClientConfig.IngredientSourceOrder.NEAREST_FIRST;
        return CraftPlanner.plan(mc, menu, entry.id(), entry.craftingRequirements().get(), useMax, nearestFirst, clicks,
                config.craftToInventory);
    }

    private static @Nullable RecipeDisplayEntry findEntry(ClientRecipeBook book, RecipeDisplayId id) {
        for (RecipeCollection collection : book.getCollections()) {
            for (RecipeDisplayEntry entry : collection.getRecipes()) {
                if (entry.id().equals(id)) return entry;
            }
        }
        return null;
    }

    private static void message(LocalPlayer player, @Nullable String error) {
        if (error != null) player.sendOverlayMessage(Component.translatable("craftysearch.craft." + error));
    }
}
