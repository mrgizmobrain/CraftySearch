package com.craftysearch.network;

import com.craftysearch.storage.PutAwayRule;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;

/**
 * Client to server: store items in storage, sorted by {@link PutAwayRule} with the client's
 * fallback (spec 3.4). They come from each of {@code slots} in turn: the open menu's cursor
 * stack for {@link #CURSOR}, otherwise that main-inventory slot; {@code amount} of them, or the
 * whole stack when it's 0. Several slots in one request (Nov 8): put-aways waiting to be sent
 * are merged, so a shift-double-click stores a full inventory in one go rather than a stack a tick.
 */
public record PutAwayPayload(List<Integer> slots, int amount, int fallback, int radius, int kindsMask)
        implements CustomPacketPayload {
    public static final int CURSOR = -1;
    public static final int WHOLE_STACK = 0;
    /** The main inventory and the cursor. */
    public static final int MAX_SLOTS = 37;

    public static final Type<PutAwayPayload> TYPE = ModPayloads.type("put_away");
    public static final StreamCodec<RegistryFriendlyByteBuf, PutAwayPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(MAX_SLOTS)), PutAwayPayload::slots,
            ByteBufCodecs.VAR_INT, PutAwayPayload::amount,
            ByteBufCodecs.VAR_INT, PutAwayPayload::fallback,
            ByteBufCodecs.VAR_INT, PutAwayPayload::radius,
            ByteBufCodecs.VAR_INT, PutAwayPayload::kindsMask,
            PutAwayPayload::new);

    public PutAwayPayload(int slot, int amount, int fallback, int radius, int kindsMask) {
        this(List.of(slot), amount, fallback, radius, kindsMask);
    }

    public PutAwayRule.Fallback fallbackMode() {
        PutAwayRule.Fallback[] values = PutAwayRule.Fallback.values();
        return fallback >= 0 && fallback < values.length ? values[fallback] : PutAwayRule.Fallback.COPPER_CHEST;
    }

    @Override
    public Type<PutAwayPayload> type() {
        return TYPE;
    }
}
