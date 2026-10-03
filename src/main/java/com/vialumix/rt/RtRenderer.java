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
import net.minecraft.client.world.ClientWorld;
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
    private static final String[] TEXTURE_NAMES = {"rt_a", "rt_b", "rt_c"};
    private static final float MAX_DISTANCE = 112.0f;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Vialumix RT scene builder");
        t.setDaemon(true);
        return t;
    });
    private static final RtSceneBuilder BUILDER = new RtSceneBuilder();
    private static final AtomicReference<RtSceneBuilder.Snapshot> PENDING = new AtomicReference<>();

    private static boolean texturesRegistered;
    private static boolean nativeReady;
    private static boolean failed;
    private static boolean sceneUploaded;
    private static RtSceneBuilder.Snapshot scene;
    private static long uploadedHash;
    private static boolean buildRunning;
    private static int centerX, centerY, centerZ;
    private static boolean hasCenter;
    private static int ticksSinceBuild;

    private static boolean glSignaled;
    private static boolean tracedThisFrame;
    private static boolean waitedThisFrame;
    private static int frameIndex;
    private static long lastLog;
    private static boolean wasActive;

    private RtRenderer() {}

    public static void register() {
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> registerTextures(client));
        ClientTickEvents.END_CLIENT_TICK.register(RtRenderer::tick);
        WorldRenderEvents.START.register(RtRenderer::frameStart);
        WorldRenderEvents.AFTER_ENTITIES.register(context -> afterEntities());
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
                && VialumixNative.supportsRayTracing() && BlissRtPatcher.isRtPackActive();
    }

    private static boolean packChecked;

    /** Applies the generated RT variant of Bliss once when the saved config asks for ray tracing. */
    private static void ensureRtPack() {
        VialumixConfig config = VialumixClient.config();
        if (packChecked || config == null || !config.rayTracing || !"vulkan".equalsIgnoreCase(config.backend)
                || VialumixNative.isRadianceBackendAvailable() || !VialumixNative.supportsRayTracing()) return;
        packChecked = true;
        if (BlissRtPatcher.isRtPackActive()) return;
        var manager = VialumixClient.shaderpacks();
        String source = manager.sourceOf(config.shaderpack == null || config.shaderpack.isBlank() ? manager.currentSelection() : config.shaderpack);
        if (!BlissRtPatcher.isBliss(source)) {
            LOGGER.warn("Vialumix RT is enabled but the selected shaderpack '{}' is not Bliss; ray tracing stays idle.", source);
            return;
        }
        String variant = BlissRtPatcher.ensureVariant(manager.directory(), source);
        if (variant == null || !manager.apply(variant)) {
            LOGGER.error("Vialumix could not apply the Bliss RT shaderpack variant.");
            return;
        }
        LOGGER.info("Vialumix applied shaderpack '{}' for ray tracing.", variant);
    }

    private static void tick(MinecraftClient client) {
        ensureRtPack();
        if (!active() || client.world == null || client.player == null) {
            hasCenter = false;
            return;
        }
        if (buildRunning) return;
        Vec3d cam = client.gameRenderer.getCamera().getPos();
        int cx = (int) Math.floor(cam.x), cy = (int) Math.floor(cam.y), cz = (int) Math.floor(cam.z);
        boolean recenter = !hasCenter || Math.abs(cx - centerX) > 16 || Math.abs(cz - centerZ) > 16 || Math.abs(cy - centerY) > 12;
        if (!recenter && ++ticksSinceBuild < 10) return;
        if (recenter) {
            centerX = Math.floorDiv(cx, 16) * 16 + 8;
            centerY = Math.floorDiv(cy, 8) * 8 + 4;
            centerZ = Math.floorDiv(cz, 16) * 16 + 8;
            hasCenter = true;
        }
        ticksSinceBuild = 0;
        buildRunning = true;
        ClientWorld world = client.world;
        int ox = centerX, oy = centerY, oz = centerZ;
        WORKER.execute(() -> {
            try {
                PENDING.set(BUILDER.build(client, world, ox, oy, oz));
            } catch (Throwable error) {
                LOGGER.warn("Vialumix RT scene capture failed", error);
            } finally {
                buildRunning = false;
            }
        });
    }

    private static void frameStart(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        // A previous frame that never reached frameEnd must still pair its wait/signal.
        if (tracedThisFrame) { finishFrame(); }
        boolean active = active() && client.world != null && client.player != null;
        if (!active) {
            if (wasActive) LOGGER.info("Vialumix RT bridge idle (RT disabled or Bliss RT pack not active).");
            wasActive = false;
            return;
        }
        wasActive = true;
        try {
            if (!ensureNative(client)) return;
            ensureTargets(client);
            uploadPendingScene();
            if (!sceneUploaded) return;
            float[] frame = frameParameters(client, context);
            if (frame == null) return;
            if (VialumixNative.rtTrace(frame, glSignaled)) {
                glSignaled = false;
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
        VialumixNative.rtInit(report.deviceLuid(), shader("rt.rgen"), shader("rt.rmiss"), shader("rt_shadow.rmiss"), shader("rt.rchit"));
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
        LOGGER.info("Vialumix RT bridge: shared RT images configured at {}x{} (RGBA16F x3).", w, h);
    }

    private static void uploadPendingScene() {
        RtSceneBuilder.Snapshot next = PENDING.getAndSet(null);
        if (next == null) return;
        if (sceneUploaded && next.hash() == uploadedHash) return;
        long start = System.nanoTime();
        VialumixNative.rtSetScene(next.solidVertices(), next.solidColors(), next.waterVertices(), next.waterColors());
        scene = next;
        uploadedHash = next.hash();
        sceneUploaded = true;
        LOGGER.info("Vialumix RT scene uploaded: {} solid + {} water triangles around ({}, {}, {}) in {} ms.",
                next.solidTriangles(), next.waterTriangles(), next.originX(), next.originY(), next.originZ(),
                (System.nanoTime() - start) / 1_000_000L);
    }

    private static float[] frameParameters(MinecraftClient client, WorldRenderContext context) {
        Camera camera = context.camera();
        Matrix4f projection = context.projectionMatrix();
        float tanY = 1.0f / projection.m11();
        float tanX = 1.0f / projection.m00();
        if (!Float.isFinite(tanX) || !Float.isFinite(tanY)) return null;
        Vec3d pos = camera.getPos();
        Vector3f forward = new Vector3f(camera.getHorizontalPlane());
        Vector3f up = new Vector3f(camera.getVerticalPlane());
        Vector3f right = new Vector3f(camera.getDiagonalPlane()).negate();
        float tickDelta = context.tickDelta();
        ClientWorld world = client.world;
        Vector3f sun = sunDirection(world.getSkyAngle(tickDelta), BlissRtPatcher.sunPathRotation());
        boolean night = sun.y < 0.0f;
        if (night) sun.negate();
        VialumixConfig config = VialumixClient.config();
        float[] f = new float[28];
        f[0] = (float) (pos.x - scene.originX());
        f[1] = (float) (pos.y - scene.originY());
        f[2] = (float) (pos.z - scene.originZ());
        f[3] = (float) ((world.getTime() + tickDelta) / 20.0);
        f[4] = right.x * tanX; f[5] = right.y * tanX; f[6] = right.z * tanX; f[7] = scene.originX();
        f[8] = up.x * tanY; f[9] = up.y * tanY; f[10] = up.z * tanY; f[11] = scene.originY();
        f[12] = forward.x; f[13] = forward.y; f[14] = forward.z; f[15] = scene.originZ();
        f[16] = sun.x; f[17] = sun.y; f[18] = sun.z; f[19] = night ? 0.03f : 0.018f;
        f[20] = config.rayTracedReflections ? 1.0f : 0.0f;
        f[21] = config.rayTracedShadows ? 1.0f : 0.0f;
        f[22] = MAX_DISTANCE;
        f[23] = frameIndex++ & 0xFFFF;
        f[24] = client.player != null && client.player.isSubmergedInWater() ? 1.0f : 0.0f;
        f[25] = 1.0f;
        return f;
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
            LOGGER.info("RT center texel: shadow={} solidDist={} waterDist={} flags={} | crosshairDist={} | reflAlbedo=({}, {}, {}) hit={} | lit={} emissive={} sky={}",
                    a[0], a[1], a[2], a[3], crosshair, b[0], b[1], b[2], b[3], c[0], c[1], c[3]);
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
            RtGlBridge.release();
            if (nativeReady) VialumixNative.rtShutdown();
        } catch (Throwable ignored) { }
        nativeReady = false;
        sceneUploaded = false;
    }
}
