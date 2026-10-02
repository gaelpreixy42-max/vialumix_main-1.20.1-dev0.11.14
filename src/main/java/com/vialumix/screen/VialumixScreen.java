package com.vialumix.screen;

import com.vialumix.client.VialumixClient;
import com.vialumix.config.VialumixConfig;
import com.vialumix.rt.VialumixNative;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.text.Text;

import java.util.List;

public final class VialumixScreen extends Screen {
    private final Screen parent;
    private final VialumixConfig cfg;
    private final int panelWidth = 420;
    private ButtonWidget shaderButton;
    private ButtonWidget resolutionButton;
    private ButtonWidget renderDistanceButton;
    private ButtonWidget graphicsQualityButton;
    private ButtonWidget dlssButton;

    public VialumixScreen(Screen parent) {
        super(Text.translatable("vialumix.title"));
        this.parent = parent;
        this.cfg = VialumixClient.config();
    }

    @Override
    protected void init() {
        int left = (width - panelWidth) / 2;
        int y = 48;

        addDrawableChild(ButtonWidget.builder(Text.translatable("vialumix.button.minecraft"), b -> {
            VialumixClient.save();
            client.setScreen(parent);
        }).dimensions(left, y, (panelWidth - 8) / 2, 24).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("vialumix.button.vialumix"), b -> {
        }).dimensions(left + (panelWidth + 8) / 2, y, (panelWidth - 8) / 2, 24).build());
        y += 36;

        resolutionButton = addDrawableChild(ButtonWidget.builder(
                Text.translatable("vialumix.graphics.resolution").append(": ").append(resolutionText()),
                b -> cycleResolution()
        ).dimensions(left, y, panelWidth, 24).build());
        y += 31;

        renderDistanceButton = addDrawableChild(ButtonWidget.builder(
                Text.translatable("vialumix.graphics.render_distance").append(": ").append(Integer.toString(cfg.renderDistance)),
                b -> cycleRenderDistance()
        ).dimensions(left, y, panelWidth, 24).build());
        y += 31;

        graphicsQualityButton = addDrawableChild(ButtonWidget.builder(
                Text.translatable("vialumix.graphics.quality").append(": ").append(graphicsQualityText()),
                b -> cycleGraphicsQuality()
        ).dimensions(left, y, panelWidth, 24).build());
        y += 38;

        List<String> packs = VialumixClient.shaderpacks().packs();
        shaderButton = addDrawableChild(ButtonWidget.builder(
                Text.translatable("vialumix.shaderpack")
                        .append(": ")
                        .append(VialumixClient.shaderpacks().displayName(cfg.shaderpack)),
                b -> cycleShaderpack(packs)
        ).dimensions(left, y, panelWidth, 24).build());
        y += 31;

        addDrawableChild(CyclingButtonWidget.onOffBuilder()
                .initially(cfg.rayTracing)
                .build(left, y, panelWidth, 24, Text.translatable("vialumix.ray_tracing"), (button, value) -> {
                    if (value) {
                        if (!VialumixNative.supportsRayTracing() || !VialumixNative.startRenderer()) {
                            button.setValue(false);
                            VialumixClient.notify("vialumix.rt.backend_missing");
                            return;
                        }
                    } else {
                        VialumixNative.stopRenderer();
                    }
                    cfg.rayTracing = value;
                    cfg.backend = value ? "vulkan" : "iris";
                }));
        y += 31;

        y = addToggle(left, y, "vialumix.rt.shadows", () -> cfg.rayTracedShadows, v -> cfg.rayTracedShadows = v);
        y = addToggle(left, y, "vialumix.rt.reflections", () -> cfg.rayTracedReflections, v -> cfg.rayTracedReflections = v);
        y = addToggle(left, y, "vialumix.rt.gi", () -> cfg.rayTracedGI, v -> cfg.rayTracedGI = v);
        y = addToggle(left, y, "vialumix.rt.ao", () -> cfg.rayTracedAO, v -> cfg.rayTracedAO = v);
        y = addToggle(left, y, "vialumix.rt.denoiser", () -> cfg.denoiser, v -> cfg.denoiser = v);
        y = addToggle(left, y, "vialumix.rt.reconstruction", () -> cfg.rayReconstruction, v -> {
            if (v && !VialumixNative.supportsRayReconstruction()) {
                VialumixClient.notify("vialumix.dlss.rr_missing");
                return;
            }
            cfg.rayReconstruction = v;
        });

        dlssButton = addDrawableChild(ButtonWidget.builder(
                Text.translatable("vialumix.upscaler").append(": ").append(upscalerText()),
                b -> cycleUpscaler()
        ).dimensions(left, y + 6, panelWidth, 24).build());
        y += 37;

        addDrawableChild(ButtonWidget.builder(Text.translatable("vialumix.dlss.quality").append(": ").append(cfg.dlssQuality), b -> cycleDlssQuality())
                .dimensions(left, y, panelWidth, 24).build());
        y += 31;

