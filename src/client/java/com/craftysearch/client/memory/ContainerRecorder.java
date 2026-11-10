package com.craftysearch.client.memory;

import com.craftysearch.client.background.StackScan;
import com.craftysearch.client.config.ClientConfig;
import com.craftysearch.client.highlight.Highlights;
import com.craftysearch.storage.ContainerKey;
import com.craftysearch.storage.ContainerKind;
import com.craftysearch.storage.ItemKey;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Records a container's contents while its screen is open. The server never says which
 * block or entity a menu belongs to, so the block or cart the player last right-clicked is
 * matched to the next container screen, and the menu's slot count must fit it.
 */
public final class ContainerRecorder {
    private static final long MATCH_WINDOW_MILLIS = 3000;
    private static final int SNAPSHOT_INTERVAL_TICKS = 10;

    private static @Nullable ContainerKey pending;
    private static long pendingAt;
    /** The block last right-clicked, never consumed: the crafting table hook reads it too. */
    private static @Nullable BlockPos lastUsedPos;
    private static long lastUsedAt;
    private static final Map<Screen, Tracked> TRACKED = new WeakHashMap<>();

    /** {@code pos} is a cart's block when it opened; snapshots look up where it is now. */
    private record Tracked(ResourceKey<Level> dimension, ContainerKey key, BlockPos pos, @Nullable BlockPos other,
                           ContainerKind kind, List<Slot> slots) {
    }

    private ContainerRecorder() {
    }

