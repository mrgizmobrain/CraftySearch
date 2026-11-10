package com.craftysearch.client.highlight;

import com.craftysearch.CraftySearch;
import com.craftysearch.client.compat.ShaderPacks;
import com.craftysearch.client.config.ClientConfig;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A marker with the count over each highlighted container, drawn on the HUD where the
 * outline's middle is on screen. Shader packs draw the outlines with their own shaders, which
 * don't keep them on top, so walls hide them; the HUD isn't touched by shaders (Nov 8).
 */
public final class HighlightMarkers {
    private static final Identifier ID = Identifier.fromNamespaceAndPath(CraftySearch.MOD_ID, "highlight_markers");
    /** The outline's green. */
    private static final int COLOR = 0xFF4CFF4C;
    private static final int BORDER_COLOR = 0xFF000000;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    /** Points this close to the camera plane or behind it aren't on screen. */
    private static final float MIN_W = 0.05f;

    private HighlightMarkers() {
    }

    public static void register() {
        // First, so the hotbar, chat and the rest draw over the markers.
        HudElementRegistry.addFirst(ID, HighlightMarkers::extract);
    }

    /** Whether markers show now; the outlines then leave out their own labels. */
    static boolean shown() {
        return switch (ClientConfig.get().highlightMarkers) {
            case ALWAYS -> true;
            case WITH_SHADERS -> ShaderPacks.inUse();
            case OFF -> false;
        };
    }

    private static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
        List<Highlights.Marker> markers = Highlights.markers();
        if (markers.isEmpty() || !shown()) return;
        Minecraft mc = Minecraft.getInstance();
        Camera camera = mc.gameRenderer.mainCamera();
        if (!camera.isInitialized()) return;
        Vec3 eye = camera.position();
        Matrix4f viewProjection = camera.getViewRotationProjectionMatrix(new Matrix4f());
        int width = g.guiWidth();
        int height = g.guiHeight();

        // Far ones first, so nearer markers draw over them.
        List<Highlights.Marker> sorted = new ArrayList<>(markers);
        sorted.sort(Comparator.comparingDouble((Highlights.Marker m) -> m.center().distanceToSqr(eye)).reversed());
        Vector4f point = new Vector4f();
        for (Highlights.Marker marker : sorted) {
            Vec3 center = marker.center();
            point.set((float) (center.x - eye.x), (float) (center.y - eye.y), (float) (center.z - eye.z), 1f);
            viewProjection.transform(point);
            if (point.w < MIN_W) continue;
            float ndcX = point.x / point.w;
            float ndcY = point.y / point.w;
            if (Math.abs(ndcX) > 1 || Math.abs(ndcY) > 1) continue;
            int x = Math.round((ndcX + 1) / 2 * width);
            int y = Math.round((1 - ndcY) / 2 * height);
            draw(g, mc.font, x, y, marker);
        }
    }

    /** A small green square with a black edge, and the count under it ("×N" for a stack of carts). */
    private static void draw(GuiGraphicsExtractor g, Font font, int x, int y, Highlights.Marker marker) {
        g.fill(x - 3, y - 3, x + 4, y + 4, BORDER_COLOR);
        g.fill(x - 2, y - 2, x + 3, y + 3, COLOR);
        String text = marker.stackSize() > 1 ? marker.count() + " ×" + marker.stackSize() : marker.count();
        g.centeredText(font, text, x, y + 6, TEXT_COLOR);
    }
}
