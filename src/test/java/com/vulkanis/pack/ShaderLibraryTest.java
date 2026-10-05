package com.vulkanis.pack;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
public class ShaderLibraryTest {
  @Test public void roundTrips() {
    ShaderLibrary lib = new ShaderLibrary();
    String k = ShaderLibrary.key("vulkanis", "post/composite", "fragment");
    lib.put(k, "#version 330");
    assertTrue(lib.has(k));
    assertEquals("#version 330", lib.get(k));
    assertFalse(lib.has("other:x:vertex"));
  }
}
