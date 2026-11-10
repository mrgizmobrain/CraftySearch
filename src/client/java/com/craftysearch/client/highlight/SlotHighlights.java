package com.craftysearch.client.highlight;

import com.craftysearch.client.config.ClientConfig;
import com.craftysearch.client.storage.StorageIndex;
import com.craftysearch.storage.ItemKey;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Marks the searched item's slots in a container opened while a search is active, and with
 * {@code shulkerContents} on, the shulker boxes holding it (Nov 8). The screen keeps its
 * highlight until it closes, even if the world outlines expire meanwhile.
 */
public final class SlotHighlights {
    private static final int RGB = 0x4CFF4C;
    private static final int TINT = 0x50000000 | RGB;
    private static final int EDGE = 0xC0000000 | RGB;
    private static final Map<Screen, Latched> LATCHED = new WeakHashMap<>();

    /**
     * The search a screen opened with, and which box stacks hold it: worked out once per
     * stack, as slots are drawn every frame. Stacks don't compare by contents, so this goes by
     * the stack object, and a box whose contents change is a new stack.
     */
    private record Latched(ItemKey target, Map<ItemStack, Boolean> boxes) {
    }

    private SlotHighlights() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register(SlotHighlights::afterInit);
    }

    private static void afterInit(Minecraft mc, Screen screen, int width, int height) {
        // Only storage screens: the inventory, crafting table, furnaces and creative tabs don't hold storage.
        if (!(screen instanceof AbstractContainerScreen<?>)
                || screen instanceof AbstractRecipeBookScreen<?>
                || screen instanceof CreativeModeInventoryScreen
                || LATCHED.containsKey(screen)) return;
        ItemKey target = Highlights.target();
        if (target != null) LATCHED.put(screen, new Latched(target, new WeakHashMap<>()));
    }

    /**
     * Called from the slot renderer before the item and count are drawn, with the pose
     * already moved to the container's corner, so slot coordinates are used as they are.
     */
    public static void extractBehind(Screen screen, GuiGraphicsExtractor g, Slot slot) {
        Latched latched = LATCHED.get(screen);
        if (latched == null || slot.container instanceof Inventory) return;
        ItemStack stack = slot.getItem();
        if (stack.isEmpty() || !(holds(stack, latched.target()) || holdsInBox(stack, latched))) return;

        int x = slot.x;
        int y = slot.y;
        g.fill(x, y, x + 16, y + 16, TINT);
        g.fill(x, y, x + 16, y + 1, EDGE);
        g.fill(x, y + 15, x + 16, y + 16, EDGE);
        g.fill(x, y + 1, x + 1, y + 15, EDGE);
        g.fill(x + 15, y + 1, x + 16, y + 15, EDGE);
    }

    private static boolean holds(ItemStack stack, ItemKey target) {
        // Cheap item check first; normalizing copies the stack.
        return stack.getItem() == target.stack().getItem() && ItemKey.normalized(stack).equals(target);
    }

    private static boolean holdsInBox(ItemStack stack, Latched latched) {
        if (!ClientConfig.get().shulkerContents) return false;
        return latched.boxes().computeIfAbsent(stack, box -> {
            boolean[] found = {false};
            StorageIndex.forEachInBox(box, 1, (inner, count) -> found[0] |= latched.target().matchesTile(inner));
            return found[0];
        });
    }
}
