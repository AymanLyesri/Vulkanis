# Measured Dynamic Exposure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the day/night-faked `Exposure` multiplier with exposure measured from the actual frame: a 1x1 history pass averages scene luminance and adapts over frames, and the final pass derives its multiplier from that value — looking at the sun brightens the frame, entering a cave darkens-then-recovers it.

**Architecture:** GPU-only feedback loop, no CPU readback. A `history` composite pass renders scene-average luma into a 1x1 ping-pong target while blending with the previous frame (temporal adaptation in-shader); later passes sample the fresh value as `In<Name>`. The exposure curve (`key / avg`, clamped) lives as a pure Java mirror for unit tests and as GLSL in the pack's final shader. The existing UBO `Exposure` float is left untouched for other packs.

**Tech Stack:** Java, Gradle under Java 25, Gson, JUnit 5, GLSL 330, renderpearl `TextureTarget` 1x1 ping-pong pair (same primitive `CascadeShadows.ensureResources` uses).

**Spec:** This conversation (user: "REAL and DYNAMIC exposure"; sun-gaze must change exposure) plus `run/shaderpacks/PACK_CONTRACT.md` (updated in Task 3).

## Global Constraints

- Clean-room: write all code fresh; reference only for approach.
- Gradle MUST run under Java 25 (`JAVA_HOME=~/.local/share/FreesmLauncher/java/java-runtime-epsilon`); check exit code explicitly, never pipe build through `grep`.
- Verify via log, never screenshots. GPU behavior is verified by user playtest (owns the merge gate); unit tests cover parsing, binding selection, and the exposure curve math.
- UBO structs must be std140-exact (this plan adds no UBO fields).
- NEVER `git commit` unless the user explicitly asks. Work on the current branch; do not touch `render/shadow/*` or entity mixins.
- Symlinked instance packs: `run/` edits go live on pack re-select, no copy step.

## Review Focus

- A graph with two `history` passes is rejected at load with a named error, never allocated.
- A history pass named `final`, or a non-history pass referencing its own name, is rejected at load.
- The history pass sampling its own previous output while rendering the new one reads the read-side texture, never the target being drawn (no feedback hazard), by construction of the ping-pong swap.
- Window resize recreates color transients but keeps 1x1 history alive (no adaptation restart); graph switch/pack re-select restarts adaptation with a documented brief sweep.
- A first-frame or garbage history value can only darken-or-brighten within `[exposureMin, exposureMax]`, never NaN/negative/infinite the frame.

---

### Task 1: History passes — spec, validation, exposure curve

**Files:**
- Modify: `src/client/java/com/vulkanis/pack/PassGraph.java` (`Pass` gains `boolean history`)
- Modify: `src/client/java/com/vulkanis/pack/PackLoader.java` (`parsePasses`: parse/validate `history`)
- Create: `src/client/java/com/vulkanis/pack/ExposureCurve.java`
- Test: extend `src/test/java/com/vulkanis/pack/PassGraphTest.java`; create `src/test/java/com/vulkanis/pack/ExposureCurveTest.java`

**Interfaces:**
- Consumes: existing `PassGraph.Pass(String, String, List<String>, double)` (all call sites updated to the 5-arg form).
- Produces: `Pass(String name, String frag, List<String> in, double size, boolean history)`; `ExposureCurve.apply(double avgLuma, double key, double min, double max) -> double` = `clamp(key / max(avgLuma, 1e-3), min, max)` with `min <= max` enforced by `IllegalArgumentException`.

Validation rules for `history` (all `PackException`, offending pass named in the message): at most 1 history pass per graph; a history pass must not be named `final`; a non-history pass may not reference its own name (still `unknown pass input`); a history pass MAY reference its own name (reads previous frame); `history` non-boolean → `PackException("bad pass history (want boolean)")`. `size` on a history pass is ignored (targets are always 1x1) — documented, not rejected.

- [ ] **Step 1: Write the failing tests**

