#version 460 core
// VulkanicShader receiver: picks a cascade by distance, compares sun depths with
// gradient-corrected PCF, blends at cascade borders, fades far away, then fogs.

layout(std140) uniform u_Globals {
	mat4 u_ProjectionMatrix;
	mat4 u_ModelViewMatrix;

	vec4 u_FogColor;
	vec2 u_EnvironmentFog;
	vec2 u_RenderFog;

	vec2 u_TexelSize;
	vec2 u_TexCoordShrink;

	float u_FadePeriodInv;
	bool u_UseRGSS;
};

float vulkanisFogValue(float vertexDistance, float fogStart, float fogEnd) {
	if (vertexDistance <= fogStart) {
		return 0.0;
	} else if (vertexDistance >= fogEnd) {
		return 1.0;
	}
	return (vertexDistance - fogStart) / (fogEnd - fogStart);
}

vec4 vulkanisLinearFog(vec4 fragColor, vec2 fragDistance, float fadeFactor) {
#ifdef USE_FOG
	float fogValue = max(1.0 - fadeFactor, max(
		vulkanisFogValue(fragDistance.y, u_EnvironmentFog.x, u_EnvironmentFog.y),
		vulkanisFogValue(fragDistance.x, u_RenderFog.x, u_RenderFog.y)));
	return vec4(mix(fragColor.rgb, u_FogColor.rgb, fogValue * u_FogColor.a), fragColor.a);
#else
	return fragColor;
#endif
}

uniform sampler2D u_LightTex;
uniform sampler2D u_BlockTex;
uniform sampler2D VulkanisShadowMap0;
uniform sampler2D VulkanisShadowMap1;
uniform sampler2D VulkanisShadowMap2;
uniform sampler2D VulkanisShadowMap3;

#define VULKANIS_CASCADES 4

layout(std140) uniform VulkanisShadowData {
	mat4 CascadeMatrix[VULKANIS_CASCADES];
	// (texelWorldSize, previousEnd, end, depthRange)
	vec4 CascadeInfo[VULKANIS_CASCADES];
	vec4 LightDirection;
	// (strength, microBias, unused, steps)
	vec4 ShadowParams;
};

layout(location = 0) in vec4 v_Color;
layout(location = 1) in vec2 v_TexCoord;
layout(location = 2) in vec2 v_FragDistance;
layout(location = 3) in float v_FadeFactor;
layout(location = 4) in vec3 v_ReceiverPos;
layout(location = 5) in float v_BlockLight;
layout(location = 6) in float v_SkyLight;
layout(location = 0) out vec4 fragColor;

vec3 vulkanisTerrainNormal(vec3 position) {
	vec3 normal = cross(dFdxFine(position), dFdyFine(position));
	float lengthSquared = dot(normal, normal);
	if (lengthSquared < 1.0e-20) return vec3(0.0, 1.0, 0.0);
	normal *= inversesqrt(lengthSquared);
	return dot(normal, -position) < 0.0 ? -normal : normal;
}

vec3 vulkanisShadowCoord(mat4 shadowMatrix, vec3 position) {
	vec4 clip = shadowMatrix * vec4(position, 1.0);
	float invW = 1.0 / max(abs(clip.w), 0.000001);
	return vec3(clip.xy * invW * 0.5 + 0.5, clip.z * invW);
}

// How fast depth changes per unit of shadow-map UV. Lets each tap correct its
// compare depth so slanted surfaces don't smear.
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

// Samplers can't be picked by a variable on our backend, so branch per cascade.
vec2 vulkanisMapTexel(int cascade) {
	if (cascade == 1) return 1.0 / vec2(textureSize(VulkanisShadowMap1, 0));
	if (cascade == 2) return 1.0 / vec2(textureSize(VulkanisShadowMap2, 0));
	if (cascade == 3) return 1.0 / vec2(textureSize(VulkanisShadowMap3, 0));
	return 1.0 / vec2(textureSize(VulkanisShadowMap0, 0));
}

float vulkanisMapDepth(int cascade, vec2 uv) {
	if (cascade == 1) return texture(VulkanisShadowMap1, uv).r;
	if (cascade == 2) return texture(VulkanisShadowMap2, uv).r;
	if (cascade == 3) return texture(VulkanisShadowMap3, uv).r;
	return texture(VulkanisShadowMap0, uv).r;
}

