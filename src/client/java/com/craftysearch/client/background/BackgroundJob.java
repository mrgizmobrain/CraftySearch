package com.craftysearch.client.background;

import com.craftysearch.client.memory.MemoryEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Work done through background container access (spec 4.2): visit containers one at a time,
 * then finish in the screen's own menu. {@link BackgroundJobs} runs it and handles opening,
 * closing, reopening the crafting table, pacing and cancelling.
 */
abstract class BackgroundJob {
    enum Kind {
        /** Done a step that sent a packet: run again after the pacing delay. */
        AGAIN,
        /** Waiting on the server: run again next tick. */
        WAIT,
        /** Move on: the next container, or the next phase. */
        NEXT,
        /** Finished, with an optional message. */
        DONE,
        /** Failed with a message; the runner cleans up. */
        FAIL
    }

    /** {@code message} is a full translation key, or null. */
    record Result(Kind kind, @Nullable String message) {
        static final Result AGAIN = new Result(Kind.AGAIN, null);
        static final Result WAIT = new Result(Kind.WAIT, null);
        static final Result NEXT = new Result(Kind.NEXT, null);
        static final Result DONE_NO_MESSAGE = new Result(Kind.DONE, null);

        static Result done(@Nullable String message) {
            return new Result(Kind.DONE, message);
        }

        static Result fail(String message) {
            return new Result(Kind.FAIL, message);
        }
    }

    private final List<MemoryEntry> containers;

    BackgroundJob(List<MemoryEntry> containers) {
        this.containers = containers;
    }

    /** The containers to visit, nearest or best first. Empty when only the finish step is needed. */
    final List<MemoryEntry> containers() {
        return containers;
    }

    /** The progress line while visiting container {@code index} of {@code total}, or while finishing. */
    abstract Component progress(int index, int total, boolean finishing);

    /**
     * True for a job the mod starts by itself rather than for a click (reading stacked carts).
     * It may start with no screen open, keeps going when screens open or close, closes each
     * container as soon as it's recorded (so {@link #work} isn't called), and stops without
     * a message.
     */
    boolean automatic() {
        return false;
    }

    /**
     * True for a job that only reads (Nov 11): a container that doesn't open, or opens
     * something else, is left out and the job goes on, instead of stopping it.
     */
    boolean skipsFailures() {
        return false;
    }

    /**
     * For an automatic job stepping aside for the player: a job for the containers from
     * {@code next} on, to run later, or null if there's nothing left.
     */
    @Nullable BackgroundJob rest(int next) {
        return null;
    }

    /**
     * An inventory slot holding an item on its way to or from the cursor, or -1. While the
     * job runs, that slot is drawn empty and its item is drawn on the cursor instead.
     */
    int handSlot() {
        return -1;
    }

    /** The item this job is bringing to the cursor, which counts as held already; empty if none. */
    ItemStack headingToCursor() {
        return ItemStack.EMPTY;
    }

    /** Before any container opens, in the screen's own menu. NEXT to go on. */
    Result prepare(Minecraft mc, AbstractContainerMenu menu) {
        return Result.NEXT;
    }

    /** One step in an open container: AGAIN for another step, NEXT to move on, DONE when no more are needed. */
    abstract Result work(Minecraft mc, AbstractContainerMenu container);

    /** True once no more containers are needed. */
    abstract boolean satisfied();

    /** After the containers, in the screen's (possibly reopened) menu, until DONE or FAIL. */
    abstract Result finish(Minecraft mc, AbstractContainerMenu menu, long now);
}
