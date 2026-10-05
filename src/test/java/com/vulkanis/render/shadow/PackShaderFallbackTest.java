package com.vulkanis.render.shadow;
import com.vulkanis.pack.ShaderLibrary;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
public class PackShaderFallbackTest {
  @Test public void prefersPackGlslWhenPresent() {
    ShaderLibrary lib = new ShaderLibrary();
    lib.put(ShaderLibrary.key("vulkanis", "pack/shadow", "fragment"), "PACK_FRAG");
    VulkanisShaderSources src = new VulkanisShaderSources(lib);
    assertEquals("PACK_FRAG", src.fragmentFor("vulkanis_shadow"));
  }
  @Test public void noSilentFallbackWhenPackAbsent() {
    VulkanisShaderSources src = new VulkanisShaderSources(new ShaderLibrary());
    assertNull(src.fragmentFor("vulkanis_shadow"));
    assertNull(src.vertexFor("vulkanis_terrain_receiver"));
  }
  @Test public void packErrorDefaultsEmpty() {
    assertTrue(com.vulkanis.render.ShadowHookState.lastPackError().isEmpty());
  }
}
