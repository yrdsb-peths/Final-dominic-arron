// --- FILE: src/main/java/com/leaf/game/render/ChunkMesher.java ---
package com.leaf.game.render;

import com.leaf.game.world.Block;
import com.leaf.game.world.Chunk;
import com.leaf.game.world.World;

import java.util.Arrays;

public class ChunkMesher {

    private static final float AO_STRENGTH = 0.2f;

    // ── Primitive growable buffers ─────────────────────────────────────────────
    // These are reused across every buildChunkMeshes call (main thread only).
    // Eliminates millions of Float/Integer autobox allocations per second.
    private static final class GrowableFloats {
        float[] buf;
        int size;
        GrowableFloats(int cap) { buf = new float[cap]; }
        void add(float v) {
            if (size == buf.length) buf = Arrays.copyOf(buf, buf.length * 2);
            buf[size++] = v;
        }
        void clear() { size = 0; }
        int vertexCount() { return size / 10; } // 10 floats per vertex
    }
    private static final class GrowableInts {
        int[] buf;
        int size;
        GrowableInts(int cap) { buf = new int[cap]; }
        void add(int v) {
            if (size == buf.length) buf = Arrays.copyOf(buf, buf.length * 2);
            buf[size++] = v;
        }
        void clear() { size = 0; }
    }

    // Static reusable instances — safe because mesh building is single-threaded
    private static final GrowableFloats oVertsBuf = new GrowableFloats(65536);
    private static final GrowableInts   oIdxBuf   = new GrowableInts(32768);
    private static final GrowableFloats tVertsBuf = new GrowableFloats(16384);
    private static final GrowableInts   tIdxBuf   = new GrowableInts(8192);

