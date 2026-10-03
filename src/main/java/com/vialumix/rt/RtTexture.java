package com.vialumix.rt;

import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.resource.ResourceManager;

/**
 * Registered in Minecraft's TextureManager as {@code vialumix:rt_a/b/c}. Iris resolves
 * {@code texture.composite.<sampler> = vialumix:rt_a} through the TextureManager every time it binds the
 * sampler, so returning the live imported OpenGL id here is all the plumbing the shaderpack needs.
 */
public final class RtTexture extends AbstractTexture {
    private final int index;

    public RtTexture(int index) { this.index = index; }

    @Override
    public void load(ResourceManager manager) {}

    @Override
    public int getGlId() { return RtGlBridge.textureId(index); }

    @Override
    public void clearGlId() {}

    @Override
    public void close() {}
}
