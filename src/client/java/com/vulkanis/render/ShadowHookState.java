package com.vulkanis.render;
public final class ShadowHookState {
  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("vulkanis");
  private static volatile boolean sulkanWarned;
  private static volatile FrameGraph.Mode mode = FrameGraph.Mode.POST_ONLY;
  private static volatile float[] shadowMatrix = new float[16];
  private static volatile com.vulkanis.pack.PipelineSpec activeSpec;
  public static void setActiveSpec(com.vulkanis.pack.PipelineSpec spec) { activeSpec = spec; }
  public static com.vulkanis.pack.PipelineSpec activeSpec() { return activeSpec; }
  public static boolean shadowsActive() {
    if (sulkanPresent()) {
      if (!sulkanWarned) {
        sulkanWarned = true;
        LOG.error("vulkanis: Sulkan is installed and owns the terrain shadow path; "
          + "disable or remove the Sulkan mod to use Vulkanis shadows (two shadow systems cannot run together)");
      }
      return false;
    }
    return com.vulkanis.pack.PackManager.shadowsActive(compositeEnabled(), activeSpec);
  }

  private static volatile Boolean sulkanCached;

  private static boolean sulkanPresent() {
    Boolean cached = sulkanCached;
    if (cached != null) {
      return cached;
    }
    boolean present;
    try {
      present = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sulkan");
    } catch (Throwable ignored) {
      present = false;
    }
    sulkanCached = present;
    return present;
  }  private static volatile boolean compositeReady = true;
  private static volatile boolean compositeEnabled = true;
  private static volatile long casterSubmits;
  private ShadowHookState(){}
  public static void setMode(FrameGraph.Mode m) { mode = m; }
  public static FrameGraph.Mode mode() { return mode; }
  public static boolean shadowPass() { return mode == FrameGraph.Mode.SHADOW_WORLD_COMPOSITE; }
  public static void setShadowMatrix(float[] m) { shadowMatrix = m.clone(); }
  public static float[] shadowMatrix() { return shadowMatrix.clone(); }
  public static void noteCasterSubmit() { casterSubmits++; }
  public static long casterSubmits() { return casterSubmits; }
  public static void noteReceiverBind() { }
  public static void setCompositeReady(boolean ready) { compositeReady = ready; }
  public static boolean compositeReady() { return compositeReady; }
  public static void setCompositeEnabled(boolean enabled) {
    compositeEnabled = enabled;
    if (enabled) {
      compositeReady = true;
      compileReset.run();
    }
  }
  public static void setCompileReset(Runnable r) { compileReset = r; }
  private static volatile Runnable compileReset = () -> { };
  public static boolean compositeEnabled() { return compositeEnabled; }
  public static void setShowDepth(boolean show) { showDepth = show; if (!show) debugCascade = -1; }
  public static boolean showDepth() { return showDepth; }
  private static volatile boolean showDepth;
  /** -1 = main depth, 0-2 = cascade shadow map. Only meaningful when showDepth. */
  public static void setDebugCascade(int cascade) { debugCascade = cascade; }
  public static int debugCascade() { return debugCascade; }
  private static volatile int debugCascade = -1;
  private static volatile float[] sunDir = {0.0f, 1.0f, 0.0f};
  private static volatile float[] camPos = {0.0f, 64.0f, 0.0f};
  private static volatile float[] viewProj = identity();
  private static volatile float[] invViewProj = identity();
  private static volatile float lastSunAngle = Float.NaN;

  public static void setSunAngle(float angleRadians) {
    lastSunAngle = angleRadians;
    float x = -(float) Math.sin(angleRadians);
    float y = (float) Math.cos(angleRadians);
    float len = (float) Math.sqrt(x * x + y * y);
    if (len < 1e-6f) { sunDir = new float[]{0.0f, 1.0f, 0.0f}; return; }
    if (y < 0) { x = -x; y = -y; }
    float l2 = (float) Math.sqrt(x * x + y * y);
    sunDir = new float[]{x / l2, y / l2, 0.0f};
  }
  public static float[] sunDir() { return sunDir.clone(); }
  public static float lastSunAngle() { return lastSunAngle; }
  public static void setFrame(float[] cam, float[] proj, float[] viewRot) {
    camPos = cam.clone();
    proj22 = proj[10];
    proj32 = proj[14];
    float[] view = com.vulkanis.shadow.FrameMatrices.viewFromRotationAndPos(viewRot, cam);
    try {
      viewProj = com.vulkanis.shadow.FrameMatrices.mul(proj, view);
      invViewProj = com.vulkanis.shadow.FrameMatrices.invert(viewProj);
    } catch (IllegalArgumentException ignored) { }
  }
  private static volatile float proj22 = 1.0f;
  private static volatile float proj32 = 0.0f;
  public static float proj22() { return proj22; }
  public static float proj32() { return proj32; }
  public static float[] camPos() { return camPos.clone(); }
  public static float[] viewProj() { return viewProj.clone(); }
  public static float[] invViewProj() { return invViewProj.clone(); }
  private static float[] identity() {
    float[] m = new float[16];
    m[0] = 1; m[5] = 1; m[10] = 1; m[15] = 1;
    return m;
  }
  public static void noteCompositeRun() { }
  private static volatile String lastPackError = "";
  public static void setLastPackError(String e) { lastPackError = e == null ? "" : e; }
  public static String lastPackError() { return lastPackError; }
}
