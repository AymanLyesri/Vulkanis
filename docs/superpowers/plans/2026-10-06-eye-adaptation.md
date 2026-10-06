# Eye-Like Adaptation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make exposure adapt like a real eye: a delayed follower with asymmetric rates — fast when the scene brightens (squint), slow when it darkens (night vision) — instead of one symmetric blend.

**Architecture:** Two-stage chained follower built from existing machinery, no new uniforms, no CPU involvement. The fast `measure` pass keeps tracking the scene; a new second history pass `measureSlow` follows the fast value with direction-dependent rates; `final` derives exposure from the slow value. Executor history support generalizes from one hardcoded pair to a name-keyed map (max 2 history passes, enforced at load).

**Tech Stack:** Java, Gradle under Java 25, Gson, JUnit 5, GLSL 330, 1x1 `TextureTarget` ping-pong pairs (established pattern).

**Spec:** This conversation (user: "delay and transition time much like the real eye") plus `run/shaderpacks/PACK_CONTRACT.md` (updated in Task 3).

## Global Constraints

- Clean-room: write all code fresh.
- Gradle MUST run under Java 25 (`JAVA_HOME=~/.local/share/FreesmLauncher/java/java-runtime-epsilon`); check exit code explicitly, never pipe build through `grep`.
- Verify via log, never screenshots. GPU timing/feel is verified by user playtest (owns the merge gate); unit tests cover validation, binding selection, and rate math.
- UBO structs must be std140-exact (this plan adds no UBO fields).
- NEVER `git commit` unless the user explicitly asks. Work on the current branch; do not touch `render/shadow/*` or entity mixins.
- Symlinked instance packs: `run/` edits go live on pack re-select, no copy step.

## Review Focus

- A graph with three history passes is rejected at load with a named error, before any target is allocated.
- `measureSlow` sampling fresh `InMeasure` while its own `InMeasureSlow` reads last frame never feeds back: distinct textures, and `measure` precedes it by the forward-reference rule.
- Zero or negative adapt rates cannot freeze or invert adaptation: validated setting minimums keep rates in (0, 1].
- Resize keeps both history pairs alive (no double restart sweep); graph switch restarts both (documented single sweep).
- The slow value stays in [0,1] (mix of two [0,1] samples), so the existing curve clamp keeps exposure bounded exactly as today.

---

### Task 1: Two history passes — validation + rate math

**Files:**
- Modify: `src/client/java/com/vulkanis/pack/PackLoader.java` (`parsePasses`: max 1 → max 2 history passes)
- Modify: `src/client/java/com/vulkanis/pack/ExposureCurve.java` (add `adaptRate` pure helper) — or place it here if a better home exists; default home is `ExposureCurve`
- Test: extend `src/test/java/com/vulkanis/pack/PassGraphTest.java`, `src/test/java/com/vulkanis/pack/ExposureCurveTest.java`

**Interfaces:**
- Consumes: `Pass.history()` flag.
- Produces: `ExposureCurve.blend(double prev, double cur, double rateUp, double rateDown) -> double` = `prev + (cur - prev) * (cur > prev ? rateUp : rateDown)` mirroring the GLSL `mix` exactly (note: GLSL `mix(a,b,t)` = `a + (b-a)*t`; `cur > prev` strictly, ties take the down-rate — pin this); validation accepts ≤2 history passes, rejects the 3rd with `"at most 2 history passes allowed, got: " + name`.

- [ ] **Step 1: Write the failing tests**

