package com.vulkanis.render.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import com.vulkanis.pack.PipelineSpec;
import org.junit.jupiter.api.Test;

class ShadowResolveTest {
  @Test public void sizeAcceptsSpecValue() {
    assertEquals(4096, CascadeShadows.resolveSize(new PipelineSpec("x", "X", "1", 4096, true, false, false, new com.vulkanis.pack.PassGraph(java.util.List.of()), java.util.List.of(), 1)));
  }

  @Test public void sizeFallsBackWithoutSpec() {
    assertEquals(2048, CascadeShadows.resolveSize(null));
  }

  @Test public void sizeSnapsOutliers() {
    assertEquals(4096, CascadeShadows.resolveSize(new PipelineSpec("x", "X", "1", 8192, true, false, false, new com.vulkanis.pack.PassGraph(java.util.List.of()), java.util.List.of(), 1)));
    assertEquals(2048, CascadeShadows.resolveSize(new PipelineSpec("x", "X", "1", 3072, true, false, false, new com.vulkanis.pack.PassGraph(java.util.List.of()), java.util.List.of(), 1)));
    assertEquals(1024, CascadeShadows.resolveSize(new PipelineSpec("x", "X", "1", 512, true, false, false, new com.vulkanis.pack.PassGraph(java.util.List.of()), java.util.List.of(), 1)));
  }

  @Test public void radiusClampsToPackRange() {
    assertEquals(128.0f, CascadeShadows.resolveRadius(128.0f), 1e-6f);
    assertEquals(32.0f, CascadeShadows.resolveRadius(0.0f), 1e-6f);
    assertEquals(256.0f, CascadeShadows.resolveRadius(9999.0f), 1e-6f);
  }
}
