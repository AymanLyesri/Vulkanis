package com.vulkanis.render;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
public class DebugViewTest {
  @Test public void debugShadersAreTokenFree() {
    assertFalse(DebugView.FRAGMENT.contains("{{"), "pack tokens must never reach jar GLSL");
    assertTrue(DebugView.FRAGMENT.contains("InDepth"));
    assertTrue(DebugView.FRAGMENT.contains("SamplerInfo"));
  }
  @Test public void rampGoesYellowToPurple() {
    String f = DebugView.FRAGMENT;
    assertTrue(f.indexOf("1.0, 0.9, 0.0") < f.indexOf("0.55, 0.1, 0.9"), "yellow(close) must mix toward purple(far)");
    assertTrue(f.contains("max("), "depth divide must be NaN-guarded");
  }
}
