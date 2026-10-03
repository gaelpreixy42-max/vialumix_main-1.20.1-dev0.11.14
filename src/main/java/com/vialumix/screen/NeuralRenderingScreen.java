package com.vialumix.screen;

import com.vialumix.client.VialumixClient;
import com.vialumix.config.VialumixConfig;
import com.vialumix.rt.NeuralRendering;
import com.vialumix.rt.VialumixNative;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

/**
 * All DLSS / neural-rendering options in one place. The DLSS 5 controls mirror what NVIDIA documents for its
 * 3D-guided neural rendering stage (model selection, structure intensity, tone intensity, semantic AI mask and
 * engine-level mask). They edit the same working copy of the config as the main Vialumix screen, so the main
 * screen's Apply button persists them.
 */
public final class NeuralRenderingScreen extends Screen {
    private static final String[] MODELS = {"auto", "quality", "balanced", "fast"};
    private static final String[] UPSCALERS = {"native", "dlss", "fsr", "xess"};
    private static final String[] DLSS_QUALITY = {"quality", "balanced", "performance", "ultra_performance"};

    private final Screen parent;
    private final VialumixConfig cfg;
    private final int panelWidth = 420;
    private NeuralRendering.Status status = NeuralRendering.Status.PLUGIN_MISSING;
    private ButtonWidget modelButton;
    private ButtonWidget upscalerButton;
    private ButtonWidget qualityButton;

    public NeuralRenderingScreen(Screen parent, VialumixConfig cfg) {
        super(Text.translatable("vialumix.neural.title"));
        this.parent = parent;
        this.cfg = cfg;
    }

    @Override
    protected void init() {
        status = NeuralRendering.status();
        int left = (width - panelWidth) / 2;
        int half = (panelWidth - 8) / 2;
        int y = 64;

        addDrawableChild(CyclingButtonWidget.onOffBuilder().initially(cfg.neuralRendering)
                .build(left, y, panelWidth, 20, Text.translatable("vialumix.neural.enable"), (b, v) -> cfg.neuralRendering = v));
        y += 24;

        modelButton = addDrawableChild(ButtonWidget.builder(modelText(), b -> {
            cfg.neuralModel = next(MODELS, cfg.neuralModel);
            modelButton.setMessage(modelText());
        }).dimensions(left, y, panelWidth, 20).build());
        y += 24;

        addDrawableChild(new IntensitySlider(left, y, panelWidth, 20, "vialumix.neural.structure", cfg.neuralStructureIntensity, v -> cfg.neuralStructureIntensity = v));
        y += 24;
        addDrawableChild(new IntensitySlider(left, y, panelWidth, 20, "vialumix.neural.tone", cfg.neuralToneIntensity, v -> cfg.neuralToneIntensity = v));
        y += 24;

        addDrawableChild(CyclingButtonWidget.onOffBuilder().initially(cfg.neuralSemanticMask)
                .build(left, y, half, 20, Text.translatable("vialumix.neural.semantic_mask"), (b, v) -> cfg.neuralSemanticMask = v));
        addDrawableChild(CyclingButtonWidget.onOffBuilder().initially(cfg.neuralEngineMask)
                .build(left + half + 8, y, half, 20, Text.translatable("vialumix.neural.engine_mask"), (b, v) -> cfg.neuralEngineMask = v));
        y += 30;

        // Classic DLSS options (previously on the main screen).
        upscalerButton = addDrawableChild(ButtonWidget.builder(upscalerText(), b -> {
            cfg.upscaler = next(UPSCALERS, cfg.upscaler);
            if ("dlss".equals(cfg.upscaler) && !VialumixNative.hasDlssRuntime()) cfg.upscaler = "fsr";
            upscalerButton.setMessage(upscalerText());
        }).dimensions(left, y, half, 20).build());
        qualityButton = addDrawableChild(ButtonWidget.builder(qualityText(), b -> {
            cfg.dlssQuality = next(DLSS_QUALITY, cfg.dlssQuality);
            qualityButton.setMessage(qualityText());
        }).dimensions(left + half + 8, y, half, 20).build());
        y += 24;

        addDrawableChild(CyclingButtonWidget.onOffBuilder().initially(cfg.frameGeneration)
                .build(left, y, half, 20, Text.translatable("vialumix.frame_generation"), (b, v) -> {
                    if (v && !VialumixNative.supportsFrameGeneration()) {
                        VialumixClient.notify("vialumix.dlss.fg_missing");
                        b.setValue(false);
                        return;
                    }
                    cfg.frameGeneration = v;
                }));
        addDrawableChild(ButtonWidget.builder(Text.translatable("vialumix.neural.reset"), b -> reset())
                .dimensions(left + half + 8, y, half, 20).build());
        y += 32;

        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), b -> close())
                .dimensions(left, y, panelWidth, 20).build());
    }

    private void reset() {
        VialumixConfig defaults = new VialumixConfig();
        cfg.neuralRendering = defaults.neuralRendering;
        cfg.neuralModel = defaults.neuralModel;
        cfg.neuralStructureIntensity = defaults.neuralStructureIntensity;
        cfg.neuralToneIntensity = defaults.neuralToneIntensity;
        cfg.neuralSemanticMask = defaults.neuralSemanticMask;
        cfg.neuralEngineMask = defaults.neuralEngineMask;
        client.setScreen(new NeuralRenderingScreen(parent, cfg));
    }

    private static String next(String[] values, String current) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(current)) return values[(i + 1) % values.length];
        return values[0];
    }

    private Text modelText() { return Text.translatable("vialumix.neural.model").append(": ").append(Text.translatable("vialumix.neural.model." + cfg.neuralModel)); }
    private Text upscalerText() { return Text.translatable("vialumix.upscaler").append(": ").append(Text.translatable("vialumix.upscaler." + cfg.upscaler)); }
    private Text qualityText() { return Text.translatable("vialumix.dlss.quality").append(": ").append(cfg.dlssQuality); }

    @Override
    public void close() { client.setScreen(parent); }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 18, 0xFFFFFF);
        int color;
        String key;
        switch (status) {
            case READY -> { color = 0x55FF55; key = "vialumix.neural.status.ready"; }
            case GPU_UNSUPPORTED -> { color = 0xFF7777; key = "vialumix.neural.status.gpu"; }
            default -> { color = 0xFFCC55; key = "vialumix.neural.status.plugin"; }
        }
        context.drawCenteredTextWithShadow(textRenderer, Text.translatable(key), width / 2, 34, color);
        if (status != NeuralRendering.Status.READY) {
            context.drawCenteredTextWithShadow(textRenderer, Text.translatable("vialumix.neural.status.note"), width / 2, 46, 0xAAAAAA);
        }
        super.render(context, mouseX, mouseY, delta);
    }

    /** 0 - 100 % slider (structure / tone intensity). */
    private static final class IntensitySlider extends SliderWidget {
        private final String key;
        private final java.util.function.IntConsumer setter;

        IntensitySlider(int x, int y, int width, int height, String key, int percent, java.util.function.IntConsumer setter) {
            super(x, y, width, height, Text.empty(), MathHelper.clamp(percent, 0, 100) / 100.0);
            this.key = key;
            this.setter = setter;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Text.translatable(key).append(": " + (int) Math.round(value * 100) + " %"));
        }

        @Override
        protected void applyValue() {
            setter.accept((int) Math.round(value * 100));
        }
    }
}
