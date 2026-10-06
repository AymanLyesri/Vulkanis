// REFERENCE ONLY: the engine never loads this file. Packs ship their own shadow/terrain GLSL (see run/shaderpacks/PACK_CONTRACT.md).
#version 460 core

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

// Engine injects VULKANIS_CASCADES (= 3 on api 1) as a compiler define.
#ifndef VULKANIS_CASCADES
#define VULKANIS_CASCADES 3
#endif

layout(std140) uniform VulkanisShadowData {
	mat4 CascadeMatrix[VULKANIS_CASCADES];
	// (texelWorldSize, previousEnd, end, depthRange)
	vec4 CascadeInfo[VULKANIS_CASCADES];
	vec4 LightDirection;
	// (strength, microBias, filterRadius, filterMode)
	// filterMode: 0 = single tap, 1 = 3x3 box, 2 = rotated Poisson-16.
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

vec2 vulkanisMapTexel(int cascade) {
	if (cascade == 1) return 1.0 / vec2(textureSize(VulkanisShadowMap1, 0));
	if (cascade == 2) return 1.0 / vec2(textureSize(VulkanisShadowMap2, 0));
	return 1.0 / vec2(textureSize(VulkanisShadowMap0, 0));
}

float vulkanisMapDepth(int cascade, vec2 uv) {
	if (cascade == 1) return texture(VulkanisShadowMap1, uv).r;
	if (cascade == 2) return texture(VulkanisShadowMap2, uv).r;
	return texture(VulkanisShadowMap0, uv).r;
}

float vulkanisTapVisibility(int cascade, vec2 tapUv, vec2 receiverUv,
		float compareDepth, vec2 gradient, vec2 texel) {
	vec2 sampleUv = (floor(tapUv / texel) + 0.5) * texel;
	float tapCompare = compareDepth + dot(gradient, sampleUv - receiverUv);
	return step(tapCompare, vulkanisMapDepth(cascade, sampleUv));
}

// Reference Poisson-16 disk on [-1, 1]; packs should
// #include <vulkanis/poisson.glsl> instead of copying this.
const vec2 VULKANIS_POISSON16[16] = vec2[16](
	vec2(-0.9420, -0.3997), vec2(0.9456, -0.7687), vec2(-0.0942, -0.9294),
	vec2(0.3448, 0.2934), vec2(-0.9159, 0.4579), vec2(-0.8154, -0.8790),
	vec2(-0.3828, 0.2761), vec2(0.9748, 0.7562), vec2(0.4433, -0.9750),
	vec2(0.5374, -0.4731), vec2(-0.2645, -0.4188), vec2(0.0320, 0.9003),
	vec2(-0.6549, -0.0970), vec2(0.5949, 0.7956), vec2(-0.0750, 0.6104),
	vec2(0.1798, -0.0320)
);

float vulkanisFilterAngle(vec2 fragCoord) {
	vec3 magic = vec3(0.06711056, 0.00583715, 52.9829189);
	return 6.2831853 * fract(magic.z * fract(dot(fragCoord, magic.xy)));
}

vec2 vulkanisRotateTap(vec2 tap, float angle) {
	float c = cos(angle);
	float s = sin(angle);
	return mat2(c, -s, s, c) * tap;
}

float vulkanisSampleCascade(int cascade, vec3 offsetPos, float filterRadius, float filterMode) {
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
	vec2 radius = max(filterRadius, 0.0) * texel;
	if (filterMode < 0.5) {
		return vulkanisTapVisibility(cascade, coord.xy, coord.xy, compareDepth, gradient, texel);
	}
	if (filterMode < 1.5) {
		float visible = 0.0;
		for (int y = -1; y <= 1; ++y) {
			for (int x = -1; x <= 1; ++x) {
				vec2 tapUv = coord.xy + vec2(float(x), float(y)) * radius;
				visible += vulkanisTapVisibility(cascade, tapUv, coord.xy, compareDepth, gradient, texel);
			}
		}
		return visible / 9.0;
	}
	float angle = vulkanisFilterAngle(gl_FragCoord.xy);
	float visible = 0.0;
	for (int i = 0; i < 16; ++i) {
		vec2 tapUv = coord.xy + vulkanisRotateTap(VULKANIS_POISSON16[i], angle) * radius;
		visible += vulkanisTapVisibility(cascade, tapUv, coord.xy, compareDepth, gradient, texel);
	}
	return visible / 16.0;
}

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
		float filterRadius = ShadowParams.z;
		float filterMode = ShadowParams.w;
		vec4 info = CascadeInfo[cascade];
		vec3 offsetPos = v_ReceiverPos
			+ normal * vulkanisNormalOffsetWorld(normal, dist);
		visibility = vulkanisSampleCascade(cascade, offsetPos, filterRadius, filterMode);
		if (cascade < VULKANIS_CASCADES - 1) {
			float span = max(info.z - info.y, 0.001);
			float edge = info.z - 0.10 * span;
			if (dist > edge && CascadeInfo[cascade + 1].z > 0.0) {
				vec3 nextPos = v_ReceiverPos
					+ normal * vulkanisNormalOffsetWorld(normal, dist);
				float next = vulkanisSampleCascade(cascade + 1, nextPos, filterRadius, filterMode);
				visibility = mix(visibility, next, smoothstep(edge, info.z, dist));
			}
		} else {
			visibility = mix(visibility, 1.0, smoothstep(info.z * 0.88, info.z, dist));
		}
	}
	color.rgb *= mix(1.0, visibility, strength);
	fragColor = vulkanisLinearFog(color, v_FragDistance, v_FadeFactor);
}
