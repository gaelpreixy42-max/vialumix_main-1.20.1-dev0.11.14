package com.vialumix.rt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import net.fabricmc.loader.api.FabricLoader;

/** Native boundary used by the Vialumix renderer. */
public final class VialumixNative {
    private static boolean loaded;
    private static Path runtimeDirectory;
    private static DlssRuntime dlssRuntime;
    private static String loadError = "";
    private static volatile boolean hardwareRayTracingAvailable;
    private static volatile boolean rendererRequested;

    private VialumixNative() {}

    public static synchronized void initialize(Path root) {
        if (isRadianceBackendAvailable()) {
            // Radiance owns MCVR's Vulkan renderer in the isolated MCVR artifact.
            // Do not also load Vialumix's earlier standalone native probe.
            loaded = false;
            runtimeDirectory = root.toAbsolutePath().normalize();
            dlssRuntime = new DlssRuntime(runtimeDirectory);
            return;
        }
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

    /** Compares Minecraft's current OpenGL adapter with Vulkan and probes exportable RT interop resources. */
    public static String probeVulkanInterop(byte[] openGlDeviceLuid) {
        if (!loaded) return "Vialumix native Vulkan probe unavailable: " + loadError;
        if (openGlDeviceLuid == null) return "OpenGL did not expose a Windows device LUID; cannot match a Vulkan adapter.";
        try {
            return nativeProbeVulkanInterop(openGlDeviceLuid);
        } catch (Throwable error) {
            return "Vulkan/OpenGL interop probe failed: " + error.getClass().getSimpleName() + ": " + error.getMessage();
        }
    }

    public static void setHardwareRayTracingAvailable(String probeReport) {
        hardwareRayTracingAvailable = probeReport != null
                && probeReport.contains("rayTracingFeatures=1")
                && probeReport.contains("VK_KHR_external_memory_win32=1")
                && probeReport.contains("VK_KHR_external_semaphore_win32=1")
                && probeReport.contains("rgba8ExternalImageExport=1")
                && probeReport.contains("opaqueWin32SemaphoreImportExport=1");
        if (!hardwareRayTracingAvailable) rendererRequested = false;
    }

    public static long[] createVulkanInteropImage(byte[] openGlDeviceLuid) {
        if (!loaded) throw new IllegalStateException("Vialumix native Vulkan probe unavailable: " + loadError);
        return nativeCreateInteropImage(openGlDeviceLuid);
    }

    public static long[] runRayTracingInterop(byte[] openGlDeviceLuid, byte[] raygen, byte[] miss, byte[] closestHit, float[] vertices) {
        if (!loaded) throw new IllegalStateException("Vialumix native Vulkan probe unavailable: " + loadError);
        return nativeRunRayTracingInterop(openGlDeviceLuid, raygen, miss, closestHit, vertices);
    }

    public static void closeInteropHandle(long handle) { nativeCloseInteropHandle(handle); }
    public static void destroyInteropImage() { if (loaded) nativeDestroyInteropImage(); }
    public static void destroyRayTracingInterop() { if (loaded) nativeDestroyRayTracingInterop(); }

    /** True only for the dedicated artifact that includes Radiance/MCVR. */
    public static boolean isRadianceBackendAvailable() {
        return FabricLoader.getInstance().isModLoaded("radiance");
    }

    private static boolean isRadianceRendererReady() {
        if (!isRadianceBackendAvailable()) return false;
        try {
            Class<?> entrypoint = Class.forName("com.radiance.client.RadianceClient", false,
                    VialumixNative.class.getClassLoader());
            Object directory = entrypoint.getField("radianceDir").get(null);
            return directory instanceof Path;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return false;
        }
    }

    public static boolean supportsRayTracing() { return isRadianceBackendAvailable() || (loaded && hardwareRayTracingAvailable); }

    public static synchronized boolean startRenderer() {
        if (isRadianceBackendAvailable()) return isRadianceRendererReady();
        rendererRequested = loaded && hardwareRayTracingAvailable;
        return rendererRequested;
    }

    public static synchronized void stopRenderer() {
        if (isRadianceBackendAvailable()) return; // MCVR hooks are installed at game startup.
        rendererRequested = false;
        if (loaded) nativeStopRenderer();
    }

    public static boolean isRendererReady() {
        return isRadianceRendererReady() || (rendererRequested && hardwareRayTracingAvailable);
    }
    public static boolean supportsDLSS() { return !isRadianceBackendAvailable() && loaded && dlssRuntime != null && dlssRuntime.hasSuperResolution() && nativeSupportsDLSS(); }
    public static boolean supportsRayReconstruction() { return !isRadianceBackendAvailable() && loaded && dlssRuntime != null && dlssRuntime.hasRayReconstruction() && nativeSupportsRayReconstruction(); }
    public static boolean supportsFrameGeneration() { return !isRadianceBackendAvailable() && loaded && dlssRuntime != null && dlssRuntime.hasFrameGeneration() && nativeSupportsFrameGeneration(); }

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
    private static native String nativeProbeVulkanInterop(byte[] openGlDeviceLuid);
    private static native long[] nativeCreateInteropImage(byte[] openGlDeviceLuid);
    private static native void nativeCloseInteropHandle(long handle);
    private static native void nativeDestroyInteropImage();
    private static native long[] nativeRunRayTracingInterop(byte[] openGlDeviceLuid, byte[] raygen, byte[] miss, byte[] closestHit, float[] vertices);
    private static native void nativeDestroyRayTracingInterop();
    // ---- Persistent Iris bridge renderer (vulkan_rt_renderer.cpp) ----
    public static boolean rtInit(byte[] luid, byte[] raygen, byte[] miss, byte[] shadowMiss, byte[] closestHit, byte[] anyHit, byte[] post) {
        if (!loaded) throw new IllegalStateException("Vialumix native Vulkan backend unavailable: " + loadError);
        return nativeRtInit(luid, raygen, miss, shadowMiss, closestHit, anyHit, post);
    }
    /** Uploads (or replaces) a batch of section meshes with one GPU submission. kind 0 = block quads (12 data floats/tri), 1 = water (4). */
    public static void rtSectionUploadBatch(int[] slots, int[] kinds, int[] positions, float[][] verts, float[][] data) {
        nativeRtSectionUploadBatch(slots, kinds, positions, verts, data);
    }
    public static void rtSectionRemove(int slot) { if (loaded) nativeRtSectionRemove(slot); }
    public static void rtClearSections() { if (loaded) nativeRtClearSections(); }
    public static int rtSectionCount() { return loaded ? nativeRtSectionCount() : 0; }
    /** Returns {width, height, vk->gl semaphore, gl->vk semaphore, (memoryHandle, size) x3}. */
    public static long[] rtConfigure(int width, int height) { return nativeRtConfigure(width, height); }
    public static boolean rtTrace(float[] frame, boolean glSignaled, int originX, int originY, int originZ) {
        return nativeRtTrace(frame, glSignaled, originX, originY, originZ);
    }
    public static void rtShutdown() { if (loaded) nativeRtShutdown(); }
    public static boolean rtSetAtlas(java.nio.ByteBuffer rgba, int width, int height) { return nativeRtSetAtlas(rgba, width, height); }
    public static float rtLastMs() { return loaded ? nativeRtLastMs() : 0f; }

    private static native boolean nativeRtInit(byte[] luid, byte[] raygen, byte[] miss, byte[] shadowMiss, byte[] closestHit, byte[] anyHit, byte[] post);
    private static native boolean nativeRtSetAtlas(java.nio.ByteBuffer rgba, int width, int height);
    private static native boolean nativeRtSectionUploadBatch(int[] slots, int[] kinds, int[] positions, float[][] verts, float[][] data);
    private static native void nativeRtSectionRemove(int slot);
    private static native void nativeRtClearSections();
    private static native int nativeRtSectionCount();
    private static native long[] nativeRtConfigure(int width, int height);
    private static native boolean nativeRtTrace(float[] frame, boolean glSignaled, int originX, int originY, int originZ);
    private static native void nativeRtShutdown();
    private static native float nativeRtLastMs();
}
