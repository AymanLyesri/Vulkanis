package com.vulkanis.pack;
import org.junit.jupiter.api.Test;
import java.io.File;
import static org.junit.jupiter.api.Assertions.*;
public class PassGraphTest {
  @Test public void legacyWhenAbsent() throws Exception {
    File dir = PackSettingsTest.pack("p0", "[]");
    assertTrue(PackLoader.parsePasses(dir).isLegacy());
  }
  @Test public void threePassBloomResolves() throws Exception {
    File dir = PackSettingsTest.pack("p1", "[]");
    var shaders = new File(dir, "shaders");
    for (String f : new String[]{"bright.fsh", "blur.fsh", "composite.fsh", "composite.vsh"})
      new File(shaders, f).createNewFile();
    java.nio.file.Files.writeString(new File(dir, "pipeline.json").toPath(), "{\"id\":\"p1\",\"settings\":[],"
      + "\"passes\":[{\"name\":\"bright\",\"frag\":\"bright.fsh\",\"size\":0.25},"
      + "{\"name\":\"blur\",\"frag\":\"blur.fsh\",\"in\":[\"bright\"],\"size\":0.25},"
      + "{\"name\":\"final\",\"frag\":\"composite.fsh\",\"in\":[\"main\",\"blur\"]}]}");
    var g = PackLoader.parsePasses(dir);
    assertEquals(3, g.passes().size());
    assertEquals(java.util.List.of("bright"), g.passes().get(1).in());
  }
  @Test public void unknownInputRejected() throws Exception {
    File dir = PackSettingsTest.pack("p2", "[]");
    var shaders = new File(dir, "shaders");
    new File(shaders, "x.fsh").createNewFile();
    new File(shaders, "composite.vsh").createNewFile();
    java.nio.file.Files.writeString(new File(dir, "pipeline.json").toPath(), "{\"id\":\"p2\",\"settings\":[],"
      + "\"passes\":[{\"name\":\"x\",\"frag\":\"x.fsh\",\"in\":[\"nope\"]}]}");
    PackLoader.PackException e = assertThrows(PackLoader.PackException.class, () -> PackLoader.parsePasses(dir));
    assertTrue(e.getMessage().contains("nope"), "named input in error, got: " + e.getMessage());
  }
  @Test public void tooManyPassesRejected() throws Exception {
    File dir = PackSettingsTest.pack("p3", "[]");
    var shaders = new File(dir, "shaders");
    StringBuilder b = new StringBuilder("{\"id\":\"p3\",\"settings\":[],\"passes\":[");
    for (int i = 0; i < 9; i++) {
      if (i > 0) b.append(",");
      new File(shaders, "f" + i + ".fsh").createNewFile();
      b.append("{\"name\":\"f").append(i).append("\",\"frag\":\"f").append(i).append(".fsh\"}");
    }
    b.append("]}");
    java.nio.file.Files.writeString(new File(dir, "pipeline.json").toPath(), b.toString());
    assertThrows(PackLoader.PackException.class, () -> PackLoader.parsePasses(dir));
  }
  @Test public void finalMustBeLast() throws Exception {
    File dir = PackSettingsTest.pack("p4", "[]");
    var shaders = new File(dir, "shaders");
    new File(shaders, "x.fsh").createNewFile();
    java.nio.file.Files.writeString(new File(dir, "pipeline.json").toPath(), "{\"id\":\"p4\",\"settings\":[],"
      + "\"passes\":[{\"name\":\"x\",\"frag\":\"x.fsh\"}]}");
    assertThrows(PackLoader.PackException.class, () -> PackLoader.parsePasses(dir));
  }
  @Test public void historySelfReferenceAllowed() throws Exception {
    File dir = PackSettingsTest.pack("h1", "[]");
    var shaders = new File(dir, "shaders");
    new File(shaders, "m.fsh").createNewFile();
    java.nio.file.Files.writeString(new File(dir, "pipeline.json").toPath(), "{\"id\":\"h1\",\"settings\":[],"
      + "\"passes\":[{\"name\":\"m\",\"frag\":\"m.fsh\",\"history\":true},"
      + "{\"name\":\"final\",\"frag\":\"composite.fsh\",\"in\":[\"main\",\"m\"]}]}");
    var g = PackLoader.parsePasses(dir);
    assertTrue(g.passes().get(0).history());
  }
  @Test public void twoHistoryPassesAllowed() throws Exception {
    File dir = PackSettingsTest.pack("h5", "[]");
    var shaders = new File(dir, "shaders");
    new File(shaders, "a.fsh").createNewFile();
    new File(shaders, "b.fsh").createNewFile();
    java.nio.file.Files.writeString(new File(dir, "pipeline.json").toPath(), "{\"id\":\"h5\",\"settings\":[],"
      + "\"passes\":[{\"name\":\"a\",\"frag\":\"a.fsh\",\"history\":true},"
      + "{\"name\":\"b\",\"frag\":\"b.fsh\",\"in\":[\"a\"],\"history\":true},"
      + "{\"name\":\"final\",\"frag\":\"composite.fsh\",\"in\":[\"main\",\"b\"]}]}");
    var g = PackLoader.parsePasses(dir);
    assertEquals(3, g.passes().size());
    assertTrue(g.passes().get(1).history());
  }
  @Test public void threeHistoryPassesRejected() throws Exception {
    File dir = PackSettingsTest.pack("h6", "[]");
    var shaders = new File(dir, "shaders");
    new File(shaders, "a.fsh").createNewFile();
    new File(shaders, "b.fsh").createNewFile();
    new File(shaders, "c.fsh").createNewFile();
    java.nio.file.Files.writeString(new File(dir, "pipeline.json").toPath(), "{\"id\":\"h6\",\"settings\":[],"
      + "\"passes\":[{\"name\":\"a\",\"frag\":\"a.fsh\",\"history\":true},"
      + "{\"name\":\"b\",\"frag\":\"b.fsh\",\"in\":[\"a\"],\"history\":true},"
      + "{\"name\":\"c\",\"frag\":\"c.fsh\",\"in\":[\"b\"],\"history\":true},"
      + "{\"name\":\"final\",\"frag\":\"composite.fsh\",\"in\":[\"main\"]}]}");
    PackLoader.PackException e = assertThrows(PackLoader.PackException.class, () -> PackLoader.parsePasses(dir));
    assertTrue(e.getMessage().contains("history"), "named error, got: " + e.getMessage());
  }
  @Test public void selfReferenceRejectedWhenNotHistory() throws Exception {
    File dir = PackSettingsTest.pack("h3", "[]");
    var shaders = new File(dir, "shaders");
    new File(shaders, "x.fsh").createNewFile();
    java.nio.file.Files.writeString(new File(dir, "pipeline.json").toPath(), "{\"id\":\"h3\",\"settings\":[],"
      + "\"passes\":[{\"name\":\"x\",\"frag\":\"x.fsh\",\"in\":[\"x\"]},"
      + "{\"name\":\"final\",\"frag\":\"composite.fsh\",\"in\":[\"main\"]}]}");
    assertThrows(PackLoader.PackException.class, () -> PackLoader.parsePasses(dir));
  }
  @Test public void historyFinalRejected() throws Exception {
    File dir = PackSettingsTest.pack("h4", "[]");
    var shaders = new File(dir, "shaders");
    new File(shaders, "c.fsh").createNewFile();
    java.nio.file.Files.writeString(new File(dir, "pipeline.json").toPath(), "{\"id\":\"h4\",\"settings\":[],"
      + "\"passes\":[{\"name\":\"final\",\"frag\":\"c.fsh\",\"history\":true}]}");
    assertThrows(PackLoader.PackException.class, () -> PackLoader.parsePasses(dir));
  }
  static PassGraph graphM() {
    return new PassGraph(java.util.List.of(
      new PassGraph.Pass("m", "m.fsh", java.util.List.of("main", "m"), 1.0, true),
      new PassGraph.Pass("final", "c.fsh", java.util.List.of("main", "m"), 1.0, false)));
  }
  @Test public void sameTopologyMatches() {
    assertTrue(PassGraph.sameTopology(graphM(), graphM()));
    assertTrue(PassGraph.sameTopology(new PassGraph(java.util.List.of()), new PassGraph(java.util.List.of())));
    var other = new PassGraph(java.util.List.of(
      new PassGraph.Pass("m", "m.fsh", java.util.List.of("main", "m"), 0.5, true),
      new PassGraph.Pass("final", "c.fsh", java.util.List.of("main", "m"), 1.0, false)));
    assertFalse(PassGraph.sameTopology(graphM(), other));
    var nohist = new PassGraph(java.util.List.of(
      new PassGraph.Pass("m", "m.fsh", java.util.List.of("main"), 1.0, false),
      new PassGraph.Pass("final", "c.fsh", java.util.List.of("main", "m"), 1.0, false)));
    assertFalse(PassGraph.sameTopology(graphM(), nohist));
  }
}
