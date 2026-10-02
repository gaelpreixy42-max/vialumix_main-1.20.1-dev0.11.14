package com.vialumix.modmenu;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import com.vialumix.screen.VialumixScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;

/** Optional Mod Menu integration. Mod Menu remains an optional runtime dependency. */
public final class VialumixModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> new VialumixScreen(parent);
    }
}