```java
@Test public void historySelfReferenceAllowed() throws Exception {
  File dir = PackSettingsTest.pack("h1", "[]");
  var shaders = new File(dir, "shaders");
  new File(shaders, "m.fsh").createNewFile();
  java.nio.file.Files.writeString(new File(dir, "pipeline.json").toPath(), "{\"id\":\"h1\",\"settings\":[],"
    + "\"passes\":[{\"name\":\"m\",\"frag\":\"m.fsh\",\"history\":true},"
    + "{\"name\":\"final\",\"frag\":\"composite.fsh\",\"in\":[\"main\",\"m\"]}]}");
  var g = PackLoader.parsePasses(dir);
  assertTrue(g.passes().get(0).history());
}
@Test public void twoHistoryPassesRejected() throws Exception {
  File dir = PackSettingsTest.pack("h2", "[]");
  var shaders = new File(dir, "shaders");
  new File(shaders, "a.fsh").createNewFile();
  new File(shaders, "b.fsh").createNewFile();
  java.nio.file.Files.writeString(new File(dir, "pipeline.json").toPath(), "{\"id\":\"h2\",\"settings\":[],"
    + "\"passes\":[{\"name\":\"a\",\"frag\":\"a.fsh\",\"history\":true},"
    + "{\"name\":\"b\",\"frag\":\"b.fsh\",\"history\":true},"
    + "{\"name\":\"final\",\"frag\":\"composite.fsh\",\"in\":[\"main\"]}]}");
  PackLoader.PackException e = assertThrows(PackLoader.PackException.class, () -> PackLoader.parsePasses(dir));
  assertTrue(e.getMessage().contains("history"), "named error, got: " + e.getMessage());
}
@Test public void selfReferenceRejectedWhenNotHistory() throws Exception {
  File dir = PackSettingsTest.pack("h3", "[]");
  var shaders = new File(dir, "shaders");
  new File(shaders, "x.fsh").createNewFile();
  java.nio.file.Files.writeString(new File(dir, "pipeline.json").toPath(), "{\"id\":\"h3\",\"settings\":[],"
    + "\"passes\":[{\"name\":\"x\",\"frag\":\"x.fsh\",\"in\":[\"x\"]},"
    + "{\"name\":\"final\",\"frag\":\"composite.fsh\",\"in\":[\"main\"]}]}");
  assertThrows(PackLoader.PackException.class, () -> PackLoader.parsePasses(dir));
}
@Test public void historyFinalRejected() throws Exception {
  File dir = PackSettingsTest.pack("h4", "[]");
  var shaders = new File(dir, "shaders");
  new File(shaders, "c.fsh").createNewFile();
  java.nio.file.Files.writeString(new File(dir, "pipeline.json").toPath(), "{\"id\":\"h4\",\"settings\":[],"
    + "\"passes\":[{\"name\":\"final\",\"frag\":\"c.fsh\",\"history\":true}]}");
  assertThrows(PackLoader.PackException.class, () -> PackLoader.parsePasses(dir));
}
```

```java
@Test public void keyOverAvg() {
  assertEquals(1.0, ExposureCurve.apply(0.35, 0.35, 0.5, 3.0), 1e-9);
}
@Test public void clampsBothEnds() {
  assertEquals(3.0, ExposureCurve.apply(0.001, 0.35, 0.5, 3.0), 1e-9);
  assertEquals(0.5, ExposureCurve.apply(10.0, 0.35, 0.5, 3.0), 1e-9);
}
@Test public void garbageCannotEscapeRange() {
  assertEquals(3.0, ExposureCurve.apply(0.0, 0.35, 0.5, 3.0), 1e-9);
  assertEquals(0.5, ExposureCurve.apply(Double.POSITIVE_INFINITY, 0.35, 0.5, 3.0), 1e-9);
}
@Test public void badRangeRejected() {
  assertThrows(IllegalArgumentException.class, () -> ExposureCurve.apply(0.5, 0.35, 3.0, 0.5));
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME=~/.local/share/FreesmLauncher/java/java-runtime-epsilon PATH="$JAVA_HOME/bin:$PATH" && ./gradlew test --tests "com.vulkanis.pack.PassGraphTest" --tests "com.vulkanis.pack.ExposureCurveTest"`
Expected: FAIL (no 5-arg `Pass`, no `history()`, no `ExposureCurve`).

