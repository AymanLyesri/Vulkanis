package com.vulkanis.render;

import com.vulkanis.pack.ShaderLibrary;
import com.vulkanis.render.shadow.CascadeShadows;
import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CompositeRenderer {
  private static final Logger LOG = LoggerFactory.getLogger("vulkanis");
  private static final Executor COMPILE_POOL = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "vulkanis-compile");
    t.setDaemon(true);
    return t;
  });

  private final ShaderLibrary library;
  private final String namespace;
  private final String vertexPath;
  private final String fragmentPath;
  private RenderPipeline pipeline;
  private PipelineCache cache;
  private CompiledRenderPipeline compiled;
  private CompletableFuture<CompiledRenderPipeline.Pending> pending;
  private RenderPipeline debugPipeline;
  private PipelineCache debugCache;
  private CompiledRenderPipeline debugCompiled;
  private CompletableFuture<CompiledRenderPipeline.Pending> debugPending;
  private GpuBuffer samplerInfo;
  private int samplerW = -1;
  private int samplerH = -1;
  private boolean errorLogged;
  private boolean debugErrorLogged;
  private boolean depthLogged;
  private int frames;

  public CompositeRenderer(ShaderLibrary library, String namespace, String vertexPath, String fragmentPath) {
    this.library = library;
    this.namespace = namespace;
    this.vertexPath = vertexPath;
    this.fragmentPath = fragmentPath;
  }

  private ShaderSource source() {
    return new ShaderSource() {
      @Override
      public String getShader(Identifier id, ShaderType type) {
        return library.get(id.getNamespace() + ":" + id.getPath() + ":" + type.getName());
      }
      @Override
      public ShaderSource.CachedIncludeSource getInclude(Identifier id) {
        return null;
      }
      @Override
      public void close() {
      }
    };
  }

  private ShaderSource debugSource() {
    return new ShaderSource() {
      @Override
      public String getShader(Identifier id, ShaderType type) {
        return "vertex".equals(type.getName()) ? DebugView.VERTEX : DebugView.FRAGMENT;
      }
      @Override
      public ShaderSource.CachedIncludeSource getInclude(Identifier id) {
        return null;
      }
      @Override
      public void close() {
      }
    };
  }

  public synchronized void ensurePipeline(GpuDevice device) {
    if (pipeline != null) return;
    pipeline = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
      .withLocation(Identifier.fromNamespaceAndPath(namespace, "post/composite"))
      .withVertexShader(Identifier.fromNamespaceAndPath(namespace, vertexPath))
      .withFragmentShader(Identifier.fromNamespaceAndPath(namespace, fragmentPath))
      .withBindGroupLayout(BindGroupLayout.builder()
        .withUniform("InSampler", UniformType.COMBINED_IMAGE_SAMPLER)
        .withUniform("InDepth", UniformType.COMBINED_IMAGE_SAMPLER)
        .withUniform("SamplerInfo", UniformType.UNIFORM_BUFFER)
        .build())
      .withColorTargetState(ColorTargetState.DEFAULT)
      .build();
    cache = new PipelineCache(device, source());
  }

  public synchronized void ensureDebugPipeline(GpuDevice device) {
    if (debugPipeline != null) return;
    debugPipeline = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
      .withLocation(Identifier.fromNamespaceAndPath(namespace, "post/debug"))
      .withVertexShader(Identifier.fromNamespaceAndPath(namespace, "post/debug"))
      .withFragmentShader(Identifier.fromNamespaceAndPath(namespace, "post/debug"))
      .withBindGroupLayout(BindGroupLayout.builder()
        .withUniform("InDepth", UniformType.COMBINED_IMAGE_SAMPLER)
        .withUniform("SamplerInfo", UniformType.UNIFORM_BUFFER)
        .build())
      .withColorTargetState(ColorTargetState.DEFAULT)
      .build();
    debugCache = new PipelineCache(device, debugSource());
  }

  public synchronized void render(RenderTarget main) {
    GpuDevice device;
    try {
      device = RenderSystem.getDevice();
    } catch (Exception e) {
      return;
    }
    try {
      ensurePipeline(device);
      boolean showDepth = ShadowHookState.showDepth();
      if (showDepth) {
        renderDebug(device, main);
        return;
      }
      if (compiled == null) {
        if (pending == null) {
          pending = device.compilePipeline(pipeline, source(), COMPILE_POOL);
        }
        if (!pending.isDone()) return;
        try {
          CompiledRenderPipeline.Pending p = pending.getNow(null);
          if (p == null) return;
          compiled = p.finishCompile();
          cache.insert(pipeline, compiled);
        } catch (Exception e) {
          if (!errorLogged) { errorLogged = true; LOG.warn("vulkanis: composite compile failed, skipping", e); }
          ShadowHookState.setCompositeReady(false);
          pending = null;
          return;
        }
      }
      int w = main.width;
      int h = main.height;
      refreshSamplerInfo(device, w, h, 0);
      CommandEncoder encoder = device.createCommandEncoder();
      if (++frames % 3600 == 0) {
        LOG.info("vulkanis: frame showDepth={} enabled={} sunY={}",
          ShadowHookState.showDepth(), ShadowHookState.compositeEnabled(), ShadowHookState.sunDir()[1]);
      }
      if (!depthLogged) {
        depthLogged = true;
        LOG.info("vulkanis: depthView={} depthFormat={} colorFormat={}",
          main.getDepthTextureView() != null, main.getDepthTexture(), main.getColorTexture());
      }
      try (RenderPass pass = encoder.createRenderPass(() -> "vulkanis composite",
        main.getColorTextureView(), Optional.empty())) {
        pass.setPipeline(compiled);
        RenderSystem.bindDefaultUniforms(pass);
        pass.setUniform("SamplerInfo", samplerInfo.slice());
        pass.setUniform("InSampler", main.getColorTextureView(),
          RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
        bindDepth(pass, main);
        pass.draw(3, 1, 0, 0);
      }
      encoder.submit();
      ShadowHookState.noteCompositeRun();
    } catch (Exception e) {
      if (!errorLogged) { errorLogged = true; LOG.warn("vulkanis: composite pass failed, skipping", e); }
      ShadowHookState.setCompositeReady(false);
    }
  }

  /** Engine debug view: bypasses pack GLSL entirely, identical on every pack. */
  private void renderDebug(GpuDevice device, RenderTarget main) {
    int cascade = ShadowHookState.debugCascade();
    com.mojang.renderpearl.api.textures.GpuTextureView cascadeView = null;
    if (cascade >= 0 && cascade < CascadeShadows.CASCADE_COUNT) {
      cascadeView = CascadeShadows.get().depthView(cascade);
    }
    try {
      ensureDebugPipeline(device);
      if (debugCompiled == null) {
        if (debugPending == null) {
          debugPending = device.compilePipeline(debugPipeline, debugSource(), COMPILE_POOL);
        }
        if (!debugPending.isDone()) return;
        try {
          CompiledRenderPipeline.Pending p = debugPending.getNow(null);
          if (p == null) return;
          debugCompiled = p.finishCompile();
          debugCache.insert(debugPipeline, debugCompiled);
        } catch (Exception e) {
          if (!debugErrorLogged) { debugErrorLogged = true; LOG.warn("vulkanis: debug compile failed, skipping", e); }
          debugPending = null;
          return;
        }
      }
      refreshSamplerInfo(device, main.width, main.height, cascadeView != null ? 2 : 1);
      CommandEncoder encoder = device.createCommandEncoder();
      try (RenderPass pass = encoder.createRenderPass(() -> "vulkanis debug",
        main.getColorTextureView(), Optional.empty())) {
        pass.setPipeline(debugCompiled);
        RenderSystem.bindDefaultUniforms(pass);
        pass.setUniform("SamplerInfo", samplerInfo.slice());
        if (cascadeView != null) {
          pass.setUniform("InDepth", cascadeView,
            RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
        } else {
          bindDepth(pass, main);
        }
        pass.draw(3, 1, 0, 0);
      }
      encoder.submit();
      ShadowHookState.noteCompositeRun();
    } catch (Exception e) {
      if (!debugErrorLogged) { debugErrorLogged = true; LOG.warn("vulkanis: debug pass failed, skipping", e); }
    }
  }

  private void refreshSamplerInfo(GpuDevice device, int w, int h, int showDepthMode) {
    if (samplerInfo == null || w != samplerW || h != samplerH) {
      if (samplerInfo != null) samplerInfo.close();
      samplerInfo = null;
      samplerW = w;
      samplerH = h;
    }
    ByteBuffer info = ByteBuffer.allocateDirect(192).order(ByteOrder.nativeOrder());
    info.putFloat((float) w).putFloat((float) h).putFloat((float) w).putFloat((float) h);
    info.putInt(showDepthMode).putFloat(ShadowHookState.proj22()).putFloat(ShadowHookState.proj32()).putFloat(0.0f);
    float[] sun = ShadowHookState.sunDir();
    info.putFloat(sun[0]).putFloat(sun[1]).putFloat(sun[2]).putFloat(0.0f);
    float[] cam = ShadowHookState.camPos();
    info.putFloat(cam[0]).putFloat(cam[1]).putFloat(cam[2]).putFloat(0.0f);
    for (float f : ShadowHookState.invViewProj()) info.putFloat(f);
    for (float f : ShadowHookState.viewProj()) info.putFloat(f);
    info.flip();
    GpuBuffer fresh = device.createBuffer(() -> "vulkanis sampler info",
      GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, info);
    if (samplerInfo != null) samplerInfo.close();
    samplerInfo = fresh;
  }

  private static void bindDepth(RenderPass pass, RenderTarget main) {
    if (main.getDepthTextureView() != null) {
      pass.setUniform("InDepth", main.getDepthTextureView(),
        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
    }
    com.mojang.renderpearl.api.textures.GpuTextureView copy =
      com.vulkanis.VulkanisClient.depthStore().view();
    if (copy != null) {
      pass.setUniform("InDepth", copy,
        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
    }
  }

  public void close() {
    if (samplerInfo != null) samplerInfo.close();
    if (cache != null) cache.close();
    if (debugCache != null) debugCache.close();
  }

  public void resetPending() {
    pending = null;
    compiled = null;
    debugPending = null;
    debugCompiled = null;
  }

  GpuBufferSlice samplerSliceForTest() {
    return samplerInfo == null ? null : samplerInfo.slice();
  }
}
