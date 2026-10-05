package com.vulkanis.pack;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
public final class PackValues {
  private final Map<String, Double> values = new LinkedHashMap<>();
  public void set(String id, double v) { values.put(id, v); }
  public double get(PackSetting s) { return values.getOrDefault(s.id(), s.def()); }
  public Set<String> ids() { return Collections.unmodifiableSet(values.keySet()); }
  public String apply(String glsl, List<PackSetting> settings) {
    String out = glsl;
    for (PackSetting s : settings) {
      double v = get(s);
      String rep = s.isInt() ? Integer.toString((int) Math.round(v)) : Double.toString(v);
      out = out.replace("{{" + s.id() + "}}", rep);
    }
    return out;
  }
  public void save(File f) throws Exception {
    f.getParentFile().mkdirs();
    StringBuilder b = new StringBuilder("{");
    boolean first = true;
    for (var e : values.entrySet()) {
      if (!first) b.append(",");
      first = false;
      b.append("\"").append(e.getKey()).append("\":").append(e.getValue());
    }
    b.append("}");
    Files.writeString(f.toPath(), b.toString());
  }
  public static PackValues load(File f, List<PackSetting> settings) throws Exception {
    PackValues v = new PackValues();
    for (PackSetting s : settings) v.set(s.id(), s.def());
    if (!f.exists()) return v;
    String json = Files.readString(f.toPath());
    for (PackSetting s : settings) {
      String q = "\"" + s.id() + "\"";
      int i = json.indexOf(q);
      if (i < 0) continue;
      int c = json.indexOf(':', i) + 1;
      int e = c;
      while (e < json.length() && "-+0123456789.eE".indexOf(json.charAt(e)) >= 0) e++;
      try { v.set(s.id(), s.clamp(Double.parseDouble(json.substring(c, e)))); } catch (Exception ignored) { }
    }
    return v;
  }
}
