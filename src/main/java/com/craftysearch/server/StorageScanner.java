package com.craftysearch.server;

import com.craftysearch.ServerConfig;
import com.craftysearch.network.StorageUpdatePayload.ItemCount;
import com.craftysearch.server.protection.Protection;
import com.craftysearch.storage.ContainerKey;
import com.craftysearch.storage.ContainerKind;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds the containers a player may use within a cube around them, in loaded chunks only:
 * container blocks, and chest minecarts and boats (spec 5.1).
 */
public final class StorageScanner {
    private StorageScanner() {
    }

    /**
     * A container in range. A double chest is one, with both halves; {@code pos} is the lower
     * half. A cart or boat has its {@code entity}, and {@code pos} is its current block.
     */
    public record Found(BlockPos pos, @Nullable BlockPos other, @Nullable Entity entity, ContainerKind kind,
                        List<Container> halves) {
        public ContainerKey key() {
            return entity != null ? ContainerKey.ofEntity(entity.getUUID()) : ContainerKey.ofBlock(pos);
        }

        double distanceSqr(Vec3 from) {
            if (entity != null) return from.distanceToSqr(entity.getBoundingBox().getCenter());
            double d = from.distanceToSqr(Vec3.atCenterOf(pos));
            return other == null ? d : Math.min(d, from.distanceToSqr(Vec3.atCenterOf(other)));
        }

        boolean isCopperChest(Level level) {
            return entity == null && ContainerKind.isCopperChest(level.getBlockState(pos));
        }
    }

