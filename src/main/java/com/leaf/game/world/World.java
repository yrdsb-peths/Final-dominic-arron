// --- FILE: src/main/java/com/leaf/game/world/World.java ---
package com.leaf.game.world;

import com.leaf.game.render.Mesh;
import com.leaf.game.entity.Player;
import com.leaf.game.core.GameConfig;
import com.leaf.game.world.gen.WorldGen;

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

    // ── FLUID SIMULATION QUEUE ──
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

                    // Re-apply saved blocks
                    Map<Integer, Block> mods = modifiedBlocks.get(chunkKey(cx, cz));
                    if (mods != null) {
                        for (Map.Entry<Integer, Block> entry : mods.entrySet()) {
                            int idx = entry.getKey();
                            chunk.setBlock(idx & 15, (idx >> 8) & 1023, (idx >> 4) & 15, entry.getValue());
                        }
                    }

                    // ── NEW: AUTO-FLOW SPAWNED WATER ──
                    // Automatically schedules water to flow on chunk generation if it touches air
                    int worldX = cx * Chunk.SIZE;
                    int worldZ = cz * Chunk.SIZE;
                    for (int lx = 0; lx < Chunk.SIZE; lx++) {
                        for (int ly = 0; ly < Chunk.HEIGHT; ly++) {
                            for (int lz = 0; lz < Chunk.SIZE; lz++) {
                                if (chunk.getBlock(lx, ly, lz) == Block.WATER) {
                                    boolean exposed = false;
                                    // Touch check
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
                    // ──────────────────────────────────

                    Chunk nX = world.getChunk(cx + 1, cz); if (nX != null) nX.dirty = true;
                    Chunk pX = world.getChunk(cx - 1, cz); if (pX != null) pX.dirty = true;
                    Chunk nZ = world.getChunk(cx, cz + 1); if (nZ != null) nZ.dirty = true;
                    Chunk pZ = world.getChunk(cx, cz - 1); if (pZ != null) pZ.dirty = true;
                }
            }
        }
    }

    // ── FLUID SIMULATION ──────────────────────────────────────────────────────

    private long packPos(int x, int y, int z) {
        return ((long)(x & 0x3FFFFFF) << 38) | ((long)(z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }
    private int unpackX(long p) { return (int) (p >> 38); }
    private int unpackZ(long p) { return (int) ((p << 26) >> 38); }
    private int unpackY(long p) { return (int) (p & 0xFFF); }

    public void scheduleFluidUpdate(int wx, int wy, int wz) {
        if (wy >= 0 && wy < Chunk.HEIGHT) activeLiquids.add(packPos(wx, wy, wz));
    }

    public void tickLiquids(float deltaTime) {
        fluidTimer += deltaTime;
        if (fluidTimer < 0.40f) return; // Tick water ~6 times a second
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

        // 1. Drain Check (If flowing water loses its source, it dries up)
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

        // 2. Flow Downwards First
        Block below = getBlock(wx, wy - 1, wz);
        if (below == Block.AIR) {
            setBlockWithMeta(wx, wy - 1, wz, Block.WATER, (byte) 1, true);
            return; // If water drops, it doesn't spread horizontally
        }

        // 3. Flow Outwards
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
        // ── THE FIX: Prevent water from leaking into ungenerated chunks! ──
        int cx = Math.floorDiv(wx, Chunk.SIZE);
        int cz = Math.floorDiv(wz, Chunk.SIZE);
        if (getChunk(cx, cz) == null) return; // Stop if the chunk doesn't exist yet!

        Block b = getBlock(wx, wy, wz);
        if (b == Block.AIR || (b == Block.WATER && getMeta(wx, wy, wz) > nextMeta)) {
            setBlockWithMeta(wx, wy, wz, Block.WATER, nextMeta, true);
        }
    }

    // ── SMART FACE CULLING & SLOPED FLUID MESHING ───────────────────────────

    private boolean shouldDrawFace(Block current, Block neighbor) {
        if (neighbor == Block.AIR) return true;
        if (current == neighbor) return false;
        if (!neighbor.isOpaque()) return true;
        return false;
    }

    private float getLiquidCornerHeight(int wx, int wy, int wz, int cx, int cz) {
        int cornerX = wx + cx;
        int cornerZ = wz + cz;
        int waterCount = 0;
        float totalHeight = 0f;

        for (int dx = -1; dx <= 0; dx++) {
            for (int dz = -1; dz <= 0; dz++) {
                int nx = cornerX + dx;
                int nz = cornerZ + dz;
                Block b = getBlock(nx, wy, nz);
                if (b == Block.WATER) {
                    if (getBlock(nx, wy + 1, nz) == Block.WATER) return 1.0f; // Full block height if water is above
                    byte meta = getMeta(nx, wy, nz);
                    totalHeight += 0.88f - (meta * 0.11f); // CHANGED: Base height slightly lowered
                    waterCount++;
                }
            }
        }

        if (waterCount == 0) return 0.05f;

        float avg = totalHeight / waterCount;

        // NEW: If water borders air, heavily slope its edge down.
        // This removes the "blocky" look when water sits on top of an ocean!
        if (waterCount < 4) {
            avg *= 0.65f;
        }

        return Math.max(0.05f, avg);
    }

    public void buildChunkMeshes(Chunk chunk) {
        List<Float> oVerts = new ArrayList<>(), tVerts = new ArrayList<>();
        List<Integer> oIdx = new ArrayList<>(), tIdx = new ArrayList<>();
        int worldXStart = chunk.cx * Chunk.SIZE, worldZStart = chunk.cz * Chunk.SIZE;

        for (int x = 0; x < Chunk.SIZE;  x++) {
            for (int y = 0; y < Chunk.HEIGHT; y++) {
                for (int z = 0; z < Chunk.SIZE;  z++) {
                    int wx = worldXStart + x, wz = worldZStart + z;
                    Block block = chunk.getBlock(x, y, z);
                    if (block == Block.AIR) continue;

                    boolean isTrans = !block.isOpaque();
                    List<Float> verts = isTrans ? tVerts : oVerts;
                    List<Integer> indices = isTrans ? tIdx : oIdx;

                    // Calculate customized heights for sloped blocks (water)
                    float h00 = block.isLiquid() ? getLiquidCornerHeight(wx, y, wz, 0, 0) : 1f;
                    float h10 = block.isLiquid() ? getLiquidCornerHeight(wx, y, wz, 1, 0) : 1f;
                    float h11 = block.isLiquid() ? getLiquidCornerHeight(wx, y, wz, 1, 1) : 1f;
                    float h01 = block.isLiquid() ? getLiquidCornerHeight(wx, y, wz, 0, 1) : 1f;

                    float[] top   = { wx, y+h00, wz,  wx+1, y+h10, wz,  wx+1, y+h11, wz+1,  wx, y+h01, wz+1 };
                    float[] bot   = { wx, y, wz+1,  wx+1, y, wz+1,  wx+1, y, wz,  wx, y, wz };
                    float[] front = { wx, y, wz+1,  wx+1, y, wz+1,  wx+1, y+h11, wz+1,  wx, y+h01, wz+1 };
                    float[] back  = { wx+1, y, wz,  wx, y, wz,  wx, y+h00, wz,  wx+1, y+h10, wz };
                    float[] right = { wx+1, y, wz+1,  wx+1, y, wz,  wx+1, y+h10, wz,  wx+1, y+h11, wz+1 };
                    float[] left  = { wx, y, wz,  wx, y, wz+1,  wx, y+h01, wz+1,  wx, y+h00, wz };

                    if (shouldDrawFace(block, getBlock(wx, y + 1, wz))) {
                        float[] ao = { computeAO(wx-1,y+1,wz, wx,y+1,wz-1, wx-1,y+1,wz-1), computeAO(wx+1,y+1,wz, wx,y+1,wz-1, wx+1,y+1,wz-1), computeAO(wx+1,y+1,wz, wx,y+1,wz+1, wx+1,y+1,wz+1), computeAO(wx-1,y+1,wz, wx,y+1,wz+1, wx-1,y+1,wz+1) };
                        addFace(verts, indices, top, block, ao,  0f, 1f, 0f);
                    }
                    if (shouldDrawFace(block, getBlock(wx, y - 1, wz))) {
                        float[] ao = { computeAO(wx-1,y-1,wz, wx,y-1,wz+1, wx-1,y-1,wz+1), computeAO(wx+1,y-1,wz, wx,y-1,wz+1, wx+1,y-1,wz+1), computeAO(wx+1,y-1,wz, wx,y-1,wz-1, wx+1,y-1,wz-1), computeAO(wx-1,y-1,wz, wx,y-1,wz-1, wx-1,y-1,wz-1) };
                        addFace(verts, indices, bot, block, ao,  0f, -1f, 0f);
                    }
                    if (shouldDrawFace(block, getBlock(wx, y, wz + 1))) {
                        float[] ao = { computeAO(wx-1,y,wz+1, wx,y-1,wz+1, wx-1,y-1,wz+1), computeAO(wx+1,y,wz+1, wx,y-1,wz+1, wx+1,y-1,wz+1), computeAO(wx+1,y,wz+1, wx,y+1,wz+1, wx+1,y+1,wz+1), computeAO(wx-1,y,wz+1, wx,y+1,wz+1, wx-1,y+1,wz+1) };
                        addFace(verts, indices, front, block, ao,  0f, 0f, 1f);
                    }
                    if (shouldDrawFace(block, getBlock(wx, y, wz - 1))) {
                        float[] ao = { computeAO(wx+1,y,wz-1, wx,y-1,wz-1, wx+1,y-1,wz-1), computeAO(wx-1,y,wz-1, wx,y-1,wz-1, wx-1,y-1,wz-1), computeAO(wx-1,y,wz-1, wx,y+1,wz-1, wx-1,y+1,wz-1), computeAO(wx+1,y,wz-1, wx,y+1,wz-1, wx+1,y+1,wz-1) };
                        addFace(verts, indices, back, block, ao,  0f, 0f, -1f);
                    }
                    if (shouldDrawFace(block, getBlock(wx + 1, y, wz))) {
                        float[] ao = { computeAO(wx+1,y-1,wz, wx+1,y,wz+1, wx+1,y-1,wz+1), computeAO(wx+1,y-1,wz, wx+1,y,wz-1, wx+1,y-1,wz-1), computeAO(wx+1,y+1,wz, wx+1,y,wz-1, wx+1,y+1,wz-1), computeAO(wx+1,y+1,wz, wx+1,y,wz+1, wx+1,y+1,wz+1) };
                        addFace(verts, indices, right, block, ao,  1f, 0f, 0f);
                    }
                    if (shouldDrawFace(block, getBlock(wx - 1, y, wz))) {
                        float[] ao = { computeAO(wx-1,y-1,wz, wx-1,y,wz-1, wx-1,y-1,wz-1), computeAO(wx-1,y-1,wz, wx-1,y,wz+1, wx-1,y-1,wz+1), computeAO(wx-1,y+1,wz, wx-1,y,wz+1, wx-1,y+1,wz+1), computeAO(wx-1,y+1,wz, wx-1,y,wz-1, wx-1,y+1,wz-1) };
                        addFace(verts, indices, left, block, ao,  -1f, 0f, 0f);
                    }
                }
            }
        }

        if (chunk.opaqueMesh != null) chunk.opaqueMesh.cleanup();
        if (chunk.transparentMesh != null) chunk.transparentMesh.cleanup();
        chunk.opaqueMesh = buildMesh(oVerts, oIdx);
        chunk.transparentMesh = buildMesh(tVerts, tIdx);
        chunk.dirty = false;

        // NEW: First time compile notification to align neighbor chunk borders
        if (!chunk.meshBuilt) {
            chunk.meshBuilt = true;
            int cx = chunk.cx;
            int cz = chunk.cz;
            Chunk nX = getChunk(cx + 1, cz); if (nX != null) nX.dirty = true;
            Chunk pX = getChunk(cx - 1, cz); if (pX != null) pX.dirty = true;
            Chunk nZ = getChunk(cx, cz + 1); if (nZ != null) nZ.dirty = true;
            Chunk pZ = getChunk(cx, cz - 1); if (pZ != null) pZ.dirty = true;
        }
    }

    private Mesh buildMesh(List<Float> verts, List<Integer> indices) {
        if (verts.isEmpty()) return null;
        float[] vArr = new float[verts.size()]; for (int i = 0; i < verts.size(); i++) vArr[i] = verts.get(i);
        int[] iArr = new int[indices.size()]; for (int i = 0; i < indices.size(); i++) iArr[i] = indices.get(i);
        return new Mesh(vArr, iArr);
    }

    private void addFace(List<Float> verts, List<Integer> indices, float[] faceVertices, Block block, float[] ao, float nx, float ny, float nz) {
        int baseIndex = verts.size() / 10;
        for (int i = 0; i < 4; i++) {
            verts.add(faceVertices[i * 3]); verts.add(faceVertices[i * 3 + 1]); verts.add(faceVertices[i * 3 + 2]);
            verts.add(block.r * ao[i]); verts.add(block.g * ao[i]); verts.add(block.b * ao[i]); verts.add(block.a);
            verts.add(nx); verts.add(ny); verts.add(nz);
        }
        if (ao[0] + ao[2] > ao[1] + ao[3]) {
            indices.add(baseIndex); indices.add(baseIndex + 1); indices.add(baseIndex + 2); indices.add(baseIndex + 2); indices.add(baseIndex + 3); indices.add(baseIndex);
        } else {
            indices.add(baseIndex); indices.add(baseIndex + 1); indices.add(baseIndex + 3); indices.add(baseIndex + 1); indices.add(baseIndex + 2); indices.add(baseIndex + 3);
        }
    }

    private static final float AO_STRENGTH = 0.2f;
    private float computeAO(int s1x, int s1y, int s1z, int s2x, int s2y, int s2z, int cx, int cy, int cz) {
        boolean s1 = getBlock(s1x, s1y, s1z).isSolid(), s2 = getBlock(s2x, s2y, s2z).isSolid(), co = getBlock( cx,  cy,  cz).isSolid();
        if (s1 && s2) return 1.0f - 3 * AO_STRENGTH;
        return 1.0f - ((s1 ? 1 : 0) + (s2 ? 1 : 0) + (co ? 1 : 0)) * AO_STRENGTH;
    }
}