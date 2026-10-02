# Vialumix — Minecraft 1.20.1

Vialumix is a Fabric client mod project intended to combine the Iris/Sodium shader workflow with a dedicated Vulkan ray-tracing backend inspired by Radiance/MCVR.

## Current development state

- Fabric 1.20.1 client entrypoint.
- Sodium 0.5.13 + Iris 1.7.6 integration as compile-time dependencies.
- Custom Vialumix graphics screen.
- Shaderpack enumeration and application delegated to Iris.
- Persistent Vialumix configuration.
- Separate render-backend state (`iris` / `vulkan`).
- Vulkan capability probe for hardware ray tracing.
- Instance-local NVIDIA NGX runtime discovery.
- Separate capability checks for DLSS Super Resolution, Ray Reconstruction and Frame Generation.
- Native bridge prepared for the MCVR renderer.

## DLSS runtime layout

Vialumix uses its own instance directory rather than modifying Radiance's directory:

    .minecraft/vialumix/
        nvngx_dlss.dll
        nvngx_dlssd.dll
        nvngx_dlssg.dll   (optional)
        core.dll          (native Vialumix/MCVR renderer, once built)
        shaders/          (native pipeline assets)
        modules/          (native pipeline modules)

The NVIDIA DLLs are not downloaded by Vialumix. They must be obtained from the appropriate NVIDIA distribution and kept under their applicable license terms.

## Important architecture note

Iris shaderpacks are OpenGL/GLSL-oriented. MCVR/Radiance is a Vulkan renderer. Therefore, simply loading an Iris shaderpack inside MCVR does not make the two systems compatible. The final Vialumix RT path requires a shader compatibility/translation layer (GLSL shaderpack semantics -> Vialumix/MCVR Vulkan pipeline) in addition to the Java UI and JNI bridge.

The current project intentionally keeps this boundary explicit: Iris remains the normal backend, while the Vulkan backend is selected only when its native renderer is actually available. This avoids presenting a UI toggle as a fake ray-tracing implementation.

## Native build

The small `native/` target builds the capability/JNI bridge. The real renderer is based on MCVR and is configured separately because MCVR has its own third-party submodules and generated JNI headers.

Example:

    cmake -S native -B native/build -DVIALUMIX_MCVR_ROOT="C:/path/to/MCVR"
    cmake --build native/build --config Release

The complete installable mod JAR must be produced by Gradle/Loom in an environment with access to the Fabric/Minecraft Maven artifacts. This development environment cannot reach the Gradle/Maven network, so no fake JAR is included.


## Java de compilation

Minecraft 1.20.1 / Fabric Loom doit être compilé avec un JDK compatible. Java 25 n'est pas utilisable avec Gradle 8.14.1 dans cette configuration (major version 69). `tools/BUILD_AND_INSTALL.bat` tente automatiquement d'utiliser un Java compatible déjà présent, notamment un runtime du launcher Minecraft. Sinon, installe JDK 17 ou 21.


## v17 – Sodium graphics interception + Mod Menu

Sodium 0.5.13 replaces Minecraft's vanilla Video Options screen with `SodiumOptionsGUI`. Vialumix therefore intercepts Sodium's `createScreen(Screen)` factory instead of intercepting vanilla `VideoOptionsScreen`.

When the user clicks Minecraft's **Graphismes / Video Settings** button, Vialumix now shows a chooser:
- **Graphismes Minecraft / Sodium** → opens the real Sodium screen
- **Graphismes Vialumix** → opens Vialumix's custom graphics screen

Mod Menu support is optional. If Mod Menu 7.2.2 for Minecraft 1.20.1 is installed, Vialumix exposes its configuration screen through the Mod Menu API. The project includes a compile-only API stub so the build does not require downloading Mod Menu; the actual Mod Menu jar must still be present in the Minecraft instance to display the config button.

The Vialumix icon is registered as `assets/vialumix/icon.png` and is referenced from `fabric.mod.json`.

## V18
The bundled Mod Menu stub was removed. Vialumix now compiles against the real Mod Menu 7.2.2 API (Minecraft 1.20.1) as an optional compile-only dependency via Modrinth Maven. Mod Menu is not required to launch Vialumix.


## Backend natif Vulkan RT

Le bouton Ray Tracing reste désactivé tant que `.minecraft\vialumix\vialumix.dll` n'est pas présent et chargeable.
Le script `RUN_AFTER_BUILD.bat` tente maintenant automatiquement de compiler le backend natif si CMake + Vulkan SDK sont disponibles.

Pour une compilation manuelle :
1. Installer Visual Studio 2022 avec les outils C++.
2. Installer le Vulkan SDK de LunarG.
3. Vérifier que `VULKAN_SDK` est défini.
4. Lancer `BUILD_VIALUMIX_NATIVE.bat`.

Le DLL natif n'est pas redistribué précompilé ici : il doit être compilé localement avec le SDK Vulkan installé sur la machine.


## v25 - chemins
Le nom du dossier de projet peut changer à chaque version. Les scripts utilisent `%~dp0` pour déterminer dynamiquement la racine et installent toujours le backend dans `<racine du projet>\\.minecraft\\vialumix\\vialumix.dll`.
Le DLL natif n'est créé que si CMake, Visual Studio C++ et le Vulkan SDK sont disponibles. Aucun DLL factice n'est fourni.
