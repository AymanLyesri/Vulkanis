package com.vulkanis.mixin;

import com.vulkanis.render.shadow.CascadeShadows;
import com.vulkanis.render.shadow.EntityDepth;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Redirects vanilla entity draws inside our shadow pass to the depth pipeline.
 * Same vertices, same per-entity pose from DynamicTransforms — only the camera
 * part becomes our sun matrices. Terrain and unknown pipelines pass through.
 */
@Mixin(value = PreparedRenderType.class, remap = false)
public abstract class VulkanisEntityDrawMixin {
  private static boolean hookLogged;

  @Inject(
      method = "draw(Lnet/minecraft/client/renderer/StagedVertexBuffer$ExecuteInfo;Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)V",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void vulkanis$entityDepthDraw(StagedVertexBuffer.ExecuteInfo info,
      RenderPass pass, RenderPipeline pipeline, CallbackInfo ci) {
    if (!CascadeShadows.renderingShadowMap()) {
      return;
    }
    if (!hookLogged) {
      hookLogged = true;
      org.slf4j.LoggerFactory.getLogger("vulkanis")
        .info("vulkanis: entity draw redirect firing");
    }
    RenderPipeline depth = EntityDepth.forPipeline(pipeline);
    if (depth == null) {
      return;
    }
    ci.cancel();
    try {
      if (!CascadeShadows.get().ensureCompiled(RenderSystem.getDevice(), depth)) {
        return;
      }
      CompiledRenderPipeline compiled = RenderSystem.getCompiledPipeline(depth);
      if (compiled == null) {
        return;
      }
      com.mojang.renderpearl.api.buffers.GpuBufferSlice cascade = EntityDepth.activeSlice();
      if (cascade == null) {
        return;
      }
      PreparedRenderType prepared = (PreparedRenderType) (Object) this;
      pass.setPipeline(compiled);
      RenderSystem.bindDefaultUniforms(pass);
      pass.setUniform("DynamicTransforms", prepared.dynamicTransforms());
      pass.setUniform("VulkanisEntityCascade", cascade);
      pass.setVertexBuffer(0, info.vertexBuffer().slice());
      pass.setIndexBuffer(info.indexBuffer(), info.indexType());
      pass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
    } catch (Exception ignored) { }
  }
}
