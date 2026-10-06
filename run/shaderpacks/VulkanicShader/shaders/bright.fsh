#version 330
#extension GL_ARB_separate_shader_objects : require

// Bright-pass: keep only pixels brighter than {{bloomThreshold}} (luma-gated
// so orange torches survive). Runs at quarter res; blur.fsh spreads the glow.
uniform sampler2D InSampler;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main(){
    vec3 s = texture(InSampler, texCoord).rgb;
    float luma = dot(s, vec3(0.299, 0.587, 0.114));
    float w = clamp((luma - {{bloomThreshold}}) / (1.0 - {{bloomThreshold}}), 0.0, 1.0);
    fragColor = vec4(s * w, 1.0);
}
