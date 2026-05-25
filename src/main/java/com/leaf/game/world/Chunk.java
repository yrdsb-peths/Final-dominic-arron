package com.leaf.game.world;

import com.leaf.game.render.Mesh;

public class Chunk {
    public static final int SIZE = 16;
    public static final int HEIGHT = 512;

    public Mesh opaqueMesh;
    public Mesh transparentMesh;
    public boolean dirty;
    public boolean meshBuilt = false;

    /**
     * Tight Y bounds of non-AIR blocks in this chunk (local coords).
     * minBlockY starts at HEIGHT and maxBlockY at -1 to signal "empty".
     * Updated conservatively on setBlock — only grows outward, never shrinks,
     * which is safe for the mesh loop: we may iterate a few extra rows but
     * will never skip a block that actually exists.
     */
    public int minBlockY = HEIGHT;
    public int maxBlockY = -1;
    public enum ChunkState { EMPTY, GENERATING, BLOCKS_READY, MESHED }
    public volatile ChunkState state = ChunkState.EMPTY;
    /**
     * cx, cz — horizontal chunk coordinates (chunk units, not blocks).
     * cy — vertical chunk index: 0 = surface world (Y 0..511),
     *      -1 = first deep layer (Y -512..-1), -2 = second, etc.
     * worldY of a local y: worldY = cy * HEIGHT + localY
     */
    public final int cx, cy, cz;

    // ── Flat byte arrays for block and meta storage ───────────────────────────
    // Replaces Block[SIZE][HEIGHT][SIZE] (jagged arrays, ~1 MB/chunk due to Java
    // object-header overhead) with two compact flat byte arrays (~256 KB/chunk).
    // At RD=16 (~1369 loaded chunks) this cuts block-storage heap from ~1.4 GB
    // to ~350 MB, eliminating the GC-thrash freeze that occurred after extended play.
    //
    // Layout: index = lx * HEIGHT * SIZE + ly * SIZE + lz
    // Block stored as its ordinal (byte); BLOCK_BY_ID maps back to enum constants.
    private static final Block[] BLOCK_BY_ID = Block.values(); // cached, never GC'd
    private static final int STRIDE = SIZE * HEIGHT; // lx stride

    private final byte[] blocks = new byte[SIZE * HEIGHT * SIZE]; // all zeros = AIR (ordinal 0)
    private final byte[] meta   = new byte[SIZE * HEIGHT * SIZE];

    private static int idx(int lx, int ly, int lz) {
        return lx * STRIDE + ly * SIZE + lz;
    }

    public Chunk(int cx, int cy, int cz) {
        this.cx = cx;
        this.cy = cy;
        this.cz = cz;
        // blocks[] is already zero-filled by JVM; AIR.ordinal() == 0, so no explicit fill needed.
    }

    /** Convenience constructor for surface (cy=0) chunks. */
    public Chunk(int cx, int cz) { this(cx, 0, cz); }

    public Block getBlock(int lx, int ly, int lz) {
        return BLOCK_BY_ID[blocks[idx(lx, ly, lz)] & 0xFF];
    }
    public void setBlock(int lx, int ly, int lz, Block b) {
        blocks[idx(lx, ly, lz)] = (byte) b.ordinal();
        if (b != Block.AIR) {
            if (ly < minBlockY) minBlockY = ly;
            if (ly > maxBlockY) maxBlockY = ly;
        }
    }

    public byte getMeta(int lx, int ly, int lz) { return meta[idx(lx, ly, lz)]; }
    public void setMeta(int lx, int ly, int lz, byte m) { meta[idx(lx, ly, lz)] = m; }

    /**
     * Releases GPU resources held by this chunk's meshes.
     *
     * <p><b>Must be called from the main OpenGL thread.</b> Called by
     * {@code World.updateChunks()} during the dynamic unloading sweep, which
     * already runs on the main thread inside the game loop.
     *
     * <p>After cleanup the mesh references are nulled so the garbage collector
     * can reclaim the Java-side Mesh objects, and the block data arrays are
     * freed by normal GC once the Chunk itself is removed from the world map.
     */
    public void cleanup() {
        if (opaqueMesh != null) {
            opaqueMesh.cleanup();
            opaqueMesh = null;
        }
        if (transparentMesh != null) {
            transparentMesh.cleanup();
            transparentMesh = null;
        }
    }
}