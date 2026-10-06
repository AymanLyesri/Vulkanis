// Vulkanis api 1: sampler-agnostic shadow helpers. Cascade selection,
// sampler branching, bias and filtering stay pack-side.

// Light-space clip to shadow-map UV + [0, 1] depth. Positions are
// camera-relative (Sodium vertices are camera-relative, and CascadeMatrix
// is built around the camera origin).
vec3 vulkanisShadowCoord(mat4 shadowMatrix, vec3 position) {
	vec4 clip = shadowMatrix * vec4(position, 1.0);
	float invW = 1.0 / max(abs(clip.w), 0.000001);
	return vec3(clip.xy * invW * 0.5 + 0.5, clip.z * invW);
}

// How fast depth changes per unit of shadow-map UV. Correct each tap's
// compare depth with dot(gradient, tapOffset) so slanted surfaces neither
// acne nor smear.
vec2 vulkanisDepthGradient(vec3 coord) {
	vec2 uvDx = dFdxFine(coord.xy);
	vec2 uvDy = dFdyFine(coord.xy);
	float depthDx = dFdxFine(coord.z);
	float depthDy = dFdyFine(coord.z);
	float det = uvDx.x * uvDy.y - uvDx.y * uvDy.x;
	if (abs(det) < 1.0e-10) return vec2(0.0);
	vec2 grad = vec2(
		(depthDx * uvDy.y - depthDy * uvDx.y) / det,
		(uvDx.x * depthDy - uvDy.x * depthDx) / det);
	return clamp(grad, vec2(-2.0), vec2(2.0));
}
