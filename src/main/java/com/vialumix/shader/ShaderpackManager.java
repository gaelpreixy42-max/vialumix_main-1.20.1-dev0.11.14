package com.vialumix.shader;

import com.vialumix.rt.RadianceRayTracingBridge;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/** Vialumix's lightweight UI layer over Iris' actual shaderpack loader, loaded reflectively. */
public final class ShaderpackManager {
    private final Path directory;
    private final List<String> packs = new ArrayList<>();

    public ShaderpackManager(Path directory) { this.directory = directory; }

    public void scan() {
        packs.clear();
        if (isRadianceAvailable()) {
            packs.addAll(RadianceRayTracingBridge.availablePackPaths());
            return;
        }
        try {
            Files.createDirectories(directory);
            Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
            Object manager = iris.getMethod("getShaderpacksDirectoryManager").invoke(null);
            Object names = manager.getClass().getMethod("enumerate").invoke(manager);
            if (names instanceof Iterable<?> iterable) {
                for (Object name : iterable) if (name != null) packs.add(name.toString());
            }
            packs.removeIf(p -> p.isBlank());
        } catch (Throwable ignored) {
            // Iris is optional in the isolated MCVR profile; fall back to scanning files.
        }
        // Iris' directory manager can return an empty listing while its async discovery is in
        // progress. Merge a direct filesystem scan so packs already on disk remain selectable.
        try (var stream = Files.list(directory)) {
            stream.filter(Files::isRegularFile)
                    .filter(ShaderpackManager::isShaderpackFile)
                    .map(p -> p.getFileName().toString())
                    .forEach(file -> {
                        boolean present = packs.stream().anyMatch(existing -> existing.equalsIgnoreCase(file));
                        if (!present) packs.add(file);
                    });
        } catch (IOException ignoredAgain) { }
        packs.removeIf(BlissRtPatcher::isVariantName);
        packs.sort(String.CASE_INSENSITIVE_ORDER);
    }

    private static boolean isShaderpackFile(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".zip") || n.endsWith(".jar");
    }

    public Path directory() { return directory; }

    /** Maps a generated "(Vialumix RT)" variant back to the pack file the user picked. */
    public String sourceOf(String name) {
        if (name == null || !BlissRtPatcher.isVariantName(name)) return name == null ? "" : name;
        String base = name.substring(0, name.length() - BlissRtPatcher.SUFFIX.length());
        for (String pack : packs) {
            String stripped = pack.toLowerCase(Locale.ROOT).endsWith(".zip") || pack.toLowerCase(Locale.ROOT).endsWith(".jar")
                    ? pack.substring(0, pack.length() - 4) : pack;
            if (stripped.equals(base)) return pack;
        }
        return base + ".zip";
    }

    public List<String> packs() { return Collections.unmodifiableList(packs); }
    public boolean canDisablePacks() { return !isRadianceAvailable(); }
    public boolean usesRadianceBackend() { return isRadianceAvailable(); }

    public String displayName(String file) {
        if (file == null || file.isBlank()) return "Aucun shaderpack";
        int dot = file.lastIndexOf('.');
        return dot > 0 ? file.substring(0, dot) : file;
    }

    /** Returns Iris' configured shaderpack when Vialumix has not yet stored a selection. */
    public String currentSelection() {
        if (isRadianceAvailable()) return RadianceRayTracingBridge.activePackPath();
        try {
            Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
            Object config = iris.getMethod("getIrisConfig").invoke(null);
            Object selected = config.getClass().getMethod("getShaderPackName").invoke(config);
            if (selected instanceof java.util.Optional<?> optional) {
                Object value = optional.orElse(null);
                return value == null ? "" : sourceOf(value.toString());
            }
        } catch (Throwable ignored) { }
        return "";
    }

    public boolean supportsVialumixTracing(String file) {
        if (isRadianceAvailable()) return true;
        String candidate = file == null || file.isBlank() ? currentSelection() : file;
        return BlissRtPatcher.isBliss(candidate);
    }

    /** Applies the selected pack through Iris itself; Vialumix does not reimplement GLSL loading. */
    public boolean apply(String file) {
        if (isRadianceAvailable()) return RadianceRayTracingBridge.selectPack(file);
        try {
            Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
            Object config = iris.getMethod("getIrisConfig").invoke(null);
            if (file == null || file.isBlank()) {
                config.getClass().getMethod("setShaderPackName", String.class).invoke(config, (String) null);
                config.getClass().getMethod("save").invoke(config);
                applyIrisToggle(false);
                return true;
            }

            Method validate = iris.getMethod("isValidShaderpack", Path.class);
            if (!Boolean.TRUE.equals(validate.invoke(null, directory.resolve(file)))) return false;
            config.getClass().getMethod("setShaderPackName", String.class).invoke(config, file);
            config.getClass().getMethod("save").invoke(config);
            applyIrisToggle(true);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Applies Vialumix's screen-space tracing controls to Bliss through Iris' own option queue.
     * This is a shaderpack integration (not hardware RT): it deliberately leaves Iris/Sodium as
     * the renderer and does not attempt to start the separate native renderer.
     */
    public boolean applyVialumixOptions(String file, boolean reflections, boolean gi,
                                        boolean contactShadows, boolean ao) {
        String selected = file == null || file.isBlank() ? currentSelection() : file;
        if (!selected.toLowerCase(Locale.ROOT).contains("bliss")) return false;
        try {
            Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
            Properties options = new Properties();
            options.setProperty("Specular_Reflections", Boolean.toString(reflections));
            options.setProperty("Screen_Space_Reflections", Boolean.toString(reflections));
            options.setProperty("indirect_effect", gi ? "3" : "0");
            options.setProperty("SCREENSPACE_CONTACT_SHADOWS", Boolean.toString(contactShadows));
            options.setProperty("AO_Strength", ao ? "1.0" : "0.0");
            iris.getMethod("queueShaderPackOptionsFromProperties", Properties.class).invoke(null, options);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void applyIrisToggle(boolean enabled) throws ReflectiveOperationException {
        Class<?> irisApi = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
        Object api = irisApi.getMethod("getInstance").invoke(null);
        Object config = api.getClass().getMethod("getConfig").invoke(api);
        config.getClass().getMethod("setShadersEnabledAndApply", boolean.class).invoke(config, enabled);
    }

    private static boolean isRadianceAvailable() {
        try {
            Class.forName("com.radiance.client.pipeline.Pipeline", false, ShaderpackManager.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }
}
