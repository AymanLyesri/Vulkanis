# Vulkanis — Agent Instructions

Fabric client mod: Vulkanis shader framework + VulkanicShader pack for Minecraft 26.3 + Sodium 0.9.3.
Repo: `~/Projects/vulkanis`. Instance: `~/.local/share/FreesmLauncher/instances/26.3/minecraft`.

## Rules

- **Never `git commit`** unless the user explicitly asks.
- **Never take screenshots** (`grim` etc.). The user judges visuals and reports back.
- The user restarts the game and joins the world themselves, unless they ask otherwise.
  Launcher can do it: `/opt/freesmlauncher/bin/freesmlauncher -l "26.3" -w "New World"`.
- Clean-room: Sulkan (`/tmp/opencode/sulkan-mrav`, mravatins fork — re-clone if missing)
  and ComplementaryReimagined (same parent dir) are reference for APPROACH only.
  Write all code fresh. Never copy their files. Sulkan targets Sodium 0.9.1, we target
  0.9.3 — verify every Sodium behavior against OUR sodium jar (bytecode via javap),
  never assume parity.

## Environment facts (verified, do not re-derive)

- Game can run on Vulkan (`com.mojang.renderpearl.backend.vulkan.VulkanDevice`) OR OpenGL
  (`com.mojang.renderpearl.backend.opengl.GlDevice`). After a crash Minecraft can silently
  fall back to OpenGL. `BackendCheck` classifies via `DeviceInfo.backendName()` (class-name
  fallback, UNKNOWN never latched); `BackendWarning` shows a startup toast + red chat line
  when NOT on Vulkan (warn-only, once per session).
  Shaders use `#ifdef VULKAN push_constant / #else plain uniforms` guards like Sodium's.
- Gradle MUST run under Java 25: `JAVA_HOME=~/.local/share/FreesmLauncher/java/java-runtime-epsilon`.
  System Java 27 breaks Gradle 9.4.1 (`Unsupported class file major version 71`).
- `sodium:config_api_user` entrypoint adds the Vulkanis page to Sodium's options.
  Stateful options REQUIRE `setStorageHandler` or the game crashes at startup.
- Sodium binds uniforms **by name** (`u_Globals`, `u_SectionTimeInfo`, `u_LightTex`, `u_BlockTex`).
  Extra uniforms must be bound mid-render (Redirect on a bind call), NOT in a TAIL inject (too late).
