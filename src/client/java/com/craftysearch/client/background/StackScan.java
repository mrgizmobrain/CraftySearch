package com.craftysearch.client.background;

import com.craftysearch.CraftySearch;
import com.craftysearch.client.config.ClientConfig;
import com.craftysearch.client.live.ServerMode;
import com.craftysearch.client.memory.ContainerMemory;
import com.craftysearch.client.memory.MemoryEntry;
import com.craftysearch.client.recipe.RecipeStorage;
import com.craftysearch.storage.ContainerKey;
import com.craftysearch.storage.ContainerKind;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Stacked carts get in each other's way, so by hand you can only ever open some of them
 * (spec 5.1). When you close a cart you opened by hand, this opens the rest of its stack in
 * the background, within reach, so they're remembered too. Client-only mode only: the
 * server reads every cart itself in server mode. Behind {@code clientOnly.scanStackedCarts}.
 */
public final class StackScan {
    /** Boxes closer than this sideways only touch, like carts in a train, and aren't stacked. */
    private static final double MARGIN = 0.05;
    /** How far around the opened cart the rest of its stack is looked for. */
    private static final double SEARCH_SIDEWAYS = 2.0;
    private static final double SEARCH_UP_DOWN = 4.0;
    /** Carts of the stack read this recently aren't read again. */
    private static final long RESCAN_AFTER_MILLIS = 5 * 60_000L;

    private static @Nullable UUID closed;

    private StackScan() {
    }

    /**
     * Two carts or boats are stacked when their boxes overlap sideways, more than just
     * touching, and overlap or touch up and down: carts squeezed onto one rail, partly or
     * fully, or resting on top of each other. A stack is everything linked this way.
     */
    public static boolean stacked(AABB a, AABB b) {
        return a.minX + MARGIN < b.maxX && b.minX + MARGIN < a.maxX
                && a.minZ + MARGIN < b.maxZ && b.minZ + MARGIN < a.maxZ
                && a.minY - MARGIN < b.maxY && b.minY - MARGIN < a.maxY;
    }

    /** The player closed the screen of a cart or boat they opened by hand. */
    public static void closedByHand(UUID cart) {
        closed = cart;
    }

    /**
     * Runs at the end of the tick the screen closed in, so the screen is gone (or another
     * one replaced it, and then nothing happens) and the close has gone to the server.
     */
    static void tick(Minecraft mc) {
        UUID cart = closed;
        if (cart == null) return;
        closed = null;
        ClientConfig config = ClientConfig.get();
        if (!config.clientOnly.scansStackedCarts() ||!config.memory.enabled || ServerMode.active()) return;
        if (mc.gui.screen() != null || mc.player == null || mc.level == null) return;
        Entity opened = mc.level.getEntity(cart);
        if (opened == null) return;
        List<MemoryEntry> others = stackedWith(mc.player, opened);
        CraftySearch.LOGGER.debug("Reading {} carts stacked with {}", others.size(), cart);
        if (!others.isEmpty()) BackgroundJobs.submitAutomatic(mc, new ScanJob(others, "craftysearch.background.scanning"));
    }

    /** The rest of the opened cart's stack, of included kinds and within reach. */
    private static List<MemoryEntry> stackedWith(LocalPlayer player, Entity opened) {
        AABB area = opened.getBoundingBox().inflate(SEARCH_SIDEWAYS, SEARCH_UP_DOWN, SEARCH_SIDEWAYS);
        List<Entity> nearby = new ArrayList<>(player.level().getEntities(opened, area,
                e -> e instanceof ContainerEntity && !e.isRemoved() && ContainerKind.of(e) != null));
        // Grow the stack from the opened cart: a cart joins once it's stacked with one already in.
        List<Entity> stack = new ArrayList<>(List.of(opened));
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Iterator<Entity> it = nearby.iterator(); it.hasNext(); ) {
                Entity candidate = it.next();
                if (stack.stream().noneMatch(s -> stacked(s.getBoundingBox(), candidate.getBoundingBox()))) continue;
                stack.add(candidate);
                it.remove();
                grew = true;
            }
        }

        ClientConfig config = ClientConfig.get();
        // Carts only change when someone opens them or a hopper empties them, and the mod records
        // every cart it opens, so ones seen a moment ago needn't be read again. A fixed window
        // since Nov 7 (the "Rescan stacks" setting was dropped).
        long seenAfter = System.currentTimeMillis() - RESCAN_AFTER_MILLIS;
        List<MemoryEntry> result = new ArrayList<>();
        for (Entity entity : stack.subList(1, stack.size())) {
            ContainerKind kind = ContainerKind.of(entity);
            if (kind == null || !config.includes(kind) || !RecipeStorage.canReach(player, entity)) continue;
            MemoryEntry known = ContainerMemory.get().entry(player.level().dimension(), ContainerKey.ofEntity(entity.getUUID()));
            if (known != null && known.lastSeenMillis() >= seenAfter) continue;
            // Only a target to open; its contents are recorded once it's open.
            result.add(new MemoryEntry(entity.blockPosition(), null, entity.getUUID(), kind, 0, new Object2IntOpenHashMap<>()));
        }
        return result;
    }
}
