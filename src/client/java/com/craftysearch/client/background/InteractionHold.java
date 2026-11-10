package com.craftysearch.client.background;

/**
 * Keeps the panel's tiles and the recipe book from re-sorting under the cursor: while a
 * background job runs or waits, and for a moment after each click, layouts hold still and
 * only counts update. Rapid clicks then keep landing on the same thing.
 */
public final class InteractionHold {
    private static final long HOLD_MILLIS = 1500;
    private static long lastClickAt;

    private InteractionHold() {
    }

    /** Call on every click that can change storage, the inventory or what's craftable. */
    public static void clicked() {
        lastClickAt = System.currentTimeMillis();
    }

    public static boolean held() {
        return BackgroundJobs.isBusy() || System.currentTimeMillis() - lastClickAt < HOLD_MILLIS;
    }
}
