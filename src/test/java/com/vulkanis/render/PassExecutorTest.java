package com.vulkanis.render;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
public class PassExecutorTest {
  @Test public void samplerNames() {
    assertEquals("InBlur", PassExecutor.samplerName("blur"));
    assertEquals("InBright", PassExecutor.samplerName("bright"));
    assertEquals("InSlow", PassExecutor.samplerName("slow"));
  }
  @Test public void targetSizes() {
    assertEquals(new PassExecutor.Size(480, 270), PassExecutor.targetSize(1920, 1080, 0.25));
    assertEquals(new PassExecutor.Size(1920, 1080), PassExecutor.targetSize(1920, 1080, 1.0));
  }
  @Test public void rejectsUnknownInputPure() {
    var known = java.util.Map.of("main", 0, "bright", 1);
    assertThrows(IllegalArgumentException.class,
      () -> PassExecutor.resolveInputs(java.util.List.of("nope"), known));
    assertDoesNotThrow(() -> PassExecutor.resolveInputs(java.util.List.of("main", "bright"), known));
  }
  @Test public void exposureTargets() {
    assertEquals(1.0, PassExecutor.exposureTarget(1.0f), 1e-9);
    assertEquals(1.8, PassExecutor.exposureTarget(-1.0f), 1e-9);
  }
  @Test public void bindsOnlyProducedOutputs() {
    var graph = new com.vulkanis.pack.PassGraph(java.util.List.of(
      new com.vulkanis.pack.PassGraph.Pass("bright", "bright.fsh", java.util.List.of("main"), 0.25, false),
      new com.vulkanis.pack.PassGraph.Pass("blur", "blur.fsh", java.util.List.of("bright"), 0.25, false),
      new com.vulkanis.pack.PassGraph.Pass("final", "composite.fsh", java.util.List.of("main", "blur"), 1.0, false)));
    assertEquals(java.util.List.of(), PassExecutor.boundOutputs(graph, 0));
    assertEquals(java.util.List.of("bright"), PassExecutor.boundOutputs(graph, 1));
    assertEquals(java.util.List.of("bright", "blur"), PassExecutor.boundOutputs(graph, 2));
  }
  @Test public void historyBindsReadSideToSelf() {
    var graph = new com.vulkanis.pack.PassGraph(java.util.List.of(
      new com.vulkanis.pack.PassGraph.Pass("m", "m.fsh", java.util.List.of("main", "m"), 0.25, true),
      new com.vulkanis.pack.PassGraph.Pass("final", "composite.fsh", java.util.List.of("main", "m"), 1.0, false)));
    assertEquals(java.util.List.of(), PassExecutor.boundOutputs(graph, 0));
    assertEquals(java.util.List.of("m"), PassExecutor.boundOutputs(graph, 1));
    assertEquals("InM", PassExecutor.samplerName("m"));
  }
  @Test public void twoHistoryChainBinds() {
    var graph = new com.vulkanis.pack.PassGraph(java.util.List.of(
      new com.vulkanis.pack.PassGraph.Pass("m", "m.fsh", java.util.List.of("main", "m"), 1.0, true),
      new com.vulkanis.pack.PassGraph.Pass("s", "s.fsh", java.util.List.of("m", "s"), 1.0, true),
      new com.vulkanis.pack.PassGraph.Pass("final", "c.fsh", java.util.List.of("main", "s"), 1.0, false)));
    assertEquals(java.util.List.of(), PassExecutor.boundOutputs(graph, 0));
    assertEquals(java.util.List.of("m"), PassExecutor.boundOutputs(graph, 1));
    assertEquals(java.util.List.of("m", "s"), PassExecutor.boundOutputs(graph, 2));
  }
  @Test public void layoutKeepsAllTransientSamplers() {
    var b = com.mojang.renderpearl.api.pipeline.BindGroupLayout.builder()
      .withUniform("InSampler", com.mojang.renderpearl.api.pipeline.UniformType.COMBINED_IMAGE_SAMPLER)
      .withUniform("InDepth", com.mojang.renderpearl.api.pipeline.UniformType.COMBINED_IMAGE_SAMPLER)
      .withUniform("SamplerInfo", com.mojang.renderpearl.api.pipeline.UniformType.UNIFORM_BUFFER);
    for (String t : java.util.List.of("bright", "blur", "measure", "slow")) {
      b.withUniform(PassExecutor.samplerName(t),
        com.mojang.renderpearl.api.pipeline.UniformType.COMBINED_IMAGE_SAMPLER);
    }
    var flat = com.mojang.renderpearl.api.pipeline.BindGroupLayout.flattenUniforms(
      java.util.List.of(b.build()));
    var names = flat.stream()
      .map(com.mojang.renderpearl.api.pipeline.BindGroupLayout.UniformDescription::name)
      .toList();
    assertTrue(names.contains("InSlow"), "layout lost InSlow, got: " + names);
    assertEquals(7, flat.size(), "layout entry count, got: " + names);
  }
}
