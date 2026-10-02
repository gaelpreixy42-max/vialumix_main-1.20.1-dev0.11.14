package com.vialumix.client;

import com.vialumix.config.VialumixConfig;
import com.vialumix.rt.VialumixNative;
import com.vialumix.shader.ShaderpackManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.nio.file.Path;

public final class VialumixClient implements ClientModInitializer {
    public static final String MOD_ID = "vialumix";
    private static VialumixConfig config;
    private static ShaderpackManager shaderpacks;

    @Override
    public void onInitializeClient() {
        MinecraftClient.getInstance();
        Path root = MinecraftClient.getInstance().runDirectory.toPath();
        config = VialumixConfig.load(root.resolve("config/vialumix.json"));
        shaderpacks = new ShaderpackManager(root.resolve("shaderpacks"));
        shaderpacks.scan();
        VialumixNative.initialize(root.resolve("vialumix"));

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
