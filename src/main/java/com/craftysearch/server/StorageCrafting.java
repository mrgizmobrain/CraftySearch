package com.craftysearch.server;

import com.craftysearch.ServerConfig;
import com.craftysearch.network.CraftRequestPayload;
import com.craftysearch.server.StorageScanner.Found;
import com.craftysearch.storage.ContainerKind;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlaceGhostRecipePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.RecipeBookMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side crafting from storage (spec 3.3 and 4.1). Vanilla recipe placement only takes
 * ingredients from the player's inventory, so this moves the missing ones there first, all
 * or nothing, then lets vanilla place the recipe and optionally crafts it into the inventory.
 */
public final class StorageCrafting {
    private static final Map<UUID, Long> LAST_REQUEST_TICK = new HashMap<>();
    /** {@link #pullFor}'s "as many as possible". */
    private static final int BIGGEST = -1;
    /** Caps one intermediate step, whatever a client asks for. */
    private static final int MAX_STEP_TIMES = 1024;
    /** Stands in for a locked slot in {@link #fitsInInventory}: never empty, never merged into. */
    private static final ItemStack LOCKED = new ItemStack(Items.BARRIER);

    /** Take {@code count} from {@code slot} of {@code container}. */
    private record Take(Container container, int slot, int count) {
    }

    private StorageCrafting() {
    }

    public static void forget(ServerPlayer player) {
        LAST_REQUEST_TICK.remove(player.getUUID());
    }

    public static void handle(MinecraftServer server, ServerPlayer player, CraftRequestPayload request) {
        ServerConfig config = ServerConfig.get();
        if (!config.enabled) return;
        if (!config.allowCrafting) {
            message(player, "disabled");
            return;
        }
        // One request per tick is plenty for clicks and keeps a spamming client cheap.
        Long last = LAST_REQUEST_TICK.put(player.getUUID(), (long) server.getTickCount());
        if (last != null && last == server.getTickCount()) return;

        // The same checks vanilla makes for its place-recipe packet.
        AbstractContainerMenu open = player.containerMenu;
        if (player.isSpectator() || open.containerId != request.containerId() || !open.stillValid(player)
                || !(open instanceof AbstractCraftingMenu menu)) return;
        RecipeManager.ServerDisplayInfo info = server.getRecipeManager().getRecipeFromDisplay(request.recipe());
        if (info == null) return;
        RecipeHolder<?> holder = info.parent();
        if (!player.getRecipeBook().contains(holder.id()) || holder.value().placementInfo().isImpossibleToPlace()) return;
        if (request.steps().size() > CraftRequestPayload.MAX_STEPS) return;

        List<Found> found = StorageScanner.scan(player, StorageSync.clampRadius(request.radius()),
                ContainerKind.fromMask(request.kindsMask()));
        Vec3 eye = player.getEyePosition();
        found.sort(Comparator.comparingDouble(f -> f.distanceSqr(eye)));
        List<Container> sources = found.stream().flatMap(f -> f.halves().stream()).toList();
        // Leftovers only go where put-away would put them: not hoppers, hopper minecarts or dispensers.
        List<Container> stores = found.stream().filter(f -> f.kind().acceptsPutAway()).flatMap(f -> f.halves().stream()).toList();
        boolean nearestFirst = request.has(CraftRequestPayload.NEAREST_FIRST);
        KeptSlots kept = KeptSlots.of(found, request.has(CraftRequestPayload.KEEP_ONE));
        // Pulled ingredients and leftovers keep out of the client's locked slots.
        long locked = request.lockedSlots();

        // Spec 3.6: intermediates first, each crafted into the inventory, in the order the client planned.
        // If one can't be done, what was crafted so far stays in the inventory.
        for (CraftRequestPayload.Step step : request.steps()) {
            RecipeHolder<?> stepHolder = intermediate(server, player, menu, step.recipe());
            if (stepHolder == null) {
                message(player, "chain_stopped");
                return;
            }
            if (!craftStep(player, menu, stepHolder, Math.min(step.times(), MAX_STEP_TIMES), nearestFirst, sources, kept, locked,
                    stores)) {
                return;
            }
        }

        Pull pull = plan(player, menu, holder.value(), request, sources, kept, locked);
        if (pull == null) return;
        for (Take take : pull.takes()) pull(player, take, locked);

        ServerLevel level = player.level();
        RecipeBookMenu.PostPlaceAction action =
                menu.handlePlacement(request.has(CraftRequestPayload.USE_MAX), player.isCreative(), holder, level, player.getInventory());
        if (action == RecipeBookMenu.PostPlaceAction.PLACE_GHOST_RECIPE) {
            player.connection.send(new ClientboundPlaceGhostRecipePacket(menu.containerId, info.display().display()));
            return;
        }
        if (request.has(CraftRequestPayload.CRAFT_TO_INVENTORY)) craftIntoInventory(player, menu, stores, locked);
    }

