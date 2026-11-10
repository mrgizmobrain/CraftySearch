package com.craftysearch.server;

import com.craftysearch.ServerConfig;
import com.craftysearch.network.OpenContainerPayload;
import com.craftysearch.server.StorageScanner.Found;
import com.craftysearch.storage.ContainerKind;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.Nameable;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Opening a container from the panel, from anywhere within the radius (Nov 9): the menu the
 * container would open, on a wrapper that stays valid while the player is within the
 * server's radius instead of vanilla's few blocks. The lid stays shut: the wrapper doesn't
 * pass on the open and close, which would also count the player as using a chest they
 * aren't next to.
 */
public final class RemoteOpen {
    /** How far past the server's radius an opened container stays open, so a step back doesn't close it. */
    private static final int KEEP_OPEN_MARGIN = 4;

    private RemoteOpen() {
    }

    public static void open(ServerPlayer player, OpenContainerPayload request) {
        ServerConfig config = ServerConfig.get();
        if (!config.enabled || player.isSpectator()) return;
        if (!config.allowRemoteOpen) {
            message(player, "open_disabled");
            return;
        }
        // A held stack would be left behind on the menu being replaced; the client never asks then.
        if (!player.containerMenu.getCarried().isEmpty()) return;
        // The same checks as live data: in range, a kind the player searches, not claimed or locked.
        Found target = null;
        for (Found f : StorageScanner.scan(player, StorageSync.clampRadius(request.radius()),
                ContainerKind.fromMask(request.kindsMask()))) {
            if (f.key().equals(request.key())) {
                target = f;
                break;
            }
        }
        if (target == null) {
            message(player, "cannot_open");
            return;
        }
        List<Container> halves = target.halves();
        Container inner = halves.size() == 2 ? new CompoundContainer(halves.get(0), halves.get(1)) : halves.getFirst();
        Remote remote = new Remote(inner, target, config.maxRadius + KEEP_OPEN_MARGIN);
        MenuKind kind = menuKind(target.kind(), inner.getContainerSize());
        if (kind == null) {
            message(player, "cannot_open");
            return;
        }
        player.openMenu(new SimpleMenuProvider((id, inventory, p) -> menu(id, inventory, kind, remote), title(target)));
    }

    private enum MenuKind { HOPPER, DISPENSER, SHULKER_BOX, THREE_ROWS, SIX_ROWS }

    /** The menu vanilla opens for this kind of container. */
    private static AbstractContainerMenu menu(int id, Inventory inventory, MenuKind kind, Container container) {
        return switch (kind) {
            case HOPPER -> new HopperMenu(id, inventory, container);
            case DISPENSER -> new DispenserMenu(id, inventory, container);
            case SHULKER_BOX -> new ShulkerBoxMenu(id, inventory, container);
            case THREE_ROWS -> ChestMenu.threeRows(id, inventory, container);
            case SIX_ROWS -> ChestMenu.sixRows(id, inventory, container);
        };
    }

    /** Null if the container isn't the usual size for its kind (vanilla's menus would refuse it). */
    private static @Nullable MenuKind menuKind(ContainerKind kind, int size) {
        return switch (kind) {
            case HOPPER, HOPPER_MINECART -> size == 5 ? MenuKind.HOPPER : null;
            case DROPPER, DISPENSER -> size == 9 ? MenuKind.DISPENSER : null;
            case SHULKER_BOX -> size == 27 ? MenuKind.SHULKER_BOX : null;
            default -> size == 54 ? MenuKind.SIX_ROWS : size == 27 ? MenuKind.THREE_ROWS : null;
        };
    }

    /** The name vanilla shows: a custom name, a double chest's own title, or the block's or entity's name. */
    private static Component title(Found found) {
        if (found.entity() != null) return found.entity().getDisplayName();
        for (Container half : found.halves()) {
            if (half instanceof Nameable named && named.hasCustomName()) return named.getDisplayName();
        }
        if (found.halves().size() == 2) return Component.translatable("container.chestDouble");
        return found.halves().getFirst() instanceof Nameable named ? named.getDisplayName()
                : Component.translatable("container.chest");
    }

    private static void message(ServerPlayer player, String key) {
        player.sendOverlayMessage(Component.translatable("craftysearch.storage." + key));
    }

    /** The container as it is, valid while it exists and the player stays within {@code reach} blocks of it. */
    private static final class Remote implements Container {
        private final Container inner;
        private final Found found;
        private final int reach;

        Remote(Container inner, Found found, int reach) {
            this.inner = inner;
            this.found = found;
            this.reach = reach;
        }

        @Override
        public boolean stillValid(Player player) {
            Entity entity = found.entity();
            if (entity != null) {
                return !entity.isRemoved() && entity.level() == player.level() && near(player, entity.blockPosition());
            }
            for (Container half : found.halves()) {
                if (half instanceof BlockEntity be && (be.isRemoved() || be.getLevel() != player.level())) return false;
            }
            return near(player, found.pos());
        }

        private boolean near(Player player, BlockPos pos) {
            BlockPos at = player.blockPosition();
            return Math.abs(at.getX() - pos.getX()) <= reach && Math.abs(at.getY() - pos.getY()) <= reach
                    && Math.abs(at.getZ() - pos.getZ()) <= reach;
        }

        @Override
        public int getContainerSize() {
            return inner.getContainerSize();
        }

        @Override
        public boolean isEmpty() {
            return inner.isEmpty();
        }

        @Override
        public ItemStack getItem(int slot) {
            return inner.getItem(slot);
        }

        @Override
        public ItemStack removeItem(int slot, int count) {
            return inner.removeItem(slot, count);
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            return inner.removeItemNoUpdate(slot);
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            inner.setItem(slot, stack);
        }

        @Override
        public int getMaxStackSize() {
            return inner.getMaxStackSize();
        }

        @Override
        public int getMaxStackSize(ItemStack stack) {
            return inner.getMaxStackSize(stack);
        }

        @Override
        public void setChanged() {
            inner.setChanged();
        }

        @Override
        public boolean canPlaceItem(int slot, ItemStack stack) {
            return inner.canPlaceItem(slot, stack);
        }

        @Override
        public boolean canTakeItem(Container into, int slot, ItemStack stack) {
            return inner.canTakeItem(into, slot, stack);
        }

        @Override
        public void clearContent() {
            inner.clearContent();
        }
    }
}
