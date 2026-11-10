package com.craftysearch.storage;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.StreamCodec;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * Which container something is (spec 5.1): a block position, the lower half for a double
 * chest, or a chest minecart or boat by its entity UUID. Exactly one of the two is set.
 * Shared by memory, live data and the network.
 */
public record ContainerKey(@Nullable BlockPos block, @Nullable UUID entity) {
    public static final StreamCodec<ByteBuf, ContainerKey> STREAM_CODEC = StreamCodec.of(
            (buf, key) -> {
                buf.writeBoolean(key.entity != null);
                if (key.entity != null) UUIDUtil.STREAM_CODEC.encode(buf, key.entity);
                else BlockPos.STREAM_CODEC.encode(buf, key.block);
            },
            buf -> buf.readBoolean() ? ofEntity(UUIDUtil.STREAM_CODEC.decode(buf)) : ofBlock(BlockPos.STREAM_CODEC.decode(buf)));

    public ContainerKey {
        if ((block == null) == (entity == null)) throw new IllegalArgumentException("Either a block or an entity");
    }

    public static ContainerKey ofBlock(BlockPos pos) {
        return new ContainerKey(pos.immutable(), null);
    }

    public static ContainerKey ofEntity(UUID uuid) {
        return new ContainerKey(null, uuid);
    }

    public boolean isEntity() {
        return entity != null;
    }
}