    /**
     * An intermediate step's recipe, if the player could place it by hand here: unlocked, a
     * crafting recipe, and small enough for the grid. Null otherwise.
     */
    private static @Nullable RecipeHolder<?> intermediate(MinecraftServer server, ServerPlayer player,
                                                          AbstractCraftingMenu menu, RecipeDisplayId id) {
        RecipeManager.ServerDisplayInfo info = server.getRecipeManager().getRecipeFromDisplay(id);
        if (info == null) return null;
        RecipeHolder<?> holder = info.parent();
        Recipe<?> recipe = holder.value();
        if (!player.getRecipeBook().contains(holder.id()) || recipe.placementInfo().isImpossibleToPlace()
                || !(recipe instanceof CraftingRecipe)) return null;
        boolean fits = recipe instanceof ShapedRecipe shaped
                ? shaped.getWidth() <= menu.getGridWidth() && shaped.getHeight() <= menu.getGridHeight()
                : recipe.placementInfo().ingredients().size() <= menu.getGridWidth() * menu.getGridHeight();
        return fits ? holder : null;
    }

    /**
     * Crafts an intermediate {@code times} times into the inventory, a grid-full at a time:
     * pull the ingredients, place the recipe once per craft (each placement adds a set, as a
     * repeated click does), and shift-click the result. False after telling the player why it stopped.
     */
    private static boolean craftStep(ServerPlayer player, AbstractCraftingMenu menu, RecipeHolder<?> holder, int times,
                                     boolean nearestFirst, List<Container> sources, KeptSlots kept, long locked,
                                     List<Container> stores) {
        ServerLevel level = player.level();
        int left = times;
        while (left > 0) {
            Pull pull = pullFor(player, menu, holder.value(), left, true, nearestFirst, sources, kept, locked);
            if (pull == null) return false;
            for (Take take : pull.takes()) pull(player, take, locked);
            int placed = 0;
            while (placed < pull.amount() && menu.handlePlacement(false, player.isCreative(), holder, level, player.getInventory())
                    == RecipeBookMenu.PostPlaceAction.NOTHING) {
                placed++;
            }
            Slot result = menu.getResultSlot();
            if (placed == 0 || !result.hasItem()) {
                returnGrid(player, menu, stores, locked);
                message(player, "chain_stopped");
                return false;
            }
            // Vanilla's shift-click on the result crafts every set in the grid.
            menu.clicked(result.index, 0, ContainerInput.QUICK_MOVE, player);
            boolean full = result.hasItem();
            returnGrid(player, menu, stores, locked);
            if (full) {
                message(player, "inventory_full");
                return false;
            }
            left -= placed;
        }
        return true;
    }

    /** Takes from storage and how many crafts they're for. */
    private record Pull(List<Take> takes, int amount) {
    }

    /** What to pull for the clicked recipe, or null (after telling the player why) if the craft can't happen. */
    private static @Nullable Pull plan(ServerPlayer player, AbstractCraftingMenu menu, Recipe<?> recipe,
                                       CraftRequestPayload request, List<Container> sources, KeptSlots kept, long locked) {
        // The same amount vanilla would place: as many as possible on shift-click, otherwise
        // one more set than the grid already holds.
        boolean useMax = request.has(CraftRequestPayload.USE_MAX);
        int amount = useMax ? BIGGEST : 1 + setsInGrid(menu, recipe, player.level());
        return pullFor(player, menu, recipe, amount, useMax, request.has(CraftRequestPayload.NEAREST_FIRST), sources, kept, locked);
    }

