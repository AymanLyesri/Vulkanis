package com.vulkanis.pack;
import org.junit.jupiter.api.Test;
import java.io.File;
import static org.junit.jupiter.api.Assertions.*;
public class RealPackTest {
  @Test public void vulkanicShaderParsesAndSubstitutes() throws Exception {
    File dir = new File("run/shaderpacks/VulkanicShader");
    assertTrue(new File(dir, "pipeline.json").exists(), "pack missing");
    var settings = PackLoader.parseSettings(dir);
    assertEquals(6, settings.size(), "want 6 settings, got " + settings.size());
    PackValues v = PackValues.load(new File(dir, "settings.json"), settings);
    String out = v.apply("int s={{shadowSteps}}; float b={{shadowBias}};", settings);
    assertFalse(out.contains("{{"), "unsubstituted token in: " + out);
  }
  @Test public void missingPackShadersLeaveLibraryEmpty() {
    ShaderLibrary lib = new ShaderLibrary();
    assertFalse(lib.has(ShaderLibrary.key("vulkanis", "pack/shadow", "fragment")));
    assertFalse(lib.has(ShaderLibrary.key("vulkanis", "pack/terrain", "fragment")));
  }
  @Test public void vulkanicShaderOwnsItsShadows() throws Exception {
    File dir = new File("run/shaderpacks/VulkanicShader");
    PipelineSpec s = PackLoader.loadSpec(dir);
    assertTrue(s.hasShadowShaders(), "VulkanicShader must ship shadow.vsh/.fsh");
    assertTrue(s.hasTerrainShaders(), "VulkanicShader must ship terrain.vsh/.fsh");
  }
}
