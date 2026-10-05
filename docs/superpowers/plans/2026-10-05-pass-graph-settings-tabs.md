# Pass-Graph Composite + Settings Tabs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Packs declare a multi-pass fullscreen composite graph and grouped settings tabs; backend executes passes in order and renders tabs, with legacy single-pass/ungrouped packs byte-identical in behavior.

**Architecture:** Pure-data parsing/validation lives in `pack/` (fully unit-tested, no GPU); execution lives in `render/` (one new executor class reusing the existing `ShaderLibrary` + `PipelineCache` compile path); the screen groups already-parsed settings by category with a tab row. Exposure ships as a CPU-driven float in the existing `SamplerInfo` padding slot, reserved for a future measured-luma driver.

**Tech Stack:** Java 17+ (mod), Gradle under Java 25 (`JAVA_HOME=~/.local/share/FreesmLauncher/java/java-runtime-epsilon`), Gson (already used by `PackLoader`), JUnit 5, GLSL 330 + `GL_ARB_separate_shader_objects`.

**Spec:** This conversation (defaults confirmed: named samplers, 8-pass/4-target caps, exposure-as-float, explicit categories list with first-seen fallback, free-form tab ids) plus `run/shaderpacks/PACK_CONTRACT.md` (update in Task 8).

## Global Constraints