float vulkanisTapVisibility(int cascade, vec2 tapUv, vec2 receiverUv,
		float compareDepth, vec2 gradient, vec2 texel) {
	vec2 sampleUv = (floor(tapUv / texel) + 0.5) * texel;
	float tapCompare = compareDepth + dot(gradient, sampleUv - receiverUv);
	return step(tapCompare, vulkanisMapDepth(cascade, sampleUv));
}

float vulkanisSampleCascade(int cascade, vec3 offsetPos, float steps) {
	mat4 shadowMatrix = CascadeMatrix[cascade];
	vec4 info = CascadeInfo[cascade];
	float texelWorld = info.x;
	float depthRange = max(info.w, 1.0);
	vec3 coord = vulkanisShadowCoord(shadowMatrix, offsetPos);
	vec2 texel = vulkanisMapTexel(cascade);
	vec2 guard = texel * 1.5;
	if (coord.x <= guard.x || coord.x >= 1.0 - guard.x
			|| coord.y <= guard.y || coord.y >= 1.0 - guard.y
			|| coord.z <= 0.0 || coord.z >= 1.0) return 1.0;
	float precisionBias = (0.0005 + texelWorld * 0.015) / depthRange;
	float compareDepth = coord.z - precisionBias - ShadowParams.y / depthRange;
	vec2 gradient = vulkanisDepthGradient(coord);
	int hw = steps < 16.0 ? 0 : (steps < 40.0 ? 1 : 2);
	float visible = 0.0;
	float taps = 0.0;
	for (int y = -2; y <= 2; ++y) {
		for (int x = -2; x <= 2; ++x) {
			if (abs(x) > hw || abs(y) > hw) continue;
			vec2 tapUv = coord.xy + vec2(float(x), float(y)) * texel;
			visible += vulkanisTapVisibility(cascade, tapUv, coord.xy, compareDepth, gradient, texel);
			taps += 1.0;
		}
	}
	return visible / max(taps, 1.0);
}

// Push the sample off the surface along its normal. Kills acne up close without
// detaching shadows (peter-panning) far away. Uncapped on purpose.
float vulkanisNormalOffsetWorld(vec3 normal, float dist) {
	float facing = clamp(dot(normal, LightDirection.xyz), 0.0, 1.0);
	float distanceBias = pow(dist * dist, 0.75);
	return (0.12 + 0.0008 * distanceBias) * (2.0 - 0.95 * facing);
}

void main() {
	vec3 normal = vulkanisTerrainNormal(v_ReceiverPos);
	vec4 color = texture(u_BlockTex, v_TexCoord) * v_Color;
#ifdef ALPHA_CUTOUT
	if (color.a < float(ALPHA_CUTOUT)) discard;
#endif
	// Shadows only matter in sunlight: scaled by skylight, killed by torchlight.
	float strength = ShadowParams.x * v_SkyLight * (1.0 - smoothstep(0.25, 0.75, v_BlockLight));
	float dist = length(v_ReceiverPos);
	int cascade = -1;
	for (int i = 0; i < VULKANIS_CASCADES; ++i) {
		float end = CascadeInfo[i].z;
		if (end > 0.0 && dist <= end) {
			cascade = i;
			break;
		}
	}
	float visibility = 1.0;
	if (strength > 0.001 && cascade >= 0) {
		float steps = ShadowParams.w;
		vec4 info = CascadeInfo[cascade];
		vec3 offsetPos = v_ReceiverPos
			+ normal * vulkanisNormalOffsetWorld(normal, dist);
		visibility = vulkanisSampleCascade(cascade, offsetPos, steps);
		if (cascade < VULKANIS_CASCADES - 1) {
			// Blend into the next cascade over the last 10% so seams don't show.
			float span = max(info.z - info.y, 0.001);
			float edge = info.z - 0.10 * span;
			if (dist > edge && CascadeInfo[cascade + 1].z > 0.0) {
				vec3 nextPos = v_ReceiverPos
					+ normal * vulkanisNormalOffsetWorld(normal, dist);
				float next = vulkanisSampleCascade(cascade + 1, nextPos, steps);
				visibility = mix(visibility, next, smoothstep(edge, info.z, dist));
			}
		} else {
			// Fade to lit at the far edge so shadows never pop out.
			visibility = mix(visibility, 1.0, smoothstep(info.z * 0.88, info.z, dist));
		}
	}
	color.rgb *= mix(1.0, visibility, strength);
	fragColor = vulkanisLinearFog(color, v_FragDistance, v_FadeFactor);
}
