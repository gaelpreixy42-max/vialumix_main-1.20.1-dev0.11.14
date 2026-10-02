package com.vialumix.screen;

import com.vialumix.client.VialumixClient;
import com.vialumix.config.VialumixConfig;
import com.vialumix.rt.VialumixNative;
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
    private CyclingButtonWidget<Boolean> rtButton;
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
            // Already on Vialumix; this button is intentionally non-navigating.
        }).dimensions(left + (panelWidth + 8) / 2, y, (panelWidth - 8) / 2, 24).build());
        y += 36;

        List<String> packs = VialumixClient.shaderpacks().packs();
        shaderButton = addDrawableChild(ButtonWidget.builder(
                Text.translatable("vialumix.shaderpack")
                        .append(": ")
                        .append(VialumixClient.shaderpacks().displayName(cfg.shaderpack)),
                b -> cycleShaderpack(packs)
        ).dimensions(left, y, panelWidth, 24).build());
        y += 31;

        rtButton = addDrawableChild(CyclingButtonWidget.onOffBuilder()
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
            VialumixClient.shaderpacks().apply(cfg.shaderpack);
            close();
        })
                .dimensions(left, y, (panelWidth - 8) / 2, 24).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.cancel"), b -> {
            client.setScreen(parent);
        }).dimensions(left + (panelWidth + 8) / 2, y, (panelWidth - 8) / 2, 24).build());
    }

    private int addToggle(int x, int y, String label, BoolGetter getter, BoolSetter setter) {
        addDrawableChild(CyclingButtonWidget.onOffBuilder().initially(getter.get())
                .build(x, y, panelWidth, 24, Text.translatable(label), (b, v) -> setter.set(v)));
        return y + 31;
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

    private Text upscalerText() {
        return Text.translatable("vialumix.upscaler." + cfg.upscaler);
    }

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
