#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D InSampler;
uniform sampler2D InDepth;
uniform sampler2D InBlur;
uniform sampler2D InMeasure;
uniform sampler2D InSlow;

layout(std140) uniform SamplerInfo {
  vec2 OutSize;
  vec2 InSize;
  int ShowDepth;
  float Proj22;
  float Proj32;
  float Exposure;
  vec3 SunDir;
  vec3 CamPos;
  mat4 InvViewProj;
  mat4 ViewProj;
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

float linearize(float d) { return Proj32 / (d + Proj22); }

void main() {

  // ============================================================
  // 1. DEPTH DEBUG VIEW
  // ============================================================
  // Displays the linearized scene depth instead of the normal
  // scene color when ShowDepth is enabled.
  // ============================================================

  if (ShowDepth == 1) {

    float depth = linearize(texture(InDepth, texCoord).r);

    // Normalize the depth into the 0.0 - 1.0 display range.
    // Increase 200.0 to make farther objects visible.
    depth /= 200.0;

    depth = clamp(depth, 0.0, 1.0);

    fragColor = vec4(vec3(depth), 1.0);

    return;
  }

  // ============================================================
  // 2. SCENE COLOR
  // ============================================================
  // Read the rendered Minecraft scene.
  // ============================================================

  vec3 c = texture(InSampler, texCoord).rgb;

  // ============================================================
  // 3. AUTO EXPOSURE
  // ============================================================
  // InSlow contains the average luminance of the scene.
  //
  // We use it to determine how much the entire image should
  // be brightened or darkened.
  // ============================================================

  float avg = texture(InSlow, vec2(0.5)).r;

  // Prevent division by zero in extremely dark scenes.

  float safeAvg = max(avg, 0.001);

  // Calculate the desired exposure multiplier.

  float measured = {{exposureKey}} / safeAvg * {{exposureBias}};

  // Prevent exposure from becoming excessively strong
  // or excessively weak.

  measured = clamp(measured, {{exposureMin}}, {{exposureMax}});

  // Apply the exposure only when enabled.
  //
  // Disabled:
  //     multiplier = 1.0
  //
  // Enabled:
  //     multiplier = measured
  // ============================================================

  c *= mix(1.0, measured, float({{exposureEnabled}}));

  // ============================================================
  // 4. PER-PIXEL LUMINANCE NORMALIZATION
  // ============================================================
  // Intentionally disabled.
  //
  // This was:
  //
  //     float lum = dot(
  //         c,
  //         vec3(0.299, 0.587, 0.114)
  //     );
  //
  //     c *= 1.0 / (lum + 0.5);
  //
  // This changes every pixel independently according to its
  // luminance. Bright sky pixels can lose their natural color
  // relationships and appear washed out.
  //
  // Keep this disabled for now.
  // ============================================================

  // ============================================================
  // 5. BLOOM
  // ============================================================
  // Add the blurred bright areas back onto the scene.
  //
  // InBlur contains the blurred bloom image.
  // ============================================================

  c += texture(InBlur, texCoord).rgb * {{bloomStrength}} *
       float({{bloomEnabled}});

  // ============================================================
  // 6. VIGNETTE
  // ============================================================
  // Calculate how far the current pixel is from the center
  // of the screen.
  // ============================================================

  float dist = distance(texCoord, vec2(0.5));

  // Darken the image progressively toward the edges.

  float vignette = 1.0 - dist * {{vignetteStrength}};

  // Prevent negative color values.

  vignette = max(vignette, 0.0);

  c *= vignette;

  // ============================================================
  // 7. FINAL COLOR
  // ============================================================
  // Output the processed scene.
  // ============================================================

  fragColor = vec4(c, 1.0);
}