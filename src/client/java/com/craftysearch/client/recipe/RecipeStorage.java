package com.craftysearch.client.recipe;

import com.craftysearch.CraftySearch;
import com.craftysearch.client.config.ClientConfig;
import com.craftysearch.client.live.ServerMode;
import com.craftysearch.client.memory.MemoryEntry;
import com.craftysearch.client.mixin.RecipeCollectionAccessor;
import com.craftysearch.client.storage.StorageIndex;
import com.craftysearch.storage.ItemKey;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.RecipeBookMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Counts remembered storage toward recipe book craftability (spec 3.2). The recipe book's
 * own contents get every container within the radius, so vanilla's craftable state and
 * "craftable only" filter include storage. A second set holds only the containers within
 * reach; recipes craftable with the first but not the second are "out of reach". Recipes
 * that need intermediates crafted first (spec 3.6) are marked craftable here too.
 */
public final class RecipeStorage {
    /** Extra block reach with "Extra block reach" on: all the server allows in its use-item-on check. */
    private static final double EXTRA_BLOCK_REACH = 1.0;
    /** Extra entity reach with "Extra cart reach" on; the server's own allowance is 3. */
    private static final double EXTRA_CART_REACH = 2.5;

    /** Inventory, grid and reachable containers, or null when every counted container is reachable. */
    private static @Nullable StackedItemContents reachable;
    private static final Set<RecipeDisplayId> REACHABLE_CRAFTABLE = new HashSet<>();
    /** Intermediates planned from everything counted, and from what's within reach when that's less. */
    private static CraftChains.@Nullable Planner chains;
    private static CraftChains.@Nullable Planner reachChains;
    /** Recipes craftable only via intermediates, with the chain for one craft. */
    private static final Map<RecipeDisplayId, CraftChains.Chain> VIA = new HashMap<>();
    private static long chainNanos;
    private static int chainChecks;

    private RecipeStorage() {
    }

    /** Clears the out-of-reach and intermediates state, for recipe books that don't count storage (furnaces). */
    public static void reset() {
        reachable = null;
        REACHABLE_CRAFTABLE.clear();
        chains = null;
        reachChains = null;
        VIA.clear();
    }

    /** Called after vanilla filled {@code contents} from the inventory and grid, before recipes are selected. */
    public static void addStorage(Minecraft mc, RecipeBookMenu menu, StackedItemContents contents) {
        if (chainChecks > 0) {
            CraftySearch.LOGGER.debug("Checked {} recipes for intermediates in {} ms", chainChecks, chainNanos / 1_000_000.0);
        }
        chainNanos = 0;
        chainChecks = 0;
        reset();
        Player player = mc.player;
        if (player == null) return;
        // With "Client-only mode" off nothing can be crafted from storage, so the book counts as vanilla does.
        if (!ServerMode.active() && !ClientConfig.get().actsClientOnly()) return;
        List<MemoryEntry> entries = StorageIndex.nearbyEntries(mc);
        List<MemoryEntry> inReach = entries.stream().filter(e -> isReachable(player, e)).toList();
        for (MemoryEntry entry : entries) account(contents, entry);
        // In server mode the server crafts from every container in the radius, so nothing is out of reach.
        boolean splitReach = !ServerMode.active() && inReach.size() != entries.size();

        if (ClientConfig.get().craftIntermediates && menu instanceof AbstractCraftingMenu crafting) {
            CraftChains.Index index = CraftChains.index(mc, crafting.getGridWidth(), crafting.getGridHeight());
            chains = CraftChains.planner(index, pool(player, crafting, entries));
            if (splitReach) reachChains = CraftChains.planner(index, pool(player, crafting, inReach));
        }
        if (!splitReach) return;

        StackedItemContents reach = new StackedItemContents();
        player.getInventory().fillStackedContents(reach);
        menu.fillCraftSlotsStackedContents(reach);
        for (MemoryEntry entry : inReach) account(reach, entry);
        reachable = reach;
    }

    /**
     * Called for each collection after vanilla marked its craftable recipes. Recipes the grid
     * can show that intermediates would make craftable are marked craftable too, so they
     * count for "show craftable only".
     */
    public static void collectionSelected(RecipeCollection collection) {
        CraftChains.Planner planner = chains;
        if (planner != null) {
            long start = System.nanoTime();
            Set<RecipeDisplayId> craftable = ((RecipeCollectionAccessor) collection).craftysearch$getCraftable();
            for (RecipeDisplayEntry entry : collection.getSelectedRecipes(RecipeCollection.CraftableStatus.NOT_CRAFTABLE)) {
                chainChecks++;
                CraftChains.Chain chain = planner.plan(entry.id(), 1);
                if (chain == null || chain.steps().isEmpty()) continue;
                craftable.add(entry.id());
                VIA.put(entry.id(), chain);
            }
            chainNanos += System.nanoTime() - start;
        }

        StackedItemContents reach = reachable;
        if (reach == null) return;
        for (RecipeDisplayEntry entry : collection.getRecipes()) {
            if (!collection.isCraftable(entry.id())) continue;
            boolean inReach = VIA.containsKey(entry.id())
                    ? reachChains != null && reachChains.plan(entry.id(), 1) != null
                    : entry.canCraft(reach);
            if (inReach) REACHABLE_CRAFTABLE.add(entry.id());
        }
    }

