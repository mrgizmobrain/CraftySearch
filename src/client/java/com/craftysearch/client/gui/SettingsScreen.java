package com.craftysearch.client.gui;

import com.craftysearch.client.config.ClientConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A page of settings: a title, widgets two to a row, and Done. Changes save when the page
 * closes. A setting's label is {@code craftysearch.config.<key>}, and it has a tooltip when
 * the language file has {@code craftysearch.config.<key>.tooltip}.
 */
abstract class SettingsScreen extends Screen {
    static final int COLUMN_WIDTH = 150;
    // 22 rather than vanilla's 24 keeps every row on screen at GUI scale 4 on a 1080p display.
    private static final int ROW_HEIGHT = 22;
    private static final int WIDGET_HEIGHT = 20;
    /** Half the space between the two columns. */
    private static final int COLUMN_MARGIN = 5;
    private static final int TOP = 32;
    /** A page with a note under its title starts lower. */
    private static final int TOP_WITH_NOTE = 44;
    private static final String PREFIX = "craftysearch.config.";

    private final Screen parent;
    private final @Nullable Component note;
    private int y;
    private boolean rightColumn;

    /** {@code titleKey} and {@code noteKey} (a grey line under the title, or null) are keys under craftysearch.config. */
    SettingsScreen(Screen parent, String titleKey, @Nullable String noteKey) {
        super(Component.translatable(PREFIX + titleKey));
        this.parent = parent;
        this.note = noteKey == null ? null : Component.translatable(PREFIX + noteKey);
    }

    @Override
    protected final void init() {
        y = note == null ? TOP : TOP_WITH_NOTE;
        rightColumn = false;
        addSettings(ClientConfig.get());
        endRow();
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(width / 2 - 100, Math.min(y + 6, height - 26), 200, WIDGET_HEIGHT)
                .build());
    }

    /** Adds the page's widgets with {@link #add}, in reading order. */
    protected abstract void addSettings(ClientConfig config);

    /** Puts the widget in the next free cell: left, then right, then the next row. */
    protected final void add(AbstractWidget widget) {
        widget.setX(rightColumn ? width / 2 + COLUMN_MARGIN : width / 2 - COLUMN_WIDTH - COLUMN_MARGIN);
        widget.setY(y);
        addRenderableWidget(widget);
        if (rightColumn) y += ROW_HEIGHT;
        rightColumn = !rightColumn;
    }

    /** Ends the row, half-filled or not, and leaves {@code pixels} of space before the next. */
    protected final void gap(int pixels) {
        endRow();
        y += pixels;
    }

    private void endRow() {
        if (rightColumn) {
            y += ROW_HEIGHT;
            rightColumn = false;
        }
    }

    /** An on/off setting. */
    protected static CycleButton<Boolean> toggle(String key, boolean value, Consumer<Boolean> set) {
        return create(CycleButton.onOffBuilder(value), key, set);
    }

    /** A choice among an enum's values, each labelled {@code craftysearch.config.<key>.<value>}. */
    protected static <E extends Enum<E>> CycleButton<E> choice(String key, E[] values, E value, Consumer<E> set) {
        return choice(key, key, values, value, set);
    }

    /** A choice whose values are labelled {@code craftysearch.config.<valuesKey>.<value>}, for labels shared or named apart. */
    protected static <E extends Enum<E>> CycleButton<E> choice(String key, String valuesKey, E[] values, E value, Consumer<E> set) {
        CycleButton.Builder<E> builder = CycleButton.builder(
                (E e) -> Component.translatable(PREFIX + valuesKey + "." + e.name().toLowerCase(Locale.ROOT)), value);
        return create(builder.withValues(values), key, set);
    }

    private static <T> CycleButton<T> create(CycleButton.Builder<T> builder, String key, Consumer<T> set) {
        String tooltip = PREFIX + key + ".tooltip";
        if (Language.getInstance().has(tooltip)) {
            builder = builder.withTooltip(value -> Tooltip.create(Component.translatable(tooltip)));
        }
        return builder.create(0, 0, COLUMN_WIDTH, WIDGET_HEIGHT, Component.translatable(PREFIX + key), (button, value) -> set.accept(value));
    }

    /** A button that opens another settings page, which comes back to this one. */
    protected final Button page(String key, Function<Screen, Screen> page) {
        return Button.builder(Component.translatable(PREFIX + key), b -> minecraft.gui.setScreen(page.apply(this)))
                .bounds(0, 0, COLUMN_WIDTH, WIDGET_HEIGHT)
                .build();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(font, title, width / 2, 12, 0xFFFFFFFF);
        if (note != null) g.centeredText(font, note, width / 2, 26, 0xFFA0A0A0);
    }

    @Override
    public void onClose() {
        ClientConfig.save();
        saved();
        minecraft.gui.setScreen(parent);
    }

    /** Called after the config is saved, for settings something else has to hear about. */
    protected void saved() {
    }
}
