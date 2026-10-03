package com.vialumix.rt;

import org.lwjgl.opengl.GL11;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Availability of NVIDIA DLSS 5 "3D-guided neural rendering". It is a final neural stage driven through NVIDIA
 * Streamline (feature {@code sl.dlss_nr}) and, per NVIDIA, supported on GeForce RTX 50 Series. Streamline's neural
 * rendering plugin is not publicly distributed yet, so this class only reports whether the plugin and a supported
 * GPU are present; the preferences in the Neural Rendering menu are stored until then.
 */
public final class NeuralRendering {
    /** NGX runtime of the neural-rendering feature ("DLSSNR"), as referenced by community tools; or the Streamline plugin. */
    public static final String[] PLUGINS = {"nvngx_dlssnr.dll", "sl.dlss_nr.dll"};
    private static final Pattern RTX_50 = Pattern.compile("RTX\\s*50\\d{2}", Pattern.CASE_INSENSITIVE);

    public enum Status { READY, GPU_UNSUPPORTED, PLUGIN_MISSING }

    private NeuralRendering() {}

    public static boolean pluginInstalled() {
        Path root = VialumixNative.runtimeDirectory();
        if (root == null) return false;
        for (String name : PLUGINS) {
            if (Files.isRegularFile(root.resolve(name)) || Files.isRegularFile(root.resolve("streamline").resolve(name))) return true;
        }
        return false;
    }

    /** Must be called on the render thread (reads the OpenGL renderer string). */
    public static boolean gpuSupported() {
        try {
            String renderer = GL11.glGetString(GL11.GL_RENDERER);
            return renderer != null && RTX_50.matcher(renderer).find();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static Status status() {
        if (!gpuSupported()) return Status.GPU_UNSUPPORTED;
        if (!pluginInstalled()) return Status.PLUGIN_MISSING;
        return Status.READY;
    }
}
