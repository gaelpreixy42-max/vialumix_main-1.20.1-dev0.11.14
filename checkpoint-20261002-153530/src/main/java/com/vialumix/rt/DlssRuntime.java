package com.vialumix.rt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Locates the optional NVIDIA NGX runtime in the same instance-local layout used by Radiance.
 *
 * Vialumix intentionally does not rename or unpack the runtime DLLs. The expected Windows
 * filenames are the official NVIDIA names and all libraries must come from the same release.
 */
public final class DlssRuntime {
    public static final String SR = "nvngx_dlss.dll";
    public static final String RR = "nvngx_dlssd.dll";
    public static final String FG = "nvngx_dlssg.dll";

    private final Path directory;

    public DlssRuntime(Path instanceRoot) {
        this.directory = Objects.requireNonNull(instanceRoot).toAbsolutePath().normalize();
    }

    public Path directory() {
        return directory;
    }

    public Path sr() { return directory.resolve(SR); }
    public Path rr() { return directory.resolve(RR); }
    public Path fg() { return directory.resolve(FG); }

    public void ensureDirectory() throws IOException {
        Files.createDirectories(directory);
    }

    public boolean hasSuperResolution() { return isUsable(sr()); }
    public boolean hasRayReconstruction() { return isUsable(rr()); }
    public boolean hasFrameGeneration() { return isUsable(fg()); }

    public boolean isCompleteFor(String feature) {
        return switch (feature) {
            case "dlss" -> hasSuperResolution();
            case "ray_reconstruction" -> hasRayReconstruction();
            case "frame_generation" -> hasFrameGeneration();
            default -> false;
        };
    }

    private static boolean isUsable(Path path) {
        try {
            // A zero-byte placeholder must never be treated as an installed NGX runtime.
            return Files.isRegularFile(path) && Files.size(path) > 1024 * 1024;
        } catch (IOException ignored) {
            return false;
        }
    }
}
