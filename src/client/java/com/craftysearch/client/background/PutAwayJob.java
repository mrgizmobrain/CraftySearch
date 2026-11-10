package com.craftysearch.client.background;

import com.craftysearch.client.memory.ContainerRecorder;
import com.craftysearch.client.memory.MemoryEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Client-only putting away (spec 3.4): a stack goes into the containers in
 * {@link com.craftysearch.storage.PutAwayRule} order. From the cursor, the stack waits in an
 * empty inventory slot meanwhile, and whatever doesn't fit goes back on the cursor. A whole
 * stack is shift-clicked from its slot; an exact amount (one item from the cursor, notches
 * of the scroll wheel, spec 3.5) is right-clicked in one item at a time. Whole stacks of one
 * item from several slots (a shift-double-click) are one job, each container opened once for
 * all of them (Nov 9).
 */
final class PutAwayJob extends BackgroundJob {
    /** Source slot value meaning the cursor. */
    static final int CURSOR = -1;
    /** Amount meaning the whole stack. */
    static final int WHOLE_STACK = 0;

    private final boolean fromCursor;
    private final boolean wholeStack;
    /** Whole stacks from inventory slots: each of these, in turn. */
    private final List<Integer> sources;
    private int left;
    private int parkIndex;
    private boolean done;

    PutAwayJob(List<MemoryEntry> containers, List<Integer> sourceSlots, int amount) {
        super(containers);
        this.fromCursor = sourceSlots.getFirst() == CURSOR;
        this.parkIndex = sourceSlots.getFirst();
        this.sources = List.copyOf(sourceSlots);
        this.wholeStack = amount == WHOLE_STACK;
        this.left = amount;
    }

    @Override
    Component progress(int index, int total, boolean finishing) {
        return Component.translatable("craftysearch.storage.progress_put", index, total);
    }

    /** From the cursor, the parked stack still belongs in the player's hand, so it's drawn there. */
    @Override
    int handSlot() {
        return fromCursor ? parkIndex : -1;
    }

    @Override
    Result prepare(Minecraft mc, AbstractContainerMenu menu) {
        if (!fromCursor) return Result.NEXT;
        if (menu.getCarried().isEmpty()) return Result.done(null);
        parkIndex = Parking.emptySlot(mc.player.getInventory());
        Slot park = parkIndex < 0 ? null : Parking.slotFor(menu, parkIndex);
        if (park == null) return Result.fail("craftysearch.storage.need_free_slot");
        Parking.click(mc, menu, park, 0, ContainerInput.PICKUP);
        return Result.NEXT;
    }

    @Override
    Result work(Minecraft mc, AbstractContainerMenu container) {
        if (wholeStack && !fromCursor) return storeStacks(mc, container);
        Slot park = Parking.slotFor(container, parkIndex);
        if (park == null || !park.hasItem()) {
            done = true;
            return Result.DONE_NO_MESSAGE;
        }
        if (wholeStack) {
            // Shift-click fills matching stacks, then empty slots; what doesn't fit stays put.
            Parking.click(mc, container, park, 0, ContainerInput.QUICK_MOVE);
            done = !park.hasItem();
            return done ? Result.DONE_NO_MESSAGE : Result.NEXT;
        }
        // Pick up the stack, right-click the items in one at a time, put the rest back; all in one tick.
        ItemStack item = park.getItem().copyWithCount(1);
        if (targetSlot(container, item) == null) return Result.NEXT;
        Parking.click(mc, container, park, 0, ContainerInput.PICKUP);
        while (left > 0 && !container.getCarried().isEmpty()) {
            Slot target = targetSlot(container, item);
            if (target == null) break;
            Parking.click(mc, container, target, 1, ContainerInput.PICKUP);
            left--;
        }
        if (!container.getCarried().isEmpty()) Parking.click(mc, container, park, 0, ContainerInput.PICKUP);
        done = left == 0 || !park.hasItem();
        return done ? Result.DONE_NO_MESSAGE : Result.NEXT;
    }

    /** One stack per step, shift-clicked in; when this container is full, on to the next. */
    private Result storeStacks(Minecraft mc, AbstractContainerMenu container) {
        for (int index : sources) {
            Slot slot = Parking.slotFor(container, index);
            if (slot == null || !slot.hasItem()) continue;
            Parking.click(mc, container, slot, 0, ContainerInput.QUICK_MOVE);
            return slot.hasItem() ? Result.NEXT : Result.AGAIN;
        }
        done = true;
        return Result.DONE_NO_MESSAGE;
    }

    @Override
    boolean satisfied() {
        return done;
    }

    @Override
    Result finish(Minecraft mc, AbstractContainerMenu menu, long now) {
        if (fromCursor) {
            Slot park = Parking.slotFor(menu, parkIndex);
            // What's left goes back on the cursor, where it came from.
            if (park != null && park.hasItem() && menu.getCarried().isEmpty()) Parking.click(mc, menu, park, 0, ContainerInput.PICKUP);
        }
        return Result.done(done ? null : "craftysearch.storage.no_room");
    }

    /** A stack of the same item with room, else an empty slot that accepts it. */
    private static @Nullable Slot targetSlot(AbstractContainerMenu container, ItemStack item) {
        Slot empty = null;
        for (Slot slot : ContainerRecorder.containerSlots(container)) {
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                if (empty == null && slot.mayPlace(item)) empty = slot;
            } else if (ItemStack.isSameItemSameComponents(stack, item) && stack.getCount() < slot.getMaxStackSize(stack)) {
                return slot;
            }
        }
        return empty;
    }
}
