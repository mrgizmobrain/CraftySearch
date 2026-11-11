package com.craftysearch.client.memory;

import com.craftysearch.client.config.ClientConfig;
import com.craftysearch.storage.ContainerKey;
import com.craftysearch.storage.ContainerKind;
import com.craftysearch.storage.ItemKey;
import com.craftysearch.storage.KeepOne;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * What was last seen inside one container. A double chest is one entry: {@code pos} is
 * the lower of the two halves and {@code other} the second half. A chest minecart or boat
 * has its {@code entity} UUID, and {@code pos} is the block it was last seen in (spec 5.1).
 * Counts are keyed by exact item, components included, and aren't changed once the entry is
 * made. {@code suspect}: someone else has used the container since (client-only mode, Nov 10),
 * so the counts may be off until it's read again.
 */
public final class MemoryEntry {
    private final BlockPos pos;
    private final @Nullable BlockPos other;
    private final @Nullable UUID entity;
    private final ContainerKind kind;
    private final long lastSeenMillis;
    private final Object2IntMap<ItemKey> counts;
    private final boolean suspect;
    /** {@link #takeableCounts} with "Leave one" on, worked out on first use. */
    private @Nullable Object2IntMap<ItemKey> takeable;

    public MemoryEntry(BlockPos pos, @Nullable BlockPos other, @Nullable UUID entity, ContainerKind kind,
                       long lastSeenMillis, Object2IntMap<ItemKey> counts) {
        this(pos, other, entity, kind, lastSeenMillis, counts, false);
    }

    public MemoryEntry(BlockPos pos, @Nullable BlockPos other, @Nullable UUID entity, ContainerKind kind,
                       long lastSeenMillis, Object2IntMap<ItemKey> counts, boolean suspect) {
        this.pos = pos;
        this.other = other;
        this.entity = entity;
        this.kind = kind;
        this.lastSeenMillis = lastSeenMillis;
        this.counts = counts;
        this.suspect = suspect;
    }

    public BlockPos pos() {
        return pos;
    }

    public @Nullable BlockPos other() {
        return other;
    }

    public @Nullable UUID entity() {
        return entity;
    }

    public ContainerKind kind() {
        return kind;
    }

    public long lastSeenMillis() {
        return lastSeenMillis;
    }

    public Object2IntMap<ItemKey> counts() {
        return counts;
    }

    public boolean suspect() {
        return suspect;
    }

    public ContainerKey key() {
        return entity != null ? ContainerKey.ofEntity(entity) : ContainerKey.ofBlock(pos);
    }

    public boolean isEntity() {
        return entity != null;
    }

    /**
     * What taking and crafting may use: {@code counts}, less the one kept of each item with
     * "Leave one" on. Kept between calls (Nov 10): the panel, the recipe book and every craft
     * plan ask for it, for every container.
     */
    public Object2IntMap<ItemKey> takeableCounts() {
        if (!ClientConfig.get().keepOne) return counts;
        Object2IntMap<ItemKey> result = takeable;
        if (result == null) {
            result = KeepOne.takeable(counts);
            takeable = result;
        }
        return result;
    }

    /** True if {@code key} names this container: the same cart, or either half of this chest. */
    public boolean is(ContainerKey key) {
        return key.entity() != null ? key.entity().equals(entity) : entity == null && contains(key.block());
    }

    public boolean contains(BlockPos p) {
        return pos.equals(p) || p.equals(other);
    }

    /** The cart or boat, while the client has it loaded. Null for blocks. */
    public @Nullable Entity findEntity(Level level) {
        return entity == null ? null : level.getEntity(entity);
    }

    /** The block a cart is in now when the client knows, else where it was last seen; {@code pos} for blocks. */
    public BlockPos currentPos(Level level) {
        Entity e = findEntity(level);
        return e != null ? e.blockPosition() : pos;
    }

    public boolean isWithin(Level level, BlockPos center, int radius) {
        if (entity != null) return inCube(currentPos(level), center, radius);
        return inCube(pos, center, radius) || (other != null && inCube(other, center, radius));
    }

    public double distanceSqr(Level level, BlockPos center) {
        if (entity != null) return currentPos(level).distSqr(center);
        double d = pos.distSqr(center);
        return other == null ? d : Math.min(d, other.distSqr(center));
    }

    /** The outline: a cart's own box while it's loaded, else the block it was last seen in. */
    public AABB box(Level level) {
        Entity e = findEntity(level);
        if (e != null) return e.getBoundingBox();
        BlockPos b = other == null ? pos : other;
        return new AABB(
                Math.min(pos.getX(), b.getX()), Math.min(pos.getY(), b.getY()), Math.min(pos.getZ(), b.getZ()),
                Math.max(pos.getX(), b.getX()) + 1, Math.max(pos.getY(), b.getY()) + 1, Math.max(pos.getZ(), b.getZ()) + 1);
    }

    public MemoryEntry withPos(BlockPos newPos) {
        return new MemoryEntry(newPos, other, entity, kind, lastSeenMillis, counts, suspect);
    }

    /** The same contents, marked as possibly changed by someone else. */
    public MemoryEntry asSuspect() {
        return suspect ? this : new MemoryEntry(pos, other, entity, kind, lastSeenMillis, counts, true);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof MemoryEntry e && lastSeenMillis == e.lastSeenMillis && suspect == e.suspect && kind == e.kind
                && pos.equals(e.pos) && Objects.equals(other, e.other) && Objects.equals(entity, e.entity) && counts.equals(e.counts);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pos, entity, kind, lastSeenMillis);
    }

    private static boolean inCube(BlockPos p, BlockPos center, int radius) {
        return Math.abs(p.getX() - center.getX()) <= radius
                && Math.abs(p.getY() - center.getY()) <= radius
                && Math.abs(p.getZ() - center.getZ()) <= radius;
    }
}
