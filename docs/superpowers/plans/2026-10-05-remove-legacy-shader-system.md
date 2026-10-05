# Remove Legacy Shader System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Delete the unused standalone shader system and keep only the working cascade-shadow + composite tonemap path.

**Architecture:** Pure deletion + validation retarget: remove orphan pack shaders and dead GLSL, drop the unused `passes` field, and make `PackLoader` require the files Java actually loads (`composite.vsh`/`composite.fsh`).

**Tech Stack:** Java 17+/25 toolchain via Gradle 9.4.1, GLSL 330/460, Gson pack JSON, JUnit 5.

**Spec:** User request 2026-10-05: "the system that is currently working is the one i wanna keep, remove the other". Working = jar-embedded 4-cascade shadows (`vulkanis_shadow.*`, `vulkanis_terrain_receiver.*`) + pack composite tonemap/vignette/depth (`composite.vsh`/`composite.fsh` via `CompositeRenderer`) + pack settings framework. Other = orphan `world.frag`, `world.vert`, `shadow.vert`, `composite.frag`, dead `sunShadow()` raymarch in `composite.fsh`, unused `passes` array in `pipeline.json`.

## Global Constraints

- Never `git commit` unless the user explicitly asks.
- Gradle MUST run under Java 25: `JAVA_HOME=~/.local/share/FreesmLauncher/java/java-runtime-epsilon`.
- `./gradlew build`: check exit code explicitly; NEVER pipe through `grep` (masks failures).
- Clean-room: write all code fresh; never copy Sulkan/Complementary files.
- Keep `SamplerInfo` UBO std140-exact (192B layout); do not shrink/reorder it in this plan.
- Verify via log/tests, never screenshots.

## Review Focus

- Pack that has `composite.vsh`+`composite.fsh` but no `world.frag` must now VALIDATE (previously rejected) — covered in Task 4 tests.
- Pack missing `composite.fsh` must now REJECT with "missing shaders/composite" — covered in Task 4 tests.
- `composite.fsh` with no `{{tokens}}` left unsubstituted must still load — existing `RealPackTest` covers, re-run in Task 2.
- Deleted `passes` field must not break `PackLoader.loadSpec` on old packs that still carry it (forward compat: ignore unknown fields) — covered in Task 3 test.
- Show Depth toggle must still work after dead-code strip (keeps `linearize`+`InDepth` path) — manual in-game check noted in Task 2, plus build must pass.

---

### Task 1: Delete orphan pack shaders

**Files:**
- Delete: `run/shaderpacks/VulkanicShader/shaders/world.frag`
- Delete: `run/shaderpacks/VulkanicShader/shaders/world.vert`
- Delete: `run/shaderpacks/VulkanicShader/shaders/shadow.vert`
- Delete: `run/shaderpacks/VulkanicShader/shaders/composite.frag`
- Modify: `src/client/java/com/example/vulkan/mixin/WorldCompositeMixin.java:16` (comment mentions `composite.frag`)

**Interfaces:**
- Consumes: nothing (pure deletion; Java never loads these — verified by grep: only `composite.vsh`/`composite.fsh` appear in `VulkanisClient.java:92-93`).
- Produces: pack dir contains only `composite.vsh`, `composite.fsh`.

- [ ] **Step 1: Confirm orphans are unreferenced**

Run: `grep -rn "world\.frag\|world\.vert\|shadow\.vert\|composite\.frag\|\"world\"\|\"shadow\"" src/ --include=*.java`
Expected: only `PackLoader.java:17-18` validation check and the `WorldCompositeMixin.java:16` comment (no loader/reader).

- [ ] **Step 2: Delete the four files**

Run: `rm run/shaderpacks/VulkanicShader/shaders/world.frag run/shaderpacks/VulkanicShader/shaders/world.vert run/shaderpacks/VulkanicShader/shaders/shadow.vert run/shaderpacks/VulkanicShader/shaders/composite.frag`
Expected: `ls run/shaderpacks/VulkanicShader/shaders/` shows only `composite.vsh composite.fsh`.

- [ ] **Step 3: Fix the stale mixin comment**

In `src/client/java/com/example/vulkan/mixin/WorldCompositeMixin.java:16`, replace:
```java
// Runs composite.frag fullscreen after the world, before HUD. Skipped when the
```
with:
```java
// Runs composite.fsh fullscreen after the world, before HUD. Skipped when the
```

- [ ] **Step 4: Run pack tests (expect failure on validation — fixed in Task 4)**

Run: `export JAVA_HOME=~/.local/share/FreesmLauncher/java/java-runtime-epsilon PATH="$JAVA_HOME/bin:$PATH" && ./gradlew test --tests "com.vulkanis.pack.*"`
Expected: FAIL on `VulkanicShaderPackTest.myBasicValidates` with "missing shaders/world.frag" (proves the only remaining reference is the validation gate).

### Task 2: Strip dead screen-space shadow code from composite.fsh

**Files:**
- Modify: `run/shaderpacks/VulkanicShader/shaders/composite.fsh:1-72`

