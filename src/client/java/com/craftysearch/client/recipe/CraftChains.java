package com.craftysearch.client.recipe;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Multi-step crafting (spec 3.6): finds intermediate crafts that make a recipe craftable
 * from what's there, using only the player's unlocked crafting recipes that fit the grid.
 * For each ingredient, what's there is used first and the rest is crafted, so each
 * intermediate is crafted exactly as often as needed.
 */
public final class CraftChains {
    /** Levels of intermediates below the clicked recipe: logs → planks → sticks is two. */
    static final int MAX_DEPTH = 4;
    public static final int MAX_STEPS = 12;
    /** A search gives up after this many recipe tries, so an odd set of recipes can't stall a frame. */
    private static final int MAX_TRIES = 1500;

    private static @Nullable Index cached;

    private CraftChains() {
    }

    /** One intermediate: craft {@code recipe} {@code times} times, at most {@code perRound} sets in the grid at once. */
    public record Step(RecipeDisplayId recipe, int times, int perRound, ItemStack result) {
        /** How many items it makes in all. */
        public int produced() {
            return times * result.getCount();
        }
    }

    /**
     * The intermediates in crafting order, how many of the clicked recipe to craft, and how
     * much of each item that was there at the start everything uses.
     */
    public record Chain(List<Step> steps, int amount, Map<Item, Integer> used) {
    }

    /**
     * An unlocked crafting recipe as the planner sees it: what it needs per craft, and what it
     * makes. Slots that take the same items are one need, so a pickaxe's three iron ingot
     * slots need 3 ingots, all of which one iron block provides.
     */
    private record Option(RecipeDisplayId id, List<Need> needs, ItemStack result, int perRound) {
    }

    /** {@code perCraft} of any item in {@code accepted}. */
    private record Need(List<Item> accepted, int perCraft) {
    }

    private static List<Need> needs(List<List<Item>> slots) {
        List<List<Item>> accepted = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        for (List<Item> slot : slots) {
            int i = accepted.indexOf(slot);
            if (i < 0) {
                accepted.add(slot);
                counts.add(1);
            } else {
                counts.set(i, counts.get(i) + 1);
            }
        }
        List<Need> needs = new ArrayList<>(accepted.size());
        for (int i = 0; i < accepted.size(); i++) needs.add(new Need(accepted.get(i), counts.get(i)));
        return List.copyOf(needs);
    }

    /** The unlocked crafting recipes that fit one grid size. Rebuilt when the recipe book changes. */
    public static final class Index {
        private final List<RecipeCollection> builtFrom;
        private final Level level;
        private final int width;
        private final int height;
        private final Map<Item, List<Option>> producers = new HashMap<>();
        private final Map<RecipeDisplayId, Option> byId = new HashMap<>();

        private Index(List<RecipeCollection> builtFrom, Level level, int width, int height) {
            this.builtFrom = builtFrom;
            this.level = level;
            this.width = width;
            this.height = height;
        }
    }

    /**
     * The recipes for a grid of this size, from the player's recipe book. {@code Ingredient.items()}
     * is marked deprecated, but it's how vanilla's own recipe placement lists an ingredient's
     * items, and the planner needs that list.
     */
    @SuppressWarnings("deprecation")
    public static Index index(Minecraft mc, int width, int height) {
        List<RecipeCollection> collections = mc.player.getRecipeBook().getCollections();
        Index index = cached;
        // The recipe book builds a new list whenever recipes are unlocked.
        if (index != null && index.builtFrom == collections && index.level == mc.level
                && index.width == width && index.height == height) return index;
        index = new Index(collections, mc.level, width, height);
        ContextMap context = SlotDisplayContext.fromLevel(mc.level);
        FeatureFlagSet features = mc.level.enabledFeatures();
        for (RecipeCollection collection : collections) {
            for (RecipeDisplayEntry entry : collection.getRecipes()) {
                if (!fits(entry.display(), width, height) || !entry.display().isEnabled(features)) continue;
                List<Ingredient> requirements = entry.craftingRequirements().orElse(List.of());
                if (requirements.isEmpty()) continue;
                List<ItemStack> results = entry.resultItems(context);
                if (results.isEmpty()) continue;
                ItemStack result = results.getFirst();
                // Special results (renamed, enchanted) would never count as an ingredient anyway.
                if (result.isEmpty() || !Inventory.isUsableForCrafting(result)) continue;
                List<List<Item>> slots = requirements.stream()
                        .map(ingredient -> ingredient.items().map(Holder::value).toList()).toList();
                Option option = new Option(entry.id(), needs(slots), result.copy(), perRound(slots));
                index.byId.put(entry.id(), option);
                index.producers.computeIfAbsent(result.getItem(), item -> new ArrayList<>()).add(option);
            }
        }
        cached = index;
        return index;
    }

    /** Drops the cached index, which holds on to its world; called on disconnect so a world left behind can be freed. */
    public static void clearCache() {
        cached = null;
    }

