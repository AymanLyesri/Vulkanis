package com.vulkanis.render;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
public class CompositeReuseTest {
  static com.vulkanis.pack.PassGraph graphM() {
    return new com.vulkanis.pack.PassGraph(java.util.List.of(
      new com.vulkanis.pack.PassGraph.Pass("m", "m.fsh", java.util.List.of("main", "m"), 1.0, true),
      new com.vulkanis.pack.PassGraph.Pass("final", "c.fsh", java.util.List.of("main", "m"), 1.0, false)));
  }
  @Test public void adoptKeepsStateOnSameTopology() {
    var lib1 = new com.vulkanis.pack.ShaderLibrary();
    var lib2 = new com.vulkanis.pack.ShaderLibrary();
    lib2.put(com.vulkanis.pack.ShaderLibrary.key("v", "post/final", "fragment"), "f2");
    var r1 = new CompositeRenderer(lib1, "v", "post/composite", "post/composite");
    var r2 = new CompositeRenderer(lib2, "v", "post/composite", "post/composite");
    r1.setPassGraph(graphM());
    r2.setPassGraph(graphM());
    assertTrue(r1.adoptIfSameTopology(r2, graphM()));
    assertTrue(r1.libraryForTest() == lib2);
  }
  @Test public void adoptRejectsDifferentTopology() {
    var r1 = new CompositeRenderer(new com.vulkanis.pack.ShaderLibrary(), "v", "post/composite", "post/composite");
    var r2 = new CompositeRenderer(new com.vulkanis.pack.ShaderLibrary(), "v", "post/composite", "post/composite");
    r1.setPassGraph(graphM());
    r2.setPassGraph(graphM());
    var other = new com.vulkanis.pack.PassGraph(java.util.List.of(
      new com.vulkanis.pack.PassGraph.Pass("final", "c.fsh", java.util.List.of("main"), 1.0, false)));
    assertFalse(r1.adoptIfSameTopology(r2, other));
  }
  @Test public void adoptLegacyBothWays() {
    var r1 = new CompositeRenderer(new com.vulkanis.pack.ShaderLibrary(), "v", "post/composite", "post/composite");
    var r2 = new CompositeRenderer(new com.vulkanis.pack.ShaderLibrary(), "v", "post/composite", "post/composite");
    var legacy = new com.vulkanis.pack.PassGraph(java.util.List.of());
    assertTrue(r1.adoptIfSameTopology(r2, legacy));
    assertFalse(r1.adoptIfSameTopology(r2, graphM()));
  }
}
