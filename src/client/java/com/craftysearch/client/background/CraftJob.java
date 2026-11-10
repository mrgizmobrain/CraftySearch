package com.craftysearch.client.background;

import com.craftysearch.client.compat.IpnLockedSlots;
import com.craftysearch.client.config.ClientConfig;
import com.craftysearch.client.memory.ContainerRecorder;
import com.craftysearch.client.recipe.CraftChains;
import com.craftysearch.storage.KeepOne;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client-only crafting from storage (spec 4.2): shift-clicks the planned ingredients out of
 * containers within reach, then places the recipe and takes the result, like a player would.
 */
final class CraftJob extends BackgroundJob {
    private static final long MIN_VERIFY_DELAY_MILLIS = 300;
    private static final long FAST_VERIFY_DELAY_MILLIS = 100;

    /**
     * The STEP phases craft the intermediates (spec 3.6), one grid-full per round, before the
     * clicked recipe; repeated clicks crafted into the inventory are one more such step.
     */
    private enum Phase { CHECK, STEP_PLACE, STEP_WAIT_PLACED, STEP_TAKE, STEP_WAIT_CRAFTED, PLACE, WAIT_RESULT, TAKE, VERIFY }

    private final RecipeDisplayId recipe;
    private final boolean useMax;
    private final boolean craftToInventory;
    private final Map<Item, Integer> remaining;
    /** The intermediates, then for repeated clicks crafted into the inventory, the clicked recipe itself. */
    private final List<CraftChains.Step> steps;
    private final int intermediates;
    /** Sets of the clicked recipe to place in the grid: one per click, more for repeated clicks (Nov 9). */
    private final int placements;
    private Phase phase = Phase.CHECK;
    private long waitUntil;
    private long earliest;
    private @Nullable Item resultItem;
    private int resultCountBefore;
    private int stepIndex;
    /** Crafts of the current step done in earlier rounds, and sets placed in this one. */
    private int stepDone;
    private int roundSets;

    CraftJob(CraftPlanner.Plan plan, RecipeDisplayId recipe, boolean useMax, boolean craftToInventory) {
        super(plan.containers());
        this.recipe = recipe;
        this.useMax = useMax;
        this.craftToInventory = craftToInventory;
        this.remaining = new LinkedHashMap<>(plan.toPull());
        this.intermediates = plan.steps().size();
        boolean repeated = !useMax && plan.sets() > 1;
        if (repeated && craftToInventory) {
            // Crafted round by round, a grid-full at a time, like an intermediate.
            List<CraftChains.Step> all = new ArrayList<>(plan.steps());
            all.add(new CraftChains.Step(recipe, plan.sets(), Math.max(1, plan.perRound()), ItemStack.EMPTY));
            this.steps = List.copyOf(all);
        } else {
            this.steps = plan.steps();
        }
        // Into the grid only, no more than it holds, as that many clicks would place.
        this.placements = repeated ? Math.min(plan.sets(), Math.max(1, plan.perRound())) : 1;
    }

    /** True once the job has gone on to the clicked recipe's own rounds. */
    private boolean craftingClicked() {
        return stepIndex >= intermediates;
    }

    @Override
    Component progress(int index, int total, boolean finishing) {
        if (finishing && !craftingClicked()) {
            return Component.translatable("craftysearch.craft.progress_intermediate", stepIndex + 1, intermediates);
        }
        return finishing ? Component.translatable("craftysearch.craft.progress_crafting")
                : Component.translatable("craftysearch.craft.progress", index, total);
    }

    @Override
    boolean satisfied() {
        return remaining.isEmpty();
    }

