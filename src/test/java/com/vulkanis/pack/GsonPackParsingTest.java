package com.vulkanis.pack;
import org.junit.jupiter.api.Test;
import java.io.File;
import java.nio.file.Files;
import static org.junit.jupiter.api.Assertions.*;

public class GsonPackParsingTest {
  static File pack(String name, String json) throws Exception {
    File dir = new File("build/tmp/gson-" + name);
    dir.mkdirs();
    new File(dir, "shaders").mkdirs();
    new File(new File(dir, "shaders"), "composite.vsh").createNewFile();
    new File(new File(dir, "shaders"), "composite.fsh").createNewFile();
    Files.writeString(new File(dir, "pipeline.json").toPath(), json);
    return dir;
  }

  @Test public void handlesEscapedQuotesInName() throws Exception {
    File dir = pack("escaped", "{\"id\":\"x\",\"name\":\"A \\\"cool\\\" pack\",\"version\":\"1\",\"shadowSize\":2048}");
    PipelineSpec s = PackLoader.loadSpec(dir);
    assertEquals("A \"cool\" pack", s.name());
  }

  @Test public void handlesBracketInSettingLabel() throws Exception {
    File dir = pack("bracket",
      "{\"id\":\"y\",\"settings\":[{\"id\":\"s\",\"label\":\"a]b}c\",\"type\":\"bool\",\"default\":1,\"min\":0,\"max\":1,\"step\":1}]}");
    var settings = PackLoader.parseSettings(dir);
    assertEquals(1, settings.size());
    assertEquals("a]b}c", settings.get(0).label());
  }

  @Test public void handlesPrettyPrintedJson() throws Exception {
    File dir = pack("pretty", "{\n  \"id\" : \"pretty-pack\",\n  \"name\" : \"Pretty\",\n  \"version\" : \"1\",\n  \"shadowSize\" : 1024\n}");
    PipelineSpec s = PackLoader.loadSpec(dir);
    assertEquals("pretty-pack", s.id());
    assertEquals(1024, s.shadowSize());
  }
}
