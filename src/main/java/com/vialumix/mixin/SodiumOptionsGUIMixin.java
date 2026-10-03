package com.vialumix.mixin;

import com.vialumix.screen.VialumixScreen;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Replaces Sodium's graphics screen with Vialumix's settings screen. */
@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.gui.SodiumOptionsGUI")
public abstract class SodiumOptionsGUIMixin {
    @Inject(method = "createScreen", at = @At("HEAD"), cancellable = true)
    private static void vialumix$openSettings(Screen parent, CallbackInfoReturnable<Screen> cir) {
        cir.setReturnValue(new VialumixScreen(parent));
    }
}
