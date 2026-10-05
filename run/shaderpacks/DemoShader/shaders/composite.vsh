#version 330
#extension GL_ARB_separate_shader_objects : require

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
    int ShowDepth;
    float Proj22;
    float Proj32;
    float Pad0;
    vec3 SunDir;
    vec3 CamPos;
    mat4 InvViewProj;
    mat4 ViewProj;
};

layout(location = 0) out vec2 texCoord;

void main(){
    vec2 uv = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(uv * vec2(2, 2) + vec2(-1, -1), 0, 1);
    texCoord = uv;
}
