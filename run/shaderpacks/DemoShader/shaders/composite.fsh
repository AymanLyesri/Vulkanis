#version 330
#extension GL_ARB_separate_shader_objects : require

// Pass 2 of 2: vignette over the gray pass. InGray is the "gray" pass output
// (pass name -> In + Capitalized). {{vigStrength}} darkens the corners.
uniform sampler2D InGray;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main(){
    vec3 c = texture(InGray, texCoord).rgb;
    float dist = distance(texCoord, vec2(0.5));
    fragColor = vec4(c * (1.0 - dist * {{vigStrength}}), 1.0);
}
