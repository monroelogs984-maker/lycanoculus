package com.glennfo.lycanoculus.render;

import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Batches Lycanites Iris/Oculus draw calls per creature render.
 *
 * The default VBOObjModel Iris path does bind→upload→setupRenderState→draw→clearRenderState→unbind
 * for EVERY model part independently. For a creature with N parts and K distinct render types,
 * this reduces that to:
 *   K × (bind + setupRenderState + clearRenderState + unbind) + N × (upload + draw)
 * instead of:
 *   N × (bind + upload + setupRenderState + draw + clearRenderState + unbind)
 *
 * MixinVBOObjModel feeds this class during model.render(). MixinCreatureRenderer calls
 * begin() before and flush() after each model.render() call.
 */
public class IrisBatchRenderer {

    // Per-part vertex buffer — reused across parts (begin/end per part, not per creature)
    public static final BufferBuilder partBuffer = new BufferBuilder(2048);
    private static VertexBuffer batchVbo;

    public static boolean active = false;

    private record PartRender(
            BufferBuilder.RenderedBuffer renderedBuffer,
            RenderType renderType,
            ResourceLocation texture,
            Matrix4f modelView,
            float colorR, float colorG, float colorB, float colorA,
            int fade
    ) {}

    private static final LinkedHashMap<RenderType, List<PartRender>> batches = new LinkedHashMap<>();

    public static void begin() {
        active = true;
    }

    public static void addPart(
            BufferBuilder.RenderedBuffer renderedBuffer,
            RenderType renderType,
            ResourceLocation texture,
            Matrix4f modelView,
            float colorR, float colorG, float colorB, float colorA,
            int fade) {
        batches.computeIfAbsent(renderType, k -> new ArrayList<>())
               .add(new PartRender(renderedBuffer, renderType, texture, modelView, colorR, colorG, colorB, colorA, fade));
    }

    public static void flush() {
        // Set active=false last (after batches.clear()) so that an exception mid-flush doesn't
        // leave active=false with non-empty batches — which would cause the next begin()/flush()
        // to process stale already-consumed RenderedBuffers and crash with double-release ISEs.
        try {
            if (batches.isEmpty()) return;

            if (batchVbo == null) {
                batchVbo = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
            }

            Matrix4f proj = RenderSystem.getProjectionMatrix();
            batchVbo.bind();
            try {
                for (Map.Entry<RenderType, List<PartRender>> entry : batches.entrySet()) {
                    RenderType rt = entry.getKey();
                    rt.setupRenderState();
                    try {
                        ShaderInstance shader = RenderSystem.getShader();
                        if (shader != null) {
                            // OverlayUv uniform is constant for the lifetime of this shader binding —
                            // look it up once per render type group, not once per part
                            Uniform overlay = shader.getUniform("OverlayUv");
                            for (PartRender part : entry.getValue()) {
                                RenderSystem.setShaderTexture(0, part.texture());
                                RenderSystem.setShaderColor(part.colorR(), part.colorG(), part.colorB(), part.colorA());
                                // upload() consumes and internally releases the RenderedBuffer
                                batchVbo.upload(part.renderedBuffer());
                                if (overlay != null) {
                                    overlay.set(0f, 10f - part.fade());
                                }
                                batchVbo.drawWithShader(part.modelView(), proj, shader);
                            }
                        }
                    } finally {
                        rt.clearRenderState();
                    }
                }
            } finally {
                VertexBuffer.unbind();
            }
        } finally {
            // Release any RenderedBuffers that weren't consumed by upload() (e.g. when shader==null
            // or when an exception aborted processing). upload() calls release() internally, so
            // buffers that were uploaded will throw ISE here — that's expected and ignored.
            releaseUnconsumedBuffers();
            batches.clear();
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            active = false;
        }
    }

    private static void releaseUnconsumedBuffers() {
        for (List<PartRender> parts : batches.values()) {
            for (PartRender part : parts) {
                try {
                    part.renderedBuffer().release();
                } catch (IllegalStateException ignored) {
                    // Already released by upload() — expected for successfully rendered parts
                }
            }
        }
    }

    /**
     * Draws a single part immediately, without batching. Used for the render paths that have
     * no begin()/flush() wrapper around them (projectile charges, crafted equipment) — their
     * part counts are small, so per-part draws are acceptable there.
     *
     * Consumes the RenderedBuffer (upload() releases it internally); if the draw is skipped
     * because no shader is bound, the buffer is released here instead.
     */
    public static void drawImmediate(
            BufferBuilder.RenderedBuffer renderedBuffer,
            RenderType renderType,
            ResourceLocation texture,
            Matrix4f modelView,
            float colorR, float colorG, float colorB, float colorA,
            int fade) {
        if (batchVbo == null) {
            batchVbo = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
        }
        boolean uploaded = false;
        try {
            batchVbo.bind();
            try {
                renderType.setupRenderState();
                try {
                    ShaderInstance shader = RenderSystem.getShader();
                    if (shader != null) {
                        RenderSystem.setShaderTexture(0, texture);
                        RenderSystem.setShaderColor(colorR, colorG, colorB, colorA);
                        uploaded = true;
                        batchVbo.upload(renderedBuffer);
                        Uniform overlay = shader.getUniform("OverlayUv");
                        if (overlay != null) {
                            overlay.set(0f, 10f - fade);
                        }
                        batchVbo.drawWithShader(modelView, RenderSystem.getProjectionMatrix(), shader);
                    }
                } finally {
                    renderType.clearRenderState();
                    RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
                }
            } finally {
                VertexBuffer.unbind();
            }
        } finally {
            if (!uploaded) {
                try {
                    renderedBuffer.release();
                } catch (IllegalStateException ignored) {
                }
            }
        }
    }

    public static VertexFormat getEntityVertexFormat() {
        return DefaultVertexFormat.NEW_ENTITY;
    }

    /**
     * Releases the shared VertexBuffer GPU handle. Call on resource reload — the handle
     * becomes invalid if the GL context is recreated, and the null check in flush() will
     * create a fresh one on the next render.
     *
     * Must be dispatched via RenderSystem.recordRenderCall() since VertexBuffer.close()
     * issues GL calls that are only valid on the render thread.
     */
    public static void invalidateGpuResources() {
        RenderSystem.assertOnRenderThread();
        if (batchVbo != null) {
            batchVbo.close();
            batchVbo = null;
        }
    }
}
