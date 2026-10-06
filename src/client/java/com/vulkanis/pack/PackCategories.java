package com.vulkanis.pack;
import java.util.*;
public final class PackCategories {
  public record Category(String id, String label) {}
  private PackCategories() {}
  public static List<Category> groupOrder(List<PackSetting> settings, List<Category> explicit) {
    List<Category> out = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (Category c : explicit) {
      if (seen.add(c.id())) out.add(c);
    }
    Map<String, String> firstLabel = new LinkedHashMap<>();
    for (PackSetting s : settings) firstLabel.putIfAbsent(s.category(), cap(s.category()));
    for (Category c : explicit) firstLabel.remove(c.id());
    for (var e : firstLabel.entrySet()) {
      if (seen.add(e.getKey())) out.add(new Category(e.getKey(), e.getValue()));
    }
    return out;
  }
  public static String defaultTabId(List<Category> order) {
    return order.isEmpty() ? "general" : order.get(0).id();
  }
  public static List<PackSetting> visibleSettings(List<PackSetting> all, String tab) {
    List<PackSetting> out = new ArrayList<>();
    for (PackSetting s : all) {
      if (s.category().equals(tab)) out.add(s);
    }
    return out;
  }
  static String cap(String id) {
    return id.isEmpty() ? id : Character.toUpperCase(id.charAt(0)) + id.substring(1);
  }
}
