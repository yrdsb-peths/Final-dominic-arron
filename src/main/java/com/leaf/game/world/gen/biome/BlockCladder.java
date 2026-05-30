package com.leaf.game.world.gen.biome;

import com.leaf.game.core.GameConfig;
import com.leaf.game.world.Block;

/**
 * Decides which block clads an alpine column at a given depth below its
 * surface. Produces the classic snow-mountain profile:
 *
 *   • sheer cliffs            → bare rock (snow can't cling)
 *   • above the snow line     → deep powder mantle over glacier ice over stone
 *   • scree band below it     → loose gravel over stone
 *   • mountain foot           → barren dirt over stone (no green grass)
 *
 * The same {@link #columnBlock} call is used by both the cy=0 surface
 * generator and the cy≥1 sky-mountain generator, so a peak is clad
 * identically on both sides of the 512-block chunk seam.
 */
public class BlockCladder {

    private final int snowAltitudeY;

    private static final float CLIFF_SLOPE      = 1.30f;
    // Raised from 0.85 so snow clings to steeper faces → noticeably snowier
    // peaks; anything steeper than this (but below CLIFF_SLOPE) reads as
    // wind-scoured rock outcrop, which keeps the massif from looking uniform.
    private static final float SNOW_SLOPE_LIMIT = 1.05f;
    private static final int   ICE_UNDER_SNOW   = 4;   // firn/glacier-ice band thickness

    public BlockCladder(int snowAltitudeY) {
        this.snowAltitudeY = snowAltitudeY;
    }

    /**
     * Block at {@code worldY} in an alpine column whose surface is at
     * {@code surfaceY}. depth = surfaceY - worldY (0 == the surface block).
     */
    public Block columnBlock(int surfaceY, int worldY, float slopeMag) {
        int depth = surfaceY - worldY;
        if (depth < 0) return Block.AIR;

        // 1. Sheer cliffs are always exposed rock, top to bottom.
        if (slopeMag > CLIFF_SLOPE) return Block.STONE;

        // 2. Snow zone: powder mantle → glacier ice → stone.
        if (surfaceY >= snowAltitudeY) {
            float localLimit = SNOW_SLOPE_LIMIT + (surfaceY - snowAltitudeY) * 0.015f;
            if (slopeMag < localLimit) {
                int sd = snowDepth(surfaceY, slopeMag, localLimit);
                if (depth < sd)                 return Block.SNOW;
                if (depth < sd + ICE_UNDER_SNOW) return Block.ICE;
                return Block.STONE;
            }
            return Block.STONE;   // too steep to hold snow → exposed rock
        }

        // 3. Scree band just below the snow line: loose gravel over stone.
        if (surfaceY > snowAltitudeY - 30) {
            if (depth == 0 && slopeMag <= 0.6f) return Block.GRAVEL;
            return Block.STONE;
        }

        // 4. Mountain foot: barren dirt skin over stone.
        if (slopeMag > 0.6f) return Block.STONE;
        return depth <= 3 ? Block.DIRT : Block.STONE;
    }

    /**
     * Snow mantle thickness (blocks). Flatter slopes and higher altitude
     * accumulate deeper snow — gives avalanches material and reads as real
     * deep snow rather than a 1-block dusting. Clamped to [1, alpineSnowDepthMax].
     */
    private int snowDepth(int surfaceY, float slopeMag, float localLimit) {
        float flat = 1f - Math.min(1f, slopeMag / Math.max(0.0001f, localLimit)); // 0..1
        float alt  = Math.min(1f, (surfaceY - snowAltitudeY) / 320f);             // 0..1
        int maxD   = GameConfig.alpineSnowDepthMax;
        int d = 1 + Math.round((maxD - 1) * (0.55f * flat + 0.45f * alt));
        return Math.max(1, Math.min(maxD, d));
    }

    // ── Legacy single-block helpers (kept for any external callers) ──────────
    public Block surfaceBlock(int surfaceY, float slopeMag) {
        return columnBlock(surfaceY, surfaceY, slopeMag);
    }
    public Block subSurfaceBlock(int surfaceY, float slopeMag) {
        return columnBlock(surfaceY, surfaceY - 1, slopeMag);
    }
}
