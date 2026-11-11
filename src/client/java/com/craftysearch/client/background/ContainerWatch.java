package com.craftysearch.client.background;

import com.craftysearch.client.config.ClientConfig;
import com.craftysearch.client.live.ServerMode;
import com.craftysearch.client.memory.ContainerMemory;
import com.craftysearch.client.memory.ContainerRecorder;
import com.craftysearch.client.memory.MemoryEntry;
import com.craftysearch.client.recipe.RecipeStorage;
import com.craftysearch.client.storage.StorageIndex;
import com.craftysearch.storage.ContainerKey;
import com.craftysearch.storage.ContainerKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Client-only mode on a shared server (Nov 10): memory only learns a container's contents
 * when this player opens it, so what other players (or a copper golem) do to it goes unseen.
 * The client is told when a nearby chest's or shulker box's lid moves, with how many have it
 * open, and sees a barrel's open state. When that isn't this player's own doing, the
 * remembered container is marked suspect: its tiles say so, and with
 * {@code clientOnly.recheckUsed} it's read again in the background once it's closed and
 * within reach. Hoppers, droppers and carts give no such sign.
 */
public final class ContainerWatch {
    /** After this player closes a container, the lid event for their own opening may still arrive. */
    private static final long GRACE_MILLIS = 1500;
    private static final int JUDGE_INTERVAL_TICKS = 5;
    private static final int BARREL_INTERVAL_TICKS = 10;
    private static final int RECHECK_INTERVAL_TICKS = 20;
    /** A container that couldn't be read again (blocked, say) is left alone this long. */
    private static final long RETRY_MILLIS = 15_000;
    /** Barrels are checked this far around the player. */
    private static final int BARREL_RADIUS = 48;

    /** How many have a lid open, by the last event for that block. */
    private static final Map<BlockPos, Integer> OPENERS = new HashMap<>();
    /** When this player last opened, was inside or closed the container at a block. */
    private static final Map<BlockPos, Long> OURS_AT = new HashMap<>();
    private static final Map<ContainerKey, Long> RECHECKED_AT = new HashMap<>();
    /** Lid events reach players within 64 blocks; a little less, so a count is dropped before it can go stale. */
    private static final double HEARING_DISTANCE = 56;
    private static @Nullable Level level;
    private static int ticks;

    private ContainerWatch() {
    }

    private static boolean watching() {
        return !ServerMode.active() && ClientConfig.get().memory.enabled;
    }

    /** This player opens or closes the container at these blocks, by hand or in the background. */
    public static void ours(BlockPos pos, @Nullable BlockPos other) {
        long now = System.currentTimeMillis();
        OURS_AT.put(pos.immutable(), now);
        if (other != null) OURS_AT.put(other.immutable(), now);
    }

    static void ours(MemoryEntry entry) {
        if (!entry.isEntity()) ours(entry.pos(), entry.other());
    }

    /** A block event from the server: type 1 on a chest or shulker box is its lid, with how many have it open. */
    public static void lidEvent(Minecraft mc, BlockPos pos, int type, int openers) {
        if (type != 1 || mc.level == null || !watching()) return;
        ContainerKind kind = ContainerKind.of(mc.level.getBlockState(pos));
        if (kind != ContainerKind.CHEST && kind != ContainerKind.SHULKER_BOX) return;
        BlockPos at = pos.immutable();
        if (openers > 0) OPENERS.put(at, openers);
        else OPENERS.remove(at);
        judge(mc, at, System.currentTimeMillis());
    }

    /** How many others have the container at {@code pos} open, as far as the last event said. */
    private static int others(Minecraft mc, BlockPos pos, long now) {
        int openers = OPENERS.getOrDefault(pos, 0);
        if (openers <= 0) return 0;
        if (inside(mc, pos)) {
            OURS_AT.put(pos, now);
            return openers - 1;
        }
        // Just closed (or just opened in the background and closed again): the count may still include this player.
        Long ours = OURS_AT.get(pos);
        return ours != null && now - ours < GRACE_MILLIS ? 0 : openers;
    }

    private static boolean inside(Minecraft mc, BlockPos pos) {
        return ContainerRecorder.hasOpen(mc, pos) || BackgroundJobs.hasOpen(pos);
    }

