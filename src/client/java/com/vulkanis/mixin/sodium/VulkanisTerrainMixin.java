package com.vulkanis.mixin.sodium;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.vulkanis.render.shadow.CascadeShadows;
import com.vulkanis.render.shadow.VulkanisShadowPipelines;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ShaderChunkRenderer.class, remap = false)
public abstract class VulkanisTerrainMixin {
  private static boolean hookLogged;
  private static boolean casterLogged;
  private static boolean notReadyLogged;
  @Shadow
  @Final
  protected VertexFormat vertexFormat;

  @Inject(method = "compileProgram", at = @At("HEAD"), cancellable = true, require = 0)
  private void vulkanis$useShadowPipeline(TerrainRenderPass pass,
      net.minecraft.client.renderer.oit.OitStage stage, CallbackInfoReturnable<RenderPipeline> cir) {
    if (!hookLogged) {
      hookLogged = true;
      org.slf4j.LoggerFactory.getLogger("vulkanis")
        .info("vulkanis: compileProgram hook firing");
    }
    boolean solid = pass == DefaultTerrainRenderPasses.SOLID;
    boolean cutout = pass == DefaultTerrainRenderPasses.CUTOUT;
    if (!solid && !cutout) {
      return;
    }
    if (!com.vulkanis.render.ShadowHookState.shadowsActive()) {
      return;
    }
    RenderPipeline ours = CascadeShadows.renderingShadowMap()
      ? VulkanisShadowPipelines.caster(pass, this.vertexFormat)
      : VulkanisShadowPipelines.receiver(pass, this.vertexFormat);
    if (!CascadeShadows.renderingShadowMap() && !CascadeShadows.get().ready()) {
      return;
    }
    try {
      if (CascadeShadows.get().ensureCompiled(
          com.mojang.blaze3d.systems.RenderSystem.getDevice(), ours)) {
        if (CascadeShadows.renderingShadowMap() && !casterLogged) {
          casterLogged = true;
          org.slf4j.LoggerFactory.getLogger("vulkanis")
            .info("vulkanis: shadow path using caster {}", ours.getLocation());
        }
        cir.setReturnValue(ours);
      } else if (CascadeShadows.renderingShadowMap() && !notReadyLogged) {
        notReadyLogged = true;
        org.slf4j.LoggerFactory.getLogger("vulkanis")
          .info("vulkanis: shadow path pipeline not ready, vanilla fallback");
      }
    } catch (Exception ignored) { }
  }
}
