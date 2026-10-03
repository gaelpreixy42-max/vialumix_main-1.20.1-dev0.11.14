package com.vialumix.rt;

import org.lwjgl.opengl.EXTMemoryObject;
import org.lwjgl.opengl.EXTMemoryObjectWin32;
import org.lwjgl.opengl.EXTSemaphore;
import org.lwjgl.opengl.EXTSemaphoreWin32;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL45C;

import java.nio.FloatBuffer;

/**
 * Imports the Vulkan RT output images and semaphores into OpenGL. The three textures are exposed to Iris
 * through {@link RtTexture}; until real data exists a 1x1 all-zero texture is bound so shaders fall back
 * to their own screen-space paths.
 */
public final class RtGlBridge {
    public static final int IMAGE_COUNT = 3;

    private static final int[] textures = new int[IMAGE_COUNT];
    private static final int[] memoryObjects = new int[IMAGE_COUNT];
    private static int semaphoreVkToGl;
    private static int semaphoreGlToVk;
    private static int fallbackTexture;
    private static int width;
    private static int height;
    private static boolean ready;

    private RtGlBridge() {}

    public static int textureId(int index) {
        if (ready && textures[index] != 0) return textures[index];
        if (fallbackTexture == 0) fallbackTexture = createFallback();
        return fallbackTexture;
    }

    public static boolean isReady() { return ready; }
    public static int width() { return width; }
    public static int height() { return height; }

    /** (Re)creates the shared images at the requested size. Must run on the render thread. */
    public static void configure(int newWidth, int newHeight) {
        release();
        long[] handles = VialumixNative.rtConfigure(newWidth, newHeight);
        if (handles == null || handles.length < 4 + IMAGE_COUNT * 2) throw new IllegalStateException("RT configure returned incomplete handles");
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            semaphoreVkToGl = EXTSemaphore.glGenSemaphoresEXT();
            EXTSemaphoreWin32.glImportSemaphoreWin32HandleEXT(semaphoreVkToGl, EXTSemaphoreWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, handles[2]);
            VialumixNative.closeInteropHandle(handles[2]);
            semaphoreGlToVk = EXTSemaphore.glGenSemaphoresEXT();
            EXTSemaphoreWin32.glImportSemaphoreWin32HandleEXT(semaphoreGlToVk, EXTSemaphoreWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, handles[3]);
            VialumixNative.closeInteropHandle(handles[3]);
            for (int i = 0; i < IMAGE_COUNT; i++) {
                long memoryHandle = handles[4 + i * 2];
                long size = handles[5 + i * 2];
                memoryObjects[i] = EXTMemoryObject.glCreateMemoryObjectsEXT();
                EXTMemoryObject.glMemoryObjectParameteriEXT(memoryObjects[i], EXTMemoryObject.GL_DEDICATED_MEMORY_OBJECT_EXT, GL11.GL_TRUE);
                EXTMemoryObjectWin32.glImportMemoryWin32HandleEXT(memoryObjects[i], size,
                        EXTMemoryObjectWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, memoryHandle);
                VialumixNative.closeInteropHandle(memoryHandle);
                textures[i] = GL11.glGenTextures();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, textures[i]);
                EXTMemoryObject.glTextureStorageMem2DEXT(textures[i], 1, GL30.GL_RGBA16F, newWidth, newHeight, memoryObjects[i], 0L);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            }
            width = newWidth;
            height = newHeight;
            ready = true;
        } catch (RuntimeException | Error error) {
            release();
            throw error;
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
        }
    }

    /** Makes subsequent OpenGL work wait for the Vulkan trace of this frame. */
    public static void waitForVulkan() {
        if (!ready) return;
        EXTSemaphore.glWaitSemaphoreEXT(semaphoreVkToGl, new int[0], textures,
                new int[]{EXTSemaphore.GL_LAYOUT_GENERAL_EXT, EXTSemaphore.GL_LAYOUT_GENERAL_EXT, EXTSemaphore.GL_LAYOUT_GENERAL_EXT});
    }

    /** Releases the images back to Vulkan once OpenGL has finished sampling them. */
    public static void signalVulkan() {
        if (!ready) return;
        EXTSemaphore.glSignalSemaphoreEXT(semaphoreGlToVk, new int[0], textures,
                new int[]{EXTSemaphore.GL_LAYOUT_GENERAL_EXT, EXTSemaphore.GL_LAYOUT_GENERAL_EXT, EXTSemaphore.GL_LAYOUT_GENERAL_EXT});
    }

    /** Debug helper: reads one RGBA texel of image {@code index}. Call between wait and signal. */
    public static float[] readTexel(int index, int x, int y) {
        if (!ready) return null;
        FloatBuffer buffer = org.lwjgl.BufferUtils.createFloatBuffer(4);
        GL45C.glGetTextureSubImage(textures[index], 0, x, y, 0, 1, 1, 1, GL11.GL_RGBA, GL11.GL_FLOAT, buffer);
        return new float[]{buffer.get(0), buffer.get(1), buffer.get(2), buffer.get(3)};
    }

    public static void release() {
        ready = false;
        for (int i = 0; i < IMAGE_COUNT; i++) {
            if (textures[i] != 0) GL11.glDeleteTextures(textures[i]);
            if (memoryObjects[i] != 0) EXTMemoryObject.glDeleteMemoryObjectsEXT(memoryObjects[i]);
            textures[i] = 0;
            memoryObjects[i] = 0;
        }
        if (semaphoreVkToGl != 0) EXTSemaphore.glDeleteSemaphoresEXT(semaphoreVkToGl);
        if (semaphoreGlToVk != 0) EXTSemaphore.glDeleteSemaphoresEXT(semaphoreGlToVk);
        semaphoreVkToGl = 0;
        semaphoreGlToVk = 0;
    }

    private static int createFallback() {
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, 1, 1, 0, GL11.GL_RGBA, GL11.GL_FLOAT, new float[4]);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
        return texture;
    }
}
