package com.vulkanis;

import java.util.Locale;

/** Detects which GPU backend (Vulkan vs OpenGL) the game is running on.
 *  Vulkanis's shadows target Vulkan; after a crash Minecraft can silently
 *  fall back to OpenGL, which wastes debugging time. Class names are
 *  compared instead of instanceof so no backend class is linked. */
public final class BackendCheck {
  public enum Backend {
    VULKAN,
    OPENGL,
    UNKNOWN
  }

  private static volatile Backend cached;
  private static volatile String lastDetail = "?";

  private BackendCheck() {
  }

  /** Cached device classification, or null when the render device is not ready yet.
   *  UNKNOWN is never latched: a later call may resolve it once the device is ready. */
  public static Backend current() {
    Backend backend = cached;
    if (backend != null && backend != Backend.UNKNOWN) {
      return backend;
    }
    backend = detect();
    if (backend != null) {
      cached = backend;
    }
    return backend;
  }

  /** Debug detail for logs (device class + backend name). */
  public static String detail() {
    current();
    return lastDetail;
  }

  static Backend detect() {
    try {
      Object device = com.mojang.blaze3d.systems.RenderSystem.getDevice();
      if (device == null) {
        return null;
      }
      String className = device.getClass().getName();
      try {
        String backendName = ((com.mojang.renderpearl.api.device.GpuDevice) device)
          .getDeviceInfo().backendName();
        lastDetail = className + " backend=" + backendName;
        if (backendName != null) {
          Backend byName = classify(backendName);
          if (byName != Backend.UNKNOWN) {
            return byName;
          }
        }
      } catch (Exception ignored) { }
      lastDetail = className;
      return classify(className);
    } catch (Exception e) {
      return null;
    }
  }

  public static Backend classify(String className) {
    if (className == null) {
      return Backend.UNKNOWN;
    }
    String name = className.toLowerCase(Locale.ROOT);
    if (name.contains("vulkan")) {
      return Backend.VULKAN;
    }
    if (name.contains("opengl") || name.contains("gldevice") || name.contains(".gl.")) {
      return Backend.OPENGL;
    }
    return Backend.UNKNOWN;
  }

  static void resetForTests() {
    cached = null;
  }
}
