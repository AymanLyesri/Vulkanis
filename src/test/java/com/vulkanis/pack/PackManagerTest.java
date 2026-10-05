package com.vulkanis.pack;
import org.junit.jupiter.api.Test;
import java.io.File;
import java.nio.file.Files;
import static org.junit.jupiter.api.Assertions.*;
public class PackManagerTest {
  static File pack(String name, String json, boolean withShader) throws Exception {
    File dir = new File("build/tmp/mgr-" + name);
    dir.mkdirs();
    if (withShader) {
      new File(dir, "shaders").mkdirs();
      new File(new File(dir, "shaders"), "composite.vsh").createNewFile();
      new File(new File(dir, "shaders"), "composite.fsh").createNewFile();
    }
    Files.writeString(new File(dir, "pipeline.json").toPath(), json);
    return dir;
  }
  @Test public void keepsLastGoodOnCorruptSelect() throws Exception {
    PackManager m = new PackManager();
    m.trySelect(pack("good", "{\"id\":\"my-basic\",\"shadowSize\":2048}", true));
    assertEquals("my-basic", m.active().id());
    m.trySelect(pack("bad", "{\"id\":\"x\",}", true));
    assertEquals("my-basic", m.active().id(), "corrupt select must keep last good");
    assertTrue(m.lastError().contains("x") || !m.lastError().isEmpty(), "error recorded for UI/toast");
  }
}
