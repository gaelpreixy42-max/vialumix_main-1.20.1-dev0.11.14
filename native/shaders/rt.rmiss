#version 460
#extension GL_EXT_ray_tracing : require
struct Hit { float t; vec3 n; vec3 albedo; float emissive; };
layout(location = 0) rayPayloadInEXT Hit hit;
void main() { hit.t = -1.0; }
