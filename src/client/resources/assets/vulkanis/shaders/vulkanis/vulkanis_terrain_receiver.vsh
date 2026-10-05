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

vec3 _vert_position;
vec2 _vert_tex_diffuse_coord;
vec2 _vert_tex_diffuse_coord_bias;
vec2 _vert_tex_light_coord;
vec4 _vert_color;
uint _draw_id;
uint _material_params;

#ifdef USE_VERTEX_COMPRESSION
const uint POSITION_BITS = 20u;
const uint POSITION_MAX_COORD = 1u << POSITION_BITS;
const uint TEXTURE_BITS = 15u;
const uint TEXTURE_MAX_COORD = 1u << TEXTURE_BITS;

const float VERTEX_SCALE = 32.0 / float(POSITION_MAX_COORD);
const float VERTEX_OFFSET = -8.0;

layout(location = 0) in uvec2 a_Position;
layout(location = 1) in vec4 a_Color;
layout(location = 2) in uvec2 a_TexCoord;
layout(location = 3) in uvec4 a_LightAndData;

uvec3 _deinterleave_u20x3(uvec2 data) {
	uvec3 hi = (uvec3(data.x) >> uvec3(0u, 10u, 20u)) & 0x3FFu;
	uvec3 lo = (uvec3(data.y) >> uvec3(0u, 10u, 20u)) & 0x3FFu;
	return (hi << 10u) | lo;
}

void _vert_init() {
	_vert_position = (_deinterleave_u20x3(a_Position) * VERTEX_SCALE) + VERTEX_OFFSET;
	_vert_color = a_Color;
	_vert_tex_diffuse_coord = vec2(a_TexCoord & (TEXTURE_MAX_COORD - 1u)) / float(TEXTURE_MAX_COORD);
	_vert_tex_diffuse_coord_bias = mix(vec2(-1.0), vec2(1.0), bvec2(a_TexCoord >> TEXTURE_BITS));
	_vert_tex_light_coord = vec2(a_LightAndData.xy) / vec2(256.0);
	_material_params = a_LightAndData[2];
	_draw_id = a_LightAndData[3];
}
#else
#error "Vertex compression must be enabled"
#endif

#ifdef VULKAN
layout(push_constant) uniform PC {
	vec3 u_RegionOffset;
	int u_CurrentTime;
	uint u_RegionID;
};
#else
uniform vec3 u_RegionOffset;
uniform int u_CurrentTime;
uniform uint u_RegionID;
#endif

uniform isamplerBuffer u_SectionTimeInfo;
uniform sampler2D u_LightTex;

layout(location = 0) out vec4 v_Color;
layout(location = 1) out vec2 v_TexCoord;
layout(location = 2) out vec2 v_FragDistance;
layout(location = 3) out float v_FadeFactor;
layout(location = 4) out vec3 v_ReceiverPos;
layout(location = 5) out float v_BlockLight;
layout(location = 6) out float v_SkyLight;

uvec3 vulkanisRelativeChunkCoord(uint drawId) {
	return uvec3(drawId) >> uvec3(5u, 0u, 2u) & uvec3(7u, 3u, 7u);
}

void main() {
	_vert_init();
	vec3 translation = u_RegionOffset + vulkanisRelativeChunkCoord(_draw_id) * vec3(16.0);
	vec3 position = _vert_position + translation;
	int chunkFade = texelFetch(u_SectionTimeInfo, int(u_RegionID * 256u + _draw_id)).r;

	gl_Position = u_ProjectionMatrix * u_ModelViewMatrix * vec4(position, 1.0);
	v_Color = _vert_color * texture(u_LightTex, _vert_tex_light_coord);
	v_TexCoord = _vert_tex_diffuse_coord + _vert_tex_diffuse_coord_bias * u_TexCoordShrink;
	v_FragDistance = vec2(max(length(position.xz), abs(position.y)), length(position));
	v_FadeFactor = chunkFade < 0 ? 1.0 : clamp(float(u_CurrentTime - chunkFade) * u_FadePeriodInv, 0.0, 1.0);
	v_ReceiverPos = position;
	v_BlockLight = clamp(_vert_tex_light_coord.x * (16.0 / 15.0), 0.0, 1.0);
	v_SkyLight = clamp(_vert_tex_light_coord.y * (16.0 / 15.0), 0.0, 1.0);
}
