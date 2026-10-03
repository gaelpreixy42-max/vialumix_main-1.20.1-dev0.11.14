#version 460
#extension GL_EXT_ray_tracing : require

struct Hit { float t; vec3 n; vec3 albedo; float emissive; };
layout(location = 0) rayPayloadInEXT Hit hit;

layout(set = 0, binding = 5, std430) readonly buffer SolidVertices { float data[]; } solidV;
layout(set = 0, binding = 6, std430) readonly buffer SolidColors { vec4 data[]; } solidC;
layout(set = 0, binding = 7, std430) readonly buffer WaterVertices { float data[]; } waterV;
layout(set = 0, binding = 8, std430) readonly buffer WaterColors { vec4 data[]; } waterC;

void main() {
    uint prim = uint(gl_PrimitiveID);
    vec3 a, b, c; vec4 col;
    if (gl_InstanceCustomIndexEXT == 1) {
        uint i = prim * 9u;
        a = vec3(waterV.data[i], waterV.data[i + 1u], waterV.data[i + 2u]);
        b = vec3(waterV.data[i + 3u], waterV.data[i + 4u], waterV.data[i + 5u]);
        c = vec3(waterV.data[i + 6u], waterV.data[i + 7u], waterV.data[i + 8u]);
        col = waterC.data[prim];
    } else {
        uint i = prim * 9u;
        a = vec3(solidV.data[i], solidV.data[i + 1u], solidV.data[i + 2u]);
        b = vec3(solidV.data[i + 3u], solidV.data[i + 4u], solidV.data[i + 5u]);
        c = vec3(solidV.data[i + 6u], solidV.data[i + 7u], solidV.data[i + 8u]);
        col = solidC.data[prim];
    }
    vec3 n = normalize(cross(b - a, c - a));
    if (dot(n, gl_WorldRayDirectionEXT) > 0.0) n = -n;
    hit.t = gl_HitTEXT;
    hit.n = n;
    hit.albedo = col.rgb;
    hit.emissive = col.a;
}
