package com.vulkanis;

/**
 * ModMenu integration. Compile-only: this class loads only when ModMenu reads
 * the "modmenu" entrypoint, so a missing/disabled ModMenu can never crash us.
 */
public class VulkanisModMenu implements com.terraformersmc.modmenu.api.ModMenuApi {
  @Override
  public com.terraformersmc.modmenu.api.ConfigScreenFactory<?> getModConfigScreenFactory() {
    return parent -> new com.vulkanis.screen.VulkanisShaderScreen(parent);
  }
}
