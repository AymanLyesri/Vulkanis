package com.vulkanis.shadow;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
public class ShadowManagerTest {
  @Test public void stableUnderJitter() {
    float[] m1 = ShadowManager.computeMatrix(new float[]{0.3f,-1f,0.2f}, new float[]{0,64,0}, 48f, 1f, 200f);
    float[] m2 = ShadowManager.computeMatrix(new float[]{0.3f,-1f,0.2f}, new float[]{0.05f,64,0}, 48f, 1f, 200f);
    assertEquals(16, m1.length);
    float drift = 0; for (int i = 0; i < 16; i++) drift += Math.abs(m1[i]-m2[i]);
    assertTrue(drift < 2.0f, "jitter drift=" + drift);
  }
  @Test public void rejectsZeroSun() {
    assertThrows(IllegalArgumentException.class, () ->
      ShadowManager.computeMatrix(new float[]{0,0,0}, new float[]{0,64,0}, 48f, 1f, 200f));
  }
}
