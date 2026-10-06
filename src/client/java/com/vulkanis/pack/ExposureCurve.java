package com.vulkanis.pack;
public final class ExposureCurve {
  private ExposureCurve() {}
  public static double apply(double avgLuma, double key, double min, double max) {
    return apply(avgLuma, key, min, max, 1.0);
  }
  public static double apply(double avgLuma, double key, double min, double max, double bias) {
    if (!(min <= max)) throw new IllegalArgumentException("exposure min > max");
    double clamped = Math.min(Math.max(avgLuma, 1e-3), Double.MAX_VALUE);
    if (Double.isNaN(clamped)) clamped = 1e-3;
    return Math.min(max, Math.max(min, key / clamped * bias));
  }
  public static double blend(double prev, double cur, double rateUp, double rateDown) {
    double rate = cur > prev ? rateUp : rateDown;
    return prev + (cur - prev) * rate;
  }
}
