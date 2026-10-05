package com.vulkanis.mixin;

import com.vulkanis.render.shadow.CascadeShadows;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drops vanilla's fake blob shadows. Real cascaded shadows replace them, so the
 * dark ellipse under entities would double up. Our own shadow collection never
 * submits blob pieces (filter no-ops them), so this only touches the main pass.
 */
@Mixin(value = SubmitNodeStorage.class, remap = false)
public abstract class VulkanisBlobShadowMixin {
  private static boolean hookLogged;

  @Inject(
      method = "submitShadow(Lcom/mojang/blaze3d/vertex/PoseStack;FLjava/util/List;)V",
      at = @At("HEAD"),
      cancellable = true,
      require = 0)
  private void vulkanis$dropBlobShadow(PoseStack poseStack, float shadowRadius,
      List<EntityRenderState.ShadowPiece> shadowPieces, CallbackInfo ci) {
    if (CascadeShadows.renderingShadowMap()) {
      return;
    }
    // Only when real shadows will actually render; otherwise vanilla keeps its blobs.
    if (!com.vulkanis.render.ShadowHookState.shadowsActive()
        || com.vulkanis.render.ShadowHookState.sunDir()[1] < 0.12f) {
      return;
    }
    if (!hookLogged) {
      hookLogged = true;
      org.slf4j.LoggerFactory.getLogger("vulkanis")
        .info("vulkanis: blob shadow suppression firing");
    }
    ci.cancel();
  }
}
