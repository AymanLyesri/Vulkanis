package com.vulkanis.render.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/** The fitted light matrix is frozen between refits while the camera moves.
 *  Compensating by the camera delta must map a fixed world point to the
 *  same light-space coord from any camera position. */
class LightCamCompensationTest {
  @Test public void sameWorldPointMapsIdenticallyAcrossCameraMoves() {
    Matrix4f fitted = new Matrix4f().ortho(-128, 128, -128, 128, 1, 512, true)
      .mul(new Matrix4f().lookAt(new Vector3f(0, -200, -100), new Vector3f(0, 0, 0), new Vector3f(0, 1, 0)));
    Vector3f worldPoint = new Vector3f(10.5f, 70.0f, -30.25f);
    Vector3f camA = new Vector3f(0, 70, 0);
    Vector3f camB = new Vector3f(3.25f, 71.5f, -2.75f);
    Vector3f expected = fitted.transformPosition(new Vector3f(worldPoint).sub(camA), new Vector3f());
    Matrix4f compensated = new Matrix4f();
    CascadeShadows.slideMatrix(fitted, camB.x - camA.x, camB.y - camA.y, camB.z - camA.z, compensated);
    Vector3f actual = compensated.transformPosition(new Vector3f(worldPoint).sub(camB), new Vector3f());
    assertEquals(expected.x, actual.x, 1e-4f);
    assertEquals(expected.y, actual.y, 1e-4f);
    assertEquals(expected.z, actual.z, 1e-5f);
  }
}