- Mixin `@Inject` handlers MUST declare the FULL target signature. Subsets fail; `require=0`
  skips silently. `LevelRenderer.renderLevel` does not exist in 26.3 (it's `render(...)`).
  `Minecraft.setScreen` does not exist (it's `setScreenAndShow`); there is no current-screen getter.
- `DefaultChunkRenderer.render` rejects unknown passes (`getPassIndex` throws). Use
  `DefaultTerrainRenderPasses.SOLID/CUTOUT` for shadow rendering, route pipelines via the
  ACTIVE-cascade flag in a `compileProgram` HEAD hook.
- Vanilla lazy pipeline compile (`GameRenderer$1` source) can NOT see our jar assets
  (`FileNotFoundException`). Our terrain pipelines compile via our own `ShaderSource`
  (reads `/assets/vulkanis/shaders/...` from our jar) + insert into vanilla's
  `PipelineCache` via the `setCurrentPipelineCache` swap trick in `CascadeShadows.ensureCompiled`.
- Sodium chunk vertices are **camera-relative** (region offsets minus camera pos).
  Any light-space matrix must therefore be built around the camera origin, NOT world coords.
- Main depth texture is transient: sample/copy it ONLY inside a `FramePass` with a declared
  `reads(main)` dependency. Sampling it after the frame yields zeros.
- `DefaultChunkRenderer.render` reuses the per-region cached batch when non-empty and
  only consults your lists otherwise; only `prepare()` rebuilds batches from a given
  list set. The cache is keyed per region per pass and SHARED with the main render
  (we use the same SOLID/CUTOUT identities). So the shadow pass must `prepare()` its
  own lists before drawing, then restore main batches via `prepare(getRenderLists())`
  afterwards (order-independent). Skipping prepare silently draws player-culled batches.
- Shadow-map render passes into one target MUST all use LOAD semantics
  (`OptionalDouble.empty()`); a clear value on the second pass wipes the first pass's
  depths (this once left only cutout-pass casters in the map). Clear explicitly once
  before the loop.
- GLSL (glslang backend): samplers can NOT be assigned to locals or selected via a
  variable — branch per cascade and call `texture()`/`textureSize()` directly on the
  named uniforms. A sampler local fails backend compile, the pipeline never loads,
  and `getCompiledPipeline` throws on the render thread (crash, not fallback).
- The Sulkan mod (if installed AND its shadows enabled) hijacks the same terrain
  pipeline swap and breaks our shadows with no error of ours. `ShadowHookState`
  stands our hooks down with an explicit error when `sulkan` is loaded. The user must
  run exactly one shadow system. (Duplicate stray jars like `*.jar.duplicate` in
  `mods/` should be deleted on sight.)
- UBO structs must be std140-exact (missing `Pad0` once shifted every matrix on the GPU).
- Fabric keybind API is `keymapping.v1.KeyMappingHelper` + vanilla `KeyMapping(Type.KEYBOARD,
InputConstants.KEY_*, Category.register(id))`. `org.lwjgl.glfw` is NOT on the compile classpath.
- 26.3 GUI is stateless (`extractRenderState`); Screens = `init()` + widgets, no `render()` override.
- `TerrainRenderPass` ctor `(ChunkSectionLayer, boolean, boolean)` exists, but custom instances
  break `getPassIndex` — don't use them with `DefaultChunkRenderer.render`.

## Build / deploy / verify

```bash
cd ~/Projects/vulkanis
export JAVA_HOME=~/.local/share/FreesmLauncher/java/java-runtime-epsilon PATH="$JAVA_HOME/bin:$PATH"
./gradlew build   # check exit code explicitly; NEVER `... | grep` the result (grep masks failures)
cp build/libs/vulkanis-0.1.0.jar <instance>/minecraft/mods/
# pack files (read from disk at runtime, no rebuild needed for .fsh/.vsh/.json edits):
cp run/shaderpacks/VulkanicShader/shaders/* <instance>/minecraft/shaderpacks/VulkanicShader/shaders/
cp run/shaderpacks/VulkanicShader/pipeline.json <instance>/minecraft/shaderpacks/VulkanicShader/
```

- `./gradlew clean build` when resources change but the jar looks stale (verify with `unzip -l`).
- Keep `<instance>/minecraft/shaderpacks/VulkanicShader/` mirroring `run/shaderpacks/VulkanicShader/`
  (plus the user's own `settings.json`). Delete strays.
- Verify via log, never screenshots:
  `grep -a "vulkanis" <instance>/minecraft/logs/latest.log | tail`
  `grep -acE "Mixin apply.*failed|ShaderCompileException|Failed to find or load pipeline" <log>`
- One-time `LOG.info` markers exist for every hook; add more freely, remove before finishing.
- `pkill -f <pattern>` matches your own shell and kills YOU. Use `pgrep -f "minecraft-26\.3-clien[t]"`.

## Architecture (what lives where)

- `VulkanisClient` — init, pack select/hot-reload (`selectPack` rebuilds composite live, no reboot),
  keybinds (I = shader screen, K = toggle), `refreshShaderValues`, `shadowStrength/Bias()`,
  `maxShadowDistance/ShadowMapSizeSetting()`, backend-warning wiring (startup toast via tick,
  red chat via JOIN).
- `BackendCheck`/`BackendWarning` — device-name backend classifier (unit-tested) + warn-once toast/chat.
- `pack/` — `PackLoader` (strict `pipeline.json` validation: rejects trailing commas, bad shadowSize,
  shadow-shader packs require `"api": 1`), `PackSetting`/`PackValues` (`settings.json` per pack,
  `{{token}}` substitution into GLSL), `ShaderIncludes` (`#include <vulkanis/...>` resolver),
  `PackManager` (keep-last-good), `ShaderLibrary`, `PipelineSpec` (`API_VERSION=1`, `API_CASCADES=3`), `ShaderConfig`.
- `render/CompositeRenderer` — fullscreen tonemap/vignette/depth-view pass, 192B `SamplerInfo` UBO
  (OutSize/InSize/ShowDepth/Proj22/Proj32/SunDir/CamPos/InvViewProj/ViewProj), per-frame buffer.
- `render/ShadowDepthStore` — persistent main-depth copy (in-frame pass). `copyShadowMap`
  is currently uncalled (debug views removed); `DepthCopyMixin` always copies main depth.
- `render/shadow/` — `CascadeShadows` (3 frustum-fitted cascades over Shadow Distance,
  blended uniform/log splits, texel-snapped centers, staggered refits 3/8/16 with
  movement+direction gates, camera-delta slide between refits, per-section NDC
  membership test, per-cascade targets {S,S/2,S/4}, 288B multi-cascade UBO,
  vanilla-cache pipeline insert),
  `CascadePlans` (cached per-cascade caster lists from all loaded regions),
  `VulkanisShadowPipelines` (caster/receiver builders; receiver binds 3 samplers +
  UBO, locations carry `_cascades3`, injects `VULKANIS_CASCADES=3` + `VULKANIS_API_VERSION=1`),
  `VulkanisShaderSources` (pack-owned GLSL; absent keys = vanilla, never jar shadows),
  `ShadowGlobals` (own u_Globals buffer, one instance per cascade),
  `FrameMatrices`/`ShadowManager` (pure math, unit-tested; composite uses them).
- `mixin/` — `VulkanisShadowPassMixin` (shadow FramePass at `addMainPass` HEAD; draws
  only refit cascades, prepares batches from our lists, restores main batches after),
  `VulkanisTerrainMixin` (`compileProgram` swap), `VulkanisUniformMixin` (mid-render Redirect
  binds 3 maps + UBO), `DepthCopyMixin`, `SkySunMixin` (sunAngle→dir),
  `WorldCompositeMixin` (captures CameraRenderState),
  `sodium/SodiumWorldRendererAccessor`.
- `screen/VulkanisShaderScreen` — pack list + per-pack settings steppers with per-setting
  Reset buttons + Show Depth button.
- `VulkanisConfig` — Sodium option page: Vulkanis-Shader toggle + Shaders... button.
- `assets/vulkanis/shaders/vulkanis/` — `vulkanis_shadow.vsh/.fsh` (atlas
  alpha-tested depth caster: 0.5 cutout / 0.01 translucent discard, opaque solid),
  `vulkanis_terrain_receiver.vsh/.fsh` (REFERENCE ONLY, engine never loads: cascade select
  by distance + 10% boundary blend + 12% far fade, per-cascade texel bias, pack-owned
  filter 0/1/2 via `filterRadius`/`filterMode`, uncapped Complementary-style normal offset,
  micro depth bias, skylight/blocklight-weighted strength + fog). Inlined Sodium snippets (no
  `#moj_import` — not preprocessed on our path). Jar-embedded: full restart to reload.
- `assets/vulkanis/shaders/include/` — engine-shipped pack includes: `poisson.glsl`
  (Poisson-16 taps, IGN rotation), `shadow_common.glsl` (shadow coord, depth gradient).
- `run/shaderpacks/VulkanicShader/` — user-facing pack, api 1: `pipeline.json` (settings:
  shadowStrength/Bias, shadowFilterMode/Radius, maxShadowDistance, vignetteStrength, shadowMapSize),
  `terrain.fsh` (reference api-1 receiver: rotated Poisson-16), `composite.vsh/.fsh`
  (tonemap/vignette/Show Depth; dead screen-space `sunShadow`
  raymarch removed 2026-10-05; orphan `world.*`/`shadow.vert`/`composite.frag`
  deleted, `PackLoader` now requires `composite.vsh`+`fsh`).
  Pack `.fsh`/`.json` edits apply live via `selectPack` (no restart); instance dir mirrors `run/`.

## Current state (2026-10-05)

WORKING: framework load, Vulkanis Sodium page + toggle + Shaders button, shader screen with live
per-pack settings + per-setting Reset buttons, I/K keybinds, composite tonemap/vignette,
depth copy + Show Depth, pack hot-reload without reboot, strict pack validation, optional
Sodium dep, full unit suite green (62).
WORKING: 3-cascade sun shadows on Vulkan (clean-room rewrite, Sulkan approach, all code fresh):
frustum-fitted texel-snapped cascades over Shadow Distance, staggered refits, cached caster
plans, alpha-tested casters (leaves/grass cast with holes), cascade blend/fade receiver,
pack-owned PCF (single-tap / 3x3 box / rotated Poisson-16 via Shadow Filter setting),
uncapped Complementary-style normal offset. Verified in-session: batch-cache prepare/restore
fix (shadow was drawing player-culled batches), LOAD-semantics multi-pass shadow targets
(solid depths were wiped by the cutout clear), sampler-local GLSL crash fix (branch per
sampler), Sulkan-mod conflict guard (ours stands down with an error; user runs one system).
WORKING (2026-10-06): Vulkanis as shader provider (api 1): versioned pack contract
(`PACK_CONTRACT.md`), engine-injected `VULKANIS_CASCADES`/`VULKANIS_API_VERSION` defines,
`#include <vulkanis/...>` helpers (poisson, shadow_common), per-frame filter params
(radius + mode) in ShadowParams, strict api validation with keep-last-good.
Master toggle gates all shadow hooks (off = vanilla). Backend warning toast + red chat on GL.
Repo: README.md, GPL-3.0-only (LICENSE, fabric.mod.json).
OPEN (not bugs, future work): entity shadows, moon handover, shadow-map distortion or
smaller default radius for sharpness (steps 5/6), world-locked texel grid for
translation swim. Old suspect list retired:
uniforms.update() suspicion dropped (own ShadowGlobals + shared read-only time info holds up).
