package com.vialumix.shader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Builds a local "(Vialumix RT)" variant of the user's own Bliss shaderpack. The variant is generated on
 * the user's machine from their copy of the pack (nothing from Bliss is redistributed) and differs by:
 * <ul>
 *   <li>three extra samplers ({@code vialumix_rt_a/b/c}) bound by Iris to the Vulkan RT textures,</li>
 *   <li>ray-traced sun shadows replacing the shadow-map result wherever the RT depth matches Iris' depth,</li>
 *   <li>ray-traced reflections (water surface and PBR surfaces) replacing screen-space tracing for the pixels
 *       RT covers, with the SSR marcher kept only as a cheaper fallback,</li>
 *   <li>reduced screen-space settings (fewer SSR steps, no screen-space contact shadows).</li>
 * </ul>
 */
public final class BlissRtPatcher {
    private static final Logger LOGGER = LoggerFactory.getLogger("vialumix-shaderpack");
    public static final String SUFFIX = " (Vialumix RT)";
    /** Bump whenever the patch set changes so existing variants are regenerated. */
    private static final int PATCH_VERSION = 2;
    private static final String STAMP = "shaders/.vialumix_rt_stamp";
    private static volatile float sunPathRotation = -35.0f;

    private BlissRtPatcher() {}

    public static boolean isVariantName(String name) {
        return name != null && stripExtension(name).endsWith(SUFFIX);
    }

