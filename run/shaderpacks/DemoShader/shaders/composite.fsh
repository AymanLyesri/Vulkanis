#version 330
#extension GL_ARB_separate_shader_objects : require

// Game picture + depth. Depth = how far the thing behind each pixel is.
uniform sampler2D InSampler;
uniform sampler2D InDepth;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
  vec2 texel = 1.0 / vec2(textureSize(InSampler, 0));
  float threshold = 0.45; // torches live ~0.5 luma, sun/sky ~0.8+
  float strength = 0.8;

  vec3 c = texture(InSampler, texCoord).rgb;
  vec3 sum = vec3(0.0);
  for (int x = -1; x <= 1; x++)
    for (int y = -1; y <= 1; y++) {
      vec3 s = texture(InSampler, texCoord + vec2(x, y) * texel * 3.0).rgb;
      float luma = dot(s, vec3(0.299, 0.587, 0.114));
      float w = clamp((luma - threshold) / (1.0 - threshold), 0.0, 1.0);
      sum += s * w; // keep full color, weight by brightness
    }
  sum /= 9.0;
  fragColor = vec4(c + sum * strength, 1.0);
}
