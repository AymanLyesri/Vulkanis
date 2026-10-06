package com.vulkanis.screen;

import com.vulkanis.VulkanisClient;
import com.vulkanis.pack.PackLoader;
import com.vulkanis.pack.PipelineSpec;
import java.io.File;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class VulkanisShaderScreen extends Screen {
  private final Screen parent;
  /** Session-persistent: reopening the screen returns to the same tab. */
  private static String selectedTab = null;

  public VulkanisShaderScreen(Screen parent) {
    super(Component.literal("Vulkanis Shaders"));
    this.parent = parent;
  }

  @Override
  protected void init() {
    File dir = new File(Minecraft.getInstance().gameDirectory, "shaderpacks");
    List<PipelineSpec> packs = PackLoader.listPacks(dir);
    String selected = VulkanisClient.selectedPackId();
    int y = 36;
    addRenderableWidget(Button.builder(Component.literal("Vulkanis Shaders: "
        + (com.vulkanis.render.ShadowHookState.compositeEnabled() ? "ON" : "OFF")), b -> {
      com.vulkanis.render.ShadowHookState
        .setCompositeEnabled(!com.vulkanis.render.ShadowHookState.compositeEnabled());
      com.vulkanis.VulkanisConfig.save();
      rebuildWidgets();
    }).bounds(width / 2 - 150, y, 300, 20).build());
    y += 24;
    if (packs.isEmpty()) {
      Button empty = Button.builder(Component.literal("No shader packs found"), b -> {
      }).bounds(width / 2 - 150, y, 300, 20).build();
      empty.active = false;
      addRenderableWidget(empty);
      y += 24;
    }
    for (PipelineSpec pack : packs) {
      String label = (pack.id().equals(selected) ? "> " : "") + pack.name();
      addRenderableWidget(Button.builder(Component.literal(label), b -> {
        VulkanisClient.selectPack(new File(dir, pack.id()));
        rebuildWidgets();
      }).bounds(width / 2 - 150, y, 300, 20).build());
      y += 24;
    }
    String packError = com.vulkanis.render.ShadowHookState.lastPackError();
    if (!packError.isEmpty()) {
      Button err = Button.builder(Component.literal("Pack error: " + packError), b -> {
      }).bounds(width / 2 - 150, y, 300, 20).build();
      err.active = false;
      addRenderableWidget(err);
      y += 24;
    }
    addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
      .bounds(width / 2 - 150, height - 40, 300, 20).build());
    addRenderableWidget(Button.builder(Component.literal(depthLabel()), b -> {
      cycleDepthView();
      rebuildWidgets();
    }).bounds(width / 2 - 150, height - 64, 300, 20).build());
    addSettings(dir, packs, selected);
  }

  private static String depthLabel() {
    boolean on = com.vulkanis.render.ShadowHookState.showDepth();
    int cascade = com.vulkanis.render.ShadowHookState.debugCascade();
    if (!on) return "Depth View: OFF";
    if (cascade < 0) return "Depth View: MAIN";
    return "Depth View: CASCADE " + cascade;
  }

  private static void cycleDepthView() {
    boolean on = com.vulkanis.render.ShadowHookState.showDepth();
    int cascade = com.vulkanis.render.ShadowHookState.debugCascade();
    if (!on) {
      com.vulkanis.render.ShadowHookState.setShowDepth(true);
      com.vulkanis.render.ShadowHookState.setDebugCascade(-1);
    } else if (cascade < com.vulkanis.render.shadow.CascadeShadows.CASCADE_COUNT - 1) {
      com.vulkanis.render.ShadowHookState.setDebugCascade(cascade + 1);
    } else {
      com.vulkanis.render.ShadowHookState.setShowDepth(false);
    }
  }

  private void addSettings(File dir, List<PipelineSpec> packs, String selected) {    PipelineSpec active = null;
    for (PipelineSpec p : packs) {
      if (p.id().equals(selected)) { active = p; break; }
    }
    if (active == null) return;
    final PipelineSpec current = active;
    File packDir = new File(dir, active.id());
    List<com.vulkanis.pack.PackSetting> settings;
    try {
      settings = com.vulkanis.pack.PackLoader.parseSettings(packDir);
    } catch (Exception e) {
      com.vulkanis.render.ShadowHookState.setLastPackError(active.id() + ": " + e.getMessage());
      return;
    }
    if (settings.isEmpty()) return;
    com.vulkanis.pack.PackValues values;
    try {
      values = com.vulkanis.pack.PackValues.load(new File(packDir, "settings.json"), settings);
    } catch (Exception e) {
      return;
    }
    java.util.List<com.vulkanis.pack.PackCategories.Category> order =
      com.vulkanis.pack.PackCategories.groupOrder(settings, current.categories());
    if (selectedTab == null || order.stream().noneMatch(c -> c.id().equals(selectedTab))) {
      selectedTab = com.vulkanis.pack.PackCategories.defaultTabId(order);
    }
    int y = 36 + (packs.size() + 2) * 24 + 12;
    y = addTabRow(order, y);
    java.util.List<com.vulkanis.pack.PackSetting> visible =
      com.vulkanis.pack.PackCategories.visibleSettings(settings, selectedTab);
    for (com.vulkanis.pack.PackSetting setting : visible) {
      final com.vulkanis.pack.PackSetting s = setting;
      double v = values.get(s);
      if (s.isBool()) {
        String label = s.label() + ": " + (v > 0.5 ? "ON" : "OFF");
        addRenderableWidget(Button.builder(Component.literal(label), b -> {
          flipBool(dir, current, s);
          rebuildWidgets();
        }).bounds(width / 2 - 150, y, 260, 20).build());
        addRenderableWidget(Button.builder(Component.literal("R"), b -> {
          resetSetting(dir, current, s);
          rebuildWidgets();
        }).bounds(width / 2 + 114, y, 36, 20).build());
        y += 24;
      } else {
        String fmt = s.isInt() ? Integer.toString((int) Math.round(v)) : String.format("%.3f", v);
        addRenderableWidget(Button.builder(Component.literal(s.label() + ": " + fmt), b -> {
        }).bounds(width / 2 - 110, y, 182, 20).build()).active = false;
        addRenderableWidget(Button.builder(Component.literal("-"), b -> {
          stepSetting(dir, current, s, -s.step());
          rebuildWidgets();
        }).bounds(width / 2 - 150, y, 36, 20).build());
        addRenderableWidget(Button.builder(Component.literal("R"), b -> {
          resetSetting(dir, current, s);
          rebuildWidgets();
        }).bounds(width / 2 + 76, y, 34, 20).build());
        addRenderableWidget(Button.builder(Component.literal("+"), b -> {
          stepSetting(dir, current, s, s.step());
          rebuildWidgets();
        }).bounds(width / 2 + 114, y, 36, 20).build());
        y += 24;
      }
    }
  }

  private int addTabRow(java.util.List<com.vulkanis.pack.PackCategories.Category> order, int y) {
    if (order.size() <= 1) return y;
    if (order.size() > 5) {
      String label = "Tab: ";
      for (var c : order) {
        if (c.id().equals(selectedTab)) { label += c.label() + " >"; break; }
      }
      final java.util.List<com.vulkanis.pack.PackCategories.Category> tabs = order;
      addRenderableWidget(Button.builder(Component.literal(label), b -> {
        int i = 0;
        for (int k = 0; k < tabs.size(); k++) {
          if (tabs.get(k).id().equals(selectedTab)) { i = k; break; }
        }
        selectedTab = tabs.get((i + 1) % tabs.size()).id();
        rebuildWidgets();
      }).bounds(width / 2 - 150, y, 300, 20).build());
      return y + 24;
    }
    int n = order.size();
    int w = (300 - (n - 1) * 4) / n;
    for (int i = 0; i < n; i++) {
      final String id = order.get(i).id();
      String label = (id.equals(selectedTab) ? "> " : "") + order.get(i).label();
      int x = width / 2 - 150 + i * (w + 4);
      addRenderableWidget(Button.builder(Component.literal(label), b -> {
        selectedTab = id;
        rebuildWidgets();
      }).bounds(x, y, w, 20).build());
    }
    return y + 24;
  }

  private void flipBool(File dir, PipelineSpec pack, com.vulkanis.pack.PackSetting s) {
    try {
      File packDir = new File(dir, pack.id());
      List<com.vulkanis.pack.PackSetting> settings =
        com.vulkanis.pack.PackLoader.parseSettings(packDir);
      com.vulkanis.pack.PackValues values =
        com.vulkanis.pack.PackValues.load(new File(packDir, "settings.json"), settings);
      values.set(s.id(), values.get(s) > 0.5 ? 0.0 : 1.0);
      values.save(new File(packDir, "settings.json"));
      VulkanisClient.selectPack(packDir);
    } catch (Exception ignored) { }
  }

  private void resetSetting(File dir, PipelineSpec pack, com.vulkanis.pack.PackSetting s) {
    try {
      File packDir = new File(dir, pack.id());
      List<com.vulkanis.pack.PackSetting> settings =
        com.vulkanis.pack.PackLoader.parseSettings(packDir);
      com.vulkanis.pack.PackValues values =
        com.vulkanis.pack.PackValues.load(new File(packDir, "settings.json"), settings);
      values.set(s.id(), s.def());
      values.save(new File(packDir, "settings.json"));
      VulkanisClient.selectPack(packDir);
    } catch (Exception ignored) { }
  }

  private void stepSetting(File dir, PipelineSpec pack, com.vulkanis.pack.PackSetting s, double delta) {
    try {
      File packDir = new File(dir, pack.id());
      List<com.vulkanis.pack.PackSetting> settings =
        com.vulkanis.pack.PackLoader.parseSettings(packDir);
      com.vulkanis.pack.PackValues values =
        com.vulkanis.pack.PackValues.load(new File(packDir, "settings.json"), settings);
      values.set(s.id(), s.clamp(values.get(s) + delta));
      values.save(new File(packDir, "settings.json"));
      VulkanisClient.selectPack(packDir);
    } catch (Exception ignored) { }
  }

  @Override
  public void onClose() {
    minecraft.setScreenAndShow(parent);
  }
}
