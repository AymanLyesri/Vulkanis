package com.vulkanis.render.shadow;

import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import net.minecraft.resources.Identifier;

public final class VulkanisShaderSources {
  private final com.vulkanis.pack.ShaderLibrary packShaders;

  public VulkanisShaderSources() {
    this(com.vulkanis.VulkanisClient.packShaders());
  }

  public VulkanisShaderSources(com.vulkanis.pack.ShaderLibrary packShaders) {
    this.packShaders = packShaders;
  }

  /** Pack GLSL when the pack provides this stage, null otherwise (no silent fallback:
   * a pack without its own shadow shaders gets vanilla rendering, never jar shadows). */
  public String vertexFor(String jarKey) {
    return packText(jarKey, "vertex");
  }

  public String fragmentFor(String jarKey) {
    return packText(jarKey, "fragment");
  }

  private String packText(String jarKey, String kind) {
    String base = jarKey.equals("vulkanis_shadow") ? "pack/shadow"
        : jarKey.equals("vulkanis_terrain_receiver") ? "pack/terrain" : null;
    if (base == null) {
      return null;
    }
    String key = com.vulkanis.pack.ShaderLibrary.key("vulkanis", base, kind);
    synchronized (packShaders) {
      return packShaders.has(key) ? packShaders.get(key) : null;
    }
  }

  public ShaderSource sourceFor(String vertexKey, String fragmentKey) {
    return new ShaderSource() {
      @Override
      public String getShader(Identifier id, ShaderType type) {
        String path = id.getPath();
        boolean vertex = "vertex".equals(type.getName());
        if (path.endsWith("vulkanis/entity_depth")) {
          return vertex ? EntityDepth.text("vertex") : EntityDepth.text("fragment");
        }
        if (path.endsWith("vulkanis_shadow")) {
          return vertex ? vertexFor("vulkanis_shadow") : fragmentFor("vulkanis_shadow");
        }
        if (path.endsWith("vulkanis_terrain_receiver")) {
          return vertex ? vertexFor("vulkanis_terrain_receiver") : fragmentFor("vulkanis_terrain_receiver");
        }
        return null;
      }

      @Override
      public ShaderSource.CachedIncludeSource getInclude(Identifier id) {
        return null;
      }

      @Override
      public void close() {
      }
    };
  }
}
