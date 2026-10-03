package com.vialumix.rt;

import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.PaneBlock;
import net.minecraft.block.TransparentBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;

import java.util.Arrays;
import java.util.List;

/**
 * Builds the ray-tracing mesh of one 16x16x16 world section from the exact baked model quads Minecraft
 * renders (same model variants, offsets, tints and face culling), so the traced geometry coincides with the
 * raster depth. Vertices are section-local (the BLAS instance carries the world offset); every triangle has
 * 12 floats of data (atlas UVs, tint, emissive, flags) while water surfaces use 4 floats per triangle.
 */
final class RtSectionBuilder {
    /** missingMask: bit0 -X, bit1 +X, bit2 -Z, bit3 +Z neighbouring chunk was not loaded when the mesh was built. */
    record Mesh(float[] solidVertices, float[] solidData, float[] waterVertices, float[] waterData, int missingMask) {
        boolean isEmpty() { return solidVertices.length == 0 && waterVertices.length == 0; }
    }

    private static final Direction[] FACES = Direction.values();
    private static final int[][] QUAD_TRIS = {{0, 1, 2}, {0, 2, 3}};

    private RtSectionBuilder() {}

    static Mesh build(MinecraftClient client, ClientWorld world, int sectionX, int sectionY, int sectionZ) {
        FloatList sv = new FloatList(4096), sd = new FloatList(2048), wv = new FloatList(256), wd = new FloatList(64);
        BlockRenderManager models = client.getBlockRenderManager();
        BlockColors colors = client.getBlockColors();
        BlockPos.Mutable pos = new BlockPos.Mutable();
        BlockPos.Mutable neighbor = new BlockPos.Mutable();
        Random random = Random.create(42);
        int baseX = sectionX << 4, baseY = sectionY << 4, baseZ = sectionZ << 4;

        for (int ly = 0; ly < 16; ly++) {
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    pos.set(baseX + lx, baseY + ly, baseZ + lz);
                    BlockState state = world.getBlockState(pos);
                    if (state.isAir()) continue;
                    FluidState fluid = state.getFluidState();
                    if (!fluid.isEmpty()) {
                        if (fluid.isIn(FluidTags.WATER)) addWaterTop(world, wv, wd, pos, neighbor, fluid, lx, ly, lz);
                        else if (fluid.isIn(FluidTags.LAVA)) addLavaTop(world, sv, sd, pos, neighbor, fluid, lx, ly, lz);
                    }
                    if (state.getRenderType() != BlockRenderType.MODEL || excluded(state)) continue;

                    BakedModel model = models.getModel(state);
                    Vec3d offset = state.getModelOffset(world, pos);
                    float emissive = state.getLuminance() / 15.0f;
                    long seed = state.getRenderingSeed(pos);
                    float bx = lx + (float) offset.x, by = ly + (float) offset.y, bz = lz + (float) offset.z;
                    for (int d = -1; d < FACES.length; d++) {
                        Direction cull = d < 0 ? null : FACES[d];
                        if (cull != null) {
                            neighbor.set(pos.getX() + cull.getOffsetX(), pos.getY() + cull.getOffsetY(), pos.getZ() + cull.getOffsetZ());
                            if (!Block.shouldDrawSide(state, world, pos, cull, neighbor)) continue;
                        }
                        random.setSeed(seed);
                        List<BakedQuad> quads = model.getQuads(state, cull, random);
                        for (BakedQuad quad : quads) addQuad(sv, sd, quad, bx, by, bz, tint(colors, world, state, pos, quad), emissive);
                    }
                }
            }
        }
        int cx = sectionX, cz = sectionZ, missing = 0;
        var chunks = world.getChunkManager();
        if (chunks.getWorldChunk(cx - 1, cz) == null) missing |= 1;
        if (chunks.getWorldChunk(cx + 1, cz) == null) missing |= 2;
        if (chunks.getWorldChunk(cx, cz - 1) == null) missing |= 4;
        if (chunks.getWorldChunk(cx, cz + 1) == null) missing |= 8;
        return new Mesh(sv.toArray(), sd.toArray(), wv.toArray(), wd.toArray(), missing);
    }

    /** Translucent blocks (glass, panes) would block the whole sun in the shadow rays. */
    private static boolean excluded(BlockState state) {
        Block block = state.getBlock();
        return block instanceof TransparentBlock || block instanceof PaneBlock;
    }

    private static float[] tint(BlockColors colors, ClientWorld world, BlockState state, BlockPos pos, BakedQuad quad) {
        if (!quad.hasColor()) return new float[]{1f, 1f, 1f};
        int rgb = colors.getColor(state, world, pos, quad.getColorIndex());
        if (rgb == -1) return new float[]{1f, 1f, 1f};
        return new float[]{srgb(((rgb >> 16) & 255) / 255f), srgb(((rgb >> 8) & 255) / 255f), srgb((rgb & 255) / 255f)};
    }

    private static float srgb(float c) { return (float) Math.pow(c, 2.2); }

    private static void addQuad(FloatList vertices, FloatList data, BakedQuad quad, float bx, float by, float bz, float[] tint, float emissive) {
        int[] v = quad.getVertexData();
        float[] px = new float[4], py = new float[4], pz = new float[4], u = new float[4], w = new float[4];
        for (int i = 0; i < 4; i++) {
            int o = i * 8;
            px[i] = bx + Float.intBitsToFloat(v[o]);
            py[i] = by + Float.intBitsToFloat(v[o + 1]);
            pz[i] = bz + Float.intBitsToFloat(v[o + 2]);
            u[i] = Float.intBitsToFloat(v[o + 4]);
            w[i] = Float.intBitsToFloat(v[o + 5]);
        }
        for (int[] t : QUAD_TRIS) {
            for (int k : t) { vertices.add(px[k]); vertices.add(py[k]); vertices.add(pz[k]); }
            data.add(u[t[0]]); data.add(w[t[0]]); data.add(u[t[1]]); data.add(w[t[1]]);
            data.add(u[t[2]]); data.add(w[t[2]]); data.add(tint[0]); data.add(tint[1]);
            data.add(tint[2]); data.add(emissive); data.add(0f); data.add(0f);
        }
    }

    private static void addWaterTop(ClientWorld world, FloatList wv, FloatList wd, BlockPos pos, BlockPos.Mutable neighbor,
                                    FluidState fluid, int lx, int ly, int lz) {
        neighbor.set(pos.getX(), pos.getY() + 1, pos.getZ());
        if (world.getFluidState(neighbor).isIn(FluidTags.WATER)) return;
        addTop(wv, lx, ly, lz, fluid.getHeight(world, pos));
        for (int i = 0; i < 2; i++) { wd.add(0.05f); wd.add(0.2f); wd.add(0.3f); wd.add(0f); }
    }

    private static void addLavaTop(ClientWorld world, FloatList sv, FloatList sd, BlockPos pos, BlockPos.Mutable neighbor,
                                   FluidState fluid, int lx, int ly, int lz) {
        neighbor.set(pos.getX(), pos.getY() + 1, pos.getZ());
        if (world.getFluidState(neighbor).isIn(FluidTags.LAVA)) return;
        addTop(sv, lx, ly, lz, fluid.getHeight(world, pos));
        for (int i = 0; i < 2; i++) {
            for (int k = 0; k < 6; k++) sd.add(0f);
            sd.add(1.0f); sd.add(0.42f); sd.add(0.08f); sd.add(1.0f); sd.add(2.0f); sd.add(0f); // flat colour, emissive
        }
    }

    /** Two triangles covering the top of the block at fluid height (18 floats). */
    private static void addTop(FloatList out, int x, int y, int z, float height) {
        float[][] c = {{0, 0}, {0, 1}, {1, 1}, {1, 0}};
        int[] order = {0, 1, 2, 0, 2, 3};
        for (int i : order) { out.add(x + c[i][0]); out.add(y + height); out.add(z + c[i][1]); }
    }

    static final class FloatList {
        private float[] values;
        int size;
        FloatList(int capacity) { values = new float[capacity]; }
        void add(float v) {
            if (size == values.length) values = Arrays.copyOf(values, values.length * 2);
            values[size++] = v;
        }
        float[] toArray() { return Arrays.copyOf(values, size); }
    }
}