    private static void judge(Minecraft mc, BlockPos pos, long now) {
        if (others(mc, pos, now) > 0) ContainerMemory.get().markSuspect(mc.level.dimension(), pos);
    }

    static void tick(Minecraft mc) {
        if (mc.level == null || mc.player == null || !watching()) return;
        if (mc.level != level) {
            // Another dimension: nothing heard about the last one's lids still holds.
            clear();
            level = mc.level;
        }
        ticks++;
        long now = System.currentTimeMillis();
        // A lid still open once the grace has passed is someone else's.
        if (ticks % JUDGE_INTERVAL_TICKS == 0 && !OPENERS.isEmpty()) {
            BlockPos at = mc.player.blockPosition();
            for (BlockPos pos : List.copyOf(OPENERS.keySet())) {
                judge(mc, pos, now);
                // The server only tells players nearby about lids, so further off the closing would go unheard.
                if (!mc.level.isLoaded(pos) || !pos.closerThan(at, HEARING_DISTANCE)) OPENERS.remove(pos);
            }
        }
        if (ticks % BARREL_INTERVAL_TICKS == 0) watchBarrels(mc, now);
        if (ticks % RECHECK_INTERVAL_TICKS == 0) {
            OURS_AT.values().removeIf(at -> now - at > 10 * GRACE_MILLIS);
            recheck(mc, now);
        }
    }

    /** A barrel has no lid event, only its open state, which doesn't say how many have it open. */
    private static void watchBarrels(Minecraft mc, long now) {
        BlockPos center = mc.player.blockPosition();
        for (MemoryEntry entry : ContainerMemory.get().entries(mc.level.dimension())) {
            if (entry.kind() != ContainerKind.BARREL || entry.suspect() || !entry.isWithin(mc.level, center, BARREL_RADIUS)) continue;
            BlockPos pos = entry.pos();
            if (!mc.level.isLoaded(pos)) continue;
            BlockState state = mc.level.getBlockState(pos);
            if (!(state.getBlock() instanceof BarrelBlock) || !state.getValue(BarrelBlock.OPEN)) continue;
            if (inside(mc, pos)) {
                OURS_AT.put(pos, now);
                continue;
            }
            Long ours = OURS_AT.get(pos);
            if (ours != null && now - ours < GRACE_MILLIS) continue;
            ContainerMemory.get().markSuspect(mc.level.dimension(), pos);
        }
    }

    /**
     * With {@code clientOnly.recheckUsed}: reads the suspect containers within reach again,
     * once nobody else has them open. Each is a real opening on the server, so only with
     * nothing else going on: no job, no container or table open, nothing on the cursor.
     */
    private static void recheck(Minecraft mc, long now) {
        LocalPlayer player = mc.player;
        if (!ClientConfig.get().clientOnly.recheckUsed || BackgroundJobs.isBusy()) return;
        if (player.containerMenu != player.inventoryMenu || !player.containerMenu.getCarried().isEmpty()
                || player.isSecondaryUseActive()) return;
        List<MemoryEntry> due = new ArrayList<>();
        for (MemoryEntry entry : StorageIndex.nearbyEntries(mc)) {
            if (!entry.suspect() || entry.isEntity() || !RecipeStorage.isReachable(player, entry)) continue;
            if (usedNow(mc, entry, now)) continue;
            Long last = RECHECKED_AT.get(entry.key());
            if (last != null && now - last < RETRY_MILLIS) continue;
            RECHECKED_AT.put(entry.key(), now);
            due.add(entry);
        }
        if (!due.isEmpty()) BackgroundJobs.submitAutomatic(mc, new ScanJob(due, "craftysearch.background.rechecking"));
    }

    /** Someone still has it open: reading it now would only catch them halfway. */
    private static boolean usedNow(Minecraft mc, MemoryEntry entry, long now) {
        if (others(mc, entry.pos(), now) > 0 || (entry.other() != null && others(mc, entry.other(), now) > 0)) return true;
        if (entry.kind() != ContainerKind.BARREL || !mc.level.isLoaded(entry.pos())) return false;
        BlockState state = mc.level.getBlockState(entry.pos());
        return state.getBlock() instanceof BarrelBlock && state.getValue(BarrelBlock.OPEN);
    }

    static void clear() {
        OPENERS.clear();
        OURS_AT.clear();
        RECHECKED_AT.clear();
        level = null;
    }
}