    /** The crafting recipe book's own rule for which recipes the grid can show. */
    private static boolean fits(RecipeDisplay display, int width, int height) {
        if (display instanceof ShapedCraftingRecipeDisplay shaped) return shaped.width() <= width && shaped.height() <= height;
        if (display instanceof ShapelessCraftingRecipeDisplay shapeless) return shapeless.ingredients().size() <= width * height;
        return false;
    }

    /** Sets the grid can hold at once: each slot holds at most a stack of its item. */
    private static int perRound(List<List<Item>> ingredients) {
        int sets = 64;
        for (List<Item> accepted : ingredients) {
            int most = 1;
            for (Item item : accepted) most = Math.max(most, item.components().getOrDefault(DataComponents.MAX_STACK_SIZE, 1));
            sets = Math.min(sets, most);
        }
        return sets;
    }

    /** A planner for one set of starting items: inventory, grid and storage, usable ones only. */
    public static Planner planner(Index index, Map<Item, Integer> pool) {
        return new Planner(index, pool);
    }

    /** Adds up item counts. */
    public static Map<Item, Integer> merge(Map<Item, Integer> a, Map<Item, Integer> b) {
        Map<Item, Integer> merged = new LinkedHashMap<>(a);
        b.forEach((item, count) -> merged.merge(item, count, Integer::sum));
        return merged;
    }

    /**
     * Plans chains for one set of starting items. Which items can be had at all within each
     * number of levels is worked out once, ignoring amounts, so hopeless branches are skipped.
     */
    public static final class Planner {
        private final Index index;
        private final Object2IntOpenHashMap<Item> pool = new Object2IntOpenHashMap<>();
        /** {@code obtainable.get(k)}: items there, or craftable from them in at most k levels. */
        private final List<Set<Item>> obtainable = new ArrayList<>();

        private Planner(Index index, Map<Item, Integer> start) {
            this.index = index;
            start.forEach((item, count) -> {
                if (count > 0) pool.addTo(item, count);
            });
            Set<Item> level = new HashSet<>(pool.keySet());
            obtainable.add(level);
            for (int k = 1; k <= MAX_DEPTH; k++) {
                Set<Item> previous = level;
                level = new HashSet<>(previous);
                for (Map.Entry<Item, List<Option>> e : index.producers.entrySet()) {
                    if (level.contains(e.getKey())) continue;
                    for (Option option : e.getValue()) {
                        if (allObtainable(option, previous)) {
                            level.add(e.getKey());
                            break;
                        }
                    }
                }
                obtainable.add(level);
            }
        }

        /** Sets of {@code recipe} the grid holds at once, or 1 for a recipe it doesn't know. */
        public int perRound(RecipeDisplayId recipe) {
            Option option = index.byId.get(recipe);
            return option == null ? 1 : option.perRound();
        }

        /** The chain for {@code amount} crafts of a recipe the grid can show, or null if there's none. */
        public @Nullable Chain plan(RecipeDisplayId recipe, int amount) {
            Option option = index.byId.get(recipe);
            if (option == null || amount <= 0) return null;
            Search search = new Search();
            // The clicked recipe's own result is never an intermediate for itself.
            search.chain.add(option.result().getItem());
            if (!search.satisfy(option.needs(), amount, 0)) return null;
            return new Chain(List.copyOf(search.steps), amount, search.usedMap());
        }

        /** The chain for as many crafts as possible, at most one grid-full, like a shift-click. */
        public @Nullable Chain planMost(RecipeDisplayId recipe) {
            Option option = index.byId.get(recipe);
            return option == null ? null : planUpTo(recipe, option.perRound());
        }

        /** The chain for as many crafts as possible, at most {@code most} (repeated clicks, Nov 9), or null if not even one. */
        public @Nullable Chain planUpTo(RecipeDisplayId recipe, int most) {
            Chain best = plan(recipe, 1);
            if (best == null) return null;
            int low = 1;
            int high = Math.max(1, most);
            // More crafts never need fewer ingredients, so the largest that works can be found by halving.
            while (low < high) {
                int mid = (low + high + 1) / 2;
                Chain chain = plan(recipe, mid);
                if (chain != null) {
                    best = chain;
                    low = mid;
                } else {
                    high = mid - 1;
                }
            }
            return best;
        }

        private static boolean allObtainable(Option option, Set<Item> items) {
            for (Need need : option.needs()) {
                if (!anyIn(need.accepted(), items)) return false;
            }
            return true;
        }

        private static boolean anyIn(List<Item> accepted, Set<Item> items) {
            for (Item item : accepted) {
                if (items.contains(item)) return true;
            }
            return false;
        }

