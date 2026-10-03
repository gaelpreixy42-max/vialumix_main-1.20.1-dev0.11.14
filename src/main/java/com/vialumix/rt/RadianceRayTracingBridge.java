package com.vialumix.rt;

import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;

/** Bridges to Radiance's public pipeline API without linking Radiance in the Iris/Sodium build. */
public final class RadianceRayTracingBridge {
    private static final String PIPELINE_CLASS = "com.radiance.client.pipeline.Pipeline";
    private static final String RT_PACK_PREFIX = "shaders/world/ray_tracing/";

    private RadianceRayTracingBridge() {}

    /** Lists Radiance-native Vulkan packs as paths understood by setShaderPack. */
    public static List<String> availablePackPaths() {
        try {
            ClassLoader loader = RadianceRayTracingBridge.class.getClassLoader();
            Class<?> pipeline = Class.forName(PIPELINE_CLASS, true, loader);
            Class<?> choiceType = Class.forName("com.radiance.client.pipeline.Pipeline$ShaderPackChoice", true, loader);
            Object result = pipeline.getMethod("getAvailableShaderPacks").invoke(null);
            if (!(result instanceof List<?> choices)) return List.of();
            Method relativePath = choiceType.getMethod("relativePath");
            List<String> paths = new ArrayList<>();
            for (Object choice : choices) {
                if (choice != null && choiceType.isInstance(choice)) {
                    Object path = relativePath.invoke(choice);
                    if (path != null && !path.toString().isBlank()) paths.add(path.toString());
                }
            }
            paths.sort(String.CASE_INSENSITIVE_ORDER);
            return paths;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            return List.of();
        }
    }

