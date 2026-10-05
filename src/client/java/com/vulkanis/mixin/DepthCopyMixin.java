package com.vulkanis.mixin;
import com.vulkanis.render.ShadowHookState;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Copies the live main depth buffer into our persistent store while the frame
// still owns it (layout barriers handled by the declared read). require=0: never crash frame.
@Mixin(LevelRenderer.class)
public class DepthCopyMixin {
  private static final Logger LOG = LoggerFactory.getLogger("vulkanis");
  private static boolean logged;
  @Shadow @Final private LevelTargetBundle targets;

  @Inject(method = "addMainPass", at = @At("TAIL"), require = 0)
  private void vulkanShaders$copyDepth(FrameGraphBuilder frame,
      FeatureRenderDispatcher.PreparedFrame preparedFrame, GpuBufferSlice fog,
      ChunkSectionsToRender sections, boolean flag, CallbackInfo ci) {
    if (!logged) { logged = true; LOG.info("vulkanis: depth copy hook firing"); }
    try {
      com.mojang.blaze3d.framegraph.FramePass pass = frame.addPass("vulkanis depth copy");
      pass.reads(targets.main);
      pass.executes(() -> {
        try {
          com.vulkanis.VulkanisClient.depthStore().copyFrom(targets.main.get());
        } catch (Exception e) {
          LOG.warn("vulkanis: depth copy pass failed", e);
        }
      });
    } catch (Exception e) {
      LOG.warn("vulkanis: depth copy hook failed", e);
    }
  }
}