    public static void buildChunkMeshes(World world, Chunk chunk) {
        oVertsBuf.clear(); oIdxBuf.clear();
        tVertsBuf.clear(); tIdxBuf.clear();

        int worldXStart = chunk.cx * Chunk.SIZE;
        int worldZStart = chunk.cz * Chunk.SIZE;
        // worldYStart offsets local y (0..HEIGHT-1) to absolute world coordinates.
        // For surface chunks (cy=0) this is 0; for deep abyss chunks it is negative.
        int worldYStart = chunk.cy * Chunk.HEIGHT;

        // ── 1. POPULATE LOCAL BLOCKS & META CACHE (Eliminates HashMap Lookups) ──
        Block[][][] blockCache = new Block[18][Chunk.HEIGHT + 2][18];
        byte[][][]  metaCache  = new byte[18][Chunk.HEIGHT + 2][18];

        // Fetch horizontal neighbour chunks (same cy)
        Chunk center = chunk;
        int cy_ = chunk.cy;  // use a local alias to distinguish from loop var
        Chunk nX   = world.getChunk(chunk.cx + 1, cy_, chunk.cz);
        Chunk pX   = world.getChunk(chunk.cx - 1, cy_, chunk.cz);
        Chunk nZ   = world.getChunk(chunk.cx,     cy_, chunk.cz + 1);
        Chunk pZ   = world.getChunk(chunk.cx,     cy_, chunk.cz - 1);
        Chunk nXnZ = world.getChunk(chunk.cx + 1, cy_, chunk.cz + 1);
        Chunk pXpZ = world.getChunk(chunk.cx - 1, cy_, chunk.cz - 1);
        Chunk nXpZ = world.getChunk(chunk.cx + 1, cy_, chunk.cz - 1);
        Chunk pXnZ = world.getChunk(chunk.cx - 1, cy_, chunk.cz + 1);
        // Vertical neighbours — needed for y-boundary face culling and AO
        Chunk chunkAbove = world.getChunk(chunk.cx, cy_ + 1, chunk.cz);
        Chunk chunkBelow = world.getChunk(chunk.cx, cy_ - 1, chunk.cz);

        for (int x = -1; x <= Chunk.SIZE; x++) {
            for (int z = -1; z <= Chunk.SIZE; z++) {
                Chunk target = getTargetChunk(x, z, center, nX, pX, nZ, pZ, nXnZ, pXpZ, nXpZ, pXnZ);
                int lx = (x < 0) ? 15 : (x >= 16 ? 0 : x);
                int lz = (z < 0) ? 15 : (z >= 16 ? 0 : z);

                for (int y = -1; y <= Chunk.HEIGHT; y++) {
                    Block b = Block.AIR;
                    byte  m = 0;

                    if (y < 0) {
                        // Below this chunk — look at the chunk directly underneath.
                        // Only valid for the main (non-padded) columns; corner/lateral
                        // neighbour overhangs stay AIR (they render in their own chunk).
                        if (target == center && chunkBelow != null) {
                            b = chunkBelow.getBlock(lx, Chunk.HEIGHT - 1, lz);
                            m = chunkBelow.getMeta(lx, Chunk.HEIGHT - 1, lz);
                        }
                    } else if (y >= Chunk.HEIGHT) {
                        // Above this chunk — look at the chunk directly above.
                        if (target == center && chunkAbove != null) {
                            b = chunkAbove.getBlock(lx, 0, lz);
                            m = chunkAbove.getMeta(lx, 0, lz);
                        }
                    } else if (target != null) {
                        b = target.getBlock(lx, y, lz);
                        m = target.getMeta(lx, y, lz);
                    }

                    blockCache[x + 1][y + 1][z + 1] = b;
                    metaCache[x + 1][y + 1][z + 1]  = m;
                }
            }
        }

        // ── 2. MESH GENERATION LOOP ──
        // Clamp Y to the tight bounds tracked in Chunk.setBlock. For typical
        // surface chunks (terrain up to ~Y=150 out of 512) this eliminates
        // ~70 % of inner-loop iterations. Deep solid abyss chunks still run
        // the full range but those are infrequent. +/- 1 padding ensures we
        // don't miss faces right at the boundary.
        int yMin = Math.max(0,              chunk.minBlockY - 1);
        int yMax = Math.min(Chunk.HEIGHT-1, chunk.maxBlockY + 1);
        // If the chunk is completely empty (minBlockY > maxBlockY) skip entirely
        if (chunk.minBlockY > chunk.maxBlockY) {
            if (chunk.opaqueMesh      != null) { chunk.opaqueMesh.cleanup();      chunk.opaqueMesh = null; }
            if (chunk.transparentMesh != null) { chunk.transparentMesh.cleanup(); chunk.transparentMesh = null; }
            chunk.dirty = false;
            return;
        }
        for (int x = 0; x < Chunk.SIZE;  x++) {
            for (int y = yMin; y <= yMax; y++) {
                for (int z = 0; z < Chunk.SIZE;  z++) {

                    int cx = x + 1; // offset for the padded caches
                    int cy = y + 1;
                    int cz = z + 1;

                    Block block = blockCache[cx][cy][cz];
                    if (block == Block.AIR) continue;

                    int wx = worldXStart + x;
                    int wz = worldZStart + z;
                    // wy is the absolute world Y of this block's base.
                    // For deep abyss chunks (cy < 0) this is negative.
                    float wy = worldYStart + y;

                    boolean isTrans = !block.isOpaque();
                    GrowableFloats verts   = isTrans ? tVertsBuf : oVertsBuf;
                    GrowableInts   indices = isTrans ? tIdxBuf   : oIdxBuf;

                    // Compute sloped liquid bounds
                    float h00 = block.isLiquid() ? getLiquidCornerHeight(blockCache, metaCache, x, y, z, 0, 0) : 1f;
                    float h10 = block.isLiquid() ? getLiquidCornerHeight(blockCache, metaCache, x, y, z, 1, 0) : 1f;
                    float h11 = block.isLiquid() ? getLiquidCornerHeight(blockCache, metaCache, x, y, z, 1, 1) : 1f;
                    float h01 = block.isLiquid() ? getLiquidCornerHeight(blockCache, metaCache, x, y, z, 0, 1) : 1f;

                    float[] top   = { wx, wy+h00, wz,  wx+1, wy+h10, wz,  wx+1, wy+h11, wz+1,  wx, wy+h01, wz+1 };
                    float[] bot   = { wx, wy, wz+1,  wx+1, wy, wz+1,  wx+1, wy, wz,  wx, wy, wz };
                    float[] front = { wx, wy, wz+1,  wx+1, wy, wz+1,  wx+1, wy+h11, wz+1,  wx, wy+h01, wz+1 };
                    float[] back  = { wx+1, wy, wz,  wx, wy, wz,  wx, wy+h00, wz,  wx+1, wy+h10, wz };
                    float[] right = { wx+1, wy, wz+1,  wx+1, wy, wz,  wx+1, wy+h10, wz,  wx+1, wy+h11, wz+1 };
                    float[] left  = { wx, wy, wz,  wx, wy, wz+1,  wx, wy+h01, wz+1,  wx, wy+h00, wz };

                    if (shouldDrawFace(block, blockCache[cx][cy + 1][cz])) {
                        float[] ao = {
                                computeAO(blockCache, cx-1, cy+1, cz, cx, cy+1, cz-1, cx-1, cy+1, cz-1),
                                computeAO(blockCache, cx+1, cy+1, cz, cx, cy+1, cz-1, cx+1, cy+1, cz-1),
                                computeAO(blockCache, cx+1, cy+1, cz, cx, cy+1, cz+1, cx+1, cy+1, cz+1),
                                computeAO(blockCache, cx-1, cy+1, cz, cx, cy+1, cz+1, cx-1, cy+1, cz+1)
                        };
                        addFace(verts, indices, top, block, ao,  0f, 1f, 0f);
                    }
                    if (shouldDrawFace(block, blockCache[cx][cy - 1][cz])) {
                        float[] ao = {
                                computeAO(blockCache, cx-1, cy-1, cz, cx, cy-1, cz+1, cx-1, cy-1, cz+1),
                                computeAO(blockCache, cx+1, cy-1, cz, cx, cy-1, cz+1, cx+1, cy-1, cz+1),
                                computeAO(blockCache, cx+1, cy-1, cz, cx, cy-1, cz-1, cx+1, cy-1, cz-1),
                                computeAO(blockCache, cx-1, cy-1, cz, cx, cy-1, cz-1, cx-1, cy-1, cz-1)
                        };
                        addFace(verts, indices, bot, block, ao,  0f, -1f, 0f);
                    }
                    if (shouldDrawFace(block, blockCache[cx][cy][cz + 1])) {
                        float[] ao = {
                                computeAO(blockCache, cx-1, cy, cz+1, cx, cy-1, cz+1, cx-1, cy-1, cz+1),
                                computeAO(blockCache, cx+1, cy, cz+1, cx, cy-1, cz+1, cx+1, cy-1, cz+1),
                                computeAO(blockCache, cx+1, cy, cz+1, cx, cy+1, cz+1, cx+1, cy+1, cz+1),
                                computeAO(blockCache, cx-1, cy, cz+1, cx, cy+1, cz+1, cx-1, cy+1, cz+1)
                        };
                        addFace(verts, indices, front, block, ao,  0f, 0f, 1f);
                    }
                    if (shouldDrawFace(block, blockCache[cx][cy][cz - 1])) {
                        float[] ao = {
                                computeAO(blockCache, cx+1, cy, cz-1, cx, cy-1, cz-1, cx+1, cy-1, cz-1),
                                computeAO(blockCache, cx-1, cy, cz-1, cx, cy-1, cz-1, cx-1, cy-1, cz-1),
                                computeAO(blockCache, cx-1, cy, cz-1, cx, cy+1, cz-1, cx-1, cy+1, cz-1),
                                computeAO(blockCache, cx+1, cy, cz-1, cx, cy+1, cz-1, cx+1, cy+1, cz-1)
                        };
                        addFace(verts, indices, back, block, ao,  0f, 0f, -1f);
                    }
                    if (shouldDrawFace(block, blockCache[cx + 1][cy][cz])) {
                        float[] ao = {
                                computeAO(blockCache, cx+1, cy-1, cz, cx+1, cy, cz+1, cx+1, cy-1, cz+1),
                                computeAO(blockCache, cx+1, cy-1, cz, cx+1, cy, cz-1, cx+1, cy-1, cz-1),
                                computeAO(blockCache, cx+1, cy+1, cz, cx+1, cy, cz-1, cx+1, cy+1, cz-1),
                                computeAO(blockCache, cx+1, cy+1, cz, cx+1, cy, cz+1, cx+1, cy+1, cz+1)
                        };
                        addFace(verts, indices, right, block, ao,  1f, 0f, 0f);
                    }
                    if (shouldDrawFace(block, blockCache[cx - 1][cy][cz])) {
                        float[] ao = {
                                computeAO(blockCache, cx-1, cy-1, cz, cx-1, cy, cz-1, cx-1, cy-1, cz-1),
                                computeAO(blockCache, cx-1, cy-1, cz, cx-1, cy, cz+1, cx-1, cy-1, cz+1),
                                computeAO(blockCache, cx-1, cy+1, cz, cx-1, cy, cz+1, cx-1, cy+1, cz+1),
                                computeAO(blockCache, cx-1, cy+1, cz, cx-1, cy, cz-1, cx-1, cy+1, cz-1)
                        };
                        addFace(verts, indices, left, block, ao,  -1f, 0f, 0f);
                    }
                }
            }
        }

        if (chunk.opaqueMesh != null) chunk.opaqueMesh.cleanup();
        if (chunk.transparentMesh != null) chunk.transparentMesh.cleanup();
        chunk.opaqueMesh = buildMesh(oVertsBuf, oIdxBuf);
        chunk.transparentMesh = buildMesh(tVertsBuf, tIdxBuf);
        chunk.dirty = false;

        // Neighbor mesh update triggers (horizontal + vertical)
        if (!chunk.meshBuilt) {
            chunk.meshBuilt = true;
            int cy_m = chunk.cy;
            Chunk nX_chunk = world.getChunk(chunk.cx + 1, cy_m, chunk.cz); if (nX_chunk != null) nX_chunk.dirty = true;
            Chunk pX_chunk = world.getChunk(chunk.cx - 1, cy_m, chunk.cz); if (pX_chunk != null) pX_chunk.dirty = true;
            Chunk nZ_chunk = world.getChunk(chunk.cx, cy_m, chunk.cz + 1); if (nZ_chunk != null) nZ_chunk.dirty = true;
            Chunk pZ_chunk = world.getChunk(chunk.cx, cy_m, chunk.cz - 1); if (pZ_chunk != null) pZ_chunk.dirty = true;
            Chunk uY_chunk = world.getChunk(chunk.cx, cy_m + 1, chunk.cz); if (uY_chunk != null) uY_chunk.dirty = true;
            Chunk dY_chunk = world.getChunk(chunk.cx, cy_m - 1, chunk.cz); if (dY_chunk != null) dY_chunk.dirty = true;
        }
    }

