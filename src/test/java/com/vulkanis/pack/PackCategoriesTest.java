package com.vulkanis.pack;
import org.junit.jupiter.api.Test;
import java.io.File;
import static org.junit.jupiter.api.Assertions.*;
public class PackCategoriesTest {
  @Test public void emptyWhenAbsent() throws Exception {
    File dir = PackSettingsTest.pack("cata", "[]");
    assertTrue(PackLoader.parseCategories(dir).isEmpty());
  }
  @Test public void explicitOrderThenLeftovers() {
    var settings = java.util.List.of(
      new PackSetting("a", "A", "float", 1, 0, 2, 0.1, "shadow"),
      new PackSetting("b", "B", "float", 1, 0, 2, 0.1, "bloom"),
      new PackSetting("c", "C", "float", 1, 0, 2, 0.1, "custom"));
    var explicit = java.util.List.of(new PackCategories.Category("bloom", "Bloom"));
    var order = PackCategories.groupOrder(settings, explicit);
    assertEquals(java.util.List.of("bloom", "shadow", "custom"),
      order.stream().map(PackCategories.Category::id).toList());
  }
  @Test public void duplicateIdsRejected() throws Exception {
    File dir = PackSettingsTest.pack("catb", "[]");
    java.nio.file.Files.writeString(new File(dir, "pipeline.json").toPath(),
      "{\"id\":\"catb\",\"categories\":[{\"id\":\"x\"},{\"id\":\"x\"}]}");
    assertThrows(PackLoader.PackException.class, () -> PackLoader.parseCategories(dir));
  }
}
