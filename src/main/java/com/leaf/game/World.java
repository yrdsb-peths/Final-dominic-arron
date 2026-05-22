package com.leaf.game;

import java.util.ArrayList;
import java.util.List;

public class World {

    public static final int WIDTH  = 32;  // X axis
    public static final int HEIGHT =64;  // Y axis (up)
    public static final int DEPTH  = 32;  // Z axis

    private static final int TERRAIN_HEIGHT = 8; // y where grass appears

    // The 3D array of blocks. blocks[x][y][z].
    private final Block[][][] blocks;

    public World() {
        blocks = new Block[WIDTH][HEIGHT][DEPTH];
        generateFlat();
    }

    // --- WORLD GENERATION ---

    private void generateFlat() {
        for (int x = 0; x < WIDTH; x++) {
            for (int y = 0; y < HEIGHT; y++) {
                for (int z = 0; z < DEPTH; z++) {

                    if (y < TERRAIN_HEIGHT - 1) {
                        blocks[x][y][z] = Block.STONE;        // underground
                    } else if (y == TERRAIN_HEIGHT - 1) {
                        blocks[x][y][z] = Block.GRASS;        // surface
                    } else {
                        blocks[x][y][z] = Block.AIR;          // sky
                    }
                }
            }
        }
    }

    // --- BLOCK ACCESS ---

    public Block getBlock(int x, int y, int z) {
        if (!inBounds(x, y, z)) return Block.AIR; // out of bounds = treat as air
        return blocks[x][y][z];
    }

    public void setBlock(int x, int y, int z, Block block) {
        if (!inBounds(x, y, z)) return;
        blocks[x][y][z] = block;
    }

    private boolean inBounds(int x, int y, int z) {
        return x >= 0 && x < WIDTH
                && y >= 0 && y < HEIGHT
                && z >= 0 && z < DEPTH;
    }

    // --- MESH BUILDING ---

    // Builds one large Mesh containing every visible face in the world.
    // Call this once at startup (and later when blocks change).
    public Mesh buildMesh() {
        List<Float>   verts   = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();
        int vertexIndex = 0; // tracks how many vertices we've added so far

        for (int x = 0; x < WIDTH;  x++) {
            for (int y = 0; y < HEIGHT; y++) {
                for (int z = 0; z < DEPTH;  z++) {

                    Block block = blocks[x][y][z];
                    if (!block.isSolid()) continue; // skip air blocks entirely

                    // Check all 6 neighbors. Add a face only if that neighbor is air/out of bounds.

                    // TOP face — neighbor above
                    if (!getBlock(x, y + 1, z).isSolid()) {
                        float brightness = 1.0f; // top faces are brightest
                        addFace(verts, indices, vertexIndex,
                                topFace(x, y, z), block, brightness);
                        vertexIndex += 4; // each face adds exactly 4 vertices
                    }

                    // BOTTOM face — neighbor below
                    if (!getBlock(x, y - 1, z).isSolid()) {
                        float brightness = 0.5f; // bottom faces are darkest
                        addFace(verts, indices, vertexIndex,
                                bottomFace(x, y, z), block, brightness);
                        vertexIndex += 4;
                    }

                    // FRONT face — neighbor at +Z
                    if (!getBlock(x, y, z + 1).isSolid()) {
                        float brightness = 0.75f;
                        addFace(verts, indices, vertexIndex,
                                frontFace(x, y, z), block, brightness);
                        vertexIndex += 4;
                    }

                    // BACK face — neighbor at -Z
                    if (!getBlock(x, y, z - 1).isSolid()) {
                        float brightness = 0.75f;
                        addFace(verts, indices, vertexIndex,
                                backFace(x, y, z), block, brightness);
                        vertexIndex += 4;
                    }

                    // RIGHT face — neighbor at +X
                    if (!getBlock(x + 1, y, z).isSolid()) {
                        float brightness = 0.6f;
                        addFace(verts, indices, vertexIndex,
                                rightFace(x, y, z), block, brightness);
                        vertexIndex += 4;
                    }

                    // LEFT face — neighbor at -X
                    if (!getBlock(x - 1, y, z).isSolid()) {
                        float brightness = 0.6f;
                        addFace(verts, indices, vertexIndex,
                                leftFace(x, y, z), block, brightness);
                        vertexIndex += 4;
                    }
                }
            }
        }

        // Convert ArrayList<Float> to float[]
        float[] vertArray = new float[verts.size()];
        for (int i = 0; i < verts.size(); i++) {
            vertArray[i] = verts.get(i);
        }

        // Convert ArrayList<Integer> to int[]
        int[] idxArray = new int[indices.size()];
        for (int i = 0; i < indices.size(); i++) {
            idxArray[i] = indices.get(i);
        }

        return new Mesh(vertArray, idxArray);
    }

