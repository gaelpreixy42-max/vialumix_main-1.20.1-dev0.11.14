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

public final class VialumixScreen extends Screen {
    private final Screen parent;
    private final VialumixConfig cfg;
    private final int panelWidth = 420;
    private ButtonWidget shaderButton;
    private ButtonWidget resolutionButton;
    private ButtonWidget renderDistanceButton;
    private ButtonWidget graphicsQualityButton;

    public VialumixScreen(Screen parent) {
        super(Text.translatable("vialumix.title"));
        this.parent = parent;
        this.cfg = VialumixClient.config().copy();
        if (cfg.shaderpack == null || cfg.shaderpack.isBlank()) {
            cfg.shaderpack = VialumixClient.shaderpacks().currentSelection();
        }
    }

    @Override
    protected void init() {
        int left = (width - panelWidth) / 2;
        int y = 48;

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

        shaderButton = addDrawableChild(ButtonWidget.builder(
                Text.translatable("vialumix.shaderpack")
                        .append(": ")
                        .append(VialumixClient.shaderpacks().displayName(cfg.shaderpack)),
                b -> openShaderpackSelection()
        ).dimensions(left, y, panelWidth, 24).build());
        y += 31;

        CyclingButtonWidget<Boolean> rayTracingButton = addDrawableChild(CyclingButtonWidget.onOffBuilder()
                .initially(cfg.rayTracing)
                .build(left, y, panelWidth, 24, Text.translatable("vialumix.rt.hardware"), (button, value) -> {
                    if (VialumixNative.isRadianceBackendAvailable()) {
                        button.setValue(true);
                        VialumixClient.notify("vialumix.rt.mcvr_always_active");
                        return;
                    }
                    if (value && !VialumixNative.supportsRayTracing()) {
                        button.setValue(false);
                        VialumixClient.notify("vialumix.rt.backend_missing");
                        return;
                    }
                    cfg.rayTracing = value;
                    cfg.backend = value ? "vulkan" : "iris";
                }));
        if (VialumixNative.isRadianceBackendAvailable()) rayTracingButton.active = false;
        y += 31;

        int halfWidth = (panelWidth - 8) / 2;
        addToggle(left, y, halfWidth, "vialumix.rt.shadows", () -> cfg.rayTracedShadows, v -> cfg.rayTracedShadows = v, true);
        addToggle(left + halfWidth + 8, y, halfWidth, "vialumix.rt.reflections", () -> cfg.rayTracedReflections, v -> cfg.rayTracedReflections = v, true);
        y += 31;
        addToggle(left, y, halfWidth, "vialumix.rt.gi", () -> cfg.rayTracedGI, v -> cfg.rayTracedGI = v, true);
        addToggle(left + halfWidth + 8, y, halfWidth, "vialumix.rt.ao", () -> cfg.rayTracedAO, v -> cfg.rayTracedAO = v, true);
        y += 31;
        addToggle(left, y, halfWidth, "vialumix.rt.denoiser", () -> cfg.denoiser, v -> cfg.denoiser = v, false);
        addToggle(left + halfWidth + 8, y, halfWidth, "vialumix.rt.reconstruction", () -> cfg.rayReconstruction, v -> {
            if (v && !VialumixNative.supportsRayReconstruction()) {
                VialumixClient.notify("vialumix.dlss.rr_missing");
                return;
            }
            cfg.rayReconstruction = v;
        }, false);
        y += 31;

        // DLSS / neural rendering options live on their own screen.
        addDrawableChild(ButtonWidget.builder(Text.translatable("vialumix.neural.open"),
                b -> client.setScreen(new NeuralRenderingScreen(this, cfg))).dimensions(left, y + 6, panelWidth, 24).build());
        y += 46;

        addDrawableChild(ButtonWidget.builder(Text.translatable("vialumix.apply"), b -> applyAndClose())
                .dimensions(left, y, (panelWidth - 8) / 2, 24).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.cancel"), b -> client.setScreen(parent))
                .dimensions(left + (panelWidth + 8) / 2, y, (panelWidth - 8) / 2, 24).build());
    }

