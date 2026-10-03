package com.vialumix.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class VialumixConfig {
    public boolean rayTracing = false;
    /** Rendering backend: IRIS keeps the normal Sodium/Iris path; VULKAN uses the Vialumix native backend. */
    public String backend = "iris";
    public boolean rayTracedShadows = true;
    public boolean rayTracedReflections = true;
    public boolean rayTracedGI = true;
    public boolean rayTracedAO = true;
    public boolean rayReconstruction = false;
    public boolean denoiser = true;
    public String shaderpack = "";
    public String upscaler = "native";
    public String dlssQuality = "quality";
    public boolean frameGeneration = false;

    /** Vialumix-owned basic video settings. */
    public String resolution = "current";
    public int renderDistance = 12;
    public String graphicsQuality = "fancy";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public VialumixConfig copy() {
        VialumixConfig copy = new VialumixConfig();
        copy.copyFrom(this);
        return copy;
    }

    public void copyFrom(VialumixConfig other) {
        rayTracing = other.rayTracing;
        backend = other.backend;
        rayTracedShadows = other.rayTracedShadows;
        rayTracedReflections = other.rayTracedReflections;
        rayTracedGI = other.rayTracedGI;
        rayTracedAO = other.rayTracedAO;
        rayReconstruction = other.rayReconstruction;
        denoiser = other.denoiser;
        shaderpack = other.shaderpack;
        upscaler = other.upscaler;
        dlssQuality = other.dlssQuality;
        frameGeneration = other.frameGeneration;
        resolution = other.resolution;
        renderDistance = other.renderDistance;
        graphicsQuality = other.graphicsQuality;
    }

    public static VialumixConfig load(Path file) {
        try {
            if (!Files.exists(file)) return new VialumixConfig();
            try (Reader r = Files.newBufferedReader(file)) {
                VialumixConfig cfg = GSON.fromJson(r, VialumixConfig.class);
                return Objects.requireNonNullElseGet(cfg, VialumixConfig::new);
            }
        } catch (Exception ignored) {
            return new VialumixConfig();
        }
    }

    public void save(Path file) {
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file)) {
                GSON.toJson(this, w);
            }
        } catch (Exception ignored) { }
    }
}