- [ ] **Step 3: Write minimal implementation**

```java
package com.vulkanis.pack;
public final class ExposureCurve {
  private ExposureCurve() {}
  public static double apply(double avgLuma, double key, double min, double max) {
    if (!(min <= max)) throw new IllegalArgumentException("exposure min > max");
    double clamped = Math.min(Math.max(avgLuma, 1e-3), Double.MAX_VALUE);
    if (Double.isNaN(clamped)) clamped = 1e-3;
    return Math.min(max, Math.max(min, key / clamped));
  }
}
```

`Pass` record gains `boolean history`. `parsePasses`: read optional `history` (default false, strict boolean check mirroring the `shadows.enabled` guard); track history count, reject >1 with `"only one history pass allowed, got: " + name`; reject history named `final`; when checking `in`, seed the per-pass known-set with the pass's own name iff `history` is true. Update the existing `Pass(...)` construction in `parsePasses` and fix the `PassExecutorTest.bindsOnlyProducedOutputs` call sites (3-arg → 5-arg, `false`).

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test` (Java 25 env).
Expected: PASS, zero failures.

- [ ] **Step 5: Stop (no commit per repo rule)**

---

### Task 2: Executor ping-pong history + self-binding

**Files:**
- Modify: `src/client/java/com/vulkanis/render/PassExecutor.java` (history pair, read-side binding, swap, lifecycle)
- Test: extend `src/test/java/com/vulkanis/render/PassExecutorTest.java` (history-aware `boundOutputs` + swap-order pure test)

**Interfaces:**
- Consumes: `PassGraph.Pass.history()` (Task 1).
- Produces: history pass `H` renders into write-side 1x1 while `InH` binds the read-side view; every later pass binds `InH` to the freshly written write-side view; read/write swap after `encoder.submit()`; resize keeps 1x1 history alive; `close()` destroys history targets; `resetPending()` leaves history intact (adaptation survives pipeline recompiles).

Rules: `boundOutputs(graph, index)` additionally excludes nothing new (history name appears for later passes by the existing earlier-pass logic); new `historyReadView`/`historyWriteView` resolution is internal. A graph without history behaves byte-identically to today.

- [ ] **Step 1: Write the failing tests**

```java
@Test public void historyBindsReadSideToSelf() {
  var graph = new com.vulkanis.pack.PassGraph(java.util.List.of(
    new com.vulkanis.pack.PassGraph.Pass("m", "m.fsh", java.util.List.of("main", "m"), 0.25, true),
    new com.vulkanis.pack.PassGraph.Pass("final", "composite.fsh", java.util.List.of("main", "m"), 1.0, false)));
  assertEquals(java.util.List.of(), PassExecutor.boundOutputs(graph, 0));
  assertEquals(java.util.List.of("m"), PassExecutor.boundOutputs(graph, 1));
  assertEquals("InM", PassExecutor.samplerName("m"));
}
```

(This pins that pass 0 binds nothing from the future and pass 1 sees `m`; the read-side-vs-write-side routing itself is GPU-only and covered by the playtest gate.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.vulkanis.render.PassExecutorTest"` (Java 25 env).
Expected: FAIL (5-arg `Pass` does not exist yet — written in Task 1; if Task 1 is done, this passes trivially and the REAL new assertion is the executor behavior below, verified by suite + playtest).

- [ ] **Step 3: Write minimal implementation**

State: `private String historyName = null;` + `private TextureTarget historyA, historyB;` + `private boolean historyReadIsA = true;` Set from graph in constructor (scan for `history()`, at most one by validation).