    public static boolean isBliss(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).contains("bliss");
    }

    /** The pack the user picked, regardless of whether the RT variant is currently applied. */
    public static String sourceName(String name) {
        return name;
    }

    public static String variantName(String sourceFile) {
        return stripExtension(sourceFile) + SUFFIX;
    }

    private static String stripExtension(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".zip") || lower.endsWith(".jar") ? name.substring(0, name.length() - 4) : name;
    }

    public static float sunPathRotation() { return sunPathRotation; }

    /** True when Iris currently runs a generated RT variant. */
    private static java.lang.reflect.Method currentPackName;

    public static boolean isRtPackActive() {
        try {
            if (currentPackName == null) {
                currentPackName = Class.forName("net.irisshaders.iris.Iris").getMethod("getCurrentPackName");
            }
            Object name = currentPackName.invoke(null);
            return name != null && stripExtension(name.toString()).endsWith(SUFFIX);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Generates (or reuses) the RT variant for {@code sourceFile} inside {@code shaderpacks}.
     * @return the variant folder name to hand to Iris, or {@code null} if the pack could not be patched.
     */
    public static String ensureVariant(Path shaderpacks, String sourceFile) {
        if (sourceFile == null || sourceFile.isBlank()) return null;
        Path source = shaderpacks.resolve(sourceFile);
        String variant = variantName(sourceFile);
        Path target = shaderpacks.resolve(variant);
        try {
            if (!Files.isRegularFile(source)) {
                // Bliss may also be installed as a folder; zip-only is supported for now.
                LOGGER.warn("Vialumix RT needs the Bliss shaderpack as a .zip file; '{}' was not found.", source);
                return null;
            }
            String expected = PATCH_VERSION + ":" + Files.size(source) + ":" + Files.getLastModifiedTime(source).toMillis();
            Path stamp = target.resolve(STAMP);
            if (Files.isRegularFile(stamp) && expected.equals(Files.readString(stamp).trim())) {
                readSunPath(target);
                return variant;
            }
            if (Files.exists(target)) deleteRecursively(target);
            extract(source, target);
            List<String> problems = new ArrayList<>();
            patch(target, problems);
            if (!problems.isEmpty()) {
                for (String problem : problems) LOGGER.error("Bliss RT patch: {}", problem);
                deleteRecursively(target);
                return null;
            }
            Files.writeString(stamp, expected);
            readSunPath(target);
            LOGGER.info("Generated '{}' from '{}' (patch set v{}).", variant, sourceFile, PATCH_VERSION);
            return variant;
        } catch (IOException | RuntimeException error) {
            LOGGER.error("Could not generate the Bliss RT variant", error);
            return null;
        }
    }

    private static void extract(Path zip, Path target) throws IOException {
        try (ZipFile file = new ZipFile(zip.toFile())) {
            var entries = file.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Path out = target.resolve(entry.getName()).normalize();
                if (!out.startsWith(target)) continue;
                if (entry.isDirectory()) { Files.createDirectories(out); continue; }
                Files.createDirectories(out.getParent());
                try (InputStream in = file.getInputStream(entry)) { Files.copy(in, out); }
            }
        }
        // Some pack zips nest everything in one folder; Iris expects shaders/ at the root.
        if (!Files.isDirectory(target.resolve("shaders"))) {
            try (var children = Files.list(target)) {
                List<Path> dirs = children.filter(Files::isDirectory).toList();
                if (dirs.size() == 1 && Files.isDirectory(dirs.get(0).resolve("shaders"))) {
                    Path nested = dirs.get(0);
                    try (var inner = Files.list(nested)) {
                        for (Path p : inner.toList()) Files.move(p, target.resolve(p.getFileName().toString()));
                    }
                    Files.deleteIfExists(nested);
                }
            }
        }
    }

    private static void patch(Path root, List<String> problems) throws IOException {
        Path shaders = root.resolve("shaders");
        if (!Files.isDirectory(shaders)) { problems.add("shaders/ folder missing in the pack"); return; }

        Files.writeString(shaders.resolve("lib/vialumix_rt.glsl"), LIB, StandardCharsets.UTF_8);

        Edit settings = edit(shaders.resolve("lib/settings.glsl"), problems);
        settings.replace("// #define Specular_Reflections", "#define Specular_Reflections");
        settings.replace("// #define Screen_Space_Reflections", "#define Screen_Space_Reflections");
        settings.replace("// #define Sky_reflection", "#define Sky_reflection");
        settings.replace("#define SSR_STEPS 30", "#define SSR_STEPS 16");
        settings.replace("#define SCREENSPACE_CONTACT_SHADOWS", "// #define SCREENSPACE_CONTACT_SHADOWS (replaced by Vialumix ray-traced shadows)");
        settings.replace("#define SHADER_VERSION_LABEL", "#define SHADER_VERSION_LABEL\n"
                + "#define VIALUMIX_RT\n"
                + "#define VLX_RT_RANGE 36.0 // blocks over which ray-traced shadows fade back to the shadow map\n"
                + "#define VLX_RT_EMISSIVE 0.8 // brightness of emissive blocks seen in reflections");
        settings.save();

        Edit composite = edit(shaders.resolve("dimensions/composite1.fsh"), problems);
        composite.replace("#include \"/lib/res_params.glsl\"", "#include \"/lib/res_params.glsl\"\n#include \"/lib/vialumix_rt.glsl\"");
        composite.replace("Shadows = mix(isWater ? lightLeakFix : LM_shadowMapFallback, Shadows, shadowMapFalloff);",
                "Shadows = mix(isWater ? lightLeakFix : LM_shadowMapFallback, Shadows, shadowMapFalloff);\n"
                + "\t\t#ifdef VIALUMIX_RT\n"
                + "\t\t\t{\n"
                + "\t\t\t\tvec4 vlxA = texture2D(vialumix_rt_a, texcoord/RENDER_SCALE);\n"
                + "\t\t\t\tfloat vlxDist = length(feetPlayerPos);\n"
                + "\t\t\t\tif (vlxMatches(vlxA.g, vlxDist) && !hand) {\n"
                + "\t\t\t\t\tfloat vlxFade = 1.0 - smoothstep(VLX_RT_RANGE*0.6, VLX_RT_RANGE, vlxDist);\n"
                + "\t\t\t\t\tShadows = mix(Shadows, min(Shadows, vlxA.r), vlxFade);\n"
                + "\t\t\t\t}\n"
                + "\t\t\t}\n"
                + "\t\t#endif");
        composite.replace("DoSpecularReflections(gl_FragData[0].rgb, viewPos, feetPlayerPos_normalized, WsunVec, specularNoises, normal, SpecularTex.r, SpecularTex.g, albedo, DirectLightColor*Shadows*NdotL, lightmap.y, hand);",
                "DoSpecularReflections(gl_FragData[0].rgb, viewPos, feetPlayerPos_normalized, WsunVec, specularNoises, normal, SpecularTex.r, SpecularTex.g, albedo, DirectLightColor*Shadows*NdotL, lightmap.y, hand, texcoord/RENDER_SCALE, length(feetPlayerPos), DirectLightColor, AmbientLightColor);");
        composite.save();

        Edit specular = edit(shaders.resolve("lib/specular.glsl"), problems);
        specular.replace("    bool Hand // mask for the hand\n){", "    bool Hand, // mask for the hand\n"
                + "\tvec2 RT_UV, float RT_Dist, vec3 RT_Direct, vec3 RT_Ambient // Vialumix ray tracing inputs\n){");
        specular.replace("vec3 RaytracePos = rayTraceSpeculars(mat3(gbufferModelView) * L, FragPos,  Noise.y, float(SSR_Quality), Hand, reflectLength);",
                "bool vlxRT = false;\n"
                + "\t\t\t#ifdef VIALUMIX_RT\n"
                + "\t\t\t\tvec4 vlxA = texture2D(vialumix_rt_a, RT_UV);\n"
                + "\t\t\t\tvlxRT = !Hand && !vlxBit(vlxA.a, 2.0) && vlxMatches(vlxA.g, RT_Dist);\n"
                + "\t\t\t#endif\n"
                + "\t\t\tvec3 RaytracePos = vlxRT ? vec3(0.0, 0.0, 1.0) : rayTraceSpeculars(mat3(gbufferModelView) * L, FragPos,  Noise.y, float(SSR_Quality), Hand, reflectLength);");
        specular.replace("// make sure it takes the fresnel into account for SSR.",
                "#ifdef VIALUMIX_RT\n"
                + "\t\t\tif (vlxRT && vlxBit(vlxA.a, 4.0)) {\n"
                + "\t\t\t\tvec4 vlxB = texture2D(vialumix_rt_b, RT_UV);\n"
                + "\t\t\t\tvec4 vlxC = texture2D(vialumix_rt_c, RT_UV);\n"
                + "\t\t\t\tSS_Reflections.rgb = vlxHitColor(vlxB, vlxC, RT_Direct, RT_Ambient) * Metals;\n"
                + "\t\t\t\tSS_Reflections.a = 1.0;\n"
                + "\t\t\t}\n"
                + "\t\t\t#endif\n"
                + "\t\t\t// make sure it takes the fresnel into account for SSR.");
        specular.save();

        Edit water = edit(shaders.resolve("dimensions/all_translucent.fsh"), problems);
        water.replace("#include \"/lib/res_params.glsl\"", "#include \"/lib/res_params.glsl\"\n#include \"/lib/vialumix_rt.glsl\"");
        water.replace("vec3 rtPos = rayTrace(reflectedVector, viewPos.xyz, interleaved_gradientNoise_temporal(), fresnel, isEyeInWater == 1,reflectLength);",
                "bool vlxWaterRT = false;\n"
                + "\t\t\t\t#ifdef VIALUMIX_RT\n"
                + "\t\t\t\t\tvec2 vlxUV = gl_FragCoord.xy*texelSize/RENDER_SCALE;\n"
                + "\t\t\t\t\tvec4 vlxA = texture2D(vialumix_rt_a, vlxUV);\n"
                + "\t\t\t\t\tif (vlxBit(vlxA.a, 2.0) && vlxMatches(vlxA.b, length(feetPlayerPos)) && isEyeInWater == 0) {\n"
                + "\t\t\t\t\t\tvlxWaterRT = true;\n"
                + "\t\t\t\t\t\tif (vlxBit(vlxA.a, 4.0)) {\n"
                + "\t\t\t\t\t\t\tvec4 vlxB = texture2D(vialumix_rt_b, vlxUV);\n"
                + "\t\t\t\t\t\t\tvec4 vlxC = texture2D(vialumix_rt_c, vlxUV);\n"
                + "\t\t\t\t\t\t\tReflections.rgb = vlxHitColor(vlxB, vlxC, VLX_DIRECT, VLX_AMBIENT) * Metals;\n"
                + "\t\t\t\t\t\t\tReflections.a = 1.0;\n"
                + "\t\t\t\t\t\t}\n"
                + "\t\t\t\t\t}\n"
                + "\t\t\t\t#endif\n"
                + "\t\t\t\tvec3 rtPos = vlxWaterRT ? vec3(0.0, 0.0, 1.0) : rayTrace(reflectedVector, viewPos.xyz, interleaved_gradientNoise_temporal(), fresnel, isEyeInWater == 1,reflectLength);");
        water.save();

        Edit properties = edit(shaders.resolve("shaders.properties"), problems);
        properties.append("\n# Vialumix ray-tracing bridge: textures registered by the mod in Minecraft's TextureManager.\n"
                + "texture.composite.vialumix_rt_a = vialumix:rt_a\n"
                + "texture.composite.vialumix_rt_b = vialumix:rt_b\n"
                + "texture.composite.vialumix_rt_c = vialumix:rt_c\n"
                + "texture.gbuffers.vialumix_rt_a = vialumix:rt_a\n"
                + "texture.gbuffers.vialumix_rt_b = vialumix:rt_b\n"
                + "texture.gbuffers.vialumix_rt_c = vialumix:rt_c\n");
        properties.save();
    }

    private static void readSunPath(Path root) {
        try {
            String text = Files.readString(root.resolve("shaders/lib/settings.glsl"));
            Matcher m = Pattern.compile("const\\s+float\\s+sunPathRotation\\s*=\\s*(-?[0-9.]+)").matcher(text);
            if (m.find()) sunPathRotation = Float.parseFloat(m.group(1));
        } catch (IOException | NumberFormatException ignored) { }
    }

    private static void deleteRecursively(Path path) throws IOException {
        try (var walk = Files.walk(path)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    private static Edit edit(Path file, List<String> problems) throws IOException {
        if (!Files.isRegularFile(file)) {
            problems.add("missing file " + file.getFileName());
            return new Edit(file, "", problems, true);
        }
        return new Edit(file, Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n"), problems, false);
    }

    private static final class Edit {
        private final Path file;
        private final List<String> problems;
        private final boolean missing;
        private String text;

        private Edit(Path file, String text, List<String> problems, boolean missing) {
            this.file = file; this.text = text; this.problems = problems; this.missing = missing;
        }

        void replace(String anchor, String replacement) {
            if (missing) return;
            int index = text.indexOf(anchor);
            if (index < 0) { problems.add(file.getFileName() + ": anchor not found: " + anchor.strip().split("\n")[0]); return; }
            text = text.substring(0, index) + replacement + text.substring(index + anchor.length());
        }

        void append(String extra) { if (!missing) text += extra; }

        void save() throws IOException { if (!missing) Files.writeString(file, text, StandardCharsets.UTF_8); }
    }

    private static final String LIB = """
            // Vialumix ray-tracing bridge helpers (generated).
            #ifdef VIALUMIX_RT
            uniform sampler2D vialumix_rt_a; // shadow, solid distance, water distance, flags (1 solid, 2 water, 4 reflection hit)
            uniform sampler2D vialumix_rt_b; // reflection hit albedo, hit
            uniform sampler2D vialumix_rt_c; // sunlit, emissive, hit distance, sky visibility

            #ifdef OVERWORLD_SHADER
            	#define VLX_DIRECT (lightCol.rgb/80.0)
            	#define VLX_AMBIENT (averageSkyCol_Clouds/30.0)
            #else
            	#define VLX_DIRECT vec3(0.0)
            	#define VLX_AMBIENT vec3(0.25)
            #endif

            // The RT primary surface must coincide with the surface Iris rendered at this pixel.
            bool vlxMatches(float rtDistance, float irisDistance){
            	return rtDistance > 0.0 && abs(rtDistance - irisDistance) < max(0.14, 0.015*irisDistance);
            }

            bool vlxBit(float flags, float bit){
            	return mod(floor(flags/bit + 0.001), 2.0) > 0.5;
            }

            vec3 vlxHitColor(vec4 hitAlbedo, vec4 hitLight, vec3 direct, vec3 ambient){
            	vec3 lit = direct * hitLight.r + ambient * (0.12 + 0.88*hitLight.a);
            	return hitAlbedo.rgb * (lit + hitLight.g * VLX_RT_EMISSIVE);
            }
            #endif
            """;
}
