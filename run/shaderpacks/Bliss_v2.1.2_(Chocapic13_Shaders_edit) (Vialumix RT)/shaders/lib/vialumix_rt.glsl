// Vialumix ray-tracing bridge helpers (generated).
#ifdef VIALUMIX_RT
uniform sampler2D vialumix_rt_a; // shadow, solid distance, water distance, flags (1 solid, 2 water, 4 reflection hit)
uniform sampler2D vialumix_rt_b; // reflection hit albedo, hit
uniform sampler2D vialumix_rt_c; // sunlit, emissive, hit distance, sky visibility
            uniform sampler2D vialumix_rt_d; // sun bounce (rgb), ambient-occlusion visibility (a)
            uniform sampler2D vialumix_rt_e; // emissive bounce (rgb), history distance (a)

#ifdef OVERWORLD_SHADER
	#define VLX_DIRECT (lightCol.rgb/80.0)
	#define VLX_AMBIENT (averageSkyCol_Clouds/30.0)
#else
	#define VLX_DIRECT vec3(0.0)
	#define VLX_AMBIENT vec3(0.25)
#endif

// The RT primary surface must coincide with the surface Iris rendered at this pixel.
bool vlxMatches(float rtDistance, float irisDistance){
	return rtDistance > 0.0 && abs(rtDistance - irisDistance) < max(0.14, 0.015*irisDistance);
}

bool vlxBit(float flags, float bit){
	return mod(floor(flags/bit + 0.001), 2.0) > 0.5;
}

vec3 vlxHitColor(vec4 hitAlbedo, vec4 hitLight, vec3 direct, vec3 ambient){
	vec3 lit = direct * hitLight.r + ambient * (0.12 + 0.88*hitLight.a);
	return hitAlbedo.rgb * (lit + hitLight.g * VLX_RT_EMISSIVE);
}
#endif
