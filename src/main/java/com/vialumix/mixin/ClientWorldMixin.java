package com.vialumix.mixin;

import com.vialumix.rt.RtSections;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tells the ray-tracing world when a block changes so the affected section mesh is rebuilt. */
@Mixin(ClientWorld.class)
public abstract class ClientWorldMixin {
    @Inject(method = "handleBlockUpdate", at = @At("TAIL"))
    private void vialumix$blockUpdated(BlockPos pos, BlockState state, int flags, CallbackInfo ci) {
        RtSections.onBlockChange(pos);
    }
}
