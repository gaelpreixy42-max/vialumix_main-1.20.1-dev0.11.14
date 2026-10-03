# Vialumix native renderer status

The `vialumix.dll` target in this directory is a small JNI/runtime probe. It is **not** an MCVR renderer: it does not consume Minecraft's OpenGL device, receive chunk/entity data, build acceleration structures, trace rays, or present a Vulkan frame. It must not be used to claim that ray tracing is active.

The repository contains the C++ sources for MCVR under `mcvr/`. Those sources are the native renderer used by Radiance, but they expect matching Java proxy classes and generated JNI headers in a Radiance Java checkout. Merely adding the C++ target or enabling a Vulkan device is insufficient.

## Integration work still required

1. Port/adapt the matching Radiance Java proxy and world/framebuffer integration to Vialumix's Fabric 1.20.1 source set and generate its JNI headers.
2. Build MCVR 0.1.6 for Windows x64 against those headers, with its OpenGL UI presentation backend so Minecraft's UI can share the display surface.
3. Replace the current probe-only runtime with MCVR's renderer library and load it with its packaged resources.
4. Decide how Iris shaderpacks are handled. Radiance's world renderer replaces Minecraft's world-render path; Iris shaderpacks cannot automatically be composited over that path. A true Iris-plus-RT mode needs an explicit compatible shader/bridge design.
5. Validate startup, world geometry uploads, presentation, resource reloads and shutdown in the target GDLauncher instance before enabling the setting by default.

Until those pieces exist, Vialumix reports RT unavailable and does not create a detached Vulkan device when the user presses Apply. This avoids a false-positive toggle and keeps the normal Iris/Sodium render path intact.

## Build on Windows

Requirements:
- Visual Studio 2022 C++ desktop workload
- CMake 3.24+
- Vulkan SDK installed and `VULKAN_SDK` configured
- Java 17 for the Fabric side

Run `BUILD_NATIVE.bat` to build only the probe library. It does not build MCVR and the resulting library does not render the Minecraft world.
