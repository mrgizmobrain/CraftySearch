package com.craftysearch.server;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Puts a stack into containers the way a shift-click does: first onto matching stacks with
 * room, across all of them, then into empty slots. Vanilla's hopper insert fills whichever
 * slot comes first, empty or not, which splits stacks (most visibly across a double chest).
 */
final class ContainerInsert {
    private ContainerInsert() {
    }

    /** Returns what didn't fit; {@code stack} itself is left untouched. */
    static ItemStack insert(List<Container> containers, ItemStack stack) {
        ItemStack rest = stack.copy();
        for (Container container : containers) {
            for (int i = 0; i < container.getContainerSize() && !rest.isEmpty(); i++) {
                ItemStack slot = container.getItem(i);
                if (slot.isEmpty() || !ItemStack.isSameItemSameComponents(slot, rest)) continue;
                int move = Math.min(rest.getCount(), container.getMaxStackSize(slot) - slot.getCount());
                if (move <= 0) continue;
                slot.grow(move);
                rest.shrink(move);
                container.setChanged();
            }
        }
        for (Container container : containers) {
            for (int i = 0; i < container.getContainerSize() && !rest.isEmpty(); i++) {
                if (!container.getItem(i).isEmpty() || !container.canPlaceItem(i, rest)) continue;
                container.setItem(i, rest.split(container.getMaxStackSize(rest)));
                container.setChanged();
            }
        }
        return rest.isEmpty() ? ItemStack.EMPTY : rest;
    }
}
