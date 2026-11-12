package com.craftysearch.client.background;

import com.craftysearch.client.memory.MemoryEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Opens containers only to record them: stacked carts and boats (spec 5.1) and containers
 * someone else has used ({@link ContainerWatch}), as automatic jobs that record each
 * container when its contents arrive and close it again at once; and every container within
 * reach when the player asks (Nov 11), as their own job, shown and cancelled like a take.
 * A container that doesn't open is left out.
 */
final class ScanJob extends BackgroundJob {
    private final String progressKey;
    private final boolean automatic;
    /** Containers read, for the message at the end of a check the player asked for. */
    private int read;

    /** An automatic read. {@code progressKey}: the progress line's translation key, taking "n of total". */
    ScanJob(List<MemoryEntry> containers, String progressKey) {
        this(containers, progressKey, true);
    }

    private ScanJob(List<MemoryEntry> containers, String progressKey, boolean automatic) {
        super(containers);
        this.progressKey = progressKey;
        this.automatic = automatic;
    }

    /** Shift-click on the panel's sort button: every container within reach. */
    static ScanJob checkingAll(List<MemoryEntry> containers) {
        return new ScanJob(containers, "craftysearch.background.checking_all", false);
    }

    @Override
    boolean automatic() {
        return automatic;
    }

    @Override
    boolean skipsFailures() {
        return true;
    }

    @Override
    @Nullable BackgroundJob rest(int next) {
        List<MemoryEntry> all = containers();
        return next >= all.size() ? null : new ScanJob(List.copyOf(all.subList(next, all.size())), progressKey, automatic);
    }

    @Override
    Component progress(int index, int total, boolean finishing) {
        return Component.translatable(progressKey, index, total);
    }

    /** Reached only in the player's own check: an automatic read closes each container as it's recorded. */
    @Override
    Result work(Minecraft mc, AbstractContainerMenu container) {
        read++;
        return Result.NEXT;
    }

    @Override
    boolean satisfied() {
        return false;
    }

    @Override
    Result finish(Minecraft mc, AbstractContainerMenu menu, long now) {
        if (!automatic && mc.player != null) {
            // Left out: those that didn't open, or were out of reach by the time their turn came.
            int skipped = containers().size() - read;
            mc.player.sendOverlayMessage(skipped == 0
                    ? Component.translatable("craftysearch.background.checked_all", read)
                    : Component.translatable("craftysearch.background.checked_all_skipped", read, skipped));
        }
        return Result.DONE_NO_MESSAGE;
    }
}