    /**
     * Moves one stack per step, from the slots the real contents offer (memory may be out of
     * date). A stack bigger than what's still needed gives exactly that many when it's
     * {@link #EXACT_LIMIT} or fewer, so a single craft takes no more than it uses; otherwise
     * the whole stack is shift-clicked.
     */
    @Override
    Result work(Minecraft mc, AbstractContainerMenu container) {
        List<Slot> slots = ContainerRecorder.containerSlots(container);
        boolean[] kept = TakeJob.keptSlots(slots);
        int index = pickSlot(slots, kept);
        if (index < 0) return Result.NEXT;
        Slot slot = slots.get(index);
        Item item = slot.getItem().getItem();
        int want = remaining.getOrDefault(item, 0);
        int before = slot.getItem().getCount();
        int takeable = KeepOne.takeable(slot.getItem(), kept[index]);
        // Always exactly what's needed (Nov 9: the "Exact amounts" toggle is gone).
        boolean exact = takeable > want;
        int moved = exact ? takeExactly(mc, container, slot, want) : -1;
        if (moved < 0 && kept[index]) {
            // "Leave one": everything but the kept one, where a shift-click would take it all.
            moved = Parking.moveAllButOne(mc, container, slot);
        } else if (moved < 0) {
            // A shift-click, or by hand around IPN's locked slots.
            moved = Parking.quickMoveOut(mc, container, slot);
        }
        if (moved <= 0) return Result.fail("craftysearch.craft.no_space");
        int left = remaining.getOrDefault(item, 0) - moved;
        if (left > 0) remaining.put(item, left);
        else remaining.remove(item);
        return remaining.isEmpty() ? Result.DONE_NO_MESSAGE : Result.AGAIN;
    }

