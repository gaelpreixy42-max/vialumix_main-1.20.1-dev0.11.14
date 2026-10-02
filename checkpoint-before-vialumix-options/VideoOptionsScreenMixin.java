package com.vialumix.mixin;

import com.vialumix.screen.VialumixScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.VideoOptionsScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces the first Video Options page with a Vialumix chooser.
 * The original Sodium/Iris video-options screen is still available through
 * the "Minecraft / Sodium" button. A short-lived bypass flag lets the
 * vanilla/Sodium screen initialize normally when opened from the chooser.
 */
@Mixin(VideoOptionsScreen.class)
public abstract class VideoOptionsScreenMixin extends Screen {
    @Unique
    private static final ThreadLocal<Boolean> VIALUMIX_BYPASS = ThreadLocal.withInitial(() -> false);

    protected VideoOptionsScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("HEAD"), cancellable = true)
    private void vialumix$replaceWithChooser(CallbackInfo ci) {
        if (VIALUMIX_BYPASS.get()) {
            return;
        }

        ci.cancel();
        vialumix$buildChooser();
    }

    @Unique
    private void vialumix$buildChooser() {
        int buttonWidth = 310;
        int buttonHeight = 28;
        int x = (width - buttonWidth) / 2;
        int y = Math.max(55, height / 2 - 42);

        addDrawableChild(ButtonWidget.builder(
                Text.translatable("vialumix.video.minecraft"),
                button -> vialumix$openMinecraftGraphics()
        ).dimensions(x, y, buttonWidth, buttonHeight).build());

        addDrawableChild(ButtonWidget.builder(
                Text.translatable("vialumix.video.vialumix"),
                button -> client.setScreen(new VialumixScreen(this))
        ).dimensions(x, y + 38, buttonWidth, buttonHeight).build());

        addDrawableChild(ButtonWidget.builder(
                Text.translatable("gui.done"),
                button -> close()
        ).dimensions(x, y + 82, buttonWidth, 20).build());
    }

    @Unique
    private void vialumix$openMinecraftGraphics() {
        if (client == null) return;

        Screen chooser = this;
        VIALUMIX_BYPASS.set(true);
        try {
            client.setScreen(new VideoOptionsScreen(chooser, client.options));
        } finally {
            VIALUMIX_BYPASS.set(false);
        }
    }
}