```java
@Test public void twoHistoryPassesAllowed() throws Exception {
  File dir = PackSettingsTest.pack("h5", "[]");
  var shaders = new File(dir, "shaders");
  new File(shaders, "a.fsh").createNewFile();
  new File(shaders, "b.fsh").createNewFile();
  java.nio.file.Files.writeString(new File(dir, "pipeline.json").toPath(), "{\"id\":\"h5\",\"settings\":[],"
    + "\"passes\":[{\"name\":\"a\",\"frag\":\"a.fsh\",\"history\":true},"
    + "{\"name\":\"b\",\"frag\":\"b.fsh\",\"in\":[\"a\"],\"history\":true},"
    + "{\"name\":\"final\",\"frag\":\"composite.fsh\",\"in\":[\"main\",\"b\"]}]}");
  var g = PackLoader.parsePasses(dir);
  assertEquals(3, g.passes().size());
  assertTrue(g.passes().get(1).history());
}
@Test public void threeHistoryPassesRejected() throws Exception {
  File dir = PackSettingsTest.pack("h6", "[]");
  var shaders = new File(dir, "shaders");
  new File(shaders, "a.fsh").createNewFile();
  new File(shaders, "b.fsh").createNewFile();
  new File(shaders, "c.fsh").createNewFile();
  java.nio.file.Files.writeString(new File(dir, "pipeline.json").toPath(), "{\"id\":\"h6\",\"settings\":[],"
    + "\"passes\":[{\"name\":\"a\",\"frag\":\"a.fsh\",\"history\":true},"
    + "{\"name\":\"b\",\"frag\":\"b.fsh\",\"in\":[\"a\"],\"history\":true},"
    + "{\"name\":\"c\",\"frag\":\"c.fsh\",\"in\":[\"b\"],\"history\":true},"
    + "{\"name\":\"final\",\"frag\":\"composite.fsh\",\"in\":[\"main\"]}]}");
  PackLoader.PackException e = assertThrows(PackLoader.PackException.class, () -> PackLoader.parsePasses(dir));
  assertTrue(e.getMessage().contains("history"), "named error, got: " + e.getMessage());
}
```

```java
@Test public void blendRisesFastFallsSlow() {
  assertEquals(0.5 + 0.5 * 0.25, ExposureCurve.blend(0.5, 1.0, 0.25, 0.04), 1e-9);
  assertEquals(0.5 - 0.5 * 0.04, ExposureCurve.blend(0.5, 0.0, 0.25, 0.04), 1e-9);
}
@Test public void blendTieTakesDownRate() {
  assertEquals(0.5, ExposureCurve.blend(0.5, 0.5, 0.25, 0.04), 1e-9);
}
```

Also update the existing `twoHistoryPassesRejected` test (now contradicts the spec — two are legal): replace it with `threeHistoryPassesRejected` above (delete the old test, do not just add; ledger the replacement as a ruling).

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME=~/.local/share/FreesmLauncher/java/java-runtime-epsilon PATH="$JAVA_HOME/bin:$PATH" && ./gradlew test --tests "com.vulkanis.pack.PassGraphTest" --tests "com.vulkanis.pack.ExposureCurveTest"`
Expected: FAIL (2-history graph rejected; no `blend` method).

- [ ] **Step 3: Write minimal implementation**

```java
public static double blend(double prev, double cur, double rateUp, double rateDown) {
  double rate = cur > prev ? rateUp : rateDown;
  return prev + (cur - prev) * rate;
}
```

Validation: `if (historyCount > 2) throw new PackException("at most 2 history passes allowed, got: " + name);`

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test` (Java 25 env).
Expected: PASS, zero failures.

- [ ] **Step 5: Stop (no commit per repo rule)**

---

### Task 2: Executor — one history pair becomes a name-keyed map

**Files:**
- Modify: `src/client/java/com/vulkanis/render/PassExecutor.java` (`historyName/historyA/historyB/historyReadIsA` → `Map<String, HistoryPair>`)
- Test: extend `src/test/java/com/vulkanis/render/PassExecutorTest.java` (two-history `boundOutputs` case)

