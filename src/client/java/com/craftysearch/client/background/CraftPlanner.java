package com.craftysearch.client.background;

import com.craftysearch.client.config.ClientConfig;
import com.craftysearch.client.memory.MemoryEntry;
import com.craftysearch.client.recipe.CraftChains;
import com.craftysearch.client.recipe.RecipeStorage;
import com.craftysearch.client.storage.StorageIndex;
import com.craftysearch.storage.ItemKey;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.StackedContents;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Works out what to pull from containers within reach for one recipe click, by the same
 * rules the server uses in server mode. Container counts come from memory, so the session
 * rechecks them once each container is open.
 */
final class CraftPlanner {
    private static final StackedContents.Output<Holder<Item>> IGNORE = item -> {
    };

    /**
     * {@code containers} are those within reach that may hold something in {@code toPull},
     * nearest first. {@code steps} are intermediates to craft first (spec 3.6), or none.
     * {@code sets}: sets of the clicked recipe to add to the grid, more than one for repeated
     * clicks (Nov 9); {@code perRound}: how many sets the grid holds at once.
     */
    record Plan(int amount, List<MemoryEntry> containers, Map<Item, Integer> toPull, List<CraftChains.Step> steps,
                int sets, int perRound) {
        boolean needsStorage() {
            return !toPull.isEmpty();
        }
    }

    /** Either a plan, or the reason (a message key under craftysearch.craft) there isn't one. */
    record Result(@Nullable Plan plan, @Nullable String error) {
    }

    private CraftPlanner() {
    }

    /**
     * {@code clicks}: plain clicks on this recipe, more than one when repeated clicks queued
     * behind a running job come as one (Nov 9). With {@code craftToInventory} those are
     * crafted round by round, so they may add up to more than the grid holds at once.
     */
    static Result plan(Minecraft mc, AbstractCraftingMenu menu, RecipeDisplayId recipe, List<Ingredient> requirements,
                       boolean useMax, boolean nearestFirst, int clicks, boolean craftToInventory) {
        LocalPlayer player = mc.player;
        Map<Item, Integer> local = new LinkedHashMap<>();
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) addUsable(local, stack);
        for (Slot slot : menu.getInputGridSlots()) addUsable(local, slot.getItem());

        List<MemoryEntry> nearby = StorageIndex.nearbyEntries(mc);
        List<MemoryEntry> reachable = nearby.stream().filter(e -> RecipeStorage.isReachable(player, e)).toList();
        Map<Item, Integer> inReach = storageCounts(reachable);
        StackedContents<Holder<Item>> all = contents(local, inReach);

        // The same amount vanilla would place: as many as possible on shift-click, otherwise
        // one more set than the grid already holds (one per click).
        int inGrid = setsInGrid(menu, requirements);
        boolean batch = !useMax && clicks > 1;
        int amount = useMax ? all.tryPickAll(requirements, Integer.MAX_VALUE, IGNORE) : clicks + inGrid;
        // As many of the clicks as there's enough for.
        if (batch) amount = all.tryPickAll(requirements, amount, IGNORE);
        int least = useMax ? 1 : inGrid + 1;
        if (batch && amount >= least && amount < clicks + inGrid) {
            // Some of the clicks are short: intermediates may make up the rest, so crafting carries on
            // through the moment the recipe stops being craftable without them (Nov 9).
            Result chain = planChain(mc, menu, recipe, false, clicks, nearestFirst, local, inReach, reachable, nearby);
            if (chain != null && chain.plan() != null && chain.plan().amount() > amount - inGrid) return chain;
        }
        List<Holder<Item>> chosen = new ArrayList<>();
        if (amount < least || !all.tryPick(requirements, amount, chosen::add)) {
            Result chain = planChain(mc, menu, recipe, useMax, batch ? clicks : 1, nearestFirst, local, inReach, reachable, nearby);
            if (chain != null) return chain;
            // Tell "it's in storage, just too far" apart from "it isn't anywhere".
            boolean withAll = contents(local, storageCounts(nearby)).tryPick(requirements, 1, IGNORE);
            return new Result(null, withAll ? "out_of_reach" : "missing");
        }
        int perRound = clampToMaxStackSize(Integer.MAX_VALUE, chosen);
        // The grid holds no more than a stack per slot; crafting repeated clicks into the inventory goes round by round instead.
        if (amount > perRound && !(batch && craftToInventory)) {
            amount = perRound;
            chosen.clear();
            if (!all.tryPick(requirements, amount, chosen::add)) return new Result(null, "missing");
        }

