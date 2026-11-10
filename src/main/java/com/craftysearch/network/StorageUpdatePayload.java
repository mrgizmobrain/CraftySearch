package com.craftysearch.network;

import com.craftysearch.storage.ContainerKey;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Optional;

/**
 * Server to client: changes to the live container data around the player. {@code reset}
 * means drop everything first (first update, new dimension, new radius). Large updates are
 * split over several payloads; only the first can carry {@code reset}.
 */
public record StorageUpdatePayload(boolean reset, List<ContainerKey> removed, List<Container> changed)
        implements CustomPacketPayload {
    public static final Type<StorageUpdatePayload> TYPE = ModPayloads.type("storage_update");

    /** One stack type and how many of it. The stack itself has a count of 1. */
    public record ItemCount(ItemStack stack, int count) {
        static final StreamCodec<RegistryFriendlyByteBuf, ItemCount> CODEC = StreamCodec.composite(
                ItemStack.STREAM_CODEC, ItemCount::stack,
                ByteBufCodecs.VAR_INT, ItemCount::count,
                ItemCount::new);
    }

    /**
     * A container's contents. A double chest is one container; {@code other} is its second
     * half. For a chest minecart or boat, {@code key} names the entity and {@code pos} is
     * the block it's in now.
     */
    public record Container(ContainerKey key, BlockPos pos, Optional<BlockPos> other, int kind, List<ItemCount> items) {
        static final StreamCodec<RegistryFriendlyByteBuf, Container> CODEC = StreamCodec.composite(
                ContainerKey.STREAM_CODEC, Container::key,
                BlockPos.STREAM_CODEC, Container::pos,
                ByteBufCodecs.optional(BlockPos.STREAM_CODEC), Container::other,
                ByteBufCodecs.VAR_INT, Container::kind,
                ItemCount.CODEC.apply(ByteBufCodecs.list()), Container::items,
                Container::new);
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, StorageUpdatePayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, StorageUpdatePayload::reset,
            ContainerKey.STREAM_CODEC.apply(ByteBufCodecs.list()), StorageUpdatePayload::removed,
            Container.CODEC.apply(ByteBufCodecs.list()), StorageUpdatePayload::changed,
            StorageUpdatePayload::new);

    @Override
    public Type<StorageUpdatePayload> type() {
        return TYPE;
    }
}
