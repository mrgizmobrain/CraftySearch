package com.craftysearch.client.background;

import com.craftysearch.CraftySearch;
import com.craftysearch.client.config.ClientConfig;
import com.craftysearch.client.gui.PanelHooks;
import com.craftysearch.client.gui.StorageScreen;
import com.craftysearch.client.memory.ContainerRecorder;
import com.craftysearch.client.memory.MemoryEntry;
import com.craftysearch.client.mixin.AbstractContainerScreenAccessor;
import com.craftysearch.client.mixin.AbstractRecipeBookScreenInvoker;
import com.craftysearch.client.mixin.RecipeBookComponentInvoker;
import com.craftysearch.client.recipe.RecipeStorage;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CraftingTableBlock;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Runs {@link BackgroundJob}s one at a time from the inventory, crafting table or storage
 * screen (spec 4.2). Containers open with a normal right-click and stay hidden; at a
 * crafting table the server closes the table, so it's reopened before the job finishes.
 * Requests made while a job runs wait in a queue. At normal speed it does at most one step
 * per tick, paced by {@code clientOnly.packetDelayMs}; at fast speed it runs steps until it
 * has to wait for the server.
 */
public final class BackgroundJobs {
    static final long TIMEOUT_MILLIS = 2000;
    private static final int MAX_QUEUE = 64;
    /** Fast speed's cap on steps in one tick, in case a job never waits. */
    private static final int MAX_STEPS_PER_TICK = 64;
    /** How far the recipe book's category tabs reach left of its page. */
    private static final int RECIPE_TAB_WIDTH = 35;
    /** How long after a right-click the player counts as busy, enough for a container to open. */
    private static final long PLAYER_GRACE_MILLIS = 500;
    /** An automatic job set aside for longer than this is dropped; the player has likely moved on. */
    private static final long SUSPEND_LIMIT_MILLIS = 60_000;

    /** The crafting table each crafting screen was opened from, so it can be reopened. */
    private static final Map<Screen, BlockPos> CRAFTING_TABLES = new WeakHashMap<>();
    private static final Deque<Request> QUEUE = new ArrayDeque<>();
    private static @Nullable Running running;
    /** The running job's last progress line, shown on between queued jobs. */
    private static @Nullable Component lastLine;
    /** An automatic job that stepped aside for the player, with the containers it hadn't read yet. */
    private static @Nullable BackgroundJob suspended;
    private static long suspendedAt;
    private static long playerUsedAt;

    enum Start { STARTED, REFUSED, NOT_APPLICABLE }

    /** A click waiting its turn. It's planned when it starts, so it sees what earlier jobs changed. */
    @FunctionalInterface
    interface Request {
        /** The job to run, or null after doing it directly or telling the player why there's nothing to do. */
        @Nullable BackgroundJob create(Minecraft mc);

        /**
         * True if this waiting request takes over {@code next} as well, such as another notch of
         * the scroll wheel on the same stack, so one job does both and each container opens once.
         */
        default boolean absorb(Request next) {
            return false;
        }
    }

    private enum Step { PREPARE, OPEN, WAIT_OPEN, WORK, CLOSE, REOPEN, WAIT_REOPEN, FINISH }

    private static final class Running {
        final BackgroundJob job;
        final @Nullable BlockPos table;
        Screen screen;
        Step step = Step.PREPARE;
        int next;
        @Nullable MemoryEntry current;
        long waitUntil;
        long nextActionAt;
        /** Counts actions, so a fast tick can tell a step that did something from one that waits. */
        int actions;
        /** The server closed the crafting table when the first container opened. */
        boolean tableClosed;
        /** The crafting menu the server closed, until the reopened one replaces it. */
        @Nullable AbstractContainerMenu closedMenu;
        /** An automatic job asked to step aside for a request the player made. */
        boolean yieldRequested;

        Running(BackgroundJob job, Screen screen, @Nullable BlockPos table) {
            this.job = job;
            this.screen = screen;
            this.table = table;
        }
    }

