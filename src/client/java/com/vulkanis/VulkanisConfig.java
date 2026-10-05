package com.vulkanis;

import com.vulkanis.render.ShadowHookState;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.LoggerFactory;

public class VulkanisConfig implements ConfigEntryPoint {
  @Override
  public void registerConfigLate(ConfigBuilder builder) {
    builder.registerModOptions("vulkanis", "Vulkanis", "0.1.0")
      .addPage(builder.createOptionPage()
        .setName(Component.literal("Vulkanis"))
        .addOptionGroup(builder.createOptionGroup()
          .setName(Component.literal("Shaders"))
          .addOption(builder.createBooleanOption(Identifier.fromNamespaceAndPath("vulkanis", "vulkanis_shader"))
            .setName(Component.literal("Vulkanis Shaders"))
            .setTooltip(Component.literal("Master switch: composite + shadows. Off = vanilla rendering."))
            .setBinding(v -> ShadowHookState.setCompositeEnabled(v), ShadowHookState::compositeEnabled)
            .setStorageHandler(VulkanisConfig::save)
            .setDefaultValue(true))
          .addOption(builder.createExternalButtonOption(Identifier.fromNamespaceAndPath("vulkanis", "shader_select"))
            .setName(Component.literal("Shaders..."))
            .setTooltip(Component.literal("Select a shader pack from shaderpacks/."))
            .setScreenConsumer(current -> net.minecraft.client.Minecraft.getInstance()
              .setScreenAndShow(new com.vulkanis.screen.VulkanisShaderScreen(current))))));
  }

  public static void save() {
    try {
      com.vulkanis.config.ShaderConfig c = new com.vulkanis.config.ShaderConfig();
      c.enabled = ShadowHookState.compositeEnabled();
      c.save(new java.io.File(FabricLoader.getInstance().getGameDir().toFile(), "config/vulkanis.json"));
    } catch (Exception e) {
      LoggerFactory.getLogger("vulkanis").warn("vulkanis: config save failed", e);
    }
  }
}
