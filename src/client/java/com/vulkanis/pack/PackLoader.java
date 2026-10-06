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
    parseSettings(packDir);
    return new PipelineSpec(id, optString(o, "name", ""), optString(o, "version", ""), shadow, shadowsEnabled,
        hasShadow, hasTerrain, parsePasses(packDir), parseCategories(packDir));
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
      String category = "";
      if (obj.has("category")) {
        JsonElement ce = obj.get("category");
        if (!ce.isJsonPrimitive() || !ce.getAsJsonPrimitive().isString())
          throw new PackException("bad setting category (want string)");
        category = ce.getAsString();
      }
      out.add(new PackSetting(id, optString(obj, "label", ""),
        type, optDouble(obj, "default", 0), optDouble(obj, "min", 0),
        optDouble(obj, "max", 1), optDouble(obj, "step", 1), category));
    }
    return out;
  }
  public static List<PackCategories.Category> parseCategories(File packDir) throws PackException {
    JsonObject o = parsePipeline(packDir);
    if (!o.has("categories")) return List.of();
    JsonElement el = o.get("categories");
    if (!el.isJsonArray()) throw new PackException("bad categories array");
    List<PackCategories.Category> out = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (JsonElement e : el.getAsJsonArray()) {
      if (!e.isJsonObject()) throw new PackException("bad category entry");
      JsonObject obj = e.getAsJsonObject();
      String id = optString(obj, "id", "").toLowerCase(java.util.Locale.ROOT);
      if (id.isBlank()) throw new PackException("category missing id");
      if (!seen.add(id)) throw new PackException("duplicate category id: " + id);
      String label = optString(obj, "label", "");
      if (label.isBlank()) label = PackCategories.cap(id);
      out.add(new PackCategories.Category(id, label));
    }
    return out;
  }
  public static PassGraph parsePasses(File packDir) throws PackException {
    JsonObject o = parsePipeline(packDir);
    if (!o.has("passes")) return new PassGraph(List.of());
    JsonElement el = o.get("passes");
    if (!el.isJsonArray()) throw new PackException("bad passes array");
    JsonArray arr = el.getAsJsonArray();
    if (arr.size() > 8) throw new PackException("too many passes (max 8)");
    List<PassGraph.Pass> out = new ArrayList<>();
    Set<String> names = new HashSet<>();
    Set<String> known = new HashSet<>(Set.of("main", "depth"));
    Set<String> transientOuts = new HashSet<>();
    int historyCount = 0;
    for (JsonElement e : arr) {
      if (!e.isJsonObject()) throw new PackException("bad pass entry");
      JsonObject obj = e.getAsJsonObject();
      String name = optString(obj, "name", "").toLowerCase(java.util.Locale.ROOT);
      if (name.isBlank()) throw new PackException("pass missing name");
      if (!name.matches("[a-z0-9_]+")) throw new PackException("bad pass name: " + name);
      if (name.equals("main") || name.equals("depth") || name.equals("composite"))
        throw new PackException("reserved pass name: " + name);
      if (!names.add(name)) throw new PackException("duplicate pass name: " + name);
      boolean history = false;
      if (obj.has("history")) {
        JsonElement he = obj.get("history");
        if (!he.isJsonPrimitive() || !he.getAsJsonPrimitive().isBoolean())
          throw new PackException("bad pass history (want boolean): " + name);
        history = he.getAsBoolean();
      }
      if (history) {
        if (name.equals("final")) throw new PackException("history pass must not be final: " + name);
        historyCount++;
        if (historyCount > 2) throw new PackException("at most 2 history passes allowed, got: " + name);
        known.add(name);
      }
      String frag = optString(obj, "frag", "");
      if (frag.isBlank() || frag.contains("/") || frag.contains("\\"))
        throw new PackException("bad pass frag: " + name);
      if (!new File(new File(packDir, "shaders"), frag).exists())
        throw new PackException("missing pass shader: " + frag);
      List<String> ins = new ArrayList<>();
      if (obj.has("in")) {
        JsonElement inEl = obj.get("in");
        if (!inEl.isJsonArray()) throw new PackException("bad pass in (want array): " + name);
        for (JsonElement ie : inEl.getAsJsonArray()) {
          String dep;
          try { dep = ie.getAsString().toLowerCase(java.util.Locale.ROOT); }
          catch (Exception ex) { throw new PackException("bad pass in entry: " + name); }
          if (!known.contains(dep)) throw new PackException("unknown pass input '" + dep + "' in: " + name);
          ins.add(dep);
        }
      } else {
        ins.add("main");
      }
      double size = optDouble(obj, "size", 1.0);
      if (!(size > 0.0) || size > 1.0) throw new PackException("bad pass size (want 0<size<=1): " + name);
      out.add(new PassGraph.Pass(name, frag, List.copyOf(ins), size, history));
      known.add(name);
      if (!name.equals("final")) transientOuts.add(name);
    }
    if (transientOuts.size() > 4) throw new PackException("too many pass targets (max 4)");
    if (!out.isEmpty() && !out.get(out.size() - 1).name().equals("final"))
      throw new PackException("last pass must be final");
    return new PassGraph(out);
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
