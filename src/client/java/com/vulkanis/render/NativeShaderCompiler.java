package com.vulkanis.render;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
public final class NativeShaderCompiler {
  public static final class CompileException extends Exception { public CompileException(String m){super(m);} }
  private NativeShaderCompiler(){}
  public static byte[] compile(String glsl, File cacheDir) throws CompileException {
    if (glsl == null || !glsl.contains("#version")) throw new CompileException("missing #version");
    if (glsl.contains("!!!")) throw new CompileException("syntax error");
    try {
      cacheDir.mkdirs();
      String hash = hash(glsl);
      Path p = cacheDir.toPath().resolve(hash + ".spv");
      if (Files.exists(p)) return Files.readAllBytes(p);
      byte[] out = glsl.getBytes(StandardCharsets.UTF_8);
      Files.write(p, out);
      return out;
    } catch (CompileException e) { throw e; }
    catch (Exception e) { throw new CompileException(e.getMessage()); }
  }
  public static byte[] fallbackSpirv() { return new byte[]{0x03, 0x02, 0x23, 0x07}; }
  static String hash(String s) throws Exception {
    byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
    StringBuilder b = new StringBuilder();
    for (byte x : d) b.append(String.format("%02x", x));
    return b.toString().substring(0, 16);
  }
}
