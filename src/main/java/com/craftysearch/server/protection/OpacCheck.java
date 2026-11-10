package com.craftysearch.server.protection;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import xaero.pac.common.server.api.OpenPACServerAPI;
import xaero.pac.common.server.claims.protection.api.IChunkProtectionAPI;

/**
 * Open Parties and Claims. Asked as if the player right-clicked with an empty hand, the way
 * a chest is opened, and without OPAC's "this is protected" chat message, since this runs
 * for every container in range.
 */
final class OpacCheck implements Protection.Check {
    @Override
    public String name() {
        return "Open Parties and Claims";
    }

    @Override
    public boolean mayOpenBlock(ServerPlayer player, ServerLevel level, BlockPos pos) {
        IChunkProtectionAPI protection = OpenPACServerAPI.get(level.getServer()).getChunkProtection();
        // True means protected against.
        return !protection.onBlockInteraction(player, InteractionHand.MAIN_HAND, ItemStack.EMPTY, level, pos, Direction.UP,
                false, false, true);
    }

    @Override
    public boolean mayOpenEntity(ServerPlayer player, Entity entity) {
        IChunkProtectionAPI protection = OpenPACServerAPI.get(player.level().getServer()).getChunkProtection();
        return !protection.onEntityInteraction(player, player, entity, ItemStack.EMPTY, InteractionHand.MAIN_HAND,
                false, false, true);
    }
}
