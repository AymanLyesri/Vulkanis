package com.vulkanis.pack;
import java.io.File;
import java.util.*;
public final class PackManager {
  private PipelineSpec active;
  private String lastError = "";
  public synchronized boolean trySelect(File packDir) {
    try {
      active = PackLoader.loadSpec(packDir);
      lastError = "";
      return true;
    } catch (PackLoader.PackException e) {
      lastError = packDir.getName() + ": " + e.getMessage();
      return false;
    }
  }
  public synchronized PipelineSpec active() { return active; }
  public static boolean shadowsActive(boolean masterOn, PipelineSpec spec) {
    return masterOn && spec != null && spec.shadowsEnabled()
        && spec.hasShadowShaders() && spec.hasTerrainShaders();
  }  public synchronized String lastError() { return lastError; }
  public synchronized List<PipelineSpec> rescan(File shaderpacksDir) {
    List<PipelineSpec> found = PackLoader.listPacks(shaderpacksDir);
    if (active != null && found.stream().noneMatch(s -> s.id().equals(active.id()))) {
      lastError = "active pack removed, keeping " + active.id();
    }
    return found;
  }
}
