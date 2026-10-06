package com.vulkanis.pack;
import java.util.*;
public final class PassGraph {
  public record Pass(String name, String frag, List<String> in, double size, boolean history) {}
  private final List<Pass> passes;
  public PassGraph(List<Pass> passes) { this.passes = List.copyOf(passes); }
  public List<Pass> passes() { return passes; }
  public boolean isLegacy() { return passes.isEmpty(); }
  public static boolean sameTopology(PassGraph a, PassGraph b) {
    if (a.passes.size() != b.passes.size()) return false;
    for (int i = 0; i < a.passes.size(); i++) {
      Pass x = a.passes.get(i), y = b.passes.get(i);
      if (!x.name().equals(y.name()) || x.history() != y.history() || x.size() != y.size()) return false;
    }
    return true;
  }
}
