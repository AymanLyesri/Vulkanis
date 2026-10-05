package com.vulkanis.render;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
public class FrameGraphTest {
  @Test public void fallbackWhenNoSodium() {
    FrameGraph g = new FrameGraph(false, true, 2048);
    assertEquals(FrameGraph.Mode.POST_ONLY, g.mode());
  }
  @Test public void fullWhenSodium() {
    FrameGraph g = new FrameGraph(true, true, 2048);
    assertEquals(FrameGraph.Mode.SHADOW_WORLD_COMPOSITE, g.mode());
  }
  @Test public void shadowOomDisablesShadows() {
    FrameGraph g = new FrameGraph(true, false, 4096);
    assertEquals(FrameGraph.Mode.POST_ONLY, g.mode());
  }
  @Test public void glBackendDisables() {
    FrameGraph g = new FrameGraph(true, true, 2048, false);
    assertEquals(FrameGraph.Mode.DISABLED, g.mode());
  }
}
