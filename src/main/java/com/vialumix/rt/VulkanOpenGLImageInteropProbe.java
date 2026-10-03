package com.vialumix.rt;

import org.lwjgl.opengl.EXTMemoryObject;
import org.lwjgl.opengl.EXTMemoryObjectWin32;
import org.lwjgl.opengl.EXTSemaphore;
import org.lwjgl.opengl.EXTSemaphoreWin32;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;
import java.io.InputStream;

/** One-shot end-to-end smoke test: Vulkan clears an exported image and OpenGL reads it. */
public final class VulkanOpenGLImageInteropProbe {
    private VulkanOpenGLImageInteropProbe() {}

    public static String run(byte[] openGlDeviceLuid) {
        return run(openGlDeviceLuid, new float[]{-0.8f,-0.8f,-3.0f, 0.8f,-0.8f,-3.0f, 0.0f,0.8f,-3.0f});
    }

    public static String run(byte[] openGlDeviceLuid, float[] sceneVertices) {
        long[] exported = null;
        int memoryObject = 0;
        int semaphore = 0;
        int texture = 0;
        try {
            exported = VialumixNative.runRayTracingInterop(openGlDeviceLuid,
                    readShader("/assets/vialumix/triangle.rgen.spv"),
                    readShader("/assets/vialumix/triangle.rmiss.spv"),
                    readShader("/assets/vialumix/triangle.rchit.spv"),
                    sceneVertices);
            if (exported == null || exported.length < 5) return "Native Vulkan image export returned incomplete handles";
            long memoryHandle = exported[0];
            long semaphoreHandle = exported[1];
            long size = exported[2];
            int width = Math.toIntExact(exported[3]);
            int height = Math.toIntExact(exported[4]);

            memoryObject = EXTMemoryObject.glCreateMemoryObjectsEXT();
            EXTMemoryObject.glMemoryObjectParameteriEXT(memoryObject, EXTMemoryObject.GL_DEDICATED_MEMORY_OBJECT_EXT, GL11.GL_TRUE);
            EXTMemoryObjectWin32.glImportMemoryWin32HandleEXT(memoryObject, size,
                    EXTMemoryObjectWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, memoryHandle);
            VialumixNative.closeInteropHandle(memoryHandle);
            exported[0] = 0;

            texture = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            EXTMemoryObject.glTextureStorageMem2DEXT(texture, 1, GL11.GL_RGBA8, width, height, memoryObject, 0L);

            semaphore = EXTSemaphore.glGenSemaphoresEXT();
            EXTSemaphoreWin32.glImportSemaphoreWin32HandleEXT(semaphore,
                    EXTSemaphoreWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, semaphoreHandle);
            VialumixNative.closeInteropHandle(semaphoreHandle);
            exported[1] = 0;
            EXTSemaphore.glWaitSemaphoreEXT(semaphore, new int[0], new int[]{texture},
                    new int[]{EXTSemaphore.GL_LAYOUT_GENERAL_EXT});

            ByteBuffer pixels = ByteBuffer.allocateDirect(Math.multiplyExact(Math.multiplyExact(width, height), 4));
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            int center = ((height / 2) * width + width / 2) * 4;
            int red = Byte.toUnsignedInt(pixels.get(center));
            int green = Byte.toUnsignedInt(pixels.get(center + 1));
            int blue = Byte.toUnsignedInt(pixels.get(center + 2));
            int alpha = Byte.toUnsignedInt(pixels.get(center + 3));
            int hitPixels = 0;
            for (int i = 0; i < width * height; i++) {
                if (Byte.toUnsignedInt(pixels.get(i * 4 + 2)) > 180) hitPixels++;
            }
            byte[] rgba = new byte[pixels.capacity()];
            pixels.position(0);
            pixels.get(rgba);
            if (sceneVertices.length > 9) {
                VialumixRayTracingDebugOverlay.show(rgba, width, height, sceneVertices.length / 9);
            }
            return "hardwareRayTracingPipeline=true, inputTriangles=" + (sceneVertices.length / 9)
                    + ", rayHitSamples=" + hitPixels + ", centerPixel=" + red + "/" + green + "/" + blue + "/" + alpha
                    + ", dimensions=" + width + "x" + height;
        } catch (Throwable error) {
            return "shared Vulkan image OpenGL import/read failed: " + error.getClass().getSimpleName()
                    + ": " + String.valueOf(error.getMessage());
        } finally {
            if (semaphore != 0) EXTSemaphore.glDeleteSemaphoresEXT(semaphore);
            if (texture != 0) GL11.glDeleteTextures(texture);
            if (memoryObject != 0) EXTMemoryObject.glDeleteMemoryObjectsEXT(memoryObject);
            if (exported != null) {
                if (exported.length > 0 && exported[0] != 0) VialumixNative.closeInteropHandle(exported[0]);
                if (exported.length > 1 && exported[1] != 0) VialumixNative.closeInteropHandle(exported[1]);
            }
            VialumixNative.destroyRayTracingInterop();
        }
    }

    private static byte[] readShader(String resource) throws Exception {
        try (InputStream input = VulkanOpenGLImageInteropProbe.class.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("Missing Vulkan RT shader resource " + resource);
            return input.readAllBytes();
        }
    }
}
