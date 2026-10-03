#version 460
#extension GL_EXT_ray_tracing : require
layout(location = 0) rayPayloadInEXT vec3 radiance;

void main() {
    radiance = vec3(0.015, 0.025, 0.045);
}
