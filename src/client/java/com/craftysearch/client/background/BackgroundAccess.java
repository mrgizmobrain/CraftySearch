package com.craftysearch.client.background;

import com.craftysearch.client.config.ClientConfig;
import com.craftysearch.client.memory.MemoryEntry;
import com.craftysearch.client.mixin.AbstractContainerScreenAccessor;
import com.craftysearch.client.mixin.AbstractRecipeBookScreenInvoker;
import com.craftysearch.client.mixin.RecipeBookComponentInvoker;
import com.craftysearch.storage.ContainerKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.vehicle.boat.AbstractChestBoat;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Opens containers without showing them (spec 4.2). The mod sends a normal "use block" (or
 * "interact" for a cart or boat), and when the server answers with an open-screen packet it
 * builds the menu with no screen, so whatever the player is looking at stays up. Everything
 * here runs on the client thread.
 */
public final class BackgroundAccess {
    /** How long after a container closes its sounds and lid events stay hidden. */
    private static final long MUTE_AFTER_CLOSE_MILLIS = 2000;

    /**
     * {@code discard}: the craft was cancelled, so close the container as soon as it opens.
     * {@code type}: the only menu the container can open (carts and boats), or null for any.
     */
    private record Expected(long until, int closingContainerId, boolean discard, @Nullable MenuType<?> type) {
    }

    private record Muted(AABB area, boolean sounds, boolean lid, long until) {
    }

    private static @Nullable Expected expected;
    private static @Nullable AbstractContainerMenu hidden;
    /** A crafting screen waiting for its table to reopen, so the new menu can go into it. */
    private static @Nullable Screen reopening;
    private static long reopenUntil;
    private static final List<Muted> MUTED = new ArrayList<>();

    private BackgroundAccess() {
    }

    /**
     * Right-clicks the container and hides the screen the server opens in reply.
     * {@code closingContainerId} is the menu the server will close first (the crafting
     * table), or -1 when the player inventory is the open menu. Returns false, doing
     * nothing, for a cart or boat the client no longer has.
     */
    static boolean open(Minecraft mc, MemoryEntry container, long timeoutMillis, int closingContainerId) {
        Entity cart = container.findEntity(mc.level);
        if (container.isEntity() && cart == null) return false;
        long now = System.currentTimeMillis();
        expected = new Expected(now + timeoutMillis, closingContainerId, false, menuType(container.kind()));
        hidden = null;
        ClientConfig.ClientOnly config = ClientConfig.get().clientOnly;
        if (config.muteOwnContainerSounds || config.hideOwnContainerAnimations) {
            MUTED.add(new Muted(container.box(mc.level).inflate(0.5), config.muteOwnContainerSounds,
                    config.hideOwnContainerAnimations, now + timeoutMillis + MUTE_AFTER_CLOSE_MILLIS));
        }
        if (cart != null) useEntity(mc, cart);
        else useBlock(mc, container.pos());
        return true;
    }

    /**
     * A plain right-click on a cart or boat, aimed at the middle of its box. The packet names
     * the entity, so it reaches every cart in a stack regardless of the crosshair (spec 5.1).
     * A chest boat only opens its chest for a sneaking click (otherwise the player gets in),
     * and the server takes the sneak flag from this packet, so the player's real input is
     * sent again right after to undo it.
     */
    static void useEntity(Minecraft mc, Entity entity) {
        LocalPlayer player = mc.player;
        if (player == null || mc.getConnection() == null) return;
        Input input = player.getLastSentInput();
        boolean sneak = entity instanceof AbstractChestBoat || input.shift();
        Vec3 hit = entity.getBoundingBox().getCenter().subtract(entity.position());
        mc.getConnection().send(new ServerboundInteractPacket(entity.getId(), InteractionHand.MAIN_HAND, hit, sneak));
        if (sneak != input.shift()) mc.getConnection().send(new ServerboundPlayerInputPacket(input));
    }

    /**
     * Right-clicks the crafting table again and puts the menu the server opens into the
     * crafting screen that's still showing. The screen, its recipe book (tab, page, layout)
     * and the storage panel all stay, instead of vanilla building a new screen.
     */
    static void reopenInPlace(Minecraft mc, BlockPos table, Screen screen, long timeoutMillis) {
        reopening = screen;
        reopenUntil = System.currentTimeMillis() + timeoutMillis;
        useBlock(mc, table);
    }

