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
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Client-only mode on a shared server (Nov 10): memory only learns a container's contents
 * when this player opens it, so what other players (or a copper golem) do to it goes unseen.
 * The client is told when a nearby chest's or shulker box's lid moves, with how many have it
 * open, and sees a barrel's open state. When that isn't this player's own doing, the
 * remembered container is marked suspect: its tiles say so, and with
 * {@code clientOnly.recheckUsed} it's read again in the background once it's closed and
 * within reach. Hoppers, droppers and carts give no such sign.
 *
 * <p>Telling this player's own lid events from someone else's (Nov 11): each of this player's
 * openings accounts for exactly one event where the count goes up and, after closing, one
 * where it goes down ({@link Visits}). The rest of the count is other people. The server
 * repeats the count every 5 ticks while a container is open, so one opened before this player
 * came near is heard about too; repeats that change nothing are ignored.
 *
 * <p>Other players with the mod (Nov 11): their background reads are visits too, so with
 * "Recheck used" two players read the same containers back and forth. A read can't be told
 * from a take by its lid events, but a visit by someone else to a container this player was in
 * moments ago is most likely their mod reading what this player just did. Such a visit isn't
 * marked at once: if it's over within a second (and the visitor's ping), nothing changed and
 * it's let go; if it lasts longer, it's marked like any other.
 */
public final class ContainerWatch {
    /** A visit whose own closing event never came (a refused click, lag) is forgotten after this long. */
    private static final long VISIT_TIMEOUT_MILLIS = 5000;
    /** After this player closes a barrel, it may still look open for this long. */
    private static final long BARREL_GRACE_MILLIS = 1500;
    private static final int JUDGE_INTERVAL_TICKS = 5;
    private static final int BARREL_INTERVAL_TICKS = 10;
    private static final int RECHECK_INTERVAL_TICKS = 10;
    /** A container that couldn't be read again (blocked, say) is left alone this long. */
    private static final long RETRY_MILLIS = 15_000;
    /** The same container isn't read again sooner than this, however often it's used: each reading is an opening on the server. */
    private static final long RECHECK_GAP_MILLIS = 3000;
    /** Barrels are checked this far around the player. */
    private static final int BARREL_RADIUS = 48;
    /** Lid events reach players within 64 blocks; a little less, so a count is dropped before it can go stale. */
    private static final double HEARING_DISTANCE = 56;
    /** A visit by someone else that begins this soon after this player was in the container may be their mod reading it. */
    private static final long ECHO_WINDOW_MILLIS = 5000;
    /** Such a visit over within this long, plus the visitor's ping, was a background read: a person takes longer. */
    private static final long READ_MILLIS = 1000;
    /** Players this close to a container may be the one in it. */
    private static final double VISITOR_DISTANCE = 8;
    /** A barrel is looked at every half second, so its visits are timed to that. */
    private static final long BARREL_LOOK_MILLIS = BARREL_INTERVAL_TICKS * 50L;

    /**
     * This player's openings of one container, by hand or in the background, until their own
     * events are in. A job may open the same container again before the events of the last
     * time have arrived, so they're counted rather than flagged.
     */
    private static final class Visits {
        boolean inside;
        boolean byHand;
        /** Openings whose event hasn't come yet, and closings likewise. */
        int risesPending;
        int dropsPending;
        /** How many of this player's openings the last event's count includes (0 or 1). */
        int counted;
        long closedAt;
    }

    /** How many have a lid open, by the last event for that block. */
    private static final Map<BlockPos, Integer> OPENERS = new HashMap<>();
    private static final Map<BlockPos, Visits> VISITS = new HashMap<>();
    /** Used by someone else while this player was inside too; marked once this player is out, since each reading clears the mark. */
    private static final Set<BlockPos> USED = new HashSet<>();
    private static final Map<ContainerKey, Long> RECHECKED_AT = new HashMap<>();
    /** When this player last closed the container at each block. */
    private static final Map<BlockPos, Long> OURS_AT = new HashMap<>();
    /** When the visit by someone else that's going on now began. */
    private static final Map<BlockPos, Long> OTHERS_SINCE = new HashMap<>();
    /** Visits by someone else not marked yet, as they may be a read: decided when they end, or once too long for one. */
    private static final Set<BlockPos> HELD = new HashSet<>();
    private static @Nullable Level level;
    private static int ticks;

