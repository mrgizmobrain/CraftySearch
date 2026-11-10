package com.craftysearch.server.protection;

import io.github.flemmli97.flan.api.ClaimHandler;
import io.github.flemmli97.flan.api.permission.BuiltinPermission;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/** Flan: the claim's "open container" permission, for blocks and for carts and boats alike. */
final class FlanCheck implements Protection.Check {
    @Override
    public String name() {
        return "Flan";
    }

    @Override
    public boolean mayOpenBlock(ServerPlayer player, ServerLevel level, BlockPos pos) {
        // Flan looks the claim up in the player's own level, which is the level being scanned.
        return ClaimHandler.canInteract(player, pos, BuiltinPermission.OPENCONTAINER);
    }

    @Override
    public boolean mayOpenEntity(ServerPlayer player, Entity entity) {
        return ClaimHandler.canInteract(player, entity.blockPosition(), BuiltinPermission.OPENCONTAINER);
    }
}
