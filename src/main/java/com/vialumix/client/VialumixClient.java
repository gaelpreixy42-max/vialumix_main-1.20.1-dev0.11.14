package com.vialumix.client;

import com.vialumix.config.VialumixConfig;
import com.vialumix.rt.VialumixNative;
import com.vialumix.rt.RadianceRayTracingBridge;
import com.vialumix.rt.OpenGLRayTracingProbe;
import com.vialumix.shader.ShaderpackManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class VialumixClient implements ClientModInitializer {
    public static final String MOD_ID = "vialumix";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static VialumixConfig config;
    private static ShaderpackManager shaderpacks;

    @Override
    public void onInitializeClient() {
        MinecraftClient.getInstance();
        Path root = MinecraftClient.getInstance().runDirectory.toPath();
        config = VialumixConfig.load(root.resolve("config/vialumix.json"));
        if (VialumixNative.isRadianceBackendAvailable()) {
            // Radiance installs MCVR's world renderer at startup; this artifact is RT-first.
            config.rayTracing = true;
            config.backend = "vulkan";
        }
        shaderpacks = new ShaderpackManager(root.resolve("shaderpacks"));
        shaderpacks.scan();
        VialumixNative.initialize(root.resolve("vialumix"));

        ClientTickEvents.END_CLIENT_TICK.register(new ClientTickEvents.EndTick() {
            private boolean logged;

            @Override
            public void onEndTick(MinecraftClient client) {
                if (logged) return;
                logged = true;
                try {
                    OpenGLRayTracingProbe.Report report = OpenGLRayTracingProbe.inspectCurrentContext();
                    LOGGER.info("Vialumix renderer capability probe: {}", report);
                    LOGGER.info("Vialumix Vulkan/OpenGL device and export probe: {}",
                            VialumixNative.probeVulkanInterop(report.deviceLuid()));
                    if (report.hasWindowsVulkanInteropExtensions() && report.deviceLuid() != null) {
                        LOGGER.info("Vialumix Vulkan/OpenGL shared-image readback probe: {}",
                                com.vialumix.rt.VulkanOpenGLImageInteropProbe.run(report.deviceLuid()));
                    }
                    if (!report.hasOpenGLNvRayTracing()) {
                        LOGGER.info("GL_NV_ray_tracing is unavailable in this OpenGL context; Vulkan ray tracing is checked independently and is not ruled out by this result.");
                    }
                    if (report.hasWindowsVulkanInteropExtensions()) {
                        LOGGER.info("OpenGL advertises the Windows external-memory and semaphore extensions required by the Vulkan/OpenGL shared-image probe.");
                    } else {
                        LOGGER.info("OpenGL does not advertise the complete Windows external-memory/semaphore extension set; direct Vulkan image sharing may need a different interop path.");
                    }
                } catch (Throwable error) {
                    LOGGER.warn("Vialumix could not inspect the active OpenGL context", error);
                }
            }
        });

        if (VialumixNative.isRadianceBackendAvailable()) {
            // Radiance owns the Vulkan renderer in this artifact. Select its built-in RT pack
            // once the client has entered a world and Radiance has populated the pipeline modules.
            ClientTickEvents.END_CLIENT_TICK.register(new ClientTickEvents.EndTick() {
                private int attempts;
                private boolean activated;

                @Override
                public void onEndTick(MinecraftClient client) {
                    if (activated || client.world == null || attempts >= 2400) return;
                    String activePack = RadianceRayTracingBridge.activePackPath();
                    if (!activePack.isBlank()) {
                        if (config.shaderpack == null || config.shaderpack.isBlank()) {
                            config.shaderpack = activePack;
                            save();
                        }
                        activated = true;
                        return;
                    }
                    attempts++;
                    // Pack discovery scans Radiance's shader directories; retry at 1 Hz while
                    // the world/pipeline finishes initializing instead of scanning every frame.
                    if (attempts % 20 != 0) return;
                    if (RadianceRayTracingBridge.activateBuiltInRayTracing(config.shaderpack)) {
                        activated = true;
                        config.shaderpack = RadianceRayTracingBridge.activePackPath();
                        save();
                        LOGGER.info("Activated Radiance ray-tracing shader pack '{}' after {} second(s).", config.shaderpack, attempts / 20);
                    } else if (attempts == 2400) {
                        LOGGER.error("Could not select a Radiance ray-tracing shader pack. Check Radiance pipeline initialization and GPU support.");
                    }
                }
            });
        }

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> { save(); VialumixNative.stopRenderer(); });
    }

    public static VialumixConfig config() { return config; }
    public static ShaderpackManager shaderpacks() { return shaderpacks; }

    public static void save() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && config != null) {
            config.save(client.runDirectory.toPath().resolve("config/vialumix.json"));
        }
    }

    public static void notify(String key) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) client.player.sendMessage(Text.translatable(key), true);
    }
}
