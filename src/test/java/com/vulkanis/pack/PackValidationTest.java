package com.vulkanis.pack;
import org.junit.jupiter.api.Test;
import java.io.File;
import java.nio.file.Files;
import static org.junit.jupiter.api.Assertions.*;
public class PackValidationTest {
  static File pack(String name, String json) throws Exception {
    File dir = new File("build/tmp/" + name);
    dir.mkdirs();
    new File(dir, "shaders").mkdirs();
    new File(new File(dir, "shaders"), "composite.vsh").createNewFile();
    new File(new File(dir, "shaders"), "composite.fsh").createNewFile();
    Files.writeString(new File(dir, "pipeline.json").toPath(), json);
    return dir;
  }
  @Test public void rejectsTrailingComma() throws Exception {
    File dir = pack("trailingcomma", "{\"id\":\"x\",\"shadowSize\":2048,}");
    assertThrows(PackLoader.PackException.class, () -> PackLoader.loadSpec(dir));
  }
  @Test public void rejectsNonJson() throws Exception {
    File dir = pack("nonjson", "not json at all \"id\":\"y\"");
    assertThrows(PackLoader.PackException.class, () -> PackLoader.loadSpec(dir));
  }
  @Test public void rejectsMissingId() throws Exception {
    File dir = pack("noid", "{\"name\":\"No Id\"}");
    assertThrows(PackLoader.PackException.class, () -> PackLoader.loadSpec(dir));
  }
  @Test public void rejectsBadShadowSize() throws Exception {
    File dir = pack("badsize", "{\"id\":\"x\",\"shadowSize\":8192}");
    assertThrows(PackLoader.PackException.class, () -> PackLoader.loadSpec(dir));
  }
  @Test public void shadowsDefaultsEnabledWhenMissing() throws Exception {
    File dir = pack("noshadows", "{\"id\":\"x\",\"shadowSize\":2048}");
    assertTrue(PackLoader.loadSpec(dir).shadowsEnabled());
  }
  @Test public void shadowsFlagFalse() throws Exception {
    File dir = pack("shadowsOff", "{\"id\":\"x\",\"shadows\":{\"enabled\":false}}");
    assertFalse(PackLoader.loadSpec(dir).shadowsEnabled());
  }
  @Test public void shadowsFlagTrue() throws Exception {
    File dir = pack("shadowsOn", "{\"id\":\"x\",\"shadows\":{\"enabled\":true}}");
    assertTrue(PackLoader.loadSpec(dir).shadowsEnabled());
  }
  @Test public void rejectsShadowsAsArray() throws Exception {
    File dir = pack("shadowsArray", "{\"id\":\"x\",\"shadows\":[]}");
    assertThrows(PackLoader.PackException.class, () -> PackLoader.loadSpec(dir));
  }
  @Test public void rejectsNonBooleanEnabled() throws Exception {
    File dir = pack("shadowsString", "{\"id\":\"x\",\"shadows\":{\"enabled\":\"yes\"}}");
    assertThrows(PackLoader.PackException.class, () -> PackLoader.loadSpec(dir));
  }
  @Test public void legacySpecHasEmptyPassesAndCategories() throws Exception {
    File dir = pack("legacy", "{\"id\":\"legacy\",\"shadowSize\":2048}");
    PipelineSpec s = PackLoader.loadSpec(dir);
    assertTrue(s.passes().isLegacy());
    assertTrue(s.categories().isEmpty());
  }
  @Test public void graphSpecLoads() throws Exception {
    File dir = pack("graphpack", "{\"id\":\"g\",\"shadowSize\":2048,"
      + "\"passes\":[{\"name\":\"bright\",\"frag\":\"bright.fsh\",\"size\":0.25},"
      + "{\"name\":\"final\",\"frag\":\"composite.fsh\",\"in\":[\"main\",\"bright\"]}]}");
    new File(new File(dir, "shaders"), "bright.fsh").createNewFile();
    PipelineSpec s = PackLoader.loadSpec(dir);
    assertEquals(2, s.passes().passes().size());
    assertEquals("bright", s.passes().passes().get(0).name());
  }
  @Test public void rejectsNonStringCategory() throws Exception {
    File dir = pack("badcat", "{\"id\":\"x\",\"settings\":[{\"id\":\"s\",\"label\":\"S\",\"type\":\"float\",\"default\":1,\"min\":0,\"max\":2,\"step\":0.1,\"category\":42}]}");
    PackLoader.PackException e = assertThrows(PackLoader.PackException.class, () -> PackLoader.loadSpec(dir));
    assertTrue(e.getMessage().contains("category"), "named error, got: " + e.getMessage());
  }
  @Test public void ignoresActuallyUnknownField() throws Exception {
    File dir = pack("futurefield", "{\"id\":\"x\",\"shadowSize\":2048,\"futureField\":[\"shadow\",\"world\",\"composite\"]}");
    assertEquals("x", PackLoader.loadSpec(dir).id());
  }
  @Test public void rejectsMalformedPasses() throws Exception {
    File dir = pack("badpasses", "{\"id\":\"x\",\"shadowSize\":2048,\"passes\":[\"shadow\",\"world\",\"composite\"]}");
    assertThrows(PackLoader.PackException.class, () -> PackLoader.loadSpec(dir));
  }
  @Test public void detectsPackOwnedShadowShaders() throws Exception {
    File dir = pack("irispack", "{\"id\":\"iris\",\"shadowSize\":2048,\"api\":1}");
    new File(new File(dir, "shaders"), "shadow.vsh").createNewFile();
    new File(new File(dir, "shaders"), "shadow.fsh").createNewFile();
    new File(new File(dir, "shaders"), "terrain.vsh").createNewFile();
    new File(new File(dir, "shaders"), "terrain.fsh").createNewFile();
    PipelineSpec s = PackLoader.loadSpec(dir);
    assertTrue(s.hasShadowShaders());
    assertTrue(s.hasTerrainShaders());
    assertEquals(1, s.api());
  }
  @Test public void rejectsShadowShadersWithoutApi() throws Exception {
    File dir = pack("noapi", "{\"id\":\"noapi\",\"shadowSize\":2048}");
    new File(new File(dir, "shaders"), "shadow.vsh").createNewFile();
    new File(new File(dir, "shaders"), "shadow.fsh").createNewFile();
    new File(new File(dir, "shaders"), "terrain.vsh").createNewFile();
    new File(new File(dir, "shaders"), "terrain.fsh").createNewFile();
    PackLoader.PackException e = assertThrows(PackLoader.PackException.class, () -> PackLoader.loadSpec(dir));
    assertTrue(e.getMessage().contains("api"), "named error, got: " + e.getMessage());
  }
  @Test public void rejectsShadowShadersWithWrongApi() throws Exception {
    File dir = pack("badapi", "{\"id\":\"badapi\",\"shadowSize\":2048,\"api\":99}");
    new File(new File(dir, "shaders"), "shadow.vsh").createNewFile();
    new File(new File(dir, "shaders"), "shadow.fsh").createNewFile();
    new File(new File(dir, "shaders"), "terrain.vsh").createNewFile();
    new File(new File(dir, "shaders"), "terrain.fsh").createNewFile();
    assertThrows(PackLoader.PackException.class, () -> PackLoader.loadSpec(dir));
  }
  @Test public void compositeOnlyPackStillValidates() throws Exception {
    File dir = pack("plainpack", "{\"id\":\"plain\",\"shadowSize\":2048}");
    PipelineSpec s = PackLoader.loadSpec(dir);
    assertFalse(s.hasShadowShaders());
    assertFalse(s.hasTerrainShaders());
  }
}
