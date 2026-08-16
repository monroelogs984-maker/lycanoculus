package com.glennfo.lycanoculus.mixin;

import com.glennfo.lycanoculus.render.ShaderState;
import com.lycanitesmobs.client.compatibility.IrisHelper;
import com.lycanitesmobs.client.renderer.entity.projectile.ProjectileSpriteRenderer;
import com.lycanitesmobs.core.entity.base.BaseProjectileEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fixes sprite-based charges under Oculus shaders.
 *
 * Sprite projectiles (every JSON projectile without a model, plus laser beam segments) draw
 * billboard quads with CustomRenderStates.getSpriteRenderType — a custom "lm_sprite" render
 * type using a vanilla core shader binding that Iris does not patch, so the quads render
 * broken when a shader pack is active. Redirect them to the vanilla entityTranslucentEmissive
 * type (the same one the Lycanites Iris path uses for glow), which matches the intended
 * sprite look: fullbright and translucent. During the shadow pass the cutout type is used
 * instead — translucent types corrupt the Oculus shadow map (same as the creature glow fix).
 *
 * renderLaser() funnels through renderSprite() per segment, so hooking renderSprite covers
 * both. The vanilla quad geometry and UV animation math are reproduced exactly; only the
 * render type and vertex format (NEW_ENTITY) change.
 */
@Mixin(value = ProjectileSpriteRenderer.class, remap = false)
public abstract class MixinProjectileSpriteRenderer {

    @Shadow public abstract ResourceLocation getTextureLocation(BaseProjectileEntity entity);

    @Inject(method = "renderSprite", at = @At("HEAD"), cancellable = true, remap = false)
    private void lycanoculus$renderSprite(
            BaseProjectileEntity entity,
            PoseStack matrixStack,
            MultiBufferSource renderTypeBuffer,
            RenderType rendertype,
            float scale,
            CallbackInfo ci) {

        if (!ShaderState.isShaderPackActive()) {
            return;
        }

        // Same quad as vanilla renderSprite, including the quirk that width/height are only
        // scaled when the sprite is animated — preserved to keep visuals identical
        float textureWidth = 0.25f;
        float textureHeight = 0.25f;
        float minU = 0.0f;
        float maxU = 1.0f;
        float minV = 0.0f;
        float maxV = 1.0f;
        if (entity.getAnimationFrameMax() > 0) {
            minV = (float) entity.getAnimationFrame() / entity.getAnimationFrameMax();
            maxV = minV + 1.0f / entity.getAnimationFrameMax();
            textureWidth *= scale;
            textureHeight *= scale;
        }

        ResourceLocation texture = this.getTextureLocation(entity);
        RenderType renderType = IrisHelper.isRenderingShadowPass()
                ? RenderType.entityCutoutNoCull(texture)
                : RenderType.entityTranslucentEmissive(texture);

        PoseStack.Pose pose = matrixStack.last();
        Matrix4f matrix4f = pose.pose();
        Matrix3f matrix3f = pose.normal();
        VertexConsumer vertexBuilder = renderTypeBuffer.getBuffer(renderType);
        quadVertex(vertexBuilder, matrix4f, matrix3f, -textureWidth, -textureHeight + textureHeight / 2.0f, minU, maxV);
        quadVertex(vertexBuilder, matrix4f, matrix3f, textureWidth, -textureHeight + textureHeight / 2.0f, maxU, maxV);
        quadVertex(vertexBuilder, matrix4f, matrix3f, textureWidth, textureHeight + textureHeight / 2.0f, maxU, minV);
        quadVertex(vertexBuilder, matrix4f, matrix3f, -textureWidth, textureHeight + textureHeight / 2.0f, minU, minV);

        ci.cancel();
    }

    private static void quadVertex(VertexConsumer vc, Matrix4f pose, Matrix3f normal, float x, float y, float u, float v) {
        vc.vertex(pose, x, y, 0.0f)
          .color(255, 255, 255, 255)
          .uv(u, v)
          .overlayCoords(OverlayTexture.NO_OVERLAY)
          .uv2(LightTexture.FULL_BRIGHT)
          .normal(normal, 0.0f, 1.0f, 0.0f)
          .endVertex();
    }
}
