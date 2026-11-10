package com.craftysearch.storage;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * "Leave one" (Nov 8): taking and crafting from storage leave one of each stackable item in
 * every container, so the container still holds it: putting away sends that item back there,
 * and a copper golem keeps sorting it there. Unstackable items (tools, armour) are taken as
 * usual, since one of them is the whole thing.
 * <p>
 * Each container keeps one item per item type, from one slot: a stack usable for crafting
 * when there is one, so crafting counts lose exactly one. The server, client-only jobs and the
 * recipe book's counts all follow this, so they agree on what can be taken.
 */
public final class KeepOne {
    private KeepOne() {
    }

    public static boolean applies(ItemStack stack) {
        return !stack.isEmpty() && stack.getMaxStackSize() > 1;
    }

    /** One container's contents in slot order (both halves of a double chest together): which slots keep one. */
    public static boolean[] keptSlots(List<ItemStack> items) {
        Map<Item, Integer> chosen = new HashMap<>();
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (!applies(stack)) continue;
            Integer at = chosen.get(stack.getItem());
            if (at == null || (!Inventory.isUsableForCrafting(items.get(at)) && Inventory.isUsableForCrafting(stack))) {
                chosen.put(stack.getItem(), i);
            }
        }
        boolean[] kept = new boolean[items.size()];
        for (int i : chosen.values()) kept[i] = true;
        return kept;
    }

    /** How many of the stack in a slot may be taken. */
    public static int takeable(ItemStack stack, boolean kept) {
        return kept ? Math.max(0, stack.getCount() - 1) : stack.getCount();
    }

    /**
     * A remembered container's counts with the kept ones taken off, by the same rule: one per
     * stackable item type, from a variant usable for crafting when there is one.
     */
    public static Object2IntMap<ItemKey> takeable(Object2IntMap<ItemKey> counts) {
        Map<Item, ItemKey> chosen = new HashMap<>();
        for (Object2IntMap.Entry<ItemKey> e : counts.object2IntEntrySet()) {
            ItemStack stack = e.getKey().stack();
            if (e.getIntValue() <= 0 || !applies(stack)) continue;
            ItemKey at = chosen.get(stack.getItem());
            if (at == null || (!Inventory.isUsableForCrafting(at.stack()) && Inventory.isUsableForCrafting(stack))) {
                chosen.put(stack.getItem(), e.getKey());
            }
        }
        if (chosen.isEmpty()) return counts;
        Object2IntMap<ItemKey> result = new Object2IntOpenHashMap<>(counts);
        for (ItemKey key : chosen.values()) {
            int left = result.getInt(key) - 1;
            if (left > 0) result.put(key, left);
            else result.removeInt(key);
        }
        return result;
    }
}
