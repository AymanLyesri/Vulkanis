# Vulkanis Pack Shader Contract

Pack-owned shadows: your pack's `shadow.vsh/.fsh` (caster) and `terrain.vsh/.fsh`
(receiver) replace the jar shaders. The engine still owns cascade fitting, depth
targets, and all uniform bindings. Your code adapts to the names below — they are
fixed and will not be renamed.

## Rule

- Pack has `shadow.vsh`+`shadow.fsh` AND `terrain.vsh`+`terrain.fsh` → your shadows run.
- Pack missing any of the four → vanilla rendering, no shadows. No silent fallback.
- Broken GLSL → vanilla rendering + one-line error in the Shaders screen. Never a crash.
- `{{settingId}}` tokens in your shaders are substituted from `pipeline.json` settings.

## Uniforms (declare exactly these names)

Caster (`shadow.*`):

| Name | Type |
|---|---|
| `u_LightTex` | `sampler2D` (lightmap) |
| `u_BlockTex` | `sampler2D` (block atlas) |
| `u_Globals` | std140 UBO (Sodium matrices/fog, same layout as vanilla Sodium) |
| `u_SectionTimeInfo` | texel buffer (`isamplerBuffer`, `R32_SINT`) |

Receiver (`terrain.*`): all of the above, plus:

| Name | Type |
|---|---|
| `VulkanisShadowMap0..3` | `sampler2D` (depth, nearest-filtered, one per cascade) |
| `VulkanisShadowData` | std140 UBO, 352 bytes (below) |

## `VulkanisShadowData` (offsets in bytes)

| Offset | Field | Meaning |
|---|---|---|
| 0 / 64 / 128 / 192 | `mat4 CascadeMatrix[4]` | sun view-proj per cascade, camera-relative |
| 256 / 272 / 288 / 304 | `vec4 CascadeInfo[4]` | (texelWorldSize, previousEnd, end, depthRange) |
| 320 | `vec4 LightDirection` | xyz = sun dir, w unused |
| 336 | `vec4 ShadowParams` | (strength, microBias, unused, steps) |

Pick cascade `i` by view distance: first `i` with `dist <= CascadeInfo[i].z`.
Project: `clip = CascadeMatrix[i] * vec4(cameraRelativePos, 1)`, uv = `clip.xy/clip.w*0.5+0.5`.

## Vertex inputs (fixed by Sodium)

```glsl
layout(location = 0) in uvec2 a_Position;   // 20-bit compressed position
layout(location = 1) in vec4 a_Color;
layout(location = 2) in uvec2 a_TexCoord;    // 15-bit compressed UV + bias bits
layout(location = 3) in uvec4 a_LightAndData;
```

Push constants (Vulkan) / uniforms (OpenGL), same names:

```glsl
vec3 u_RegionOffset; int u_CurrentTime; uint u_RegionID; // 20 bytes total
```

Copy the decode block from the jar reference
(`src/client/resources/assets/vulkanis/shaders/vulkanis/`) — do not re-derive it.

## Defines the engine sets

| Shader | Define | Value |
|---|---|---|
| both | `USE_VERTEX_COMPRESSION` | always |
| caster + receiver | `ALPHA_CUTOUT` | `0.5` cutout pass, `0.01` translucent pass, absent on solid |
| receiver | `USE_FOG` | always |
| receiver | `VULKANIS_CASCADE_COUNT` | `4` |

Receiver pipelines use QUADS topology, culling on; caster pipelines depth `LESS_THAN_OR_EQUAL`, no culling.

## Reference

The jar shaders are the working example — copy and simplify them. Start from
`vulkanis_terrain_receiver.fsh` and delete what you don't need (e.g. reduce PCF
to 1 tap) before writing your own.