**Interfaces:**
- Consumes: `SamplerInfo` UBO layout from `CompositeRenderer.java:128-137` (192B, unchanged).
- Produces: slimmer `composite.fsh` exposing identical `main()` behavior (tonemap + vignette + ShowDepth).

- [ ] **Step 1: Write the characterization check**

Run: `grep -n "sunShadow\|reconstructWorld\|ViewProj\|InvViewProj\|SunDir\|CamPos" run/shaderpacks/VulkanicShader/shaders/composite.fsh`
Expected: matches at lines 28-59 (`reconstructWorld`, `sunShadow`, `ViewProj`, `SunDir`) plus UBO declarations — and `sunShadow(` called nowhere in `main()` (lines 61-72).

- [ ] **Step 2: Delete dead functions, keep ShowDepth path**

Replace `run/shaderpacks/VulkanicShader/shaders/composite.fsh` lines 28-59 (the `reconstructWorld` and `sunShadow` functions) with nothing, so the file becomes:
```glsl
#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D InSampler;
uniform sampler2D InDepth;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
    int ShowDepth;
    float Proj22;
    float Proj32;
    float Pad0;
    vec3 SunDir;
    vec3 CamPos;
    mat4 InvViewProj;
    mat4 ViewProj;
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

float linearize(float d) {
    return Proj32 / (d + Proj22);
}

void main(){
    if (ShowDepth == 1) {
        float d = linearize(texture(InDepth, texCoord).r) / 200.0;
        fragColor = vec4(vec3(clamp(d, 0.0, 1.0)), 1.0);
        return;
    }
    vec3 c = texture(InSampler, texCoord).rgb;
    c = c / (c + 0.6) * 1.35;
    float dist = distance(texCoord, vec2(0.5));
    c *= 1.0 - dist * {{vignetteStrength}};
    fragColor = vec4(c, 1.0);
}
```
Note: UBO keeps all fields (std140-exact, `CompositeRenderer` still uploads 192B). `linearize` stays because ShowDepth uses it. `{{maxShadowDistance}}`, `{{shadowSteps}}`, `{{shadowBias}}`, `{{shadowStrength}}` tokens disappear with `sunShadow` — that is intended; those settings remain live via the cascade receiver path (`VulkanisClient.shadowStrengthSetting()` etc.).

- [ ] **Step 3: Verify no tokens left and no callers broken**

Run: `grep -n "sunShadow\|reconstructWorld" run/shaderpacks/VulkanicShader/shaders/composite.fsh; grep -rn "sunShadow" src/ run/`
Expected: no output (zero matches).

- [ ] **Step 4: Run composite/pack tests**

Run: `export JAVA_HOME=~/.local/share/FreesmLauncher/java/java-runtime-epsilon PATH="$JAVA_HOME/bin:$PATH" && ./gradlew test --tests "com.vulkanis.pack.RealPackTest"`
Expected: PASS.

### Task 3: Drop unused passes field from pipeline.json

**Files:**
- Modify: `run/shaderpacks/VulkanicShader/pipeline.json:1`
- Test: `src/test/java/com/example/vulkan/pack/PackValidationTest.java` (append new test)

**Interfaces:**
- Consumes: `PackLoader.loadSpec` (ignores unknown fields — no Java change needed).
- Produces: `pipeline.json` without `passes`.

- [ ] **Step 1: Remove the passes array**

In `run/shaderpacks/VulkanicShader/pipeline.json`, replace `"passes":["shadow","world","composite"],` with nothing (keep valid JSON, no trailing comma).

- [ ] **Step 2: Add forward-compat test (unknown fields ignored)**

Append to `src/test/java/com/example/vulkan/pack/PackValidationTest.java`:
```java
@Test public void ignoresUnknownPassesField() throws Exception {
  File dir = pack("passesfield", "{\"id\":\"x\",\"shadowSize\":2048,\"passes\":[\"shadow\",\"world\",\"composite\"]}");
  assertEquals("x", PackLoader.loadSpec(dir).id());
}
```
Note: `pack()` helper already creates `shaders/world.frag`; after Task 4 it will create composite files instead — add this test AFTER Task 4's helper change, or update the helper first.

- [ ] **Step 3: Run validation tests**

Run: `export JAVA_HOME=~/.local/share/FreesmLauncher/java/java-runtime-epsilon PATH="$JAVA_HOME/bin:$PATH" && ./gradlew test --tests "com.vulkanis.pack.PackValidationTest"`
Expected: PASS.

### Task 4: Retarget PackLoader validation to actually-loaded files

**Files:**
- Modify: `src/client/java/com/example/vulkan/pack/PackLoader.java:17-18`
- Modify test helpers (each creates `shaders/world.frag`; switch to `shaders/composite.vsh` + `shaders/composite.fsh`):
  - `src/test/java/com/example/vulkan/pack/PackLoaderTest.java:16`
  - `src/test/java/com/example/vulkan/pack/PackValidationTest.java:11`
  - `src/test/java/com/example/vulkan/pack/GsonPackParsingTest.java:12`
  - `src/test/java/com/example/vulkan/pack/PackManagerTest.java:12`
  - `src/test/java/com/example/vulkan/pack/PackSettingsTest.java:12,28`