    @Override
    Result finish(Minecraft mc, AbstractContainerMenu menu, long now) {
        if (!(menu instanceof AbstractCraftingMenu crafting)) return Result.done(null);
        switch (phase) {
            case CHECK -> {
                if (!remaining.isEmpty()) return Result.fail("craftysearch.craft.missing");
                phase = steps.isEmpty() ? Phase.PLACE : Phase.STEP_PLACE;
                return Result.AGAIN;
            }
            case STEP_PLACE -> {
                // Each placement adds one set to the grid once it holds this recipe, like repeated clicks.
                CraftChains.Step step = steps.get(stepIndex);
                roundSets = Math.min(step.times() - stepDone, step.perRound());
                for (int i = 0; i < roundSets; i++) {
                    mc.getConnection().send(new ServerboundPlaceRecipePacket(crafting.containerId, step.recipe(), false));
                }
                phase = Phase.STEP_WAIT_PLACED;
                waitUntil = now + BackgroundJobs.TIMEOUT_MILLIS;
                return Result.AGAIN;
            }
            case STEP_WAIT_PLACED -> {
                boolean placed = crafting.getResultSlot().hasItem();
                if (placed && setsInGrid(crafting) >= roundSets) {
                    phase = Phase.STEP_TAKE;
                    return Result.AGAIN;
                }
                if (now <= waitUntil) return Result.WAIT;
                if (!placed) return Result.fail("craftysearch.craft.chain_stopped");
                // Fewer sets than asked for: craft what's there and carry on.
                roundSets = setsInGrid(crafting);
                phase = Phase.STEP_TAKE;
                return Result.AGAIN;
            }
            case STEP_TAKE -> {
                Parking.click(mc, crafting, crafting.getResultSlot(), 0, ContainerInput.QUICK_MOVE);
                phase = Phase.STEP_WAIT_CRAFTED;
                // The client only predicts one craft; the server crafts the rest and then clears the result.
                // Fast speed still waits a little: in singleplayer the latency is 0, and the prediction alone
                // looked like a finished round (Nov 9: the next step was placed on a grid the server hadn't
                // emptied, because the inventory was full, and it stopped with "crafting stopped partway").
                earliest = now + (fast() ? Math.max(FAST_VERIFY_DELAY_MILLIS, 2L * latency(mc))
                        : Math.max(MIN_VERIFY_DELAY_MILLIS, 3L * latency(mc)));
                waitUntil = now + BackgroundJobs.TIMEOUT_MILLIS;
                return Result.AGAIN;
            }
            case STEP_WAIT_CRAFTED -> {
                if (now < earliest) return Result.WAIT;
                boolean resultLeft = crafting.getResultSlot().hasItem();
                // Crafted once the server has used every set: the result slot and the grid's sets are gone.
                // Until its answer arrives, the grid still shows the sets the client didn't predict.
                if (resultLeft || setsInGrid(crafting) > 0) {
                    if (now <= waitUntil) return Result.WAIT;
                    // Still something to take: the inventory filled up.
                    if (resultLeft) return Result.fail("craftysearch.craft.inventory_full");
                    // Only what a recipe leaves behind (a bucket, a bottle) is in the grid: crafted.
                }
                stepDone += roundSets;
                if (stepDone >= steps.get(stepIndex).times()) {
                    stepIndex++;
                    stepDone = 0;
                }
                if (stepIndex < steps.size()) {
                    phase = Phase.STEP_PLACE;
                    return Result.AGAIN;
                }
                // Repeated clicks were crafted as the last step: nothing left to place.
                if (steps.size() > intermediates) return Result.done(null);
                phase = Phase.PLACE;
                return Result.AGAIN;
            }
            case PLACE -> {
                // Each placement adds one set to the grid, as each click would.
                for (int i = 0; i < placements; i++) {
                    mc.getConnection().send(new ServerboundPlaceRecipePacket(crafting.containerId, recipe, useMax));
                }
                if (!craftToInventory) return Result.done(null);
                phase = Phase.WAIT_RESULT;
                waitUntil = now + BackgroundJobs.TIMEOUT_MILLIS;
                return Result.AGAIN;
            }
            case WAIT_RESULT -> {
                Slot result = crafting.getResultSlot();
                if (!result.hasItem()) {
                    // Nothing placed: the server sent a ghost recipe instead, which the recipe book shows.
                    return now > waitUntil ? Result.done(null) : Result.WAIT;
                }
                resultItem = result.getItem().getItem();
                resultCountBefore = count(mc.player.getInventory(), resultItem);
                phase = Phase.TAKE;
                return Result.AGAIN;
            }
            case TAKE -> {
                // Like a player shift-clicking the result: the server crafts until the grid or the inventory runs out.
                Parking.click(mc, crafting, crafting.getResultSlot(), 0, ContainerInput.QUICK_MOVE);
                phase = Phase.VERIFY;
                // The client only predicts one craft; wait for the server's answer before checking.
                waitUntil = now + (fast()
                        ? Math.max(FAST_VERIFY_DELAY_MILLIS, 2L * latency(mc))
                        : Math.max(MIN_VERIFY_DELAY_MILLIS, 3L * latency(mc)));
                return Result.AGAIN;
            }
            case VERIFY -> {
                if (now < waitUntil) return Result.WAIT;
                // Spec 4.2 step 5: the result should have arrived in the inventory.
                boolean gained = resultItem != null && count(mc.player.getInventory(), resultItem) > resultCountBefore;
                boolean leftover = crafting.getResultSlot().hasItem();
                return Result.done(!gained ? "craftysearch.craft.not_finished" : leftover ? "craftysearch.craft.inventory_full" : null);
            }
        }
        return Result.done(null);
    }

