package com.vulkanis.pack;
public record PackSetting(String id, String label, String type, double def, double min, double max, double step) {
  public boolean isBool() { return "bool".equals(type); }
  public boolean isInt() { return "int".equals(type); }
  public double clamp(double v) { return Math.min(max, Math.max(min, v)); }
}
