package com.vulkanis.render.shadow;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.joml.Matrix4f;

public final class ShadowGlobals {
  static final int BYTES = 64 + 64 + 16 + 8 + 8 + 8 + 8 + 4 + 4 + 8;

  private GpuBuffer buffer;
  private GpuBufferSlice slice;
  private final ByteBuffer staging = ByteBuffer.allocateDirect(BYTES).order(ByteOrder.nativeOrder());

  public synchronized GpuBufferSlice update(GpuDevice device, CommandEncoder encoder, Matrix4f lightViewProjection) {
    if (buffer == null) {
      buffer = device.createBuffer(() -> "vulkanis:shadow_globals",
        GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, BYTES);
      slice = buffer.slice();
    }
    staging.clear();
    float[] m = new float[16];
    lightViewProjection.get(m);
    for (float f : m) staging.putFloat(f);
    for (int i = 0; i < 16; i++) staging.putFloat(i == 0 || i == 5 || i == 10 || i == 15 ? 1.0f : 0.0f);
    for (int i = 0; i < 4; i++) staging.putFloat(0.0f);
    for (int i = 0; i < 8; i++) staging.putFloat(i < 2 ? 1.0f : 0.0f);
    staging.putFloat(1.0f);
    staging.putInt(0);
    staging.putLong(0L);
    staging.flip();
    encoder.writeToBuffer(slice, staging);
    return slice;
  }

  public synchronized void close() {
    buffer = null;
    slice = null;
  }
}
