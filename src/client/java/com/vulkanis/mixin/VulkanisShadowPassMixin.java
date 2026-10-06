package com.vulkanis.mixin;

import com.vulkanis.VulkanisClient;
import com.vulkanis.mixin.sodium.SodiumWorldRendererAccessor;
import com.vulkanis.render.shadow.CascadePlans;
import com.vulkanis.render.shadow.CascadeShadows;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.framegraph.FramePass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import java.util.Optional;
import java.util.OptionalDouble;
import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.sprite.AtlasManager;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class VulkanisShadowPassMixin {
  private static final Logger LOG = LoggerFactory.getLogger("vulkanis");
  private static boolean hookedLogged;
  private static boolean errorLogged;
  private static boolean shadowExecLogged;
  @Unique
  private static long vulkanis$shadowFrames;
  @Unique
  private static final com.vulkanis.render.shadow.ShadowGlobals[] vulkanis$shadowGlobals = {
    new com.vulkanis.render.shadow.ShadowGlobals(),
    new com.vulkanis.render.shadow.ShadowGlobals(),
    new com.vulkanis.render.shadow.ShadowGlobals(),
  };
  @Unique
  private static final CascadePlans vulkanis$plans = new CascadePlans();
  private static final com.vulkanis.render.shadow.EntityShadowCasters vulkanis$entityCasters =
    new com.vulkanis.render.shadow.EntityShadowCasters();
  private static RenderBuffers vulkanis$entityRenderBuffers;
  private static FeatureRenderDispatcher vulkanis$entityFeatureDispatcher;
  private static boolean vulkanis$entityCollectLogged;
  private static long vulkanis$entityIterated;
  private static long vulkanis$entityExtracted;
  private static long vulkanis$entitySubmitted;
  private static long vulkanis$entityFailed;
  private static String vulkanis$entityFirstError;

  @Shadow
  @Final
  private LevelRenderState levelRenderState;
  @Shadow
  private GpuSampler chunkLayerSampler;

  @Shadow
  @Final
  private ModelManager modelManager;
  @Shadow
  @Final
  private AtlasManager atlasManager;
  @Shadow
  @Final
  private net.minecraft.client.renderer.GameRenderer gameRenderer;
  @Shadow
  @Final
  private net.minecraft.client.renderer.entity.EntityRenderDispatcher entityRenderDispatcher;  @Inject(method = "render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZZ)V",
      at = @At(value = "INVOKE",
          target = "Lnet/minecraft/client/renderer/LevelRenderer;submitFeatures(Lnet/minecraft/client/renderer/state/level/LevelRenderState;Lnet/minecraft/client/renderer/SubmitNodeCollector;Z)V"),
      require = 0)
  private void vulkanis$collectShadowEntities(
      com.mojang.blaze3d.resource.GraphicsResourceAllocator allocator, boolean renderBlockOutline,
      net.minecraft.client.renderer.state.level.CameraRenderState cameraRenderState,
      com.mojang.renderpearl.api.buffers.GpuBufferSlice fog, org.joml.Vector4f fogColor,
      boolean renderOutline, boolean something, CallbackInfo ci) {
    vulkanis$entityCasters.clear();
    if (!com.vulkanis.render.ShadowHookState.shadowsActive()) {
      return;
    }
    if (levelRenderState == null) {
      return;
    }
    boolean hadOutlines = levelRenderState.shouldShowEntityOutlines;
    levelRenderState.shouldShowEntityOutlines = false;
    // Own entity loop with main-camera state (matrices come from DynamicTransforms
    // at draw time; our draw mixin swaps only the camera part to the sun).
    try {
      vulkanis$submitSunEntities(CascadeShadows.get());
      if (!vulkanis$entityCollectLogged) {
        vulkanis$entityCollectLogged = true;
        LOG.info("vulkanis: entity shadow collection firing");
      }
    } catch (Exception e) {
      if (!errorLogged) { errorLogged = true; LOG.warn("vulkanis: entity shadow collect failed", e); }
    } finally {
      levelRenderState.shouldShowEntityOutlines = hadOutlines;
    }
  }

  @Inject(method = "addMainPass", at = @At("HEAD"), require = 0)
  private void vulkanis$renderShadowMap(FrameGraphBuilder builder,
      FeatureRenderDispatcher.PreparedFrame preparedFrame, GpuBufferSlice fog,
      ChunkSectionsToRender sections, boolean flag, CallbackInfo ci) {
    if (!hookedLogged) { hookedLogged = true; LOG.info("vulkanis: shadow pass hook firing"); }
    if (levelRenderState == null || levelRenderState.cameraRenderState == null
        || levelRenderState.cameraRenderState.pos == null || levelRenderState.skyRenderState == null) {
      return;
    }
    Vec3 cam = levelRenderState.cameraRenderState.pos;
    CascadeShadows shadows = CascadeShadows.get();
    shadows.prepare(cam.x, cam.y, cam.z, levelRenderState.skyRenderState.sunAngle,
      levelRenderState.cameraRenderState.projectionMatrix,
      levelRenderState.cameraRenderState.viewRotationMatrix);
    SodiumWorldRenderer sodiumRenderer = SodiumWorldRenderer.instanceNullable();
    if (sodiumRenderer == null) {
      return;
    }
    GpuSampler sampler = chunkLayerSampler != null ? chunkLayerSampler
      : RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
    FramePass pass = builder.addPass("vulkanis:terrain_shadow");
    pass.disableCulling();
    pass.executes(() -> {
      CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
      try {
        boolean render = com.vulkanis.render.ShadowHookState.shadowsActive()
          && shadows.shouldRender();
        if (!shadowExecLogged) { shadowExecLogged = true; LOG.info("vulkanis: shadow executes render={}", render); }
        if (render) {
          vulkanis$renderTerrainShadow(shadows, sodiumRenderer, cam, sampler, encoder);
        }
        shadows.uploadData(encoder, render ? VulkanisClient.shadowStrengthSetting() : 0.0f,
          VulkanisClient.shadowBiasSetting(), VulkanisClient.shadowFilterRadiusSetting(),
          VulkanisClient.shadowFilterModeSetting());
      } catch (Exception e) {
        if (!errorLogged) { errorLogged = true; LOG.warn("vulkanis: shadow pass failed", e); }
      } finally {
        encoder.submit();
      }
    });
  }

  @Unique
  private void vulkanis$submitSunEntities(CascadeShadows shadows) {
    net.minecraft.client.renderer.state.level.CameraRenderState mainCam =
      levelRenderState.cameraRenderState;
    if (mainCam == null || mainCam.pos == null) {
      return;
    }
    double camX = mainCam.pos.x;    double camY = mainCam.pos.y;
    double camZ = mainCam.pos.z;
    net.minecraft.client.renderer.state.level.CameraRenderState mainState = mainCam;
    int iterated = 0;
    for (net.minecraft.client.renderer.entity.state.EntityRenderState state
        : levelRenderState.entityRenderStates) {
      iterated++;
      if (state == null) {
        continue;
      }
      try {
        for (int c = 0; c < CascadeShadows.CASCADE_COUNT; c++) {
          if (!com.vulkanis.render.shadow.EntityShadowCasters.inRange(
              state.distanceToCameraSq, shadows.cascadeEnd(c),
              state.boundingBoxWidth, state.boundingBoxHeight)) {
            continue;
          }
          entityRenderDispatcher.submit(state, mainState, state.x - camX, state.y - camY,
            state.z - camZ, new PoseStack(),
            vulkanis$entityCasters.cascadeCollector(c));
          vulkanis$entitySubmitted++;
        }
        vulkanis$entityExtracted++;
      } catch (Exception e) {
        vulkanis$entityFailed++;
        if (vulkanis$entityFirstError == null) {
          vulkanis$entityFirstError = String.valueOf(e);
        }
      }
    }
    vulkanis$entityIterated += iterated;
  }

  @Unique
  private FeatureRenderDispatcher.PreparedFrame vulkanis$prepareEntityShadow(int cascade) {
    if (vulkanis$entityFeatureDispatcher == null) {
      vulkanis$entityRenderBuffers =
        new RenderBuffers(Runtime.getRuntime().availableProcessors());
      vulkanis$entityFeatureDispatcher = new FeatureRenderDispatcher(
        vulkanis$entityRenderBuffers, modelManager, atlasManager,
        net.minecraft.client.Minecraft.getInstance().font,
        gameRenderer.gameRenderState());
    }
    return vulkanis$entityFeatureDispatcher.prepareFrame(vulkanis$entityCasters.storage(cascade));
  }

  @Unique
  private void vulkanis$renderTerrainShadow(CascadeShadows shadows, SodiumWorldRenderer sodiumRenderer,
      Vec3 cam, GpuSampler sampler, CommandEncoder encoder) {
    SodiumWorldRendererAccessor access = (SodiumWorldRendererAccessor) sodiumRenderer;
    RenderSectionManager sectionManager = access.vulkanis$renderSectionManager();
    if (sectionManager == null) {
      return;
    }
    UniformBufferManager uniforms = access.vulkanis$uniformBufferManager();
    CameraTransform shadowCamera = new CameraTransform(cam.x, cam.y, cam.z);
    boolean culling = SodiumClientMod.options().performance.useBlockFaceCulling;
    SodiumClientMod.options().performance.useBlockFaceCulling = false;
    shadows.begin();
    boolean drew = false;
    org.joml.Matrix4f inverseMainView = new org.joml.Matrix4f();
    try {
      if (levelRenderState.cameraRenderState.viewRotationMatrix != null) {
        inverseMainView.set(levelRenderState.cameraRenderState.viewRotationMatrix).invert();
      }
    } catch (Exception ignored) { }
    try {
      for (int cascade = 0; cascade < CascadeShadows.CASCADE_COUNT; cascade++) {
        if (!shadows.shouldUpdateCascade(cascade) || !shadows.cascadeReady(cascade)) {
          continue;
        }
        ChunkRenderListIterable lists =
          vulkanis$plans.planFor(sectionManager, shadows, cascade, true);
        ChunkRenderMatrices matrices = new ChunkRenderMatrices(
          new Matrix4f(shadows.liveMatrix(cascade)), new Matrix4f());
        GpuBufferSlice globals = vulkanis$shadowGlobals[cascade].update(
          RenderSystem.getDevice(), encoder, shadows.liveMatrix(cascade));
        // Rebuild draw batches from the cascade's lists: render() reuses the
        // cached batch when non-empty, and the cache is shared with the main
        // render, so skipping this would draw player-culled batches.
        sectionManager.getChunkRenderer().prepare(lists, shadowCamera, false);
        if ((vulkanis$shadowFrames % 600) == 0) {
          LOG.info("vulkanis: shadow diag cascade={} end={} sections={} dir={} entityNodes=[{},{},{}] loop=[iter={} ok={} submitted={} failed={} err={}]",
            cascade, shadows.cascadeEnd(cascade), vulkanis$plans.casterSectionCount(cascade),
            shadows.lightDirection(), vulkanis$entityCasters.submitCount(0),
            vulkanis$entityCasters.submitCount(1), vulkanis$entityCasters.submitCount(2),
            vulkanis$entityIterated, vulkanis$entityExtracted,
            vulkanis$entitySubmitted, vulkanis$entityFailed, vulkanis$entityFirstError);
        }
        encoder.clearColorAndDepthTextures(shadows.target(cascade).getColorTexture(),
          new Vector4f(1.0f, 1.0f, 1.0f, 1.0f), shadows.target(cascade).getDepthTexture(), 1.0);
        for (TerrainRenderPass pass : new TerrainRenderPass[]{
            net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses.SOLID,
            net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses.CUTOUT}) {
          // LOAD (empty clear opts): the target was cleared once above, and a
          // clear here would wipe the previous pass's depths, leaving only the
          // last pass's casters in the map.
          try (RenderPass renderPass = encoder.createRenderPass(() -> "vulkanis terrain shadow",
            shadows.target(cascade).getColorTextureView(), Optional.empty(),
            shadows.target(cascade).getDepthTextureView(), OptionalDouble.empty())) {
            sectionManager.getChunkRenderer().render(matrices, lists, pass,
              shadowCamera, FogParameters.NONE, false, renderPass, sampler,
              globals, uniforms.getSectionTimeInfo(), null);
          }
        }
        drew = true;
        // Entities share the refit flag and the already-cleared target (LOAD, no
        // clear): their depth joins the terrain depths in the same map. Draws are
        // redirected to the depth pipeline by VulkanisEntityDrawMixin.
        if (vulkanis$entityCasters.hasSubmits(cascade)) {
          try {
            com.vulkanis.render.shadow.EntityDepth.setActiveCascade(cascade);
            com.vulkanis.render.shadow.EntityDepth.upload(encoder, cascade,
              shadows.liveMatrix(cascade), inverseMainView);
            FeatureRenderDispatcher.PreparedFrame entityFrame = vulkanis$prepareEntityShadow(cascade);
            try (RenderPass renderPass = encoder.createRenderPass(() -> "vulkanis entity shadow",
              shadows.target(cascade).getColorTextureView(), Optional.empty(),
              shadows.target(cascade).getDepthTextureView(), OptionalDouble.empty())) {
              entityFrame.executeSolid(renderPass);
              entityFrame.executeTranslucent(renderPass);
            } finally {
              entityFrame.close();
              vulkanis$entityRenderBuffers.endFrame();
            }
          } catch (Exception e) {
            if (!errorLogged) { errorLogged = true; LOG.warn("vulkanis: entity shadow draw failed", e); }
          } finally {
            com.vulkanis.render.shadow.EntityDepth.setActiveCascade(-1);
          }
        }
      }
      vulkanis$shadowFrames++;
      if (drew) {
        // Restore the main view's batches however the main prepare is ordered
        // relative to this pass; it rebuilds from its own lists anyway.
        sectionManager.getChunkRenderer()
          .prepare(sectionManager.getRenderLists(), shadowCamera, false);
      }
    } finally {
      shadows.end();
      SodiumClientMod.options().performance.useBlockFaceCulling = culling;
    }
  }
}