**Interfaces:**
- Consumes: multiple `Pass.history()` names per graph (Task 1), `boundOutputs(graph, index)` (unchanged semantics).
- Produces: identical externally-visible behavior for 0/1-history graphs (no legacy or single-history regression); N-history routing: history pass `H` renders into its write-side while `InH` binds its read-side; later passes bind `InH` to the fresh write-side; all pairs swap after `encoder.submit()`; `close()` destroys all pairs; `resetPending()` leaves history intact.

- [ ] **Step 1: Write the failing test**

```java
@Test public void twoHistoryChainBinds() {
  var graph = new com.vulkanis.pack.PassGraph(java.util.List.of(
    new com.vulkanis.pack.PassGraph.Pass("m", "m.fsh", java.util.List.of("main", "m"), 1.0, true),
    new com.vulkanis.pack.PassGraph.Pass("s", "s.fsh", java.util.List.of("m", "s"), 1.0, true),
    new com.vulkanis.pack.PassGraph.Pass("final", "c.fsh", java.util.List.of("main", "s"), 1.0, false)));
  assertEquals(java.util.List.of(), PassExecutor.boundOutputs(graph, 0));
  assertEquals(java.util.List.of("m"), PassExecutor.boundOutputs(graph, 1));
  assertEquals(java.util.List.of("m", "s"), PassExecutor.boundOutputs(graph, 2));
}
```

