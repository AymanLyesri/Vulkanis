#version 330
#extension GL_ARB_separate_shader_objects : require

// Pass 1 of 2: grayscale. {{grayMix}} blends color -> gray (0 = off, 1 = full).
uniform sampler2D InSampler;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main(){
    vec3 c = texture(InSampler, texCoord).rgb;
    float g = dot(c, vec3(0.299, 0.587, 0.114));
    fragColor = vec4(mix(c, vec3(g), {{grayMix}}), 1.0);
}