- Clean-room: write all code fresh; reference Sulkan/Complementary for approach only, never copy files.
- Gradle MUST run under Java 25; system Java 27 breaks Gradle 9.4.1. Never pipe build output through `grep` (masks failures); check exit code explicitly.
- Verify via log (`grep -a "vulkanis" <instance>/minecraft/logs/latest.log`), never screenshots.
- UBO structs must be std140-exact.
- Mixin `@Inject` handlers MUST declare the FULL target signature; `require=0` skips silently.
- NEVER `git commit` unless the user explicitly asks (repo rule overrides the skill's commit steps: end tasks at green tests, do not commit).
- Coordinate with the entity-shadows agent: do NOT touch entity/caster files (`render/shadow/CascadeShadows.java`, `render/shadow/VulkanisShadowPipelines.java`, `render/shadow/CascadePlans.java`, entity mixins). Shared surface is `pipeline.json` settings block only: add new keys, never rename/remove theirs. If `pipeline.json` conflicts, their change lands first, this plan rebases.

## Review Focus

- A legacy pack with no `passes`/`categories` keys renders exactly as today (no behavior change, no new log spam).
- A pack with `"category": 42` (non-string) is rejected with a one-line pack error on the Shaders screen, never a crash.
- A pass whose `in` names an unknown target fails pack load with a named error before any GPU work.
- Window resize mid-session rebuilds transient pass targets at the new size without leaking GPU buffers.
- A `settings.json` written before categories existed still loads (values are keyed by setting id; categories are additive).

---

### Task 1: `PackSetting` category field

**Files:**
- Modify: `src/client/java/com/example/vulkan/pack/PackSetting.java`
- Test: `src/test/java/com/example/vulkan/pack/PackSettingsTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: `PackSetting(String id, String label, String type, double def, double min, double max, double step, String category)` with `category()` normalized lowercase, blank/`null` → `"general"`. All existing call sites (`PackLoader.parseSettings`, tests) updated to the 8-arg constructor.

- [ ] **Step 1: Write the failing test**

```java
@Test public void categoryDefaultsToGeneral() throws Exception {
  File dir = pack("cat1", "[{\"id\":\"s\",\"label\":\"S\",\"type\":\"float\",\"default\":1,\"min\":0,\"max\":2,\"step\":0.1}]");
  var settings = PackLoader.parseSettings(dir);
  assertEquals("general", settings.get(0).category());
}
@Test public void categoryNormalizes() throws Exception {
  File dir = pack("cat2", "[{\"id\":\"b\",\"label\":\"B\",\"type\":\"float\",\"default\":1,\"min\":0,\"max\":2,\"step\":0.1,\"category\":\"Bloom\"}]");
  var settings = PackLoader.parseSettings(dir);
  assertEquals("bloom", settings.get(0).category());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=~/.local/share/FreesmLauncher/java/java-runtime-epsilon PATH="$JAVA_HOME/bin:$PATH" && ./gradlew test --tests "com.vulkanis.pack.PackSettingsTest" ` (from repo root)
Expected: FAIL (no `category()` method / 7-arg constructor mismatch).

- [ ] **Step 3: Write minimal implementation**

```java
package com.vulkanis.pack;
public record PackSetting(String id, String label, String type, double def, double min, double max, double step, String category) {
  public PackSetting {
    category = (category == null || category.isBlank()) ? "general" : category.toLowerCase(java.util.Locale.ROOT);
  }
  public boolean isBool() { return "bool".equals(type); }
  public boolean isInt() { return "int".equals(type); }
  public double clamp(double v) { return Math.min(max, Math.max(min, v)); }
}
```

And in `PackLoader.parseSettings`, read the optional field and pass it through:

```java
String category = "";
if (obj.has("category")) {
  JsonElement ce = obj.get("category");
  if (!ce.isJsonPrimitive() || !ce.getAsJsonPrimitive().isString())
    throw new PackException("bad setting category (want string)");
  category = ce.getAsString();
}
out.add(new PackSetting(id, optString(obj, "label", ""),
  type, optDouble(obj, "default", 0), optDouble(obj, "min", 0),
  optDouble(obj, "max", 1), optDouble(obj, "step", 1), category));
```

Fix every other `new PackSetting(` call site (grep) to pass a category (existing tests pass `""` where they mean default).

- [ ] **Step 4: Run tests to verify they pass**

Run: same `./gradlew test --tests "com.vulkanis.pack.PackSettingsTest"` command.
Expected: PASS, and the full suite still passes (`./gradlew test`).

- [ ] **Step 5: Stop (no commit per repo rule)**

---

### Task 2: Categories order list + grouping helper

**Files:**
- Create: `src/client/java/com/example/vulkan/pack/PackCategories.java`
- Modify: `src/client/java/com/example/vulkan/pack/PackLoader.java` (add `parseCategories`)
- Test: `src/test/java/com/example/vulkan/pack/PackCategoriesTest.java`

**Interfaces:**
- Consumes: `List<PackSetting>` (Task 1).
- Produces: `PackCategories.parseOrder(File packDir) -> List<Category>` where `record Category(String id, String label)`; `PackCategories.groupOrder(List<PackSetting> settings, List<Category> explicit) -> List<Category>` returning explicit order first, then leftover first-seen setting categories labeled by capitalized id.

Rules: no `categories` key → `parseOrder` returns `List.of()`; entry missing `id` → `PackException`; `id` blank → `PackException`; non-string `label` → `PackException`; duplicate ids → `PackException`.

- [ ] **Step 1: Write the failing test**

```java
@Test public void emptyWhenAbsent() throws Exception {
  File dir = PackSettingsTest.pack("cata", "[]");
  assertTrue(PackLoader.parseCategories(dir).isEmpty());
}
@Test public void explicitOrderThenLeftovers() {
  var settings = java.util.List.of(
    new PackSetting("a", "A", "float", 1, 0, 2, 0.1, "shadow"),
    new PackSetting("b", "B", "float", 1, 0, 2, 0.1, "bloom"),
    new PackSetting("c", "C", "float", 1, 0, 2, 0.1, "custom"));
  var explicit = java.util.List.of(new PackCategories.Category("bloom", "Bloom"));
  var order = PackCategories.groupOrder(settings, explicit);
  assertEquals(java.util.List.of("bloom", "shadow", "custom"),
    order.stream().map(PackCategories.Category::id).toList());
}
@Test public void duplicateIdsRejected() throws Exception {
  File dir = PackSettingsTest.pack("catb", "[]");
  java.nio.file.Files.writeString(new File(dir, "pipeline.json").toPath(),
    "{\"id\":\"catb\",\"categories\":[{\"id\":\"x\"},{\"id\":\"x\"}]}");
  assertThrows(PackLoader.PackException.class, () -> PackLoader.parseCategories(dir));
}
```

(`PackSettingsTest.pack`/`delete` helpers are package-visible static; reuse them, do not duplicate.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.vulkanis.pack.PackCategoriesTest"` (with Java 25 env).
Expected: FAIL (classes do not exist).

- [ ] **Step 3: Write minimal implementation**

```java
package com.vulkanis.pack;
import java.io.File;
import java.util.*;
public final class PackCategories {
  public record Category(String id, String label) {}
  private PackCategories() {}
  public static List<Category> groupOrder(List<PackSetting> settings, List<Category> explicit) {
    List<Category> out = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (Category c : explicit) {
      if (seen.add(c.id())) out.add(c);
    }
    Map<String, String> firstLabel = new LinkedHashMap<>();
    for (PackSetting s : settings) firstLabel.putIfAbsent(s.category(), cap(s.category()));
    for (Category c : explicit) firstLabel.remove(c.id());
    for (var e : firstLabel.entrySet()) {
      if (seen.add(e.getKey())) out.add(new Category(e.getKey(), e.getValue()));
    }
    return out;
  }
  static String cap(String id) {
    return id.isEmpty() ? id : Character.toUpperCase(id.charAt(0)) + id.substring(1);
  }
}
```

`PackLoader.parseCategories(File packDir)` mirrors `parseSettings` plumbing: read `pipeline.json` via the existing private `parsePipeline`, return `List.of()` when absent, validate each entry is an object with non-blank string `id` and optional string `label` (default: capitalized id), reject duplicates.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.vulkanis.pack.*"` (Java 25 env).
Expected: PASS.

- [ ] **Step 5: Stop (no commit per repo rule)**

---

### Task 3: Pass-graph spec + validation (pure data, no GPU)

**Files:**
- Create: `src/client/java/com/example/vulkan/pack/PassGraph.java`
- Modify: `src/client/java/com/example/vulkan/pack/PackLoader.java` (add `parsePasses`)
- Test: `src/test/java/com/example/vulkan/pack/PassGraphTest.java`

**Interfaces:**
- Consumes: `File packDir` (reads `pipeline.json` + checks `shaders/<frag>` existence).
- Produces: `PassGraph(List<Pass> passes)` with `record Pass(String name, String frag, List<String> in, double size)`; `PassGraph.isLegacy()` true when `passes` is empty. Limits: max 8 passes, max 4 distinct transient outputs, `size` in `(0, 1]`, `name` = `[a-z0-9_]+`, `in` entries resolve to `"main"`, `"depth"`, or an earlier pass's output (declaration order = execution order, no forward refs, so cycles are impossible by construction).

Validation errors (all `PackException` with the offending name in the message): missing/blank `name`, duplicate pass `name`, >8 passes, >4 transient outputs, `size` out of range, `in` naming unknown target, `shaders/<frag>` file missing, pass named `main`/`depth`/`final` (reserved).

- [ ] **Step 1: Write the failing test**

```java
@Test public void legacyWhenAbsent() throws Exception {
  File dir = PackSettingsTest.pack("p0", "[]");
  assertTrue(PackLoader.parsePasses(dir).isLegacy());
}
@Test public void threePassBloomResolves() throws Exception {
  File dir = PackSettingsTest.pack("p1", "[]");
  var shaders = new File(dir, "shaders");
  for (String f : new String[]{"bright.fsh", "blur.fsh", "composite.fsh", "composite.vsh"})
    new File(shaders, f).createNewFile();
  java.nio.file.Files.writeString(new File(dir, "pipeline.json").toPath(), "{\"id\":\"p1\",\"settings\":[],"
    + "\"passes\":[{\"name\":\"bright\",\"frag\":\"bright.fsh\",\"size\":0.25},"
    + "{\"name\":\"blur\",\"frag\":\"blur.fsh\",\"in\":[\"bright\"],\"size\":0.25},"
    + "{\"name\":\"final\",\"frag\":\"composite.fsh\",\"in\":[\"main\",\"blur\"]}]}");
  var g = PackLoader.parsePasses(dir);
  assertEquals(3, g.passes().size());
  assertEquals(java.util.List.of("bright"), g.passes().get(1).in());
}
@Test public void unknownInputRejected() throws Exception {
  File dir = PackSettingsTest.pack("p2", "[]");
  var shaders = new File(dir, "shaders");
  new File(shaders, "x.fsh").createNewFile();
  new File(shaders, "composite.vsh").createNewFile();
  java.nio.file.Files.writeString(new File(dir, "pipeline.json").toPath(), "{\"id\":\"p2\",\"settings\":[],"
    + "\"passes\":[{\"name\":\"x\",\"frag\":\"x.fsh\",\"in\":[\"nope\"]}]}");
  assertThrows(PackLoader.PackException.class, () -> PackLoader.parsePasses(dir));
}
@Test public void tooManyPassesRejected() throws Exception {
  File dir = PackSettingsTest.pack("p3", "[]");
  var shaders = new File(dir, "shaders");
  StringBuilder b = new StringBuilder("{\"id\":\"p3\",\"settings\":[],\"passes\":[");
  for (int i = 0; i < 9; i++) {
    if (i > 0) b.append(",");
    new File(shaders, "f" + i + ".fsh").createNewFile();
    b.append("{\"name\":\"f").append(i).append("\",\"frag\":\"f").append(i).append(".fsh\"}");
  }
  b.append("]}");
  java.nio.file.Files.writeString(new File(dir, "pipeline.json").toPath(), b.toString());
  assertThrows(PackLoader.PackException.class, () -> PackLoader.parsePasses(dir));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.vulkanis.pack.PassGraphTest"` (Java 25 env).
Expected: FAIL (no `parsePasses` / `PassGraph`).

- [ ] **Step 3: Write minimal implementation**

`PassGraph.java`: immutable holder + `isLegacy()`. `PackLoader.parsePasses`: return empty graph when key absent; otherwise validate every rule above using the existing `parsePipeline` helper and `optString`/`optDouble` helpers. `in` defaults to `["main"]`; `size` defaults to `1.0`. Transient outputs = every pass name except `"final"`; count distinct, cap 4.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.vulkanis.pack.*"` (Java 25 env).
Expected: PASS.

- [ ] **Step 5: Stop (no commit per repo rule)**

---

### Task 4: `PipelineSpec` carries passes + categories

**Files:**
- Modify: `src/client/java/com/example/vulkan/pack/PipelineSpec.java`
- Modify: `src/client/java/com/example/vulkan/pack/PackLoader.java` (`loadSpec` fills new fields)
- Test: extend `src/test/java/com/example/vulkan/pack/PackValidationTest.java` (read it first; follow its style)

**Interfaces:**
- Consumes: `PassGraph`, `List<PackCategories.Category>` (Tasks 2–3).
- Produces: `PipelineSpec` gains `PassGraph passes()` and `List<Category> categories()`; `loadSpec` on a legacy pack yields `isLegacy()==true` and `categories()==List.of()` without error.

- [ ] **Step 1: Write the failing test** (in `PackValidationTest` style: legacy Vulkanic-style `pipeline.json` loads; check `spec.passes().isLegacy()` and `spec.categories().isEmpty()`; a graph pack loads with 3 passes).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.vulkanis.pack.PackValidationTest"` (Java 25 env).
Expected: FAIL (no accessors).

- [ ] **Step 3: Write minimal implementation** (extend the record; `loadSpec` calls `parsePasses`/`parseCategories`, wrapping their `PackException`s as-is so the message reaches the screen).

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.vulkanis.pack.*"` then full `./gradlew test` (Java 25 env).
Expected: PASS.

- [ ] **Step 5: Stop (no commit per repo rule)**

---

### Task 5: Settings tabs in `VulkanisShaderScreen`

**Files:**
- Modify: `src/client/java/com/example/vulkan/screen/VulkanisShaderScreen.java`
- Test: `src/test/java/com/example/vulkan/screen/SettingsTabsTest.java` (new; pure-logic only)

**Interfaces:**
- Consumes: `PackCategories.groupOrder` + `PackLoader.parseCategories` (Task 2).
- Produces: screen shows a tab row (`Shadow | Bloom | …`) above the settings list; only the selected tab's settings render with existing stepper/Reset widgets. Extract `static List<PackSetting> visibleSettings(List<PackSetting> all, String tab)` and `static String defaultTab(...)` as pure static helpers — these are what the unit test covers (GUI widgets themselves are verified in-game via the Shaders screen).

Behavior: `private String selectedTab` field, initialized to first tab on pack change (reset when `selected` pack id differs from last render); tab buttons call `selectedTab = id; rebuildWidgets();`. More tabs than fit (6+): render a single `Tab: <label> >` cycler button instead of a row. Zero settings → no tab row (as today). `settings.json` read/write unchanged (keyed by id).

- [ ] **Step 1: Write the failing test**

```java
@Test public void filtersByTab() {
  var all = java.util.List.of(
    new PackSetting("a", "A", "float", 1, 0, 2, 0.1, "shadow"),
    new PackSetting("b", "B", "float", 1, 0, 2, 0.1, "bloom"));
  assertEquals(1, VulkanisShaderScreen.visibleSettings(all, "bloom").size());
  assertEquals("b", VulkanisShaderScreen.visibleSettings(all, "bloom").get(0).id());
}
@Test public void defaultIsFirstTab() {
  var order = java.util.List.of(
    new PackCategories.Category("shadow", "Shadow"),
    new PackCategories.Category("bloom", "Bloom"));
  assertEquals("shadow", VulkanisShaderScreen.defaultTab(order));
  assertEquals("general", VulkanisShaderScreen.defaultTab(java.util.List.of()));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.vulkanis.screen.SettingsTabsTest"` (Java 25 env). Note: screen classes reference Minecraft classes — if the test source set cannot load them, move the two helpers to `PackCategories` (`visibleSettings`, `defaultTabId`) and test there instead; the screen calls them.
Expected: FAIL then implement accordingly.

- [ ] **Step 3: Write minimal implementation** (helpers + tab row in `addSettings`; keep all existing stepper/Reset/`flipBool`/`stepSetting` code paths untouched, just iterate the filtered list).

- [ ] **Step 4: Run tests + in-game check**

Run: `./gradlew test` (Java 25 env) PASS; then `./gradlew build`, copy jar to instance mods, open Shaders screen on Vulkanic pack, confirm tabs render and stepping still hot-reloads (`composite load` lines in `latest.log`).

- [ ] **Step 5: Stop (no commit per repo rule)**

---

### Task 6: Multi-pass executor in `render/`

**Files:**
- Create: `src/client/java/com/example/vulkan/render/PassExecutor.java`
- Modify: `src/client/java/com/example/vulkan/render/CompositeRenderer.java` (delegate per-pass draws to it; keep legacy single-pass path intact)
- Modify: `src/client/java/com/example/vulkan/VulkanisClient.java` (`loadComposite` loads every pass frag with `{{token}}` substitution into `ShaderLibrary` keys `post/<passname>`)
- Test: `src/test/java/com/example/vulkan/render/PassExecutorTest.java` (pure resolution logic, no GPU)

**Interfaces:**
- Consumes: `PassGraph` (Task 3), `ShaderLibrary`, `PackValues`.
- Produces: `PassExecutor.targetSize(int w, int h, double scale)`, `PassExecutor.samplerName(String passOutput)` → `"In" + Capitalized` (e.g. `blur` → `InBlur`), `PassExecutor.resolveInputs(Pass, Map<String,Integer>)` (pure, tested). Runtime: owns one transient target per non-`final` pass output (sized `w*size × h*size`, rebuilt on resize/pack switch, closed on `close()`), compiles one pipeline per pass via the existing `device.compilePipeline` + `PipelineCache` path, binds `SamplerInfo` + `InSampler`/`InDepth` for `main`/`depth` plus `In<Name>` for pass outputs, draws `final` to the main color view and intermediate passes to their targets with `OptionalDouble.empty()` LOAD semantics (same rule as shadow targets: never clear mid-chain).

Legacy path: `PassGraph.isLegacy()` → today's exact behavior (one `post/composite` pipeline, same bind calls).

- [ ] **Step 1: Write the failing test**

```java
@Test public void samplerNames() {
  assertEquals("InBlur", PassExecutor.samplerName("blur"));
  assertEquals("InBright", PassExecutor.samplerName("bright"));
}
@Test public void targetSizes() {
  assertEquals(new PassExecutor.Size(480, 270), PassExecutor.targetSize(1920, 1080, 0.25));
}
@Test public void rejectsUnknownInputPure() {
  var known = java.util.Map.of("main", 0, "bright", 1);
  assertThrows(IllegalArgumentException.class,
    () -> PassExecutor.resolveInputs(java.util.List.of("nope"), known));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.vulkanis.render.PassExecutorTest"` (Java 25 env).
Expected: FAIL (class missing).

- [ ] **Step 3: Write minimal implementation** (pure helpers first; then the GPU executor reusing `CompositeRenderer`'s compile/UBO/depth-bind code paths — move shared snippets into package-private static helpers if duplication exceeds ~20 lines, otherwise duplicate deliberately to avoid disturbing the legacy path).

- [ ] **Step 4: Run tests + in-game check**

Run: `./gradlew test` PASS; `./gradlew build`, deploy jar, select DemoShader (still legacy → unchanged picture), confirm `latest.log` shows no composite errors and one `LOG.info` per executed pass.

- [ ] **Step 5: Stop (no commit per repo rule)**

---

### Task 7: Exposure float in the `SamplerInfo` padding slot

**Files:**
- Modify: `src/client/java/com/example/vulkan/render/CompositeRenderer.java` (`refreshSamplerInfo` writes exposure at the `Pad0` byte offset; document the offset)
- Modify: `run/shaderpacks/VulkanicShader/shaders/composite.vsh` + `composite.fsh` (declare `float Exposure;` where `float Pad0;` was — keep position, rename only)
- Modify: `run/shaderpacks/DemoShader/shaders/composite.vsh` (same rename)
- Test: `src/test/java/com/example/vulkan/render/SamplerInfoLayoutTest.java` (new; asserts the exposure float offset equals the old `Pad0` offset and total size stays 192)

**Interfaces:**
- Consumes: `ShadowHookState.sunDir()` (already available).
- Produces: v1 CPU value `exposure = smoothed(1.0 + nightBoost)` where `nightBoost = clamp(1.0 - sunDirY * 2.0, 0, 1) * 0.8`, smoothed with exponential damping toward target (`stored += (target - stored) * min(1, dt * 2)`, `dt` from frame timestamps already available in the render path — if none, fixed `0.05` factor per frame). Packs use `color * Exposure`. The slot/offset is now reserved: a future measured-luma driver replaces only the CPU computation, pack GLSL stays valid.

GLSL stays std140-exact: same 192 bytes, same offsets; only the name at the `Pad0` offset changes. Legacy packs declaring `Pad0` keep compiling and rendering (they simply ignore the value).

- [ ] **Step 1: Write the failing test**

```java
@Test public void exposureOffsetMatchesPad0() {
  assertEquals(CompositeRenderer.SAMPLER_EXPOSURE_OFFSET, CompositeRenderer.SAMPLER_PAD0_OFFSET);
  assertEquals(192, CompositeRenderer.SAMPLER_INFO_BYTES);
}
@Test public void nightBoostRange() {
  assertEquals(1.0, PassExecutor.exposureTarget(1.0), 1e-9);   // noon sun
  assertEquals(1.8, PassExecutor.exposureTarget(-1.0), 1e-9);  // midnight
}
```

(If the constants live on `CompositeRenderer`, expose them package-private static; put `exposureTarget(float sunY)` next to them or on `PassExecutor` — pick one place and keep the test honest to it.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.vulkanis.render.SamplerInfoLayoutTest"` (Java 25 env).
Expected: FAIL.

- [ ] **Step 3: Write minimal implementation** (constants + `exposureTarget` + write into the buffer at the existing `Pad0` position in `refreshSamplerInfo`; GLSL renames).

- [ ] **Step 4: Run tests + in-game check**

Run: `./gradlew test` PASS; `./gradlew build`, deploy, confirm `latest.log` has no composite errors on both packs and night frames brighten smoothly (ask the user to judge visuals; never screenshot).

- [ ] **Step 5: Stop (no commit per repo rule)**

---

### Task 8: Pack content — tabs, bloom passes, contract docs

**Files:**
- Modify: `run/shaderpacks/VulkanicShader/pipeline.json` (add `categories` + per-setting `category`; add `bloomThreshold`/`bloomStrength`/`bloomRadius` under `bloom`; add `passes: [bright, blur, final]`)
- Create: `run/shaderpacks/VulkanicShader/shaders/bright.fsh`, `run/shaderpacks/VulkanicShader/shaders/blur.fsh` (luma-gated bright-pass + 9-tap blur from the taught bloom recipe, with `{{bloomThreshold}}`/`{{bloomRadius}}` tokens)
- Modify: `run/shaderpacks/VulkanicShader/shaders/composite.fsh` (additive combine `c + bloom * {{bloomStrength}}`, keep tonemap/vignette/Show Depth)
- Modify: `run/shaderpacks/DemoShader/*` (2 settings under `effects`; same 3-pass shape as the minimal example)
- Modify: `run/shaderpacks/PACK_CONTRACT.md` (document `passes`, `categories`, named `In<Name>` samplers, `Exposure` field, caps)

**Interfaces:**
- Consumes: Tasks 1–7.
- Produces: both packs load with `tokensLeft=false` in the log; instance `shaderpacks/` dirs are symlinked to `run/` (shaders dir + `pipeline.json`), so no copy step — just re-select the pack in-game to hot-reload.

- [ ] **Step 1: Write the failing test** (pack-level: `VulkanicShaderPackTest`-style — read that file first and follow it; assert the shipped `run/` packs parse: 3 passes, categories non-empty, every `{{token}}` in every pass frag resolves against settings).

```java
@Test public void shippedPacksSelfConsistent() throws Exception {
  for (String id : new String[]{"VulkanicShader", "DemoShader"}) {
    File dir = new File("run/shaderpacks/" + id);
    var settings = PackLoader.parseSettings(dir);
    var passes = PackLoader.parsePasses(dir);
    var values = PackValues.load(new File(dir, "settings.json"), settings);
    for (var p : passes.passes()) {
      String glsl = java.nio.file.Files.readString(new File(dir, "shaders/" + p.frag()).toPath());
      String applied = values.apply(glsl, settings);
      assertFalse(applied.contains("{{"), id + "/" + p.frag() + " has unresolved token");
    }
  }
}
```

(If the unit-test working dir is not the repo root, resolve `run/` relative to a `vulkanis.root` system property or skip gracefully — do NOT hardcode a home directory.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.vulkanis.pack.VulkanicShaderPackTest"` (Java 25 env).
Expected: FAIL (no passes/categories in shipped packs yet).

- [ ] **Step 3: Write minimal implementation** (pack JSON + the two new `.fsh` files + `PACK_CONTRACT.md` section).

- [ ] **Step 4: Run tests + in-game check**

Run: `./gradlew test` then `./gradlew build`; deploy jar; re-select each pack; `latest.log` shows `composite load settings=<n> tokensLeft=false` and per-pass execution lines; user judges bloom/tabs visually.

- [ ] **Step 5: Stop (no commit per repo rule)**

---

## Self-Review

- Spec coverage: named samplers (Tasks 3, 6, 8), 8/4 caps (Task 3), exposure-as-float (Task 7), explicit-categories-with-fallback (Task 2), free-form ids (Tasks 1–2, 5). Legacy behavior pinned by Tasks 3–4 tests + Task 6 legacy path + Task 8 token test.
- No placeholders: every step names exact files, signatures, commands, and expected output.
- Type consistency: `PassGraph.Pass(String, String, List<String>, double)`, `PackCategories.Category(String, String)`, `PackSetting(…, String category)`, sampler `In` + capitalized output name, exposure at ex-`Pad0` offset, 192 bytes total.
- Commit steps replaced with stop steps per the repo's no-commit rule.
