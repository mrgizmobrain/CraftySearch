package com.craftysearch.storage;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;

/**
 * An item type plus its data components (enchantments, potion, custom name), usable as a
 * map key. The wrapped stack has a count of 1 and must not be modified. Shared by the
 * client (memory, panel) and the server (taking by tile).
 */
public final class ItemKey {
    private final ItemStack stack;
    private final int hash;

    private ItemKey(ItemStack stack) {
        this.stack = stack;
        this.hash = ItemStack.hashItemAndComponents(stack);
    }

    /** Keeps every component. This is what memory stores. */
    public static ItemKey exact(ItemStack stack) {
        return new ItemKey(stack.copyWithCount(1));
    }

    /**
     * Drops durability and anvil repair cost, so worn copies of the same tool group into one
     * tile. Setting a component back to the item's default removes it from the patch.
     */
    public static ItemKey normalized(ItemStack stack) {
        ItemStack copy = stack.copyWithCount(1);
        if (copy.isDamaged()) copy.setDamageValue(0);
        if (copy.has(DataComponents.REPAIR_COST)) copy.set(DataComponents.REPAIR_COST, 0);
        return new ItemKey(copy);
    }

    public ItemKey normalized() {
        return normalized(stack);
    }

    /** Whether {@code stack} belongs to this (normalized) tile. Checks the item first, since normalizing copies. */
    public boolean matchesTile(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() == this.stack.getItem() && normalized(stack).equals(this);
    }

    public ItemStack stack() {
        return stack;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ItemKey other && hash == other.hash && ItemStack.isSameItemSameComponents(stack, other.stack);
    }

    @Override
    public int hashCode() {
        return hash;
    }
}