    /** A plain right-click on a block, aimed at its centre from the side facing the player. */
    static void useBlock(Minecraft mc, BlockPos pos) {
        if (mc.player == null || mc.gameMode == null) return;
        Vec3 center = Vec3.atCenterOf(pos);
        Direction face = Direction.getApproximateNearest(mc.player.getEyePosition().subtract(center));
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(center, face, pos, false));
    }

    /**
     * The one menu a cart or boat opens, so a container the player opens at the same time
     * isn't taken for it. Blocks open many kinds of menu, so any goes for them.
     */
    private static @Nullable MenuType<?> menuType(ContainerKind kind) {
        return switch (kind) {
            case CHEST_MINECART, CHEST_BOAT -> MenuType.GENERIC_9x3;
            case HOPPER_MINECART -> MenuType.HOPPER;
            default -> null;
        };
    }

    /** The hidden menu, once the server has opened it. */
    static @Nullable AbstractContainerMenu hiddenMenu(Minecraft mc) {
        return hidden != null && mc.player != null && mc.player.containerMenu == hidden ? hidden : null;
    }

    /**
     * The menu the last open was answered with, even if a container the player opened has
     * replaced it since. Its contents still arrived first, so it can be read but not clicked.
     */
    static @Nullable AbstractContainerMenu openedMenu() {
        return hidden;
    }

    static boolean isWaiting() {
        return expected != null;
    }

    /** Closes the hidden menu on the server and goes back to the player inventory, without touching the screen. */
    static void closeHidden(Minecraft mc) {
        AbstractContainerMenu menu = hiddenMenu(mc);
        if (menu != null && mc.getConnection() != null) {
            mc.getConnection().send(new ServerboundContainerClosePacket(menu.containerId));
            menu.removed(mc.player);
            mc.player.containerMenu = mc.player.inventoryMenu;
        }
        hidden = null;
        expected = null;
        // The close sound and lid event arrive after the close, so keep hiding them a little longer.
        long until = System.currentTimeMillis() + MUTE_AFTER_CLOSE_MILLIS;
        MUTED.replaceAll(m -> new Muted(m.area(), m.sounds(), m.lid(), Math.max(m.until(), until)));
    }

    /**
     * Gives up on the current container: closes it if it's open, and if the server hasn't
     * answered yet, closes it unseen when it does, so it never pops up on screen.
     */
    static void abandon(Minecraft mc) {
        Expected e = expected;
        closeHidden(mc);
        if (e != null && System.currentTimeMillis() <= e.until()) {
            expected = new Expected(e.until(), -1, true, e.type());
        }
    }

    static void reset() {
        expected = null;
        hidden = null;
        reopening = null;
    }

    // ---- Called from ClientPacketListenerMixin, on the client thread ----

    /** True if this open-screen packet answers our click and should stay hidden. */
    public static boolean onOpenScreen(Minecraft mc, ClientboundOpenScreenPacket packet) {
        if (reopenIntoScreen(mc, packet)) return true;
        Expected e = expected;
        if (e == null || mc.player == null) return false;
        if (System.currentTimeMillis() > e.until()) {
            expected = null;
            return false;
        }
        // Not the menu a cart opens: the player's own container. Show it and keep waiting for ours.
        if (e.type() != null && packet.getType() != e.type()) return false;
        expected = null;
        if (e.discard()) {
            // A failed craft at a table reopens the table, which must show normally.
            if (packet.getType() == MenuType.CRAFTING) return false;
            if (mc.getConnection() != null) mc.getConnection().send(new ServerboundContainerClosePacket(packet.getContainerId()));
            return true;
        }
        AbstractContainerMenu menu = packet.getType().create(packet.getContainerId(), mc.player.getInventory());
        mc.player.containerMenu = menu;
        hidden = menu;
        return true;
    }

    private static boolean reopenIntoScreen(Minecraft mc, ClientboundOpenScreenPacket packet) {
        Screen screen = reopening;
        if (screen == null || packet.getType() != MenuType.CRAFTING || mc.player == null) return false;
        reopening = null;
        if (System.currentTimeMillis() > reopenUntil || mc.gui.screen() != screen
                || !(screen instanceof CraftingScreen crafting)) return false;
        CraftingMenu menu = MenuType.CRAFTING.create(packet.getContainerId(), mc.player.getInventory());
        ((AbstractContainerScreenAccessor) crafting).craftysearch$setMenu(menu);
        RecipeBookComponent<?> book = ((AbstractRecipeBookScreenInvoker) crafting).craftysearch$getRecipeBookComponent();
        ((RecipeBookComponentInvoker) book).craftysearch$setMenu(menu);
        mc.player.containerMenu = menu;
        return true;
    }

    /**
     * True to ignore a close: opening a container closes the crafting table on the server,
     * but its screen stays up until the mod reopens the table.
     */
    public static boolean onContainerClose(ClientboundContainerClosePacket packet) {
        Expected e = expected;
        return e != null && e.closingContainerId() >= 0 && packet.getContainerId() == e.closingContainerId()
                && System.currentTimeMillis() <= e.until();
    }

    public static boolean shouldMuteSound(double x, double y, double z) {
        long now = System.currentTimeMillis();
        MUTED.removeIf(m -> m.until() < now);
        for (Muted m : MUTED) {
            if (m.sounds() && m.area().contains(x, y, z)) return true;
        }
        return false;
    }

    /** Chest and shulker box lids open through block events; hiding them keeps the lid shut for us. */
    public static boolean shouldHideBlockEvent(BlockPos pos) {
        long now = System.currentTimeMillis();
        MUTED.removeIf(m -> m.until() < now);
        Vec3 center = Vec3.atCenterOf(pos);
        for (Muted m : MUTED) {
            if (m.lid() && m.area().contains(center)) return true;
        }
        return false;
    }
}
