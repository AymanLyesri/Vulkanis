package com.vulkanis.render.shadow;

import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cascaded sun shadows. Three frustum-fitted cascades cover the configured
 * shadow range; near cascades refit often, far ones rarely. Between refits the
 * live matrix slides with the camera instead of refitting.
 *
 * <p>Approach notes: frustum-slice fitting with texel-snapped centers, blended
 * uniform/log splits, per-section cascade membership with an NDC margin, and
 * staggered refit intervals are the standard way to keep far shadows stable
 * and cheap. The receiver picks a cascade purely by view distance.
 */
public final class CascadeShadows {
  public static final int CASCADE_COUNT = 3;
  public static final int UBO_BYTES = 64 * CASCADE_COUNT + 16 * CASCADE_COUNT + 16 + 16;

  private static final Logger LOG = LoggerFactory.getLogger("vulkanis");
  private static final int DEFAULT_SIZE = 2048;
  private static final float MIN_SUN_HEIGHT = 0.12f;
  private static final int[] UPDATE_INTERVALS = {3, 8, 16};
  private static final double[] MOVE_LIMITS = {1.5, 4.0, 12.0};
  private static final float LIST_MOVE_LIMIT = 4.0f;
  private static final float LIGHT_DIR_DOT = 0.9998f;
  private static final float LOOK_DIR_DOT = 0.9659f;
  private static final float NEAR_SPLIT = 8.0f;

  private static final CascadeShadows INSTANCE = new CascadeShadows();

  private final TextureTarget[] targets = new TextureTarget[CASCADE_COUNT];
  private final int[] targetSizes = new int[CASCADE_COUNT];
  private final Matrix4f[] fitted = new Matrix4f[CASCADE_COUNT];
  private final Matrix4f[] live = new Matrix4f[CASCADE_COUNT];
  private final float[] ends = new float[CASCADE_COUNT];
  private final float[] texelWorld = new float[CASCADE_COUNT];
  private final float[] depthRanges = new float[CASCADE_COUNT];
  private final double[] renderedX = new double[CASCADE_COUNT];
  private final double[] renderedY = new double[CASCADE_COUNT];
  private final double[] renderedZ = new double[CASCADE_COUNT];
  private final Vector3f[] renderedLight = new Vector3f[CASCADE_COUNT];
  private final Vector3f[] renderedForward = new Vector3f[CASCADE_COUNT];
  private final long[] renderedSerial = new long[CASCADE_COUNT];
  private final boolean[] initialized = new boolean[CASCADE_COUNT];
  private final boolean[] needsUpdate = new boolean[CASCADE_COUNT];

  private final Vector3f lightDirection = new Vector3f(0.0f, 1.0f, 0.0f);
  private final Vector3f cameraForward = new Vector3f(0.0f, 0.0f, -1.0f);
  private final Vector3f[] sliceCorners = new Vector3f[8];
  private final float[] matrixFloats = new float[16];
  private final ByteBuffer upload = ByteBuffer.allocateDirect(UBO_BYTES).order(ByteOrder.nativeOrder());