    private ContainerWatch() {
    }

    private static boolean watching() {
        return !ServerMode.active() && ClientConfig.get().memory.enabled;
    }

    /** This player opens the container at these blocks: by hand (its screen shows), or a job clicked it. */
    public static void opened(BlockPos pos, @Nullable BlockPos other, boolean byHand) {
        if (!watching()) return;
        open(pos.immutable(), byHand);
        if (other != null) open(other.immutable(), byHand);
    }

    private static void open(BlockPos pos, boolean byHand) {
        Visits visits = VISITS.computeIfAbsent(pos, p -> new Visits());
        visits.inside = true;
        visits.byHand = byHand;
        visits.risesPending++;
    }

    /** This player closes it again. Anyone still inside can change it unseen from here on. */
    public static void closed(BlockPos pos, @Nullable BlockPos other) {
        if (!watching()) return;
        long now = System.currentTimeMillis();
        close(pos.immutable(), now);
        if (other != null) close(other.immutable(), now);
    }

    static void opened(MemoryEntry entry) {
        if (!entry.isEntity()) opened(entry.pos(), entry.other(), false);
    }

    static void closed(MemoryEntry entry) {
        if (!entry.isEntity()) closed(entry.pos(), entry.other());
    }

    private static void close(BlockPos pos, long now) {
        Visits visits = VISITS.get(pos);
        if (visits == null || !visits.inside) return;
        visits.inside = false;
        visits.closedAt = now;
        visits.dropsPending++;
        OURS_AT.put(pos, now);
        // Whoever else is inside now can change it after this player's last look. Someone who came in
        // during this visit is still held back: if it's a read, it's let go when it ends.
        if (OPENERS.getOrDefault(pos, 0) - visits.counted > 0 && !HELD.contains(pos)) USED.add(pos);
    }

    /** A block event from the server: type 1 on a chest or shulker box is its lid, with how many have it open. */
    public static void lidEvent(Minecraft mc, BlockPos pos, int type, int openers) {
        if (type != 1 || mc.level == null || !watching()) return;
        ContainerKind kind = ContainerKind.of(mc.level.getBlockState(pos));
        if (kind != ContainerKind.CHEST && kind != ContainerKind.SHULKER_BOX) return;
        BlockPos at = pos.immutable();
        int before = OPENERS.getOrDefault(at, 0);
        // The server sends the count again every 5 ticks while a container is open and once more after the
        // last closing (ContainerOpenersCounter.recheckOpeners). A repeat says nothing new (Nov 11: taking the
        // one after this player's own closing for someone else marked everything they opened).
        if (openers == before) return;
        if (openers > 0) OPENERS.put(at, openers);
        else OPENERS.remove(at);

        Visits visits = VISITS.get(at);
        int mineBefore = visits == null ? 0 : visits.counted;
        if (visits != null) {
            if (openers > before && visits.risesPending > 0) {
                visits.risesPending--;
                visits.counted++;
            } else if (openers < before && visits.dropsPending > 0 && visits.counted > 0) {
                visits.dropsPending--;
                visits.counted--;
            }
            // This player's last closing is in: from here on every event is someone else's.
            if (!visits.inside && visits.risesPending == 0 && visits.dropsPending == 0) VISITS.remove(at);
        }
        int mineAfter = visits == null ? 0 : visits.counted;
        boolean othersBefore = before - mineBefore > 0;
        boolean othersNow = openers - mineAfter > 0;
        long now = System.currentTimeMillis();
        if (othersNow && !othersBefore) othersArrived(mc, at, now);
        else if (!othersNow && othersBefore) othersLeft(mc, at, now);
    }