    /**
     * What to pull for {@code wanted} crafts ({@link #BIGGEST} for as many as possible), at most
     * a grid-full. With {@code allowFewer}, fewer when the ingredients wouldn't fit in the
     * inventory. Null, after telling the player why, if not even that works.
     */
    private static @Nullable Pull pullFor(ServerPlayer player, AbstractCraftingMenu menu, Recipe<?> recipe, int wanted,
                                          boolean allowFewer, boolean nearestFirst, List<Container> sources, KeptSlots kept,
                                          long locked) {
        Inventory inventory = player.getInventory();
        StackedItemContents all = new StackedItemContents();
        inventory.fillStackedContents(all);
        menu.fillCraftSlotsStackedContents(all);
        for (Container container : sources) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                // A kept slot counts one less; accountSimpleStack skips enchanted, damaged and renamed items, as vanilla does.
                int takeable = kept.takeable(container, i);
                if (takeable > 0) all.accountSimpleStack(takeable == stack.getCount() ? stack : stack.copyWithCount(takeable));
            }
        }

        int amount = wanted == BIGGEST ? all.getBiggestCraftableStack(recipe, null) : wanted;
        List<Holder<Item>> chosen = new ArrayList<>();
        if (amount <= 0 || !all.canCraft(recipe, amount, chosen::add)) {
            message(player, "missing");
            return null;
        }
        int clamped = clampToMaxStackSize(amount, chosen);
        if (clamped != amount) {
            amount = clamped;
            chosen.clear();
            if (!all.canCraft(recipe, amount, chosen::add)) {
                message(player, "missing");
                return null;
            }
        }

        // On shift-click, craft fewer if the ingredients wouldn't fit in the inventory.
        for (int n = amount; n >= (allowFewer ? 1 : amount); n--) {
            if (n != amount) {
                chosen.clear();
                if (!all.canCraft(recipe, n, chosen::add)) continue;
            }
            List<Take> takes = takesFor(chosen, n, inventory, menu, sources, kept, nearestFirst);
            if (fitsInInventory(inventory, takes, locked)) return new Pull(takes, n);
        }
        message(player, "no_space");
        return null;
    }

    /** Vanilla places each chosen item {@code amount} times, one per ingredient slot. */
    private static List<Take> takesFor(List<Holder<Item>> chosen, int amount, Inventory inventory, AbstractCraftingMenu menu,
                                       List<Container> sources, KeptSlots kept, boolean nearestFirst) {
        Map<Item, Integer> needed = new LinkedHashMap<>();
        for (Holder<Item> item : chosen) needed.merge(item.value(), amount, Integer::sum);

        List<Take> takes = new ArrayList<>();
        for (Map.Entry<Item, Integer> entry : needed.entrySet()) {
            Item item = entry.getKey();
            // Nearest first takes everything storage has and leaves the inventory for the rest.
            int toPull = nearestFirst ? entry.getValue() : Math.max(0, entry.getValue() - localCount(item, inventory, menu));
            for (Container container : sources) {
                for (int i = 0; i < container.getContainerSize() && toPull > 0; i++) {
                    ItemStack stack = container.getItem(i);
                    if (stack.isEmpty() || stack.getItem() != item || !Inventory.isUsableForCrafting(stack)) continue;
                    int count = Math.min(toPull, kept.takeable(container, i));
                    if (count <= 0) continue;
                    takes.add(new Take(container, i, count));
                    toPull -= count;
                }
                if (toPull == 0) break;
            }
        }
        return takes;
    }

    private static int localCount(Item item, Inventory inventory, AbstractCraftingMenu menu) {
        int count = 0;
        for (ItemStack stack : inventory.getNonEquipmentItems()) {
            if (stack.getItem() == item && Inventory.isUsableForCrafting(stack)) count += stack.getCount();
        }
        for (Slot slot : menu.getInputGridSlots()) {
            ItemStack stack = slot.getItem();
            if (stack.getItem() == item && Inventory.isUsableForCrafting(stack)) count += stack.getCount();
        }
        return count;
    }

    /** Simulates adding the pulled stacks to copies of the inventory's main slots, outside locked ones. */
    private static boolean fitsInInventory(Inventory inventory, List<Take> takes, long locked) {
        List<ItemStack> slots = new ArrayList<>();
        int index = 0;
        for (ItemStack stack : inventory.getNonEquipmentItems()) {
            // A locked slot is left out by standing in as a full one.
            slots.add(LockedSlots.isLocked(locked, index++) ? LOCKED : stack.copy());
        }
        for (Take take : takes) {
            ItemStack incoming = take.container().getItem(take.slot());
            int left = take.count();
            for (ItemStack slot : slots) {
                if (left == 0) break;
                if (slot == LOCKED || slot.isEmpty() || !ItemStack.isSameItemSameComponents(slot, incoming)) continue;
                int move = Math.min(left, slot.getMaxStackSize() - slot.getCount());
                if (move > 0) {
                    slot.grow(move);
                    left -= move;
                }
            }
            for (int i = 0; i < slots.size() && left > 0; i++) {
                if (!slots.get(i).isEmpty()) continue;
                int move = Math.min(left, incoming.getMaxStackSize());
                slots.set(i, incoming.copyWithCount(move));
                left -= move;
            }
            if (left > 0) return false;
        }
        return true;
    }

    private static void pull(ServerPlayer player, Take take, long locked) {
        ItemStack taken = take.container().removeItem(take.slot(), take.count());
        take.container().setChanged();
        LockedSlots.add(player.getInventory(), taken, locked);
        if (!taken.isEmpty()) {
            // Only if the simulation was wrong: put it back where it came from, which has room now.
            ItemStack rest = ContainerInsert.insert(List.of(take.container()), taken);
            if (!rest.isEmpty()) player.getInventory().placeItemBackInInventory(rest);
        }
    }

    /** Shift-clicks the result like a player would, then clears what's left of the grid. */
    private static void craftIntoInventory(ServerPlayer player, AbstractCraftingMenu menu, List<Container> sources, long locked) {
        Slot result = menu.getResultSlot();
        if (!result.hasItem()) return;
        // Vanilla's shift-click on the result crafts repeatedly and stops when the inventory is full.
        menu.clicked(result.index, 0, ContainerInput.QUICK_MOVE, player);
        boolean stoppedEarly = result.hasItem();
        returnGrid(player, menu, sources, locked);
        if (stoppedEarly) message(player, "inventory_full");
    }

    /** Moves leftover grid items to the inventory (outside locked slots), then to storage; anything that fits nowhere stays put. */
    private static void returnGrid(ServerPlayer player, AbstractCraftingMenu menu, List<Container> sources, long locked) {
        for (Slot slot : menu.getInputGridSlots()) {
            if (!slot.hasItem()) continue;
            ItemStack rest = slot.getItem().copy();
            LockedSlots.add(player.getInventory(), rest, locked);
            if (!rest.isEmpty()) rest = ContainerInsert.insert(sources, rest);
            slot.set(rest);
        }
    }

    /** How many full sets of this recipe the grid already holds, as vanilla counts them. */
    private static int setsInGrid(AbstractCraftingMenu menu, Recipe<?> recipe, Level level) {
        if (!(recipe instanceof CraftingRecipe crafting)) return 0;
        List<ItemStack> items = menu.getInputGridSlots().stream().map(Slot::getItem).toList();
        if (!crafting.matches(CraftingInput.of(menu.getGridWidth(), menu.getGridHeight(), items), level)) return 0;
        int min = Integer.MAX_VALUE;
        for (ItemStack stack : items) {
            if (!stack.isEmpty()) min = Math.min(min, stack.getCount());
        }
        return min == Integer.MAX_VALUE ? 0 : min;
    }

    /** Vanilla's clamp: no grid slot may hold more than its item's max stack size. */
    private static int clampToMaxStackSize(int amount, List<Holder<Item>> items) {
        for (Holder<Item> item : items) {
            amount = Math.min(amount, item.components().getOrDefault(DataComponents.MAX_STACK_SIZE, 1));
        }
        return amount;
    }

    private static void message(ServerPlayer player, String key) {
        player.sendOverlayMessage(Component.translatable("craftysearch.craft." + key));
    }
}
