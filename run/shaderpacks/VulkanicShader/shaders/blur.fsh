#version 330
#extension GL_ARB_separate_shader_objects : require

// 9-tap box blur over the bright-pass at quarter res. {{bloomRadius}}
// scales the sample spread (texels): wider = softer, larger halo.
uniform sampler2D InBright;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main(){
    vec2 texel = 1.0 / vec2(textureSize(InBright, 0));
    vec3 sum = vec3(0.0);
    for (int x = -1; x <= 1; x++)
    for (int y = -1; y <= 1; y++) {
        sum += texture(InBright, texCoord + vec2(x, y) * texel * {{bloomRadius}}).rgb;
    }
    fragColor = vec4(sum / 9.0, 1.0);
}
