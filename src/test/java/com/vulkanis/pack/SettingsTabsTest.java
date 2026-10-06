package com.vulkanis.pack;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
public class SettingsTabsTest {
  @Test public void filtersByTab() {
    var all = java.util.List.of(
      new PackSetting("a", "A", "float", 1, 0, 2, 0.1, "shadow"),
      new PackSetting("b", "B", "float", 1, 0, 2, 0.1, "bloom"));
    assertEquals(1, PackCategories.visibleSettings(all, "bloom").size());
    assertEquals("b", PackCategories.visibleSettings(all, "bloom").get(0).id());
  }
  @Test public void defaultIsFirstTab() {
    var order = java.util.List.of(
      new PackCategories.Category("shadow", "Shadow"),
      new PackCategories.Category("bloom", "Bloom"));
    assertEquals("shadow", PackCategories.defaultTabId(order));
    assertEquals("general", PackCategories.defaultTabId(java.util.List.of()));
  }
}
