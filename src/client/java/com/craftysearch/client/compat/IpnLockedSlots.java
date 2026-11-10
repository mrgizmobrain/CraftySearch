package com.craftysearch.client.compat;

import com.craftysearch.CraftySearch;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.entity.player.Inventory;
import org.jspecify.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Inventory Profiles Next's locked slots (Nov 8): a locked stack is never put away, by
 * shift-click, drag, scroll or shift-double-click, and nothing taken from storage lands in a
 * locked slot (vanilla's quick-move and the server's inventory fill would use the first empty
 * one). In server mode put-away and take go to the server as requests, past IPN's own guard on
 * clicks, so the requests carry the locked slots ({@link #mask}).
 * IPN has no API for it, so its lock handler is looked up by name; without IPN, or if that
 * changes, nothing is locked.
 */
public final class IpnLockedSlots {
    private static final String HANDLER = "org.anti_ad.mc.ipnext.event.LockSlotsHandler";

    private static boolean looked;
    private static @Nullable Object handler;
    private static @Nullable MethodHandle isSlotLocked;

    private IpnLockedSlots() {
    }

    /** Whether IPN has inventory slot {@code inventorySlot} (hotbar 0-8, the rows above 9-35) locked. */
    public static boolean isLocked(int inventorySlot) {
        if (!looked) lookUp();
        if (handler == null || isSlotLocked == null || inventorySlot < 0) return false;
        try {
            return (boolean) isSlotLocked.invoke(handler, inventorySlot);
        } catch (Throwable e) {
            CraftySearch.LOGGER.warn("Couldn't ask Inventory Profiles Next about locked slots; treating none as locked", e);
            handler = null;
            return false;
        }
    }

    /** The locked main-inventory slots (0-35) as bits, for the server; 0 without IPN or with none locked. */
    public static long mask() {
        if (!looked) lookUp();
        if (handler == null) return 0;
        long mask = 0;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            if (isLocked(i)) mask |= 1L << i;
        }
        return mask;
    }

    public static boolean anyLocked() {
        return mask() != 0;
    }

    private static void lookUp() {
        looked = true;
        if (!FabricLoader.getInstance().isModLoaded("inventoryprofilesnext")) return;
        try {
            Class<?> type = Class.forName(HANDLER);
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            handler = lookup.findStaticGetter(type, "INSTANCE", type).invoke();
            isSlotLocked = lookup.findVirtual(type, "isSlotLocked", MethodType.methodType(boolean.class, int.class));
        } catch (Throwable e) {
            CraftySearch.LOGGER.warn("Inventory Profiles Next is installed but its locked slots weren't found; they may be put away", e);
            handler = null;
        }
    }
}
