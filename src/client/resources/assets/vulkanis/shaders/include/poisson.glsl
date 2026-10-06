// Vulkanis api 1: Poisson-disk helpers for pack shadow filters.
// Pack declares its own VulkanisShadowMap samplers; this file only provides
// offsets and rotation. Sampling stays pack-side (samplers cannot be passed
// around on the glslang backend — branch per cascade in pack code).

// 16 uniform Poisson taps on the [-1, 1] disk. Scale by
// ShadowParams.z (filter radius) * map texel size.
const vec2 VULKANIS_POISSON16[16] = vec2[16](
	vec2(-0.9420, -0.3997), vec2(0.9456, -0.7687), vec2(-0.0942, -0.9294),
	vec2(0.3448, 0.2934), vec2(-0.9159, 0.4579), vec2(-0.8154, -0.8790),
	vec2(-0.3828, 0.2761), vec2(0.9748, 0.7562), vec2(0.4433, -0.9750),
	vec2(0.5374, -0.4731), vec2(-0.2645, -0.4188), vec2(0.0320, 0.9003),
	vec2(-0.6549, -0.0970), vec2(0.5949, 0.7956), vec2(-0.0750, 0.6104),
	vec2(0.1798, -0.0320)
);

// Interleaved-gradient-noise rotation angle from the fragment position.
// Rotating the kernel per pixel trades banding for noise, which TAA or the
// eye averages out (see the PCF section of the shadow-mapping reference).
float vulkanisFilterAngle(vec2 fragCoord) {
	vec3 magic = vec3(0.06711056, 0.00583715, 52.9829189);
	return 6.2831853 * fract(magic.z * fract(dot(fragCoord, magic.xy)));
}

vec2 vulkanisRotateTap(vec2 tap, float angle) {
	float c = cos(angle);
	float s = sin(angle);
	return mat2(c, -s, s, c) * tap;
}
