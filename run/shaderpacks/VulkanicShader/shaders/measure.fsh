#version 330
#extension GL_ARB_separate_shader_objects : require

// Exposure measure: 8x8 average scene luma blended with the previous frame
// (InMeasure reads last frame via ping-pong). Output feeds InMeasure (.r)
// for later passes this frame. First frames sweep from dark: eye-adapting,
// not a bug. {{exposureSpeed}} sets adapt rate (higher = faster).
uniform sampler2D InSampler;
uniform sampler2D InMeasure;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main(){
    // Center-weighted metering: taps near the crosshair count most, so
    // look-direction drives exposure (staring at the sun matters; a bright
    // corner doesn't). Weight falls off quadratically with screen distance.
    float sum = 0.0;
    float sumW = 0.0;
    for (int x = 0; x < 8; x++)
    for (int y = 0; y < 8; y++) {
        vec2 uv = (vec2(x, y) + 0.5) / 8.0;
        float d = distance(uv, vec2(0.5));
        float w = 1.0 / (1.0 + 8.0 * d * d);
        vec3 s = texture(InSampler, uv).rgb;
        sum += dot(s, vec3(0.299, 0.587, 0.114)) * w;
        sumW += w;
    }
    float avg = sum / sumW;
    float prev = texture(InMeasure, vec2(0.5)).r;
    float adapted = mix(prev, avg, {{exposureSpeed}});
    fragColor = vec4(adapted, adapted, adapted, 1.0);
}
