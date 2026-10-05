# Global Debug View + ModMenu Section Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Depth debug view rendered by the engine (works with any pack, yellow-close → purple-far) plus a ModMenu Vulkanis section with shader select and toggles.

**Architecture:** `CompositeRenderer` gains a second jar-embedded debug pipeline used instead of the pack pipeline whenever Show Depth is on; ModMenu integration is compile-only (`modCompileOnly`) with an entrypoint-gated class so the mod never crashes when ModMenu is absent.

**Tech Stack:** Java (Gradle 9.4.1, Java 25), GLSL 330, ModMenu 21.0.0 (Modrinth maven, compile-only), JUnit 5.

**Spec:** User 2026-10-05: (1) "depth only," yellow = close, purple = far, engine-side/global; (2) ModMenu section with Open-Shaders + toggles, keybinds stay in vanilla Controls.

## Global Constraints

- Never `git commit` unless the user explicitly asks.
- Gradle MUST run under Java 25: `JAVA_HOME=~/.local/share/FreesmLauncher/java/java-runtime-epsilon`.
- `./gradlew build`: check exit code explicitly; NEVER pipe through `grep`.
- `SamplerInfo` 192B layout FROZEN (debug pipeline reuses the same buffer object).
- Pack `composite.fsh` ShowDepth branch left untouched (now unreachable when global view is on, harmless).
- ModMenu absent at runtime must never crash us (compile-only dep + entrypoint gating only).

## Review Focus

- Debug view must look identical on every pack (it bypasses pack GLSL entirely) — verify by selecting DemoShader + VulkanicShader with Show Depth on.
- Far/sky pixels must clamp to purple, never NaN/white — `clamp` + `max(w, eps)` in shader.
- Missing ModMenu at runtime: our `VulkanisModMenu` class must never load (only referenced by the `modmenu` entrypoint key).
- Master toggle in the shader screen must persist via existing `VulkanisConfig.save` path.

---

### Task 1: Engine-side yellow→purple depth view

**Files:**
- Create: `src/client/java/com/example/vulkan/render/DebugView.java` (GLSL string constants)
- Modify: `src/client/java/com/example/vulkan/render/CompositeRenderer.java:77-91,93-177`
- Test: `src/test/java/com/example/vulkan/render/DebugViewTest.java`

**Interfaces:**
- Consumes: `ShadowHookState.showDepth()`, existing `samplerInfo` 192B buffer, `depthStore().view()` / `main.getDepthTextureView()`.
- Produces: when Show Depth is on, output = debug pipeline image; pack pipeline untouched/compile-skipped.

- [ ] **Step 1: Write the failing test**

```java
package com.vulkanis.render;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
public class DebugViewTest {
  @Test public void debugShadersAreTokenFree() {
    assertFalse(DebugView.FRAGMENT.contains("{{"), "pack tokens must never reach jar GLSL");
    assertTrue(DebugView.FRAGMENT.contains("InDepth"));
    assertTrue(DebugView.FRAGMENT.contains("SamplerInfo"));
  }
  @Test public void rampGoesYellowToPurple() {
    String f = DebugView.FRAGMENT;
    assertTrue(f.indexOf("1.0, 0.9, 0.0") < f.indexOf("0.55, 0.1, 0.9"), "yellow(close) must mix toward purple(far)");
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=~/.local/share/FreesmLauncher/java/java-runtime-epsilon PATH="$JAVA_HOME/bin:$PATH" && ./gradlew test --tests "com.vulkanis.render.DebugViewTest"`
Expected: FAIL, `DebugView` undefined.

- [ ] **Step 3: Write minimal implementation**

