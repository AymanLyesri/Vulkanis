package com.vulkanis.mixin;
import com.vulkanis.render.ShadowHookState;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SkyRenderer.class)
public class SkySunMixin {
  private static final Logger LOG = LoggerFactory.getLogger("vulkanis");
  private static boolean logged;
  private static int frames;
  @Inject(method = "extractRenderState", at = @At("TAIL"), require = 0)
  private void vulkanShaders$captureSun(ClientLevel level, float partialTick, Camera camera,
      SkyRenderState state, CallbackInfo ci) {
    if (!logged) { logged = true; LOG.info("vulkanis: sky sun hook firing"); }
    ShadowHookState.setSunAngle(state.sunAngle);
    if (++frames % 3600 == 0) {
      float[] d = ShadowHookState.sunDir();
      LOG.info("vulkanis: sunAngle={} dir={},{},{}", state.sunAngle, d[0], d[1], d[2]);
    }
  }
}
