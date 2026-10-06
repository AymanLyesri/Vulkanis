#version 330
#extension GL_ARB_separate_shader_objects : require

// Eye follower: trails the fast InMeasure with asymmetric rates —
// quick squint when the scene brightens ({{adaptBright}}), slow night
// vision when it darkens ({{adaptDark}}). InSlow reads last frame
// via ping-pong. Mirrors ExposureCurve.blend exactly (strict >).
uniform sampler2D InMeasure;
uniform sampler2D InSlow;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main(){
    float cur = texture(InMeasure, vec2(0.5)).r;
    float prev = texture(InSlow, vec2(0.5)).r;
    float rate = cur > prev ? {{adaptBright}} : {{adaptDark}};
    float adapted = mix(prev, cur, rate);
    fragColor = vec4(adapted, adapted, adapted, 1.0);
}
