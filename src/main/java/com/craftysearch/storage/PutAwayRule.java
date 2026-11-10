package com.craftysearch.storage;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Where put-away stores an item, in the order to try (spec 3.4), much like a copper golem
 * sorts. The server applies it to live containers, the client to remembered ones.
 */
public final class PutAwayRule {
    /** What to do when no container already holds the item. */
    public enum Fallback {
        /** The nearest copper chest, so a copper golem can carry the item home. */
        @SerializedName("copper_chest") COPPER_CHEST,
        @SerializedName("nearest") NEAREST,
        @SerializedName("empty") EMPTY
    }

    /** A possible target, described for one item. */
    public interface Candidate {
        /** {@link PutAwayMatch#of}: lower is better, {@link PutAwayMatch#NONE} for no link. */
        int match();

        boolean isCopperChest();

        boolean isEmpty();

        boolean hasRoom();
    }

    private PutAwayRule() {
    }

    /** {@code nearestFirst} must be sorted by distance. Returns the targets to try, best first. */
    public static <C extends Candidate> List<C> order(List<C> nearestFirst, Fallback fallback) {
        Set<C> order = new LinkedHashSet<>();
        // 1. Where the item already lives, best match first (loose, then in shulker boxes; see
        // PutAwayMatch), nearest first within each. Copper chests count like any other here, so
        // storage built from both kinds works (Nov 8; they used to be skipped as an inbox).
        for (int match = PutAwayMatch.SAME; match <= PutAwayMatch.ANY_BOX; match++) {
            int wanted = match;
            addAll(order, nearestFirst, c -> c.hasRoom() && c.match() == wanted);
        }
        // 2. The fallback, then anything with room.
        switch (fallback) {
            case COPPER_CHEST -> addAll(order, nearestFirst, c -> c.hasRoom() && c.isCopperChest());
            case EMPTY -> addAll(order, nearestFirst, Candidate::isEmpty);
            case NEAREST -> {
            }
        }
        addAll(order, nearestFirst, Candidate::hasRoom);
        return new ArrayList<>(order);
    }

    private static <C> void addAll(Set<C> order, List<C> candidates, Predicate<C> filter) {
        for (C c : candidates) {
            if (filter.test(c)) order.add(c);
        }
    }
}
