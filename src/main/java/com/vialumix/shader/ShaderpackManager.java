package com.vialumix.shader;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Vialumix's lightweight UI layer over Iris' actual shaderpack loader. */
public final class ShaderpackManager {
    private final Path directory;
    private final List<String> packs = new ArrayList<>();

    public ShaderpackManager(Path directory) { this.directory = directory; }

    public void scan() {
        packs.clear();
        try {
            Files.createDirectories(directory);
            // Prefer Iris' own directory enumeration so Vialumix follows the exact same
            // validity rules and filenames as the installed Iris version.
            packs.addAll(Iris.getShaderpacksDirectoryManager().enumerate());
            packs.removeIf(p -> p == null || p.isBlank());
            packs.sort(String.CASE_INSENSITIVE_ORDER);
        } catch (Exception ignored) {
            // Fallback keeps the UI useful even if Iris is still initializing.
            try (var stream = Files.list(directory)) {
                stream.filter(Files::isRegularFile)
                        .filter(ShaderpackManager::isShaderpackFile)
                        .map(p -> p.getFileName().toString())
                        .sorted(String.CASE_INSENSITIVE_ORDER)
                        .forEach(packs::add);
            } catch (IOException ignoredAgain) { }
        }
    }

    private static boolean isShaderpackFile(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".zip") || n.endsWith(".jar");
    }

    public List<String> packs() { return Collections.unmodifiableList(packs); }

    public String displayName(String file) {
        if (file == null || file.isBlank()) return "Aucun shaderpack";
        int dot = file.lastIndexOf('.');
        return dot > 0 ? file.substring(0, dot) : file;
    }

    /** Applies the selected pack through Iris itself; Vialumix does not reimplement GLSL loading. */
    public boolean apply(String file) {
        try {
            if (file == null || file.isBlank()) {
                Iris.getIrisConfig().setShaderPackName(null);
                Iris.getIrisConfig().save();
                IrisApi.getInstance().getConfig().setShadersEnabledAndApply(false);
                return true;
            }

            if (!Iris.isValidShaderpack(directory.resolve(file))) return false;
            Iris.getIrisConfig().setShaderPackName(file);
            Iris.getIrisConfig().save();
            IrisApi.getInstance().getConfig().setShadersEnabledAndApply(true);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