`DebugView.java`: `VERTEX` (fullscreen triangle, same shape as `composite.vsh`) + `FRAGMENT`:
```glsl
#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D InDepth;
layout(std140) uniform SamplerInfo { vec2 OutSize; vec2 InSize; int ShowDepth; float Proj22; float Proj32; float Pad0; vec3 SunDir; vec3 CamPos; mat4 InvViewProj; mat4 ViewProj; };
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;
void main(){
    float d = texture(InDepth, texCoord).r;
    float t = clamp((Proj32 / (d + Proj22)) / 200.0, 0.0, 1.0);
    vec3 c = mix(vec3(1.0, 0.9, 0.0), vec3(0.55, 0.1, 0.9), t);
    fragColor = vec4(c, 1.0);
}
```
`CompositeRenderer`: add `debugPipeline`/`debugCompiled`/`debugPending` fields + `ensureDebugPipeline(device)` (location `vulkanis:post/debug`, bind layout InDepth + SamplerInfo, own `ShaderSource` serving the two constants). In `render()`: after computing `showDepth`, if true use the debug pipeline/compile path (same pending/finish/insert pattern, own `errorLogged` reuse) and skip the composite compile block.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.vulkanis.render.DebugViewTest"`
Expected: PASS.

- [ ] **Step 5: Full build**

Run: `./gradlew build`
Expected: exit 0.

### Task 2: ModMenu Vulkanis section + master toggle in shader screen

**Files:**
- Modify: `build.gradle` (Modrinth repo + `modCompileOnly "maven.modrinth:modmenu:21.0.0"`)
- Modify: `src/main/resources/fabric.mod.json` (add `"modmenu": ["com.vulkanis.VulkanisModMenu"]` entrypoint)
- Create: `src/client/java/com/example/vulkan/VulkanisModMenu.java`
- Modify: `src/client/java/com/example/vulkan/screen/VulkanisShaderScreen.java:34-41` (master toggle button above pack list)
- Test: build resolves the dep (resolution IS the test); headless cannot load ModMenu classes.

**Interfaces:**
- Consumes: existing `VulkanisShaderScreen(parent)` constructor, `ShadowHookState.setCompositeEnabled/compositeEnabled`, `VulkanisConfig.save()`.
- Produces: ModMenu → Vulkanis entry → our screen; screen has ON/OFF master toggle persisting via save().

- [ ] **Step 1: Add dep + entrypoint (no test-first possible headless; build is the gate)**

`VulkanisModMenu.java`:
```java
package com.vulkanis;
public class VulkanisModMenu implements com.terraformersmc.modmenu.api.ModMenuApi {
  @Override
  public com.terraformersmc.modmenu.api.ConfigScreenFactory<?> getModConfigScreenFactory() {
    return parent -> new com.vulkanis.screen.VulkanisShaderScreen(parent);
  }
}
```
(Verify `VulkanisShaderScreen` has a `(Screen parent)` constructor — read the file head first; adapt if the ctor differs.)

- [ ] **Step 2: Master toggle in shader screen**

Above the pack loop in `init()`, add: `Button "Vulkanis Shaders: ON/OFF"` → `setCompositeEnabled(!compositeEnabled()); VulkanisConfig.save(); rebuildWidgets();` — wait, `save()` is package-visible static (`static void save()`), same package `com.vulkanis` vs screen package `com.vulkanis.screen` — NOT visible. Options: call `ShadowHookState.setCompositeEnabled` (which does NOT save) then replicate save, or widen `save` to public. Minimal: make `VulkanisConfig.save()` public (one word) and call it. Ledger the ruling.

- [ ] **Step 3: Build with dep resolution**

Run: `./gradlew build`
Expected: exit 0, modmenu resolved from Modrinth. If resolution fails (version missing), ledger + try `com.terraformersmc:modmenu:21.0.0` from Terraformers maven as fallback (add repo `https://maven.terraformersmc.com/releases/`).

- [ ] **Step 4: Deploy + user verifies in game**

Deploy jar + packs. User: Mods → Vulkanis → gear icon → our screen; Show Depth → yellow/purple depth on ANY pack; master toggle persists across reselect.

## Execution Handoff

Plan saved. Please review. Which execution approach?
- **Subagent-driven** - per-task implement + review gates.
- **Native** - I implement inline, one final review. Cheapest.

I recommend **Native**: 2 small tasks, shared files, failures surface at build time. Does the plan capture what you want, and which approach?
