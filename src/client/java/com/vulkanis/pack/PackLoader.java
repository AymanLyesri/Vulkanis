package com.vulkanis.pack;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
public final class PackLoader {
  public static final class PackException extends Exception { public PackException(String m){super(m);} }
  private PackLoader(){}
  public static PipelineSpec loadSpec(File packDir) throws PackException {
    JsonObject o = parsePipeline(packDir);
    String id = optString(o, "id", "");
    if (id.isEmpty()) throw new PackException("missing id");
    if (!new File(packDir, "shaders/composite.fsh").exists()
        && !new File(packDir, "shaders/composite.frag").exists()) throw new PackException("missing shaders/composite.fsh");
    if (!new File(packDir, "shaders/composite.vsh").exists()
        && !new File(packDir, "shaders/composite.vert").exists()) throw new PackException("missing shaders/composite.vsh");
    int shadow = optInt(o, "shadowSize", 2048);
    if (shadow != 1024 && shadow != 2048 && shadow != 4096) throw new PackException("bad shadowSize (want 1024/2048/4096)");
    boolean shadowsEnabled = true;
    if (o.has("shadows")) {
      JsonElement shadowsEl = o.get("shadows");
      if (!shadowsEl.isJsonObject()) throw new PackException("bad shadows block");
      JsonObject shadows = shadowsEl.getAsJsonObject();
      if (shadows.has("enabled")) {
        JsonElement enabled = shadows.get("enabled");
        if (!enabled.isJsonPrimitive() || !enabled.getAsJsonPrimitive().isBoolean())
          throw new PackException("bad shadows.enabled (want boolean)");
        shadowsEnabled = enabled.getAsBoolean();
      }
    }
    boolean hasShadow = new File(packDir, "shaders/shadow.vsh").exists()
        && new File(packDir, "shaders/shadow.fsh").exists();
    boolean hasTerrain = new File(packDir, "shaders/terrain.vsh").exists()
        && new File(packDir, "shaders/terrain.fsh").exists();
    return new PipelineSpec(id, optString(o, "name", ""), optString(o, "version", ""), shadow, shadowsEnabled,
        hasShadow, hasTerrain);
  }
  public static List<PipelineSpec> listPacks(File dirs) {
    List<PipelineSpec> out = new ArrayList<>();
    File[] kids = dirs.listFiles(File::isDirectory);
    if (kids == null) return out;
    for (File k : kids) { try { out.add(loadSpec(k)); } catch (PackException ignored) {} }
    return out;
  }
  public static List<PackSetting> parseSettings(File packDir) throws PackException {
    JsonObject o = parsePipeline(packDir);
    if (!o.has("settings")) return List.of();
    JsonElement el = o.get("settings");
    if (!el.isJsonArray()) throw new PackException("bad settings array");
    JsonArray arr = el.getAsJsonArray();
    List<PackSetting> out = new ArrayList<>();
    for (JsonElement e : arr) {
      if (!e.isJsonObject()) throw new PackException("bad setting entry");
      JsonObject obj = e.getAsJsonObject();
      String id = optString(obj, "id", "");
      if (id.isEmpty()) throw new PackException("setting missing id");
      String type = optString(obj, "type", "");
      if (!type.equals("bool") && !type.equals("int") && !type.equals("float"))
        throw new PackException("bad setting type: " + type);
      out.add(new PackSetting(id, optString(obj, "label", ""),
        type, optDouble(obj, "default", 0), optDouble(obj, "min", 0),
        optDouble(obj, "max", 1), optDouble(obj, "step", 1)));
    }
    return out;
  }
  private static JsonObject parsePipeline(File packDir) throws PackException {
    final String json;
    try {
      json = Files.readString(new File(packDir, "pipeline.json").toPath());
    } catch (Exception e) { throw new PackException("unreadable pipeline.json: " + e.getMessage()); }
    String trimmed = json.trim();
    if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) throw new PackException("pipeline.json is not a JSON object");
    if (trimmed.matches("(?s).*?,\\s*[}\\]].*")) throw new PackException("pipeline.json has trailing comma");
    try {
      JsonElement el = JsonParser.parseString(json);
      if (!el.isJsonObject()) throw new PackException("pipeline.json is not a JSON object");
      return el.getAsJsonObject();
    } catch (JsonSyntaxException | IllegalStateException e) {
      throw new PackException("bad pipeline.json: " + e.getMessage());
    }
  }
  private static String optString(JsonObject o, String key, String def) {
    JsonElement e = o.get(key);
    if (e == null || e.isJsonNull()) return def;
    try { return e.getAsString(); } catch (Exception ex) { return def; }
  }
  private static int optInt(JsonObject o, String key, int def) {
    JsonElement e = o.get(key);
    if (e == null || e.isJsonNull()) return def;
    try { return e.getAsInt(); } catch (Exception ex) { return def; }
  }
  private static double optDouble(JsonObject o, String key, double def) {
    JsonElement e = o.get(key);
    if (e == null || e.isJsonNull()) return def;
    try { return e.getAsDouble(); } catch (Exception ex) { return def; }
  }
}
