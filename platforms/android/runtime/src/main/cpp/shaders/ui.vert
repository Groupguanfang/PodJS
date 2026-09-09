#version 450
layout(push_constant) uniform Primitive {
    ivec4 bounds; ivec4 xy01; ivec4 xy2Mode; uvec4 colors;
    vec4 uv01; vec4 uv2Extra; ivec4 texInfo; vec4 screen;
} p;
void main() {
    const ivec2 corner[6] = ivec2[6](ivec2(0,0),ivec2(1,0),ivec2(0,1),ivec2(0,1),ivec2(1,0),ivec2(1,1));
    vec2 pos = mix(vec2(p.bounds.xy),vec2(p.bounds.zw),vec2(corner[gl_VertexIndex]));
    gl_Position = vec4(pos / p.screen.xy * 2.0 - 1.0, 0, 1);
}
