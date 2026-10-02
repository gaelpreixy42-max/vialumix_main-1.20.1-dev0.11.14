package com.vialumix.rt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Native boundary used by the Vialumix renderer. */
public final class VialumixNative {
    private static boolean loaded;
    private static Path runtimeDirectory;
    private static DlssRuntime dlssRuntime;
    private static String loadError = "";

    private VialumixNative() {}

    public static synchronized void initialize(Path root) {
        runtimeDirectory = Objects.requireNonNull(root).toAbsolutePath().normalize();
        dlssRuntime = new DlssRuntime(runtimeDirectory);
        try { Files.createDirectories(runtimeDirectory); } catch (Exception ignored) { }

        // Radiance stores its native renderer and optional runtimes beside the instance-local
        // radiance directory. Vialumix deliberately uses its own namespace to avoid collisions.
        Path primary = runtimeDirectory.resolve(isWindows() ? "vialumix.dll" : "libvialumix.so");
        Path legacy = runtimeDirectory.resolve(isWindows() ? "core.dll" : "libcore.so");
        try {
            if (Files.isRegularFile(primary)) {
                System.load(primary.toAbsolutePath().toString());
                loaded = true;
            } else if (Files.isRegularFile(legacy)) {
                System.load(legacy.toAbsolutePath().toString());
                loaded = true;
            } else {
                // Development fallback: allow a JVM library named vialumix to be supplied via java.library.path.
                System.loadLibrary("vialumix");
                loaded = true;
            }
            nativeSetDlssRuntimePath(dlssRuntime.directory().toAbsolutePath().toString());
        } catch (Throwable t) {
            loaded = false;
            loadError = t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    public static boolean isLoaded() { return loaded; }
    public static Path runtimeDirectory() { return runtimeDirectory; }
    public static DlssRuntime dlss() { return dlssRuntime; }
    public static String loadError() { return loadError; }

    public static boolean supportsRayTracing() { return loaded && nativeSupportsRayTracing(); }

    public static synchronized boolean startRenderer() {
        return loaded && nativeStartRenderer();
    }

    public static synchronized void stopRenderer() {
        if (loaded) nativeStopRenderer();
    }

    public static boolean isRendererReady() { return loaded && nativeIsRendererReady(); }
    public static boolean supportsDLSS() { return loaded && dlssRuntime != null && dlssRuntime.hasSuperResolution() && nativeSupportsDLSS(); }
    public static boolean supportsRayReconstruction() { return loaded && dlssRuntime != null && dlssRuntime.hasRayReconstruction() && nativeSupportsRayReconstruction(); }
    public static boolean supportsFrameGeneration() { return loaded && dlssRuntime != null && dlssRuntime.hasFrameGeneration() && nativeSupportsFrameGeneration(); }

    public static boolean hasDlssRuntime() { return dlssRuntime != null && dlssRuntime.hasSuperResolution(); }
    public static boolean hasRayReconstructionRuntime() { return dlssRuntime != null && dlssRuntime.hasRayReconstruction(); }
    public static boolean hasFrameGenerationRuntime() { return dlssRuntime != null && dlssRuntime.hasFrameGeneration(); }

    private static native boolean nativeSupportsRayTracing();
    private static native boolean nativeStartRenderer();
    private static native void nativeStopRenderer();
    private static native boolean nativeIsRendererReady();
    private static native boolean nativeSupportsDLSS();
    private static native boolean nativeSupportsRayReconstruction();
    private static native boolean nativeSupportsFrameGeneration();
    private static native void nativeSetDlssRuntimePath(String path);
}
