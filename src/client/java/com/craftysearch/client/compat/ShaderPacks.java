package com.craftysearch.client.compat;

import com.craftysearch.CraftySearch;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Whether a shader pack is on, from Iris's public API. Iris is optional, so it's looked up by
 * name; without Iris, or if its API ever changes, this is simply false.
 */
public final class ShaderPacks {
    private static final String IRIS_API = "net.irisshaders.iris.api.v0.IrisApi";

    private static boolean looked;
    private static @Nullable Object api;
    private static @Nullable MethodHandle isShaderPackInUse;

    private ShaderPacks() {
    }

    public static boolean inUse() {
        if (!looked) lookUp();
        if (api == null || isShaderPackInUse == null) return false;
        try {
            return (boolean) isShaderPackInUse.invoke(api);
        } catch (Throwable e) {
            CraftySearch.LOGGER.warn("Couldn't ask Iris whether a shader pack is on; assuming not", e);
            api = null;
            return false;
        }
    }

    private static void lookUp() {
        looked = true;
        if (!FabricLoader.getInstance().isModLoaded("iris")) return;
        try {
            Class<?> type = Class.forName(IRIS_API);
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            api = lookup.findStatic(type, "getInstance", MethodType.methodType(type)).invoke();
            isShaderPackInUse = lookup.findVirtual(type, "isShaderPackInUse", MethodType.methodType(boolean.class));
        } catch (Throwable e) {
            CraftySearch.LOGGER.warn("Iris is installed but its API wasn't found; highlight markers won't follow shader packs", e);
            api = null;
        }
    }
}
