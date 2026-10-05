# Full Iris-Style Pack Shaders Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Packs author their own shadow-caster and terrain-receiver GLSL, loaded from disk and hot-reloaded like `composite.fsh` is today, while the engine keeps cascade math, targets, and uniform bindings.

**Architecture:** Engine owns the *plumbing* (cascade fitting in `CascadeShadows`, 4 depth targets, `VulkanisShadowData` UBO, bind-group layouts, Sodium vertex format, compile-and-insert trick); packs own the *code* (`shadow.vsh/.fsh`, `terrain.vsh/.fsh` in the pack dir). A fixed uniform contract + jar fallback keeps old packs working.

**Tech Stack:** Java (Gradle 9.4.1, Java 25 toolchain), GLSL 460, Gson pack JSON, JUnit 5, Sodium 0.9.3 APIs.

**Spec:** User request 2026-10-05: "i want the iris style approach", flavor 2 (pack defines shadow behavior, not just numbers). Scope ruling: packs author shader *code* for the engine's existing passes (caster SOLID/CUTOUT, receiver, composite) — NOT new pass types or render-graph changes. **Fallback ruling (user-confirmed 2026-10-06): pack owns shadows outright — a pack without `shadow.*`+`terrain.*` gets NO shadows (vanilla look), never jar shadows behind its back. Broken pack GLSL also falls back to vanilla, never crashes. Jar GLSL stays on disk as reference code for authors only.**

## Global Constraints

- Never `git commit` unless the user explicitly asks.
- Gradle MUST run under Java 25: `JAVA_HOME=~/.local/share/FreesmLauncher/java/java-runtime-epsilon`.
- `./gradlew build`: check exit code explicitly; NEVER pipe through `grep`.
- `SamplerInfo` (192B) and `VulkanisShadowData` (352B) UBO layouts are FROZEN — pack shaders adapt to them, not vice versa.
- Sulkan-mod conflict guard (`ShadowHookState`) stays active; no changes there.
- Mixin `@Inject` handlers MUST declare the FULL target signature; `require=0` where vanilla may vary.
- Clean-room: all pack example code written fresh, never copied from Sulkan/Complementary.

## Review Focus

- Pack with broken GLSL must fall back to vanilla rendering, never crash the frame — needs a test + in-game error line.
- Pack missing `terrain.*` or `shadow.*` must get VANILLA (no shadows), never jar shadows — gated in `PackManager.shadowsActive`, pinned by `ShadowsGateTest.offWhenPackLacksShadowShaders/TerrainShaders`.
- `{{token}}` substitution must apply to the new pack shaders exactly like composite (unsubstituted token = silently wrong shader).
- Hot-reselect must never leak pipelines or serve stale GLSL after an edit.
- Old packs (VulkanicShader, DemoShader: composite-only) must keep working unchanged.

---

### Task 1: Pack format + validation for shadow/terrain shaders

**Files:**
- Modify: `src/client/java/com/example/vulkan/pack/PackLoader.java:13-33`
- Modify: `src/client/java/com/example/vulkan/pack/PipelineSpec.java`
- Test: `src/test/java/com/example/vulkan/pack/PackValidationTest.java` (append)

**Interfaces:**
- Consumes: pack dir layout (`pipeline.json` + `shaders/`).
- Produces: `PipelineSpec(id, name, version, shadowSize, shadowsEnabled, hasShadowShaders, hasTerrainShaders)` — the two booleans are the **shadow gate**: `PackManager.shadowsActive` requires both (no pack shaders = vanilla, never jar fallback).

- [ ] **Step 1: Write the failing test**

