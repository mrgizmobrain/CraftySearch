package com.craftysearch.client.config;

import com.craftysearch.CraftySearch;
import com.craftysearch.storage.ContainerKind;
import com.craftysearch.storage.PutAwayRule;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Client config, stored in {@code config/craftysearch-client.json}. The open-storage
 * keybind is a vanilla key mapping, so it lives in the Controls screen and options.txt.
 */
public final class ClientConfig {
    public static final int MIN_RADIUS = 4;
    public static final int MAX_RADIUS = 128;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("craftysearch-client.json");
    private static ClientConfig instance = new ClientConfig();

    /** 0 in files from before phase 6. Lets {@link #sanitize} switch on kinds added since. */
    public int configVersion;
    public int radius = 32;
    public boolean showPanelInInventory = true;
    public boolean showPanelInCraftingTable = true;
    public PanelOpen panelOpen = new PanelOpen();
    public int highlightDurationSeconds = 10;
    public IngredientSourceOrder ingredientSourceOrder = IngredientSourceOrder.INVENTORY_FIRST;
    /** Recipe clicks craft straight into the inventory (spec 3.3); off leaves the recipe in the grid like vanilla. */
    public boolean craftToInventory = true;
    /** Recipes whose ingredients can be crafted from what's there get those crafted first (spec 3.6). */
    public boolean craftIntermediates = true;
    public List<String> includeContainerTypes = new ArrayList<>(ContainerKind.allIds());
    /** Whether the panel's totals count the player's inventory. Crafting always uses it. */
    public boolean includeInventory = true;
    /** How the panel orders its tiles; changed with the button beside the search box. */
    public PanelSort panelSort = PanelSort.COUNT;
    /** The panel keeps its search text between screens, instead of starting empty. */
    public boolean rememberSearch = true;
    /** On-screen markers over highlighted containers; shader packs hide the outlines behind walls. */
    public Markers highlightMarkers = Markers.WITH_SHADERS;
    /** Taking and crafting leave one of each stackable item in every container ({@link com.craftysearch.storage.KeepOne}). */
    public boolean keepOne = false;
    /** Items inside shulker boxes count in the panel and are highlighted; they're never taken out (Nov 8). */
    public boolean shulkerContents = true;
    /**
     * The panel shows only what can be taken (Nov 9): in client-only mode from containers
     * within reach, and with "Leave one" on not the kept ones.
     */
    public boolean onlyTakeable = false;
    public ClientOnly clientOnly = new ClientOnly();
    public Memory memory = new Memory();
    public PutAway putAway = new PutAway();
    public Mouse mouse = new Mouse();

    public static final class PutAway {
        /** Where an item goes when no container already holds it (spec 3.4). */
        public PutAwayRule.Fallback fallback = PutAwayRule.Fallback.COPPER_CHEST;
        /** Which shift-click on an inventory stack puts it away; the other keeps vanilla's shift-click. */
        public ShiftClick shiftClick = ShiftClick.LEFT;
    }

    public enum ShiftClick {
        @SerializedName("left") LEFT,
        @SerializedName("right") RIGHT,
        @SerializedName("off") OFF;

        /** The GLFW mouse button that stores, or -1 for off. */
        public int button() {
            return switch (this) {
                case LEFT -> 0;
                case RIGHT -> 1;
                case OFF -> -1;
            };
        }
    }

    /** The scroll wheel and shift-drag on storage (spec 3.5). */
    public static final class Mouse {
        public ScrollDirection scrollDirection = ScrollDirection.DOWN_TAKES;
        /** Scrolling over an inventory stack moves items between it and storage while the panel shows. */
        public boolean scrollOverInventory = true;
        /** Scrolling over a tile moves items; off, it scrolls the grid. */
        public boolean scrollOverTiles = true;
        /** Shift-dragging over inventory stacks puts them away, over tiles takes a stack of each. */
        public boolean shiftDrag = true;
        /** What a right-click on a tile does (Nov 9). */
        public TileClick rightClick = TileClick.OPEN;
        /** What a shift-right-click on a tile does (Nov 9). */
        public TileClick shiftRightClick = TileClick.SHOW_WHERE;
    }

    /** What a right-click or shift-right-click on a tile does; either can be any of them (Nov 9). */
    public enum TileClick {
        /** Opens the nearest container holding it: anywhere in range in server mode, within reach otherwise. */
        @SerializedName("open") OPEN,
        /** One onto the cursor (or one more onto a held stack of it); with Shift, into the inventory. */
        @SerializedName("take_one") TAKE_ONE,
        @SerializedName("show_where") SHOW_WHERE,
        /** As much as fits in the inventory, not just a stack. */
        @SerializedName("take_all") TAKE_ALL
    }

    public enum Markers {
        /** Only while a shader pack is on, as the outlines then don't show through walls. */
        @SerializedName("with_shaders") WITH_SHADERS,
        @SerializedName("always") ALWAYS,
        @SerializedName("off") OFF
    }

    public enum PanelSort {
        /** Most first: what there's plenty of. */
        @SerializedName("count") COUNT,
        @SerializedName("name") NAME,
        /** By mod, then name: items from one mod together. */
        @SerializedName("mod") MOD;

