package com.vulkanis.pack;
import org.junit.jupiter.api.Test;
import java.io.File;
import static org.junit.jupiter.api.Assertions.*;
public class PackLoaderTest {
  @Test public void rejectsMissingCompositeShaders() throws Exception {
    File dir = new File("build/tmp/badpack");
    dir.mkdirs();
    new java.io.FileWriter(new File(dir, "pipeline.json")).close();
    assertThrows(PackLoader.PackException.class, () -> PackLoader.loadSpec(dir));
  }
  @Test public void acceptsMinimalPack() throws Exception {
    File dir = new File("build/tmp/goodpack");
    dir.mkdirs();
    new File(dir, "shaders").mkdirs();
    new File(new File(dir, "shaders"), "composite.vsh").createNewFile();
    new File(new File(dir, "shaders"), "composite.fsh").createNewFile();
    try (var w = new java.io.FileWriter(new File(dir, "pipeline.json"))) {
      w.write("{\"id\":\"my-basic\",\"name\":\"Basic\",\"version\":\"1\",\"shadowSize\":2048}");
    }
    PipelineSpec s = PackLoader.loadSpec(dir);
    assertEquals("my-basic", s.id());
  }
  @Test public void acceptsPackWithCompositeOnly() throws Exception {
    File dir = new File("build/tmp/compositepack");
    dir.mkdirs();
    new File(dir, "shaders").mkdirs();
    new File(new File(dir, "shaders"), "composite.vsh").createNewFile();
    new File(new File(dir, "shaders"), "composite.fsh").createNewFile();
    try (var w = new java.io.FileWriter(new File(dir, "pipeline.json"))) {
      w.write("{\"id\":\"composite-only\",\"name\":\"C\",\"version\":\"1\",\"shadowSize\":2048}");
    }
    assertEquals("composite-only", PackLoader.loadSpec(dir).id());
  }
  @Test public void rejectsMissingComposite() throws Exception {
    File dir = new File("build/tmp/nocomposite");
    dir.mkdirs();
    new File(dir, "shaders").mkdirs();
    try (var w = new java.io.FileWriter(new File(dir, "pipeline.json"))) {
      w.write("{\"id\":\"x\",\"name\":\"X\",\"version\":\"1\",\"shadowSize\":2048}");
    }
    assertThrows(PackLoader.PackException.class, () -> PackLoader.loadSpec(dir));
  }
}
