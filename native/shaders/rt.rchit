#version 460
#extension GL_EXT_ray_tracing : require

struct Hit { float t; vec3 n; vec3 albedo; float emissive; };
layout(location = 0) rayPayloadInEXT Hit hit;
hitAttributeEXT vec2 attribs;

layout(set = 0, binding = 5, std430) readonly buffer SolidVertices { float data[]; } solidV;
layout(set = 0, binding = 6, std430) readonly buffer SolidData { vec4 data[]; } solidD; // 3 vec4 per triangle
layout(set = 0, binding = 7, std430) readonly buffer WaterVertices { float data[]; } waterV;
layout(set = 0, binding = 8, std430) readonly buffer WaterColors { vec4 data[]; } waterC;
layout(set = 0, binding = 13) uniform sampler2D atlas;

void main() {
    uint prim = uint(gl_PrimitiveID);
    vec3 a, b, c;
    if (gl_InstanceCustomIndexEXT == 1) {
        uint i = prim * 9u;
        a = vec3(waterV.data[i], waterV.data[i + 1u], waterV.data[i + 2u]);
        b = vec3(waterV.data[i + 3u], waterV.data[i + 4u], waterV.data[i + 5u]);
        c = vec3(waterV.data[i + 6u], waterV.data[i + 7u], waterV.data[i + 8u]);
        vec4 col = waterC.data[prim];
        hit.albedo = col.rgb;
        hit.emissive = col.a;
    } else {
        uint i = prim * 9u;
        a = vec3(solidV.data[i], solidV.data[i + 1u], solidV.data[i + 2u]);
        b = vec3(solidV.data[i + 3u], solidV.data[i + 4u], solidV.data[i + 5u]);
        c = vec3(solidV.data[i + 6u], solidV.data[i + 7u], solidV.data[i + 8u]);
        vec4 d0 = solidD.data[prim * 3u], d1 = solidD.data[prim * 3u + 1u], d2 = solidD.data[prim * 3u + 2u];
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
