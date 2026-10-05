package com.vulkanis.render.shadow;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;

/**
 * Depth-only entity pipelines. At draw time our mixin cancels vanilla entity
 * draws inside the shadow pass and re-issues them here: same vertices, same
 * per-entity pose from vanilla's DynamicTransforms, but our sun matrices.
 * Engine-owned (pure function, no pack control).
 */
public final class EntityDepth {
  public static final int UBO_BYTES = 128 * CascadeShadows.CASCADE_COUNT;

  public static final String VERTEX = """
      #version 460 core
      layout(std140) uniform DynamicTransforms {
          mat4 ModelViewMat;
          mat4 TextureMat;
          vec4 ColorModulator;
          vec3 ModelOffset;
      };
      layout(std140) uniform VulkanisEntityCascade {
          mat4 CascadeViewProjection;
          mat4 InverseViewRotation;
      };
      layout(location = 0) in vec3 Position;
      void main() {
          vec3 viewPosition = (ModelViewMat * vec4(Position, 1.0)).xyz;
          vec3 cameraRelative = (InverseViewRotation * vec4(viewPosition, 0.0)).xyz;
          gl_Position = CascadeViewProjection * vec4(cameraRelative, 1.0);
      }
      """;

  public static final String FRAGMENT = """
      #version 460 core
      layout(location = 0) out vec4 fragColor;
      void main() {
          fragColor = vec4(1.0);
      }
      """;

  private static final Map<RenderPipeline, RenderPipeline> DEPTH_FOR = new ConcurrentHashMap<>();
  private static volatile RenderPipeline depthPipeline;
  private static volatile GpuBuffer cascadeBuffer;
  private static volatile int activeCascade = -1;

  private EntityDepth() {
  }

  public static String text(String kind) {
    return "vertex".equals(kind) ? VERTEX : FRAGMENT;
  }

  public static void setActiveCascade(int cascade) {
    activeCascade = cascade;
  }

  public static GpuBufferSlice activeSlice() {
    GpuBuffer buffer = cascadeBuffer;
    if (buffer == null || activeCascade < 0 || activeCascade >= CascadeShadows.CASCADE_COUNT) {
      return null;
    }
    return buffer.slice(activeCascade * 128L, 128L);
  }

  public static void upload(CommandEncoder encoder, int cascade, Matrix4f cascadeMatrix,
      Matrix4f inverseViewRotation) {
    try {
      if (cascadeBuffer == null) {
        cascadeBuffer = RenderSystem.getDevice().createBuffer(() -> "vulkanis:entity_cascade",
          GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, UBO_BYTES);
      }
      ByteBuffer info = ByteBuffer.allocateDirect(128).order(ByteOrder.nativeOrder());
      float[] floats = new float[16];
      cascadeMatrix.get(floats);
      for (float f : floats) info.putFloat(f);
      inverseViewRotation.get(floats);
      for (float f : floats) info.putFloat(f);
      info.flip();
      encoder.writeToBuffer(cascadeBuffer.slice(cascade * 128L, 128L), info);
    } catch (Exception ignored) { }
  }

  /** Maps known vanilla entity pipelines to our depth pipeline; null = leave alone. */
  public static RenderPipeline forPipeline(RenderPipeline pipeline) {
    RenderPipeline mapped = DEPTH_FOR.get(pipeline);
    if (mapped != null) {
      return mapped;
    }
    if (!isEntityPipeline(pipeline)) {
      return null;
    }
    RenderPipeline depth = depthPipeline;
    if (depth == null) {
      depth = build();
      depthPipeline = depth;
    }
    DEPTH_FOR.put(pipeline, depth);
    return depth;
  }

  private static boolean isEntityPipeline(RenderPipeline pipeline) {
    return pipeline == net.minecraft.client.renderer.RenderPipelines.ENTITY_SOLID
      || pipeline == net.minecraft.client.renderer.RenderPipelines.ENTITY_SOLID_Z_OFFSET_FORWARD
      || pipeline == net.minecraft.client.renderer.RenderPipelines.ENTITY_CUTOUT
      || pipeline == net.minecraft.client.renderer.RenderPipelines.ENTITY_CUTOUT_CULL
      || pipeline == net.minecraft.client.renderer.RenderPipelines.ENTITY_CUTOUT_Z_OFFSET
      || pipeline == net.minecraft.client.renderer.RenderPipelines.ENTITY_CUTOUT_DISSOLVE
      || pipeline == net.minecraft.client.renderer.RenderPipelines.ENTITY_TRANSLUCENT
      || pipeline == net.minecraft.client.renderer.RenderPipelines.ENTITY_TRANSLUCENT_CULL
      || pipeline == net.minecraft.client.renderer.RenderPipelines.ENTITY_TRANSLUCENT_EMISSIVE
      || pipeline == net.minecraft.client.renderer.RenderPipelines.ARMOR_CUTOUT_NO_CULL
      || pipeline == net.minecraft.client.renderer.RenderPipelines.ARMOR_DECAL_CUTOUT_NO_CULL;
  }

  private static RenderPipeline build() {
    VertexFormat format = com.mojang.blaze3d.vertex.DefaultVertexFormat.ENTITY;
    return RenderPipeline.builder()
      .withBindGroupLayout(BindGroupLayout.builder()
        .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
        .withUniform("VulkanisEntityCascade", UniformType.UNIFORM_BUFFER)
        .build())
      .withLocation(Identifier.fromNamespaceAndPath("vulkanis", "pipeline/shadow/entity_depth"))
      .withVertexShader(Identifier.fromNamespaceAndPath("vulkanis", "vulkanis/entity_depth"))
      .withFragmentShader(Identifier.fromNamespaceAndPath("vulkanis", "vulkanis/entity_depth"))
      .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
      .withVertexBinding(0, format)
      .withPushConstantSize(20)
      .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true, 0.0f, 0.0f))
      .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, 0xFFFFFFFF))
      .withCull(false)
      .build();
  }
}