    /** True when the collection is craftable only with ingredients from containers out of reach. */
    public static boolean isOutOfReach(RecipeCollection collection) {
        if (reachable == null || !collection.hasCraftable()) return false;
        for (RecipeDisplayEntry entry : collection.getRecipes()) {
            if (collection.isCraftable(entry.id()) && REACHABLE_CRAFTABLE.contains(entry.id())) return false;
        }
        return true;
    }

    /** True when every craftable recipe in the collection needs intermediates crafted first. */
    public static boolean isViaIntermediates(RecipeCollection collection) {
        if (VIA.isEmpty() || !collection.hasCraftable()) return false;
        for (RecipeDisplayEntry entry : collection.getRecipes()) {
            if (collection.isCraftable(entry.id()) && !VIA.containsKey(entry.id())) return false;
        }
        return true;
    }

    /** The intermediates one craft of {@code recipe} needs, if it's craftable only via intermediates. */
    public static CraftChains.@Nullable Chain intermediatesFor(RecipeDisplayId recipe) {
        return VIA.get(recipe);
    }

    /**
     * A fresh chain for a click, from everything within the radius (server mode), or null.
     * Shift-click plans as many as possible.
     */
    public static CraftChains.@Nullable Chain planChain(Minecraft mc, AbstractCraftingMenu menu, RecipeDisplayId recipe, boolean useMax) {
        if (!ClientConfig.get().craftIntermediates || mc.player == null || mc.level == null) return null;
        CraftChains.Index index = CraftChains.index(mc, menu.getGridWidth(), menu.getGridHeight());
        CraftChains.Planner planner = CraftChains.planner(index, pool(mc.player, menu, StorageIndex.nearbyEntries(mc)));
        return useMax ? planner.planMost(recipe) : planner.plan(recipe, 1);
    }

    /** Usable item counts in the inventory, the crafting grid and {@code entries}, as vanilla counts ingredients. */
    public static Map<Item, Integer> pool(Player player, AbstractCraftingMenu menu, List<MemoryEntry> entries) {
        Map<Item, Integer> pool = new HashMap<>();
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) addUsable(pool, stack, stack.getCount());
        for (Slot slot : menu.getInputGridSlots()) addUsable(pool, slot.getItem(), slot.getItem().getCount());
        for (MemoryEntry entry : entries) {
            for (Object2IntMap.Entry<ItemKey> e : entry.takeableCounts().object2IntEntrySet()) addUsable(pool, e.getKey().stack(), e.getIntValue());
        }
        return pool;
    }

    private static void addUsable(Map<Item, Integer> pool, ItemStack stack, int count) {
        // Vanilla never uses enchanted, damaged or renamed items as ingredients.
        if (!stack.isEmpty() && Inventory.isUsableForCrafting(stack)) pool.merge(stack.getItem(), count, Integer::sum);
    }

    /**
     * Whether the player can open the container: either half of a block within
     * {@link #canReach(Player, BlockPos)}, or a cart or boat loaded on the client and within
     * {@link #canReach(Player, Entity)}.
     */
    public static boolean isReachable(Player player, MemoryEntry entry) {
        if (entry.isEntity()) {
            Entity cart = entry.findEntity(player.level());
            return cart != null && canReach(player, cart);
        }
        return canReach(player, entry.pos()) || (entry.other() != null && canReach(player, entry.other()));
    }

    /**
     * A block within the block interaction range a crosshair has. The server allows 1 block
     * more; with {@code clientOnly.extraBlockReach} on, all of it is used. Anti-cheat plugins
     * may check closer than the server does.
     */
    public static boolean canReach(Player player, BlockPos pos) {
        double extra = ClientConfig.get().clientOnly.reachesFurtherForBlocks() ? EXTRA_BLOCK_REACH : 0.0;
        return player.isWithinBlockInteractionRange(pos, extra);
    }

    /**
     * A cart or boat within the entity interaction range a crosshair has. The server allows
     * 3 blocks more, but anti-cheat plugins check much closer to vanilla's range; with
     * {@code clientOnly.extraCartReach} on, most of that extra is used, leaving half a
     * block for movement and lag.
     */
    public static boolean canReach(Player player, Entity cart) {
        double extra = ClientConfig.get().clientOnly.reachesFurtherForCarts() ? EXTRA_CART_REACH : 0.0;
        return !cart.isRemoved() && player.isWithinEntityInteractionRange(cart.getBoundingBox(), extra);
    }

    private static void account(StackedItemContents contents, MemoryEntry entry) {
        // With "Leave one" on, the one each container keeps isn't an ingredient.
        for (Object2IntMap.Entry<ItemKey> e : entry.takeableCounts().object2IntEntrySet()) {
            ItemStack stack = e.getKey().stack();
            // Same rule as vanilla's inventory: enchanted, damaged and renamed items aren't ingredients.
            if (!Inventory.isUsableForCrafting(stack)) continue;
            // The default accountStack caps at the max stack size; a container entry holds the whole total.
            contents.accountStack(stack.copyWithCount(e.getIntValue()), Integer.MAX_VALUE);
        }
    }
}
