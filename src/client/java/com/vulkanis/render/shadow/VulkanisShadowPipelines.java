package com.vulkanis.render.shadow;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.minecraft.resources.Identifier;

public final class VulkanisShadowPipelines {
  private static final Map<TerrainRenderPass, RenderPipeline> CASTERS = new ConcurrentHashMap<>();
  private static final Map<TerrainRenderPass, RenderPipeline> RECEIVERS = new ConcurrentHashMap<>();

  private VulkanisShadowPipelines() {
  }

  public static RenderPipeline caster(TerrainRenderPass pass, VertexFormat vertexFormat) {
    return CASTERS.computeIfAbsent(pass, ignored -> build(pass, vertexFormat, true));
  }

  public static RenderPipeline receiver(TerrainRenderPass pass, VertexFormat vertexFormat) {
    return RECEIVERS.computeIfAbsent(pass, ignored -> build(pass, vertexFormat, false));
  }

  public static void close() {
    CASTERS.clear();
    RECEIVERS.clear();
  }

  private static RenderPipeline build(TerrainRenderPass pass, VertexFormat vertexFormat, boolean caster) {
    String layer = pass.isTranslucent() ? "translucent" : pass.supportsFragmentDiscard() ? "cutout" : "solid";
    String shader = caster ? "vulkanis_shadow" : "vulkanis_terrain_receiver";
    String variant = caster ? shader : shader + "_cascades3";
    RenderPipeline.Builder builder = RenderPipeline.builder()
      .withBindGroupLayout(caster ? sodiumLayout() : receiverLayout())
      .withLocation(Identifier.fromNamespaceAndPath("vulkanis", "pipeline/shadow/" + variant + "_" + layer))
      .withVertexShader(Identifier.fromNamespaceAndPath("vulkanis", "vulkanis/" + shader))
      .withFragmentShader(Identifier.fromNamespaceAndPath("vulkanis", "vulkanis/" + shader))
      .withPrimitiveTopology(PrimitiveTopology.QUADS)
      .withVertexBinding(0, vertexFormat)
      .withPushConstantSize(20)
      .withShaderDefine("USE_VERTEX_COMPRESSION");

    if (caster) {
      builder.withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true, 0.0f, 0.0f))
        .withColorTargetState(new ColorTargetState(java.util.Optional.empty(), GpuFormat.RGBA8_UNORM, 0xFFFFFFFF))
        .withCull(false);
    } else {
      builder.withDepthStencilState(DepthStencilState.DEFAULT)
        .withCull(true)
        .withShaderDefine("USE_FOG")
        .withShaderDefine("VULKANIS_CASCADE_COUNT", 3)
        .withShaderDefine("VULKANIS_CASCADES", 3)
        .withShaderDefine("VULKANIS_API_VERSION", 1);
      if (pass.isTranslucent()) {
        builder.withColorTargetState(ColorTargetState.DEFAULT);
      } else {
        builder.withColorTargetState(ColorTargetState.DEFAULT);
      }
    }

    if (pass.isTranslucent()) {
      builder.withShaderDefine("ALPHA_CUTOUT", 0.01f);
    } else if (pass.supportsFragmentDiscard()) {
      builder.withShaderDefine("ALPHA_CUTOUT", 0.5f);
    }
    return builder.build();
  }

  private static BindGroupLayout sodiumLayout() {
    return BindGroupLayout.builder()
      .withUniform("u_LightTex", UniformType.COMBINED_IMAGE_SAMPLER)
      .withUniform("u_BlockTex", UniformType.COMBINED_IMAGE_SAMPLER)
      .withUniform("u_Globals", UniformType.UNIFORM_BUFFER)
      .withUniform("u_SectionTimeInfo", UniformType.TEXEL_BUFFER, GpuFormat.R32_SINT)
      .build();
  }

  private static BindGroupLayout receiverLayout() {
    return BindGroupLayout.builder()
      .withUniform("u_LightTex", UniformType.COMBINED_IMAGE_SAMPLER)
      .withUniform("u_BlockTex", UniformType.COMBINED_IMAGE_SAMPLER)
      .withUniform("u_Globals", UniformType.UNIFORM_BUFFER)
      .withUniform("u_SectionTimeInfo", UniformType.TEXEL_BUFFER, GpuFormat.R32_SINT)
      .withUniform("VulkanisShadowMap0", UniformType.COMBINED_IMAGE_SAMPLER)
      .withUniform("VulkanisShadowMap1", UniformType.COMBINED_IMAGE_SAMPLER)
      .withUniform("VulkanisShadowMap2", UniformType.COMBINED_IMAGE_SAMPLER)
      .withUniform("VulkanisShadowData", UniformType.UNIFORM_BUFFER)
      .build();
  }
}