Append to `PackValidationTest.java`:
```java
@Test public void detectsPackOwnedShadowShaders() throws Exception {
  File dir = pack("irispack", "{\"id\":\"iris\",\"shadowSize\":2048}");
  new File(new File(dir, "shaders"), "shadow.vsh").createNewFile();
  new File(new File(dir, "shaders"), "shadow.fsh").createNewFile();
  new File(new File(dir, "shaders"), "terrain.vsh").createNewFile();
  new File(new File(dir, "shaders"), "terrain.fsh").createNewFile();
  PipelineSpec s = PackLoader.loadSpec(dir);
  assertTrue(s.hasShadowShaders());
  assertTrue(s.hasTerrainShaders());
}
@Test public void compositeOnlyPackStillValidates() throws Exception {
  File dir = pack("plainpack", "{\"id\":\"plain\",\"shadowSize\":2048}");
  PipelineSpec s = PackLoader.loadSpec(dir);
  assertFalse(s.hasShadowShaders());
  assertFalse(s.hasTerrainShaders());
}
```
(`pack()` helper already creates composite.vsh/fsh.)

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=~/.local/share/FreesmLauncher/java/java-runtime-epsilon PATH="$JAVA_HOME/bin:$PATH" && ./gradlew test --tests "com.vulkanis.pack.PackValidationTest"`
Expected: FAIL, `hasShadowShaders()` undefined on `PipelineSpec`.

- [ ] **Step 3: Minimal implementation**

Add `hasShadowShaders, hasTerrainShaders` booleans to the `PipelineSpec` record; in `PackLoader.loadSpec`, set them by file existence (`shaders/shadow.vsh`+`shadow.fsh` both present; same for `terrain.*`). Do NOT reject packs lacking them (fallback covers it). Update positional constructor call sites in `ShadowResolveTest.java:9,17-19` and `ShadowsGateTest.java:6` with `, false, false`.

- [ ] **Step 4: Run test to verify it passes**

Run: same command as Step 2.
Expected: PASS.

### Task 2: Load pack shadow/terrain GLSL from disk with token substitution

**Files:**
- Modify: `src/client/java/com/example/vulkan/VulkanisClient.java:81-104` (generalize `loadComposite`)
- Test: extend `src/test/java/com/example/vulkan/pack/RealPackTest.java`

**Interfaces:**
- Consumes: `PackValues.apply` (unchanged, already generic), `ShaderLibrary` (unchanged).
- Produces: `ShaderLibrary` holding keys `vulkanis:pack/shadow:vertex|fragment` and `vulkanis:pack/terrain:vertex|fragment` when the pack provides them; absent otherwise (fallback signal).

- [ ] **Step 1: Write the failing test**

Append to `RealPackTest.java`:
```java
@Test public void missingPackShadersLeaveLibraryEmpty() {
  ShaderLibrary lib = new ShaderLibrary();
  assertFalse(lib.has(ShaderLibrary.key("vulkanis", "pack/shadow", "fragment")));
}
```
(This pins the absent-means-fallback contract; the loader behavior is pinned in Task 3's test.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.vulkanis.pack.RealPackTest"` (with JAVA_HOME export as above).
Expected: PASS immediately (contract already holds — characterization guard, note in ledger).

- [ ] **Step 3: Write minimal implementation**

