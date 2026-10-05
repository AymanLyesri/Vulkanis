package com.vulkanis;
import org.junit.jupiter.api.Test;
import java.io.File;
import java.nio.file.Files;
import static org.junit.jupiter.api.Assertions.*;
public class PackagingTest {
  @Test public void sodiumIsOptional() throws Exception {
    String json = Files.readString(new File("src/main/resources/fabric.mod.json").toPath());
    String depends = json.split("\"suggests\"")[0];
    assertFalse(depends.contains("\"sodium\""), "sodium must not be a hard dependency (breaks post-only fallback)");
    assertTrue(json.contains("\"sodium\""), "sodium should still be suggested");
  }
}
