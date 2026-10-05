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
