package com.craftysearch.client.live;

import com.craftysearch.network.PutAwayPayload;
import com.craftysearch.network.TakePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Craft, take and put-away requests in server mode, sent at most one per tick. A shift-drag,
 * a shift-double-click, a fast scroll or quick recipe clicks can make several in one frame;
 * the server only takes a few per tick and drops the rest. Scroll notches on the same target
 * in a row become one request, and so do whole-stack put-aways from several slots and
 * whole-stack takes of several tiles.
 */
public final class ServerOutbox {
    private static final int MAX_WAITING = 64;
    private static final Deque<CustomPacketPayload> WAITING = new ArrayDeque<>();
    private static boolean sentThisTick;

    private ServerOutbox() {
    }

    public static void send(CustomPacketPayload payload) {
        if (!sentThisTick && WAITING.isEmpty()) {
            ClientPlayNetworking.send(payload);
            sentThisTick = true;
            return;
        }
        CustomPacketPayload merged = merge(WAITING.peekLast(), payload);
        if (merged != null) {
            WAITING.pollLast();
            WAITING.add(merged);
        } else if (WAITING.size() < MAX_WAITING) {
            WAITING.add(payload);
        }
    }

    /** True while requests wait to be sent: the inventory is about to change on the server. */
    public static boolean isBusy() {
        return !WAITING.isEmpty();
    }

    /** At the end of each client tick: the next waiting request, if any. */
    static void tick() {
        sentThisTick = false;
        CustomPacketPayload next = WAITING.poll();
        if (next != null) {
            ClientPlayNetworking.send(next);
            sentThisTick = true;
        }
    }

    static void clear() {
        WAITING.clear();
        sentThisTick = false;
    }

    /** One request doing what both would; null if they can't be one. */
    private static @Nullable CustomPacketPayload merge(@Nullable CustomPacketPayload last, CustomPacketPayload next) {
        if (last instanceof TakePayload a && next instanceof TakePayload b && a.flags() == b.flags()
                && a.targetSlot() == b.targetSlot() && a.radius() == b.radius() && a.kindsMask() == b.kindsMask()
                && a.lockedSlots() == b.lockedSlots()) {
            // Scroll notches on one item: one request for all of them.
            if (a.amount() > 0 && b.amount() > 0 && a.items().size() == 1 && b.items().size() == 1
                    && ItemStack.isSameItemSameComponents(a.items().getFirst(), b.items().getFirst())) {
                return new TakePayload(a.items(), a.flags(), a.radius(), a.kindsMask(), a.amount() + b.amount(), a.targetSlot(),
                        a.lockedSlots());
            }
            // Stacks of several tiles into the inventory (a shift-drag over tiles): one request, so the
            // server takes them all in one go rather than a stack a tick (Nov 9). The cursor holds one.
            if (a.amount() == 0 && b.amount() == 0 && !a.has(TakePayload.TO_CURSOR)
                    && a.items().size() + b.items().size() <= TakePayload.MAX_ITEMS) {
                List<ItemStack> items = new ArrayList<>(a.items());
                items.addAll(b.items());
                return new TakePayload(List.copyOf(items), a.flags(), a.radius(), a.kindsMask(), 0, a.targetSlot(), a.lockedSlots());
            }
        }
        if (last instanceof PutAwayPayload a && next instanceof PutAwayPayload b && a.fallback() == b.fallback()
                && a.radius() == b.radius() && a.kindsMask() == b.kindsMask()) {
            // Scroll notches on one slot: one request for all of them.
            if (a.amount() > 0 && b.amount() > 0 && a.slots().equals(b.slots())) {
                return new PutAwayPayload(a.slots(), a.amount() + b.amount(), a.fallback(), a.radius(), a.kindsMask());
            }
            // Whole stacks from several slots (a shift-double-click, a quick shift-drag): one request,
            // so the server stores them all in one go rather than a stack a tick (Nov 8).
            if (a.amount() == PutAwayPayload.WHOLE_STACK && b.amount() == PutAwayPayload.WHOLE_STACK) {
                List<Integer> slots = new ArrayList<>(a.slots());
                for (int slot : b.slots()) {
                    if (!slots.contains(slot)) slots.add(slot);
                }
                if (slots.size() <= PutAwayPayload.MAX_SLOTS) {
                    return new PutAwayPayload(List.copyOf(slots), PutAwayPayload.WHOLE_STACK, a.fallback(), a.radius(), a.kindsMask());
                }
            }
        }
        return null;
    }
}