(This passes on current code already — `boundOutputs` is order/name-based and history-agnostic. Its value is pinning the chain's binding contract before the refactor; the refactor must keep it green. State that in the ledger.)

- [ ] **Step 2: Run test to verify it passes pre-refactor (contract pin), then refactor**

Run: `./gradlew test --tests "com.vulkanis.render.PassExecutorTest"` (Java 25 env).
Expected: PASS before the refactor; still PASS after.

- [ ] **Step 3: Refactor to map-based history**

Replace `historyName/historyA/historyB/historyReadIsA` with:

```java
private static final class HistoryPair {
  TextureTarget a;
  TextureTarget b;
  boolean readIsA = true;
}
private final Map<String, HistoryPair> history = new LinkedHashMap<>();
```

Constructor collects every `history()` pass name (validation caps at 2). `ensureTargets`: create each pair once at 1x1 (never on resize). Render loop: `boolean isHistory = history.containsKey(p.name())`; read/write views from the pair; self-bind read-side NEAREST; consumers bind write-side LINEAR via the existing `boundOutputs` branch (replace the `produced.equals(historyName)` check with `history.containsKey(produced)`). After submit: toggle every pair's `readIsA`. `close()`: destroy all pairs. `adoptIfSameTopology` path is untouched (executors are reused whole, pairs included — settings ticks now preserve BOTH stages with zero extra work).

- [ ] **Step 4: Run tests + build to verify**

Run: `./gradlew test` then `./gradlew build` (Java 25 env).
Expected: PASS both (119 + new tests).

- [ ] **Step 5: Stop (no commit per repo rule)**

---

### Task 3: Pack content — slow follower, settings, docs

**Files:**
- Create: `run/shaderpacks/VulkanicShader/shaders/measureSlow.fsh`
- Modify: `run/shaderpacks/VulkanicShader/shaders/composite.fsh` (sample `InMeasureSlow`, declare it)
- Modify: `run/shaderpacks/VulkanicShader/pipeline.json` (`measureSlow` pass, `adaptBright`/`adaptDark` settings, final `in` update)
- Modify: `run/shaderpacks/PACK_CONTRACT.md` (two-stage adaptation section)
- Test: extend `src/test/java/com/vulkanis/pack/VulkanicShaderPackTest.java` (5 passes, both history flags); update `RealPackTest` 13 → 15 settings

**Interfaces:**
- Consumes: 2-history validation + map routing (Tasks 1–2), `ExposureCurve.blend` parity (Task 1).
- Produces: Vulkanic graph `bright -> blur -> measure -> measureSlow -> final`; DemoShader untouched.

`measureSlow.fsh` (exact content):

```glsl
#version 330
#extension GL_ARB_separate_shader_objects : require

// Eye follower: trails the fast InMeasure with asymmetric rates —
// quick squint when the scene brightens ({{adaptBright}}), slow night
// vision when it darkens ({{adaptDark}}). InMeasureSlow reads last frame
// via ping-pong. Mirrors ExposureCurve.blend exactly (strict >).
uniform sampler2D InMeasure;
uniform sampler2D InMeasureSlow;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main(){
    float cur = texture(InMeasure, vec2(0.5)).r;
    float prev = texture(InMeasureSlow, vec2(0.5)).r;
    float rate = cur > prev ? {{adaptBright}} : {{adaptDark}};
    float adapted = mix(prev, cur, rate);
    fragColor = vec4(adapted, adapted, adapted, 1.0);
}
```

`composite.fsh`: add `uniform sampler2D InMeasureSlow;`, replace `texture(InMeasure, vec2(0.5))` with `texture(InMeasureSlow, vec2(0.5))`.

`pipeline.json`: settings `adaptBright` (float, default 0.25, min 0.05, max 1.0, step 0.05, `exposure`), `adaptDark` (0.04, 0.01..0.5, 0.01); passes insert `{"name":"measureSlow","frag":"measureSlow.fsh","in":["measure","measureSlow"],"history":true}` after `measure`; final `in` becomes `["main","blur","measureSlow"]`. (5 passes ≤ 8; transients bright/blur/measure/measureSlow = 4 ≤ 4 — exactly at cap, note it.)

Contract appendix: two-stage model (fast tracker + slow follower = delay; strict->tie rule; minimums forbid frozen adaptation; both pairs survive resize, restart on graph switch with one sweep).

- [ ] **Step 1: Write the failing test**

`shippedGraphsSelfConsistent`: VulkanicShader 4 → 5 passes (token loop then covers `measureSlow.fsh`'s `{{adaptBright}}`/`{{adaptDark}}` automatically).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.vulkanis.pack.VulkanicShaderPackTest"` (Java 25 env).
Expected: FAIL (4 != 5).

- [ ] **Step 3: Write minimal implementation** (files above verbatim; `RealPackTest` 13 → 15 in the same step since the settings are added here).

- [ ] **Step 4: Run tests + build + deploy**

Run: `./gradlew test` (PASS, note total), `./gradlew build`, `cp build/libs/vulkanis-0.1.0.jar <instance>/minecraft/mods/`, verify via `javap` that the deployed jar contains the map-based executor. `measureSlow.fsh` arrives via symlink; re-select Vulkanic in-game.

- [ ] **Step 5: Stop (no commit per repo rule)**

---

## Playtest gate (user, in-game — merge blocked until reported)

1. Noon sun-stare: frame darkens FAST (~1s squint), holds while looking.
2. Look away / enter cave: brightness lifts SLOWLY over several seconds (night vision), no snap.
3. Torch at night: halo + gentle lift, no pumping/breathing oscillation.
4. Exposure tab: Bright/Dark adapt sliders retune live with NO restart sweep (adopt path); Speed still governs the fast tracker.
5. Log: `pass graph bright->blur->measure->measureSlow->final`, `tokensLeft=false`, zero `compile failed`.

## Self-Review

- Spec coverage: delay (follower lag, Tasks 2–3); asymmetric transition (blend rates, Tasks 1/3); eye directionality (rise-fast/fall-slow + tie rule, Task 1 tests); tunability (2 settings, Task 3); no-freeze minimums (Task 3 validation ranges + Review Focus).
- No placeholders: exact GLSL/math/signatures/commands/values in every step.
- Type consistency: `blend(double,double,double,double)->double`; history map keyed by pass name; `InMeasureSlow` via existing `samplerName`; 5-pass graph within 8-pass/4-transient caps; `RealPackTest` 13→15.
- `loadComposite`, `adoptIfSameTopology`, UBO untouched — settings ticks preserve both history stages with zero extra work (adopt reuses the whole executor).
