package com.vulkanis.pack;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
public class ExposureCurveTest {
  @Test public void keyOverAvg() {
    assertEquals(1.0, ExposureCurve.apply(0.35, 0.35, 0.5, 3.0), 1e-9);
  }
  @Test public void clampsBothEnds() {
    assertEquals(3.0, ExposureCurve.apply(0.001, 0.35, 0.5, 3.0), 1e-9);
    assertEquals(0.5, ExposureCurve.apply(10.0, 0.35, 0.5, 3.0), 1e-9);
  }
  @Test public void garbageCannotEscapeRange() {
    assertEquals(3.0, ExposureCurve.apply(0.0, 0.35, 0.5, 3.0), 1e-9);
    assertEquals(0.5, ExposureCurve.apply(Double.POSITIVE_INFINITY, 0.35, 0.5, 3.0), 1e-9);
  }
  @Test public void badRangeRejected() {
    assertThrows(IllegalArgumentException.class, () -> ExposureCurve.apply(0.5, 0.35, 3.0, 0.5));
  }
  @Test public void biasScalesInsideClamp() {
    assertEquals(2.0, ExposureCurve.apply(0.35, 0.35, 0.5, 3.0, 2.0), 1e-9);
    assertEquals(3.0, ExposureCurve.apply(0.35, 0.35, 0.5, 3.0, 10.0), 1e-9);
    assertEquals(1.0, ExposureCurve.apply(0.35, 0.35, 0.5, 3.0, 1.0), 1e-9);
  }
  @Test public void blendRisesFastFallsSlow() {
    assertEquals(0.5 + 0.5 * 0.25, ExposureCurve.blend(0.5, 1.0, 0.25, 0.04), 1e-9);
    assertEquals(0.5 - 0.5 * 0.04, ExposureCurve.blend(0.5, 0.0, 0.25, 0.04), 1e-9);
  }
  @Test public void blendTieTakesDownRate() {
    assertEquals(0.5, ExposureCurve.blend(0.5, 0.5, 0.25, 0.04), 1e-9);
  }
}