    public static void register() {
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide()) {
                lastUsedPos = hit.getBlockPos();
                lastUsedAt = System.currentTimeMillis();
                expect(ContainerKey.ofBlock(hit.getBlockPos()));
            }
            return InteractionResult.PASS;
        });
        // A right-click on a stack of carts names one entity, and that's the one the server opens.
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (level.isClientSide()) {
                lastUsedPos = null;
                expect(ContainerKind.of(entity) != null ? ContainerKey.ofEntity(entity.getUUID()) : null);
            }
            return InteractionResult.PASS;
        });
        ScreenEvents.AFTER_INIT.register(ContainerRecorder::afterInit);
    }

    /** The inventory key while riding a chest boat opens the boat's chest (LocalPlayerMixin). */
    public static void openingVehicleInventory(@Nullable Entity vehicle) {
        lastUsedPos = null;
        expect(vehicle != null && ContainerKind.of(vehicle) != null ? ContainerKey.ofEntity(vehicle.getUUID()) : null);
    }

    /** A container opened from the panel (Nov 9): the next container screen is this one. */
    public static void expectOpening(ContainerKey key) {
        lastUsedPos = null;
        expect(key);
    }

    private static void expect(@Nullable ContainerKey key) {
        pending = key;
        pendingAt = System.currentTimeMillis();
    }

    /** The block the player right-clicked in the last few seconds, if any. */
    public static @Nullable BlockPos recentlyUsedBlock() {
        return System.currentTimeMillis() - lastUsedAt <= MATCH_WINDOW_MILLIS ? lastUsedPos : null;
    }

    /**
     * For a container opened in the background (no screen): checks that the menu fits the
     * block or cart {@code target} names, and records its contents if memory is on. Returns
     * false if the menu doesn't fit, such as a plugin GUI opened by the click.
     */
    public static boolean recordHidden(Minecraft mc, MemoryEntry target, AbstractContainerMenu menu) {
        if (mc.level == null) return false;
        Tracked tracked = target.isEntity() ? trackEntity(mc, target.key(), menu) : trackBlock(mc, target.pos(), menu);
        if (tracked == null) return false;
        if (ClientConfig.get().memory.enabled && ClientConfig.get().includes(tracked.kind())) snapshot(mc, tracked, menu);
        return true;
    }

    private static void afterInit(Minecraft mc, Screen screen, int width, int height) {
        // Per-screen events are recreated on every init (including resizes), so re-register each time.
        Tracked tracked = TRACKED.get(screen);
        if (tracked == null) {
            tracked = startTracking(mc, screen);
            if (tracked == null) return;
            TRACKED.put(screen, tracked);
        }
        AbstractContainerMenu menu = ((AbstractContainerScreen<?>) screen).getMenu();
        Tracked t = tracked;
        int[] ticks = {0};
        ScreenEvents.afterTick(screen).register(s -> {
            if (++ticks[0] % SNAPSHOT_INTERVAL_TICKS == 0) snapshot(mc, t, menu);
        });
        ScreenEvents.remove(screen).register(s -> {
            snapshot(mc, t, menu);
            TRACKED.remove(s);
            // Carts stacked with this one can't be picked by hand; read them in the background.
            if (t.key().isEntity()) StackScan.closedByHand(t.key().entity());
        });
    }

    private static @Nullable Tracked startTracking(Minecraft mc, Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) return null;
        ContainerKey key = pending;
        if (key == null || System.currentTimeMillis() - pendingAt > MATCH_WINDOW_MILLIS) return null;
        if (mc.level == null) return null;
        pending = null;

        AbstractContainerMenu menu = containerScreen.getMenu();
        Tracked tracked = key.isEntity() ? trackEntity(mc, key, menu) : trackBlock(mc, key.block(), menu);
        if (tracked == null) return null;
        Highlights.containerOpened(key);
        if (!ClientConfig.get().memory.enabled || !ClientConfig.get().includes(tracked.kind())) return null;
        return tracked;
    }

    /** The container at {@code pos}, if the menu's slot count fits it. */
    private static @Nullable Tracked trackBlock(Minecraft mc, BlockPos pos, AbstractContainerMenu menu) {
        BlockState state = mc.level.getBlockState(pos);
        ContainerKind kind = ContainerKind.of(state);
        if (kind == null) return null;
        BlockPos other = ContainerKind.otherHalf(pos, state);
        List<Slot> slots = containerSlots(menu);
        if (slots.size() != kind.expectedSlots(other != null)) return null;
        return new Tracked(mc.level.dimension(), ContainerKey.ofBlock(pos), pos, other, kind, slots);
    }

    /** The cart or boat {@code key} names, if the client has it and the menu's slot count fits it. */
    private static @Nullable Tracked trackEntity(Minecraft mc, ContainerKey key, AbstractContainerMenu menu) {
        Entity entity = mc.level.getEntity(key.entity());
        ContainerKind kind = entity == null ? null : ContainerKind.of(entity);
        if (kind == null) return null;
        List<Slot> slots = containerSlots(menu);
        if (slots.size() != kind.expectedSlots(false)) return null;
        return new Tracked(mc.level.dimension(), key, entity.blockPosition(), null, kind, slots);
    }

    /** The menu's slots that aren't the player's own inventory. */
    public static List<Slot> containerSlots(AbstractContainerMenu menu) {
        return menu.slots.stream().filter(slot -> !(slot.container instanceof Inventory)).toList();
    }

    private static void snapshot(Minecraft mc, Tracked tracked, AbstractContainerMenu menu) {
        // State id 0 means the server hasn't sent the contents yet, and an empty
        // snapshot would wipe what we remembered.
        if (menu.getStateId() == 0) return;
        Object2IntMap<ItemKey> counts = new Object2IntOpenHashMap<>();
        for (Slot slot : tracked.slots()) {
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty()) counts.mergeInt(ItemKey.exact(stack), stack.getCount(), Integer::sum);
        }
        if (tracked.key().isEntity()) {
            // A cart can roll on while its screen is open.
            Entity entity = mc.level == null ? null : mc.level.getEntity(tracked.key().entity());
            BlockPos pos = entity != null ? entity.blockPosition() : tracked.pos();
            ContainerMemory.get().recordEntity(tracked.dimension(), tracked.key().entity(), pos, tracked.kind(), counts);
        } else {
            ContainerMemory.get().record(tracked.dimension(), tracked.pos(), tracked.other(), tracked.kind(), counts);
        }
    }
}
