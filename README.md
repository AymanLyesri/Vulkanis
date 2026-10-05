# Vulkanis

A clean-room sun-shadow framework for Minecraft (Fabric) on the Vulkan renderer, plus the
**VulkanicShader** shader pack. Built for Minecraft 26.3 + Sodium 0.9.3.

The framework renders cascaded shadow maps from Sodium's chunk geometry; packs declare
settings in `pipeline.json` and GLSL in `shaders/`. No Iris required.

## Features

- 4-cascade sun shadows with frustum-fitted, texel-snapped shadow cameras
- Alpha-tested shadow casters (leaves and grass cast with holes, not blobs)
- Distance-scaled normal offset + gradient-corrected PCF receiver
- Live pack settings UI (I key) with per-setting reset buttons, no reboot needed
- Composite pass: tonemap, vignette, depth view
- Startup backend check (warns when not on Vulkan)

## Requirements

- Minecraft 26.3 (Fabric), Java 25
- Sodium 0.9.3 (shadow path; composite works without it)
- A Vulkan-capable GPU — the mod warns on OpenGL fallback
- Incompatible with the Sulkan mod: two shadow systems cannot own the terrain
  path at once. Disable/remove Sulkan to use Vulkanis shadows.

## Install

1. Build: `./gradlew build` (with `JAVA_HOME` pointing at Java 25)
2. Copy `build/libs/vulkanis-0.1.0.jar` into your instance's `mods/`
3. Copy `run/shaderpacks/VulkanicShader/` into your instance's `shaderpacks/`
4. Enable the VulkanicShader pack in the Vulkanis shader screen (I key)

## Controls

- **I** — shader pack screen (packs, per-pack settings, Show Depth)
- **K** — toggle shaders

## Inspiration

- [Iris](https://github.com/IrisShaders/Iris) — the shader-pack
  convention and shadow-pipeline concepts
- [Sulkan](https://github.com/mravatins/sulkanShaders) — cascaded shadow-map
  architecture (frustum-fitted cascades, staggered refits, cached caster plans);
  studied for approach, all code written fresh
- [Complementary Reimagined](https://github.com/ComplementaryDevelopment/ComplementaryReimagined) —
  shadow sampling and bias reference (rotated PCF kernels, distance-scaled offsets)

## License

GPL-3.0-only. See [LICENSE](LICENSE).
