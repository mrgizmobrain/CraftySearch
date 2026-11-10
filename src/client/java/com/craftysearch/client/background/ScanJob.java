package com.craftysearch.client.background;

import com.craftysearch.client.memory.MemoryEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Opens carts and boats only to record them (spec 5.1): as an automatic job, the runner
 * records each container when its contents arrive and closes it again at once.
 */
final class ScanJob extends BackgroundJob {
    ScanJob(List<MemoryEntry> carts) {
        super(carts);
    }

    @Override
    boolean automatic() {
        return true;
    }

    @Override
    @Nullable BackgroundJob rest(int next) {
        List<MemoryEntry> carts = containers();
        return next >= carts.size() ? null : new ScanJob(List.copyOf(carts.subList(next, carts.size())));
    }

    @Override
    Component progress(int index, int total, boolean finishing) {
        return Component.translatable("craftysearch.background.scanning", index, total);
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
