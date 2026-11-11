package com.craftysearch.client.background;

import com.craftysearch.client.memory.MemoryEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Opens containers only to record them: stacked carts and boats (spec 5.1), and containers
 * someone else has used ({@link ContainerWatch}). As an automatic job, the runner records
 * each container when its contents arrive and closes it again at once.
 */
final class ScanJob extends BackgroundJob {
    private final String progressKey;

    /** {@code progressKey}: the progress line's translation key, taking "n of total". */
    ScanJob(List<MemoryEntry> containers, String progressKey) {
        super(containers);
        this.progressKey = progressKey;
    }

    @Override
    boolean automatic() {
        return true;
    }

    @Override
    @Nullable BackgroundJob rest(int next) {
        List<MemoryEntry> all = containers();
        return next >= all.size() ? null : new ScanJob(List.copyOf(all.subList(next, all.size())), progressKey);
    }

    @Override
    Component progress(int index, int total, boolean finishing) {
        return Component.translatable(progressKey, index, total);
    }

    @Override
    Result work(Minecraft mc, AbstractContainerMenu container) {
        return Result.NEXT;
    }

    @Override
    boolean satisfied() {
        return false;
    }

    @Override
    Result finish(Minecraft mc, AbstractContainerMenu menu, long now) {
        return Result.DONE_NO_MESSAGE;
    }
}
