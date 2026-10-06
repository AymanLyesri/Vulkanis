package com.vulkanis.render.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

class CascadeShadowsTest {
  @Test public void uboLayoutIsStd140Exact() {
    assertEquals(64 * 3 + 16 * 3 + 16 + 16, CascadeShadows.UBO_BYTES);
  }

  @Test public void splitsEndAtCoverageInOrder() {
    float[] splits = CascadeShadows.splitDistances(128.0f, 3);
    assertEquals(3, splits.length);
    for (int i = 1; i < 3; i++) {
      assertTrue(splits[i] > splits[i - 1], "splits must grow");
    }
    assertEquals(128.0f, splits[2], 1e-3f);
    assertTrue(splits[0] > 8.0f && splits[0] < 128.0f);
  }

  @Test public void cascadeSizesScaleDownWithDistance() {
    assertEquals(2048, CascadeShadows.cascadeSize(2048, 0));
    assertEquals(1024, CascadeShadows.cascadeSize(2048, 1));
    assertEquals(1024, CascadeShadows.cascadeSize(2048, 2));
    assertEquals(4096, CascadeShadows.cascadeSize(4096, 0));
    assertEquals(2048, CascadeShadows.cascadeSize(4096, 1));
  }

  @Test public void cameraSectionIsInsideNearCascade() {
    CascadeShadows shadows = fittedNoon();
    assertTrue(shadows.sectionInCascade(0, 0, 64, 0));
  }

  @Test public void farSectionIsOutsideNearCascade() {
    CascadeShadows shadows = fittedNoon();
    assertFalse(shadows.sectionInCascade(0, 10000, 64, 10000));
    assertTrue(shadows.sectionInCascade(2, 0, 64, 0));
  }

  private static CascadeShadows fittedNoon() {
    CascadeShadows shadows = CascadeShadows.get();
    Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(70.0), 16.0f / 9.0f, 0.05f, 1000.0f);
    Matrix4f viewRot = new Matrix4f();
    shadows.prepare(0.0, 70.0, 0.0, (float) (Math.PI / 4), proj, viewRot);
    return shadows;
  }
}
