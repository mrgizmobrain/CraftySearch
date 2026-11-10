package com.craftysearch.storage;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import java.util.HashSet;
import java.util.Set;

/**
 * How well a container suits an item being put away, by what it already holds (Nov 8), for
 * {@link PutAwayRule}: lower is better, {@link #NONE} for no link at all. Server and client
 * both use it, on live slots and remembered counts.
 * <p>
 * A loose item: the item loose ({@link #SAME}), else inside a shulker box ({@link #IN_BOX}).
 * A shulker box: boxes sharing an item with it ({@link #SAME}), else its items loose
 * ({@link #IN_BOX}), else any shulker box ({@link #ANY_BOX}).
 */
public final class PutAwayMatch {
    public static final int SAME = 0;
    public static final int IN_BOX = 1;
    public static final int ANY_BOX = 2;
    public static final int NONE = Integer.MAX_VALUE;

    private PutAwayMatch() {
    }

    public static boolean isShulkerBox(ItemStack stack) {
        return stack.getItem() instanceof BlockItem item && item.getBlock() instanceof ShulkerBoxBlock;
    }

    /** {@code held}: what the container holds, a stack per slot or per kind of item. */
    public static int of(ItemStack moving, Iterable<ItemStack> held) {
        if (isShulkerBox(moving)) {
            Set<Item> carried = itemsIn(moving);
            int best = NONE;
            for (ItemStack stack : held) {
                if (stack.isEmpty()) continue;
                if (isShulkerBox(stack)) {
                    if (!carried.isEmpty() && shares(stack, carried)) return SAME;
                    best = Math.min(best, ANY_BOX);
                } else if (carried.contains(stack.getItem())) {
                    best = Math.min(best, IN_BOX);
                }
            }
            return best;
        }
        int best = NONE;
        Set<Item> one = Set.of(moving.getItem());
        for (ItemStack stack : held) {
            if (stack.isEmpty()) continue;
            if (stack.getItem() == moving.getItem()) return SAME;
            if (best > IN_BOX && shares(stack, one)) best = IN_BOX;
        }
        return best;
    }

    private static Set<Item> itemsIn(ItemStack box) {
        Set<Item> items = new HashSet<>();
        ItemContainerContents contents = box.get(DataComponents.CONTAINER);
        if (contents != null) contents.nonEmptyItemCopyStream().forEach(inner -> items.add(inner.getItem()));
        return items;
    }

    /** Whether the box (or any item with container contents) holds one of {@code items}. */
    private static boolean shares(ItemStack box, Set<Item> items) {
        ItemContainerContents contents = box.get(DataComponents.CONTAINER);
        return contents != null && contents.nonEmptyItemCopyStream().anyMatch(inner -> items.contains(inner.getItem()));
    }
}