`ensureTargets(w, h)`: after the existing transient loop, if `historyName != null && (historyA == null)` create both 1x1 (`new TextureTarget("vulkanis:history-A", 1, 1, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT)` and `-B`). Deliberately NOT recreated on resize.

Render loop binding: replace the produced-outputs loop body with resolution — for the pass being rendered, if it is the history pass, bind `In<name>` to the read-side view explicitly (it is absent from its own `boundOutputs`); for every name in `boundOutputs(graph, i)`, bind the write-side view when the name is the history name, else the transient target view:

```java
GpuTextureView historyRead = historyReadIsA ? historyA.getColorTextureView() : historyB.getColorTextureView();
GpuTextureView historyWrite = historyReadIsA ? historyB.getColorTextureView() : historyA.getColorTextureView();
// inside pass loop, after InSampler/InDepth binds:
if (historyName != null && p.name().equals(historyName)) {
  pass.setUniform(samplerName(historyName), historyRead,
    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
}
for (String produced : boundOutputs(graph, i)) {
  GpuTextureView view = (historyName != null && produced.equals(historyName))
    ? historyWrite : targets.get(produced).getColorTextureView();
  pass.setUniform(samplerName(produced), view,
    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
}
```

After `encoder.submit()`: `if (historyName != null) historyReadIsA = !historyReadIsA;`

`close()`: destroy `historyA`/`historyB` if non-null, null them. `resetPending()`: unchanged (history survives recompiles by design).

`loadComposite` needs NO change (it already loads every pass frag generically into `post/<name>` keys).

- [ ] **Step 4: Run tests + build to verify**

Run: `./gradlew test` then `./gradlew build` (Java 25 env).
Expected: PASS both; `PassExecutor.class` fresh in `build/classes`.

- [ ] **Step 5: Stop (no commit per repo rule)**

---

### Task 3: Pack content — measure pass, final math, settings, docs

**Files:**
- Create: `run/shaderpacks/VulkanicShader/shaders/measure.fsh`
- Modify: `run/shaderpacks/VulkanicShader/shaders/composite.fsh` (measured exposure replaces `* Exposure`)
- Modify: `run/shaderpacks/VulkanicShader/pipeline.json` (passes + `measure`, 4 exposure settings, `exposure` tab)
- Modify: `run/shaderpacks/PACK_CONTRACT.md` (history passes, `In<Name>` read/write semantics, exposure curve)
- Test: extend `src/test/java/com/vulkanis/pack/VulkanicShaderPackTest.java` (4 passes, `exposure` tab present, all frags token-resolve); update `RealPackTest` 9 → 13 settings

**Interfaces:**
- Consumes: `history` flag + `ExposureCurve` (Task 1), executor history routing (Task 2).
- Produces: Vulkanic graph `bright -> blur -> measure -> final`; DemoShader untouched (stays 2-pass, no exposure).

`measure.fsh` (exact content):

```glsl
#version 330
#extension GL_ARB_separate_shader_objects : require

// Exposure measure: 8x8 average scene luma blended with the previous frame
// (InMeasure reads last frame via ping-pong). Output feeds InMeasure (.r)
// for later passes this frame. First frames sweep from dark: eye-adapting,
// not a bug. {{exposureSpeed}} sets adapt rate (higher = faster).
uniform sampler2D InSampler;
uniform sampler2D InMeasure;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main(){
    float sum = 0.0;
    for (int x = 0; x < 8; x++)
    for (int y = 0; y < 8; y++) {
        vec3 s = texture(InSampler, (vec2(x, y) + 0.5) / 8.0).rgb;
        sum += dot(s, vec3(0.299, 0.587, 0.114));
    }
    float avg = sum / 64.0;
    float prev = texture(InMeasure, vec2(0.5)).r;
    float adapted = mix(prev, avg, {{exposureSpeed}});
    fragColor = vec4(adapted, adapted, adapted, 1.0);
}
```

