package com.vulkanis.mixin;

import com.vulkanis.render.shadow.CascadeShadows;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = DefaultChunkRenderer.class, remap = false)
public abstract class VulkanisUniformMixin {
  private static final Logger LOG = LoggerFactory.getLogger("vulkanis");
  @Unique
  private static GpuSampler vulkanis$shadowSampler;

  @Redirect(
      method = "render",
      at = @At(
          value = "INVOKE",
          target = "Lcom/mojang/renderpearl/api/commands/RenderPass;setUniform(Ljava/lang/String;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Lcom/mojang/renderpearl/api/textures/GpuSampler;)V",
          ordinal = 1))
  private void vulkanis$bindShadowUniforms(RenderPass renderPass, String name,
      GpuTextureView view, GpuSampler sampler) {
    renderPass.setUniform(name, view, sampler);
    if (CascadeShadows.renderingShadowMap()) {
      return;
    }
    if (!com.vulkanis.render.ShadowHookState.shadowsActive()) {
      return;
    }
    CascadeShadows shadows = CascadeShadows.get();
    if (!shadows.ready()) {
      return;
    }
    try {
      renderPass.setUniform("VulkanisShadowData", shadows.dataSlice());
      if (vulkanis$shadowSampler == null) {
        vulkanis$shadowSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
      }
      for (int i = 0; i < CascadeShadows.CASCADE_COUNT; i++) {
        GpuTextureView depthView = shadows.depthView(i);
        if (depthView != null) {
          renderPass.setUniform("VulkanisShadowMap" + i, depthView, vulkanis$shadowSampler);
        }
      }
    } catch (Exception e) {
      LOG.warn("vulkanis: shadow uniform bind failed", e);
    }
  }
}
