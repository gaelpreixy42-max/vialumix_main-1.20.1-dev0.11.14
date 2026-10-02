package com.vialumix.screen;

import me.jellysquid.mods.sodium.client.gui.SodiumOptionsGUI;
import net.minecraft.client.gui.screen.Screen;

/** Opens Sodium's original screen while temporarily disabling Vialumix's factory interception. */
public final class SodiumScreenBridge {
    private static final ThreadLocal<Boolean> BYPASS = ThreadLocal.withInitial(() -> false);

    private SodiumScreenBridge() {}

    public static boolean isBypassActive() {
        return BYPASS.get();
    }

    public static Screen openOriginal(Screen parent) {
        BYPASS.set(true);
        try {
            return SodiumOptionsGUI.createScreen(parent);
        } finally {
            BYPASS.set(false);
        }
    }
}
