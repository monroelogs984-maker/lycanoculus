package com.glennfo.lycanoculus.mixin;

import com.glennfo.lycanoculus.render.IrisBatchRenderer;
import com.glennfo.lycanoculus.render.ShaderState;
import com.lycanitesmobs.client.compatibility.IrisHelper;
import com.lycanitesmobs.client.compatibility.OculusCompat;
import com.lycanitesmobs.client.obj.geometry.ObjPart;
import com.lycanitesmobs.client.obj.geometry.Vertex;
import com.lycanitesmobs.client.obj.model.VBOObjModel;
import com.lycanitesmobs.client.renderer.util.CustomRenderStates;
import com.lycanitesmobs.client.renderer.util.RecolorTextureCache;
import com.lycanitesmobs.core.util.math.Vector3o;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts the Iris rendering path in VBOObjModel.renderPart to feed IrisBatchRenderer
 * instead of immediately uploading and drawing per part. Also intercepts the legacy
 * renderNormal/VBOBatcher path when a shader pack is active — projectile charges and crafted
 * equipment never got an Iris path in Lycanites, so their parts are redirected to an
 * immediate Iris entity-format draw here.
 *
 * The original path (VBOObjModel): for each model part —
 *   build vertices → bind VBO → upload → setupRenderState → draw → clearRenderState → unbind
 *
 * Our path: build vertices into IrisBatchRenderer.partBuffer, capture the RenderedBuffer
 * and render state, then cancel. IrisBatchRenderer.flush() groups by render type and
 * executes: bind once → (setupRenderState → N×(upload+draw) → clearRenderState) per group → unbind once.
 *
 * Note: Matrix3f is unused in the Iris path (Lycanites ignores it there) but is part of the
 * method signature so we accept it.
 */
@Mixin(value = VBOObjModel.class, remap = false)
public class MixinVBOObjModel {

    // Reused default normal for vertices missing normal data — avoids per-vertex allocation
    private static final Vector3o DEFAULT_NORMAL = new Vector3o(0f, 1f, 0f);

    @Shadow public float variantAmount;
    @Shadow public float baseR;
    @Shadow public float baseG;
    @Shadow public float baseB;
    @Shadow public float baseRange;
    @Shadow public float variantR;
    @Shadow public float variantG;
    @Shadow public float variantB;

