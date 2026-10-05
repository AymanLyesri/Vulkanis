package com.vulkanis.render;
public final class FrameGraph {
  public enum Mode { POST_ONLY, SHADOW_WORLD_COMPOSITE, DISABLED }
  private final Mode mode;
  public FrameGraph(boolean sodiumPresent, boolean shadowAllocOk, int shadowSize) {
    this(sodiumPresent, shadowAllocOk, shadowSize, true);
  }
  public FrameGraph(boolean sodiumPresent, boolean shadowAllocOk, int shadowSize, boolean isVulkan) {
    if (!isVulkan) this.mode = Mode.DISABLED;
    else if (!sodiumPresent || !shadowAllocOk) this.mode = Mode.POST_ONLY;
    else this.mode = Mode.SHADOW_WORLD_COMPOSITE;
  }
  public Mode mode() { return mode; }
}
