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
  @Test public void ignoresUnknownPassesField() throws Exception {
    File dir = pack("passesfield", "{\"id\":\"x\",\"shadowSize\":2048,\"passes\":[\"shadow\",\"world\",\"composite\"]}");
    assertEquals("x", PackLoader.loadSpec(dir).id());
  }
  @Test public void detectsPackOwnedShadowShaders() throws Exception {
    File dir = pack("irispack", "{\"id\":\"iris\",\"shadowSize\":2048}");
    new File(new File(dir, "shaders"), "shadow.vsh").createNewFile();
    new File(new File(dir, "shaders"), "shadow.fsh").createNewFile();
    new File(new File(dir, "shaders"), "terrain.vsh").createNewFile();
    new File(new File(dir, "shaders"), "terrain.fsh").createNewFile();
    PipelineSpec s = PackLoader.loadSpec(dir);
    assertTrue(s.hasShadowShaders());
    assertTrue(s.hasTerrainShaders());
  }
  @Test public void compositeOnlyPackStillValidates() throws Exception {
    File dir = pack("plainpack", "{\"id\":\"plain\",\"shadowSize\":2048}");
    PipelineSpec s = PackLoader.loadSpec(dir);
    assertFalse(s.hasShadowShaders());
    assertFalse(s.hasTerrainShaders());
  }
}