**Interfaces:**
- Consumes: pack dir layout (`pipeline.json` + `shaders/composite.vsh` + `shaders/composite.fsh`).
- Produces: `PackLoader.loadSpec` accepting working packs, rejecting packs missing composite shaders.

- [ ] **Step 1: Add failing tests for new validation**

In `src/test/java/com/example/vulkan/pack/PackLoaderTest.java` append:
```java
@Test public void rejectsMissingComposite() throws Exception {
  File dir = new File("build/tmp/nocomposite");
  dir.mkdirs();
  new File(dir, "shaders").mkdirs();
  try (var w = new java.io.FileWriter(new File(dir, "pipeline.json"))) {
    w.write("{\"id\":\"x\",\"name\":\"X\",\"version\":\"1\",\"shadowSize\":2048}");
  }
  assertThrows(PackLoader.PackException.class, () -> PackLoader.loadSpec(dir));
}
```
Run: `export JAVA_HOME=~/.local/share/FreesmLauncher/java/java-runtime-epsilon PATH="$JAVA_HOME/bin:$PATH" && ./gradlew test --tests "com.vulkanis.pack.PackLoaderTest"`
Expected: FAIL (validation still checks world.frag, so missing-composite pack with no files passes the composite check vacuously — actually it throws for missing world.frag, test passes for wrong reason; the companion check below pins it: temporally, `acceptsMinimalPack` must be updated first).

- [ ] **Step 2: Switch validation to composite shaders**

In `src/client/java/com/example/vulkan/pack/PackLoader.java:17-18`, replace:
```java
    if (!new File(packDir, "shaders/world.frag").exists()
        && !new File(packDir, "shaders/world.fsh").exists()) throw new PackException("missing shaders/world.frag");
```
with:
```java
    if (!new File(packDir, "shaders/composite.fsh").exists()
        && !new File(packDir, "shaders/composite.frag").exists()) throw new PackException("missing shaders/composite.fsh");
    if (!new File(packDir, "shaders/composite.vsh").exists()
        && !new File(packDir, "shaders/composite.vert").exists()) throw new PackException("missing shaders/composite.vsh");
```
(`.frag`/`.vert` alternates preserved to match the old two-extension tolerance pattern.)

- [ ] **Step 3: Update all test helpers to create composite files**

In each of the 5 test files, replace `new File(dir, "shaders/world.frag")` creation with:
```java
new File(new File(dir, "shaders"), "composite.vsh").createNewFile();
new File(new File(dir, "shaders"), "composite.fsh").createNewFile();
```
(adapt to each file's style: `FileWriter...close()` vs `createNewFile()` — keep the surrounding style, just change the filenames; create BOTH files.) For `PackValidationTest.pack()` and `GsonPackParsingTest.pack()` helpers this is one edit each covering all their tests.

- [ ] **Step 4: Update the rejectsMissingWorldFrag test name/meaning**

In `src/test/java/com/example/vulkan/pack/PackLoaderTest.java:6-11`, rename `rejectsMissingWorldFrag` to `rejectsMissingComposite` (it creates only `pipeline.json`, which now fails on missing composite — same body, new name reflecting the gate).

- [ ] **Step 5: Run full suite**

Run: `export JAVA_HOME=~/.local/share/FreesmLauncher/java/java-runtime-epsilon PATH="$JAVA_HOME/bin:$PATH" && ./gradlew test`
Expected: PASS (all 62+ tests, including new `rejectsMissingComposite`, `ignoresUnknownPassesField`).

### Task 5: Docs + instance mirror note

**Files:**
- Modify: `AGENTS.md:135` (pack file list)
- Verify: `README.md` pack section if it mentions `world.*`/`shadow.vert` (grep first)

**Interfaces:**
- Consumes: results of Tasks 1-4.
- Produces: docs matching the new on-disk reality.

- [ ] **Step 1: Find stale doc references**

Run: `grep -rn "world\.\|shadow\.vert\|composite\.frag\|sunShadow\|passes" AGENTS.md README.md docs/ 2>/dev/null`
Expected: hits in `AGENTS.md:135` (`composite.vsh/.fsh`, `world.*`, `shadow.vert`) and `WorldCompositeMixin` already fixed in Task 1.

- [ ] **Step 2: Update AGENTS.md pack listing**

Replace the `run/shaderpacks/VulkanicShader/` line listing `composite.vsh/.fsh`, `world.*`, `shadow.vert` with: `composite.vsh/.fsh` only, and note `world.frag/world.vert/shadow.vert/composite.frag removed 2026-10-05 (orphans, never loaded); PackLoader now requires composite.vsh/fsh`.

- [ ] **Step 3: Full build**

Run: `export JAVA_HOME=~/.local/share/FreesmLauncher/java/java-runtime-epsilon PATH="$JAVA_HOME/bin:$PATH" && ./gradlew build`
Expected: exit 0. Then note for user: mirror `run/shaderpacks/VulkanicShader/` to `<instance>/minecraft/shaderpacks/VulkanicShader/` (delete the 4 orphan files there too) — do NOT auto-touch the instance dir in this plan.
