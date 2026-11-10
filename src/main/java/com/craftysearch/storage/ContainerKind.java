package com.craftysearch.storage;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.boat.AbstractChestBoat;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.entity.vehicle.minecart.MinecartHopper;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CopperChestBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DropperBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The containers Crafty Search tracks (spec section 5): placed blocks, and chest minecarts
 * and boats (5.1). Ender chests aren't storage here yet (spec 9). New kinds go at the end,
 * because network masks use the ordinal. Shared by the client (memory) and the server (live
 * data, crafting).
 */
public enum ContainerKind {
    CHEST("chest", 27, false),
    BARREL("barrel", 27, false),
    SHULKER_BOX("shulker_box", 27, false),
    HOPPER("hopper", 5, false),
    DROPPER("dropper", 9, false),
    DISPENSER("dispenser", 9, false),
    CHEST_MINECART("chest_minecart", 27, true),
    /** Chest boats and bamboo chest rafts. */
    CHEST_BOAT("chest_boat", 27, true),
    HOPPER_MINECART("hopper_minecart", 5, true);

    private final String id;
    private final int slots;
    private final boolean entity;

    ContainerKind(String id, int slots, boolean entity) {
        this.id = id;
        this.slots = slots;
        this.entity = entity;
    }

    public String id() {
        return id;
    }

    /** A minecart or boat rather than a block. */
    public boolean isEntity() {
        return entity;
    }

    /**
     * Where put-away may store items. Hoppers and hopper minecarts would pass them on, and
     * dispensers could fire them (spec 3.4, 5.1).
     */
    public boolean acceptsPutAway() {
        return this == CHEST || this == BARREL || this == SHULKER_BOX || this == CHEST_MINECART || this == CHEST_BOAT;
    }

    /** Copper chests (every stage, waxed or not) are a copper golem's inbox. */
    public static boolean isCopperChest(BlockState state) {
        return state.getBlock() instanceof CopperChestBlock;
    }

    /** Number of container slots the menu should have, so plugin GUIs opened by a click aren't recorded. */
    public int expectedSlots(boolean isDouble) {
        return isDouble ? slots * 2 : slots;
    }

    public static List<String> allIds() {
        return Arrays.stream(values()).map(ContainerKind::id).toList();
    }

    public static @Nullable ContainerKind byId(String id) {
        for (ContainerKind kind : values()) {
            if (kind.id.equals(id)) return kind;
        }
        return null;
    }

    /** One bit per kind, by ordinal, for sending a set of kinds over the network. */
    public static int toMask(Set<ContainerKind> kinds) {
        int mask = 0;
        for (ContainerKind kind : kinds) mask |= 1 << kind.ordinal();
        return mask;
    }

    public static Set<ContainerKind> fromMask(int mask) {
        Set<ContainerKind> kinds = EnumSet.noneOf(ContainerKind.class);
        for (ContainerKind kind : values()) {
            if ((mask & (1 << kind.ordinal())) != 0) kinds.add(kind);
        }
        return kinds;
    }

    public static @Nullable ContainerKind of(BlockState state) {
        Block block = state.getBlock();
        // Covers trapped chests too. Ender chests are an AbstractChestBlock, not a ChestBlock.
        if (block instanceof ChestBlock) return CHEST;
        if (block instanceof BarrelBlock) return BARREL;
        if (block instanceof ShulkerBoxBlock) return SHULKER_BOX;
        if (block instanceof HopperBlock) return HOPPER;
        // DropperBlock extends DispenserBlock, so check it first.
        if (block instanceof DropperBlock) return DROPPER;
        if (block instanceof DispenserBlock) return DISPENSER;
        return null;
    }

    public static @Nullable ContainerKind of(Entity entity) {
        if (entity instanceof MinecartChest) return CHEST_MINECART;
        if (entity instanceof MinecartHopper) return HOPPER_MINECART;
        // Covers bamboo chest rafts too.
        if (entity instanceof AbstractChestBoat) return CHEST_BOAT;
        return null;
    }

    /** The other half of a double chest, or null for anything else. */
    public static @Nullable BlockPos otherHalf(BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            return ChestBlock.getConnectedBlockPos(pos, state);
        }
        return null;
    }
}