    /** Someone else is in the container at {@code pos} now, since {@code since}, where nobody else was. */
    private static void othersArrived(Minecraft mc, BlockPos pos, long since) {
        OTHERS_SINCE.put(pos, since);
        // Right after this player's own visit it may be another player's mod reading it: wait and see.
        if (justVisited(pos, since)) HELD.add(pos);
        else mark(mc, pos);
    }

    /** Everyone else has left the container at {@code pos}. */
    private static void othersLeft(Minecraft mc, BlockPos pos, long now) {
        Long since = OTHERS_SINCE.remove(pos);
        // Not held back: it was marked when it began.
        if (!HELD.remove(pos)) return;
        // Over this soon, it was a background read: nothing changed, so there's nothing to mark or read again.
        if (since != null && now - since < readLimit(mc, pos)) return;
        used(pos);
    }

    /** This player is in the container at {@code pos}, or was moments before {@code at}. */
    private static boolean justVisited(BlockPos pos, long at) {
        Visits visits = VISITS.get(pos);
        if (visits != null && visits.inside) return true;
        Long ours = OURS_AT.get(pos);
        return ours != null && at - ours < ECHO_WINDOW_MILLIS;
    }

    /**
     * The longest a background read by someone at {@code pos} takes: about one round trip to the
     * server, so a second plus the ping (the tab list's) of the slowest player near it.
     */
    private static long readLimit(Minecraft mc, BlockPos pos) {
        int ping = 0;
        ClientPacketListener connection = mc.getConnection();
        if (connection != null && mc.level != null) {
            for (AbstractClientPlayer other : mc.level.players()) {
                if (other == mc.player || !other.blockPosition().closerThan(pos, VISITOR_DISTANCE)) continue;
                PlayerInfo info = connection.getPlayerInfo(other.getUUID());
                if (info != null) ping = Math.max(ping, info.getLatency());
            }
        }
        return READ_MILLIS + ping;
    }

    /** Someone else's visit changed it, as far as anyone can tell: marked once this player is out. */
    private static void used(BlockPos pos) {
        Visits visits = VISITS.get(pos);
        // With its screen up, their changes showed as they happened, and closing records them.
        if (visits != null && visits.inside && visits.byHand) return;
        USED.add(pos);
    }

    private static void mark(Minecraft mc, BlockPos pos) {
        ContainerMemory.get().markSuspect(mc.level.dimension(), pos);
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
        if (!USED.isEmpty()) {
            USED.removeIf(pos -> {
                Visits visits = VISITS.get(pos);
                if (visits != null && visits.inside) return false;
                mark(mc, pos);
                return true;
            });
        }
        if (ticks % JUDGE_INTERVAL_TICKS == 0) judge(mc, now);
        if (ticks % BARREL_INTERVAL_TICKS == 0) watchBarrels(mc, now);
        if (ticks % RECHECK_INTERVAL_TICKS == 0) {
            recheck(mc, now);
            OURS_AT.values().removeIf(at -> now - at > ECHO_WINDOW_MILLIS);
        }
    }

    private static void judge(Minecraft mc, long now) {
        for (Map.Entry<BlockPos, Visits> e : List.copyOf(VISITS.entrySet())) {
            Visits visits = e.getValue();
            // A closing this class wasn't told about.
            if (visits.inside && !ContainerRecorder.hasOpen(mc, e.getKey()) && !BackgroundJobs.hasOpen(e.getKey())) {
                close(e.getKey(), now);
            }
            if (!visits.inside && now - visits.closedAt > VISIT_TIMEOUT_MILLIS) VISITS.remove(e.getKey());
        }
        // Held back for longer than a read takes (or its end went unheard): someone is using it.
        for (BlockPos pos : List.copyOf(HELD)) {
            Long since = OTHERS_SINCE.get(pos);
            if (since != null && now - since < readLimit(mc, pos)) continue;
            HELD.remove(pos);
            used(pos);
        }
        if (OPENERS.isEmpty()) return;
        BlockPos at = mc.player.blockPosition();
        for (BlockPos pos : List.copyOf(OPENERS.keySet())) {
            // A lid open with no visit of this player's, and not held back, is someone else's.
            if (!VISITS.containsKey(pos) && !HELD.contains(pos)) mark(mc, pos);
            // The server only tells players nearby about lids, so further off the closing would go unheard.
            if (!mc.level.isLoaded(pos) || !pos.closerThan(at, HEARING_DISTANCE)) {
                OPENERS.remove(pos);
                OTHERS_SINCE.remove(pos);
            }
        }
    }

