package com.craftysearch.client.highlight;

import com.craftysearch.client.background.StackScan;
import com.craftysearch.client.config.ClientConfig;
import com.craftysearch.client.memory.MemoryEntry;
import com.craftysearch.client.storage.StorageIndex;
import com.craftysearch.storage.ContainerKey;
import com.craftysearch.storage.ContainerKind;
import com.craftysearch.storage.ItemKey;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;

/**
 * The current search from the storage panel: the item clicked, and the containers holding
 * it, outlined through walls with vanilla's always-on-top gizmos, each with how many it
 * holds. A container's outline is dropped once it's opened or emptied, so the rest show
 * where there's still some to find. Carts and boats are outlined where they are each tick,
 * and a stack of them gets one outline with its total and a "×N".
 */
public final class Highlights {
    private static final int COLOR = 0xFF4CFF4C;
    private static final int COUNT_COLOR = 0xFFFFFFFF;
    private static final float LINE_WIDTH = 2.5f;
    /**
     * Labels sit in the middle of the outline (Nov 8: they were above it). A text gizmo hangs
     * down from its position, at this many blocks per font pixel.
     */
    private static final double PIXEL = TextGizmo.Style.DEFAULT_SCALE / 16.0;
    /** How tall digits are, and the step from one line to the next. */
    private static final double GLYPH_HEIGHT = 7 * PIXEL;
    private static final double LINE_STEP = 10 * PIXEL;
    /** How often the counts are read again from live data or memory. */
    private static final int REFRESH_TICKS = 10;

    /** Each outlined container as last known, with how many of the item it holds. */
    private record Marked(MemoryEntry entry, int count) {
    }

    /** What an outline's label says and where the outline's middle is, for {@link HighlightMarkers}. */
    public record Marker(Vec3 center, String count, int stackSize) {
    }

    private static List<Marked> marked = List.of();
    private static List<Marker> markers = List.of();
    private static @Nullable ItemKey target;
    private static @Nullable ResourceKey<Level> dimension;
    private static long expiresAt;
    private static int ticks;

    /** Stacked carts and boats ({@link StackScan#stacked}), drawn as one outline. */
    private static final class Stack {
        final List<AABB> boxes = new ArrayList<>();
        AABB bounds;
        long count;

        Stack(AABB box, int count) {
            boxes.add(box);
            bounds = box;
            this.count = count;
        }

        void add(AABB box, long more) {
            boxes.add(box);
            bounds = bounds.minmax(box);
            count += more;
        }
    }

    private Highlights() {
    }

    public static void show(Minecraft mc, Collection<MemoryEntry> entries, ItemKey item) {
        if (mc.level == null) return;
        marked = new ArrayList<>(entries.stream().map(e -> new Marked(e, countOf(e, item))).toList());
        target = item;
        dimension = mc.level.dimension();
        expiresAt = System.currentTimeMillis() + ClientConfig.get().highlightDurationSeconds * 1000L;
        ticks = 0;
    }

    /** The item being searched for, while the search is active. */
    public static @Nullable ItemKey target() {
        return target != null && System.currentTimeMillis() <= expiresAt ? target : null;
    }

    /** One cart of a stack drops out of the stack's count; the others keep their outline. */
    public static void containerOpened(ContainerKey key) {
        if (!marked.isEmpty()) marked.removeIf(m -> m.entry().is(key));
    }

    /** The outlines drawn this tick, as markers. */
    public static List<Marker> markers() {
        return markers;
    }

    public static void clear() {
        marked = List.of();
        markers = List.of();
        target = null;
        dimension = null;
    }

