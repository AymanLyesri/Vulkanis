// REFERENCE ONLY: the engine never loads this file. Packs ship their own shadow/terrain GLSL (see run/shaderpacks/PACK_CONTRACT.md).
#version 460 core

uniform sampler2D u_BlockTex;

layout(location = 0) in vec4 v_Color;
layout(location = 1) in vec2 v_TexCoord;
layout(location = 0) out vec4 fragColor;

void main() {
#ifdef ALPHA_CUTOUT
	vec4 texel = texture(u_BlockTex, v_TexCoord);
	if (texel.a * v_Color.a < float(ALPHA_CUTOUT)) discard;
#endif
	fragColor = vec4(1.0);
}
