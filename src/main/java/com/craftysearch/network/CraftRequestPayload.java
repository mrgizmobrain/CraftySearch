package com.craftysearch.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;

import java.util.List;

/**
 * Client to server: a recipe book click in server mode, sent in place of vanilla's
 * place-recipe packet. The server pulls missing ingredients from storage, places the
 * recipe like vanilla, and with {@link #CRAFT_TO_INVENTORY} crafts it into the inventory.
 * {@code steps} are intermediates to craft into the inventory first (spec 3.6), in order.
 * {@code lockedSlots}: main-inventory slots pulled ingredients and leftovers may not go into,
 * as in {@link TakePayload}.
 */
public record CraftRequestPayload(int containerId, RecipeDisplayId recipe, int flags, int radius, int kindsMask,
                                  List<Step> steps, long lockedSlots)
        implements CustomPacketPayload {
    /** Shift-click: as many as fit in the grid. */
    public static final int USE_MAX = 1;
    /** Take the result into the inventory instead of leaving the recipe in the grid. */
    public static final int CRAFT_TO_INVENTORY = 2;
    /** Use storage before the inventory. */
    public static final int NEAREST_FIRST = 4;
    /** Each container keeps one of each stackable item ({@link com.craftysearch.storage.KeepOne}). */
    public static final int KEEP_ONE = 8;
    /** The most intermediate steps one request may carry. */
    public static final int MAX_STEPS = 12;

    /** Craft {@code recipe} {@code times} times into the inventory. */
    public record Step(RecipeDisplayId recipe, int times) {
        static final StreamCodec<RegistryFriendlyByteBuf, Step> CODEC = StreamCodec.composite(
                RecipeDisplayId.STREAM_CODEC, Step::recipe,
                ByteBufCodecs.VAR_INT, Step::times,
                Step::new);
    }

    public static final Type<CraftRequestPayload> TYPE = ModPayloads.type("craft_request");
    public static final StreamCodec<RegistryFriendlyByteBuf, CraftRequestPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, CraftRequestPayload::containerId,
            RecipeDisplayId.STREAM_CODEC, CraftRequestPayload::recipe,
            ByteBufCodecs.VAR_INT, CraftRequestPayload::flags,
            ByteBufCodecs.VAR_INT, CraftRequestPayload::radius,
            ByteBufCodecs.VAR_INT, CraftRequestPayload::kindsMask,
            Step.CODEC.apply(ByteBufCodecs.list(MAX_STEPS)), CraftRequestPayload::steps,
            ByteBufCodecs.VAR_LONG, CraftRequestPayload::lockedSlots,
            CraftRequestPayload::new);

    public boolean has(int flag) {
        return (flags & flag) != 0;
    }

    @Override
    public Type<CraftRequestPayload> type() {
        return TYPE;
    }
}
