package com.craftysearch.server;

import com.craftysearch.server.StorageScanner.Found;
import com.craftysearch.storage.KeepOne;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * For one take or craft request with "Leave one" on ({@link KeepOne}): the slots that keep
 * one item, worked out once from the containers as they were when the request came in. A
 * kept slot never goes below one, so it stays the kept one while the request runs.
 */
final class KeptSlots {
    private static final KeptSlots NONE = new KeptSlots(Map.of());

    private final Map<Container, boolean[]> kept;

    private KeptSlots(Map<Container, boolean[]> kept) {
        this.kept = kept;
    }

    static KeptSlots of(List<Found> found, boolean keepOne) {
        if (!keepOne) return NONE;
        Map<Container, boolean[]> kept = new IdentityHashMap<>();
        for (Found f : found) {
            // Both halves of a double chest are one container: it keeps one of each item, not two.
            List<ItemStack> items = new ArrayList<>();
            for (Container half : f.halves()) {
                for (int i = 0; i < half.getContainerSize(); i++) items.add(half.getItem(i));
            }
            boolean[] all = KeepOne.keptSlots(items);
            int start = 0;
            for (Container half : f.halves()) {
                kept.put(half, Arrays.copyOfRange(all, start, start + half.getContainerSize()));
                start += half.getContainerSize();
            }
        }
        return new KeptSlots(kept);
    }

    /** How many of the stack in {@code slot} may be taken. */
    int takeable(Container container, int slot) {
        boolean[] slots = kept.get(container);
        return KeepOne.takeable(container.getItem(slot), slots != null && slot < slots.length && slots[slot]);
    }
}
