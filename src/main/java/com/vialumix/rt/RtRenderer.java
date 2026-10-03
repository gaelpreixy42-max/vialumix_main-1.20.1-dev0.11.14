package com.vialumix.rt;

import com.vialumix.client.VialumixClient;
import com.vialumix.config.VialumixConfig;
import com.vialumix.shader.BlissRtPatcher;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Drives the Vulkan RT bridge: keeps a world snapshot on the GPU, traces once per frame at the start of
 * world rendering, and hands the result to Iris through three shared textures (see {@link RtTexture}).
 */
public final class RtRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger("vialumix-rt");
    private static final String[] TEXTURE_NAMES = {"rt_a", "rt_b", "rt_c", "rt_d", "rt_e", "rt_f"};

    private static boolean texturesRegistered;
    private static boolean nativeReady;
    private static boolean failed;
    private static int originX, originY, originZ;   // TLAS origin for the current frame (16-aligned, near the camera)
    private static int radiusChunks = 16;
    private static float radiusBlocks = 256f;

    private static boolean glSignaled;
    private static boolean tracedThisFrame;
    private static boolean waitedThisFrame;
    private static int frameIndex;
    private static long lastLog;
    private static boolean wasActive;
    // Previous camera (absolute world space, direction vectors pre-scaled by the half-FOV tangents) for temporal reprojection.
    private static final float[] PREV_CAMERA = new float[12];
    private static boolean hasPrevCamera;
    private static final float[] PREV_FORWARD = new float[3];

    private RtRenderer() {}

    public static void register() {
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> registerTextures(client));
        ClientTickEvents.END_CLIENT_TICK.register(RtRenderer::tick);
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
             public Identifier getFabricId() { return new Identifier("vialumix", "rt_atlas"); }
             public void reload(ResourceManager manager) { atlasDirty = true; atlasDelay = 20; }
        });
        WorldRenderEvents.START.register(RtRenderer::frameStart);
        WorldRenderEvents.AFTER_ENTITIES.register(context -> afterEntities());
        WorldRenderEvents.AFTER_TRANSLUCENT.register(RtRenderer::composite);
        WorldRenderEvents.END.register(context -> frameEnd(context));
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> shutdown());
    }

    private static void registerTextures(MinecraftClient client) {
        if (texturesRegistered) return;
        for (int i = 0; i < TEXTURE_NAMES.length; i++) {
            client.getTextureManager().registerTexture(new Identifier("vialumix", TEXTURE_NAMES[i]), new RtTexture(i));
        }
        texturesRegistered = true;
    }

    /** True when RT is enabled, the Bliss RT variant is the active pack, and the native backend is usable. */
    public static boolean active() {
        VialumixConfig config = VialumixClient.config();
        return config != null && config.rayTracing && "vulkan".equalsIgnoreCase(config.backend)
                && !failed && VialumixNative.isLoaded() && !VialumixNative.isRadianceBackendAvailable()
                && VialumixNative.supportsRayTracing() && mode() != Mode.NONE;
    }

    public enum Mode { NONE, BLISS, NATIVE }

    private static java.lang.reflect.Method shaderPackInUse;
    private static Object irisApi;

    private static boolean irisShadersInUse() {
        try {
            if (shaderPackInUse == null) {
                Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                irisApi = api.getMethod("getInstance").invoke(null);
                shaderPackInUse = api.getMethod("isShaderPackInUse");
            }
            return Boolean.TRUE.equals(shaderPackInUse.invoke(irisApi));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** BLISS: Iris runs the generated Bliss RT variant. NATIVE: no shaderpack, RT draws the lit world itself. */
    public static Mode mode() {
        if (BlissRtPatcher.isRtPackActive()) return Mode.BLISS;
        return irisShadersInUse() ? Mode.NONE : Mode.NATIVE;
    }

    private static boolean atlasDirty = true;
    private static int atlasDelay;
    private static boolean packChecked;
    private static int packAttempts;
    private static int packCooldown;

    /** Applies the right shaderpack state once the saved config asks for ray tracing (retries while Iris starts). */
    private static void ensureRtPack() {
        VialumixConfig config = VialumixClient.config();
        if (packChecked || config == null || !config.rayTracing || !"vulkan".equalsIgnoreCase(config.backend)
                || VialumixNative.isRadianceBackendAvailable() || !VialumixNative.supportsRayTracing()) return;
        boolean wantNative = config.shaderpack == null || config.shaderpack.isBlank();
        if (!wantNative && BlissRtPatcher.isRtPackActive()) { packChecked = true; return; }
        if (packCooldown-- > 0) return;
        packCooldown = 40;
        var manager = VialumixClient.shaderpacks();
        if (wantNative) {
            // No shaderpack chosen: native ray-traced lighting. Make sure Iris is not running any pack.
            if (irisShadersInUse() && !manager.apply("")) return;
            LOGGER.info("Vialumix native ray tracing selected (no shaderpack).");
            packChecked = true;
            return;
        }
        String source = manager.sourceOf(config.shaderpack);
        if (!BlissRtPatcher.isBliss(source)) {
            LOGGER.warn("Vialumix RT is enabled but the selected shaderpack '{}' is not Bliss; ray tracing stays idle.", source);
            packChecked = true;
            return;
        }
        String variant = BlissRtPatcher.ensureVariant(manager.directory(), source);
        if (variant == null || !manager.apply(variant)) {
            if (++packAttempts >= 6) {
                packChecked = true;
                LOGGER.error("Vialumix could not apply the Bliss RT shaderpack variant after {} attempts.", packAttempts);
            }
            return;
        }
        packChecked = true;
        LOGGER.info("Vialumix applied shaderpack '{}' for ray tracing.", variant);
    }

    private static boolean bobbingRestore;

    /**
     * Vanilla applies view bobbing inside the projection matrix, so the raster world sways while the ray-traced image does
     * not; the depth test between them then hides the RT image in motion. Bobbing is therefore switched off while RT is
     * active and the player's setting is restored afterwards.
     */
    private static void manageBobbing(MinecraftClient client, boolean rtActive) {
        var option = client.options.getBobView();
        if (rtActive) {
            if (option.getValue()) { option.setValue(false); bobbingRestore = true; }
        } else if (bobbingRestore) {
            option.setValue(true);
            bobbingRestore = false;
        }
    }

    private static void tick(MinecraftClient client) {
        ensureRtPack();
        manageBobbing(client, active() && client.world != null);
        if (!active() || client.world == null || client.player == null) return;
        VialumixConfig config = VialumixClient.config();
        int vanilla = client.options.getViewDistance().getValue();
        radiusChunks = Math.max(2, Math.min(32, config.rtDistanceLimit > 0 ? Math.min(config.rtDistanceLimit, vanilla) : vanilla));
        radiusBlocks = radiusChunks * 16f;
        RtSections.tick(client, radiusChunks);
    }

    private static long framesSeen, framesTraced, compositesDrawn;

    private static void frameStart(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        framesSeen++;
        // A previous frame that never reached frameEnd must still pair its wait/signal.
        if (tracedThisFrame) { finishFrame(); }
        boolean active = active() && client.world != null && client.player != null;
        if (!active) {
            if (wasActive) LOGGER.info("Vialumix RT bridge idle (RT disabled or an unsupported shaderpack is active).");
            wasActive = false;
            return;
        }
        if (!wasActive) LOGGER.info("Vialumix RT mode: {}", mode());
        wasActive = true;
        try {
            if (!ensureNative(client)) return;
            ensureTargets(client);
            uploadAtlasIfNeeded(client);
            if (RtSections.upload(96) == 0) return;
            float[] frame = frameParameters(client, context);
            if (frame == null) return;
            if (VialumixNative.rtTrace(frame, glSignaled, originX, originY, originZ)) {
                glSignaled = false;
                framesTraced++;
                tracedThisFrame = true;
                waitedThisFrame = false;
            }
        } catch (Throwable error) {
            failed = true;
            LOGGER.error("Vialumix RT bridge failed and was disabled for this session", error);
        }
    }

    private static void afterEntities() {
        if (!tracedThisFrame || waitedThisFrame) return;
        try {
            RtGlBridge.waitForVulkan();
            waitedThisFrame = true;
        } catch (Throwable error) {
            failed = true;
            LOGGER.error("Vialumix RT GL wait failed", error);
        }
    }

    private static final float[] lastForward = new float[3];
    private static float lastTanX = 1f, lastTanY = 1f, lastP22 = -1f, lastP32 = -0.1f;
    private static boolean nativeFrame;

    /** Native mode: draws the RT image over the vanilla world (after translucents, before the hand and HUD). */
    private static void composite(WorldRenderContext context) {
        if (!tracedThisFrame || !nativeFrame || failed) return;
        if (!waitedThisFrame) afterEntities();
        try {
            compositesDrawn++;
            RtComposite.draw(RtGlBridge.textureId(5), lastTanX, lastTanY, lastP22, lastP32, radiusBlocks * 0.86f, radiusBlocks * 0.98f, 1.0f);
        } catch (Throwable error) {
            failed = true;
            LOGGER.error("Vialumix RT composite failed", error);
        }
    }

    private static void frameEnd(WorldRenderContext context) {
        if (!tracedThisFrame) return;
        if (!waitedThisFrame) afterEntities();
        debugLog(context);
        finishFrame();
    }

    private static void finishFrame() {
        try {
            if (!waitedThisFrame) { RtGlBridge.waitForVulkan(); waitedThisFrame = true; }
            RtGlBridge.signalVulkan();
            glSignaled = true;
        } catch (Throwable error) {
            failed = true;
            LOGGER.error("Vialumix RT GL signal failed", error);
        } finally {
            tracedThisFrame = false;
            waitedThisFrame = false;
        }
    }

    private static boolean ensureNative(MinecraftClient client) throws Exception {
        if (nativeReady) return true;
        OpenGLRayTracingProbe.Report report = OpenGLRayTracingProbe.inspectCurrentContext();
        if (report.deviceLuid() == null || !report.hasWindowsVulkanInteropExtensions()) {
            LOGGER.warn("Vialumix RT bridge unavailable: OpenGL does not expose the device LUID / Win32 interop extensions.");
            failed = true;
            return false;
        }
        VialumixNative.rtInit(report.deviceLuid(), shader("rt.rgen"), shader("rt.rmiss"), shader("rt_shadow.rmiss"), shader("rt.rchit"), shader("rt.rahit"), shader("rt_post.comp"));
        nativeReady = true;
        LOGGER.info("Vialumix RT bridge: Vulkan device created and ray-tracing pipeline compiled.");
        return true;
    }

    private static void ensureTargets(MinecraftClient client) {
        VialumixConfig config = VialumixClient.config();
        float scale = Math.max(0.25f, Math.min(1.0f, config.rtResolutionScale));
        int w = Math.max(16, Math.round(client.getWindow().getFramebufferWidth() * scale));
        int h = Math.max(16, Math.round(client.getWindow().getFramebufferHeight() * scale));
        if (RtGlBridge.isReady() && RtGlBridge.width() == w && RtGlBridge.height() == h) return;
        if (tracedThisFrame) finishFrame();
        RtGlBridge.configure(w, h);
        glSignaled = false;
        LOGGER.info("Vialumix RT bridge: shared RT images configured at {}x{} (RGBA16F x6).", w, h);
    }

    /** Copies level 0 of the block atlas to Vulkan (once, and again after every resource reload). */
    private static void uploadAtlasIfNeeded(MinecraftClient client) {
        if (!atlasDirty) return;
        if (atlasDelay > 0) { atlasDelay--; return; }
        AbstractTexture texture = client.getTextureManager().getTexture(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE);
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        ByteBuffer pixels = null;
        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture.getGlId());
            int w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            if (w <= 0 || h <= 0) { atlasDelay = 20; return; }
            pixels = MemoryUtil.memAlloc(w * h * 4);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            VialumixNative.rtSetAtlas(pixels, w, h);
            atlasDirty = false;
            RtSections.rebuildAll();
            LOGGER.info("Vialumix RT bridge: block atlas {}x{} uploaded to Vulkan.", w, h);
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
            if (pixels != null) MemoryUtil.memFree(pixels);
        }
    }

    private static float[] frameParameters(MinecraftClient client, WorldRenderContext context) {
        Camera camera = context.camera();
        Matrix4f projection = context.projectionMatrix();
        float tanY = 1.0f / projection.m11();
        float tanX = 1.0f / projection.m00();
        if (!Float.isFinite(tanX) || !Float.isFinite(tanY)) return null;
        Vec3d pos = camera.getPos();
        originX = Math.floorDiv((int) Math.floor(pos.x), 16) * 16;
        originY = Math.floorDiv((int) Math.floor(pos.y), 16) * 16;
        originZ = Math.floorDiv((int) Math.floor(pos.z), 16) * 16;
        // Use the actual view matrix handed to the world renderer: it already contains view bobbing, hurt tilt and nausea.
        org.joml.Matrix4f view = new org.joml.Matrix4f(context.matrixStack().peek().getPositionMatrix());
        org.joml.Matrix3f rot = new org.joml.Matrix3f(view);
        Vector3f right = new Vector3f(rot.m00(), rot.m10(), rot.m20());   // world direction of view-space +X
        Vector3f up = new Vector3f(rot.m01(), rot.m11(), rot.m21());
        Vector3f forward = new Vector3f(-rot.m02(), -rot.m12(), -rot.m22()); // view space looks down -Z
        Vector3f viewShift = new Vector3f(view.m30(), view.m31(), view.m32());
        // view = R * (p - cam) + t  =>  effective camera position = cam - R^T * t
        Vector3f shift = new Vector3f(rot.transpose(new org.joml.Matrix3f()).transform(viewShift));
        pos = new Vec3d(pos.x - shift.x, pos.y - shift.y, pos.z - shift.z);
        float tickDelta = context.tickDelta();
        ClientWorld world = client.world;
        Vector3f sun = sunDirection(world.getSkyAngle(tickDelta), BlissRtPatcher.sunPathRotation());
        Vector3f sunRaw = new Vector3f(sun);
        boolean night = sun.y < 0.0f;
        if (night) sun.negate();
        VialumixConfig config = VialumixClient.config();
        float[] f = new float[56];
        lastForward[0] = forward.x; lastForward[1] = forward.y; lastForward[2] = forward.z;
        lastTanX = tanX; lastTanY = tanY; lastP22 = projection.m22(); lastP32 = projection.m32();
        nativeFrame = mode() == Mode.NATIVE;
        f[0] = (float) (pos.x - originX);
        f[1] = (float) (pos.y - originY);
        f[2] = (float) (pos.z - originZ);
        f[3] = (float) ((world.getTime() + tickDelta) / 20.0);
        f[4] = right.x * tanX; f[5] = right.y * tanX; f[6] = right.z * tanX; f[7] = originX;
        f[8] = up.x * tanY; f[9] = up.y * tanY; f[10] = up.z * tanY; f[11] = originY;
        f[12] = forward.x; f[13] = forward.y; f[14] = forward.z; f[15] = originZ;
        f[16] = sun.x; f[17] = sun.y; f[18] = sun.z; f[19] = night ? 0.03f : 0.018f;
        f[20] = config.rayTracedReflections ? 1.0f : 0.0f;
        f[21] = config.rayTracedShadows ? 1.0f : 0.0f;
        f[22] = radiusBlocks * 1.08f + 24.0f;
        f[23] = frameIndex++ & 0xFFFF;
        f[24] = client.player != null && client.player.isSubmergedInWater() ? 1.0f : 0.0f;
        f[25] = 1.0f;
        boolean gi = config.rayTracedGI, ao = config.rayTracedAO;
        f[26] = (gi || ao || nativeFrame) ? 1.0f : 0.0f;
        f[27] = (ao ? 1 : 0) + (gi ? 2 : 0);
        // Previous camera, re-expressed relative to the current scene origin.
        float[] cur = {(float) pos.x, (float) pos.y, (float) pos.z, 0f,
                right.x * tanX, right.y * tanX, right.z * tanX, 0f,
                up.x * tanY, up.y * tanY, up.z * tanY, 0f};
        float[] prev = hasPrevCamera ? PREV_CAMERA : cur;
        f[28] = prev[0] - originX; f[29] = prev[1] - originY; f[30] = prev[2] - originZ;
        f[32] = prev[4]; f[33] = prev[5]; f[34] = prev[6];
        f[36] = prev[8]; f[37] = prev[9]; f[38] = prev[10];
        float[] prevForward = hasPrevCamera ? PREV_FORWARD : new float[]{forward.x, forward.y, forward.z};
        f[40] = prevForward[0]; f[41] = prevForward[1]; f[42] = prevForward[2];
        System.arraycopy(cur, 0, PREV_CAMERA, 0, 12);
        PREV_FORWARD[0] = forward.x; PREV_FORWARD[1] = forward.y; PREV_FORWARD[2] = forward.z;
        hasPrevCamera = true;
        // Native lighting (only used when no shaderpack renders the world).
        float elevation = sunRaw.y;
        float day = smooth(-0.12f, 0.20f, elevation);
        float low = 1.0f - smooth(0.05f, 0.45f, elevation);
        float rain = world.getRainGradient(tickDelta) * 0.7f;
        float sunPower = 3.2f * day * (1.0f - 0.8f * rain);
        float[] sunRgb = {1.0f, 0.93f - 0.38f * low, 0.82f - 0.57f * low};
        float moon = (1.0f - day) * 0.22f;
        // Sky radiance from vanilla's own sky colour (follows time of day, weather and biome), made linear.
        Vec3d vanillaSky = world.getSkyColor(pos, tickDelta);
        float[] zenith = {lin(vanillaSky.x) * 0.55f, lin(vanillaSky.y) * 0.55f, lin(vanillaSky.z) * 0.55f};
        float[] horizon = {
                (zenith[0] * 0.55f + 0.55f * day) * (1.0f + 0.35f * low), (zenith[1] * 0.55f + 0.62f * day) * (1.0f - 0.10f * low),
                (zenith[2] * 0.55f + 0.70f * day) * (1.0f - 0.35f * low)};
        for (int i = 0; i < 3; i++) { horizon[i] = horizon[i] * 0.80f + 0.03f * (1.0f - day); }
        f[44] = zenith[0]; f[45] = zenith[1]; f[46] = zenith[2];
        f[52] = horizon[0]; f[53] = horizon[1]; f[54] = horizon[2]; f[55] = 1.0f;
        f[47] = nativeFrame ? 1.0f : 0.0f;
        f[48] = sunRgb[0] * sunPower + 0.55f * moon;
        f[49] = sunRgb[1] * sunPower + 0.65f * moon;
        f[50] = sunRgb[2] * sunPower + 1.00f * moon;
        f[51] = 7.0f; // emissive scale
        return f;
    }

    private static float lin(double c) { return (float) Math.pow(Math.max(c, 0.0), 2.2); }

    private static float smooth(float a, float b, float x) {
        float t = Math.max(0f, Math.min(1f, (x - a) / (b - a)));
        return t * t * (3f - 2f * t);
    }

    /** Same celestial transform Iris applies: rotate Y(-90), Z(sunPathRotation), X(skyAngle * 360). */
    static Vector3f sunDirection(float skyAngle, float sunPathRotationDegrees) {
        double theta = skyAngle * Math.PI * 2.0;
        double tilt = Math.toRadians(sunPathRotationDegrees);
        double x = -Math.sin(theta);
        double y = Math.cos(theta) * Math.cos(tilt);
        double z = -Math.cos(theta) * Math.sin(tilt);
        return new Vector3f((float) x, (float) y, (float) z).normalize();
    }

    private static void debugLog(WorldRenderContext context) {
        VialumixConfig config = VialumixClient.config();
        if (config == null || !config.rtDebug) return;
        long now = System.currentTimeMillis();
        if (now - lastLog < 5000) return;
        lastLog = now;
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            int cx = RtGlBridge.width() / 2, cy = RtGlBridge.height() / 2;
            float[] a = RtGlBridge.readTexel(0, cx, cy);
            float[] b = RtGlBridge.readTexel(1, cx, cy);
            float[] c = RtGlBridge.readTexel(2, cx, cy);
            double crosshair = -1;
            if (client.crosshairTarget != null && client.crosshairTarget.getType() != net.minecraft.util.hit.HitResult.Type.MISS) {
                crosshair = client.crosshairTarget.getPos().distanceTo(context.camera().getPos());
            }
            StringBuilder all = new StringBuilder();
            for (int im = 0; im < 6; im++) { float[] s = RtGlBridge.imageStats(im, false); all.append(" ").append((char) ('A' + im)).append("[a>0=").append(String.format("%.3f", s == null ? -1 : s[0])).append(" rgb=").append(s == null ? 0 : String.format("%.3f,%.3f,%.3f", s[1], s[2], s[3])).append("]"); }
            LOGGER.info("RT all images:{}", all);
            float[] fa = RtGlBridge.imageStats(0, true), ff = RtGlBridge.imageStats(5, false);
            LOGGER.info("RT image stats: A.flags>0 {} | F.dist>0 {} (mean rgb {}, {}, {})", fa == null ? -1 : fa[0], ff == null ? -1 : ff[0], ff == null ? 0 : ff[1], ff == null ? 0 : ff[2], ff == null ? 0 : ff[3]);
            Vec3d dbgPos = context.camera().getPos();
            LOGGER.info("RT cam=({}, {}, {}) yaw={} origin=({}, {}, {}) basisFwd=({}, {}, {}) player=({}, {}, {})",
                    String.format("%.1f", dbgPos.x), String.format("%.1f", dbgPos.y), String.format("%.1f", dbgPos.z), String.format("%.1f", context.camera().getYaw()),
                    originX, originY, originZ, lastForward[0], lastForward[1], lastForward[2],
                    String.format("%.1f", client.player.getX()), String.format("%.1f", client.player.getY()), String.format("%.1f", client.player.getZ()));
            LOGGER.info("RT composites drawn {} (traced {}, frames {}) | RT gpu={} ms, {} sections | RT center texel: shadow={} solidDist={} waterDist={} flags={} | crosshairDist={} | reflAlbedo=({}, {}, {}) hit={} | lit={} emissive={} sky={}",
                    compositesDrawn, framesTraced, framesSeen, String.format("%.2f", VialumixNative.rtLastMs()), VialumixNative.rtSectionCount(), a[0], a[1], a[2], a[3], crosshair, b[0], b[1], b[2], b[3], c[0], c[1], c[3]);
        } catch (Throwable error) {
            LOGGER.warn("RT debug read failed: {}", error.toString());
            config.rtDebug = false;
        }
    }

    private static byte[] shader(String name) throws Exception {
        try (InputStream in = RtRenderer.class.getResourceAsStream("/assets/vialumix/rt/" + name + ".spv")) {
            if (in == null) throw new IllegalStateException("Missing RT shader resource " + name);
            return in.readAllBytes();
        }
    }

    public static void shutdown() {
        try {
            if (tracedThisFrame) finishFrame();
            RtSections.reset();
            if (bobbingRestore) { MinecraftClient.getInstance().options.getBobView().setValue(true); bobbingRestore = false; }
            RtGlBridge.release();
            if (nativeReady) VialumixNative.rtShutdown();
        } catch (Throwable ignored) { }
        nativeReady = false;
    }
}