    private static Chunk getTargetChunk(int x, int z, Chunk center, Chunk nX, Chunk pX, Chunk nZ, Chunk pZ, Chunk nXnZ, Chunk pXpZ, Chunk nXpZ, Chunk pXnZ) {
        int cxOffset = (x < 0) ? -1 : (x >= 16 ? 1 : 0);
        int czOffset = (z < 0) ? -1 : (z >= 16 ? 1 : 0);

        if (cxOffset == 0 && czOffset == 0) return center;
        if (cxOffset == 1 && czOffset == 0) return nX;
        if (cxOffset == -1 && czOffset == 0) return pX;
        if (cxOffset == 0 && czOffset == 1) return nZ;
        if (cxOffset == 0 && czOffset == -1) return pZ;
        if (cxOffset == 1 && czOffset == 1) return nXnZ;
        if (cxOffset == -1 && czOffset == -1) return pXpZ;
        if (cxOffset == 1 && czOffset == -1) return nXpZ;
        if (cxOffset == -1 && czOffset == 1) return pXnZ;
        return null;
    }

    private static boolean shouldDrawFace(Block current, Block neighbor) {
        if (neighbor == Block.AIR) return true;
        if (current == neighbor) return false;
        return !neighbor.isOpaque();
    }

    private static float getLiquidCornerHeight(Block[][][] cache, byte[][][] metaCache, int lx, int ly, int lz, int cx, int cz) {
        int cornerX = lx + cx + 1;
        int cornerZ = lz + cz + 1;
        int waterCount = 0;
        float totalHeight = 0f;

        for (int dx = -1; dx <= 0; dx++) {
            for (int dz = -1; dz <= 0; dz++) {
                int nx = cornerX + dx;
                int nz = cornerZ + dz;
                Block b = cache[nx][ly + 1][nz];
                if (b == Block.WATER) {
                    if (cache[nx][ly + 2][nz] == Block.WATER) return 1.0f;
                    byte meta = metaCache[nx][ly + 1][nz];
                    totalHeight += 0.88f - (meta * 0.11f);
                    waterCount++;
                }
            }
        }

        if (waterCount == 0) return 0.05f;
        float avg = totalHeight / waterCount;
        if (waterCount < 4) {
            avg *= 0.65f;
        }
        return Math.max(0.05f, avg);
    }

