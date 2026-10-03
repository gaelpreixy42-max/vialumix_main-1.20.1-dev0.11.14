# Vialumix MCVR test profile

The MCVR build is a separate Fabric mod artifact for the first ray-tracing milestone. It bundles the Windows Radiance 0.1.6 runtime and uses its Vulkan world renderer. The normal Vialumix artifact remains intended for Iris/Sodium.

## Create a separate GDLauncher instance

1. Create a new Fabric instance for Minecraft 1.20.1 and use Java 17.
2. Add Fabric API and `build/libs/vialumix-0.1.0-alpha-v20-mcvr.jar` to `mods`.
3. Do not add the normal Vialumix JAR, Sodium, or Iris. The MCVR build declares Sodium and Iris as incompatible; mixing their OpenGL renderer with Radiance caused the crash recorded in the existing instance.
4. Launch the instance and enter a world. Vialumix selects Radiance's built-in `advanced` path-tracing pack, with `vanilla-pt` as fallback, if no pack is already active. The Vialumix shader selector lists the Radiance/Vulkan packs and applies them through Radiance's pipeline API.

This is the hardware path-tracing backend, but it does not load Iris shaderpacks. Bliss uses Iris/OpenGL shader stages and cannot be selected as an MCVR pack. The next integration milestone is a Radiance-native pack that ports Bliss's material and lighting style onto MCVR's path tracer; it must not be described as loading the original Bliss ZIP. The current Radiance Advanced pack already reads LabPBR material textures, which is the first material bridge to validate.

When Radiance Advanced is selected in Vialumix and **Apply** is pressed, Vialumix now offers a Bliss-inspired Radiance preset (four path-tracing bounces, volumetric clouds, water colour/absorption, and indirect-light tuning). This is a native MCVR path-tracer configuration, not a port of Bliss's shaders yet; matching Bliss's exact tonemapping, clouds, and shader-specific materials still requires shader work.

The MCVR JAR is Windows-only and includes Radiance's own license file. DLSS features are not part of this first validation step.