  private GpuBuffer dataBuffer;
  private GpuBufferSlice dataSlice;
  private final Map<RenderPipeline, CompletableFuture<CompiledRenderPipeline.Pending>> pendingCompiles =
    new java.util.concurrent.ConcurrentHashMap<>();
  private static final Executor COMPILE_POOL = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "vulkanis-shadow-compile");
    t.setDaemon(true);
    return t;
  });

  private final VulkanisShaderSources shaderSources = new VulkanisShaderSources();
  private long frameSerial;
  private double camX;
  private double camY;
  private double camZ;
  private float coverage;
  private boolean sunUp = true;
  private boolean renderingShadow;
  private boolean errorLogged;
  private static boolean cacheHitLogged;

  private CascadeShadows() {
    for (int i = 0; i < CASCADE_COUNT; i++) {
      fitted[i] = new Matrix4f();
      live[i] = new Matrix4f();
      renderedLight[i] = new Vector3f();
      renderedForward[i] = new Vector3f();
      renderedSerial[i] = -1;
    }
    for (int i = 0; i < sliceCorners.length; i++) {
      sliceCorners[i] = new Vector3f();
    }
  }

  public static CascadeShadows get() {
    return INSTANCE;
  }

  public static boolean renderingShadowMap() {
    return INSTANCE.renderingShadow;
  }

  /** Pack shadow map size snapped to the nearest supported target. */
  static int resolveSize(com.vulkanis.pack.PipelineSpec spec) {
    if (spec == null) {
      return DEFAULT_SIZE;
    }
    return snapSize(spec.shadowSize());
  }

  static int snapSize(int size) {
    if (size <= 1536) {
      return 1024;
    }
    if (size <= 3072) {
      return 2048;
    }
    return 4096;
  }

  /** Pack shadow distance clamped to the validated pipeline.json range. */
  static float resolveRadius(double value) {
    if (value < 32.0) {
      return 32.0f;
    }
    if (value > 256.0) {
      return 256.0f;
    }
    return (float) value;
  }

  public int shadowSize() {
    int override = com.vulkanis.VulkanisClient.shadowMapSizeSetting();
    if (override > 0) {
      return snapSize(override);
    }
    return resolveSize(com.vulkanis.render.ShadowHookState.activeSpec());
  }

  public float shadowRadius() {
    return resolveRadius(com.vulkanis.VulkanisClient.maxShadowDistanceSetting());
  }

  /** Per-cascade target sizes: full resolution near, quarter far. */
  static int cascadeSize(int base, int cascade) {
    int div = cascade == 0 ? 1 : cascade == 1 ? 2 : 4;
    return Math.max(1024, snapSize((base + div - 1) / div));
  }

  /** Blended uniform/logarithmic split distances for the cascade ends. */
  static float[] splitDistances(float coverage, int count) {
    float[] result = new float[CASCADE_COUNT];
    for (int i = 0; i < count; i++) {
      float t = (float) (i + 1) / (float) count;
      float logarithmic = NEAR_SPLIT * (float) Math.pow(coverage / NEAR_SPLIT, t);
      float uniform = NEAR_SPLIT + (coverage - NEAR_SPLIT) * t;
      result[i] = uniform * 0.35f + logarithmic * 0.65f;
    }
    return result;
  }

  /** Slide a fitted matrix with the camera between refits. */
  static void slideMatrix(Matrix4f fittedMatrix, double dx, double dy, double dz, Matrix4f out) {
    out.set(fittedMatrix).translate((float) dx, (float) dy, (float) dz);
  }

  public void prepare(double x, double y, double z, float sunAngle,
      Matrix4f projectionMatrix, Matrix4f viewRotationMatrix) {
    frameSerial++;
    camX = x;
    camY = y;
    camZ = z;
    float sx = -(float) Math.sin(sunAngle);
    float sy = (float) Math.cos(sunAngle);
    float len = (float) Math.sqrt(sx * sx + sy * sy);
    if (len < 1e-6f) {
      sunUp = false;
      return;
    }
    lightDirection.set(sx / len, sy / len, 0.0f);
    if (lightDirection.y < 0.0f) {
      lightDirection.negate();
    }
    sunUp = lightDirection.y >= MIN_SUN_HEIGHT;
    if (viewRotationMatrix != null) {
      Matrix4f inverse = new Matrix4f(viewRotationMatrix).invert();
      cameraForward.set(0.0f, 0.0f, -1.0f);
      inverse.transformDirection(cameraForward).normalize();
    }
    coverage = shadowRadius();
    int baseSize = shadowSize();
    boolean resized = false;
    for (int i = 0; i < CASCADE_COUNT; i++) {
      int size = cascadeSize(baseSize, i);
      if (targets[i] == null || targetSizes[i] != size) {
        resized = true;
        targetSizes[i] = size;
      }
    }
    ensureResources();
    if (!sunUp) {
      return;
    }
    float[] splits = splitDistances(coverage, CASCADE_COUNT);
    Matrix4f inverseProjection = projectionMatrix == null ? null : new Matrix4f(projectionMatrix).invert();
    Matrix4f inverseRotation = viewRotationMatrix == null ? new Matrix4f() : new Matrix4f(viewRotationMatrix).invert();
    for (int i = 0; i < CASCADE_COUNT; i++) {
      boolean update = shouldRefit(i, resized, splits[i]);
      needsUpdate[i] = update;
      if (update) {
        float start = i == 0 ? 0.5f : splits[i - 1] * 0.82f;
        ends[i] = splits[i];
        fitCascade(i, inverseProjection, inverseRotation, start, splits[i]);
        renderedX[i] = x;
        renderedY[i] = y;
        renderedZ[i] = z;
        renderedLight[i].set(lightDirection);
        renderedForward[i].set(cameraForward);
        renderedSerial[i] = frameSerial;
        initialized[i] = true;
      }
      slideMatrix(fitted[i], x - renderedX[i], y - renderedY[i], z - renderedZ[i], live[i]);
    }
  }

  private boolean shouldRefit(int cascade, boolean resized, float end) {
    if (resized || !initialized[cascade]) {
      return true;
    }
    if (frameSerial - renderedSerial[cascade] >= UPDATE_INTERVALS[cascade]) {
      return true;
    }
    double dx = camX - renderedX[cascade];
    double dy = camY - renderedY[cascade];
    double dz = camZ - renderedZ[cascade];
    double limit = MOVE_LIMITS[cascade];
    if (dx * dx + dy * dy + dz * dz >= limit * limit) {
      return true;
    }
    if (Math.abs(ends[cascade] - end) > 0.5f) {
      return true;
    }
    return renderedLight[cascade].dot(lightDirection) < LIGHT_DIR_DOT
      || renderedForward[cascade].dot(cameraForward) < LOOK_DIR_DOT;
  }

  public boolean shouldUpdateCascade(int cascade) {
    return cascade >= 0 && cascade < CASCADE_COUNT && initialized[cascade] && needsUpdate[cascade];
  }

  public boolean cascadeReady(int cascade) {
    return initialized[cascade] && targets[cascade] != null
      && targets[cascade].getDepthTextureView() != null;
  }

  public boolean ready() {
    if (dataSlice == null) {
      return false;
    }
    for (int i = 0; i < CASCADE_COUNT; i++) {
      if (!cascadeReady(i)) {
        return false;
      }
    }
    return true;
  }

  public boolean shouldRender() {
    return sunUp && ready();
  }

  public Matrix4f liveMatrix(int cascade) {
    return live[cascade];
  }

  public TextureTarget target(int cascade) {
    return targets[cascade];
  }

  public GpuTextureView depthView(int cascade) {
    return targets[cascade] == null ? null : targets[cascade].getDepthTextureView();
  }

  public float cascadeEnd(int cascade) {
    return ends[cascade];
  }

  public float listMoveLimit() {
    return LIST_MOVE_LIMIT;
  }

  public Vector3f lightDirection() {
    return lightDirection;
  }

  public Vector3f cameraForward() {
    return cameraForward;
  }

  public double cameraX() {
    return camX;
  }

  public double cameraY() {
    return camY;
  }

  public double cameraZ() {
    return camZ;
  }

  /** NDC-space membership test for a 16-block section against a cascade. */
  public boolean sectionInCascade(int cascade, int originX, int originY, int originZ) {
    Matrix4f m = live[cascade];
    float minX = Float.POSITIVE_INFINITY;
    float minY = Float.POSITIVE_INFINITY;
    float minZ = Float.POSITIVE_INFINITY;
    float maxX = Float.NEGATIVE_INFINITY;
    float maxY = Float.NEGATIVE_INFINITY;
    float maxZ = Float.NEGATIVE_INFINITY;
    for (int ox = 0; ox <= 16; ox += 16) {
      for (int oy = 0; oy <= 16; oy += 16) {
        for (int oz = 0; oz <= 16; oz += 16) {
          float rx = (float) (originX + ox - camX);
          float ry = (float) (originY + oy - camY);
          float rz = (float) (originZ + oz - camZ);
          float cx = m.m00() * rx + m.m10() * ry + m.m20() * rz + m.m30();
          float cy = m.m01() * rx + m.m11() * ry + m.m21() * rz + m.m31();
          float cz = m.m02() * rx + m.m12() * ry + m.m22() * rz + m.m32();
          float cw = m.m03() * rx + m.m13() * ry + m.m23() * rz + m.m33();
          if (Math.abs(cw) < 1e-5f) {
            continue;
          }
          float invW = 1.0f / cw;
          float nx = cx * invW;
          float ny = cy * invW;
          float nz = cz * invW;
          if (nx < minX) minX = nx;
          if (ny < minY) minY = ny;
          if (nz < minZ) minZ = nz;
          if (nx > maxX) maxX = nx;
          if (ny > maxY) maxY = ny;
          if (nz > maxZ) maxZ = nz;
        }
      }
    }
    float margin = cascade == 0 ? 0.18f : 0.12f;
    return maxX >= -1.0f - margin && minX <= 1.0f + margin
      && maxY >= -1.0f - margin && minY <= 1.0f + margin
      && maxZ >= -0.08f && minZ <= 1.08f;
  }

  public GpuBufferSlice dataSlice() {
    return dataSlice;
  }

  public void uploadData(CommandEncoder encoder, float strength, float bias, float filterRadius, float filterMode) {
    upload.clear();
    for (int i = 0; i < CASCADE_COUNT; i++) {
      live[i].get(matrixFloats);
      for (float f : matrixFloats) upload.putFloat(f);
    }
    for (int i = 0; i < CASCADE_COUNT; i++) {
      float prevEnd = i == 0 ? 0.0f : ends[i - 1];
      upload.putFloat(texelWorld[i]).putFloat(prevEnd).putFloat(ends[i]).putFloat(depthRanges[i]);
    }
    upload.putFloat(lightDirection.x).putFloat(lightDirection.y).putFloat(lightDirection.z).putFloat(0.0f);
    float s = sunUp ? strength : 0.0f;
    upload.putFloat(s).putFloat(bias).putFloat(filterRadius).putFloat(filterMode);
    upload.flip();
    try {
      encoder.writeToBuffer(dataSlice, upload);
    } catch (Exception e) {
      if (!errorLogged) { errorLogged = true; LOG.warn("vulkanis: shadow UBO upload failed", e); }
    }
  }

  public void begin() {
    renderingShadow = true;
  }

  public void end() {
    renderingShadow = false;
  }

  public boolean ensureCompiled(GpuDevice device, RenderPipeline pipeline) {
    try {
      if (RenderSystem.getCompiledPipelineNullable(pipeline) != null) {
        if (!cacheHitLogged) {
          cacheHitLogged = true;
          LOG.info("vulkanis: pipeline already in vanilla cache");
        }
        return true;
      }
    } catch (Exception ignored) { }
    CompletableFuture<CompiledRenderPipeline.Pending> future = pendingCompiles.get(pipeline);
    if (future == null) {
      try {
        future = device.compilePipeline(pipeline, shaderSources.sourceFor("", ""), COMPILE_POOL);
        pendingCompiles.put(pipeline, future);
        LOG.info("vulkanis: kicked shadow compile");
      } catch (Exception e) {
        if (!errorLogged) { errorLogged = true; LOG.warn("vulkanis: shadow pipeline kick failed", e); }
        com.vulkanis.render.ShadowHookState.setLastPackError(
          "shadow pipeline kick failed: " + String.valueOf(e.getMessage()));
        return false;
      }
      return false;
    }
    if (!future.isDone()) {
      return false;
    }
    try {
      CompiledRenderPipeline.Pending pending = future.getNow(null);
      if (pending == null) {
        return false;
      }
      CompiledRenderPipeline compiled = pending.finishCompile();
      PipelineCache mine = new PipelineCache(device, shaderSources.sourceFor("", ""));
      PipelineCache vanilla = RenderSystem.setCurrentPipelineCache(mine);
      try {
        vanilla.insert(pipeline, compiled);
      } finally {
        RenderSystem.setCurrentPipelineCache(vanilla);
      }
      mine.close();
      pendingCompiles.remove(pipeline);
      LOG.info("vulkanis: shadow pipeline ready");
      return true;
    } catch (Exception e) {
      if (!errorLogged) { errorLogged = true; LOG.warn("vulkanis: shadow pipeline compile failed", e); }
      com.vulkanis.render.ShadowHookState.setLastPackError(
        "shadow pipeline compile failed: " + String.valueOf(e.getMessage()));
      pendingCompiles.remove(pipeline);
      return false;
    }
  }

  private void ensureResources() {
    try {
      if (dataBuffer == null) {
        dataBuffer = RenderSystem.getDevice().createBuffer(() -> "vulkanis:shadow_data",
          GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, UBO_BYTES);
        dataSlice = dataBuffer.slice();
      }
      for (int i = 0; i < CASCADE_COUNT; i++) {
        int size = targetSizes[i] > 0 ? targetSizes[i] : DEFAULT_SIZE;
        if (targets[i] == null || targets[i].width != size || targets[i].height != size) {
          if (targets[i] != null) targets[i].destroyBuffers();
          targets[i] = new TextureTarget("vulkanis:shadow" + i, size, size,
            GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
        }
      }
    } catch (Exception e) {
      if (!errorLogged) { errorLogged = true; LOG.warn("vulkanis: shadow resources failed", e); }
    }
  }

  private void fitCascade(int cascade, Matrix4f inverseProjection, Matrix4f inverseRotation,
      float start, float end) {
    buildSliceCorners(inverseProjection, inverseRotation, start, end);
    Vector3f center = new Vector3f();
    for (Vector3f corner : sliceCorners) {
      center.add(corner);
    }
    center.div(sliceCorners.length);
    float radius = 0.0f;
    for (Vector3f corner : sliceCorners) {
      radius = Math.max(radius, center.distance(corner));
    }
    radius += Math.max(2.0f, (end - start) * 0.03f);
    radius = (float) Math.ceil(radius * 16.0f) / 16.0f;

    Vector3f dir = new Vector3f(lightDirection).normalize();
    Vector3f up = Math.abs(dir.y) > 0.88f ? new Vector3f(0.0f, 0.0f, 1.0f) : new Vector3f(0.0f, 1.0f, 0.0f);
    Vector3f right = new Vector3f(up).cross(dir).normalize();
    Vector3f lightUp = new Vector3f(dir).cross(right).normalize();

    int size = targetSizes[cascade] > 0 ? targetSizes[cascade] : DEFAULT_SIZE;
    float texel = 2.0f * radius / size;
    texelWorld[cascade] = texel;
    float snappedX = Math.round(right.dot(center) / texel) * texel;
    float snappedY = Math.round(lightUp.dot(center) / texel) * texel;
    Vector3f stable = new Vector3f(center)
      .add(new Vector3f(right).mul(snappedX - right.dot(center)))
      .add(new Vector3f(lightUp).mul(snappedY - lightUp.dot(center)));

    float padding = Math.max(32.0f, Math.min(128.0f, end * 0.35f));
    float lightward = cascade < 2 ? Math.max(padding, coverage) : padding;
    float lightReach = radius + lightward;
    float farReach = radius + padding;
    float depth = lightReach + farReach;
    depthRanges[cascade] = depth;
    Vector3f eye = new Vector3f(stable).fma(lightReach, dir);
    Matrix4f view = new Matrix4f().lookAt(eye, stable, lightUp);
    fitted[cascade].identity().ortho(-radius, radius, -radius, radius, 0.1f, depth, true).mul(view);
  }

  private void buildSliceCorners(Matrix4f inverseProjection, Matrix4f inverseRotation,
      float start, float end) {
    int index = 0;
    float[] distances = {start, end};
    for (float distance : distances) {
      for (int y = -1; y <= 1; y += 2) {
        for (int x = -1; x <= 1; x += 2) {
          Vector3f corner = new Vector3f();
          if (inverseProjection != null) {
            org.joml.Vector4f view = inverseProjection.transform(new org.joml.Vector4f(x, y, 1.0f, 1.0f));
            view.div(view.w);
            corner.set(view.x, view.y, view.z).normalize().mul(distance);
            inverseRotation.transformPosition(corner);
          } else {
            corner.set(x * distance * 0.5f, y * distance * 0.3f, -distance);
          }
          sliceCorners[index++].set(corner);
        }
      }
    }
  }

  public void close() {
    for (int i = 0; i < CASCADE_COUNT; i++) {
      if (targets[i] != null) targets[i].destroyBuffers();
      targets[i] = null;
    }
    if (dataBuffer != null) dataBuffer.close();
    dataBuffer = null;
    dataSlice = null;
  }
}
