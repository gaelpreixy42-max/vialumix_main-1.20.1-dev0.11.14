package com.vialumix.mixin;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.GameOptionsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GameOptionsScreen.class)
public interface GameOptionsScreenAccessor {
    @Accessor("parent")
    Screen vialumix$getParent();
}
