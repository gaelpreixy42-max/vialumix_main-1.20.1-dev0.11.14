#version 460
#extension GL_EXT_ray_tracing : require
hitAttributeEXT vec2 barycentrics;
layout(location = 0) rayPayloadInEXT vec3 radiance;

void main() {
    float facing = gl_HitKindEXT == gl_HitKindFrontFacingTriangleEXT ? 1.0 : 0.65;
    radiance = mix(vec3(0.05, 0.24, 1.0), vec3(0.15, 0.85, 1.0), barycentrics.x) * facing;
}
