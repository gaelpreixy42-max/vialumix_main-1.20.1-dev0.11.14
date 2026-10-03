package com.vialumix.rt;

import com.vialumix.mixin.SpriteContentsAccessor;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.PaneBlock;
import net.minecraft.block.TransparentBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteContents;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShape;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts the loaded block world around the camera into triangles for the Vulkan BLASes. Solid geometry
 * follows each block's collision shape (so stairs, slabs, fences, leaves ... match Iris' depth closely);
 * water contributes its exposed top surface. Runs on a worker thread; only reads loaded chunks.
 */
public final class RtSceneBuilder {
    public static final int HORIZONTAL_RADIUS = 40;
    private static final int VERTICAL_UP = 24;
    private static final int VERTICAL_DOWN = 32;
    private static final int MAX_TRIANGLES = 700_000;
    private static final Direction[] FACES = Direction.values();
    private static final float[][][] FACE_CORNERS = {
            {{0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1}}, {{0, 1, 0}, {0, 1, 1}, {1, 1, 1}, {1, 1, 0}},
            {{0, 0, 0}, {0, 1, 0}, {1, 1, 0}, {1, 0, 0}}, {{0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1}},
            {{0, 0, 0}, {0, 0, 1}, {0, 1, 1}, {0, 1, 0}}, {{1, 0, 0}, {1, 1, 0}, {1, 1, 1}, {1, 0, 1}}
    };

    public record Snapshot(int originX, int originY, int originZ, float[] solidVertices, float[] solidColors,
                           float[] waterVertices, float[] waterColors, int solidTriangles, int waterTriangles, long hash) {}

    private final Map<BlockState, float[][]> spriteColors = new HashMap<>();

