package com.vulkanis.render.shadow;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
public class EntityDepthTest {
  @Test public void depthShaderUsesSunMatricesNotProjMat() {
    assertTrue(EntityDepth.VERTEX.contains("CascadeViewProjection"));
    assertTrue(EntityDepth.VERTEX.contains("InverseViewRotation"));
    assertTrue(EntityDepth.VERTEX.contains("ModelViewMat"));
    assertFalse(EntityDepth.VERTEX.contains("ProjMat"), "must not use main-camera projection");
  }
  @Test public void depthShaderReadsEntityPositionSlot() {
    assertTrue(EntityDepth.VERTEX.contains("layout(location = 0) in vec3 Position"));
  }
}
