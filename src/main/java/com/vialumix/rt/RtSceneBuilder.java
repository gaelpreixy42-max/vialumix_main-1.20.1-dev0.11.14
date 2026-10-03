package com.vialumix.rt;

import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.PaneBlock;
import net.minecraft.block.TransparentBlock;
import net.minecraft.client.MinecraftClient;
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
 * Converts the loaded block world around the camera into triangles for the Vulkan BLASes using the exact
 * baked model quads Minecraft itself renders (same variants, offsets, tints and face culling), so the ray
 * traced scene coincides with Iris' depth buffer. Each triangle carries its atlas UVs; the Vulkan side
 * samples the block atlas for colour and alpha (leaves, grass, ...). Fluids are added separately:
 * water as a flat-colour top surface in its own BLAS, lava as an emissive flat-colour top.
 * Runs on a worker thread; only reads loaded chunks.
 */
public final class RtSceneBuilder {
    public static final int HORIZONTAL_RADIUS = 36;
    private static final int VERTICAL_UP = 24;
    private static final int VERTICAL_DOWN = 32;
    private static final int MAX_TRIANGLES = 600_000;
    private static final Direction[] FACES = Direction.values();
    private static final float[][][] FACE_CORNERS = {
            {{0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1}}, {{0, 1, 0}, {0, 1, 1}, {1, 1, 1}, {1, 1, 0}},
            {{0, 0, 0}, {0, 1, 0}, {1, 1, 0}, {1, 0, 0}}, {{0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1}},
            {{0, 0, 0}, {0, 0, 1}, {0, 1, 1}, {0, 1, 0}}, {{1, 0, 0}, {1, 1, 0}, {1, 1, 1}, {1, 0, 1}}
    };

    /** Solid colours buffer is 12 floats per triangle (see rt.rchit); water is 4 floats per triangle. */
    public record Snapshot(int originX, int originY, int originZ, float[] solidVertices, float[] solidData,
                           float[] waterVertices, float[] waterColors, int solidTriangles, int waterTriangles, long hash) {}

    public Snapshot build(MinecraftClient client, ClientWorld world, int originX, int originY, int originZ) {
        FloatList sv = new FloatList(1 << 17), sd = new FloatList(1 << 16), wv = new FloatList(1 << 12), wc = new FloatList(1 << 10);
        int minY = Math.max(world.getBottomY(), originY - VERTICAL_DOWN);
        int maxY = Math.min(world.getTopY() - 1, originY + VERTICAL_UP);
        BlockPos.Mutable pos = new BlockPos.Mutable();
        BlockPos.Mutable neighbor = new BlockPos.Mutable();
        Random random = Random.create(42);
        var models = client.getBlockRenderManager();
        var blockColors = client.getBlockColors();

        outer:
        for (int y = minY; y <= maxY; y++) {
            for (int z = originZ - HORIZONTAL_RADIUS; z <= originZ + HORIZONTAL_RADIUS; z++) {
                for (int x = originX - HORIZONTAL_RADIUS; x <= originX + HORIZONTAL_RADIUS; x++) {
                    if (sv.size / 9 >= MAX_TRIANGLES) break outer;
                    pos.set(x, y, z);
                    BlockState state = world.getBlockState(pos);
                    if (state.isAir()) continue;
                    FluidState fluid = state.getFluidState();
                    if (!fluid.isEmpty()) {
                        if (fluid.isIn(FluidTags.WATER)) addWaterTop(world, wv, wc, pos, neighbor, fluid, originX, originY, originZ);
                        else if (fluid.isIn(FluidTags.LAVA)) addLavaTop(world, sv, sd, pos, neighbor, fluid, originX, originY, originZ);
                    }
                    if (state.getRenderType() != BlockRenderType.MODEL || excluded(state)) continue;

                    BakedModel model = models.getModel(state);
                    Vec3d offset = state.getModelOffset(world, pos);
                    float emissive = state.getLuminance() / 15.0f;
                    long seed = state.getRenderingSeed(pos);
                    float bx = x - originX + (float) offset.x, by = y - originY + (float) offset.y, bz = z - originZ + (float) offset.z;
                    for (int d = -1; d < FACES.length; d++) {
                        Direction cull = d < 0 ? null : FACES[d];
                        if (cull != null) {
                            neighbor.set(x + cull.getOffsetX(), y + cull.getOffsetY(), z + cull.getOffsetZ());
                            if (!Block.shouldDrawSide(state, world, pos, cull, neighbor)) continue;
                        }
                        random.setSeed(seed);
                        List<BakedQuad> quads = model.getQuads(state, cull, random);
                        for (BakedQuad quad : quads) addQuad(sv, sd, quad, bx, by, bz, tint(blockColors, world, state, pos, quad), emissive);
                    }
                }
            }
        }
        float[] solidVertices = sv.toArray(), solidData = sd.toArray(), waterVertices = wv.toArray(), waterColors = wc.toArray();
        long hash = 1469598103934665603L;
        hash = hash * 1099511628211L + Arrays.hashCode(solidVertices);
        hash = hash * 1099511628211L + Arrays.hashCode(solidData);
        hash = hash * 1099511628211L + Arrays.hashCode(waterVertices);
        hash = hash * 1099511628211L + originX * 31L + originY * 17L + originZ;
        return new Snapshot(originX, originY, originZ, solidVertices, solidData, waterVertices, waterColors,
                solidVertices.length / 9, waterVertices.length / 9, hash);
    }

