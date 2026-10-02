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

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

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
