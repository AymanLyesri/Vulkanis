package com.vulkanis.pack;
public record PipelineSpec(String id, String name, String version, int shadowSize, boolean shadowsEnabled,
    boolean hasShadowShaders, boolean hasTerrainShaders) {}