    /**
     * A barrel has no lid event, only its open state, which doesn't say how many have it open.
     * It's looked at twice a second, so a background read is often missed altogether.
     */
    private static void watchBarrels(Minecraft mc, long now) {
        BlockPos center = mc.player.blockPosition();
        // A copy: marking a barrel changes the memory this loops over.
        for (MemoryEntry entry : List.copyOf(ContainerMemory.get().entries(mc.level.dimension()))) {
            if (entry.kind() != ContainerKind.BARREL || !entry.isWithin(mc.level, center, BARREL_RADIUS)) continue;
            BlockPos pos = entry.pos();
            Visits visits = VISITS.get(pos);
            boolean mine = visits != null && (visits.inside || now - visits.closedAt < BARREL_GRACE_MILLIS);
            boolean others = barrelOpen(mc, pos) && !mine;
            boolean othersBefore = OTHERS_SINCE.containsKey(pos);
            // It opened some time since the last look: halfway is the best guess.
            if (others && !othersBefore) othersArrived(mc, pos, now - BARREL_LOOK_MILLIS / 2);
            else if (!others && othersBefore) othersLeft(mc, pos, now);
        }
    }

    private static boolean barrelOpen(Minecraft mc, BlockPos pos) {
        if (!mc.level.isLoaded(pos)) return false;
        BlockState state = mc.level.getBlockState(pos);
        return state.getBlock() instanceof BarrelBlock && state.getValue(BarrelBlock.OPEN);
    }

    /**
     * With {@code clientOnly.recheckUsed}: reads the suspect containers within reach again,
     * once nobody else has them open. Each is a real opening on the server, so only with
     * nothing else going on: no job, no container or table open, nothing on the cursor.
     */
    private static void recheck(Minecraft mc, long now) {
        LocalPlayer player = mc.player;
        ClientConfig config = ClientConfig.get();
        if (!config.actsClientOnly() || !config.clientOnly.rechecksUsed() || BackgroundJobs.isBusy()) return;
        if (player.containerMenu != player.inventoryMenu || !player.containerMenu.getCarried().isEmpty()
                || player.isSecondaryUseActive()) return;
        List<MemoryEntry> due = new ArrayList<>();
        for (MemoryEntry entry : StorageIndex.nearbyEntries(mc)) {
            if (!entry.suspect() || entry.isEntity() || !RecipeStorage.isReachable(player, entry)) continue;
            if (usedNow(mc, entry)) continue;
            Long last = RECHECKED_AT.get(entry.key());
            if (last != null) {
                // Read since the last try, so this is a new use: no need to hold off as after a failed try
                // (Nov 11: the wait applied either way, so a second use within it went unread for a while).
                boolean readSince = entry.lastSeenMillis() >= last;
                if (now - last < (readSince ? RECHECK_GAP_MILLIS : RETRY_MILLIS)) continue;
            }
            RECHECKED_AT.put(entry.key(), now);
            due.add(entry);
        }
        if (!due.isEmpty()) BackgroundJobs.submitAutomatic(mc, new ScanJob(due, "craftysearch.background.rechecking"));
    }

    /** Someone still has it open: reading it now would only catch them halfway. */
    private static boolean usedNow(Minecraft mc, MemoryEntry entry) {
        if (OPENERS.containsKey(entry.pos()) || (entry.other() != null && OPENERS.containsKey(entry.other()))) return true;
        return entry.kind() == ContainerKind.BARREL && barrelOpen(mc, entry.pos());
    }

    static void clear() {
        OPENERS.clear();
        VISITS.clear();
        USED.clear();
        RECHECKED_AT.clear();
        OURS_AT.clear();
        OTHERS_SINCE.clear();
        HELD.clear();
        level = null;
    }
}