    public static List<Found> scan(ServerPlayer player, int radius, Set<ContainerKind> kinds) {
        ServerLevel level = player.level();
        BlockPos center = player.blockPosition();
        boolean respectLocks = ServerConfig.get().respectContainerLocks;
        Map<BlockPos, Found> found = new LinkedHashMap<>();
        Set<BlockPos> skipped = new HashSet<>();

        int minX = SectionPos.blockToSectionCoord(center.getX() - radius);
        int maxX = SectionPos.blockToSectionCoord(center.getX() + radius);
        int minZ = SectionPos.blockToSectionCoord(center.getZ() - radius);
        int maxZ = SectionPos.blockToSectionCoord(center.getZ() + radius);
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                // getChunkNow never loads or generates a chunk.
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    BlockPos pos = be.getBlockPos();
                    if (!inCube(pos, center, radius) || !(be instanceof Container container)) continue;
                    ContainerKind kind = ContainerKind.of(be.getBlockState());
                    if (kind == null || !kinds.contains(kind) || !accessible(be, respectLocks)) continue;
                    // Claims and spawn protection: only what the player could open by hand.
                    if (!Protection.mayOpen(player, level, pos)) continue;

                    BlockPos other = ContainerKind.otherHalf(pos, be.getBlockState());
                    if (other == null) {
                        found.put(pos, new Found(pos, null, null, kind, List.of(container)));
                        continue;
                    }
                    BlockPos first = other.compareTo(pos) < 0 ? other : pos;
                    if (found.containsKey(first) || skipped.contains(first)) continue;
                    // The other half may be just outside the cube; it still belongs to this chest.
                    BlockEntity otherBe = level.isLoaded(other) ? level.getBlockEntity(other) : null;
                    List<Container> halves;
                    if (otherBe instanceof Container otherContainer && ContainerKind.of(otherBe.getBlockState()) == ContainerKind.CHEST) {
                        // Vanilla only opens a double chest when neither half is locked. A claim
                        // border between the halves counts the same way.
                        if (!accessible(otherBe, respectLocks) || !Protection.mayOpen(player, level, other)) {
                            skipped.add(first);
                            continue;
                        }
                        // In vanilla's order, so put-away fills the top rows first, as a shift-click does: the
                        // half whose chest type is RIGHT is the double chest's first (Nov 8; by block position
                        // the bottom half came first for some facings).
                        boolean thisFirst = be.getBlockState().getValue(ChestBlock.TYPE) == ChestType.RIGHT;
                        halves = thisFirst ? List.of(container, otherContainer) : List.of(otherContainer, container);
                    } else {
                        halves = List.of(container);
                    }
                    found.put(first, new Found(first, first.equals(pos) ? other : pos, null, kind, halves));
                }
            }
        }
        List<Found> result = new ArrayList<>(found.values());
        if (kinds.stream().anyMatch(ContainerKind::isEntity)) addVehicles(player, level, center, radius, kinds, result);
        return result;
    }

    /** Chest minecarts and boats in the cube, found with one entity query over loaded sections. Every cart in a stack counts. */
    private static void addVehicles(ServerPlayer player, ServerLevel level, BlockPos center, int radius, Set<ContainerKind> kinds,
                                    List<Found> result) {
        AABB area = AABB.encapsulatingFullBlocks(center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius));
        for (Entity entity : level.getEntitiesOfClass(Entity.class, area, e -> e instanceof ContainerEntity && !e.isRemoved())) {
            ContainerKind kind = ContainerKind.of(entity);
            BlockPos pos = entity.blockPosition();
            if (kind == null || !kinds.contains(kind) || !inCube(pos, center, radius)) continue;
            ContainerEntity container = (ContainerEntity) entity;
            // An unopened loot minecart (mineshafts) would generate its loot if read.
            if (container.getContainerLootTable() != null) continue;
            if (!Protection.mayOpen(player, entity)) continue;
            result.add(new Found(pos, null, entity, kind, List.of(container)));
        }
    }

    private static boolean accessible(BlockEntity be, boolean respectLocks) {
        // Reading an unopened loot chest would generate its loot, so leave it alone.
        if (be instanceof RandomizableContainer loot && loot.getLootTable() != null) return false;
        // isLocked has no side effects; canOpen would message the player. A locked container
        // is skipped even if the player holds its key, which is stricter than vanilla.
        return !(respectLocks && be instanceof BaseContainerBlockEntity base && base.isLocked());
    }

    private static boolean inCube(BlockPos p, BlockPos center, int radius) {
        return Math.abs(p.getX() - center.getX()) <= radius
                && Math.abs(p.getY() - center.getY()) <= radius
                && Math.abs(p.getZ() - center.getZ()) <= radius;
    }

    /**
     * Changes whenever the contents change (slot order included), without building a summary.
     * A cart's block counts too, so the client hears where it moved.
     */
    public static int contentHash(Found found) {
        int hash = found.kind().ordinal();
        hash = 31 * hash + (found.other() == null ? 0 : found.other().hashCode());
        hash = 31 * hash + (found.entity() == null ? 0 : found.pos().hashCode());
        for (Container container : found.halves()) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (stack.isEmpty()) continue;
                hash = 31 * hash + i;
                hash = 31 * hash + ItemStack.hashItemAndComponents(stack);
                hash = 31 * hash + stack.getCount();
            }
        }
        return hash;
    }

    /** Each distinct stack (item plus components) with its total count. */
    public static List<ItemCount> contents(Found found) {
        List<ItemStack> types = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        for (Container container : found.halves()) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (stack.isEmpty()) continue;
                int index = indexOf(types, stack);
                if (index < 0) {
                    types.add(stack.copyWithCount(1));
                    counts.add(stack.getCount());
                } else {
                    counts.set(index, counts.get(index) + stack.getCount());
                }
            }
        }
        List<ItemCount> result = new ArrayList<>(types.size());
        for (int i = 0; i < types.size(); i++) result.add(new ItemCount(types.get(i), counts.get(i)));
        return result;
    }

    private static int indexOf(List<ItemStack> types, ItemStack stack) {
        for (int i = 0; i < types.size(); i++) {
            if (ItemStack.isSameItemSameComponents(types.get(i), stack)) return i;
        }
        return -1;
    }
}
