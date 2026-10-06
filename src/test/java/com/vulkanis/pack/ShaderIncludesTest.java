package com.vulkanis.pack;
import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ShaderIncludesTest {
  private static ShaderIncludes.Loader loader(Map<String, String> files) {
    return files::get;
  }

  @Test public void leavesShaderWithoutIncludesAlone() {
    String glsl = "#version 460 core\nvoid main() {}";
    assertEquals(glsl, ShaderIncludes.resolve(glsl, loader(Map.of())));
  }

  @Test public void inlinesNamedInclude() {
    String glsl = "#version 460 core\n#include <vulkanis/poisson.glsl>\nvoid main() {}";
    String out = ShaderIncludes.resolve(glsl,
      loader(Map.of("poisson.glsl", "const int TAPS = 16;")));
    assertTrue(out.contains("const int TAPS = 16;"), out);
    assertFalse(out.contains("#include"), out);
  }

  @Test public void resolvesNestedIncludes() {
    String glsl = "#include <vulkanis/a.glsl>";
    String out = ShaderIncludes.resolve(glsl, loader(Map.of(
      "a.glsl", "A\n#include <vulkanis/b.glsl>\n",
      "b.glsl", "B")));
    assertEquals("A\nB", out);
  }

  @Test public void missingIncludeNamesTheFile() {
    ShaderIncludes.IncludeException e = assertThrows(ShaderIncludes.IncludeException.class,
      () -> ShaderIncludes.resolve("#include <vulkanis/gone.glsl>", loader(Map.of())));
    assertTrue(e.getMessage().contains("gone.glsl"), e.getMessage());
  }

  @Test public void cyclicIncludeIsRejected() {
    ShaderIncludes.IncludeException e = assertThrows(ShaderIncludes.IncludeException.class,
      () -> ShaderIncludes.resolve("#include <vulkanis/a.glsl>", loader(Map.of(
        "a.glsl", "#include <vulkanis/b.glsl>",
        "b.glsl", "#include <vulkanis/a.glsl>"))));
    assertTrue(e.getMessage().contains("cycl"), e.getMessage());
  }

  @Test public void rejectsPathsOutsideVulkanis() {
    assertThrows(ShaderIncludes.IncludeException.class,
      () -> ShaderIncludes.resolve("#include <other/evil.glsl>", loader(Map.of())));
  }
}
