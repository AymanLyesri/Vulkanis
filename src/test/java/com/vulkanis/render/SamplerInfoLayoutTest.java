package com.vulkanis.render;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.jupiter.api.Assertions.*;
public class SamplerInfoLayoutTest {
  @Test public void exposureOffsetMatchesPad0() {
    assertEquals(CompositeRenderer.SAMPLER_EXPOSURE_OFFSET, CompositeRenderer.SAMPLER_PAD0_OFFSET);
    assertEquals(192, CompositeRenderer.SAMPLER_INFO_BYTES);
  }
  @Test public void writerRoundTrips() {
    float[] ident = new float[16];
    for (int i = 0; i < 4; i++) ident[i * 5] = 1.0f;
    ByteBuffer info = CompositeRenderer.writeSamplerInfo(1920, 1080, 0, 1.1f, 2.2f,
      new float[]{0.0f, 1.0f, 0.0f}, new float[]{4.0f, 5.0f, 6.0f}, ident, ident, 1.5f);
    info.order(ByteOrder.nativeOrder());
    assertEquals(1920.0f, info.getFloat(0), 1e-6);
    assertEquals(1080.0f, info.getFloat(4), 1e-6);
    assertEquals(0, info.getInt(16));
    assertEquals(1.1f, info.getFloat(20), 1e-6);
    assertEquals(2.2f, info.getFloat(24), 1e-6);
    assertEquals(1.5f, info.getFloat(28), 1e-6);
    assertEquals(1.0f, info.getFloat(36), 1e-6);
    assertEquals(4.0f, info.getFloat(48), 1e-6);
  }
  @Test public void smoothingConverges() {
    float v = CompositeRenderer.smoothExposure(1.0f, 1.8);
    assertTrue(v > 1.0f && v < 1.8);
    assertEquals(1.8f, CompositeRenderer.smoothExposure(1.8f, 1.8), 1e-6);
  }
}