    /** Translucent blocks (glass, panes) would block the whole sun in the opaque-ish shadow rays. */
    private static boolean excluded(BlockState state) {
        Block block = state.getBlock();
        return block instanceof TransparentBlock || block instanceof PaneBlock;
    }

    private static float[] tint(net.minecraft.client.color.block.BlockColors colors, ClientWorld world, BlockState state,
                                BlockPos pos, BakedQuad quad) {
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
        int[][] tris = {{0, 1, 2}, {0, 2, 3}};
        for (int[] t : tris) {
            for (int k : t) { vertices.add(px[k]); vertices.add(py[k]); vertices.add(pz[k]); }
            data.add(u[t[0]]); data.add(w[t[0]]); data.add(u[t[1]]); data.add(w[t[1]]);
            data.add(u[t[2]]); data.add(w[t[2]]); data.add(tint[0]); data.add(tint[1]);
            data.add(tint[2]); data.add(emissive); data.add(0f); data.add(0f);
        }
    }

    private void addWaterTop(ClientWorld world, FloatList wv, FloatList wc, BlockPos pos, BlockPos.Mutable neighbor,
                             FluidState fluid, int ox, int oy, int oz) {
        neighbor.set(pos.getX(), pos.getY() + 1, pos.getZ());
        if (world.getFluidState(neighbor).isIn(FluidTags.WATER)) return;
        float height = fluid.getHeight(world, pos);
        float[] corners = topCorners(pos.getX() - ox, pos.getY() - oy, pos.getZ() - oz, height);
        for (int i = 0; i < corners.length; i++) wv.add(corners[i]);
        for (int i = 0; i < 2; i++) { wc.add(0.05f); wc.add(0.2f); wc.add(0.3f); wc.add(0f); }
    }

    private void addLavaTop(ClientWorld world, FloatList sv, FloatList sd, BlockPos pos, BlockPos.Mutable neighbor,
                            FluidState fluid, int ox, int oy, int oz) {
        neighbor.set(pos.getX(), pos.getY() + 1, pos.getZ());
        if (world.getFluidState(neighbor).isIn(FluidTags.LAVA)) return;
        float height = fluid.getHeight(world, pos);
        float[] corners = topCorners(pos.getX() - ox, pos.getY() - oy, pos.getZ() - oz, height);
        for (int i = 0; i < corners.length; i++) sv.add(corners[i]);
        for (int i = 0; i < 2; i++) {
            for (int k = 0; k < 6; k++) sd.add(0f);
            sd.add(1.0f); sd.add(0.42f); sd.add(0.08f); sd.add(1.0f); sd.add(2.0f); sd.add(0f); // flat colour, emissive
        }
    }

    /** Two triangles covering the top face of a block at the given fluid height (18 floats). */
    private static float[] topCorners(int x, int y, int z, float height) {
        float[][] c = FACE_CORNERS[Direction.UP.getId()];
        float[][] p = new float[4][3];
        for (int i = 0; i < 4; i++) { p[i][0] = x + c[i][0]; p[i][1] = y + height; p[i][2] = z + c[i][2]; }
        float[] out = new float[18];
        int[] order = {0, 1, 2, 0, 2, 3};
        for (int i = 0; i < 6; i++) { out[i * 3] = p[order[i]][0]; out[i * 3 + 1] = p[order[i]][1]; out[i * 3 + 2] = p[order[i]][2]; }
        return out;
    }

    private static final class FloatList {
        private float[] values;
        private int size;
        private FloatList(int capacity) { values = new float[capacity]; }
        private void add(float v) {
            if (size == values.length) values = Arrays.copyOf(values, values.length * 2);
            values[size++] = v;
        }
        private float[] toArray() { return Arrays.copyOf(values, size); }
    }
}
