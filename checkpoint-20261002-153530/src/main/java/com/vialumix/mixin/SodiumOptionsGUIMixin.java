package com.vialumix.mixin;

import com.vialumix.screen.GraphicsChooserScreen;
import com.vialumix.screen.SodiumScreenBridge;
import me.jellysquid.mods.sodium.client.gui.SodiumOptionsGUI;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Intercepts Sodium 0.5.13's graphics screen factory. */
@Mixin(SodiumOptionsGUI.class)
public abstract class SodiumOptionsGUIMixin {
    @Inject(method = "createScreen", at = @At("RETURN"), cancellable = true)
    private static void vialumix$replaceSodiumScreen(Screen parent, CallbackInfoReturnable<Screen> cir) {
        if (SodiumScreenBridge.isBypassActive()) {
            return;
        }
        cir.setReturnValue(new GraphicsChooserScreen(parent));
    }

    /** Marker kept private so Mixin does not attempt to merge a public helper into SodiumOptionsGUI. */
    @Unique
    private static void vialumix$marker() {
        // Intentionally empty.
    }
}
