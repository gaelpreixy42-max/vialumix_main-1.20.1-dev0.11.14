# Vialumix architecture — 1.20.1

## Goal

Keep Iris/Sodium as the shader and fast raster path while introducing a Vialumix
render path capable of using the MCVR/Radiance Vulkan architecture for hardware
ray tracing. The UI must expose both the normal Minecraft graphics screen and
Vialumix settings.

## Current implementation

- Fabric client entrypoint.
- Video Options integration button.
- Vialumix settings screen.
- JSON configuration in `config/vialumix.json`.
- Shaderpack discovery from `shaderpacks/*.zip` and `*.jar`.
- Upscaler/quality/frame-generation settings.
- JNI boundary for the native renderer.
- Vulkan RT capability probe in `native/`.

## Next renderer milestone

The current native library is intentionally a capability probe. It does not yet
replace Minecraft's world renderer. The next stage is to reuse the MCVR JNI and
resource lifecycle from Radiance while preserving Iris shaderpack compilation.

The key boundary should become:

Minecraft/Sodium -> Vialumix scene extraction -> MCVR Vulkan frame graph
                                          -> RT passes
                                          -> reconstruction/upscaling
                                          -> OpenGL UI composite

Iris remains responsible for loading and transforming shaderpacks. Vialumix
must not pretend an Iris GLSL shader is automatically a Vulkan ray-tracing
shader; a translation/material contract is required.

## DLSS

DLSS runtime DLLs/SOs are not redistributed. The eventual native backend will
load the user-provided NVIDIA runtimes and expose capability bits individually
for Super Resolution, Ray Reconstruction and Frame Generation.

## Video Options chooser

Vialumix replaces the initial Minecraft/Iris/Sodium video-options page with a small chooser. The user can enter either the original Minecraft/Sodium video settings or the Vialumix graphics screen. A short-lived mixin bypass is used when opening the original screen so it can initialize normally.
