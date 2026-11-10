package com.craftysearch;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Server config, stored in {@code config/craftysearch-server.json}. Singleplayer worlds use
 * it too, because they run on a built-in server.
 */
public final class ServerConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("craftysearch-server.json");
    private static ServerConfig instance = new ServerConfig();

    /** When false, clients see a server without the mod and fall back to client-only mode. */
    public boolean enabled = true;
    /** Caps the radius any client can request, for live data and for crafting. */
    public int maxRadius = 32;
    public boolean allowCrafting = true;
    /** Taking from and putting away into storage from the panel, without opening containers. */
    public boolean allowTakeAndPutAway = true;
    /**
     * Right-clicking a panel tile opens the container holding it from anywhere within the
     * radius (Nov 9). Off, players can still open the ones within reach that way.
     */
    public boolean allowRemoteOpen = true;
    /** Skips locked containers, both in live data and as crafting sources. */
    public boolean respectContainerLocks = true;
    /**
     * Skips containers the player couldn't open by hand: in another player's claim (Open
     * Parties and Claims, Flan, GOML and other mods using the Common Protection API), or
     * inside spawn protection for players who aren't operators.
     */
    public boolean respectClaims = true;

    public static ServerConfig get() {
        return instance;
    }

    public static void load() {
        if (Files.exists(FILE)) {
            try (Reader reader = Files.newBufferedReader(FILE)) {
                ServerConfig loaded = GSON.fromJson(reader, ServerConfig.class);
                if (loaded != null) instance = loaded;
            } catch (IOException | JsonParseException e) {
                CraftySearch.LOGGER.warn("Could not read {}, using defaults", FILE, e);
            }
        }
        instance.maxRadius = Math.clamp(instance.maxRadius, 0, 128);
        try {
            Files.createDirectories(FILE.getParent());
            try (Writer writer = Files.newBufferedWriter(FILE)) {
                GSON.toJson(instance, writer);
            }
        } catch (IOException e) {
            CraftySearch.LOGGER.warn("Could not write {}", FILE, e);
        }
    }
}
