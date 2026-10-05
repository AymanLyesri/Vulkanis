package com.vulkanis.render;

/** Jar-embedded debug view. Pack-independent: used instead of pack GLSL whenever
 * Show Depth is on, so it looks identical on every pack. */
public final class DebugView {
  private DebugView() {
  }

  public static final String VERTEX = """
      #version 330
      #extension GL_ARB_separate_shader_objects : require

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

      layout(location = 0) out vec2 texCoord;

      void main(){
          vec2 uv = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
          gl_Position = vec4(uv * vec2(2, 2) + vec2(-1, -1), 0, 1);
          texCoord = uv;
      }
      """;

  public static final String FRAGMENT = """
      #version 330
      #extension GL_ARB_separate_shader_objects : require

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

      void main(){
          float d = texture(InDepth, texCoord).r;
          // Mode 1 (main camera): perspective depth needs linearizing.
          // Mode 2 (sun cascade): ortho depth is already linear, show it raw.
          float t = ShowDepth > 1 ? clamp(d, 0.0, 1.0)
              : clamp((Proj32 / max(d + Proj22, 0.000001)) / 200.0, 0.0, 1.0);
          vec3 c = mix(vec3(1.0, 0.9, 0.0), vec3(0.55, 0.1, 0.9), t);
          fragColor = vec4(c, 1.0);
      }
      """;
}