        public PanelSort next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    public enum ScrollDirection {
        /** Scroll down takes from storage, up puts away: Mouse Tweaks' habit. */
        @SerializedName("down_takes") DOWN_TAKES,
        @SerializedName("up_takes") UP_TAKES
    }

    public static final class PanelOpen {
        public boolean inventory = true;
        public boolean craftingTable = true;
    }

    public static final class ClientOnly {
        /** Pause between background actions at normal speed. */
        public int packetDelayMs = 50;
        public boolean muteOwnContainerSounds = true;
        public boolean hideOwnContainerAnimations = true;
        public Speed speed = Speed.NORMAL;
        /**
         * Opening one of several stacked carts by hand opens the others in the background to
         * record them (spec 5.1). Strict anti-cheat plugins may flag it.
         */
        public boolean scanStackedCarts = true;
        /**
         * Reaches carts and boats 2.5 blocks beyond the crosshair's range, close to the 3 the
         * server allows. Anti-cheat plugins check reach closely, so it's off by default.
         */
        public boolean extraCartReach = false;
        /**
         * Reaches blocks 1 block beyond the crosshair's range, all the server allows. Off by
         * default for the same reason as {@link #extraCartReach}.
         */
        public boolean extraBlockReach = false;
    }

    public enum Speed {
        /** Paced, one step per tick: gentle on anti-cheat plugins. */
        @SerializedName("normal") NORMAL,
        /** No pauses and as many steps per tick as the server's replies allow. For servers without anti-cheat. */
        @SerializedName("fast") FAST
    }

    public static final class Memory {
        public boolean enabled = true;
        public boolean showStaleAge = true;
    }

    public enum IngredientSourceOrder {
        @SerializedName("inventory_first") INVENTORY_FIRST,
        @SerializedName("nearest_first") NEAREST_FIRST
    }

    public static ClientConfig get() {
        return instance;
    }

    public boolean includes(ContainerKind kind) {
        return includeContainerTypes.contains(kind.id());
    }

    /** The included container kinds, as sent to the server. */
    public int kindsMask() {
        Set<ContainerKind> kinds = EnumSet.noneOf(ContainerKind.class);
        for (ContainerKind kind : ContainerKind.values()) {
            if (includes(kind)) kinds.add(kind);
        }
        return ContainerKind.toMask(kinds);
    }

    public static void load() {
        if (Files.exists(FILE)) {
            try (Reader reader = Files.newBufferedReader(FILE)) {
                ClientConfig loaded = GSON.fromJson(reader, ClientConfig.class);
                if (loaded != null) {
                    instance = loaded;
                }
            } catch (IOException | JsonParseException e) {
                CraftySearch.LOGGER.warn("Could not read {}, using defaults", FILE, e);
            }
        }
        instance.sanitize();
        save();
    }

    public static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            try (Writer writer = Files.newBufferedWriter(FILE)) {
                GSON.toJson(instance, writer);
            }
        } catch (IOException e) {
            CraftySearch.LOGGER.warn("Could not write {}", FILE, e);
        }
    }

    // Gson leaves fields null when the file has explicit nulls or unknown enum values.
    private void sanitize() {
        radius = Math.clamp(radius, MIN_RADIUS, MAX_RADIUS);
        highlightDurationSeconds = Math.clamp(highlightDurationSeconds, 1, 300);
        if (panelOpen == null) panelOpen = new PanelOpen();
        if (ingredientSourceOrder == null) ingredientSourceOrder = IngredientSourceOrder.INVENTORY_FIRST;
        if (includeContainerTypes == null) includeContainerTypes = new ArrayList<>(ContainerKind.allIds());
        if (configVersion < 1) {
            // Chest minecarts and boats came in phase 6; older files list only the block kinds.
            for (ContainerKind kind : ContainerKind.values()) {
                if (kind.isEntity() && !includeContainerTypes.contains(kind.id())) includeContainerTypes.add(kind.id());
            }
            configVersion = 1;
        }
        if (clientOnly == null) clientOnly = new ClientOnly();
        if (memory == null) memory = new Memory();
        if (putAway == null) putAway = new PutAway();
        if (putAway.fallback == null) putAway.fallback = PutAwayRule.Fallback.COPPER_CHEST;
        if (putAway.shiftClick == null) putAway.shiftClick = ShiftClick.LEFT;
        if (panelSort == null) panelSort = PanelSort.COUNT;
        if (mouse == null) mouse = new Mouse();
        if (mouse.scrollDirection == null) mouse.scrollDirection = ScrollDirection.DOWN_TAKES;
        if (mouse.rightClick == null) mouse.rightClick = TileClick.OPEN;
        if (mouse.shiftRightClick == null) mouse.shiftRightClick = TileClick.SHOW_WHERE;
        clientOnly.packetDelayMs = Math.max(0, clientOnly.packetDelayMs);
        if (clientOnly.speed == null) clientOnly.speed = Speed.NORMAL;
        if (highlightMarkers == null) highlightMarkers = Markers.WITH_SHADERS;
    }
}
