package com.vulkanis.config;
import java.io.File;
import java.nio.file.Files;
public final class ShaderConfig {
  public boolean enabled = true;
  public String selectedPack = "VulkanicShader";
  public int shadowSize = 2048;
  public boolean showShadowmap = false;
  public void save(File f) throws Exception {
    f.getParentFile().mkdirs();
    String json = "{\"enabled\":" + enabled + ",\"selectedPack\":\"" + selectedPack
      + "\",\"shadowSize\":" + shadowSize + ",\"showShadowmap\":" + showShadowmap + "}";
    Files.writeString(f.toPath(), json);
  }
  public static ShaderConfig load(File f) throws Exception {
    ShaderConfig c = new ShaderConfig();
    if (!f.exists()) return c;
    String json = Files.readString(f.toPath());
    if (json.contains("\"enabled\":false")) c.enabled = false;
    if (json.contains("\"showShadowmap\":true")) c.showShadowmap = true;
    String p = field(json, "selectedPack");
    if (!p.isEmpty()) c.selectedPack = p;
    int size = intField(json, "shadowSize");
    if (size == 1024 || size == 2048 || size == 4096) c.shadowSize = size;
    return c;
  }
  static String field(String json, String key) {
    String q = "\"" + key + "\"";
    int i = json.indexOf(q); if (i < 0) return "";
    int c = json.indexOf(':', i); int a = json.indexOf('"', c + 1); int b = json.indexOf('"', a + 1);
    return a < 0 || b < 0 ? "" : json.substring(a + 1, b);
  }
  static int intField(String json, String key) {
    String q = "\"" + key + "\"";
    int i = json.indexOf(q); if (i < 0) return -1;
    int c = json.indexOf(':', i) + 1;
    int e = c;
    while (e < json.length() && (Character.isDigit(json.charAt(e)) || json.charAt(e) == '-')) e++;
    if (e == c) return -1;
    try { return Integer.parseInt(json.substring(c, e)); } catch (NumberFormatException ex) { return -1; }
  }
}
