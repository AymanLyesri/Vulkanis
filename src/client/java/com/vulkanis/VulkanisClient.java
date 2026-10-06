package com.vulkanis;
import com.vulkanis.pack.PackLoader;
import com.vulkanis.pack.PackManager;
import com.vulkanis.pack.PipelineSpec;
import com.vulkanis.pack.ShaderLibrary;
import com.vulkanis.render.CompositeRenderer;
import com.vulkanis.render.ShadowDepthStore;
import com.vulkanis.render.FrameGraph;
import com.vulkanis.render.ShadowHookState;
import com.vulkanis.render.SodiumCompat;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
public class VulkanisClient implements ClientModInitializer {
  private static final Logger LOG = LoggerFactory.getLogger("vulkanis");
  private static CompositeRenderer composite;
  private static final ShaderLibrary packShaders = new ShaderLibrary();
  private static final ShadowDepthStore depthStore = new ShadowDepthStore();
  private static com.vulkanis.config.ShaderConfig config = new com.vulkanis.config.ShaderConfig();
  private static KeyMapping selectKey;
  private static KeyMapping toggleKey;
  private final PackManager packs = new PackManager();
  @Override public void onInitializeClient() {
    try {
      File gameDir = FabricLoader.getInstance().getGameDir().toFile();
      try {
        config = com.vulkanis.config.ShaderConfig.load(new File(gameDir, "config/vulkanis.json"));
      } catch (Exception ignored) { }
      ShadowHookState.setCompositeEnabled(config.enabled);
      ShadowHookState.setShowDepth(config.showShadowmap);
      File dir = new File(gameDir, "shaderpacks");
      List<PipelineSpec> found = packs.rescan(dir);
      try {
        ShadowHookState.setActiveSpec(PackLoader.loadSpec(new File(dir, config.selectedPack)));
      } catch (Exception ignored) { }
      String sodium = FabricLoader.getInstance().getModContainer("sodium")
        .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse(null);
      FrameGraph.Mode mode = SodiumCompat.modeFor(sodium);
      ShadowHookState.setMode(mode);
      LOG.info("vulkanis: packs={} mode={} sodium={}", found.size(), mode, sodium);
      if (!packs.lastError().isEmpty()) LOG.warn("vulkanis: {}", packs.lastError());
      composite = loadComposite(new File(dir, config.selectedPack + "/shaders"));
      refreshShaderValues(new File(dir, config.selectedPack));
      if (composite != null) {
        CompositeRenderer c = composite;
        ShadowHookState.setCompileReset(c::resetPending);
      }
      net.minecraft.client.KeyMapping.Category category =
        net.minecraft.client.KeyMapping.Category.register(
          Identifier.fromNamespaceAndPath("vulkanis", "vulkanis"));
      selectKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.vulkanis.select_shaders",
        com.mojang.blaze3d.platform.InputConstants.Type.KEYBOARD,
        com.mojang.blaze3d.platform.InputConstants.KEY_I, category));
      toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.vulkanis.toggle_shader",
        com.mojang.blaze3d.platform.InputConstants.Type.KEYBOARD,
        com.mojang.blaze3d.platform.InputConstants.KEY_K, category));
      ClientTickEvents.END_CLIENT_TICK.register(client -> {
        com.vulkanis.BackendWarning.maybeShowStartupToast(client);
        while (selectKey.consumeClick()) {
          client.setScreenAndShow(new com.vulkanis.screen.VulkanisShaderScreen(null));
        }
        while (toggleKey.consumeClick()) {
          ShadowHookState.setCompositeEnabled(!ShadowHookState.compositeEnabled());
        }
      });
      net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN
        .register((handler, sender, client) -> com.vulkanis.BackendWarning.onWorldJoin(client));
    } catch (Exception e) {
      ShadowHookState.setMode(FrameGraph.Mode.POST_ONLY);
      LOG.warn("vulkanis init failed, post-only fallback", e);
    }
  }
  static CompositeRenderer loadComposite(File shadersDir) {
    try {
      File packDir = shadersDir.getParentFile();
      java.util.List<com.vulkanis.pack.PackSetting> settings;
      try {
        settings = com.vulkanis.pack.PackLoader.parseSettings(packDir);
      } catch (Exception e) {
        settings = java.util.List.of();
      }
      com.vulkanis.pack.PackValues values =
        com.vulkanis.pack.PackValues.load(new File(packDir, "settings.json"), settings);
      String vsh = values.apply(Files.readString(new File(shadersDir, "composite.vsh").toPath()), settings);
      String fsh = values.apply(Files.readString(new File(shadersDir, "composite.fsh").toPath()), settings);
      LOG.info("vulkanis: composite load settings={} tokensLeft={}", settings.size(), fsh.contains("{{"));
      ShaderLibrary lib = new ShaderLibrary();
      lib.put(ShaderLibrary.key("vulkanis", "post/composite", "vertex"), vsh);
      lib.put(ShaderLibrary.key("vulkanis", "post/composite", "fragment"), fsh);
      com.vulkanis.pack.PassGraph graph = com.vulkanis.pack.PackLoader.parsePasses(packDir);
      for (com.vulkanis.pack.PassGraph.Pass p : graph.passes()) {
        String frag = values.apply(Files.readString(new File(new File(packDir, "shaders"), p.frag()).toPath()), settings);
        LOG.info("vulkanis: pass load {} tokensLeft={}", p.name(), frag.contains("{{"));
        lib.put(ShaderLibrary.key("vulkanis", "post/" + p.name(), "vertex"), vsh);
        lib.put(ShaderLibrary.key("vulkanis", "post/" + p.name(), "fragment"), frag);
      }
      synchronized (packShaders) {
        packShaders.remove(ShaderLibrary.key("vulkanis", "pack/shadow", "vertex"));
        packShaders.remove(ShaderLibrary.key("vulkanis", "pack/shadow", "fragment"));
        packShaders.remove(ShaderLibrary.key("vulkanis", "pack/terrain", "vertex"));
        packShaders.remove(ShaderLibrary.key("vulkanis", "pack/terrain", "fragment"));
      }
      loadPackShaderBlock(shadersDir, "shadow", values, settings);
      loadPackShaderBlock(shadersDir, "terrain", values, settings);
      CompositeRenderer renderer = new CompositeRenderer(lib, "vulkanis", "post/composite", "post/composite");
      renderer.setPassGraph(graph);
      return renderer;
    } catch (Exception e) {
      LOG.warn("vulkanis: no composite shaders in {}, pass disabled: {}", shadersDir, e.getMessage());
      ShadowHookState.setCompositeReady(false);
      return null;
    }
  }
  /** Pack-owned shadow/terrain GLSL, absent keys = null (vanilla rendering, never jar shadows). Missing files skip silently. */
  static void loadPackShaderBlock(File shadersDir, String base,
      com.vulkanis.pack.PackValues values,
      java.util.List<com.vulkanis.pack.PackSetting> settings) {
    try {
      String vsh = values.apply(Files.readString(new File(shadersDir, base + ".vsh").toPath()), settings);
      String fsh = values.apply(Files.readString(new File(shadersDir, base + ".fsh").toPath()), settings);
      synchronized (packShaders) {
        packShaders.put(ShaderLibrary.key("vulkanis", "pack/" + base, "vertex"), vsh);
        packShaders.put(ShaderLibrary.key("vulkanis", "pack/" + base, "fragment"), fsh);
      }
      LOG.info("vulkanis: pack {} shaders loaded tokensLeft={}", base, fsh.contains("{{"));
    } catch (Exception ignored) { }
  }
  public static ShaderLibrary packShaders() { return packShaders; }
  public static CompositeRenderer composite() { return composite; }
  public static ShadowDepthStore depthStore() { return depthStore; }

  private static final java.util.Map<String, Double> shaderSettingValues = new java.util.HashMap<>();

  public static void refreshShaderValues(File packDir) {
    try {
      java.util.List<com.vulkanis.pack.PackSetting> settings =
        com.vulkanis.pack.PackLoader.parseSettings(packDir);
      com.vulkanis.pack.PackValues values =
        com.vulkanis.pack.PackValues.load(new File(packDir, "settings.json"), settings);
      synchronized (shaderSettingValues) {
        shaderSettingValues.clear();
        for (com.vulkanis.pack.PackSetting s : settings) {
          shaderSettingValues.put(s.id(), values.get(s));
        }
      }
    } catch (Exception ignored) { }
  }

  public static float shadowStrengthSetting() {
    synchronized (shaderSettingValues) {
      return shaderSettingValues.getOrDefault("shadowStrength", 0.85).floatValue();
    }
  }

  public static float shadowBiasSetting() {
    synchronized (shaderSettingValues) {
      return shaderSettingValues.getOrDefault("shadowBias", 0.05).floatValue();
    }
  }

  public static float maxShadowDistanceSetting() {
    synchronized (shaderSettingValues) {
      return shaderSettingValues.getOrDefault("maxShadowDistance", 128.0).floatValue();
    }
  }

  public static int shadowStepsSetting() {
    synchronized (shaderSettingValues) {
      return shaderSettingValues.getOrDefault("shadowSteps", 24.0).intValue();
    }
  }

  /** Player map-size override; -1 when the pack defines no such setting. */
  public static int shadowMapSizeSetting() {
    synchronized (shaderSettingValues) {
      return shaderSettingValues.getOrDefault("shadowMapSize", -1.0).intValue();
    }
  }

  public static String selectedPackId() {
    return config.selectedPack;
  }

  public static void selectPack(File packDir) {
    final PipelineSpec spec;
    try {
      spec = PackLoader.loadSpec(packDir);
    } catch (Exception e) {
      LOG.warn("vulkanis: pack select rejected {}: {}", packDir.getName(), e.getMessage());
      ShadowHookState.setLastPackError(packDir.getName() + ": " + e.getMessage());
      return;
    }
    CompositeRenderer next = loadComposite(new File(packDir, "shaders"));
    if (next == null) return;
    if (composite != null && composite.adoptIfSameTopology(next, spec.passes())) {
      next.close();
      refreshShaderValues(packDir);
      ShadowHookState.setLastPackError("");
      config.selectedPack = spec.id();
      try {
        config.save(new File(FabricLoader.getInstance().getGameDir().toFile(), "config/vulkanis.json"));
      } catch (Exception e) {
        LOG.warn("vulkanis: pack select save failed", e);
      }
      return;
    }
    ShadowHookState.setActiveSpec(spec);
    refreshShaderValues(packDir);
    // Order: packShaders keys were already refreshed by loadComposite above; dropping
    // the cached pipelines now forces the next compileProgram to rebuild on fresh GLSL.
    // A frame racing in between draws through already-compiled old objects — benign.
    com.vulkanis.render.shadow.VulkanisShadowPipelines.close();
    ShadowHookState.setLastPackError("");
    CompositeRenderer old = composite;
    composite = next;
    ShadowHookState.setCompileReset(next::resetPending);
    ShadowHookState.setCompositeReady(true);
    config.selectedPack = spec.id();
    try {
      config.save(new File(FabricLoader.getInstance().getGameDir().toFile(), "config/vulkanis.json"));
    } catch (Exception e) {
      LOG.warn("vulkanis: pack select save failed", e);
    }
    if (old != null) old.close();
  }
  public static String describeSelection(File packDir) {
    PackManager m = new PackManager();
    if (m.trySelect(packDir)) return "selected " + m.active().id();
    return "kept previous (" + m.lastError() + ")";
  }
}
