package com.vialumix.rt;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Keeps the Vulkan ray-tracing world in sync with the client world, one 16^3 section at a time: sections
 * inside the ray-tracing render distance are meshed on worker threads (nearest first), uploaded to the GPU
 * with a per-frame budget, rebuilt when a block changes or a neighbouring chunk loads, and dropped when they
 * leave the radius. All state except the worker queues is touched only from the client thread.
 */
public final class RtSections {
    private static final Logger LOGGER = LoggerFactory.getLogger("vialumix-rt");
    static final int MAX_SLOTS = 65536;

    private record Task(long key, int sx, int sy, int sz, double priority, long order) implements Comparable<Task> {
        @Override public int compareTo(Task other) {
            int c = Double.compare(priority, other.priority);
            return c != 0 ? c : Long.compare(order, other.order);
        }
    }
    private record Result(long key, int sx, int sy, int sz, RtSectionBuilder.Mesh mesh, ClientWorld world) {}
    private static final class Entry { int solidSlot = -1, waterSlot = -1, missingMask; }

    private static final PriorityBlockingQueue<Task> QUEUE = new PriorityBlockingQueue<>();
    private static final ConcurrentLinkedQueue<Result> RESULTS = new ConcurrentLinkedQueue<>();
    private static final AtomicLong ORDER = new AtomicLong();
    private static ExecutorService workers;

    private static final Map<Long, Entry> ENTRIES = new HashMap<>();
    private static final Set<Long> COLUMNS = new HashSet<>();
    private static final ArrayDeque<Integer> FREE_SLOTS = new ArrayDeque<>();
    private static ClientWorld currentWorld;
    private static int camSx, camSy, camSz;
    private static int scanTicks;
    private static long lastLog;
    private static int uploadedSinceLog;

    private RtSections() {}

    private static long sectionKey(int sx, int sy, int sz) {
        return ((long) (sx & 0x3FFFFF) << 42) | ((long) (sz & 0x3FFFFF) << 20) | (sy & 0xFFFFF);
    }
    private static long columnKey(int cx, int cz) { return ((long) cx << 32) | (cz & 0xFFFFFFFFL); }