    // --- FACE HELPER: adds 4 vertices + 6 indices for one quad face ---
    private void addFace(List<Float> verts, List<Integer> indices,
                         int baseIndex, float[] faceVertices,
                         Block block, float brightness) {

        // faceVertices is an array of 12 floats: 4 vertices × (x, y, z)
        // We interleave position and color into the vertex buffer: x, y, z, r, g, b

        for (int i = 0; i < 4; i++) {
            // Position (from the face definition)
            verts.add(faceVertices[i * 3]);      // x
            verts.add(faceVertices[i * 3 + 1]);  // y
            verts.add(faceVertices[i * 3 + 2]);  // z

            // Color (block color × brightness for shading)
            verts.add(block.r * brightness);
            verts.add(block.g * brightness);
            verts.add(block.b * brightness);
        }

        // Two triangles from 4 vertices (same pattern as Phase 1)
        // v0-v1-v2 and v2-v3-v0
        indices.add(baseIndex);
        indices.add(baseIndex + 1);
        indices.add(baseIndex + 2);
        indices.add(baseIndex + 2);
        indices.add(baseIndex + 3);
        indices.add(baseIndex);
    }

    // --- FACE GEOMETRY DEFINITIONS ---
    // Each method returns 12 floats: 4 vertices × (x, y, z)
    // Listed CCW when viewed from outside the block.
    // Block occupies (x, y, z) to (x+1, y+1, z+1).

    private float[] topFace(int x, int y, int z) {
        // Viewed from above (+Y): CCW order
        return new float[] {
                x,   y+1, z,      // back-left
                x+1, y+1, z,      // back-right
                x+1, y+1, z+1,    // front-right
                x,   y+1, z+1     // front-left
        };
    }

    private float[] bottomFace(int x, int y, int z) {
        // Viewed from below (-Y): CCW order (reversed from top)
        return new float[] {
                x,   y, z+1,    // front-left
                x+1, y, z+1,    // front-right
                x+1, y, z,      // back-right
                x,   y, z       // back-left
        };
    }

    private float[] frontFace(int x, int y, int z) {
        // Viewed from +Z side: CCW order
        return new float[] {
                x,   y,   z+1,   // bottom-left
                x+1, y,   z+1,   // bottom-right
                x+1, y+1, z+1,   // top-right
                x,   y+1, z+1    // top-left
        };
    }

    private float[] backFace(int x, int y, int z) {
        // Viewed from -Z side: CCW order (reversed from front)
        return new float[] {
                x+1, y,   z,     // bottom-right (from outside)
                x,   y,   z,     // bottom-left
                x,   y+1, z,     // top-left
                x+1, y+1, z      // top-right
        };
    }

    private float[] rightFace(int x, int y, int z) {
        // Viewed from +X side: CCW order
        return new float[] {
                x+1, y,   z+1,   // bottom-front
                x+1, y,   z,     // bottom-back
                x+1, y+1, z,     // top-back
                x+1, y+1, z+1    // top-front
        };
    }

    private float[] leftFace(int x, int y, int z) {
        // Viewed from -X side: CCW order
        return new float[] {
                x, y,   z,       // bottom-back
                x, y,   z+1,     // bottom-front
                x, y+1, z+1,     // top-front
                x, y+1, z        // top-back
        };
    }
}