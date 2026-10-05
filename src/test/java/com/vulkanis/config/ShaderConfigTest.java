package com.vulkanis.config;
import org.junit.jupiter.api.Test;
import java.io.File;
import static org.junit.jupiter.api.Assertions.*;
public class ShaderConfigTest {
  @Test public void roundTripsShadowSize() throws Exception {
    ShaderConfig c = new ShaderConfig();
    c.shadowSize = 4096;
    File f = new File("build/tmp/config-test.json");
    c.save(f);
    assertEquals(4096, ShaderConfig.load(f).shadowSize);
  }
  @Test public void missingFileGivesDefaults() throws Exception {
    ShaderConfig c = ShaderConfig.load(new File("build/tmp/does-not-exist.json"));
    assertEquals(2048, c.shadowSize);
    assertEquals("VulkanicShader", c.selectedPack);
  }
}
