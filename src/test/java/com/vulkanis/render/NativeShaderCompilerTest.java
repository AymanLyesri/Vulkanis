package com.vulkanis.render;
import org.junit.jupiter.api.Test;
import java.io.File;
import static org.junit.jupiter.api.Assertions.*;
public class NativeShaderCompilerTest {
  @Test public void rejectsBadGlsl() {
    assertThrows(NativeShaderCompiler.CompileException.class, () ->
      NativeShaderCompiler.compile("not glsl !!!", new File("build/tmp/spirv")));
  }
  @Test public void cachesGoodGlsl() throws Exception {
    String src = "#version 450\nvoid main(){}";
    byte[] a = NativeShaderCompiler.compile(src, new File("build/tmp/spirv"));
    byte[] b = NativeShaderCompiler.compile(src, new File("build/tmp/spirv"));
    assertArrayEquals(a, b);
  }
  @Test public void fallsBackToPassthrough() {
    assertTrue(NativeShaderCompiler.fallbackSpirv().length > 0);
  }
}
