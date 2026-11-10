package com.craftysearch.server.protection;

import com.craftysearch.CraftySearch;
import com.craftysearch.ServerConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Whether a player may open a container, by the same rules as opening it by hand (Nov 8).
 * Server mode reads and moves items straight from containers, so without this a claimed
 * chest would be open to anyone with the mod. Covers vanilla's spawn protection and world
 * border, Open Parties and Claims, Flan, and every mod that answers through the Common
 * Protection API (GOML among them). Containers that fail are left out of the scan, so they
 * show nowhere: not in live data, not as crafting sources, not for taking or putting away.
 * <p>
 * Client-only mode needs none of this: there the server sees ordinary clicks, which claim
 * mods already guard.
 */
public final class Protection {
    /** One claim mod's answer. Each lives in its own class, loaded only if that mod is. */
    interface Check {
        String name();

        boolean mayOpenBlock(ServerPlayer player, ServerLevel level, BlockPos pos);

        boolean mayOpenEntity(ServerPlayer player, Entity entity);
    }

    private static List<Check> checks = List.of();
    /** Checks that have thrown, so the log says so once rather than every scan. */
    private static final Set<String> FAILED = new HashSet<>();

    private Protection() {
    }

    /** Finds the claim mods that are installed; at each server start. */
    public static void init() {
        FabricLoader loader = FabricLoader.getInstance();
        List<Check> found = new ArrayList<>();
        if (loader.isModLoaded("openpartiesandclaims")) found.add(new OpacCheck());
        if (loader.isModLoaded("flan")) found.add(new FlanCheck());
        // GOML and others answer through the Common Protection API (Flan too, when it's installed).
        if (loader.isModLoaded("common-protection-api")) found.add(new CommonProtectionCheck());
        checks = List.copyOf(found);
        FAILED.clear();
        if (!checks.isEmpty()) {
            CraftySearch.LOGGER.info("Storage access follows claims from: {}", checks.stream().map(Check::name).toList());
        }
    }

    public static boolean mayOpen(ServerPlayer player, ServerLevel level, BlockPos pos) {
        if (!ServerConfig.get().respectClaims) return true;
        // Spawn protection (for players who aren't operators) and the world border, as for a click.
        if (!level.mayInteract(player, pos)) return false;
        for (Check check : checks) {
            try {
                if (!check.mayOpenBlock(player, level, pos)) return false;
            } catch (Throwable e) {
                return failed(check, e);
            }
        }
        return true;
    }

    public static boolean mayOpen(ServerPlayer player, Entity entity) {
        if (!ServerConfig.get().respectClaims) return true;
        for (Check check : checks) {
            try {
                if (!check.mayOpenEntity(player, entity)) return false;
            } catch (Throwable e) {
                return failed(check, e);
            }
        }
        return true;
    }

    /**
     * A claim mod's API that throws (a version that changed it, say) can't vouch for anything,
     * so access is refused: better no storage than other players' storage.
     */
    private static boolean failed(Check check, Throwable e) {
        if (FAILED.add(check.name())) {
            CraftySearch.LOGGER.error("Couldn't ask {} about claims, so storage access is refused where it applies. "
                    + "Set \"respectClaims\": false in craftysearch-server.json to ignore claims.", check.name(), e);
        }
        return false;
    }
}
