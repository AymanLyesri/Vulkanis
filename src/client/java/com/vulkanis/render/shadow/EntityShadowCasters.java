package com.vulkanis.render.shadow;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.feature.phase.FeatureRenderPhase;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaternionf;

/**
 * Collects vanilla entity model submits into one {@link SubmitNodeStorage} per
 * cascade, routed by camera distance. Everything that is not a 3D model
 * (name tags, text, particles, outlines, gizmos, block overlays) is dropped:
 * only depth-casting geometry reaches the shadow maps.
 */
public final class EntityShadowCasters {
  private static final double RANGE_MARGIN = 8.0;

  private final SubmitNodeStorage[] storages = new SubmitNodeStorage[CascadeShadows.CASCADE_COUNT];

  public EntityShadowCasters() {
    for (int i = 0; i < storages.length; i++) {
      storages[i] = new SubmitNodeStorage();
    }
  }

  public void clear() {
    for (SubmitNodeStorage storage : storages) {
      storage.getSubmitsPerOrder().clear();
    }
  }

  public SubmitNodeStorage storage(int cascade) {
    return storages[cascade];
  }

  public int submitCount(int cascade) {
    int n = 0;
    for (SubmitNodeCollection collection : storages[cascade].getSubmitsPerOrder().values()) {
      for (FeatureRenderPhase<?> phase : collection.allPhases()) {
        if (!phase.isEmpty()) {
          n++;
        }
      }
    }
    return n;
  }

  public boolean hasSubmits(int cascade) {    for (SubmitNodeCollection collection : storages[cascade].getSubmitsPerOrder().values()) {
      for (FeatureRenderPhase<?> phase : collection.allPhases()) {
        if (!phase.isEmpty()) {
          return true;
        }
      }
    }
    return false;
  }

  public static boolean inRange(double distSq, float end, double w, double h) {
    double limit = end + RANGE_MARGIN + Math.max(w, h) * 2.0;
    return distSq <= limit * limit;
  }

  /** Collector writing only one cascade's storage (draws carry main-camera state;
   * our draw mixin swaps the camera part to the sun at draw time). */
  public SubmitNodeCollector cascadeCollector(int cascade) {
    return new Filter(cascade);
  }

  private final class Filter implements SubmitNodeCollector {
    private final int fixedCascade;

    Filter(int fixedCascade) {
      this.fixedCascade = fixedCascade;
    }

    @Override
    public OrderedSubmitNodeCollector order(int order) {
      return this;
    }

    @Override
    public <S> void submitModel(Model<? super S> model, S state, PoseStack poseStack,
        RenderType renderType, int light, int overlay, int outlineColor,
        net.minecraft.client.renderer.texture.UvMapping uvMapping, int packedOverlay) {
      storages[fixedCascade].submitModel(model, state, poseStack, renderType, light,
        overlay, outlineColor, uvMapping, packedOverlay);
    }

    @Override
    public void submitCustomGeometry(PoseStack poseStack, RenderType renderType,
        CustomGeometryRenderer renderer) {
      storages[fixedCascade].submitCustomGeometry(poseStack, renderType, renderer);
    }

    @Override
    public void submitShadow(PoseStack poseStack, float shadowRadius,
        List<EntityRenderState.ShadowPiece> shadowPieces) {
    }

    @Override
    public void submitNameTag(PoseStack poseStack, Vec3 offset, int light,
        Component component, boolean sneaking, int backgroundColor,
        CameraRenderState cameraRenderState) {
    }

    @Override
    public void submitText(PoseStack poseStack, float x, float y,
        FormattedCharSequence text, boolean shadow, Font.DisplayMode displayMode,
        int color, int backgroundColor, int light, int packedOverlay) {
    }

    @Override
    public void submitTextBackground(PoseStack poseStack, float x, float y, float width,
        float height, int color, Font.DisplayMode displayMode, int packedOverlay) {
    }

    @Override
    public void submitFlame(PoseStack poseStack, EntityRenderState entityRenderState,
        Quaternionf rotation) {
    }

    @Override
    public void submitLeash(PoseStack poseStack, EntityRenderState.LeashState leashState) {
    }

    @Override
    public <S> void submitCrumblingOverlay(Model<? super S> model, S state,
        PoseStack poseStack, RenderType renderType, int light, int overlay, int outlineColor,
        ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
    }

    @Override
    public void submitMovingBlock(PoseStack poseStack,
        MovingBlockRenderState movingBlockRenderState, int light) {
    }

    @Override
    public void submitBlockModel(PoseStack poseStack, RenderType renderType,
        List<BlockStateModelPart> parts, int[] tintCache, int light, int overlay,
        int outlineColor) {
    }

    @Override
    public void submitBreakingBlockModel(PoseStack poseStack,
        List<BlockStateModelPart> parts, int progress, boolean withOutline) {
    }

    @Override
    public void submitShapeOutline(PoseStack poseStack, VoxelShape shape,
        RenderType renderType, int color, float alpha, boolean fullBright) {
    }

    @Override
    public void submitItem(PoseStack poseStack, ItemDisplayContext displayContext,
        int light, int overlay, int outlineColor, int[] tints, ItemQuads quads,
        ItemStackRenderState.FoilType foilType) {
    }

    @Override
    public void submitQuadParticleGroup(QuadParticleRenderState quadParticleRenderState) {
    }

    @Override
    public void submitGizmoPrimitives(DrawableGizmoPrimitives.Group group,
        CameraRenderState cameraRenderState, boolean fullBright) {
    }
  }
}