    private void applyAndClose() {
        boolean radiance = VialumixNative.isRadianceBackendAvailable();
        String packToApply = cfg.shaderpack;
        if (!radiance && cfg.rayTracing && "vulkan".equalsIgnoreCase(cfg.backend)) {
            // Ray tracing runs on top of Bliss: Iris is pointed at a locally generated "(Vialumix RT)" copy.
            String source = VialumixClient.shaderpacks().sourceOf(cfg.shaderpack);
            if (source == null || source.isBlank()) {
                // No shaderpack: native ray-traced lighting over vanilla/Sodium rendering.
                packToApply = "";
            } else if (!com.vialumix.shader.BlissRtPatcher.isBliss(source)) {
                cfg.rayTracing = false;
                cfg.backend = "iris";
                VialumixClient.notify("vialumix.rt.bliss_required");
            } else {
                String variant = com.vialumix.shader.BlissRtPatcher.ensureVariant(
                        VialumixClient.shaderpacks().directory(), source);
                if (variant == null) {
                    cfg.rayTracing = false;
                    cfg.backend = "iris";
                    VialumixClient.notify("vialumix.rt.patch_failed");
                } else {
                    packToApply = variant;
                }
            }
        }
        // Apply window/graphics settings before Iris rebuilds the rendering pipeline.
        applyBasicGraphics();
        if (!VialumixClient.shaderpacks().apply(packToApply)) {
            VialumixClient.notify("vialumix.shaderpack.apply_failed");
            return;
        }

        if (radiance) {
            cfg.rayTracing = true;
            cfg.backend = "vulkan";
            if (cfg.shaderpack != null && cfg.shaderpack.endsWith("/advanced.zip")) {
                int applied = com.vialumix.rt.RadianceRayTracingBridge.applyBlissInspiredPreset();
                if (applied > 0) VialumixClient.notify("vialumix.rt.bliss_preset_applied");
                else VialumixClient.notify("vialumix.rt.preset_apply_failed");
            }
        }
        VialumixClient.config().copyFrom(cfg);
        if (cfg.rayTracing && "iris".equalsIgnoreCase(cfg.backend)) {
            // The Iris shaderpack reload above consumes the option queue prepared before applying it.
        } else if (cfg.rayTracing) {
            if (!VialumixNative.isRendererReady() && !VialumixNative.startRenderer()) {
                cfg.rayTracing = false;
                cfg.backend = "iris";
                VialumixClient.config().copyFrom(cfg);
                VialumixClient.notify("vialumix.rt.backend_missing");
            }
        } else if (VialumixNative.isRendererReady()) {
            VialumixNative.stopRenderer();
        }

        if (cfg.rayTracing && !radiance) VialumixClient.notify("vialumix.rt.preview_notice");

        VialumixClient.save();
        client.setScreen(parent);
    }

    private void applyBasicGraphics() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.options.getViewDistance().getValue() != cfg.renderDistance) {
            client.options.getViewDistance().setValue(cfg.renderDistance);
        }
        switch (cfg.graphicsQuality) {
            case "fast" -> client.options.getGraphicsMode().setValue(net.minecraft.client.option.GraphicsMode.FAST);
            // Sodium's terrain renderer is not compatible with Minecraft's Fabulous framebuffer path.
            case "fabulous" -> client.options.getGraphicsMode().setValue(net.minecraft.client.option.GraphicsMode.FANCY);
            default -> client.options.getGraphicsMode().setValue(net.minecraft.client.option.GraphicsMode.FANCY);
        }
        if (!"current".equals(cfg.resolution)) {
            String[] size = cfg.resolution.split("x", 2);
            if (size.length == 2) {
                try {
                    client.getWindow().setWindowedSize(Integer.parseInt(size[0]), Integer.parseInt(size[1]));
                } catch (NumberFormatException ignored) { }
            }
        }
        client.options.write();
    }

    private void addToggle(int x, int y, int width, String label, BoolGetter getter, BoolSetter setter, boolean implemented) {
        CyclingButtonWidget<Boolean> toggle = addDrawableChild(CyclingButtonWidget.onOffBuilder().initially(getter.get())
                .build(x, y, width, 24, Text.translatable(label), (b, v) -> setter.set(v)));
        if (VialumixNative.isRadianceBackendAvailable() || !implemented) toggle.active = false;
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

    private void openShaderpackSelection() {
        VialumixClient.shaderpacks().scan();
        client.setScreen(new ShaderpackSelectionScreen(this, cfg.shaderpack, selected -> {
            cfg.shaderpack = selected;
            updateShaderButton();
        }));
    }

    private void updateShaderButton() {
        shaderButton.setMessage(Text.translatable("vialumix.shaderpack").append(": ").append(VialumixClient.shaderpacks().displayName(cfg.shaderpack)));
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
