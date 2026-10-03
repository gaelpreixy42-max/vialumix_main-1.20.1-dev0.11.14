#version 460
#extension GL_EXT_ray_tracing : require
#extension GL_EXT_buffer_reference : require
#extension GL_EXT_buffer_reference_uvec2 : require

struct Hit { float t; vec3 n; vec3 albedo; float emissive; };
layout(location = 0) rayPayloadInEXT Hit hit;
hitAttributeEXT vec2 attribs;

layout(buffer_reference, std430, buffer_reference_align = 4) readonly buffer FloatBuf { float v[]; };
layout(buffer_reference, std430, buffer_reference_align = 16) readonly buffer Vec4Buf { vec4 v[]; };
// Per-section device addresses: (vertices lo, vertices hi, data lo, data hi). Slot = custom index & 0x7FFFFF, bit 23 = water.
layout(set = 0, binding = 5, std430) readonly buffer SectionTable { uvec4 entries[]; } table;
layout(set = 0, binding = 13) uniform sampler2D atlas;

void main() {
    uint ci = uint(gl_InstanceCustomIndexEXT);
    bool water = (ci & 0x800000u) != 0u;
    uvec4 e = table.entries[ci & 0x7FFFFFu];
    FloatBuf verts = FloatBuf(e.xy);
    Vec4Buf data = Vec4Buf(e.zw);
    uint prim = uint(gl_PrimitiveID);
    uint i = prim * 9u;
    vec3 a = vec3(verts.v[i], verts.v[i + 1u], verts.v[i + 2u]);
    vec3 b = vec3(verts.v[i + 3u], verts.v[i + 4u], verts.v[i + 5u]);
    vec3 c = vec3(verts.v[i + 6u], verts.v[i + 7u], verts.v[i + 8u]);
    if (water) {
        vec4 col = data.v[prim];
        hit.albedo = col.rgb;
        hit.emissive = col.a;
    } else {
        vec4 d0 = data.v[prim * 3u], d1 = data.v[prim * 3u + 1u], d2 = data.v[prim * 3u + 2u];
        vec3 w = vec3(1.0 - attribs.x - attribs.y, attribs.x, attribs.y);
        vec2 uv = d0.xy * w.x + d0.zw * w.y + d1.xy * w.z;
        vec3 tint = vec3(d1.z, d1.w, d2.x);
        vec3 base = d2.z > 1.5 ? vec3(1.0) : pow(textureLod(atlas, uv, 0.0).rgb, vec3(2.2));
        hit.albedo = base * tint;
        hit.emissive = d2.y;
    }
    vec3 n = normalize(cross(b - a, c - a));
    if (dot(n, gl_WorldRayDirectionEXT) > 0.0) n = -n;
    hit.t = gl_HitTEXT;
    hit.n = n;
}