        addDrawableChild(CyclingButtonWidget.onOffBuilder()
                .initially(cfg.frameGeneration)
                .build(left, y, panelWidth, 24, Text.translatable("vialumix.frame_generation"), (b, value) -> {
                    if (value && !VialumixNative.supportsFrameGeneration()) {
                        VialumixClient.notify("vialumix.dlss.fg_missing");
                        b.setValue(false);
                        return;
                    }
                    cfg.frameGeneration = value;
                }));
        y += 40;

        addDrawableChild(ButtonWidget.builder(Text.translatable("vialumix.apply"), b -> {
            applyBasicGraphics();
            VialumixClient.shaderpacks().apply(cfg.shaderpack);
            close();
        }).dimensions(left, y, (panelWidth - 8) / 2, 24).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.cancel"), b -> client.setScreen(parent))
                .dimensions(left + (panelWidth + 8) / 2, y, (panelWidth - 8) / 2, 24).build());
    }

    private void applyBasicGraphics() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.options.getViewDistance().getValue() != cfg.renderDistance) {
            client.options.getViewDistance().setValue(cfg.renderDistance);
        }
        switch (cfg.graphicsQuality) {
            case "fast" -> client.options.getGraphicsMode().setValue(net.minecraft.client.option.GraphicsMode.FAST);
            case "fabulous" -> client.options.getGraphicsMode().setValue(net.minecraft.client.option.GraphicsMode.FABULOUS);
            default -> client.options.getGraphicsMode().setValue(net.minecraft.client.option.GraphicsMode.FANCY);
        }
        client.options.write();
    }

    private int addToggle(int x, int y, String label, BoolGetter getter, BoolSetter setter) {
        addDrawableChild(CyclingButtonWidget.onOffBuilder().initially(getter.get())
                .build(x, y, panelWidth, 24, Text.translatable(label), (b, v) -> setter.set(v)));
        return y + 31;
    }

    private void cycleResolution() {
        cfg.resolution = switch (cfg.resolution) {
            case "current" -> "1920x1080";
            case "1920x1080" -> "2560x1440";
            case "2560x1440" -> "3840x2160";
            case "3840x2160" -> "1280x720";
            default -> "current";
        };
        resolutionButton.setMessage(Text.translatable("vialumix.graphics.resolution").append(": ").append(resolutionText()));
    }

    private Text resolutionText() {
        return Text.translatable("vialumix.graphics.resolution." + cfg.resolution);
    }

    private void cycleRenderDistance() {
        int[] values = {6, 8, 10, 12, 16, 20, 24, 32};
        int current = 0;
        for (int i = 0; i < values.length; i++) if (values[i] == cfg.renderDistance) current = i;
        cfg.renderDistance = values[(current + 1) % values.length];
        renderDistanceButton.setMessage(Text.translatable("vialumix.graphics.render_distance").append(": ").append(Integer.toString(cfg.renderDistance)));
    }

    private void cycleGraphicsQuality() {
        cfg.graphicsQuality = switch (cfg.graphicsQuality) {
            case "fast" -> "fancy";
            case "fancy" -> "fabulous";
            default -> "fast";
        };
        graphicsQualityButton.setMessage(Text.translatable("vialumix.graphics.quality").append(": ").append(graphicsQualityText()));
    }

    private Text graphicsQualityText() {
        return Text.translatable("vialumix.graphics.quality." + cfg.graphicsQuality);
    }

    private void cycleShaderpack(List<String> packs) {
        if (packs.isEmpty()) { cfg.shaderpack = ""; updateShaderButton(); return; }
        int current = packs.indexOf(cfg.shaderpack);
        cfg.shaderpack = packs.get((current + 1 + packs.size()) % packs.size());
        updateShaderButton();
    }

    private void updateShaderButton() {
        shaderButton.setMessage(Text.translatable("vialumix.shaderpack").append(": ").append(VialumixClient.shaderpacks().displayName(cfg.shaderpack)));
    }

    private void cycleUpscaler() {
        cfg.upscaler = switch (cfg.upscaler) {
            case "native" -> VialumixNative.hasDlssRuntime() ? "dlss" : "fsr";
            case "dlss" -> "fsr";
            case "fsr" -> "xess";
            default -> "native";
        };
        dlssButton.setMessage(Text.translatable("vialumix.upscaler").append(": ").append(upscalerText()));
    }

    private Text upscalerText() { return Text.translatable("vialumix.upscaler." + cfg.upscaler); }

    private void cycleDlssQuality() {
        cfg.dlssQuality = switch (cfg.dlssQuality) {
            case "quality" -> "balanced";
            case "balanced" -> "performance";
            case "performance" -> "ultra_performance";
            default -> "quality";
        };
    }

    @Override
    public void close() {
        VialumixClient.save();
        client.setScreen(parent);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 22, 0xFFFFFF);
        super.render(context, mouseX, mouseY, delta);
    }

    @FunctionalInterface private interface BoolGetter { boolean get(); }
    @FunctionalInterface private interface BoolSetter { void set(boolean value); }
}
