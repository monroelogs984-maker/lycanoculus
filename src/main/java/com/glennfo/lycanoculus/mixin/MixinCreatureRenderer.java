package com.glennfo.lycanoculus.mixin;

import com.glennfo.lycanoculus.render.IrisBatchRenderer;
import com.glennfo.lycanoculus.render.ShaderState;
import com.lycanitesmobs.client.compatibility.IrisHelper;
import com.lycanitesmobs.client.compatibility.OculusCompat;
import net.minecraft.client.Minecraft;
import com.lycanitesmobs.client.model.creature.base.CreatureModel;
import com.lycanitesmobs.client.obj.model.VBOObjModel;
import com.lycanitesmobs.client.renderer.entity.creature.CreatureRenderer;
import com.lycanitesmobs.client.renderer.layer.creature.LayerCreatureBase;
import com.lycanitesmobs.core.entity.base.BaseCreatureEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fixes Lycanites Mobs entity rendering when Oculus shaders are active.
 *
 * Root cause: CreatureRenderer passes a non-null VertexConsumer which causes ObjModel.renderPart()
 * to submit triangles into a QUADS buffer — scrambling UV coordinates (mosaic/jigsaw visual).
 *
 * Fix: intercept renderModel() when Iris is active and force the VBO TRIANGLES path by passing
 * null VertexConsumer. VBOObjModel.renderPart() activates this path when vertexBuilder == null
 * and useEntityFormat + irisRenderType are set.
 *
 * Changes vs 1.0:
 * - OculusCompat moved to client.compatibility in Lycanites 0.0.9-alpha
 * - Glow entities during shadow pass must use the non-glow (cutout) render type; the translucent
 *   glow type is incompatible with Oculus shadow shaders and corrupts the shadow map, making
 *   glowing creatures appear transparent in the main pass
 */
@Mixin(value = CreatureRenderer.class, remap = false)
public abstract class MixinCreatureRenderer {

    // Shadow LOD: during the Oculus shadow pass, skip rendering creatures beyond this distance.
    // Their shadow contribution at range is imperceptible, but the GPU cost is the same.
    // 48 blocks squared — far enough to avoid visible shadow pop-in at normal render distances.
    private static final double SHADOW_LOD_DIST_SQ = 48.0 * 48.0;

    @Shadow public ResourceLocation texture;

    @Shadow public abstract CreatureModel getMainModel();

    @Shadow public abstract ResourceLocation getEntityTexture(BaseCreatureEntity entity, LayerCreatureBase layer);

    @Inject(
        method = "renderModel",
        at = @At("HEAD"),
        cancellable = true,
        remap = false
    )
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void lycanoculus$renderModel(
            BaseCreatureEntity entity,
            PoseStack matrixStack,
            MultiBufferSource renderTypeBuffer,
            LayerCreatureBase layer,
            float time,
            float distance,
            float loop,
            float lookY,
            float lookX,
            float scale,
            int brightness,
            int fade,
            boolean invisible,
            boolean allyInvisible,
            CallbackInfo ci) {

        if (!ShaderState.isShaderPackActive()) {
            return;
        }

        // Shadow LOD: skip shadow rendering for distant creatures. Oculus renders shadow maps
        // with multiple cascades and the GPU cost is the same regardless of distance, but the
        // visual contribution of a creature's shadow at 48+ blocks is imperceptible.
        if (IrisHelper.isRenderingShadowPass()) {
            net.minecraft.world.phys.Vec3 cam =
                    Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            if (entity.distanceToSqr(cam.x, cam.y, cam.z) > SHADOW_LOD_DIST_SQ) {
                ci.cancel();
                return;
            }
        }

        if (!invisible || allyInvisible) {
            this.texture = this.getEntityTexture(entity, layer);

            CreatureModel model = (CreatureModel) this.getMainModel();
            int blending = model.getBlending(entity, layer);
            boolean glow = model.getGlow(entity, layer);

            // Shadow pass doesn't support translucent render types — glow entities must render
            // as cutout during shadows, otherwise their shadow map entry is corrupted and they
            // appear transparent in the main pass.
            boolean irisGlow = glow && !IrisHelper.isRenderingShadowPass();
            RenderType irisRenderType = OculusCompat.getIrisEntityRenderType(this.texture, blending, irisGlow);

            VBOObjModel.tex = this.texture;
            VBOObjModel.useEntityFormat = true;
            VBOObjModel.irisRenderType = irisRenderType;
            VBOObjModel.irisBlending = blending;
            VBOObjModel.irisGlow = irisGlow;

            IrisBatchRenderer.begin();
            try {
                model.render(entity, matrixStack, null, layer,
                        time, distance, loop, lookY, lookX, 1.0f, brightness, fade);
            } finally {
                try {
                    IrisBatchRenderer.flush();
                } finally {
                    VBOObjModel.tex = null;
                    VBOObjModel.useEntityFormat = false;
                    VBOObjModel.irisRenderType = null;
                    VBOObjModel.irisBlending = 0;
                    VBOObjModel.irisGlow = false;
                }
            }
        }

        ci.cancel();
    }
}
