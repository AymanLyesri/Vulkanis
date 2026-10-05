package com.vulkanis.render.shadow;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

/** Diagnostic: what NDC z does our shadow ortho produce for eye distances
 *  1 (near plane), 256 (center), 511 (far plane)? Prints the mapping; fails
 *  loudly if it is not near->0 / far->1. */
class OrthoConventionTest {
  @Test public void orthoMapsNearToZeroFarToOne() {
    Matrix4f ortho = new Matrix4f().ortho(-128, 128, -128, 128, 1.0f, 512.0f, true);
    float near = ortho.transform(new Vector4f(0, 0, -1, 1)).z;
    float mid = ortho.transform(new Vector4f(0, 0, -256, 1)).z;
    float far = ortho.transform(new Vector4f(0, 0, -512, 1)).z;
    System.out.println("ORTHO near=" + near + " mid=" + mid + " far=" + far);
    org.junit.jupiter.api.Assertions.assertEquals(0.0f, near, 1e-5f);
    org.junit.jupiter.api.Assertions.assertEquals(255.0f / 511.0f, mid, 1e-5f);
    org.junit.jupiter.api.Assertions.assertEquals(1.0f, far, 1e-5f);
  }
}