    public static String activePackPath() {
        try {
            ClassLoader loader = RadianceRayTracingBridge.class.getClassLoader();
            Class<?> pipeline = Class.forName(PIPELINE_CLASS, true, loader);
            Class<?> choiceType = Class.forName("com.radiance.client.pipeline.Pipeline$ShaderPackChoice", true, loader);
            Method active = pipeline.getMethod("isShaderPackActive", choiceType);
            Method relativePath = choiceType.getMethod("relativePath");
            Object choices = pipeline.getMethod("getAvailableShaderPacks").invoke(null);
            if (choices instanceof List<?> list) for (Object choice : list) {
                if (choice != null && Boolean.TRUE.equals(active.invoke(null, choice))) {
                    return String.valueOf(relativePath.invoke(choice));
                }
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) { }
        return "";
    }

    /** Selects a Radiance pack from the Vialumix screen and rebuilds the Vulkan pipeline. */
    public static boolean selectPack(String requestedPath) {
        if (requestedPath == null || requestedPath.isBlank()) return false;
        try {
            ClassLoader loader = RadianceRayTracingBridge.class.getClassLoader();
            Class<?> pipeline = Class.forName(PIPELINE_CLASS, true, loader);
            Class<?> choiceType = Class.forName("com.radiance.client.pipeline.Pipeline$ShaderPackChoice", true, loader);
            Method relativePath = choiceType.getMethod("relativePath");
            Method selectable = pipeline.getMethod("isShaderPackSelectable", choiceType);
            Method setShaderPack = pipeline.getMethod("setShaderPack", choiceType, boolean.class);
            Object choices = pipeline.getMethod("getAvailableShaderPacks").invoke(null);
            if (!(choices instanceof List<?> list)) return false;
            for (Object choice : list) {
                if (choice != null && choiceType.isInstance(choice)
                        && requestedPath.equals(String.valueOf(relativePath.invoke(choice)))) {
                    return Boolean.TRUE.equals(selectable.invoke(null, choice))
                            && Boolean.TRUE.equals(setShaderPack.invoke(null, choice, true));
                }
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) { }
        return false;
    }

    /**
     * Applies a conservative Bliss-inspired look preset to Radiance Advanced.
     * This changes real path-tracer attributes; it does not load Bliss's Iris shaders.
     * Returns the number of attributes accepted by Radiance, or zero if Advanced is not active.
     */
    public static int applyBlissInspiredPreset() {
        if (!activePackPath().endsWith("/advanced.zip")) return 0;
        try {
            ClassLoader loader = RadianceRayTracingBridge.class.getClassLoader();
            Class<?> pipeline = Class.forName(PIPELINE_CLASS, true, loader);
            Object module = pipeline.getMethod("getMainRenderModule").invoke(null);
            if (module == null) return 0;

            java.lang.reflect.Field attributesField = module.getClass().getField("attributeConfigs");
            Object attributesObject = attributesField.get(module);
            if (!(attributesObject instanceof List<?> attributes)) return 0;
            Method setValue = pipeline.getMethod("setAttributeValue", module.getClass(),
                    Class.forName("com.radiance.client.pipeline.config.AttributeConfig", true, loader), String.class);

            // Moderate bounce count and volumetric atmosphere/water settings retain the
            // path tracer while approximating the soft, atmospheric Bliss presentation.
            java.util.Map<String, String> preset = java.util.Map.of(
                    "quality_tier", "render_pipeline.module.main_render.attribute.quality_tier.medium",
                    "num_ray_bounces", "4",
                    "cloud_mode", "render_pipeline.module.main_render.attribute.cloud_mode.volumetric",
                    "should_volumetric_clouds_cast_shadows", "render_pipeline.true",
                    "volumetric_cloud_coverage", "0.42",
                    "volumetric_cloud_density", "0.82",
                    "water_color", "0.10,0.36,0.48",
                    "water_absorption", "0.18",
                    "indirect_light_strength", "1.15"
            );
            int changed = 0;
            for (Object attribute : attributes) {
                if (attribute == null) continue;
                java.lang.reflect.Field nameField = attribute.getClass().getField("name");
                String fullName = String.valueOf(nameField.get(attribute));
                String shortName = fullName.substring(fullName.lastIndexOf('.') + 1);
                String value = preset.get(shortName);
                if (value != null && Boolean.TRUE.equals(setValue.invoke(null, module, attribute, value))) changed++;
            }
            if (changed > 0) {
                pipeline.getMethod("savePipeline").invoke(null);
                pipeline.getMethod("build").invoke(null);
            }
            return changed;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            return 0;
        }
    }

    /** Selects and builds Radiance's built-in path-tracing pipeline. */
    public static boolean activateBuiltInRayTracing() {
        return activateBuiltInRayTracing("");
    }

    public static boolean activateBuiltInRayTracing(String preferredPath) {
        try {
            ClassLoader loader = RadianceRayTracingBridge.class.getClassLoader();
            Class<?> pipeline = Class.forName(PIPELINE_CLASS, true, loader);
            Method availablePacks = pipeline.getMethod("getAvailableShaderPacks");
            Method selectable = pipeline.getMethod("isShaderPackSelectable", Class.forName(
                    "com.radiance.client.pipeline.Pipeline$ShaderPackChoice", true, loader));
            Class<?> choiceType = Class.forName("com.radiance.client.pipeline.Pipeline$ShaderPackChoice", true, loader);
            Method relativePath = choiceType.getMethod("relativePath");
            Method setShaderPack = pipeline.getMethod("setShaderPack", choiceType, boolean.class);

            Object packList = availablePacks.invoke(null);
            if (!(packList instanceof List<?> packs)) return false;

            // Advanced is Radiance's full path tracer; vanilla-pt is its compatibility fallback.
            List<?> candidates = packs.stream()
                    .filter(choice -> choice != null && choiceType.isInstance(choice))
                    .filter(choice -> {
                        try { return String.valueOf(relativePath.invoke(choice)).startsWith(RT_PACK_PREFIX); }
                        catch (ReflectiveOperationException ignored) { return false; }
                    })
                    .sorted(Comparator.comparingInt(choice -> {
                        try {
                            String path = String.valueOf(relativePath.invoke(choice));
                            if (!preferredPath.isBlank() && path.equals(preferredPath)) return -1;
                            return path.endsWith("/advanced.zip") ? 0 : path.endsWith("/vanilla-pt.zip") ? 1 : 2;
                        } catch (ReflectiveOperationException ignored) { return 3; }
                    }))
                    .toList();

            for (Object choice : candidates) {
                if (Boolean.TRUE.equals(selectable.invoke(null, choice))
                        && Boolean.TRUE.equals(setShaderPack.invoke(null, choice, true))) {
                    return true;
                }
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            // Radiance is optional; the regular Iris/Sodium artifact must remain loadable without it.
        }
        return false;
    }
}
