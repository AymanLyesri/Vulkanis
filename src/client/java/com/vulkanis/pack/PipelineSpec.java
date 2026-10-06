package com.vulkanis.pack;
import java.util.List;
public record PipelineSpec(String id, String name, String version, int shadowSize, boolean shadowsEnabled,
    boolean hasShadowShaders, boolean hasTerrainShaders, PassGraph passes, List<PackCategories.Category> categories,
    int api) {
  /** Shadow API level the engine implements. Packs declare "api" in pipeline.json. */
  public static final int API_VERSION = 1;
  /** Cascade count api 1 packs must target (samplers, UBO arrays, defines). */
  public static final int API_CASCADES = 3;
}
