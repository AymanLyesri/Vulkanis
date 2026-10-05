package com.vulkanis.render;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
public class SodiumCompatTest {
  @Test public void absentSodiumIsPostOnly() {
    assertEquals(FrameGraph.Mode.POST_ONLY, SodiumCompat.modeFor(null));
  }
  @Test public void matchingSodiumIsFull() {
    assertEquals(FrameGraph.Mode.SHADOW_WORLD_COMPOSITE, SodiumCompat.modeFor("0.9.3-alpha.1+mc26.3"));
  }
  @Test public void mismatchedSodiumIsPostOnly() {
    assertEquals(FrameGraph.Mode.POST_ONLY, SodiumCompat.modeFor("0.9.1+mc26.2"));
  }
  @Test public void openGlDisables() {
    assertEquals(FrameGraph.Mode.DISABLED, SodiumCompat.modeFor("0.9.3-alpha.1+mc26.3", false));
  }
  @Test public void failedShadowAllocIsPostOnly() {
    assertEquals(FrameGraph.Mode.POST_ONLY, SodiumCompat.modeFor("0.9.3-alpha.1+mc26.3", true, false));
  }
  @Test public void skipCompositeOnCompileFailure() {
    assertFalse(SodiumCompat.shouldComposite(false));
    assertTrue(SodiumCompat.shouldComposite(true));
  }
}
