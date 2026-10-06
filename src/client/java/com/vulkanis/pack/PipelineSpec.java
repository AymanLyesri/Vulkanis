package com.vulkanis.pack;
import java.util.List;
public record PipelineSpec(String id, String name, String version, int shadowSize, boolean shadowsEnabled,
    boolean hasShadowShaders, boolean hasTerrainShaders, PassGraph passes, List<PackCategories.Category> categories) {}
