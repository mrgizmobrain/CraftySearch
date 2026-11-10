package com.craftysearch.server.protection;

import eu.pb4.common.protection.api.CommonProtection;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * The Common Protection API, which claim mods such as GOML answer through. It asks every
 * registered provider, so it also covers mods this one doesn't know by name.
 */
final class CommonProtectionCheck implements Protection.Check {
    @Override
    public String name() {
        return "Common Protection API";
    }

    @Override
    public boolean mayOpenBlock(ServerPlayer player, ServerLevel level, BlockPos pos) {
        return CommonProtection.canInteractBlock(level, pos, player.nameAndId(), player);
    }

    @Override
    public boolean mayOpenEntity(ServerPlayer player, Entity entity) {
        return CommonProtection.canInteractEntity(player.level(), entity, player.nameAndId(), player);
    }
}
