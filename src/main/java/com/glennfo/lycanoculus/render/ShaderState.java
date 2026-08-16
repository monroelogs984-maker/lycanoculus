package com.glennfo.lycanoculus.render;

import com.lycanitesmobs.client.compatibility.OculusCompat;

/**
 * Cached shader-pack-active check shared by the mixins.
 *
 * isShaderPackActive() would otherwise be called per part / per creature per pass — cache it
 * with a long TTL since shader pack state only changes when the user manually opens the
 * shader menu. isRenderingShadowPass() must NOT be cached (the shadow/main pass boundary
 * happens mid-frame), so callers query IrisHelper for that directly.
 */
public final class ShaderState {

    private static final long CACHE_TTL_NS = 5_000_000_000L; // 5 seconds

    private static boolean cachedActive;
    private static long cacheExpiry;

    private ShaderState() {
    }

    public static boolean isShaderPackActive() {
        long now = System.nanoTime();
        if (now > cacheExpiry) {
            cachedActive = OculusCompat.isShaderPackActive();
            cacheExpiry = now + CACHE_TTL_NS;
        }
        return cachedActive;
    }
}
