# Entity Shadows (Casters) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Mobs, animals, and players cast shadows onto terrain through the existing 4-cascade maps. Jar change; packs need nothing new.

**Architecture:** Inside the existing shadow `FramePass`, after terrain casters, submit nearby entities' render states with a sun-oriented camera state so vanilla entity pipelines draw them (depth counts, color is cleared) into each refit cascade's depth target. Terrain receiver already samples the maps — entity shadows appear for free. Entity-on-entity receiving is out of scope (vanilla entity shaders).

**Tech Stack:** Java (Gradle 9.4.1, Java 25), vanilla 26.3 stateless entity API (`extractEntity`/`submit`), JUnit 5.

**Spec:** User 2026-10-05: "make the shadows get cast from the entities also like animals, players, mobs etc". Confirmed jar-side (packs can't reach entity rendering).

## Global Constraints

- Never `git commit` unless the user explicitly asks.
- Gradle MUST run under Java 25: `JAVA_HOME=~/.local/share/FreesmLauncher/java/java-runtime-epsilon`.
- `./gradlew build`: check exit code explicitly; NEVER pipe through `grep`.
- Mixin `@Inject` handlers MUST declare the FULL target signature; `require=0` where vanilla may vary.
- No screenshots; verify via log + user in-game report.
- Sulkan guard untouched.

## Review Focus

- No-entity frames (empty area) must cost ~nothing (early-out, no submits).
- Broken/empty entity states must never break the terrain shadow pass (per-entity try/catch or pre-validation).
- First-person player body and name tags must not pollute the maps.
- Big entity counts (village + farm) must not tank the frame — cap + distance gate per cascade.
- Shadows must vanish when the pack lacks shadow shaders (gate already in `shadowsActive` — entity path must check it too).

---

### Task 1: Spike — how entity submits reach a pass (timeboxed)

**Files:**
- Read-only: vanilla bytecode via `javap -c` on `minecraft-client.jar` (`LevelRenderer`, `FeatureRenderDispatcher`, submit consumers).
- Throwaway probe mixin (delete after): log entity count + submit-path names from inside our shadow pass. NO production code.

**Interfaces:**
- Consumes: nothing.
- Produces: a ledger-recorded mechanism decision: WHERE entity states are submitted (which method, which collector) and WHETHER our `FramePass.executes` context can submit+execute them into our own depth target.

- [ ] **Step 1: Find the submit call chain**

Run: `javap -c -cp <client.jar> net.minecraft.client.renderer.LevelRenderer | grep -B5 -A2 "extractEntity\|SubmitNode" | head -40`
Expected: the method(s) that extract entities and the collector they submit into.

- [ ] **Step 2: Find the execute side**

Identify what consumes `SubmitNodeStorage` per pass (feature phases) and whether a `FramePass` can own that cycle for our target.
Expected: yes/no + the 2-3 classes involved.

- [ ] **Step 3: Record the ruling**

Append to the plan ledger: mechanism + chosen hook point + what could still fail. If the mechanism is "impossible without engine rewrite," STOP and report to user instead of continuing.

### Task 2: Entity caster submission in the shadow pass

**Files:**
- Modify: `src/client/java/com/example/vulkan/mixin/VulkanisShadowPassMixin.java` (entity loop after terrain loop, per refit cascade)
- Test: headless-testable parts extracted to pure helpers (distance gate, entity cap) with unit tests; GPU path verified in-game by user.

**Interfaces:**
- Consumes: Task 1 mechanism; `CascadeShadows.liveMatrix/cascadeReady/shouldUpdateCascade`; `ShadowHookState.shadowsActive()`.
- Produces: entities drawn (depth) into refit cascade targets; nothing when gate off / no entities / sun down.

- [ ] **Step 1: Write failing tests for the pure helpers**

```java
@Test public void entityCapIsRespected() {
  assertTrue(EntityCasterGate.limit(300) <= 200, "never submit more than the cap");
}
@Test public void farEntitiesAreSkipped() {
  assertFalse(EntityCasterGate.inRange(1000.0, 128.0f));
  assertTrue(EntityCasterGate.inRange(10.0, 128.0f));
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew test --tests "*EntityCasterGate*"`
Expected: FAIL, class undefined.

- [ ] **Step 3: Minimal implementation**

New `render/shadow/EntityCasterGate.java` (pure: cap constant ~100, per-cascade range check vs cascade end + margin). Mixin: iterate `Minecraft.getInstance().level.entitiesForRendering()` (confirm name in spike — may be `entitiesForRendering` supplier), skip players in first person? (spike decides: skip `cameraEntity` when first-person), `shouldRender` against sun frustum OR simple distance gate (spike decides), `extractEntity(e, partialTick)` + `submit(...)` with synthesized `CameraRenderState` (projection = cascade ortho, viewRotation = sun orientation). All inside per-cascade try/catch; skip when `!shadowsActive()`.

- [ ] **Step 4: Build + full suite**

Run: `./gradlew build`
Expected: exit 0, suite green.

### Task 3: Deploy + in-game verification checklist (user-driven)

**Files:** none (deploy only).

- [ ] **Step 1: Deploy jar + packs to instance**
- [ ] **Step 2: User confirms:** sunny noon, spawn/cow nearby → visible blob on ground tracking the animal; player shadow in third person; no shadow from name tags; F3-friendly frame rate near a village.
- [ ] **Step 3: If broken, pull `grep -a "vulkanis" logs/latest.log | tail` and debug from there.**

## Execution Handoff

Plan saved. Task 1 is a spike with a STOP condition — if entities can't be submitted into our pass, I'll report back rather than burn effort. Recommend **Native** (spike needs live codebase judgment). Proceed — Native or Subagent-driven?
