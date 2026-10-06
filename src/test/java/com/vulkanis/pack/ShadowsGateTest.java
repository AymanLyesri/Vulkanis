package com.vulkanis.pack;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class ShadowsGateTest {
  private static PipelineSpec spec(boolean shadows) {
    return new PipelineSpec("x", "X", "1", 2048, shadows, true, true, new com.vulkanis.pack.PassGraph(java.util.List.of()), java.util.List.of());
  }
  @Test public void onWhenMasterAndPackEnable() {
    assertTrue(PackManager.shadowsActive(true, spec(true)));
  }
  @Test public void offWhenMasterOff() {
    assertFalse(PackManager.shadowsActive(false, spec(true)));
  }
  @Test public void offWhenPackDisables() {
    assertFalse(PackManager.shadowsActive(true, spec(false)));
  }
  @Test public void offWhenNoSpec() {
    assertFalse(PackManager.shadowsActive(true, null));
  }
  @Test public void offWhenPackLacksShadowShaders() {
    assertFalse(PackManager.shadowsActive(true,
      new PipelineSpec("x", "X", "1", 2048, true, false, true, new com.vulkanis.pack.PassGraph(java.util.List.of()), java.util.List.of())));
  }
  @Test public void offWhenPackLacksTerrainShaders() {
    assertFalse(PackManager.shadowsActive(true,
      new PipelineSpec("x", "X", "1", 2048, true, true, false, new com.vulkanis.pack.PassGraph(java.util.List.of()), java.util.List.of())));
  }
}
