package com.vialumix.mixin;

import com.vialumix.screen.VialumixScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.VideoOptionsScreen;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Opens Vialumix directly when Minecraft's graphics button is selected. */
@Mixin(VideoOptionsScreen.class)
public abstract class VideoOptionsScreenMixin extends Screen {
    protected VideoOptionsScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("HEAD"), cancellable = true)
    private void vialumix$openVialumix(CallbackInfo ci) {
        ci.cancel();
        Screen parent = ((GameOptionsScreenAccessor) (Object) this).vialumix$getParent();
        client.setScreen(new VialumixScreen(parent));
    }
}
