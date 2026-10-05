package com.vulkanis.mixin.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = SodiumWorldRenderer.class, remap = false)
public interface SodiumWorldRendererAccessor {
  @Accessor("renderSectionManager")
  RenderSectionManager vulkanis$renderSectionManager();

  @Accessor("uniformBufferManager")
  UniformBufferManager vulkanis$uniformBufferManager();
}
