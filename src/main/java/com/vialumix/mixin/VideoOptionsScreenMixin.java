package com.vialumix.mixin;

import com.vialumix.screen.VialumixScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.VideoOptionsScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replaces Minecraft's video-options entry point with Vialumix's own graphics screen. */
@Mixin(VideoOptionsScreen.class)
public abstract class VideoOptionsScreenMixin extends Screen {
    protected VideoOptionsScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("HEAD"), cancellable = true)
    private void vialumix$replaceWithChooser(CallbackInfo ci) {
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
                Text.translatable("vialumix.video.vialumix"),
                button -> client.setScreen(new VialumixScreen(this))
        ).dimensions(x, y, buttonWidth, buttonHeight).build());

        addDrawableChild(ButtonWidget.builder(
                Text.translatable("gui.done"),
                button -> close()
        ).dimensions(x, y + 38, buttonWidth, 20).build());
    }
}
