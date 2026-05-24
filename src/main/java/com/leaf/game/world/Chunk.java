package com.leaf.game.world;

import com.leaf.game.render.Mesh;

public class Chunk {
    public static final int SIZE = 16;
    public static final int HEIGHT = 64;

    public Mesh opaqueMesh;
    public Mesh transparentMesh;
    public boolean dirty;

    public final int cx, cz;

    private final Block[][][] blocks = new Block[SIZE][HEIGHT][SIZE];
    private final byte[][][]  meta   = new byte[SIZE][HEIGHT][SIZE]; // NEW: Block states!

    public Chunk(int cx, int cz) {
        this.cx = cx;
        this.cz = cz;
        for (Block[][] yz : blocks)
            for (Block[] z : yz)
                java.util.Arrays.fill(z, Block.AIR);
    }

    public Block getBlock(int lx, int ly, int lz) { return blocks[lx][ly][lz]; }
    public void setBlock(int lx, int ly, int lz, Block b) { blocks[lx][ly][lz] = b; }

    public byte getMeta(int lx, int ly, int lz) { return meta[lx][ly][lz]; }
    public void setMeta(int lx, int ly, int lz, byte m) { meta[lx][ly][lz] = m; }
}