    /**
     * Picks up the stack and moves exactly {@code want} items into an inventory slot: for a
     * few, right-clicks one at a time into the slot and puts the rest back; for most of the
     * stack, right-clicks what stays back one at a time and drops the rest in at once (Nov 9,
     * so repeated clicks needing dozens stay a few clicks). All the clicks go out in one
     * tick, so nothing can interrupt while the hidden container holds the stack on its cursor
     * (vanilla would drop a cursor stack if the container closed with the inventory full).
     * Returns how many moved, or -1 if no inventory slot has room for them.
     */
    private static int takeExactly(Minecraft mc, AbstractContainerMenu container, Slot source, int want) {
        Slot target = inventoryTarget(container, source.getItem(), want);
        if (target == null) return -1;
        int count = source.getItem().getCount();
        int before = target.getItem().getCount();
        Parking.click(mc, container, source, 0, ContainerInput.PICKUP);
        if (want <= count - want) {
            for (int i = 0; i < want; i++) Parking.click(mc, container, target, 1, ContainerInput.PICKUP);
        } else {
            for (int i = 0; i < count - want; i++) Parking.click(mc, container, source, 1, ContainerInput.PICKUP);
            Parking.click(mc, container, target, 0, ContainerInput.PICKUP);
        }
        if (!container.getCarried().isEmpty()) Parking.click(mc, container, source, 0, ContainerInput.PICKUP);
        return target.getItem().getCount() - before;
    }

    /**
     * A main-inventory slot that can take {@code count} more of {@code item}: a matching stack
     * first, else an empty slot, in vanilla's quick-move order (hotbar first; see Parking.roomFor).
     */
    private static @Nullable Slot inventoryTarget(AbstractContainerMenu container, ItemStack item, int count) {
        Slot empty = null;
        for (int i = container.slots.size() - 1; i >= 0; i--) {
            Slot slot = container.slots.get(i);
            if (!(slot.container instanceof Inventory) || slot.getContainerSlot() >= Inventory.INVENTORY_SIZE) continue;
            // IPN may stop clicks on a locked slot, which would leave items on the hidden container's cursor.
            if (IpnLockedSlots.isLocked(slot.getContainerSlot())) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                if (empty == null && slot.mayPlace(item)) empty = slot;
            } else if (ItemStack.isSameItemSameComponents(stack, item) && slot.getMaxStackSize(stack) - stack.getCount() >= count) {
                return slot;
            }
        }
        return empty;
    }

    /**
     * The next slot to take from: for the first still-needed item the container has, the
     * stack that pulls the least extra.
     */
    private int pickSlot(List<Slot> slots, boolean[] kept) {
        for (Map.Entry<Item, Integer> want : remaining.entrySet()) {
            int best = -1;
            int bestCount = 0;
            for (int i = 0; i < slots.size(); i++) {
                ItemStack stack = slots.get(i).getItem();
                if (stack.isEmpty() || stack.getItem() != want.getKey() || !Inventory.isUsableForCrafting(stack)) continue;
                // With "Leave one" on, a kept slot offers one less.
                int count = KeepOne.takeable(stack, kept[i]);
                if (count <= 0) continue;
                if (best < 0 || isBetter(count, bestCount, want.getValue())) {
                    best = i;
                    bestCount = count;
                }
            }
            if (best >= 0) return best;
        }
        return -1;
    }

    /** A stack covering what's left beats one that doesn't; then the smaller covering one, or the bigger short one. */
    private static boolean isBetter(int count, int bestCount, int want) {
        boolean covers = count >= want;
        boolean bestCovers = bestCount >= want;
        if (covers != bestCovers) return covers;
        return covers ? count < bestCount : count > bestCount;
    }

    /** Sets in the grid: placement puts one item per ingredient slot per set. */
    private static int setsInGrid(AbstractCraftingMenu crafting) {
        int sets = Integer.MAX_VALUE;
        for (Slot slot : crafting.getInputGridSlots()) {
            if (slot.hasItem()) sets = Math.min(sets, slot.getItem().getCount());
        }
        return sets == Integer.MAX_VALUE ? 0 : sets;
    }

    private static int count(Inventory inventory, Item item) {
        int count = 0;
        for (ItemStack stack : inventory.getNonEquipmentItems()) {
            if (stack.getItem() == item) count += stack.getCount();
        }
        return count;
    }

    private static boolean fast() {
        return ClientConfig.get().clientOnly.speed == ClientConfig.Speed.FAST;
    }

    private static int latency(Minecraft mc) {
        PlayerInfo info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(mc.player.getUUID());
        return info == null ? 0 : info.getLatency();
    }
}