In `VulkanisClient`, add `loadPackShaderBlock(File shadersDir, String base, PackValues values, List<PackSetting> settings, ShaderLibrary lib)` that reads `base + ".vsh"`/`base + ".fsh"`, applies token substitution, and puts both keys; called for `base = "shadow"` and `"terrain"` inside `loadComposite` (rename to `loadPack` only if all call sites — `onInitializeClient:50`, `selectPack:168` — are updated together; otherwise keep the name and ledger the ruling). Missing files = skip silently (fallback). Loaded GLSL stored in a static `ShaderLibrary packShaders` field with a getter for Task 3.

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests "com.vulkanis.pack.*"`
Expected: PASS.

### Task 3: Serve pack GLSL to the shadow pipelines (no jar fallback)

**Files:**
- Modify: `src/client/java/com/example/vulkan/render/shadow/VulkanisShaderSources.java:11`
- Test: new `src/test/java/com/example/vulkan/render/shadow/PackShaderFallbackTest.java`

**Interfaces:**
- Consumes: `VulkanisClient.packShaders()` library from Task 2.
- Produces: `vertexFor`/`fragmentFor` return pack GLSL or null. Null (pack absent — unreachable when the Task 1 gate holds, defense in depth otherwise) fails compile safely into vanilla fallback. The `shadowsActive` gate in `PackManager` is what keeps composite-only packs vanilla; jar files on disk are reference-only.

- [ ] **Step 1: Write the failing test**

```java
package com.vulkanis.render.shadow;
import com.vulkanis.pack.ShaderLibrary;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
public class PackShaderFallbackTest {
  @Test public void prefersPackGlslWhenPresent() {
    ShaderLibrary lib = new ShaderLibrary();
    lib.put(ShaderLibrary.key("vulkanis", "pack/shadow", "fragment"), "PACK_FRAG");
    VulkanisShaderSources src = new VulkanisShaderSources(lib);
    assertEquals("PACK_FRAG", src.fragmentFor("vulkanis_shadow"));
  }
  @Test public void noSilentFallbackWhenPackAbsent() {
    VulkanisShaderSources src = new VulkanisShaderSources(new ShaderLibrary());
    assertNull(src.fragmentFor("vulkanis_shadow"));
    assertNull(src.vertexFor("vulkanis_terrain_receiver"));
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.vulkanis.render.shadow.PackShaderFallbackTest"`
Expected: FAIL — no such constructor/methods.

- [ ] **Step 3: Write minimal implementation**

Add a `VulkanisShaderSources(ShaderLibrary packShaders)` constructor (keep the no-arg one delegating with an empty library for existing call sites: `CascadeShadows.java:83` field init and `ensureCompiled:411,429`). Add `fragmentFor`/`vertexFor` (or extend `sourceFor`) mapping `vulkanis_shadow` -> `pack/shadow` keys, `vulkanis_terrain_receiver` -> `pack/terrain` keys, jar-embedded otherwise. `CascadeShadows` must use the instance backed by `VulkanisClient.packShaders()` — wire via a setter called from `loadPack`/`selectPack`, defaulting to empty (pure jar) before first pack load.

- [ ] **Step 4: Run tests**

Run: `./gradlew test`
Expected: PASS (65+ new tests).

### Task 4: Hot-reload pack shadow pipelines on reselect + surface compile errors

**Files:**
- Modify: `src/client/java/com/example/vulkan/VulkanisClient.java:160-183` (`selectPack`)
- Modify: `src/client/java/com/example/vulkan/render/shadow/VulkanisShadowPipelines.java:32-35` (already has `close()` — call it)
- Modify: `src/client/java/com/example/vulkan/render/ShadowHookState.java` (add `lastPackError` string + setter/getter)
- Test: extend `PackShaderFallbackTest` or `PackManagerTest` with a reselect-clears-cache test if feasible without GPU (counter on `VulkanisShadowPipelines.close()` is hard headless — at minimum assert `ShadowHookState.lastPackError` defaults empty).

**Interfaces:**
- Consumes: Tasks 2-3 outputs.
- Produces: reselecting a pack rebuilds composite AND drops cached shadow pipelines so next `compileProgram` re-resolves GLSL; broken pack GLSL keeps vanilla fallback + records a message for the shader screen.

- [ ] **Step 1: Write the failing test**

```java
@Test public void packErrorDefaultsEmpty() {
  assertTrue(com.vulkanis.render.ShadowHookState.lastPackError().isEmpty());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.vulkanis.render.shadow.PackShaderFallbackTest"`
Expected: FAIL — no such method.

- [ ] **Step 3: Write minimal implementation**

In `selectPack` after composite rebuild: `VulkanisShadowPipelines.close()` (drops CASTER/RECEIVER caches so `VulkanisTerrainMixin` rebuilds against fresh GLSL), re-wire `VulkanisShaderSources` pack library, `ShadowHookState.setCompositeReady(true)`, clear `lastPackError`. In `CascadeShadows.ensureCompiled` catch path, set `lastPackError` to a one-line message (keep existing warn-once behavior). `VulkanisShaderScreen`: append `lastPackError` to the pack entry when non-empty (read existing screen code first — `VulkanisShaderScreen.java:34` pack loop).

- [ ] **Step 4: Run tests + build**

Run: `./gradlew build` (with JAVA_HOME export, no grep pipe).
Expected: exit 0.

### Task 5: Uniform contract doc for pack authors

**Files:**
- Create: `run/shaderpacks/PACK_CONTRACT.md` (shipped next to packs, mirrored to instance)
- Modify: `AGENTS.md` pack section (one line pointing at it)

**Interfaces:**
- Consumes: `VulkanisShadowPipelines.receiverLayout/caster` (`:75-95`), `VulkanisShadowData` UBO (`vulkanis_terrain_receiver.fsh:47-54`), defines in `VulkanisShadowPipelines.build` (`:49,58-59,67-71`), push constants (`:48`), Sodium vertex decode block (copy the comment style, not Sulkan code).

**Contents (no placeholders — write the real tables):** uniform name table (u_LightTex, u_BlockTex, u_Globals, u_SectionTimeInfo, VulkanisShadowMap0-3, VulkanisShadowData) with types; UBO field layout with byte offsets (CascadeMatrix 0-255, CascadeInfo 256-319, LightDirection 320-335, ShadowParams 336-351); defines table (USE_VERTEX_COMPRESSION always; ALPHA_CUTOUT 0.5 cutout / 0.01 translucent / absent solid+receiver-solid; USE_FOG + VULKANIS_CASCADE_COUNT=4 on receiver); vertex inputs (a_Position/a_Color/a_TexCoord/a_LightAndData + decode snippet pointer); push-constant block; "your shader MUST declare / MUST NOT rename" list; fallback rule (per-file jar fallback).

- [ ] **Step 1: Draft from code (read every cited line, copy exact names/offsets)**
- [ ] **Step 2: Self-check each name against `grep -n` output; fix mismatches**
- [ ] **Step 3: `./gradlew build` green (doc-only, but proves nothing else broke)**

### Task 6: Bundled shadows live in VulkanicShader (DemoShader stays the user's)

**Files:**
- Create: `run/shaderpacks/VulkanicShader/shaders/shadow.vsh`, `shadow.fsh`, `terrain.vsh`, `terrain.fsh` (full cascade port: PCF, normal offset, blend, fade, fog)
- `run/shaderpacks/DemoShader/`: composite-only passthrough, shadows disabled — the user's playground, do NOT add shadow code there (user ruling 2026-10-06).

**Interfaces:**
- Consumes: Tasks 1-5 (contract doc is the authoring guide).
- Produces: DemoShader renders terrain with pack-owned receiver — proof the indirection works end to end.

- [ ] **Step 1: Write `shadow.vsh/.fsh`** — caster port: Sodium decode, sun-matrix transform, alpha-test + white. Behavior identical to the former jar caster.
- [ ] **Step 2: Write `terrain.vsh/.fsh`** — full receiver port: cascade pick, normal offset, gradient-corrected PCF, boundary blend, far fade, fog. Behavior identical to the former jar receiver (shadows must look the same in-game as before this plan).
- [ ] **Step 3: Deploy to instance, select VulkanicShader in-game, user confirms shadows look as before; select DemoShader, user confirms plain vanilla (no shadows)**
- [ ] **Step 4: `./gradlew build` + full test suite green**

## Execution Handoff

Plan saved. Please review. Which execution approach?
- **Subagent-driven** - fresh subagent per task + reviewer gates; most thorough.
- **Native** - I implement inline, one final review at the end; cheapest.

I recommend **Native**: tasks share the loader→sources→pipelines interface chain, mistakes surface as compile/test failures immediately, and the final proof is visual in-game anyway. Does the plan capture what you want, and which approach?
