package com.vulkanis.pack;
import java.util.*;
public final class ShaderLibrary {
  private final Map<String, String> shaders = new LinkedHashMap<>();
  public void put(String key, String glsl) { shaders.put(key, glsl); }
  public void remove(String key) { shaders.remove(key); }
  public String get(String key) { return shaders.get(key); }
  public boolean has(String key) { return shaders.containsKey(key); }
  public Set<String> keys() { return Collections.unmodifiableSet(shaders.keySet()); }
  public static String key(String namespace, String path, String kind) {
    return namespace + ":" + path + ":" + kind;
  }
}