    private static float computeAO(Block[][][] cache, int s1x, int s1y, int s1z, int s2x, int s2y, int s2z, int cx, int cy, int cz) {
        boolean s1 = cache[s1x][s1y][s1z].isSolid();
        boolean s2 = cache[s2x][s2y][s2z].isSolid();
        boolean co = cache[cx][cy][cz].isSolid();
        if (s1 && s2) return 1.0f - 3 * AO_STRENGTH;
        return 1.0f - ((s1 ? 1 : 0) + (s2 ? 1 : 0) + (co ? 1 : 0)) * AO_STRENGTH;
    }

    private static Mesh buildMesh(GrowableFloats verts, GrowableInts indices) {
        if (verts.size == 0) return null;
        // Pass slices of the backing arrays — Mesh constructor copies what it needs
        return new Mesh(Arrays.copyOf(verts.buf, verts.size),
                        Arrays.copyOf(indices.buf, indices.size));
    }

    private static void addFace(GrowableFloats verts, GrowableInts indices,
                                 float[] faceVertices, Block block, float[] ao,
                                 float nx, float ny, float nz) {
        int baseIndex = verts.vertexCount();
        for (int i = 0; i < 4; i++) {
            verts.add(faceVertices[i * 3]);
            verts.add(faceVertices[i * 3 + 1]);
            verts.add(faceVertices[i * 3 + 2]);
            verts.add(block.r * ao[i]);
            verts.add(block.g * ao[i]);
            verts.add(block.b * ao[i]);
            verts.add(block.a);
            verts.add(nx); verts.add(ny); verts.add(nz);
        }
        if (ao[0] + ao[2] > ao[1] + ao[3]) {
            indices.add(baseIndex);     indices.add(baseIndex + 1); indices.add(baseIndex + 2);
            indices.add(baseIndex + 2); indices.add(baseIndex + 3); indices.add(baseIndex);
        } else {
            indices.add(baseIndex);     indices.add(baseIndex + 1); indices.add(baseIndex + 3);
            indices.add(baseIndex + 1); indices.add(baseIndex + 2); indices.add(baseIndex + 3);
        }
    }
}