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
 * where it goes down ({@link Visits}). Every other change of the count is someone else's.
 * The server repeats the count every 5 ticks while a container is open, so one opened before
 * this player came near is heard about too; repeats that change nothing are ignored.
 *
 * <p>Two players with the mod and "Recheck used" (Nov 11): each one's background read is
 * someone else's visit to the other, so they read the same containers back and forth for as
 * long as they stood there. A read can't be told from a take by its lid events, so each
 * visit by someone else is judged when it ends: a short one (under a second, the length of a
 * background job) that comes within seconds of this player's own recheck finding nothing
 * changed is most likely the other player's recheck of that read, an "echo". It still marks
 * the container, but doesn't get it read again; any other visit does.
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
    /** A visit by someone else shorter than this is a background job's length: a read, or a quick take. */
    private static final long SHORT_VISIT_MILLIS = 1000;
    /** A short visit this soon after this player's recheck found nothing changed is taken for an echo of that read. */
    private static final long ECHO_WINDOW_MILLIS = 10_000;
    /** What's known about a block's visits is dropped after this long. */
    private static final long FORGET_MILLIS = 10 * 60_000;

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
    /** When someone else's visit to a chest or shulker box began, while it lasts. */
    private static final Map<BlockPos, Long> OPENED_AT = new HashMap<>();
    /** How many looks in a row found a barrel open with this player not inside: one is a short visit. */
    private static final Map<BlockPos, Integer> BARREL_SEEN = new HashMap<>();
    /** When someone else's last visit that should be read again ended, and the last echo. */
    private static final Map<BlockPos, Long> REAL_AT = new HashMap<>();
    private static final Map<BlockPos, Long> ECHO_AT = new HashMap<>();
    /** When this player's last recheck of the container found nothing changed. */
    private static final Map<BlockPos, Long> QUIET_AT = new HashMap<>();
    /** A recheck asked for, with what was remembered then, until a reading shows whether anything changed. */
    private record Pending(MemoryEntry before, long at) {
    }
    private static final Map<ContainerKey, Pending> PENDING = new HashMap<>();
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
        // Whoever else is inside now can change it after this player's last look.
        if (OPENERS.getOrDefault(pos, 0) - visits.counted > 0) USED.add(pos);
    }

    /** A block event from the server: type 1 on a chest or shulker box is its lid, with how many have it open. */
    public static void lidEvent(Minecraft mc, BlockPos pos, int type, int openers) {
        if (type != 1 || mc.level == null || !watching()) return;
        ContainerKind kind = ContainerKind.of(mc.level.getBlockState(pos));
        if (kind != ContainerKind.CHEST && kind != ContainerKind.SHULKER_BOX) return;
        BlockPos at = pos.immutable();
        long now = System.currentTimeMillis();
        int before = OPENERS.getOrDefault(at, 0);
        if (openers > 0) OPENERS.put(at, openers);
        else OPENERS.remove(at);
        Long openedAt = openers == 0 ? OPENED_AT.remove(at) : null;

        Visits visits = VISITS.get(at);
        if (visits == null) {
            // Not this player's: someone has it open, or just closed it. The server sends the count again
            // every 5 ticks while a container is open and once more after the last closing
            // (ContainerOpenersCounter.recheckOpeners), so a 0 after a 0 is only that repeat: it follows this
            // player's own visits too (Nov 11: taking it for someone else marked everything they opened).
            if (openers > 0 && before == 0) OPENED_AT.putIfAbsent(at, now);
            if (openers > 0 || before > 0) mark(mc, at);
            // The last one closed it: a visit of unknown length (opened before this player came near) isn't short.
            if (openers == 0 && before > 0) visitEnded(at, openedAt != null && now - openedAt < SHORT_VISIT_MILLIS, now);
        } else if (openers > before) {
            if (visits.risesPending > 0) {
                visits.risesPending--;
                visits.counted++;
            } else if (!(visits.inside && visits.byHand)) {
                // Someone else opened it too. (With its screen up their changes show, and closing reads it.)
                USED.add(at);
            }
        } else if (openers < before && visits.dropsPending > 0 && visits.counted > 0) {
            visits.dropsPending--;
            visits.counted--;
            // This player's last closing is in: from here on every event is someone else's.
            if (!visits.inside && visits.risesPending == 0 && visits.dropsPending == 0) VISITS.remove(at);
        }
    }

    private static void mark(Minecraft mc, BlockPos pos) {
        ContainerMemory.get().markSuspect(mc.level.dimension(), pos);
    }

    /** Someone else's visit to the container at {@code pos} is over: an echo of this player's own read, or one to read again. */
    private static void visitEnded(BlockPos pos, boolean shortVisit, long now) {
        Long quiet = QUIET_AT.get(pos);
        boolean echo = shortVisit && quiet != null && now - quiet < ECHO_WINDOW_MILLIS;
        (echo ? ECHO_AT : REAL_AT).put(pos, now);
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
                // Overlapping this player's own visit, its length is unknown: read it again.
                REAL_AT.put(pos, now);
                return true;
            });
        }
        // Every tick: the other player's echo of a read can come back within half a second.
        resolvePending(mc, now);
        if (ticks % JUDGE_INTERVAL_TICKS == 0) judge(mc, now);
        // Barrels first: a barrel found closed this tick is judged before the recheck looks at it.
        if (ticks % BARREL_INTERVAL_TICKS == 0) watchBarrels(mc, now);
        if (ticks % RECHECK_INTERVAL_TICKS == 0) {
            recheck(mc, now);
            forgetOld(now);
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
        if (OPENERS.isEmpty()) return;
        BlockPos at = mc.player.blockPosition();
        for (BlockPos pos : List.copyOf(OPENERS.keySet())) {
            // A lid open with no visit of this player's is someone else's.
            if (!VISITS.containsKey(pos)) mark(mc, pos);
            // The server only tells players nearby about lids, so further off the closing would go unheard.
            if (!mc.level.isLoaded(pos) || !pos.closerThan(at, HEARING_DISTANCE)) {
                OPENERS.remove(pos);
                OPENED_AT.remove(pos);
            }
        }
    }

    /**
     * A barrel has no lid event, only its open state, which doesn't say how many have it open.
     * It's looked at twice a second, so a background read is often missed altogether; one seen
     * open at a single look was a short visit.
     */
    private static void watchBarrels(Minecraft mc, long now) {
        BlockPos center = mc.player.blockPosition();
        for (MemoryEntry entry : ContainerMemory.get().entries(mc.level.dimension())) {
            if (entry.kind() != ContainerKind.BARREL || !entry.isWithin(mc.level, center, BARREL_RADIUS)) continue;
            BlockPos pos = entry.pos();
            if (!barrelOpen(mc, pos)) {
                Integer seen = BARREL_SEEN.remove(pos);
                if (seen != null) visitEnded(pos, seen < 2, now);
                continue;
            }
            Visits visits = VISITS.get(pos);
            if (visits != null && (visits.inside || now - visits.closedAt < BARREL_GRACE_MILLIS)) {
                BARREL_SEEN.remove(pos);
                continue;
            }
            BARREL_SEEN.merge(pos, 1, Integer::sum);
            mark(mc, pos);
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
            if (usedNow(mc, entry) || onlyEchoes(entry)) continue;
            Long last = RECHECKED_AT.get(entry.key());
            if (last != null) {
                // Read since the last try, so this is a new use: no need to hold off as after a failed try
                // (Nov 11: the wait applied either way, so a second use within it went unread for a while).
                boolean readSince = entry.lastSeenMillis() >= last;
                if (now - last < (readSince ? RECHECK_GAP_MILLIS : RETRY_MILLIS)) continue;
            }
            RECHECKED_AT.put(entry.key(), now);
            PENDING.put(entry.key(), new Pending(entry, now));
            due.add(entry);
        }
        if (!due.isEmpty()) BackgroundJobs.submitAutomatic(mc, new ScanJob(due, "craftysearch.background.rechecking"));
    }

    /**
     * True when every visit by others since the container was last read was an echo of this
     * player's own read: it stays marked, but isn't read again (the read would only be echoed
     * back). A container marked before this session, or by a visit that went unjudged, has no
     * echo and is read as before.
     */
    private static boolean onlyEchoes(MemoryEntry entry) {
        long seen = entry.lastSeenMillis();
        return after(ECHO_AT, entry, seen) && !after(REAL_AT, entry, seen);
    }

    private static boolean after(Map<BlockPos, Long> times, MemoryEntry entry, long since) {
        Long at = times.get(entry.pos());
        if (at != null && at > since) return true;
        Long other = entry.other() == null ? null : times.get(entry.other());
        return other != null && other > since;
    }

    /** Learns from each recheck whether it found anything changed, once the container has been read. */
    private static void resolvePending(Minecraft mc, long now) {
        if (PENDING.isEmpty()) return;
        PENDING.entrySet().removeIf(e -> {
            Pending pending = e.getValue();
            MemoryEntry current = ContainerMemory.get().entry(mc.level.dimension(), e.getKey());
            if (current == null) return true;
            if (current.lastSeenMillis() < pending.at()) return now - pending.at() > RETRY_MILLIS;
            boolean unchanged = current.counts().equals(pending.before().counts());
            quiet(current.pos(), unchanged, current.lastSeenMillis());
            if (current.other() != null) quiet(current.other(), unchanged, current.lastSeenMillis());
            return true;
        });
    }

    private static void quiet(BlockPos pos, boolean unchanged, long readAt) {
        if (unchanged) QUIET_AT.put(pos, readAt);
        else QUIET_AT.remove(pos);
    }

    private static void forgetOld(long now) {
        REAL_AT.values().removeIf(at -> now - at > FORGET_MILLIS);
        ECHO_AT.values().removeIf(at -> now - at > FORGET_MILLIS);
        QUIET_AT.values().removeIf(at -> now - at > FORGET_MILLIS);
        OPENED_AT.values().removeIf(at -> now - at > FORGET_MILLIS);
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
        OPENED_AT.clear();
        BARREL_SEEN.clear();
        REAL_AT.clear();
        ECHO_AT.clear();
        QUIET_AT.clear();
        PENDING.clear();
        level = null;
    }
}