    public static void tick(Minecraft mc) {
        ItemKey item = target;
        if (item == null) return;
        if (mc.level == null || !mc.level.dimension().equals(dimension) || System.currentTimeMillis() > expiresAt) {
            clear();
            return;
        }
        if (++ticks % REFRESH_TICKS == 0) refresh(mc, item);
        // A container broken since the search shouldn't stay outlined until the highlight runs out.
        marked.removeIf(m -> !m.entry().isEntity() && mc.level.isLoaded(m.entry().pos())
                && ContainerKind.of(mc.level.getBlockState(m.entry().pos())) == null);
        if (marked.isEmpty()) {
            markers = List.of();
            return;
        }

        List<Stack> stacks = new ArrayList<>();
        List<Marker> nowMarked = new ArrayList<>();
        // With the HUD markers showing the counts, labels here would only repeat them.
        boolean labels = !HighlightMarkers.shown();
        // Per-tick gizmos are drained once per tick and drawn until the next drain.
        try (var _ = mc.collectPerTickGizmos()) {
            for (Marked m : marked) {
                AABB box = m.entry().box(mc.level);
                if (m.entry().isEntity()) {
                    addToStack(stacks, box, m.count());
                } else {
                    String count = StorageIndex.formatCount(m.count());
                    outline(box);
                    if (labels) label(box.getCenter(), count, null);
                    nowMarked.add(new Marker(box.getCenter(), count, 1));
                }
            }
            for (Stack stack : stacks) {
                String count = StorageIndex.formatCount(stack.count);
                outline(stack.bounds);
                if (labels) label(stack.bounds.getCenter(), count, stack.boxes.size() > 1 ? "×" + stack.boxes.size() : null);
                nowMarked.add(new Marker(stack.bounds.getCenter(), count, stack.boxes.size()));
            }
        }
        markers = nowMarked;
    }

    /**
     * Reads each container's count again, so the labels keep up with takes; one that's run out
     * loses its outline. A container nothing is known about now (a cart out of sight, one
     * outside the live data's range) keeps its last count.
     */
    private static void refresh(Minecraft mc, ItemKey item) {
        for (ListIterator<Marked> it = marked.listIterator(); it.hasNext(); ) {
            Marked m = it.next();
            MemoryEntry now = StorageIndex.currentEntry(mc, m.entry().key());
            if (now == null) continue;
            int count = countOf(now, item);
            if (count <= 0) it.remove();
            else it.set(new Marked(now, count));
        }
    }

    /**
     * How many of the tile's item a container holds, inside its shulker boxes too with
     * {@code shulkerContents} on; durability and repair cost don't matter, as on the tile.
     */
    private static int countOf(MemoryEntry entry, ItemKey item) {
        long count = StorageIndex.inBoxes(entry, item);
        for (Object2IntMap.Entry<ItemKey> e : entry.counts().object2IntEntrySet()) {
            if (item.matchesTile(e.getKey().stack())) count += e.getIntValue();
        }
        return (int) Math.min(Integer.MAX_VALUE, count);
    }

    /** Adds a cart to the stack it's stacked with, merging stacks it links up. */
    private static void addToStack(List<Stack> stacks, AABB box, int count) {
        Stack joined = null;
        for (Iterator<Stack> it = stacks.iterator(); it.hasNext(); ) {
            Stack stack = it.next();
            if (stack.boxes.stream().noneMatch(b -> StackScan.stacked(b, box))) continue;
            if (joined == null) {
                joined = stack;
            } else {
                for (AABB b : stack.boxes) joined.add(b, 0);
                joined.count += stack.count;
                it.remove();
            }
        }
        if (joined == null) stacks.add(new Stack(box, count));
        else joined.add(box, count);
    }

    private static void outline(AABB box) {
        Gizmos.cuboid(box.inflate(0.005), GizmoStyle.stroke(COLOR, LINE_WIDTH)).setAlwaysOnTop();
    }

    /**
     * The count centred on {@code center}, visible through walls like the outline; for a stack
     * of carts, "×N" in green under it, the two lines centred together.
     */
    private static void label(Vec3 center, String count, @Nullable String stackSize) {
        double height = stackSize == null ? GLYPH_HEIGHT : LINE_STEP + GLYPH_HEIGHT;
        double top = center.y + height / 2;
        text(new Vec3(center.x, top, center.z), count, COUNT_COLOR);
        if (stackSize != null) text(new Vec3(center.x, top - LINE_STEP, center.z), stackSize, COLOR);
    }

    private static void text(Vec3 pos, String text, int color) {
        Gizmos.billboardText(text, pos, TextGizmo.Style.forColorAndCentered(color)).setAlwaysOnTop();
    }
}