    private BackgroundJobs() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register(BackgroundJobs::afterInit);
        ClientTickEvents.END_CLIENT_TICK.register(StackScan::tick);
        ClientTickEvents.END_CLIENT_TICK.register(ContainerWatch::tick);
        ClientTickEvents.END_CLIENT_TICK.register(BackgroundJobs::tick);
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> playerInteracted(level));
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> playerInteracted(level));
        UseItemCallback.EVENT.register((player, level, hand) -> playerInteracted(level));
    }

    /**
     * The player right-clicked something. The server lets a player have one container open
     * at a time, so an automatic job (reading stacked carts) steps aside: it finishes the cart
     * it's opening and stops before the next, and the rest waits until the player is done
     * ({@link #playerBusy}). The mod's own clicks on blocks pass through here too, but only
     * jobs from a screen make them, and those aren't automatic.
     */
    private static InteractionResult playerInteracted(Level level) {
        if (level.isClientSide()) playerUsedAt = System.currentTimeMillis();
        return InteractionResult.PASS;
    }

    /**
     * True while the player may be opening something, or has their own container open: a
     * right-click in the last moment, or an open menu that isn't the inventory or a hidden one.
     */
    private static boolean playerBusy(Minecraft mc, long now) {
        if (now - playerUsedAt < PLAYER_GRACE_MILLIS) return true;
        return mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu && BackgroundAccess.hiddenMenu(mc) == null;
    }

    /** Starts an automatic job now if nothing else runs, otherwise after the player's jobs. Replaces one set aside earlier. */
    static void submitAutomatic(Minecraft mc, BackgroundJob job) {
        suspended = null;
        if (!isBusy() && !playerBusy(mc, System.currentTimeMillis())) {
            start(mc, job);
        } else {
            setAside(job);
        }
    }

    private static void setAside(@Nullable BackgroundJob job) {
        suspended = job;
        suspendedAt = System.currentTimeMillis();
    }

    /** Picks up an automatic job that stepped aside, once nothing else runs and the player is done. */
    private static void resumeSuspended(Minecraft mc) {
        BackgroundJob job = suspended;
        if (job == null) return;
        long now = System.currentTimeMillis();
        if (mc.player == null || now - suspendedAt > SUSPEND_LIMIT_MILLIS) {
            suspended = null;
            return;
        }
        if (playerBusy(mc, now)) return;
        suspended = null;
        start(mc, job);
    }

    /**
     * Spec 3.5: a container click that isn't the mod's own while a job may have its own menu
     * open on the server. The screen's menu is then closed there, so another mod's click (an
     * auto-refill, a drag that began before the job) would land in the hidden container with
     * the wrong slot numbers. An automatic job only holds a container for about one round trip.
     */
    public static boolean blocksForeignClick() {
        Running r = running;
        if (r == null || Parking.isOwnClick()) return false;
        return !r.job.automatic() || BackgroundAccess.isWaiting() || BackgroundAccess.openedMenu() != null;
    }

    /** True during the mod's own clicks in a container. */
    public static boolean isOwnClick() {
        return Parking.isOwnClick();
    }

    /**
     * True while the player's open menu is a hidden container rather than the one the
     * screen shows. Other mods drawing over the screen's slots by the open menu would draw
     * in the wrong places then (IPN's item highlights, Nov 8).
     */
    public static boolean screenShowsAnotherMenu() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.gui.screen() instanceof AbstractContainerScreen<?> screen
                && mc.player.containerMenu != screen.getMenu();
    }

    /**
     * True while other mods' slot overlays should hold off: for the whole of a job and its
     * queue, not just while a hidden container is open, or they'd flash on between
     * containers (Nov 8).
     */
    public static boolean hidesSlotOverlays() {
        return isBusy() || screenShowsAnotherMenu();
    }

    /** True while a job has the container at {@code pos} (either half of a double chest) open, or is opening it. */
    static boolean hasOpen(BlockPos pos) {
        Running r = running;
        return r != null && r.current != null && !r.current.isEntity() && r.current.contains(pos);
    }

    /** True while a job runs or waits in the queue: new requests should be queued too. */
    public static boolean isBusy() {
        return running != null || !QUEUE.isEmpty();
    }

    /**
     * A line for the screen while a job runs, or null. Between one queued job and the next,
     * a tick passes with none running; the last line stays up meanwhile, so it doesn't blink
     * off and on through a queue (Nov 8).
     */
    public static @Nullable Component progress() {
        Running r = running;
        if (r == null) {
            Component last = lastLine;
            return last == null || QUEUE.isEmpty() ? null : Component.translatable("craftysearch.background.queued", last, QUEUE.size());
        }
        boolean visiting = r.step.ordinal() <= Step.CLOSE.ordinal() && !r.job.containers().isEmpty();
        Component line = r.job.progress(Math.max(1, Math.min(r.next, r.job.containers().size())), r.job.containers().size(), !visiting);
        lastLine = line;
        return QUEUE.isEmpty() ? line : Component.translatable("craftysearch.background.queued", line, QUEUE.size());
    }

    public static void reset() {
        lastLine = null;
        running = null;
        suspended = null;
        QUEUE.clear();
        BackgroundAccess.reset();
        ContainerWatch.clear();
    }

    /** Runs the request now, or queues it behind the running job. An automatic job steps aside for it. */
    static void submit(Minecraft mc, Request request) {
        Running r = running;
        if (r != null && r.job.automatic()) r.yieldRequested = true;
        if (!isBusy()) {
            BackgroundJob job = request.create(mc);
            if (job != null) start(mc, job);
            return;
        }
        Request last = QUEUE.peekLast();
        if (last != null && last.absorb(request)) return;
        if (QUEUE.size() >= MAX_QUEUE) {
            if (mc.player != null) mc.player.sendOverlayMessage(Component.translatable("craftysearch.background.queue_full"));
            return;
        }
        QUEUE.add(request);
    }

    /** Starts the next queued request that has something to do. A refusal (sneaking, say) clears the queue. */
    private static boolean startNext(Minecraft mc) {
        while (running == null && !QUEUE.isEmpty()) {
            BackgroundJob job = QUEUE.poll().create(mc);
            if (job == null) continue;
            if (start(mc, job) != Start.STARTED) {
                QUEUE.clear();
                return false;
            }
        }
        return running != null;
    }

    /**
     * While a job runs, the screen's own menu may be closed on the server, so most clicks
     * are ignored. The recipe book and the storage panel keep working, so their clicks can
     * be queued.
     */
    private static boolean allowedWhileBusy(Screen screen, MouseButtonEvent event) {
        double mouseX = event.x();
        double mouseY = event.y();
        if (PanelHooks.isOverPanel(screen, mouseX, mouseY)) return true;
        // A shift-click that puts an inventory stack away queues like a panel click. At Normal speed the
        // second click of a shift-double-click came while the first stack's job ran, and was dropped (Nov 9).
        if (PanelHooks.queuesPutAway(screen, event)) return true;
        if (screen instanceof StorageScreen storage) return storage.isOverPanel(mouseX, mouseY);
        return screen instanceof AbstractRecipeBookScreen<?> && isOverRecipeBook(
                ((AbstractRecipeBookScreenInvoker) screen).craftysearch$getRecipeBookComponent(), mouseX, mouseY);
    }

    /**
     * A click or key in a screen while an automatic job runs: the job steps aside before its
     * next cart, until the player has paused for a moment. The input goes through unless a
     * cart is open on the server right now (about one round trip), when a click would land in
     * the wrong menu.
     */
    private static boolean allowedForAutomaticJob() {
        Running r = running;
        if (r == null || !r.job.automatic()) return false;
        playerUsedAt = System.currentTimeMillis();
        r.yieldRequested = true;
        return !BackgroundAccess.isWaiting() && BackgroundAccess.openedMenu() == null;
    }

    /** The book's page plus its category tabs, which stick out on the left. */
    private static boolean isOverRecipeBook(RecipeBookComponent<?> book, double mouseX, double mouseY) {
        if (!book.isVisible()) return false;
        RecipeBookComponentInvoker origin = (RecipeBookComponentInvoker) book;
        int left = origin.craftysearch$getXOrigin() - RECIPE_TAB_WIDTH;
        int top = origin.craftysearch$getYOrigin();
        return mouseX >= left && mouseX < origin.craftysearch$getXOrigin() + RecipeBookComponent.IMAGE_WIDTH
                && mouseY >= top && mouseY < top + RecipeBookComponent.IMAGE_HEIGHT;
    }

    private static boolean fast() {
        return ClientConfig.get().clientOnly.speed == ClientConfig.Speed.FAST;
    }

    /**
     * What the player is holding as far as they can see: the screen menu's cursor stack, or
     * the item a running take is bringing to the cursor.
     */
    public static ItemStack heldItem(AbstractContainerMenu screenMenu) {
        if (!screenMenu.getCarried().isEmpty()) return screenMenu.getCarried();
        Running r = running;
        return r == null ? ItemStack.EMPTY : r.job.headingToCursor();
    }

    /** True for the inventory slot whose item the running job shows on the cursor instead. */
    public static boolean hidesSlot(Slot slot) {
        Running r = running;
        return r != null && r.job.handSlot() >= 0 && slot.container instanceof Inventory && slot.getContainerSlot() == r.job.handSlot();
    }

    private static void afterInit(Minecraft mc, Screen screen, int width, int height) {
        if (!(screen instanceof InventoryScreen || screen instanceof CraftingScreen || screen instanceof StorageScreen)) return;
        if (screen instanceof CraftingScreen && mc.level != null) {
            BlockPos pos = ContainerRecorder.recentlyUsedBlock();
            if (pos != null && mc.level.getBlockState(pos).getBlock() instanceof CraftingTableBlock) {
                CRAFTING_TABLES.put(screen, pos);
            }
        }
        // Escape still closes the screen, which cancels the job and clears the queue.
        ScreenMouseEvents.allowMouseClick(screen).register((s, event) ->
                running == null || allowedForAutomaticJob() || allowedWhileBusy(s, event));
        ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) ->
                running == null || allowedForAutomaticJob() || event.isEscape());
        ScreenEvents.afterExtract(screen).register((s, g, mouseX, mouseY, partialTick) -> {
            Component progress = progress();
            if (progress == null) return;
            // The item waiting in the parking slot is really on its way to or from the hand.
            Running r = running;
            if (r != null && r.job.handSlot() >= 0 && mc.player != null && mc.player.containerMenu.getCarried().isEmpty()) {
                ItemStack inHand = mc.player.getInventory().getItem(r.job.handSlot());
                if (!inHand.isEmpty()) {
                    g.item(inHand, mouseX - 8, mouseY - 8);
                    g.itemDecorations(mc.font, inHand, mouseX - 8, mouseY - 8);
                }
            }
            if (s instanceof AbstractContainerScreen<?>) {
                AbstractContainerScreenAccessor bounds = (AbstractContainerScreenAccessor) s;
                g.centeredText(mc.font, progress, bounds.craftysearch$getLeftPos() + bounds.craftysearch$getImageWidth() / 2,
                        bounds.craftysearch$getTopPos() - 12, 0xFFFFFFFF);
            } else {
                g.centeredText(mc.font, progress, s.width / 2, 2, 0xFFFFFFFF);
            }
        });
    }

    /** Starts a job from the current screen (or none, for an automatic one). REFUSED means the player was told why. */
    static Start start(Minecraft mc, BackgroundJob job) {
        if (running != null) return Start.REFUSED;
        LocalPlayer player = mc.player;
        Screen screen = mc.gui.screen();
        if (player == null || (screen == null && !job.automatic())) return Start.NOT_APPLICABLE;
        AbstractContainerMenu menu = player.containerMenu;
        BlockPos table = null;
        if (!job.containers().isEmpty()) {
            // Sneaking makes a right-click on a block use the held item instead of opening it,
            // and the crafting table is a block too. Carts and boats get their own sneak flag.
            boolean clicksBlocks = menu instanceof CraftingMenu || job.containers().stream().anyMatch(e -> !e.isEntity());
            if (clicksBlocks && player.isSecondaryUseActive()) return refuse(player, "sneaking");
            if (menu instanceof CraftingMenu crafting) {
                table = CRAFTING_TABLES.get(screen);
                if (table == null || !RecipeStorage.canReach(player, table)) return refuse(player, "no_table");
                // Closing the table returns the grid to the inventory, and vanilla drops what doesn't fit.
                if (!gridFitsInInventory(crafting, player.getInventory())) return refuse(player, "clear_grid");
            } else if (menu != player.inventoryMenu) {
                return Start.NOT_APPLICABLE;
            }
        }
        running = new Running(job, screen, table);
        return Start.STARTED;
    }

    private static void tick(Minecraft mc) {
        if (!isBusy()) {
            resumeSuspended(mc);
            if (!isBusy()) return;
        }
        if (mc.player == null || mc.getConnection() == null || mc.gameMode == null) {
            reset();
            return;
        }
        // Fast speed keeps going until a step has to wait for the server.
        int budget = fast() ? MAX_STEPS_PER_TICK : 1;
        for (int i = 0; i < budget; i++) {
            if (running == null && !startNext(mc)) return;
            Running r = running;
            Step before = r.step;
            int actions = r.actions;
            step(mc, r);
            if (running == r && r.step == before && r.actions == actions) return;
        }
    }

    private static void step(Minecraft mc, Running r) {
        // The player closed the screen, or something replaced it. Reopening the table replaces it on purpose.
        // An automatic job belongs to no screen: opening the inventory to watch the panel mustn't stop it.
        if (!r.job.automatic() && mc.gui.screen() != r.screen && r.step != Step.WAIT_REOPEN) {
            stop(mc, r, "craftysearch.background.cancelled");
            return;
        }
        long now = System.currentTimeMillis();
        if (now < r.nextActionAt) return;
        switch (r.step) {
            case PREPARE -> prepare(mc, r, now);
            case OPEN -> openNext(mc, r, now);
            case WAIT_OPEN -> waitOpen(mc, r, now);
            case WORK -> work(mc, r, now);
            case CLOSE -> closeCurrent(mc, r, now);
            case REOPEN -> {
                r.closedMenu = ((AbstractContainerScreen<?>) r.screen).getMenu();
                BackgroundAccess.reopenInPlace(mc, r.table, r.screen, TIMEOUT_MILLIS);
                r.step = Step.WAIT_REOPEN;
                r.waitUntil = now + TIMEOUT_MILLIS;
                paced(r, now);
            }
            case WAIT_REOPEN -> waitReopen(mc, r, now);
            case FINISH -> finish(mc, r, now);
        }
    }

    private static void prepare(Minecraft mc, Running r, long now) {
        BackgroundJob.Result result = r.job.prepare(mc, mc.player.containerMenu);
        if (handled(mc, r, result)) return;
        // The table closes when a container opens, and its cursor item would be dropped if the inventory is full.
        if (r.table != null && !mc.player.containerMenu.getCarried().isEmpty()) {
            stop(mc, r, "craftysearch.background.carried");
            return;
        }
        r.step = Step.OPEN;
        paced(r, now);
    }

    private static void openNext(Minecraft mc, Running r, long now) {
        // Between containers is the one moment an automatic job can step aside cleanly.
        if (r.job.automatic() && (r.yieldRequested || playerBusy(mc, now))) {
            running = null;
            setAside(r.job.rest(r.next));
            return;
        }
        if (r.job.satisfied() || r.next >= r.job.containers().size()) {
            r.step = r.tableClosed ? Step.REOPEN : Step.FINISH;
            return;
        }
        MemoryEntry entry = r.job.containers().get(r.next++);
        // The player may have been pushed out of reach since planning, or a cart rolled away; try the next one.
        if (!RecipeStorage.isReachable(mc.player, entry)) return;
        if (!entry.isEntity() && mc.player.isSecondaryUseActive()) {
            stop(mc, r, "craftysearch.background.sneaking");
            return;
        }
        int closing = r.table != null && !r.tableClosed ? mc.player.containerMenu.containerId : -1;
        if (!BackgroundAccess.open(mc, entry, TIMEOUT_MILLIS, closing)) return;
        r.current = entry;
        ContainerWatch.ours(entry);
        if (r.table != null) r.tableClosed = true;
        r.step = Step.WAIT_OPEN;
        r.waitUntil = now + TIMEOUT_MILLIS;
        paced(r, now);
    }

    private static void waitOpen(Minecraft mc, Running r, long now) {
        // A container the player opened meanwhile replaces the hidden one, but its contents
        // came first, so an automatic job (which only reads) can still record it.
        AbstractContainerMenu menu = r.job.automatic() ? BackgroundAccess.openedMenu() : BackgroundAccess.hiddenMenu(mc);
        // State id 0: the contents haven't arrived yet.
        if (menu == null || menu.getStateId() == 0) {
            if (now > r.waitUntil) stop(mc, r, "craftysearch.background.no_open");
            return;
        }
        if (!ContainerRecorder.recordHidden(mc, r.current, menu)) {
            stop(mc, r, "craftysearch.background.unexpected");
            return;
        }
        if (r.job.automatic()) {
            // Reading is all it does, and the contents are in, so close right away. Closing a
            // screen the player opened meanwhile closes whatever menu is open, so a menu left
            // open for another step could be closed under the job.
            BackgroundAccess.closeHidden(mc);
            ContainerWatch.ours(r.current);
            r.current = null;
            r.step = Step.OPEN;
            paced(r, now);
            return;
        }
        r.step = Step.WORK;
    }

    private static void work(Minecraft mc, Running r, long now) {
        AbstractContainerMenu menu = BackgroundAccess.hiddenMenu(mc);
        if (menu == null) {
            stop(mc, r, "craftysearch.background.no_open");
            return;
        }
        BackgroundJob.Result result = r.job.work(mc, menu);
        if (result.kind() == BackgroundJob.Kind.NEXT || result.kind() == BackgroundJob.Kind.DONE) {
            r.step = Step.CLOSE;
            paced(r, now);
            return;
        }
        handled(mc, r, result);
    }

    private static void closeCurrent(Minecraft mc, Running r, long now) {
        AbstractContainerMenu menu = BackgroundAccess.hiddenMenu(mc);
        if (menu != null && r.current != null) ContainerRecorder.recordHidden(mc, r.current, menu);
        BackgroundAccess.closeHidden(mc);
        if (r.current != null) ContainerWatch.ours(r.current);
        r.current = null;
        r.step = Step.OPEN;
        paced(r, now);
    }

    /**
     * Normally the reopened table's menu goes into the screen that's showing; if vanilla
     * built a new crafting screen instead, that one carries on.
     */
    private static void waitReopen(Minecraft mc, Running r, long now) {
        Screen screen = mc.gui.screen();
        if (screen instanceof CraftingScreen crafting && mc.player.containerMenu == crafting.getMenu()
                && crafting.getMenu() != r.closedMenu && crafting.getMenu().getStateId() != 0) {
            r.screen = screen;
            r.closedMenu = null;
            r.tableClosed = false;
            r.step = Step.FINISH;
            return;
        }
        if (now > r.waitUntil) stop(mc, r, "craftysearch.background.no_reopen");
    }

    private static void finish(Minecraft mc, Running r, long now) {
        BackgroundJob.Result result = r.job.finish(mc, mc.player.containerMenu, now);
        handled(mc, r, result);
    }

    /** Applies AGAIN, WAIT, DONE and FAIL. Returns true unless the result was NEXT. */
    private static boolean handled(Minecraft mc, Running r, BackgroundJob.Result result) {
        long now = System.currentTimeMillis();
        switch (result.kind()) {
            case AGAIN -> paced(r, now);
            case WAIT -> {
            }
            case DONE -> end(mc, result.message());
            case FAIL -> stop(mc, r, result.message());
            case NEXT -> {
                return false;
            }
        }
        return true;
    }

    /**
     * Ends a job that went wrong, leaving the player with a working screen. The queue goes
     * too: after a failure (out of ingredients, cancelled) the rest would most likely fail.
     */
    private static void stop(Minecraft mc, Running r, @Nullable String message) {
        BackgroundAccess.abandon(mc);
        if (r.current != null) ContainerWatch.ours(r.current);
        running = null;
        QUEUE.clear();
        // The server closed the table for the first container; its screen is dead, so open the table again.
        if (r.table != null && r.tableClosed && mc.player != null && mc.gui.screen() == r.screen) {
            mc.gui.setScreen(null);
            BackgroundAccess.useBlock(mc, r.table);
        }
        // An automatic job stops quietly, so the log is the only place that says why.
        if (r.job.automatic()) CraftySearch.LOGGER.info("Stopped reading stacked carts: {}", message);
        else if (message != null && mc.player != null) mc.player.sendOverlayMessage(Component.translatable(message));
    }

    private static void end(Minecraft mc, @Nullable String message) {
        running = null;
        if (message != null && mc.player != null) mc.player.sendOverlayMessage(Component.translatable(message));
    }

    private static boolean gridFitsInInventory(AbstractCraftingMenu menu, Inventory inventory) {
        int filled = 0;
        for (Slot slot : menu.getInputGridSlots()) {
            if (slot.hasItem()) filled++;
        }
        int free = 0;
        for (ItemStack stack : inventory.getNonEquipmentItems()) {
            if (stack.isEmpty()) free++;
        }
        return filled <= free;
    }

    private static void paced(Running r, long now) {
        r.actions++;
        r.nextActionAt = fast() ? now : now + ClientConfig.get().clientOnly.packetDelayMs;
    }

    private static Start refuse(LocalPlayer player, String reason) {
        player.sendOverlayMessage(Component.translatable("craftysearch.background." + reason));
        return Start.REFUSED;
    }
}