    public Snapshot build(MinecraftClient client, ClientWorld world, int originX, int originY, int originZ) {
        FloatList sv = new FloatList(1 << 16), sc = new FloatList(1 << 14), wv = new FloatList(1 << 12), wc = new FloatList(1 << 10);
        int minY = Math.max(world.getBottomY(), originY - VERTICAL_DOWN);
        int maxY = Math.min(world.getTopY() - 1, originY + VERTICAL_UP);
        BlockPos.Mutable pos = new BlockPos.Mutable();
        BlockPos.Mutable neighbor = new BlockPos.Mutable();
        Random random = Random.create(42);

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
                        else if (fluid.isIn(FluidTags.LAVA)) addLavaTop(world, sv, sc, pos, neighbor, fluid, originX, originY, originZ);
                    }
                    if (excluded(state)) continue;
                    if (state.isFullCube(world, pos)) {
                        for (Direction face : FACES) {
                            neighbor.set(x + face.getOffsetX(), y + face.getOffsetY(), z + face.getOffsetZ());
                            BlockState other = world.getBlockState(neighbor);
                            if (!other.isAir() && other.isFullCube(world, neighbor) && !excluded(other)) continue;
                            addFace(sv, sc, x - originX, y - originY, z - originZ, 0, 0, 0, 1, 1, 1, face,
                                    color(client, world, state, face, pos, random));
                        }
                        continue;
                    }
                    VoxelShape shape = state.getCollisionShape(world, pos);
                    if (shape.isEmpty() && state.getLuminance() > 0) shape = state.getOutlineShape(world, pos);
                    if (shape.isEmpty()) continue;
                    List<Box> boxes = shape.getBoundingBoxes();
                    for (Box box : boxes) {
                        for (Direction face : FACES) {
                            addFace(sv, sc, x - originX, y - originY, z - originZ, (float) box.minX, (float) box.minY, (float) box.minZ,
                                    (float) box.maxX, (float) box.maxY, (float) box.maxZ, face,
                                    color(client, world, state, face, pos, random));
                        }
                    }
                }
            }
        }
        float[] solidVertices = sv.toArray(), solidColors = sc.toArray(), waterVertices = wv.toArray(), waterColors = wc.toArray();
        long hash = 1469598103934665603L;
        hash = hash * 1099511628211L + Arrays.hashCode(solidVertices);
        hash = hash * 1099511628211L + Arrays.hashCode(solidColors);
        hash = hash * 1099511628211L + Arrays.hashCode(waterVertices);
        hash = hash * 1099511628211L + originX * 31L + originY * 17L + originZ;
        return new Snapshot(originX, originY, originZ, solidVertices, solidColors, waterVertices, waterColors,
                solidVertices.length / 9, waterVertices.length / 9, hash);
    }

    private static boolean excluded(BlockState state) {
        Block block = state.getBlock();
        return block instanceof TransparentBlock || block instanceof PaneBlock || block instanceof LeavesBlock // leaves need alpha-tested textures; Bliss's shadow map keeps them
                || state.isOf(Blocks.BARRIER) || state.isOf(Blocks.LIGHT) || state.isOf(Blocks.STRUCTURE_VOID);
    }

    private void addWaterTop(ClientWorld world, FloatList wv, FloatList wc, BlockPos pos, BlockPos.Mutable neighbor,
                             FluidState fluid, int ox, int oy, int oz) {
        neighbor.set(pos.getX(), pos.getY() + 1, pos.getZ());
        if (world.getFluidState(neighbor).isIn(FluidTags.WATER)) return;
        float height = fluid.getHeight(world, pos);
        addFace(wv, wc, pos.getX() - ox, pos.getY() - oy, pos.getZ() - oz, 0, 0, 0, 1, height, 1, Direction.UP, new float[]{0.05f, 0.2f, 0.3f, 0f});
    }

    private void addLavaTop(ClientWorld world, FloatList sv, FloatList sc, BlockPos pos, BlockPos.Mutable neighbor,
                            FluidState fluid, int ox, int oy, int oz) {
        neighbor.set(pos.getX(), pos.getY() + 1, pos.getZ());
        if (world.getFluidState(neighbor).isIn(FluidTags.LAVA)) return;
        float height = fluid.getHeight(world, pos);
        addFace(sv, sc, pos.getX() - ox, pos.getY() - oy, pos.getZ() - oz, 0, 0, 0, 1, height, 1, Direction.UP, new float[]{1.0f, 0.42f, 0.08f, 1.0f});
    }

    private static void addFace(FloatList vertices, FloatList colors, int bx, int by, int bz,
                                float x0, float y0, float z0, float x1, float y1, float z1, Direction face, float[] color) {
        float[][] c = FACE_CORNERS[face.getId()];
        float[][] p = new float[4][3];
        for (int i = 0; i < 4; i++) {
            p[i][0] = bx + x0 + c[i][0] * (x1 - x0);
            p[i][1] = by + y0 + c[i][1] * (y1 - y0);
            p[i][2] = bz + z0 + c[i][2] * (z1 - z0);
        }
        triangle(vertices, p[0], p[1], p[2]);
        triangle(vertices, p[0], p[2], p[3]);
        for (int i = 0; i < 2; i++) { colors.add(color[0]); colors.add(color[1]); colors.add(color[2]); colors.add(color[3]); }
    }

    private static void triangle(FloatList out, float[] a, float[] b, float[] c) {
        for (float[] v : new float[][]{a, b, c}) { out.add(v[0]); out.add(v[1]); out.add(v[2]); }
    }

    /** Linear-light albedo (rgb) plus emissive strength (a) for one face of a block at a position. */
    private float[] color(MinecraftClient client, ClientWorld world, BlockState state, Direction face, BlockPos pos, Random random) {
        float[][] cached = spriteColors.computeIfAbsent(state, s -> new float[7][]);
        float[] base = cached[face.getId()];
        int tint = -1;
        if (base == null) {
            base = new float[]{0.5f, 0.5f, 0.5f, 0f, -1f};
            try {
                BakedModel model = client.getBlockRenderManager().getModel(state);
                List<BakedQuad> quads = model.getQuads(state, face, random);
                if (quads.isEmpty()) quads = model.getQuads(state, null, random);
                Sprite sprite = quads.isEmpty() ? model.getParticleSprite() : quads.get(0).getSprite();
                if (!quads.isEmpty()) base[4] = quads.get(0).getColorIndex();
                float[] avg = averageSprite(sprite);
                if (avg != null) { base[0] = avg[0]; base[1] = avg[1]; base[2] = avg[2]; }
            } catch (RuntimeException ignored) { }
            base[3] = state.getLuminance() / 15.0f;
            cached[face.getId()] = base;
        }
        tint = (int) base[4];
        float r = base[0], g = base[1], b = base[2];
        if (tint >= 0) {
            int rgb = client.getBlockColors().getColor(state, world, pos, tint);
            if (rgb != -1) {
                r *= srgb(((rgb >> 16) & 255) / 255f);
                g *= srgb(((rgb >> 8) & 255) / 255f);
                b *= srgb((rgb & 255) / 255f);
            }
        }
        return new float[]{r, g, b, base[3]};
    }

    private static float srgb(float c) { return (float) Math.pow(c, 2.2); }

    private static float[] averageSprite(Sprite sprite) {
        SpriteContents contents = sprite.getContents();
        NativeImage image = ((SpriteContentsAccessor) contents).vialumix$getImage();
        if (image == null) return null;
        int w = Math.min(contents.getWidth(), image.getWidth());
        int h = Math.min(contents.getHeight(), image.getHeight());
        double r = 0, g = 0, b = 0, total = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int abgr = image.getColor(x, y);
                int a = abgr >>> 24;
                if (a < 16) continue;
                r += Math.pow((abgr & 255) / 255.0, 2.2);
                g += Math.pow(((abgr >> 8) & 255) / 255.0, 2.2);
                b += Math.pow(((abgr >> 16) & 255) / 255.0, 2.2);
                total += 1;
            }
        }
        if (total == 0) return null;
        return new float[]{(float) (r / total), (float) (g / total), (float) (b / total)};
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
