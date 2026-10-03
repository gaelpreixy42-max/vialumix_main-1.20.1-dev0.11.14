package com.vialumix.rt;

import com.vialumix.client.VialumixClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/** Temporary HUD preview for validating Vulkan RT hits against the captured block scene. */
public final class VialumixRayTracingDebugOverlay {
    private static final int PREVIEW_SIZE = 32;
    private static int[] pixels;
    private static int triangleCount;
    private static long visibleUntil;

    private VialumixRayTracingDebugOverlay() {}

    public static void show(byte[] rgba, int width, int height, int triangles) {
        if (rgba == null || rgba.length < width * height * 4) return;
        int[] preview = new int[PREVIEW_SIZE * PREVIEW_SIZE];
        for (int y = 0; y < PREVIEW_SIZE; y++) {
            int sourceY = Math.min(height - 1, (y * height) / PREVIEW_SIZE);
            for (int x = 0; x < PREVIEW_SIZE; x++) {
                int sourceX = Math.min(width - 1, (x * width) / PREVIEW_SIZE);
                int index = (sourceY * width + sourceX) * 4;
                int red = Byte.toUnsignedInt(rgba[index]);
                int green = Byte.toUnsignedInt(rgba[index + 1]);
                int blue = Byte.toUnsignedInt(rgba[index + 2]);
                int alpha = Byte.toUnsignedInt(rgba[index + 3]);
                preview[y * PREVIEW_SIZE + x] = (alpha << 24) | (red << 16) | (green << 8) | blue;
            }
        }
        pixels = preview;
        triangleCount = triangles;
        MinecraftClient client = MinecraftClient.getInstance();
        visibleUntil = client.world == null ? 0 : client.world.getTime() + 400;
    }

    public static void render(DrawContext context, MinecraftClient client) {
        if (pixels == null || client.world == null || client.player == null || client.world.getTime() > visibleUntil
                || VialumixClient.config() == null || !VialumixClient.config().rayTracing) return;
        int x = 10;
        int y = 10;
        int scale = 4;
        int size = PREVIEW_SIZE * scale;
        context.fill(x - 4, y - 4, x + size + 4, y + size + 24, 0xB0000000);
        for (int py = 0; py < PREVIEW_SIZE; py++) {
            for (int px = 0; px < PREVIEW_SIZE; px++) {
                context.fill(x + px * scale, y + py * scale, x + (px + 1) * scale,
                        y + (py + 1) * scale, pixels[py * PREVIEW_SIZE + px]);
            }
        }
        context.drawTextWithShadow(client.textRenderer,
                Text.literal("Vialumix Vulkan RT preview • " + triangleCount + " triangles"), x, y + size + 7, 0xFFFFFFFF);
    }
}
