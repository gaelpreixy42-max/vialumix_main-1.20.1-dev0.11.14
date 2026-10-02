# Vialumix native renderer - phase 1

This native component is no longer only a capability probe. When `Ray Tracing` is enabled it creates a Vulkan 1.2 instance, selects a GPU exposing `VK_KHR_acceleration_structure`, `VK_KHR_ray_tracing_pipeline` and `VK_KHR_deferred_host_operations`, enables buffer device address, creates a graphics queue and keeps the Vulkan device alive until RT is disabled.

This is the renderer bootstrap. It does not yet replace Iris' world framebuffer or build Minecraft chunk/entity BLAS/TLAS. That is the next rendering phase.

## Build on Windows

Requirements:
- Visual Studio 2022 C++ desktop workload
- CMake 3.24+
- Vulkan SDK installed and `VULKAN_SDK` configured
- Java 17 for the Fabric side

Run `BUILD_NATIVE.bat`. It copies `vialumix.dll` to the project `.minecraft/vialumix/` directory.