        /** One search. Backtracking restores a saved copy of the counts. */
        private final class Search {
            /** Starting items not used yet. */
            Object2IntOpenHashMap<Item> left = new Object2IntOpenHashMap<>(pool);
            /** Made by earlier steps and not used yet, such as a fourth plank. */
            Object2IntOpenHashMap<Item> spare = new Object2IntOpenHashMap<>();
            Object2IntOpenHashMap<Item> used = new Object2IntOpenHashMap<>();
            final List<Step> steps = new ArrayList<>();
            /** Items being crafted further up, which are neither crafted again nor used up below (no loops). */
            final Set<Item> chain = new HashSet<>();
            int tries;

            /**
             * {@code crafts} sets of a recipe's ingredients. What's there is reserved for every
             * need first, then the rest is crafted, so crafting one doesn't use up what another
             * needed as it was. Before crafting, spare output from crafts made meanwhile is
             * used, since one craft often makes several (a block gives 9 ingots).
             */
            boolean satisfy(List<Need> needs, int crafts, int depth) {
                int[] missing = new int[needs.size()];
                for (int i = 0; i < missing.length; i++) {
                    int count = crafts * needs.get(i).perCraft();
                    missing[i] = count - take(needs.get(i).accepted(), count);
                }
                for (int i = 0; i < missing.length; i++) {
                    if (missing[i] == 0) continue;
                    List<Item> accepted = needs.get(i).accepted();
                    missing[i] -= take(accepted, missing[i]);
                    if (missing[i] > 0 && !craft(accepted, missing[i], depth)) return false;
                }
                return true;
            }

            /**
             * Takes up to {@code count} accepted items: spare intermediates first, then the most
             * plentiful starting item. Never an item being made further up this chain: unpacking
             * an iron block into ingots to pack them into an iron block gains nothing.
             */
            private int take(List<Item> accepted, int count) {
                int taken = 0;
                for (Item item : accepted) {
                    int spareCount = spare.getInt(item);
                    if (spareCount <= 0 || chain.contains(item)) continue;
                    int t = Math.min(spareCount, count - taken);
                    spare.addTo(item, -t);
                    taken += t;
                    if (taken == count) return taken;
                }
                List<Item> there = new ArrayList<>();
                for (Item item : accepted) {
                    if (left.getInt(item) > 0 && !chain.contains(item)) there.add(item);
                }
                there.sort(Comparator.comparingInt((Item item) -> left.getInt(item)).reversed());
                for (Item item : there) {
                    int t = Math.min(left.getInt(item), count - taken);
                    left.addTo(item, -t);
                    used.addTo(item, t);
                    taken += t;
                    if (taken == count) break;
                }
                return taken;
            }

            /** Crafts {@code count} of any accepted item, trying the recipes whose ingredients there are most of first. */
            private boolean craft(List<Item> accepted, int count, int depth) {
                int budget = MAX_DEPTH - depth;
                if (budget <= 0 || steps.size() >= MAX_STEPS) return false;
                Set<Item> reachable = obtainable.get(budget);
                Set<Item> below = obtainable.get(budget - 1);
                List<Option> options = new ArrayList<>();
                for (Item item : accepted) {
                    if (chain.contains(item) || !reachable.contains(item)) continue;
                    for (Option option : index.producers.getOrDefault(item, List.of())) {
                        if (allObtainable(option, below)) options.add(option);
                    }
                }
                if (options.isEmpty()) return false;
                options.sort(Comparator.comparingInt(this::score).reversed());

                for (Option option : options) {
                    if (++tries > MAX_TRIES) return false;
                    Item made = option.result().getItem();
                    int times = Math.ceilDiv(count, option.result().getCount());
                    Object2IntOpenHashMap<Item> savedLeft = left.clone();
                    Object2IntOpenHashMap<Item> savedSpare = spare.clone();
                    Object2IntOpenHashMap<Item> savedUsed = used.clone();
                    int savedSteps = steps.size();

                    chain.add(made);
                    boolean ok = satisfy(option.needs(), times, depth + 1);
                    chain.remove(made);
                    if (ok && steps.size() < MAX_STEPS) {
                        steps.add(new Step(option.id(), times, option.perRound(), option.result()));
                        spare.addTo(made, times * option.result().getCount() - count);
                        return true;
                    }
                    left = savedLeft;
                    spare = savedSpare;
                    used = savedUsed;
                    while (steps.size() > savedSteps) steps.removeLast();
                }
                return false;
            }

            /** How much of the recipe's ingredients is there now. */
            private int score(Option option) {
                int score = 0;
                for (Need need : option.needs()) {
                    int most = 0;
                    for (Item item : need.accepted()) most = Math.max(most, left.getInt(item) + spare.getInt(item));
                    score += Math.min(most, 64);
                }
                return score;
            }

            Map<Item, Integer> usedMap() {
                Map<Item, Integer> map = new LinkedHashMap<>();
                for (Object2IntMap.Entry<Item> e : used.object2IntEntrySet()) {
                    if (e.getIntValue() > 0) map.put(e.getKey(), e.getIntValue());
                }
                return map;
            }
        }
    }
}
