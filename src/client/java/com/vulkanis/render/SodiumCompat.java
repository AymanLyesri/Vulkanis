package com.vulkanis.render;
public final class SodiumCompat {
  public static final String REQUIRED_SODIUM = "0.9.3";
  private SodiumCompat(){}
  public static FrameGraph.Mode modeFor(String sodiumVersion) {
    return modeFor(sodiumVersion, true, true);
  }
  public static FrameGraph.Mode modeFor(String sodiumVersion, boolean isVulkan) {
    return modeFor(sodiumVersion, isVulkan, true);
  }
  public static FrameGraph.Mode modeFor(String sodiumVersion, boolean isVulkan, boolean shadowAllocOk) {
    if (!isVulkan) return FrameGraph.Mode.DISABLED;
    if (sodiumVersion == null || !sodiumVersion.startsWith(REQUIRED_SODIUM) || !shadowAllocOk)
      return FrameGraph.Mode.POST_ONLY;
    return FrameGraph.Mode.SHADOW_WORLD_COMPOSITE;
  }
  public static boolean shouldComposite(boolean compiledOk) {
    return compiledOk;
  }
}
