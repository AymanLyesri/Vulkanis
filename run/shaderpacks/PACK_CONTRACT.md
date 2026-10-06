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

## Composite passes (optional)

No `passes` key = legacy single pass: `composite.vsh` + `composite.fsh`,
`InSampler` (game picture) + `InDepth`, drawn to the screen.

With `passes`, the pack declares a fullscreen graph (max 8 passes, max 4
transient targets). Declaration order = execution order. The last pass MUST
be named `final` and renders to the screen; earlier passes render to
transient targets at `size` scale (`0 < size <= 1`, default `1.0`).

```json
"passes": [
  {"name": "bright", "frag": "bright.fsh", "size": 0.25},
  {"name": "blur", "frag": "blur.fsh", "in": ["bright"], "size": 0.25},
  {"name": "final", "frag": "composite.fsh", "in": ["main", "blur"]}
]
```

- `in` defaults to `["main"]`. Names resolve to `main` (game picture),
  `depth` (scene depth, bound as `InDepth`), or an earlier pass output.
  Forward references are rejected. Reserved pass names: `main`, `depth`,
  `composite`.
- A pass output named `x` is sampled in later passes as `InX`
  (capitalized: `blur` -> `InBlur`). All passes share one bind layout:
  `InSampler`, `InDepth`, `SamplerInfo`, plus every transient `InX`.
- Every pass reuses `composite.vsh` (fullscreen triangle, `texCoord` 0..1);
  only the `.fsh` differs per pass.

## Composite inputs

| Name | Type | Meaning |
|---|---|---|
| `InSampler` | `sampler2D` | game picture (LINEAR, clamp) |
| `InDepth` | `sampler2D` | scene depth (NEAREST) |
| `In<Name>` | `sampler2D` | pass output (LINEAR), graph packs only |
| `SamplerInfo` | std140 UBO | `OutSize`, `InSize`, `ShowDepth`, `Proj22`, `Proj32`, **`Exposure`** (was `Pad0`: day/night smoothed multiplier, 1.0 day .. 1.8 night), `SunDir`, `CamPos`, `InvViewProj`, `ViewProj`. 192 bytes, offsets unchanged. |

## Settings categories (optional)

Each setting takes an optional `"category"` (any string, lowercased;
missing/blank = `"general"`). The Shaders screen renders one tab per
category. Optional top-level `"categories": [{"id": "...", "label": "..."}]`
fixes tab order/labels; without it, tabs follow first-seen order.
`"category"` must be a string when present, else the pack is rejected.
`settings.json` stays keyed by setting id — categories never touch saves.

## History passes (measured exposure)

A pass with `"history": true` is the feedback loop for frame-to-frame state
(exposure, ...). Rules, all enforced at load with named errors:

- At most 2 history passes per graph. It must not be named `final`.
- Only a history pass may name itself in `in` — that reads the PREVIOUS
  frame (ping-pong). Any other self-reference is rejected.
- History targets are always 1x1 and survive window resize (no adaptation
  restart). Switching graphs or re-selecting the pack restarts adaptation:
  expect a brief exposure sweep, eye-adapting, not a bug.
- For later passes, `In<Name>` yields the value written THIS frame.
  While the history pass renders, its own `In<Name>` yields last frame.
- `size` on a history pass is ignored.

Exposure curve (pack-side, mirrors `ExposureCurve.apply`):

```glsl
float avg = texture(InMeasure, vec2(0.5)).r;
float exposure = clamp({{exposureKey}} / max(avg, 0.001), {{exposureMin}}, {{exposureMax}});
```

Defaults: key 0.35, min 0.5, max 3.0, adapt speed 0.15. Tune in the
Exposure tab. The `SamplerInfo.Exposure` UBO float (day/night smoothed)
is still bound for packs that want it; Vulkanic final uses measured.

## Two-stage eye adaptation

`measure` (fast tracker) follows the scene; `slow` (second history
pass) follows `measure` with asymmetric rates — `adaptBright` when the
scene brightens (squint, fast), `adaptDark` when it darkens (night vision,
slow). `final` reads the slow value. The lag between the stages IS the
delay: no timers, no extra uniforms.

Pass names double as sampler names (`In` + capitalized: `slow` →
`InSlow`). Keep them short, lowercase, and prefix-distinct from other
passes/prefixes of each other — the backend matches them exactly.

- At most 2 history passes per graph. Both survive resize; both restart on
  graph switch (one sweep).
- `mix(prev, cur, rate)` with strict `>`: ties take the dark rate.
  Validated setting minimums (bright 0.05, dark 0.01) forbid a frozen rate.

## Metering + bias

`measure` meters center-weighted (quadratic falloff from the crosshair),
so gaze direction drives exposure. `exposureBias` multiplies the curve
result inside the clamp — the manual test knob (1.0 = neutral).