        // On shift-click or repeated clicks, craft fewer if the ingredients wouldn't fit in the inventory.
        int lowest = Math.min(amount, useMax || batch ? least : amount);
        for (int n = amount; n >= lowest; n--) {
            if (n != amount) {
                chosen.clear();
                if (!all.tryPick(requirements, n, chosen::add)) continue;
            }
            Map<Item, Integer> needed = new LinkedHashMap<>();
            for (Holder<Item> item : chosen) needed.merge(item.value(), n, Integer::sum);
            Map<Item, Integer> toPull = toPull(needed, local, inReach, nearestFirst);
            if (fits(player.getInventory(), toPull)) {
                List<MemoryEntry> containers = reachable.stream().filter(e -> holdsAny(e, toPull)).toList();
                return new Result(new Plan(n, containers, toPull, List.of(), Math.max(1, n - inGrid), perRound), null);
            }
        }
        return new Result(null, "no_space");
    }

    /**
     * Spec 3.6: a plan that crafts intermediates first, from the inventory, the grid and
     * containers within reach. Null when there's no chain at all, so the caller reports why
     * the direct craft can't happen; "out_of_reach" when only containers out of reach would do.
     */
    private static @Nullable Result planChain(Minecraft mc, AbstractCraftingMenu menu, RecipeDisplayId recipe, boolean useMax,
                                              int clicks, boolean nearestFirst, Map<Item, Integer> local, Map<Item, Integer> inReach,
                                              List<MemoryEntry> reachable, List<MemoryEntry> nearby) {
        if (!ClientConfig.get().craftIntermediates) return null;
        CraftChains.Index index = CraftChains.index(mc, menu.getGridWidth(), menu.getGridHeight());
        CraftChains.Planner planner = CraftChains.planner(index, CraftChains.merge(local, inReach));
        CraftChains.Chain chain = useMax ? planner.planMost(recipe)
                : clicks > 1 ? planner.planUpTo(recipe, clicks) : planner.plan(recipe, 1);
        if (chain == null || chain.steps().isEmpty()) {
            boolean withAll = CraftChains.planner(index, CraftChains.merge(local, storageCounts(nearby))).plan(recipe, 1) != null;
            return withAll ? new Result(null, "out_of_reach") : null;
        }
        // The chain says how much of each starting item it uses; the inventory's own come first, as for direct crafts.
        Map<Item, Integer> toPull = toPull(chain.used(), local, inReach, nearestFirst);
        if (!fits(mc.player.getInventory(), toPull)) return new Result(null, "no_space");
        List<MemoryEntry> containers = reachable.stream().filter(e -> holdsAny(e, toPull)).toList();
        return new Result(new Plan(chain.amount(), containers, toPull, chain.steps(), chain.amount(), planner.perRound(recipe)), null);
    }

    private static Map<Item, Integer> toPull(Map<Item, Integer> needed, Map<Item, Integer> local,
                                             Map<Item, Integer> inReach, boolean nearestFirst) {
        Map<Item, Integer> toPull = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> e : needed.entrySet()) {
            int available = inReach.getOrDefault(e.getKey(), 0);
            // Nearest first takes everything storage has and leaves the inventory for the rest.
            int pull = nearestFirst ? e.getValue() : e.getValue() - local.getOrDefault(e.getKey(), 0);
            pull = Math.min(pull, available);
            if (pull > 0) toPull.put(e.getKey(), pull);
        }
        return toPull;
    }

    /**
     * Rough check that the pulled stacks get a slot each. Shift-clicking never drops
     * anything (what doesn't fit stays in the container), so this only avoids starting a
     * craft that can't finish.
     */
    private static boolean fits(Inventory inventory, Map<Item, Integer> toPull) {
        int free = 0;
        for (ItemStack stack : inventory.getNonEquipmentItems()) {
            if (stack.isEmpty()) free++;
        }
        int needed = 0;
        for (Map.Entry<Item, Integer> e : toPull.entrySet()) {
            int maxStack = e.getKey().getDefaultMaxStackSize();
            needed += (e.getValue() + maxStack - 1) / maxStack;
        }
        return needed <= free;
    }

    /** How many full sets of the recipe the grid holds: each filled slot fits an ingredient. */
    private static int setsInGrid(AbstractCraftingMenu menu, List<Ingredient> requirements) {
        int filled = 0;
        int min = Integer.MAX_VALUE;
        for (Slot slot : menu.getInputGridSlots()) {
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            filled++;
            if (requirements.stream().noneMatch(i -> i.acceptsItem(stack.typeHolder()))) return 0;
            min = Math.min(min, stack.getCount());
        }
        return filled == requirements.size() && filled > 0 ? min : 0;
    }

    /** Vanilla's clamp: no grid slot may hold more than its item's max stack size. */
    private static int clampToMaxStackSize(int amount, List<Holder<Item>> items) {
        for (Holder<Item> item : items) {
            amount = Math.min(amount, item.components().getOrDefault(DataComponents.MAX_STACK_SIZE, 1));
        }
        return amount;
    }

    private static boolean holdsAny(MemoryEntry entry, Map<Item, Integer> items) {
        for (ItemKey key : entry.takeableCounts().keySet()) {
            if (items.containsKey(key.stack().getItem()) && Inventory.isUsableForCrafting(key.stack())) return true;
        }
        return false;
    }

    private static void addUsable(Map<Item, Integer> counts, ItemStack stack) {
        // Vanilla never uses enchanted, damaged or renamed items as ingredients.
        if (!stack.isEmpty() && Inventory.isUsableForCrafting(stack)) counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
    }

    private static Map<Item, Integer> storageCounts(List<MemoryEntry> entries) {
        Map<Item, Integer> counts = new LinkedHashMap<>();
        for (MemoryEntry entry : entries) {
            for (Object2IntMap.Entry<ItemKey> e : entry.takeableCounts().object2IntEntrySet()) {
                ItemStack stack = e.getKey().stack();
                if (Inventory.isUsableForCrafting(stack)) counts.merge(stack.getItem(), e.getIntValue(), Integer::sum);
            }
        }
        return counts;
    }

    /** StackedContents keys by holder identity, so always use the registry's holder for the item. */
    private static StackedContents<Holder<Item>> contents(Map<Item, Integer> a, Map<Item, Integer> b) {
        StackedContents<Holder<Item>> contents = new StackedContents<>();
        a.forEach((item, count) -> contents.account(BuiltInRegistries.ITEM.wrapAsHolder(item), count));
        b.forEach((item, count) -> contents.account(BuiltInRegistries.ITEM.wrapAsHolder(item), count));
        return contents;
    }
}
