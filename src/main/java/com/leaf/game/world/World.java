// --- FILE: src/main/java/com/leaf/game/world/World.java ---
package com.leaf.game.world;

import com.leaf.game.entity.Player;
import com.leaf.game.core.GameConfig;
import com.leaf.game.world.gen.WorldGen;
import com.leaf.game.world.gen.terrain.AbyssConfig;
import com.leaf.game.render.ChunkMesher;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class World {
    public static final int WIDTH  = 128;
    public static final int HEIGHT = 512;
    public static final int DEPTH  = 128;

    private final ConcurrentHashMap<Long, Chunk> chunks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Map<Integer, Block>> modifiedBlocks = new ConcurrentHashMap<>();

    private final Set<Long> activeLiquids = ConcurrentHashMap.newKeySet();
    private float fluidTimer = 0.0f;

    // ── FIX 1: Active Queue Throttle ─────────────────────────────────────────
    // Caps simultaneous background generation tasks. At RD=12 there can be
    // 500+ eligible chunks per frame; without this limit, the thread-pool queue
    // balloons, the JVM heap saturates, and GC thrashing freezes the game.
    private static final int MAX_CONCURRENT_GENERATIONS = 12;
    private final AtomicInteger activeGenerations = new AtomicInteger(0);

    public World() {}

    public Map<Long, Map<Integer, Block>> getModifiedBlocksMap() { return modifiedBlocks; }

    // ── Chunk key encoding ────────────────────────────────────────────────────
    // 3D key: 21 bits each for cx, cy, cz — handles ±1M chunks per axis.
    private static long chunkKey(int cx, int cy, int cz) {
        return ((long)(cx & 0x1FFFFF) << 42)
                | ((long)(cy & 0x1FFFFF) << 21)
                |  (long)(cz & 0x1FFFFF);
    }
    // 2D surface key kept for modifiedBlocks (player edits are surface-only)
    private static long chunkKey2D(int cx, int cz) { return ((long) cx << 32) | (cz & 0xFFFFFFFFL); }

    // Background threads for heavy math
    private final ExecutorService chunkThreadPool =
            Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors() - 1));

    // Safe queue to hand chunks back to the main thread for OpenGL rendering
    public final ConcurrentLinkedQueue<Chunk> meshingQueue = new ConcurrentLinkedQueue<>();

    // ── 3D chunk access ───────────────────────────────────────────────────────
    public Chunk getChunk(int cx, int cy, int cz) { return chunks.get(chunkKey(cx, cy, cz)); }
    /** Backward-compatible overload — assumes surface chunk (cy=0). */
    public Chunk getChunk(int cx, int cz) { return getChunk(cx, 0, cz); }

    public Chunk getOrCreateChunk(int cx, int cy, int cz) {
        return chunks.computeIfAbsent(chunkKey(cx, cy, cz), k -> new Chunk(cx, cy, cz));
    }
    /** Backward-compatible overload — creates surface chunk (cy=0). */
    public Chunk getOrCreateChunk(int cx, int cz) { return getOrCreateChunk(cx, 0, cz); }

    // ── Block access (routes to the correct vertical chunk) ───────────────────
    public Block getBlock(int wx, int wy, int wz) {
        int cy = Math.floorDiv(wy, Chunk.HEIGHT);
        int ly = Math.floorMod(wy, Chunk.HEIGHT);
        Chunk chunk = getChunk(Math.floorDiv(wx, Chunk.SIZE), cy, Math.floorDiv(wz, Chunk.SIZE));
        if (chunk == null) return Block.AIR;
        return chunk.getBlock(Math.floorMod(wx, Chunk.SIZE), ly, Math.floorMod(wz, Chunk.SIZE));
    }

    public byte getMeta(int wx, int wy, int wz) {
        int cy = Math.floorDiv(wy, Chunk.HEIGHT);
        int ly = Math.floorMod(wy, Chunk.HEIGHT);
        Chunk chunk = getChunk(Math.floorDiv(wx, Chunk.SIZE), cy, Math.floorDiv(wz, Chunk.SIZE));
        if (chunk == null) return 0;
        return chunk.getMeta(Math.floorMod(wx, Chunk.SIZE), ly, Math.floorMod(wz, Chunk.SIZE));
    }

    public void setBlock(int wx, int wy, int wz, Block b) {
        setBlockWithMeta(wx, wy, wz, b, (byte)0, true);
    }

    public void setBlockWithMeta(int wx, int wy, int wz, Block b, byte meta, boolean triggerUpdate) {
        int cy = Math.floorDiv(wy, Chunk.HEIGHT);
        int ly = Math.floorMod(wy, Chunk.HEIGHT);
        // Safety guard: don't allocate chunks absurdly far from the surface
        if (cy < -32 || cy > 4) return;
        int cx = Math.floorDiv(wx, Chunk.SIZE), cz = Math.floorDiv(wz, Chunk.SIZE);
        Chunk chunk = getOrCreateChunk(cx, cy, cz);
        int lx = Math.floorMod(wx, Chunk.SIZE), lz = Math.floorMod(wz, Chunk.SIZE);

        chunk.setBlock(lx, ly, lz, b);
        chunk.setMeta(lx, ly, lz, meta);
        chunk.dirty = true;

        // Track player-placed block modifications (surface world only)
        if (cy == 0) {
            int localIdx = (ly << 8) | (lz << 4) | lx;
            modifiedBlocks.computeIfAbsent(chunkKey2D(cx, cz), k -> new HashMap<>()).put(localIdx, b);
        }

        if (triggerUpdate) {
            scheduleFluidUpdate(wx, wy, wz);
            scheduleFluidUpdate(wx + 1, wy, wz); scheduleFluidUpdate(wx - 1, wy, wz);
            scheduleFluidUpdate(wx, wy + 1, wz); scheduleFluidUpdate(wx, wy - 1, wz);
            scheduleFluidUpdate(wx, wy, wz + 1); scheduleFluidUpdate(wx, wy, wz - 1);
        }
    }

    public java.util.Collection<Chunk> getAllChunks() { return chunks.values(); }
    public void clearAllChunks() { chunks.clear(); activeLiquids.clear(); }

    /**
     * Shuts down the background chunk-generation thread pool.
     * Call from the main thread after the render loop exits so Java can exit cleanly.
     * Without this, non-daemon worker threads keep the JVM alive indefinitely.
     */
    public void shutdown() {
        chunkThreadPool.shutdownNow();
        try { chunkThreadPool.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS); }
        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    public void updateChunks(World world, WorldGen gen, Player player) {
        int RENDER_DISTANCE = GameConfig.renderDistance;
        int playerCX = Math.floorDiv((int) player.position.x, Chunk.SIZE);
        int playerCZ = Math.floorDiv((int) player.position.z, Chunk.SIZE);
        // Which vertical chunk-slab the player is currently in
        int playerCY = Math.floorDiv((int) player.position.y, Chunk.HEIGHT);

        // ── FIX 2: Smart Abyss Culling — precompute player's proximity to abyss ──
        // We only need deep chunks if the player is both horizontally near the
        // abyss shaft AND has descended far enough to actually see them.  When
        // the player is on the surface, the fragment shader's abyss fog hides
        // everything below y≈(playerY - 80) in impenetrable darkness, so there
        // is no visual benefit to generating cy<0 slabs hundreds of blocks away.
        float dxAbyss = player.position.x - AbyssConfig.centerX;
        float dzAbyss = player.position.z - AbyssConfig.centerZ;
        float horizDistToAbyss = (float) Math.sqrt(dxAbyss * dxAbyss + dzAbyss * dzAbyss);
        // Allow the full render-distance ring around the shaft entrance plus a
        // small buffer for near-edge columns.
        float abyssHorizThreshold = AbyssConfig.entranceRadius
                + RENDER_DISTANCE * Chunk.SIZE
                + 32f;
        boolean playerNearAbyssHoriz = horizDistToAbyss < abyssHorizThreshold;
        // Player must be meaningfully below the entrance before we start
        // generating deep slabs.  50 blocks below entrance ≈ clearly inside.
        boolean playerDescended = player.position.y < (AbyssConfig.entranceY - 50);

        // ── Load loop — sorted nearest-first ─────────────────────────────────
        // Collect all (dx,dz) offsets, sort by squared distance from player,
        // then submit generation requests closest-first.  This guarantees that
        // the limited MAX_CONCURRENT_GENERATIONS slots are always given to the
        // chunks the player can actually see rather than chunks on the far edge
        // of the render ring — eliminating the "empty terrain holes nearby while
        // distant chunks load" symptom.
        int totalDiameter = 2 * RENDER_DISTANCE + 1;
        List<int[]> columnOffsets = new ArrayList<>(totalDiameter * totalDiameter);
        for (int dx = -RENDER_DISTANCE; dx <= RENDER_DISTANCE; dx++) {
            for (int dz = -RENDER_DISTANCE; dz <= RENDER_DISTANCE; dz++) {
                columnOffsets.add(new int[]{dx, dz, dx * dx + dz * dz});
            }
        }
        columnOffsets.sort(Comparator.comparingInt(e -> e[2]));

        for (int[] col : columnOffsets) {
            int dx = col[0], dz = col[1];
            int cx = playerCX + dx;
            int cz = playerCZ + dz;

            // Always load the surface chunk (cy=0)
            loadChunkIfNeeded(world, gen, cx, 0, cz);

            // ── Smart deep-abyss loading ──────────────────────────────────────
            if (gen.isChunkInAbyssZone(cx, cz)
                    && playerNearAbyssHoriz
                    && playerDescended) {

                int deepestCY = Math.max(playerCY - 3, -5);
                for (int cy = -1; cy >= deepestCY; cy--) {
                    loadChunkIfNeeded(world, gen, cx, cy, cz);
                }
            }
        }

        // ── FIX 3: Dynamic Chunk Unloading ────────────────────────────────────
        // Evict fully-meshed chunks that have drifted outside the keep radius.
        // • Only evict MESHED chunks — GENERATING chunks are live on a background
        //   thread and must not be touched; BLOCKS_READY chunks are queued for
        //   meshing on this same frame and will be caught next sweep.
        // • modifiedBlocks is already up-to-date (every setBlock call writes to
        //   it in real time), so no extra save step is required here.
        // • Mesh cleanup calls glDelete* — safe because updateChunks runs on the
        //   main OpenGL thread.
        int keepRadius = RENDER_DISTANCE + 2;
        // Deep chunks need an extra vertical margin so fast descents don't pop.
        int keepRadiusY = keepRadius + 3;

        Iterator<Map.Entry<Long, Chunk>> iter = chunks.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<Long, Chunk> entry = iter.next();
            Chunk c = entry.getValue();

            // Never evict a chunk that is still being generated — the background
            // thread holds a reference and will write to it after this point.
            if (c.state == Chunk.ChunkState.GENERATING) continue;
            // Never evict chunks flagged as hand-built structures.
            if (c.noEvict) continue;

            // Check Manhattan distance per axis.
            boolean outsideX = Math.abs(c.cx - playerCX) > keepRadius;
            boolean outsideZ = Math.abs(c.cz - playerCZ) > keepRadius;
            boolean outsideY = Math.abs(c.cy - playerCY) > keepRadiusY;

            if (outsideX || outsideZ || outsideY) {
                // Release GPU memory for mesh buffers.
                c.cleanup();
                iter.remove();
            }
        }
    }

    /**
     * Generates and initialises a chunk at (cx, cy, cz) if it does not yet exist.
     *
     * <p>FIX 1 — Rate limiting: if {@value MAX_CONCURRENT_GENERATIONS} generation
     * tasks are already in-flight, the chunk is left in the EMPTY state and
     * will be retried on the next {@code updateChunks()} call. This prevents
     * the thread-pool queue from filling with hundreds of tasks in one frame,
     * which previously caused memory explosion and GC thrashing at high render
     * distances.
     */
    private void loadChunkIfNeeded(World world, WorldGen gen, int cx, int cy, int cz) {
        long key = chunkKey(cx, cy, cz);
        Chunk chunk = getOrCreateChunk(cx, cy, cz);

        // Ensure we only submit EMPTY chunks to the thread pool.
        synchronized (chunk) {
            if (chunk.state != Chunk.ChunkState.EMPTY) return;

            // ── FIX 1: Throttle check ────────────────────────────────────────
            // Check INSIDE the synchronized block so the state cannot be
            // changed to GENERATING by two callers simultaneously.
            if (activeGenerations.get() >= MAX_CONCURRENT_GENERATIONS) {
                // Chunk stays EMPTY — updateChunks will retry next frame.
                return;
            }

            chunk.state = Chunk.ChunkState.GENERATING;
            activeGenerations.incrementAndGet();
        }

        chunkThreadPool.submit(() -> {
            try {
                gen.generateChunk(chunk); // Heavy 3D math runs in background!

                /// Restore player-placed block edits (only tracked for cy=0)
                if (cy == 0) {
                    Map<Integer, Block> mods = modifiedBlocks.get(chunkKey2D(cx, cz));
                    if (mods != null) {
                        for (Map.Entry<Integer, Block> entry : mods.entrySet()) {
                            int idx = entry.getKey();
                            chunk.setBlock(idx & 15, (idx >> 8) & 1023, (idx >> 4) & 15, entry.getValue());
                        }
                    }
                }

                // Schedule fluid updates for exposed water (run for ALL cy <= 0 so deep waterfalls tick!)
                if (cy <= 0) {
                    int worldX = cx * Chunk.SIZE;
                    int worldZ = cz * Chunk.SIZE;
                    int worldYOffset = cy * Chunk.HEIGHT;
                    for (int lx = 0; lx < Chunk.SIZE; lx++) {
                        for (int ly = 0; ly < Chunk.HEIGHT; ly++) {
                            for (int lz = 0; lz < Chunk.SIZE; lz++) {
                                if (chunk.getBlock(lx, ly, lz) == Block.WATER) {
                                    // A water block is exposed if it is adjacent to AIR in any of the 6 directions
                                    boolean exposed = (lx == 0 || lx == Chunk.SIZE - 1
                                            || lz == 0 || lz == Chunk.SIZE - 1)
                                            || chunk.getBlock(lx+1, ly, lz) == Block.AIR
                                            || chunk.getBlock(lx-1, ly, lz) == Block.AIR
                                            || chunk.getBlock(lx, ly, lz+1) == Block.AIR
                                            || chunk.getBlock(lx, ly, lz-1) == Block.AIR
                                            || (ly > 0 && chunk.getBlock(lx, ly-1, lz) == Block.AIR)
                                            || (ly < Chunk.HEIGHT - 1 && chunk.getBlock(lx, ly+1, lz) == Block.AIR);
                                    if (exposed) {
                                        world.scheduleFluidUpdate(worldX + lx, worldYOffset + ly, worldZ + lz);
                                    }
                                }
                            }
                        }
                    }
                }

                chunk.state = Chunk.ChunkState.BLOCKS_READY;
                meshingQueue.add(chunk); // Hand back to main thread
            } finally {
                // Always decrement — even if generateChunk throws — so a
                // crashed task never permanently clogs the slot counter.
                activeGenerations.decrementAndGet();
            }
        });
    }

    public void scheduleFluidUpdate(int wx, int wy, int wz) {
        // Accept any Y in range [-16*512 .. 4*512] — the same safety bounds used
        // by setBlockWithMeta.  Negative Y is needed for deep abyss water.
        activeLiquids.add(packPos(wx, wy, wz));
    }

    public void tickLiquids(float deltaTime) {
        fluidTimer += deltaTime;
        if (fluidTimer < 0.40f) return;
        fluidTimer = 0;

        if (activeLiquids.isEmpty()) return;
        List<Long> currentQueue = new ArrayList<>(activeLiquids);
        activeLiquids.clear();

        for (long p : currentQueue) {
            int wx = unpackX(p), wy = unpackY(p), wz = unpackZ(p);
            if (getBlock(wx, wy, wz) == Block.WATER) processWaterFlow(wx, wy, wz);
        }
    }

    private void processWaterFlow(int wx, int wy, int wz) {
        byte meta = getMeta(wx, wy, wz);

        if (meta > 0) {
            boolean validSource = false;
            if (getBlock(wx, wy + 1, wz) == Block.WATER) validSource = true;
            else if (isValidSource(wx + 1, wy, wz, meta) || isValidSource(wx - 1, wy, wz, meta) ||
                    isValidSource(wx, wy, wz + 1, meta) || isValidSource(wx, wy, wz - 1, meta)) {
                validSource = true;
            }
            if (!validSource) {
                setBlockWithMeta(wx, wy, wz, Block.AIR, (byte)0, true);
                return;
            }
        }

        Block below = getBlock(wx, wy - 1, wz);
        if (below == Block.AIR) {
            setBlockWithMeta(wx, wy - 1, wz, Block.WATER, (byte) 1, true);
            return;
        }

        if (meta < 7 && below.isSolid()) {
            byte nextMeta = (byte) (meta + 1);
            tryFlowOut(wx + 1, wy, wz, nextMeta);
            tryFlowOut(wx - 1, wy, wz, nextMeta);
            tryFlowOut(wx, wy, wz + 1, nextMeta);
            tryFlowOut(wx, wy, wz - 1, nextMeta);
        }
    }

    private boolean isValidSource(int wx, int wy, int wz, byte currentMeta) {
        return getBlock(wx, wy, wz) == Block.WATER && getMeta(wx, wy, wz) < currentMeta;
    }

    private void tryFlowOut(int wx, int wy, int wz, byte nextMeta) {
        int cx = Math.floorDiv(wx, Chunk.SIZE);
        int cz = Math.floorDiv(wz, Chunk.SIZE);
        if (getChunk(cx, cz) == null) return;

        Block b = getBlock(wx, wy, wz);
        if (b == Block.AIR || (b == Block.WATER && getMeta(wx, wy, wz) > nextMeta)) {
            setBlockWithMeta(wx, wy, wz, Block.WATER, nextMeta, true);
        }
    }

    // ── DECOUPLED MESH TRIGGER (Redirects to specialized ChunkMesher) ──
    public void buildChunkMeshes(Chunk chunk) {
        ChunkMesher.buildChunkMeshes(this, chunk);
    }

    /**
     * Immediately rebuilds the mesh for the chunk containing world position
     * (wx, wy, wz) and marks it MESHED.  Also rebuilds any MESHED horizontal
     * neighbours whose boundary faces may have changed (e.g. the side of the
     * newly placed block that faces into the next chunk).
     *
     * <p>Must be called from the main OpenGL thread (same as buildChunkMeshes).
     * Use this after player-triggered block edits so the visual update is
     * instantaneous rather than waiting for the next dirty-flag scan.
     */
    public void rebuildChunkAt(int wx, int wy, int wz) {
        int cy = Math.floorDiv(wy, Chunk.HEIGHT);
        int cx = Math.floorDiv(wx, Chunk.SIZE);
        int cz = Math.floorDiv(wz, Chunk.SIZE);

        Chunk main = getChunk(cx, cy, cz);
        if (main != null && main.state == Chunk.ChunkState.MESHED) {
            buildChunkMeshes(main);
        }

        // Rebuild neighbours only if the edit was on a chunk border (within 1 block of edge)
        int lx = Math.floorMod(wx, Chunk.SIZE);
        int lz = Math.floorMod(wz, Chunk.SIZE);
        if (lx == 0)             { Chunk n = getChunk(cx - 1, cy, cz); if (n != null && n.state == Chunk.ChunkState.MESHED) buildChunkMeshes(n); }
        if (lx == Chunk.SIZE - 1) { Chunk n = getChunk(cx + 1, cy, cz); if (n != null && n.state == Chunk.ChunkState.MESHED) buildChunkMeshes(n); }
        if (lz == 0)             { Chunk n = getChunk(cx, cy, cz - 1); if (n != null && n.state == Chunk.ChunkState.MESHED) buildChunkMeshes(n); }
        if (lz == Chunk.SIZE - 1) { Chunk n = getChunk(cx, cy, cz + 1); if (n != null && n.state == Chunk.ChunkState.MESHED) buildChunkMeshes(n); }
    }

    // ── Position packing for the fluid scheduler ─────────────────────────────
    // 64-bit layout:
    //   bits 63..39 (25 bits) — X biased by 2^24  → range [-16 777 216, 16 777 215]
    //   bits 38..14 (25 bits) — Z biased by 2^24  → same range
    //   bits 13..0  (14 bits) — Y biased by 2^13  → range [−8 192, 8 191]
    //                                                (covers cy −16 … +16)
    //
    // Bias makes every field unsigned before packing, so no sign-extension
    // surprises on unpack. This replaces the original scheme that silently
    // dropped negative-Y (abyss) and mis-handled negative-X/Z coordinates.
    private static final int X_BIAS = 1 << 24;  // half of 25-bit unsigned range
    private static final int Z_BIAS = 1 << 24;
    private static final int Y_BIAS = 1 << 13;  // half of 14-bit unsigned range

    private long packPos(int x, int y, int z) {
        long px = (long)((x + X_BIAS) & 0x1FFFFFF);  // 25 bits
        long pz = (long)((z + Z_BIAS) & 0x1FFFFFF);  // 25 bits
        long py = (long)((y + Y_BIAS) & 0x3FFF);     // 14 bits
        return (px << 39) | (pz << 14) | py;
    }
    private int unpackX(long p) { return (int)((p >>> 39) & 0x1FFFFFF) - X_BIAS; }
    private int unpackZ(long p) { return (int)((p >>> 14) & 0x1FFFFFF) - Z_BIAS; }
    private int unpackY(long p) { return (int)(p & 0x3FFF)  - Y_BIAS; }

    // ─────────────────────────────────────────────────────────────────────────
    //  GROUND SMASH — Impact Crater
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Carves a spherical crater centred at (wx, wy, wz) with the given radius.
     *
     * Algorithm:
     *   • Inner sphere (dist < radius - 1): blocks removed → AIR.
     *   • Edge shell  (radius - 1 ≤ dist ≤ radius): solid blocks → GRAVEL.
     *   • All affected chunks are rebuilt once at the end (not per-block).
     *
     * The method is called on the main OpenGL thread (same as buildChunkMeshes),
     * so mesh rebuilds are safe.  Must NOT be called from a background thread.
     *
     * Window.java spawns a burst of DroppedItem ejecta after calling this to
     * give the visual impression of debris flying outward.
     *
     * @param wx  world X of smash impact
     * @param wy  world Y of smash impact
     * @param wz  world Z of smash impact
     * @param radius  sphere radius in blocks (GameConfig.smashCraterRadius)
     */
    /**
     * Preloads a tube of chunks surrounding the full ballistic trajectory.
     *
     * Called at CHARGE START (not fire) so the background thread pool has the
     * entire charge window (~2.5 s) to generate terrain before the player
     * arrives. Uses MAX-power velocity so the tube covers the longest possible
     * path; shorter actual shots land inside the already-loaded zone.
     *
     * The tube radius is sideRadius chunk-columns wide on each side of the path.
     * This covers what the player sees when they rotate during flight. A radius
     * of 4 gives an 8-chunk-wide corridor (128 blocks) — enough for any look
     * direction while tumbling.
     *
     * loadChunkIfNeeded is idempotent (skips already-generating/meshed chunks)
     * so calling this every charge frame is safe but wasteful; Window calls it
     * once on the leading edge of charging instead.
     *
     * @param sideRadius  chunk columns loaded on each side of path centre
     */
    public void preloadChunksAroundPath(float ox, float oy, float oz,
                                        float vx, float vy, float vz,
                                        com.leaf.game.world.gen.WorldGen gen,
                                        int sideRadius) {
        float gravity = com.leaf.game.core.GameConfig.GRAVITY;
        float px = ox, py = oy, pz = oz;
        float dvx = vx, dvy = vy, dvz = vz;
        float simDt = 0.10f;

        // Collect unique chunk columns to avoid redundant loadChunkIfNeeded calls
        java.util.Set<Long> queued = new java.util.HashSet<>();

        for (int step = 0; step < 250; step++) {
            dvy -= gravity * simDt;
            px  += dvx * simDt;
            py  += dvy * simDt;
            pz  += dvz * simDt;
            if (py < -64f) break;

            int cx = Math.floorDiv((int)px, Chunk.SIZE);
            int cz = Math.floorDiv((int)pz, Chunk.SIZE);

            // Load a square sideRadius×sideRadius around each trajectory column
            for (int dx = -sideRadius; dx <= sideRadius; dx++) {
                for (int dz = -sideRadius; dz <= sideRadius; dz++) {
                    int nx = cx + dx, nz = cz + dz;
                    long key = ((long)(nx + 32768) << 32) | (long)(nz + 32768);
                    if (queued.add(key)) {
                        loadChunkIfNeeded(this, gen, nx, 0, nz);
                    }
                }
            }
        }
    }

    /**
     * Count how many chunk columns along a path are fully meshed.
     * Window.java uses this to show a readiness percentage during charging.
     * Returns a float 0..1 (1 = all path chunks ready).
     */
    public float pathReadinessFraction(float ox, float oy, float oz,
                                       float vx, float vy, float vz) {
        float gravity = com.leaf.game.core.GameConfig.GRAVITY;
        float px = ox, py = oy, pz = oz;
        float dvx = vx, dvy = vy, dvz = vz;
        float simDt = 0.15f;
        int total = 0, ready = 0;
        int prevCX = Integer.MIN_VALUE, prevCZ = Integer.MIN_VALUE;

        for (int step = 0; step < 200; step++) {
            dvy -= gravity * simDt;
            px  += dvx * simDt;
            py  += dvy * simDt;
            pz  += dvz * simDt;
            if (py < -64f) break;

            int cx = Math.floorDiv((int)px, Chunk.SIZE);
            int cz = Math.floorDiv((int)pz, Chunk.SIZE);
            if (cx == prevCX && cz == prevCZ) continue;
            prevCX = cx; prevCZ = cz;
            total++;
            Chunk c = getChunk(cx, 0, cz);
            // BLOCKS_READY means generation is done, just waiting for meshing.
            // It counts as "ready" since it only needs ~1ms of CPU on next drain.
            if (c != null && (c.state == Chunk.ChunkState.MESHED
                    || c.state == Chunk.ChunkState.BLOCKS_READY)) ready++;
        }
        return (total == 0) ? 1f : (float) ready / total;
    }

    // Legacy thin-path version kept for compatibility (used by smash/network sync)
    public void preloadChunksAlongPath(float ox, float oy, float oz,
                                       float vx, float vy, float vz,
                                       com.leaf.game.world.gen.WorldGen gen) {
        preloadChunksAroundPath(ox, oy, oz, vx, vy, vz, gen, 0);
    }

    public void createImpactCrater(int wx, int wy, int wz, int radius) {
        Set<Chunk> dirtyChunks = new java.util.HashSet<>();
        float rSq      = (float)(radius * radius);
        float edgeInner = (radius - 1f);

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    float distSq = dx*dx + dy*dy + dz*dz;
                    if (distSq > rSq) continue;

                    int bx = wx + dx, by = wy + dy, bz = wz + dz;
                    Block existing = getBlock(bx, by, bz);

                    // Only modify solid non-bedrock blocks
                    if (!existing.isSolid()) continue;

                    float dist = (float)Math.sqrt(distSq);
                    Block replacement = (dist >= edgeInner) ? Block.GRAVEL : Block.AIR;

                    // setBlock without triggerUpdate to avoid scheduling thousands
                    // of fluid updates; we'll handle that after the loop.
                    setBlock(bx, by, bz, replacement);

                    // Track the chunk for a single mesh rebuild
                    int cx = Math.floorDiv(bx, Chunk.SIZE);
                    int cy = Math.floorDiv(by, Chunk.HEIGHT);
                    int cz = Math.floorDiv(bz, Chunk.SIZE);
                    Chunk c = getChunk(cx, cy, cz);
                    if (c != null) dirtyChunks.add(c);
                }
            }
        }

        // Rebuild all affected chunk meshes exactly once
        for (Chunk c : dirtyChunks) {
            buildChunkMeshes(c);
        }

        // Trigger fluid updates on the crater rim so exposed water can flow
        for (int dx = -(radius+1); dx <= radius+1; dx++) {
            for (int dz = -(radius+1); dz <= radius+1; dz++) {
                scheduleFluidUpdate(wx + dx, wy, wz + dz);
                scheduleFluidUpdate(wx + dx, wy + radius, wz + dz);
            }
        }
    }
}