    @Inject(method = "renderPart", at = @At("HEAD"), cancellable = true, remap = false)
    private void lycanoculus$renderPart(
            VertexConsumer vertexBuilder,
            org.joml.Matrix3f matrix3f,
            Matrix4f matrix4f,
            int brightness,
            int fade,
            ObjPart objPart,
            Vector4f color,
            Vector2f textureOffset,
            CallbackInfo ci) {

        if (vertexBuilder != null) return;

        boolean batched;
        int blending;
        boolean glow;
        if (VBOObjModel.useEntityFormat && VBOObjModel.irisRenderType != null && IrisBatchRenderer.active) {
            // Creature path — MixinCreatureRenderer set up the statics and wrapped us in begin()/flush()
            batched = true;
            blending = VBOObjModel.irisBlending;
            glow = VBOObjModel.irisGlow;
        } else if (VBOObjModel.renderNormal && VBOObjModel.renderType != null && ShaderState.isShaderPackActive()) {
            // Projectile charges and crafted equipment: Lycanites only added its Iris path to
            // CreatureRenderer — ProjectileModelRenderer, EquipmentModel and EquipmentPartRenderer
            // still set renderNormal=true and go through VBOBatcher, whose custom shader pipeline
            // breaks under Iris. Route these parts through the same Iris entity-format draw as
            // creatures, but immediately: no begin()/flush() wrapper exists on these call paths,
            // and their part counts are small enough that batching isn't worth adding one.
            batched = false;
            // These call sites only publish the composite render type, so recover blending/glow
            // from it — getObjVBORenderType returns shared instances, identity compare is exact
            int foundBlending = 0;
            boolean foundGlow = false;
            found:
            for (CustomRenderStates.BLEND blend : CustomRenderStates.BLEND.values()) {
                for (int g = 0; g <= 1; g++) {
                    if (CustomRenderStates.getObjVBORenderType(blend.getValue(), g == 1) == VBOObjModel.renderType) {
                        foundBlending = blend.getValue();
                        foundGlow = g == 1;
                        break found;
                    }
                }
            }
            blending = foundBlending;
            // Same as the creature fix: translucent glow types corrupt the Oculus shadow map
            glow = foundGlow && !IrisHelper.isRenderingShadowPass();
        } else {
            return;
        }

        // Determine the effective render type (variant recoloring may change the texture and type)
        ResourceLocation tex = VBOObjModel.tex;
        if (tex == null) return;
        ResourceLocation effectiveTex = tex;
        if (variantAmount > 0.01f) {
            effectiveTex = RecolorTextureCache.getRecoloredTexture(
                    tex, baseR, baseG, baseB, baseRange, variantR, variantG, variantB, variantAmount);
        }
        RenderType renderType = (batched && effectiveTex == tex)
                ? VBOObjModel.irisRenderType
                : OculusCompat.getIrisEntityRenderType(effectiveTex, blending, glow);

        // Ensure normals are computed before reading them.
        // Check vertices null separately to avoid NPE in the length comparison.
        if (objPart.mesh.normals == null || objPart.mesh.vertices == null
                || objPart.mesh.normals.length != objPart.mesh.vertices.length) {
            objPart.mesh.computeVertexNormalsIfNeeded();
        }

        int[] indices = objPart.mesh.indices;
        Vertex[] vertices = objPart.mesh.vertices;
        Vector3o[] normals = objPart.mesh.normals;

        // Skip uninitialized or empty parts rather than crashing or leaving partBuffer stuck
        if (indices == null || vertices == null || indices.length == 0) {
            ci.cancel();
            return;
        }

        float texOX = textureOffset.x * 0.01f;
        float texOY = textureOffset.y * 0.01f;

        BufferBuilder buf = IrisBatchRenderer.partBuffer;
        buf.begin(VertexFormat.Mode.TRIANGLES, IrisBatchRenderer.getEntityVertexFormat());

        // Wrap vertex building AND buf.end() so that any exception leaves partBuffer in a clean
        // non-building state. buf.end() outside the try would permanently stick the buffer if it
        // threw, crashing every subsequent Lycanites render for the rest of the session.
        BufferBuilder.RenderedBuffer renderedBuffer;
        try {
            for (int i = 0; i < indices.length; i++) {
                int idx = indices[i];
                if (idx < 0 || idx >= vertices.length) continue; // skip degenerate index
                Vertex v = vertices[idx];
                Vector3o n = (normals != null && idx < normals.length && normals[idx] != null)
                        ? normals[idx]
                        : DEFAULT_NORMAL;
                buf.vertex(v.getPos().x(), v.getPos().y(), v.getPos().z())
                   .color(255, 255, 255, 255)
                   .uv(v.getTexCoords().x + texOX, 1f - (v.getTexCoords().y + texOY))
                   .overlayCoords(0, 10 - fade)
                   .uv2(brightness)
                   .normal(n.x(), n.y(), n.z())
                   .endVertex();
            }
            renderedBuffer = buf.end();
        } catch (Exception e) {
            // End and discard the partial buffer to restore partBuffer to non-building state
            try { buf.end().release(); } catch (Exception ignored) {}
            ci.cancel();
            return;
        }

        // Capture the model-view matrix for this part (copy — the RenderSystem matrix changes per entity)
        Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(matrix4f);

        if (batched) {
            IrisBatchRenderer.addPart(
                    renderedBuffer,
                    renderType,
                    effectiveTex,
                    modelView,
                    color.x(), color.y(), color.z(), color.w(),
                    fade);
        } else {
            IrisBatchRenderer.drawImmediate(
                    renderedBuffer,
                    renderType,
                    effectiveTex,
                    modelView,
                    color.x(), color.y(), color.z(), color.w(),
                    fade);
        }

        ci.cancel();
    }
}
