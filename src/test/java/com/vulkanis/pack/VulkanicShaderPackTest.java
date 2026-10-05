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
}