    private static void ensureWorkers() {
        if (workers != null) return;
        int threads = Math.max(3, Math.min(8, Runtime.getRuntime().availableProcessors() / 3));
        workers = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "Vialumix RT section builder");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
        for (int i = 0; i < threads; i++) workers.execute(RtSections::workerLoop);
        if (FREE_SLOTS.isEmpty()) for (int i = MAX_SLOTS - 1; i >= 0; i--) FREE_SLOTS.push(i);
    }

    private static void workerLoop() {
        MinecraftClient client = MinecraftClient.getInstance();
        while (true) {
            try {
                Task task = QUEUE.take();
                ClientWorld world = currentWorld;
                if (world == null) continue;
                RtSectionBuilder.Mesh mesh = RtSectionBuilder.build(client, world, task.sx, task.sy, task.sz);
                RESULTS.add(new Result(task.key, task.sx, task.sy, task.sz, mesh, world));
            } catch (InterruptedException interrupted) {
                return;
            } catch (Throwable error) {
                LOGGER.warn("Vialumix RT section build failed: {}", error.toString());
            }
        }
    }

    private static void enqueue(int sx, int sy, int sz, double priority) {
        QUEUE.add(new Task(sectionKey(sx, sy, sz), sx, sy, sz, priority, ORDER.incrementAndGet()));
    }

    private static double priorityOf(int sx, int sy, int sz) {
        double dx = sx - camSx, dy = (sy - camSy) * 0.7, dz = sz - camSz;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Called from the block-update mixin (client thread). */
    public static void onBlockChange(BlockPos pos) {
        if (workers == null || currentWorld == null) return;
        int sx = pos.getX() >> 4, sy = pos.getY() >> 4, sz = pos.getZ() >> 4;
        if (!COLUMNS.contains(columnKey(sx, sz))) return;
        enqueue(sx, sy, sz, -1.0);
        int lx = pos.getX() & 15, ly = pos.getY() & 15, lz = pos.getZ() & 15;
        if (lx == 0) enqueue(sx - 1, sy, sz, -0.5);
        if (lx == 15) enqueue(sx + 1, sy, sz, -0.5);
        if (ly == 0) enqueue(sx, sy - 1, sz, -0.5);
        if (ly == 15) enqueue(sx, sy + 1, sz, -0.5);
        if (lz == 0) enqueue(sx, sy, sz - 1, -0.5);
        if (lz == 15) enqueue(sx, sy, sz + 1, -0.5);
    }

    /** Client thread, once per tick while ray tracing is active. */
    public static void tick(MinecraftClient client, int radiusChunks) {
        ClientWorld world = client.world;
        if (world == null) { reset(); return; }
        ensureWorkers();
        if (world != currentWorld) { reset(); currentWorld = world; }
        BlockPos camera = client.gameRenderer.getCamera().getBlockPos();
        camSx = camera.getX() >> 4; camSy = camera.getY() >> 4; camSz = camera.getZ() >> 4;
        if (++scanTicks < 4) return;
        scanTicks = 0;

        // Add columns that came into range / finished loading.
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                if (dx * dx + dz * dz > radiusChunks * radiusChunks + 2) continue;
                int cx = camSx + dx, cz = camSz + dz;
                long ck = columnKey(cx, cz);
                if (COLUMNS.contains(ck)) continue;
                WorldChunk chunk = world.getChunkManager().getWorldChunk(cx, cz);
                if (chunk == null) continue;
                COLUMNS.add(ck);
                enqueueColumn(world, chunk, cx, cz);
                // Faces on the shared border of neighbouring columns depend on this chunk now existing.
                refreshNeighbour(world, cx - 1, cz, 2); refreshNeighbour(world, cx + 1, cz, 1);
                refreshNeighbour(world, cx, cz - 1, 8); refreshNeighbour(world, cx, cz + 1, 4);
            }
        }
        // Drop columns that left the radius or were unloaded.
        int outside = radiusChunks + 2;
        for (Iterator<Long> it = COLUMNS.iterator(); it.hasNext(); ) {
            long ck = it.next();
            int cx = (int) (ck >> 32), cz = (int) ck;
            boolean far = Math.abs(cx - camSx) > outside || Math.abs(cz - camSz) > outside;
            if (far || world.getChunkManager().getWorldChunk(cx, cz) == null) {
                it.remove();
                removeColumn(world, cx, cz);
            }
        }
    }

    /** A new column appeared next to (cx, cz): rebuild only the sections that were meshed while that neighbour was missing. */
    private static void refreshNeighbour(ClientWorld world, int cx, int cz, int missingBit) {
        if (!COLUMNS.contains(columnKey(cx, cz))) return;
        int bottom = world.getBottomSectionCoord(), top = world.getTopSectionCoord();
        for (int sy = bottom; sy < top; sy++) {
            Entry entry = ENTRIES.get(sectionKey(cx, sy, cz));
            if (entry != null && (entry.missingMask & missingBit) != 0) enqueue(cx, sy, cz, priorityOf(cx, sy, cz) - 0.5);
        }
    }

    private static void enqueueColumn(ClientWorld world, WorldChunk chunk, int cx, int cz) {
        ChunkSection[] sections = chunk.getSectionArray();
        for (int i = 0; i < sections.length; i++) {
            int sy = world.sectionIndexToCoord(i);
            if (sections[i].isEmpty()) {
                // Became empty (or always was): make sure no stale geometry stays.
                if (ENTRIES.containsKey(sectionKey(cx, sy, cz))) enqueue(cx, sy, cz, priorityOf(cx, sy, cz));
                continue;
            }
            enqueue(cx, sy, cz, priorityOf(cx, sy, cz));
        }
    }

    private static void removeColumn(ClientWorld world, int cx, int cz) {
        int bottom = world.getBottomSectionCoord(), top = world.getTopSectionCoord();
        for (int sy = bottom; sy < top; sy++) {
            Entry entry = ENTRIES.remove(sectionKey(cx, sy, cz));
            if (entry != null) release(entry);
        }
    }

    private static void release(Entry entry) {
        if (entry.solidSlot >= 0) { VialumixNative.rtSectionRemove(entry.solidSlot); FREE_SLOTS.push(entry.solidSlot); entry.solidSlot = -1; }
        if (entry.waterSlot >= 0) { VialumixNative.rtSectionRemove(entry.waterSlot); FREE_SLOTS.push(entry.waterSlot); entry.waterSlot = -1; }
    }

    /** Render thread, before tracing: uploads finished meshes (one GPU submission per call). Returns the live section count. */
    public static int upload(int maxSections) {
        java.util.ArrayList<Integer> slots = new java.util.ArrayList<>(), kinds = new java.util.ArrayList<>(), positions = new java.util.ArrayList<>();
        java.util.ArrayList<float[]> verts = new java.util.ArrayList<>(), data = new java.util.ArrayList<>();
        int done = 0;
        Result r;
        while (done < maxSections && (r = RESULTS.poll()) != null) {
            if (r.world != currentWorld) continue;
            if (!COLUMNS.contains(columnKey(r.sx, r.sz))) continue; // column was dropped meanwhile
            done++;
            uploadedSinceLog++;
            Entry entry = ENTRIES.computeIfAbsent(r.key, k -> new Entry());
            entry.missingMask = r.mesh.missingMask();
            int wx = r.sx << 4, wy = r.sy << 4, wz = r.sz << 4;
            entry.solidSlot = stage(entry.solidSlot, 0, wx, wy, wz, r.mesh.solidVertices(), r.mesh.solidData(), slots, kinds, positions, verts, data);
            entry.waterSlot = stage(entry.waterSlot, 1, wx, wy, wz, r.mesh.waterVertices(), r.mesh.waterData(), slots, kinds, positions, verts, data);
            if (entry.solidSlot < 0 && entry.waterSlot < 0) ENTRIES.remove(r.key);
        }
        if (!slots.isEmpty()) {
            int[] s = new int[slots.size()], k = new int[slots.size()], p = new int[slots.size() * 3];
            for (int i = 0; i < s.length; i++) {
                s[i] = slots.get(i); k[i] = kinds.get(i);
                p[i * 3] = positions.get(i * 3); p[i * 3 + 1] = positions.get(i * 3 + 1); p[i * 3 + 2] = positions.get(i * 3 + 2);
            }
            VialumixNative.rtSectionUploadBatch(s, k, p, verts.toArray(new float[0][]), data.toArray(new float[0][]));
        }
        int live = VialumixNative.rtSectionCount();
        long now = System.currentTimeMillis();
        if (now - lastLog > 5000 && (uploadedSinceLog > 0 || lastLog == 0)) {
            lastLog = now;
            LOGGER.info("Vialumix RT sections: {} live, {} uploaded since last report, {} queued for meshing, {} ready to upload.",
                    live, uploadedSinceLog, QUEUE.size(), RESULTS.size());
            uploadedSinceLog = 0;
        }
        return live;
    }

    /** Returns the slot used afterwards (or -1); queues an upload when the mesh is non-empty, a removal otherwise. */
    private static int stage(int slot, int kind, int x, int y, int z, float[] vertices, float[] meshData,
                             java.util.List<Integer> slots, java.util.List<Integer> kinds, java.util.List<Integer> positions,
                             java.util.List<float[]> verts, java.util.List<float[]> data) {
        if (vertices.length == 0) {
            if (slot >= 0) { VialumixNative.rtSectionRemove(slot); FREE_SLOTS.push(slot); }
            return -1;
        }
        if (slot < 0) {
            if (FREE_SLOTS.isEmpty()) return -1; // out of instance slots: skip distant geometry rather than fail
            slot = FREE_SLOTS.pop();
        }
        slots.add(slot); kinds.add(kind); positions.add(x); positions.add(y); positions.add(z);
        verts.add(vertices); data.add(meshData);
        return slot;
    }

    /** Rebuild everything (resource reload changed the atlas UVs). */
    public static void rebuildAll() {
        QUEUE.clear();
        ClientWorld world = currentWorld;
        if (world == null) return;
        for (long ck : COLUMNS) {
            int cx = (int) (ck >> 32), cz = (int) ck;
            WorldChunk chunk = world.getChunkManager().getWorldChunk(cx, cz);
            if (chunk != null) enqueueColumn(world, chunk, cx, cz);
        }
    }

    public static void reset() {
        QUEUE.clear();
        RESULTS.clear();
        for (Entry e : ENTRIES.values()) release(e);
        ENTRIES.clear();
        COLUMNS.clear();
        currentWorld = null;
        if (VialumixNative.isLoaded()) { try { VialumixNative.rtClearSections(); } catch (Throwable ignored) { } }
        FREE_SLOTS.clear();
        for (int i = MAX_SLOTS - 1; i >= 0; i--) FREE_SLOTS.push(i);
    }
}
