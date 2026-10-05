package com.vulkanis.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ShadowDepthStore {
  private static final Logger LOG = LoggerFactory.getLogger("vulkanis");
  private RenderTarget target;
  private boolean errorLogged;

  public synchronized void copyFrom(RenderTarget main) {
    copy(main, false);
  }

  public synchronized void copyShadowMap(RenderTarget shadow) {
    copy(shadow, true);
  }

  private synchronized void copy(RenderTarget source, boolean shadow) {
    try {
      if (target == null || target.width != source.width || target.height != source.height) {
        if (target != null) target.destroyBuffers();
        target = new TextureTarget("vulkanis shadow depth", source.width, source.height, null, GpuFormat.D32_FLOAT);
      }
      target.copyDepthFrom(source);
    } catch (Exception e) {
      if (!errorLogged) { errorLogged = true; LOG.warn("vulkanis: depth copy failed", e); }
    }
  }

  public synchronized GpuTextureView view() {
    if (target == null || !target.hasDepth()) return null;
    return target.getDepthTextureView();
  }

  public synchronized void close() {
    if (target != null) target.destroyBuffers();
    target = null;
  }
}
