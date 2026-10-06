package com.vulkanis.render;

import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
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
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.vulkanis.pack.PassGraph;
import com.vulkanis.pack.ShaderLibrary;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Multi-pass fullscreen composite executor. Declaration order = execution order; last pass renders to main. */
public final class PassExecutor {
  private static final Logger LOG = LoggerFactory.getLogger("vulkanis");
  private static final Executor COMPILE_POOL = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "vulkanis-pass-compile");
    t.setDaemon(true);
    return t;
  });

  public record Size(int w, int h) {}

  public static String samplerName(String passOutput) {
    return "In" + Character.toUpperCase(passOutput.charAt(0)) + passOutput.substring(1);
  }

  public static Size targetSize(int w, int h, double scale) {
    return new Size(Math.max(1, (int) (w * scale)), Math.max(1, (int) (h * scale)));
  }

  public static void resolveInputs(List<String> in, Map<String, Integer> known) {
    for (String s : in) {
      if (!known.containsKey(s)) throw new IllegalArgumentException("unknown pass input: " + s);
    }
  }

  /** Outputs safe to sample while rendering pass `index`: earlier non-final passes only. */
  static List<String> boundOutputs(PassGraph graph, int index) {
    List<String> out = new ArrayList<>();
    List<PassGraph.Pass> passes = graph.passes();
    for (int j = 0; j < index && j < passes.size(); j++) {
      String n = passes.get(j).name();
      if (!n.equals("final")) out.add(n);
    }
    return out;
  }
  /** Day/night exposure target from sun height: 1.0 at noon, 1.8 at midnight. */
  public static double exposureTarget(double sunY) {
    double night = Math.min(1.0, Math.max(0.0, 1.0 - sunY * 2.0));
    return 1.0 + night * 0.8;
  }

  private final ShaderLibrary library;
  private final String namespace;
  private final PassGraph graph;
  private final List<String> transients = new ArrayList<>();
  private final List<RenderPipeline> pipelines = new ArrayList<>();
  private final List<CompiledRenderPipeline> compiled = new ArrayList<>();
  private final List<CompletableFuture<CompiledRenderPipeline.Pending>> pending = new ArrayList<>();
  private PipelineCache cache;
  private final Map<String, TextureTarget> targets = new HashMap<>();
  private int targetW = -1;
  private int targetH = -1;
  private static final class HistoryPair {
    TextureTarget a;
    TextureTarget b;
    boolean readIsA = true;
  }
  private final Map<String, HistoryPair> history = new LinkedHashMap<>();
  private boolean errorLogged;
  private boolean graphLogged;
  private boolean compileLogged;
  private int pendingFrames;
  private boolean stuckWarned;

  public PassExecutor(ShaderLibrary library, String namespace, PassGraph graph) {
    this.library = library;
    this.namespace = namespace;
    this.graph = graph;
    for (PassGraph.Pass p : graph.passes()) {
      if (p.history() && !history.containsKey(p.name())) history.put(p.name(), new HistoryPair());
      if (!p.name().equals("final") && !transients.contains(p.name())) transients.add(p.name());
      pipelines.add(null);
      compiled.add(null);
      pending.add(null);
    }
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

  private synchronized void ensurePipelines(GpuDevice device) {
    if (cache == null) cache = new PipelineCache(device, source());
    if (pipelines.stream().allMatch(p -> p != null)) return;
    BindGroupLayout.Builder layout = BindGroupLayout.builder()
      .withUniform("InSampler", UniformType.COMBINED_IMAGE_SAMPLER)
      .withUniform("InDepth", UniformType.COMBINED_IMAGE_SAMPLER)
      .withUniform("SamplerInfo", UniformType.UNIFORM_BUFFER);
    for (String t : transients) layout.withUniform(samplerName(t), UniformType.COMBINED_IMAGE_SAMPLER);
    BindGroupLayout built = layout.build();
    for (int i = 0; i < graph.passes().size(); i++) {
      if (pipelines.get(i) != null) continue;
      String name = graph.passes().get(i).name();
      pipelines.set(i, RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
        .withLocation(Identifier.fromNamespaceAndPath(namespace, "post/" + name))
        .withVertexShader(Identifier.fromNamespaceAndPath(namespace, "post/" + name))
        .withFragmentShader(Identifier.fromNamespaceAndPath(namespace, "post/" + name))
        .withBindGroupLayout(built)
        .withColorTargetState(ColorTargetState.DEFAULT)
        .build());
    }
  }

  private synchronized void ensureTargets(int w, int h) {
    if (w == targetW && h == targetH) return;
    for (TextureTarget t : targets.values()) t.destroyBuffers();
    targets.clear();
    for (PassGraph.Pass p : graph.passes()) {
      if (p.name().equals("final") || p.history()) continue;
      Size s = targetSize(w, h, p.size());
      targets.put(p.name(), new TextureTarget("vulkanis:pass-" + p.name(), s.w(), s.h(),
        GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT));
    }
    targetW = w;
    targetH = h;
    for (Map.Entry<String, HistoryPair> e : history.entrySet()) {
      HistoryPair pair = e.getValue();
      if (pair.a == null) {
        pair.a = new TextureTarget("vulkanis:history-" + e.getKey() + "-A", 1, 1,
          GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
        pair.b = new TextureTarget("vulkanis:history-" + e.getKey() + "-B", 1, 1,
          GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
      }
    }
  }

  private GpuTextureView historyReadView(String name) {
    HistoryPair pair = history.get(name);
    return (pair.readIsA ? pair.a : pair.b).getColorTextureView();
  }

  private GpuTextureView historyWriteView(String name) {
    HistoryPair pair = history.get(name);
    return (pair.readIsA ? pair.b : pair.a).getColorTextureView();
  }

  /** Runs all passes. Returns false when pipelines are still compiling (caller skips the frame). */
  public synchronized boolean render(GpuDevice device, RenderTarget main, GpuBufferSlice samplerInfo) {
    try {
      ensurePipelines(device);
      boolean allCompiled = true;
      for (int i = 0; i < pipelines.size(); i++) {
        if (compiled.get(i) == null) {
          allCompiled = false;
          if (pending.get(i) == null) {
            pending.set(i, device.compilePipeline(pipelines.get(i), source(), COMPILE_POOL));
            if (!compileLogged) {
              compileLogged = true;
              StringBuilder names = new StringBuilder();
              for (PassGraph.Pass p : graph.passes()) {
                if (names.length() > 0) names.append("->");
                names.append(p.name());
              }
              LOG.info("vulkanis: pass graph {} compiling {} passes", names, pipelines.size());
            }
          }
          if (!pending.get(i).isDone()) continue;
          try {
            CompiledRenderPipeline.Pending p = pending.get(i).getNow(null);
            if (p == null) continue;
            compiled.set(i, p.finishCompile());
            cache.insert(pipelines.get(i), compiled.get(i));
            LOG.info("vulkanis: pass {} compiled", graph.passes().get(i).name());
          } catch (Exception e) {
            if (!errorLogged) {
              errorLogged = true;
              LOG.warn("vulkanis: pass compile failed ({}), skipping graph (backend ERROR above has details)",
                graph.passes().get(i).name(), e);
            }
            ShadowHookState.setCompositeReady(false);
            pending.set(i, null);
            return false;
          }
        }
      }
      if (!allCompiled) {
        if (++pendingFrames == 600 && !stuckWarned) {
          stuckWarned = true;
          StringBuilder missing = new StringBuilder();
          for (int i = 0; i < pipelines.size(); i++) {
            if (compiled.get(i) == null) {
              if (missing.length() > 0) missing.append(",");
              missing.append(graph.passes().get(i).name());
            }
          }
          LOG.warn("vulkanis: passes still compiling after 600 frames ({}), check backend ERROR lines above", missing);
        }
        return false;
      }
      ensureTargets(main.width, main.height);
      if (!graphLogged) {
        graphLogged = true;
        StringBuilder names = new StringBuilder();
        for (PassGraph.Pass p : graph.passes()) {
          if (names.length() > 0) names.append("->");
          names.append(p.name());
        }
        LOG.info("vulkanis: pass graph {}", names);
      }
      GpuTextureView mainView = main.getColorTextureView();
      CommandEncoder encoder = device.createCommandEncoder();
      List<PassGraph.Pass> passes = graph.passes();
      for (int i = 0; i < passes.size(); i++) {
        PassGraph.Pass p = passes.get(i);
        boolean last = (i == passes.size() - 1);
        boolean isHistory = history.containsKey(p.name());
        GpuTextureView out = last ? mainView
          : (isHistory ? historyWriteView(p.name()) : targets.get(p.name()).getColorTextureView());
        try (RenderPass pass = encoder.createRenderPass(() -> "vulkanis pass " + p.name(),
          out, Optional.empty())) {
          pass.setPipeline(compiled.get(i));
          RenderSystem.bindDefaultUniforms(pass);
          pass.setUniform("SamplerInfo", samplerInfo);
          pass.setUniform("InSampler", mainView,
            RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
          CompositeRenderer.bindDepth(pass, main);
          if (isHistory) {
            pass.setUniform(samplerName(p.name()), historyReadView(p.name()),
              RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
          }
          for (String produced : boundOutputs(graph, i)) {
            GpuTextureView view = history.containsKey(produced)
              ? historyWriteView(produced) : targets.get(produced).getColorTextureView();
            pass.setUniform(samplerName(produced), view,
              RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
          }
          pass.draw(3, 1, 0, 0);
        } catch (Exception e) {
          if (!errorLogged) {
            errorLogged = true;
            LOG.warn("vulkanis: pass draw failed ({}), skipping frame (backend ERROR above has details)", p.name(), e);
          }
          ShadowHookState.setCompositeReady(false);
          return false;
        }
      }
      encoder.submit();
      for (HistoryPair pair : history.values()) pair.readIsA = !pair.readIsA;
      return true;
    } catch (Exception e) {
      if (!errorLogged) {
        errorLogged = true;
        LOG.warn("vulkanis: pass graph failed, skipping", e);
      }
      ShadowHookState.setCompositeReady(false);
      return false;
    }
  }

  public synchronized void close() {
    for (TextureTarget t : targets.values()) t.destroyBuffers();
    targets.clear();
    targetW = -1;
    targetH = -1;
    for (HistoryPair pair : history.values()) {
      if (pair.a != null) { pair.a.destroyBuffers(); pair.a = null; }
      if (pair.b != null) { pair.b.destroyBuffers(); pair.b = null; }
      pair.readIsA = true;
    }
    if (cache != null) cache.close();
  }

  public synchronized void resetPending() {
    for (int i = 0; i < pending.size(); i++) {
      pending.set(i, null);
      compiled.set(i, null);
    }
    pendingFrames = 0;
  }
}
