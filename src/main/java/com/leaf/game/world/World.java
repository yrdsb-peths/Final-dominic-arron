// --- FILE: src/main/java/com/leaf/game/world/World.java ---
package com.leaf.game.world;

import com.leaf.game.entity.Player;
import com.leaf.game.core.GameConfig;
import com.leaf.game.world.gen.WorldGen;
import com.leaf.game.render.ChunkMesher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class World {
    public static final int WIDTH  = 128;
    public static final int HEIGHT = 512;
    public static final int DEPTH  = 128;

    private final HashMap<Long, Chunk> chunks = new HashMap<>();
    private final Map<Long, Map<Integer, Block>> modifiedBlocks = new HashMap<>();

    private final Set<Long> activeLiquids = ConcurrentHashMap.newKeySet();
    private float fluidTimer = 0.0f;

    public World() {}

    public Map<Long, Map<Integer, Block>> getModifiedBlocksMap() { return modifiedBlocks; }
    private static long chunkKey(int cx, int cz) { return ((long) cx << 32) | (cz & 0xFFFFFFFFL); }

    public Chunk getChunk(int cx, int cz) { return chunks.get(chunkKey(cx, cz)); }
    public Chunk getOrCreateChunk(int cx, int cz) { return chunks.computeIfAbsent(chunkKey(cx, cz), k -> new Chunk(cx, cz)); }

    public Block getBlock(int wx, int wy, int wz) {
        if (wy < 0 || wy >= Chunk.HEIGHT) return Block.AIR;
        Chunk chunk = getChunk(Math.floorDiv(wx, Chunk.SIZE), Math.floorDiv(wz, Chunk.SIZE));
        if (chunk == null) return Block.AIR;
        return chunk.getBlock(Math.floorMod(wx, Chunk.SIZE), wy, Math.floorMod(wz, Chunk.SIZE));
    }

    public byte getMeta(int wx, int wy, int wz) {
        if (wy < 0 || wy >= Chunk.HEIGHT) return 0;
        Chunk chunk = getChunk(Math.floorDiv(wx, Chunk.SIZE), Math.floorDiv(wz, Chunk.SIZE));
        if (chunk == null) return 0;
        return chunk.getMeta(Math.floorMod(wx, Chunk.SIZE), wy, Math.floorMod(wz, Chunk.SIZE));
    }

    public void setBlock(int wx, int wy, int wz, Block b) {
        setBlockWithMeta(wx, wy, wz, b, (byte)0, true);
    }

    public void setBlockWithMeta(int wx, int wy, int wz, Block b, byte meta, boolean triggerUpdate) {
        if (wy < 0 || wy >= Chunk.HEIGHT) return;
        int cx = Math.floorDiv(wx, Chunk.SIZE), cz = Math.floorDiv(wz, Chunk.SIZE);
        Chunk chunk = getOrCreateChunk(cx, cz);
        int lx = Math.floorMod(wx, Chunk.SIZE), lz = Math.floorMod(wz, Chunk.SIZE);

        chunk.setBlock(lx, wy, lz, b);
        chunk.setMeta(lx, wy, lz, meta);
        chunk.dirty = true;

        int localIdx = (wy << 8) | (lz << 4) | lx;
        modifiedBlocks.computeIfAbsent(chunkKey(cx, cz), k -> new HashMap<>()).put(localIdx, b);

        if (triggerUpdate) {
            scheduleFluidUpdate(wx, wy, wz);
            scheduleFluidUpdate(wx + 1, wy, wz); scheduleFluidUpdate(wx - 1, wy, wz);
            scheduleFluidUpdate(wx, wy + 1, wz); scheduleFluidUpdate(wx, wy - 1, wz);
            scheduleFluidUpdate(wx, wy, wz + 1); scheduleFluidUpdate(wx, wy, wz - 1);
        }
    }

    public java.util.Collection<Chunk> getAllChunks() { return chunks.values(); }
    public void clearAllChunks() { chunks.clear(); activeLiquids.clear(); }

    public void updateChunks(World world, WorldGen gen, Player player) {
        int RENDER_DISTANCE = GameConfig.renderDistance;
        int playerCX = Math.floorDiv((int) player.position.x, Chunk.SIZE);
        int playerCZ = Math.floorDiv((int) player.position.z, Chunk.SIZE);

        for (int dx = -RENDER_DISTANCE; dx <= RENDER_DISTANCE; dx++) {
            for (int dz = -RENDER_DISTANCE; dz <= RENDER_DISTANCE; dz++) {
                int cx = playerCX + dx;
                int cz = playerCZ + dz;

                if (world.getChunk(cx, cz) == null) {
                    Chunk chunk = world.getOrCreateChunk(cx, cz);
                    gen.generateChunk(chunk);

                    Map<Integer, Block> mods = modifiedBlocks.get(chunkKey(cx, cz));
                    if (mods != null) {
                        for (Map.Entry<Integer, Block> entry : mods.entrySet()) {
                            int idx = entry.getKey();
                            chunk.setBlock(idx & 15, (idx >> 8) & 1023, (idx >> 4) & 15, entry.getValue());
                        }
                    }

                    int worldX = cx * Chunk.SIZE;
                    int worldZ = cz * Chunk.SIZE;
                    for (int lx = 0; lx < Chunk.SIZE; lx++) {
                        for (int ly = 0; ly < Chunk.HEIGHT; ly++) {
                            for (int lz = 0; lz < Chunk.SIZE; lz++) {
                                if (chunk.getBlock(lx, ly, lz) == Block.WATER) {
                                    boolean exposed = false;
                                    if (lx == 0 || lx == Chunk.SIZE - 1 || lz == 0 || lz == Chunk.SIZE - 1) {
                                        exposed = true;
                                    } else if (chunk.getBlock(lx+1, ly, lz) == Block.AIR ||
                                            chunk.getBlock(lx-1, ly, lz) == Block.AIR ||
                                            chunk.getBlock(lx, ly, lz+1) == Block.AIR ||
                                            chunk.getBlock(lx, ly, lz-1) == Block.AIR ||
                                            (ly > 0 && chunk.getBlock(lx, ly-1, lz) == Block.AIR)) {
                                        exposed = true;
                                    }

                                    if (exposed) {
                                        world.scheduleFluidUpdate(worldX + lx, ly, worldZ + lz);
                                    }
                                }
                            }
                        }
                    }

                    Chunk nX = world.getChunk(cx + 1, cz); if (nX != null) nX.dirty = true;
                    Chunk pX = world.getChunk(cx - 1, cz); if (pX != null) pX.dirty = true;
                    Chunk nZ = world.getChunk(cx, cz + 1); if (nZ != null) nZ.dirty = true;
                    Chunk pZ = world.getChunk(cx, cz - 1); if (pZ != null) pZ.dirty = true;
                }
            }
        }
    }

    public void scheduleFluidUpdate(int wx, int wy, int wz) {
        if (wy >= 0 && wy < Chunk.HEIGHT) activeLiquids.add(packPos(wx, wy, wz));
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

    private long packPos(int x, int y, int z) {
        return ((long)(x & 0x3FFFFFF) << 38) | ((long)(z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }
    private int unpackX(long p) { return (int) (p >> 38); }
    private int unpackZ(long p) { return (int) ((p << 26) >> 38); }
    private int unpackY(long p) { return (int) (p & 0xFFF); }
}