`composite.fsh` final-math edit (replaces `* Exposure`):

```glsl
    vec3 c = texture(InSampler, texCoord).rgb;
    float avg = texture(InMeasure, vec2(0.5)).r;
    float exposure = clamp({{exposureKey}} / max(avg, 0.001), {{exposureMin}}, {{exposureMax}});
    c *= exposure;
```

(mirrors `ExposureCurve.apply` exactly: same epsilon, same clamp order. Keep the `Exposure` UBO declaration — still bound, available to packs — but Vulkanic final no longer multiplies by it.)

`pipeline.json` additions: settings `exposureKey` (float, default 0.35, min 0.1, max 1.0, step 0.05, `exposure`), `exposureMin` (0.5, 0.25..1.0, 0.05), `exposureMax` (3.0, 1.0..8.0, 0.5), `exposureSpeed` (0.15, 0.01..1.0, 0.01); categories list gains `{"id":"exposure","label":"Exposure"}` after `bloom`; passes become `bright, blur, measure(history, in [main, measure]), final(in [main, blur, measure])`. `measure` entry: `{"name":"measure","frag":"measure.fsh","in":["main","measure"],"history":true}` (size ignored → 1x1).

Contract appendix: `history` flag semantics (1 max, not `final`, self-`in` allowed = previous frame, 1x1 always, survives resize, restarts on graph switch with a brief sweep), `In<Name>` for a history output = fresh value for later passes, exposure curve formula + defaults.

- [ ] **Step 1: Write the failing test**

Extend `shippedGraphsSelfConsistent`: `expectedPasses` VulkanicShader 3 → 4; add categories assertion containing `exposure`:

```java
assertTrue(cats.stream().map(PackCategories.Category::id).toList().contains("exposure"));
```

(GLSL↔Java curve parity is pinned by `ExposureCurveTest` from Task 1 plus this token-resolution loop covering `measure.fsh`'s `{{exposureSpeed}}`.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.vulkanis.pack.VulkanicShaderPackTest"` (Java 25 env).
Expected: FAIL (3 != 4).

- [ ] **Step 3: Write minimal implementation** (files above, verbatim GLSL/math).

- [ ] **Step 4: Run tests + build + deploy**

Run: `./gradlew test` (PASS, note new total), `./gradlew build`, `cp build/libs/vulkanis-0.1.0.jar <instance>/minecraft/mods/`. New `measure.fsh` arrives via the symlinked shaders dir; re-select Vulkanic in-game to hot-reload.

- [ ] **Step 5: Stop (no commit per repo rule)**

---

## Playtest gate (user, in-game — merge blocked until reported)

1. Noon, look at ground then flick to the sun: frame darkens within ~1s (exposure drops), sun halos (bloom).
2. Night: torches halo; exposure brightens the scene up to the cap without gray wash.
3. Cave enter/exit: brief sweep, no flash-to-white/black stuck states.
4. Log: `pass graph bright->blur->measure->final`, `tokensLeft=false`, zero `compile failed`.
5. Resize drag mid-session: no crash; adaptation continues (no restart sweep).

## Self-Review

- Spec coverage: sun-gaze changes exposure (measure→curve→final, Tasks 1–3); dynamic adaptation (ping-pong + speed, Tasks 2–3); cave/dark clamp safety (curve clamps + NaN/Infinity guards, Task 1 tests); tunability (4 settings, Task 3).
- No placeholders: every step names files, signatures, commands, exact GLSL/math, expected outputs.
- Type consistency: `Pass(..., boolean history)`, `ExposureCurve.apply(double, double, double, double) -> double`, `boundOutputs(graph, index)`, `InM` naming via existing `samplerName`, measure output read via `.r` at `vec2(0.5)` with NEAREST.
- `loadComposite` needs no change (generic per-pass loading already ships); UBO untouched (no std140 risk).
