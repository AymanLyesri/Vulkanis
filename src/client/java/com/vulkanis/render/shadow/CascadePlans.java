package com.vulkanis.render.shadow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionFlags;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import org.joml.Vector3f;

/**
 * Per-cascade caster lists, cached across frames. A plan is reused while the
 * camera stays within the list movement budget and neither the light nor the
 * view direction swung past the refit dots; otherwise the cascade's sections
 * are re-tested against the cascade frustum.
 */
public final class CascadePlans {
  private final Plan[] plans = new Plan[CascadeShadows.CASCADE_COUNT];

  private static final class Plan {
    List<ChunkRenderList> lists = List.of();
    final ShadowLists iterable = new ShadowLists();
    double x;
    double y;
    double z;
    final Vector3f light = new Vector3f();
    final Vector3f forward = new Vector3f();
    float end;
    boolean filled;
  }

  private static final class ShadowLists implements ChunkRenderListIterable {
    List<ChunkRenderList> lists = List.of();

    @Override
    public Iterator<ChunkRenderList> iterator(boolean reverse) {
      if (reverse) {
        List<ChunkRenderList> flipped = new ArrayList<>(lists);
        Collections.reverse(flipped);
        return flipped.iterator();
      }
      return lists.iterator();
    }
  }

  public CascadePlans() {
    for (int i = 0; i < plans.length; i++) {
      plans[i] = new Plan();
    }
  }

  static boolean planMatches(Plan plan, double x, double y, double z,
      Vector3f light, Vector3f forward, float end, float moveLimit) {
    if (!plan.filled || Math.abs(plan.end - end) > 0.5f) {
      return false;
    }
    double dx = x - plan.x;
    double dy = y - plan.y;
    double dz = z - plan.z;
    if (dx * dx + dy * dy + dz * dz > moveLimit * moveLimit) {
      return false;
    }
    return plan.light.dot(light) >= 0.9925f && plan.forward.dot(forward) >= 0.9925f;
  }

  public ChunkRenderListIterable planFor(RenderSectionManager sections, CascadeShadows shadows,
      int cascade, boolean force) {
    Plan plan = plans[cascade];
    Vector3f light = shadows.lightDirection();
    Vector3f forward = shadows.cameraForward();
    float end = shadows.cascadeEnd(cascade);
    if (!force && planMatches(plan, shadows.cameraX(), shadows.cameraY(), shadows.cameraZ(),
        light, forward, end, shadows.listMoveLimit())) {
      return plan.iterable;
    }
    List<ChunkRenderList> lists = new ArrayList<>();
    for (RenderRegion region : sections.regions.getLoadedRegions()) {
      ChunkRenderList list = new ChunkRenderList(region);
      for (int i = 0; i < RenderRegion.REGION_SIZE; i++) {
        int flags = region.getSectionFlags(i);
        if ((flags & RenderSectionFlags.MASK_HAS_BLOCK_GEOMETRY) == 0) {
          continue;
        }
        int sx = region.getChunkX() + net.caffeinemc.mods.sodium.client.render.chunk.LocalSectionIndex.unpackX(i);
        int sy = region.getChunkY() + net.caffeinemc.mods.sodium.client.render.chunk.LocalSectionIndex.unpackY(i);
        int sz = region.getChunkZ() + net.caffeinemc.mods.sodium.client.render.chunk.LocalSectionIndex.unpackZ(i);
        if (shadows.sectionInCascade(cascade, sx << 4, sy << 4, sz << 4)) {
          list.add(i);
        }
      }
      if (list.getSectionsWithGeometryCount() > 0) {
        lists.add(list);
      }
    }
    plan.lists = List.copyOf(lists);
    plan.iterable.lists = plan.lists;
    plan.x = shadows.cameraX();
    plan.y = shadows.cameraY();
    plan.z = shadows.cameraZ();
    plan.light.set(light);
    plan.forward.set(forward);
    plan.end = end;
    plan.filled = true;
    return plan.iterable;
  }

  public int casterSectionCount(int cascade) {
    int total = 0;
    for (ChunkRenderList list : plans[cascade].lists) {
      total += list.getSectionsWithGeometryCount();
    }
    return total;
  }
}
