package com.vulkanis.pack;
import org.junit.jupiter.api.Test;
import java.io.File;
import java.nio.file.Files;
import static org.junit.jupiter.api.Assertions.*;
public class PackSettingsTest {
  static File pack(String name, String settingsJson) throws Exception {
    File dir = new File("build/tmp/set-" + name);
    delete(dir);
    dir.mkdirs();
    new File(dir, "shaders").mkdirs();
    new File(new File(dir, "shaders"), "composite.vsh").createNewFile();
    new File(new File(dir, "shaders"), "composite.fsh").createNewFile();
    Files.writeString(new File(dir, "pipeline.json").toPath(),
      "{\"id\":\"" + name + "\",\"settings\":" + settingsJson + "}");
    return dir;
  }
  @Test public void parsesSettings() throws Exception {
    File dir = pack("a", "[{\"id\":\"shadowStrength\",\"label\":\"Shadow Strength\",\"type\":\"float\",\"default\":0.85,\"min\":0.0,\"max\":1.0,\"step\":0.05}]");
    var settings = PackLoader.parseSettings(dir);
    assertEquals(1, settings.size());
    assertEquals("shadowStrength", settings.get(0).id());
    assertEquals(0.85, settings.get(0).def());
  }
  @Test public void categoryDefaultsToGeneral() throws Exception {
    File dir = pack("cat1", "[{\"id\":\"s\",\"label\":\"S\",\"type\":\"float\",\"default\":1,\"min\":0,\"max\":2,\"step\":0.1}]");
    var settings = PackLoader.parseSettings(dir);
    assertEquals("general", settings.get(0).category());
  }
  @Test public void categoryNormalizes() throws Exception {
    File dir = pack("cat2", "[{\"id\":\"b\",\"label\":\"B\",\"type\":\"float\",\"default\":1,\"min\":0,\"max\":2,\"step\":0.1,\"category\":\"Bloom\"}]");
    var settings = PackLoader.parseSettings(dir);
    assertEquals("bloom", settings.get(0).category());
  }
  @Test public void emptyWhenAbsent() throws Exception {
    File dir = new File("build/tmp/set-empty");
    dir.mkdirs();
    new File(dir, "shaders").mkdirs();
    new File(new File(dir, "shaders"), "composite.vsh").createNewFile();
    new File(new File(dir, "shaders"), "composite.fsh").createNewFile();
    Files.writeString(new File(dir, "pipeline.json").toPath(), "{\"id\":\"empty\"}");
    assertTrue(PackLoader.parseSettings(dir).isEmpty());
  }
  @Test public void valuesRoundTripAndSubstitute() throws Exception {
    File dir = pack("b", "[{\"id\":\"shadowSteps\",\"label\":\"Steps\",\"type\":\"int\",\"default\":24,\"min\":8,\"max\":64,\"step\":4}]");
    var settings = PackLoader.parseSettings(dir);
    PackValues v = PackValues.load(new File(dir, "settings.json"), settings);
    assertEquals(24, v.get(settings.get(0)));
    v.set("shadowSteps", 32);
    v.save(new File(dir, "settings.json"));
    PackValues v2 = PackValues.load(new File(dir, "settings.json"), settings);
    assertEquals(32.0, v2.get(settings.get(0)), 1e-9);
    assertEquals("int s = 32;", v2.apply("int s = {{shadowSteps}};", settings));
  }
  @Test public void substitutionLeavesUnknownTokens() {
    PackValues v = new PackValues();
    assertEquals("a {{nope}} b", v.apply("a {{nope}} b", java.util.List.of()));
  }
  static void delete(File f) {
    if (f.isDirectory()) for (File k : f.listFiles()) delete(k);
    f.delete();
  }
}
