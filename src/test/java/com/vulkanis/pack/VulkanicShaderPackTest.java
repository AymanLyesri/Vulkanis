package com.vulkanis.pack;
import org.junit.jupiter.api.Test;
import java.io.File;
import static org.junit.jupiter.api.Assertions.*;
public class VulkanicShaderPackTest {
  @Test public void myBasicValidates() throws Exception {
    File dir = new File("run/shaderpacks/VulkanicShader");
    assertTrue(dir.exists(), "pack missing");
    assertDoesNotThrow(() -> PackLoader.loadSpec(dir));
  }
  @Test public void shippedGraphsSelfConsistent() throws Exception {
    java.util.Map<String, Integer> expectedPasses = java.util.Map.of("VulkanicShader", 5, "DemoShader", 2);
    for (var e : expectedPasses.entrySet()) {
      File dir = new File("run/shaderpacks/" + e.getKey());
      var settings = PackLoader.parseSettings(dir);
      var passes = PackLoader.parsePasses(dir);
      assertEquals(e.getValue(), passes.passes().size(), e.getKey() + " pass count");
      var values = PackValues.load(new File(dir, "settings.json"), settings);
      for (var p : passes.passes()) {
        String glsl = java.nio.file.Files.readString(new File(dir, "shaders/" + p.frag()).toPath());
        String applied = values.apply(glsl, settings);
        assertFalse(applied.contains("{{"), e.getKey() + "/" + p.frag() + " has unresolved token");
      }
    }
    var cats = PackLoader.parseCategories(new File("run/shaderpacks/VulkanicShader"));
    assertEquals(java.util.List.of("shadow", "bloom", "exposure", "effects"),
      cats.stream().map(PackCategories.Category::id).toList());
  }
}
