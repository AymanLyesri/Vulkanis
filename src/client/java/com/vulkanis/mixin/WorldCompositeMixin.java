package com.vulkanis.mixin;
import com.vulkanis.VulkanisClient;
import com.vulkanis.render.CompositeRenderer;
import com.vulkanis.render.ShadowHookState;
import com.vulkanis.render.SodiumCompat;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
// Runs composite.fsh fullscreen after the world, before HUD. Skipped when the
// pack failed to compile (passthrough = do nothing). require=0: never crash frame.
@Mixin(LevelRenderer.class)
public class WorldCompositeMixin {
  private static final Logger LOG = LoggerFactory.getLogger("vulkanis");
  private static boolean logged;
  private static boolean errorLogged;
  @Inject(method = "render", at = @At("TAIL"), require = 0)
  private void vulkanShaders$composite(GraphicsResourceAllocator allocator, boolean renderOutline,
      CameraRenderState cameraState, GpuBufferSlice terrainFog, org.joml.Vector4f fogColor,
      boolean sky, boolean consistentDepth, CallbackInfo ci) {
    if (!logged) { logged = true; LOG.info("vulkanis: world composite hook firing"); }
    try {
      float[] pos = {(float) cameraState.pos.x, (float) cameraState.pos.y, (float) cameraState.pos.z};
      float[] proj = new float[16];
      float[] rot = new float[16];
      cameraState.projectionMatrix.get(proj);
      cameraState.viewRotationMatrix.get(rot);
      ShadowHookState.setFrame(pos, proj, rot);
    } catch (Exception e) {
      if (!errorLogged) { errorLogged = true; LOG.warn("vulkanis: frame capture failed", e); }
    }
    if (!SodiumCompat.shouldComposite(ShadowHookState.compositeReady())) return;
    if (!ShadowHookState.compositeEnabled()) return;
    try {
      CompositeRenderer renderer = VulkanisClient.composite();
      if (renderer == null) return;
      renderer.render(net.minecraft.client.Minecraft.getInstance().gameRenderer.mainRenderTarget());
    } catch (Exception e) {
      if (!errorLogged) { errorLogged = true; LOG.warn("vulkanis: composite disabled after error", e); }
      ShadowHookState.setCompositeReady(false);
    }
  }
}
