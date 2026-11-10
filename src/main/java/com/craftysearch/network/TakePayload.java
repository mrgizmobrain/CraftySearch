package com.craftysearch.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Client to server: take items of panel tiles from storage (spec 3.4), each of {@code items}
 * in turn. An item is the tile's item (count 1, durability ignored when matching), or with
 * {@link #EXACT} the exact item to match, components and all. It takes a stack, or exactly
 * {@code amount} when that's above 0 (the scroll wheel, spec 3.5, which may ask for several
 * stacks), or with {@link #ALL} as much as fits in the inventory. With {@link #TO_CURSOR} it
 * goes onto the cursor of the open menu (one item only; onto the held stack if it's the same
 * item), otherwise into the inventory, into main-inventory slot {@code targetSlot} first when
 * that's 0 or more. With {@link #DROP} it's thrown on the ground in front of the player. With
 * {@link #KEEP_ONE}, each container keeps one ({@link com.craftysearch.storage.KeepOne}).
 * {@code lockedSlots} has a bit set for each main-inventory slot (0-35) nothing may go into
 * (another mod's locked slots, such as Inventory Profiles Next's). Several items in one
 * request (Nov 9): whole-stack takes waiting to be sent, as from a shift-drag over tiles, are
 * merged.
 */
public record TakePayload(List<ItemStack> items, int flags, int radius, int kindsMask, int amount, int targetSlot,
                          long lockedSlots)
        implements CustomPacketPayload {
    public static final int TO_CURSOR = 2;
    public static final int EXACT = 4;
    public static final int KEEP_ONE = 8;
    public static final int ALL = 16;
    public static final int DROP = 32;
    public static final int NO_SLOT = -1;
    /** More than any panel shows in one shift-drag, and still a small packet. */
    public static final int MAX_ITEMS = 64;

    public static final Type<TakePayload> TYPE = ModPayloads.type("take");
    public static final StreamCodec<RegistryFriendlyByteBuf, TakePayload> CODEC = StreamCodec.composite(
            ItemStack.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_ITEMS)), TakePayload::items,
            ByteBufCodecs.VAR_INT, TakePayload::flags,
            ByteBufCodecs.VAR_INT, TakePayload::radius,
            ByteBufCodecs.VAR_INT, TakePayload::kindsMask,
            ByteBufCodecs.VAR_INT, TakePayload::amount,
            ByteBufCodecs.VAR_INT, TakePayload::targetSlot,
            ByteBufCodecs.VAR_LONG, TakePayload::lockedSlots,
            TakePayload::new);

    public TakePayload(ItemStack item, int flags, int radius, int kindsMask, int amount, int targetSlot, long lockedSlots) {
        this(List.of(item), flags, radius, kindsMask, amount, targetSlot, lockedSlots);
    }

    public boolean has(int flag) {
        return (flags & flag) != 0;
    }

    @Override
    public Type<TakePayload> type() {
        return TYPE;
    }
}
