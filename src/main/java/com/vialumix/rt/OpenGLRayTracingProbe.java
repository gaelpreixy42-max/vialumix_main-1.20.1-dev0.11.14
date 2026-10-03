package com.vialumix.rt;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.EXTMemoryObject;
import org.lwjgl.opengl.EXTMemoryObjectWin32;

import java.nio.ByteBuffer;

/** Reads the active Minecraft OpenGL context without creating another context or renderer. */
public final class OpenGLRayTracingProbe {
    private OpenGLRayTracingProbe() {}

    public static Report inspectCurrentContext() {
        String vendor = safeString(GL11.GL_VENDOR);
        String renderer = safeString(GL11.GL_RENDERER);
        String version = safeString(GL11.GL_VERSION);
        boolean nvRayTracing = false;
        boolean computeShaders = false;
        boolean externalMemory = false;
        boolean externalMemoryWin32 = false;
        boolean externalSemaphore = false;
        boolean externalSemaphoreWin32 = false;
        byte[] deviceLuid = null;

        try {
            var capabilities = GL.getCapabilities();
            computeShaders = capabilities.OpenGL43 || capabilities.GL_ARB_compute_shader;
            int extensionCount = GL11.glGetInteger(GL30.GL_NUM_EXTENSIONS);
            for (int i = 0; i < extensionCount; i++) {
                String extension = GL30.glGetStringi(GL11.GL_EXTENSIONS, i);
                if ("GL_NV_ray_tracing".equals(extension)) nvRayTracing = true;
                if ("GL_EXT_memory_object".equals(extension)) externalMemory = true;
                if ("GL_EXT_memory_object_win32".equals(extension)) externalMemoryWin32 = true;
                if ("GL_EXT_semaphore".equals(extension)) externalSemaphore = true;
                if ("GL_EXT_semaphore_win32".equals(extension)) externalSemaphoreWin32 = true;
            }
            if (externalMemoryWin32 && externalSemaphoreWin32) {
                ByteBuffer luid = ByteBuffer.allocateDirect(EXTMemoryObjectWin32.GL_LUID_SIZE_EXT);
                EXTMemoryObject.glGetUnsignedBytevEXT(EXTMemoryObjectWin32.GL_DEVICE_LUID_EXT, luid);
                byte[] bytes = new byte[EXTMemoryObjectWin32.GL_LUID_SIZE_EXT];
                luid.get(bytes);
                deviceLuid = bytes;
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Keep the basic GL identity report if an older or unusual context rejects extension enumeration.
        }

        return new Report(vendor, renderer, version, computeShaders, nvRayTracing,
                externalMemory, externalMemoryWin32, externalSemaphore, externalSemaphoreWin32, deviceLuid);
    }

    private static String safeString(int name) {
        try {
            String value = GL11.glGetString(name);
            return value == null ? "unknown" : value;
        } catch (RuntimeException | LinkageError ignored) {
            return "unknown";
        }
    }

    public record Report(String vendor, String renderer, String version, boolean computeShaders,
                         boolean nvRayTracing, boolean externalMemory, boolean externalMemoryWin32,
                         boolean externalSemaphore, boolean externalSemaphoreWin32, byte[] deviceLuid) {
        public Report {
            deviceLuid = deviceLuid == null ? null : deviceLuid.clone();
        }

        @Override public byte[] deviceLuid() { return deviceLuid == null ? null : deviceLuid.clone(); }

        public boolean hasOpenGLNvRayTracing() { return nvRayTracing; }

        /** These OpenGL extensions are required for the proposed Windows Vulkan image-sharing bridge. */
        public boolean hasWindowsVulkanInteropExtensions() {
            return externalMemory && externalMemoryWin32 && externalSemaphore && externalSemaphoreWin32;
        }

        @Override
        public String toString() {
            return "OpenGL vendor='" + vendor + "', renderer='" + renderer + "', version='" + version
                    + "', computeShaders=" + computeShaders + ", GL_NV_ray_tracing=" + nvRayTracing
                    + " (OpenGL only; does not report Vulkan RT), GL_EXT_memory_object=" + externalMemory
                    + ", GL_EXT_memory_object_win32=" + externalMemoryWin32
                    + ", GL_EXT_semaphore=" + externalSemaphore
                    + ", GL_EXT_semaphore_win32=" + externalSemaphoreWin32
                    + ", GL_DEVICE_LUID_EXT=" + (deviceLuid != null ? "available" : "unavailable");
        }
    }
}
