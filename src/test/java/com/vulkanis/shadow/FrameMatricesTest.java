package com.vulkanis.shadow;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
public class FrameMatricesTest {
  static float[] identity() {
    float[] m = new float[16];
    m[0] = 1; m[5] = 1; m[10] = 1; m[15] = 1;
    return m;
  }
  @Test public void invertIdentity() {
    float[] inv = FrameMatrices.invert(identity());
    assertArrayEquals(identity(), inv, 1e-6f);
  }
  @Test public void invertRoundTrips() {
    float[] m = {2, 0, 0, 0, 0, 3, 0, 0, 0, 0, 4, 0, 5, 6, 7, 1};
    float[] back = FrameMatrices.mul(m, FrameMatrices.invert(m));
    assertArrayEquals(identity(), back, 1e-4f);
  }
  @Test public void viewTranslatesByNegatedPos() {
    float[] v = FrameMatrices.viewFromRotationAndPos(identity(), new float[]{10, 64, -5});
    assertEquals(-10f, v[12], 1e-6f);
    assertEquals(-64f, v[13], 1e-6f);
    assertEquals(5f, v[14], 1e-6f);
  }
  @Test public void rejectsSingular() {
    assertThrows(IllegalArgumentException.class, () -> FrameMatrices.invert(new float[16]));
  }
}
