package com.vulkanis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class BackendCheckTest {
  @Test
  void classifiesVulkanDevice() {
    assertEquals(BackendCheck.Backend.VULKAN,
      BackendCheck.classify("com.mojang.renderpearl.backend.vulkan.VulkanDevice"));
  }

  @Test
  void classifiesOpenGlDevice() {
    assertEquals(BackendCheck.Backend.OPENGL,
      BackendCheck.classify("com.mojang.renderpearl.backend.opengl.GlDevice"));
  }

  @Test
  void unknownForNullOrUnrecognized() {
    assertEquals(BackendCheck.Backend.UNKNOWN, BackendCheck.classify(null));
    assertEquals(BackendCheck.Backend.UNKNOWN, BackendCheck.classify("com.acme.FutureDevice"));
  }
}
