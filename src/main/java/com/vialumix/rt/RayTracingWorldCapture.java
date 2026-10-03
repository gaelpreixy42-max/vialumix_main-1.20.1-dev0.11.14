package com.vialumix.rt;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/** Builds a bounded, opaque-block snapshot in camera space for the Vulkan RT scene probe. */
public final class RayTracingWorldCapture {
    private static final int HORIZONTAL_RADIUS = 24;
    private static final int VERTICAL_RADIUS = 16;
    private static final int MAX_TRIANGLES = 500_000;
    private static final Direction[] FACES = Direction.values();
    private static final float[][][] FACE_CORNERS = {
            {{0,0,0},{1,0,0},{1,0,1},{0,0,1}}, {{0,1,0},{0,1,1},{1,1,1},{1,1,0}},
            {{0,0,0},{0,1,0},{1,1,0},{1,0,0}}, {{0,0,1},{1,0,1},{1,1,1},{0,1,1}},
            {{0,0,0},{0,0,1},{0,1,1},{0,1,0}}, {{1,0,0},{1,1,0},{1,1,1},{1,0,1}}
    };

    private RayTracingWorldCapture() {}

    public static Scene capture(MinecraftClient client) {
        if (client.world == null || client.player == null) return new Scene(new float[0], 0);
        Camera camera = client.gameRenderer.getCamera();
        Vec3d cameraPos = camera.getPos();
        double yaw = Math.toRadians(camera.getYaw());
        double pitch = Math.toRadians(camera.getPitch());
        Vec3d forward = new Vec3d(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch)).normalize();
        Vec3d worldUp = new Vec3d(0, 1, 0);
        Vec3d right = worldUp.crossProduct(forward).normalize();
        if (right.lengthSquared() < 1.0e-6) right = new Vec3d(1, 0, 0);
        Vec3d up = forward.crossProduct(right).normalize();

        int centerX = BlockPos.ofFloored(cameraPos.x, cameraPos.y, cameraPos.z).getX();
        int centerY = BlockPos.ofFloored(cameraPos.x, cameraPos.y, cameraPos.z).getY();
        int centerZ = BlockPos.ofFloored(cameraPos.x, cameraPos.y, cameraPos.z).getZ();
        int minY = Math.max(client.world.getBottomY(), centerY - VERTICAL_RADIUS);
        int maxY = Math.min(client.world.getTopY(), centerY + VERTICAL_RADIUS);
        BlockPos.Mutable pos = new BlockPos.Mutable();
        BlockPos.Mutable neighborPos = new BlockPos.Mutable();
        FloatBuilder vertices = new FloatBuilder(32_768);

        for (int y = minY; y < maxY && vertices.triangles < MAX_TRIANGLES; y++) {
            for (int z = centerZ - HORIZONTAL_RADIUS; z <= centerZ + HORIZONTAL_RADIUS && vertices.triangles < MAX_TRIANGLES; z++) {
                for (int x = centerX - HORIZONTAL_RADIUS; x <= centerX + HORIZONTAL_RADIUS && vertices.triangles < MAX_TRIANGLES; x++) {
                    pos.set(x, y, z);
                    BlockState state = client.world.getBlockState(pos);
                    if (!state.isOpaqueFullCube(client.world, pos)) continue;
                    for (Direction face : FACES) {
                        neighborPos.set(x + face.getOffsetX(), y + face.getOffsetY(), z + face.getOffsetZ());
                        BlockState neighbor = client.world.getBlockState(neighborPos);
                        if (!neighbor.isOpaqueFullCube(client.world, neighborPos)) {
                            appendFace(vertices, x, y, z, face, cameraPos, right, up, forward);
                            if (vertices.triangles >= MAX_TRIANGLES) break;
                        }
                    }
                }
            }
        }
        return new Scene(vertices.toArray(), vertices.triangles);
    }

    private static void appendFace(FloatBuilder out, int x, int y, int z, Direction face,
                                   Vec3d camera, Vec3d right, Vec3d up, Vec3d forward) {
        float[][] corners = FACE_CORNERS[face.getId()];
        addTriangle(out, x, y, z, corners[0], corners[1], corners[2], camera, right, up, forward);
        addTriangle(out, x, y, z, corners[0], corners[2], corners[3], camera, right, up, forward);
    }

    private static void addTriangle(FloatBuilder out, int x, int y, int z, float[] a, float[] b, float[] c,
                                    Vec3d camera, Vec3d right, Vec3d up, Vec3d forward) {
        addVertex(out, x + a[0], y + a[1], z + a[2], camera, right, up, forward);
        addVertex(out, x + b[0], y + b[1], z + b[2], camera, right, up, forward);
        addVertex(out, x + c[0], y + c[1], z + c[2], camera, right, up, forward);
        out.triangles++;
    }

    private static void addVertex(FloatBuilder out, double x, double y, double z,
                                  Vec3d camera, Vec3d right, Vec3d up, Vec3d forward) {
        double dx = x - camera.x, dy = y - camera.y, dz = z - camera.z;
        out.add((float)(dx * right.x + dy * right.y + dz * right.z));
        out.add((float)(dx * up.x + dy * up.y + dz * up.z));
        out.add((float)-(dx * forward.x + dy * forward.y + dz * forward.z));
    }

    public record Scene(float[] vertices, int triangles) {}

    private static final class FloatBuilder {
        private float[] values;
        private int size;
        private int triangles;
        private FloatBuilder(int initialCapacity) { values = new float[initialCapacity]; }
        private void add(float value) {
            if (size == values.length) values = java.util.Arrays.copyOf(values, values.length * 2);
            values[size++] = value;
        }
        private float[] toArray() { return java.util.Arrays.copyOf(values, size); }
    }
}
