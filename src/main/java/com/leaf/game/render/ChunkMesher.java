// --- FILE: src/main/java/com/leaf/game/render/ChunkMesher.java ---
package com.leaf.game.render;

import com.leaf.game.world.Block;
import com.leaf.game.world.Chunk;
import com.leaf.game.world.World;

import java.util.ArrayList;
import java.util.List;

public class ChunkMesher {

    private static final float AO_STRENGTH = 0.2f;

    public static void buildChunkMeshes(World world, Chunk chunk) {
        List<Float> oVerts = new ArrayList<>(), tVerts = new ArrayList<>();
        List<Integer> oIdx = new ArrayList<>(), tIdx = new ArrayList<>();

        int worldXStart = chunk.cx * Chunk.SIZE;
        int worldZStart = chunk.cz * Chunk.SIZE;

        // ── 1. POPULATE LOCAL BLOCKS & META CACHE (Eliminates HashMap Lookups) ──
        Block[][][] blockCache = new Block[18][Chunk.HEIGHT + 2][18];
        byte[][][]  metaCache  = new byte[18][Chunk.HEIGHT + 2][18];

        // Fetch neighbor chunks once to avoid HashMap queries inside the coordinate loops
        Chunk center = chunk;
        Chunk nX = world.getChunk(chunk.cx + 1, chunk.cz);
        Chunk pX = world.getChunk(chunk.cx - 1, chunk.cz);
        Chunk nZ = world.getChunk(chunk.cx, chunk.cz + 1);
        Chunk pZ = world.getChunk(chunk.cx, chunk.cz - 1);
        Chunk nXnZ = world.getChunk(chunk.cx + 1, chunk.cz + 1);
        Chunk pXpZ = world.getChunk(chunk.cx - 1, chunk.cz - 1);
        Chunk nXpZ = world.getChunk(chunk.cx + 1, chunk.cz - 1);
        Chunk pXnZ = world.getChunk(chunk.cx - 1, chunk.cz + 1);

        for (int x = -1; x <= Chunk.SIZE; x++) {
            for (int z = -1; z <= Chunk.SIZE; z++) {
                Chunk target = getTargetChunk(x, z, center, nX, pX, nZ, pZ, nXnZ, pXpZ, nXpZ, pXnZ);
                int lx = (x < 0) ? 15 : (x >= 16 ? 0 : x);
                int lz = (z < 0) ? 15 : (z >= 16 ? 0 : z);

                for (int y = -1; y <= Chunk.HEIGHT; y++) {
                    if (y < 0 || y >= Chunk.HEIGHT || target == null) {
                        blockCache[x + 1][y + 1][z + 1] = Block.AIR;
                        metaCache[x + 1][y + 1][z + 1]  = 0;
                    } else {
                        blockCache[x + 1][y + 1][z + 1] = target.getBlock(lx, y, lz);
                        metaCache[x + 1][y + 1][z + 1]  = target.getMeta(lx, y, lz);
                    }
                }
            }
        }

        // ── 2. MESH GENERATION LOOP ──
        for (int x = 0; x < Chunk.SIZE;  x++) {
            for (int y = 0; y < Chunk.HEIGHT; y++) {
                for (int z = 0; z < Chunk.SIZE;  z++) {

                    int cx = x + 1; // offset for the padded caches
                    int cy = y + 1;
                    int cz = z + 1;

                    Block block = blockCache[cx][cy][cz];
                    if (block == Block.AIR) continue;

                    int wx = worldXStart + x;
                    int wz = worldZStart + z;

                    boolean isTrans = !block.isOpaque();
                    List<Float> verts = isTrans ? tVerts : oVerts;
                    List<Integer> indices = isTrans ? tIdx : oIdx;

                    // Compute sloped liquid bounds
                    float h00 = block.isLiquid() ? getLiquidCornerHeight(blockCache, metaCache, x, y, z, 0, 0) : 1f;
                    float h10 = block.isLiquid() ? getLiquidCornerHeight(blockCache, metaCache, x, y, z, 1, 0) : 1f;
                    float h11 = block.isLiquid() ? getLiquidCornerHeight(blockCache, metaCache, x, y, z, 1, 1) : 1f;
                    float h01 = block.isLiquid() ? getLiquidCornerHeight(blockCache, metaCache, x, y, z, 0, 1) : 1f;

                    float[] top   = { wx, y+h00, wz,  wx+1, y+h10, wz,  wx+1, y+h11, wz+1,  wx, y+h01, wz+1 };
                    float[] bot   = { wx, y, wz+1,  wx+1, y, wz+1,  wx+1, y, wz,  wx, y, wz };
                    float[] front = { wx, y, wz+1,  wx+1, y, wz+1,  wx+1, y+h11, wz+1,  wx, y+h01, wz+1 };
                    float[] back  = { wx+1, y, wz,  wx, y, wz,  wx, y+h00, wz,  wx+1, y+h10, wz };
                    float[] right = { wx+1, y, wz+1,  wx+1, y, wz,  wx+1, y+h10, wz,  wx+1, y+h11, wz+1 };
                    float[] left  = { wx, y, wz,  wx, y, wz+1,  wx, y+h01, wz+1,  wx, y+h00, wz };

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
        chunk.opaqueMesh = buildMesh(oVerts, oIdx);
        chunk.transparentMesh = buildMesh(tVerts, tIdx);
        chunk.dirty = false;

        // Neighbor mesh update triggers
        if (!chunk.meshBuilt) {
            chunk.meshBuilt = true;
            Chunk nX_chunk = world.getChunk(chunk.cx + 1, chunk.cz); if (nX_chunk != null) nX_chunk.dirty = true;
            Chunk pX_chunk = world.getChunk(chunk.cx - 1, chunk.cz); if (pX_chunk != null) pX_chunk.dirty = true;
            Chunk nZ_chunk = world.getChunk(chunk.cx, chunk.cz + 1); if (nZ_chunk != null) nZ_chunk.dirty = true;
            Chunk pZ_chunk = world.getChunk(chunk.cx, chunk.cz - 1); if (pZ_chunk != null) pZ_chunk.dirty = true;
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

    private static Mesh buildMesh(List<Float> verts, List<Integer> indices) {
        if (verts.isEmpty()) return null;
        float[] vArr = new float[verts.size()]; for (int i = 0; i < verts.size(); i++) vArr[i] = verts.get(i);
        int[] iArr = new int[indices.size()]; for (int i = 0; i < indices.size(); i++) iArr[i] = indices.get(i);
        return new Mesh(vArr, iArr);
    }

    private static void addFace(List<Float> verts, List<Integer> indices, float[] faceVertices, Block block, float[] ao, float nx, float ny, float nz) {
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
}