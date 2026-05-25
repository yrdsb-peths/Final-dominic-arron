package com.leaf.game.world.gen.terrain;

import com.leaf.game.util.Noise;
import com.leaf.game.world.Block;
import com.leaf.game.world.Chunk;

/**
 * Procedural Abyss generator — Made in Abyss inspired vertical mega-structure.
 *
 * ── INFINITE DEPTH DESIGN ─────────────────────────────────────────────────
 * Every depth calculation uses `worldY` rather than raw chunk-local Y.
 * Currently worldY == localY (single-column chunks, no Y offset).
 *
 * When 3D chunk coords are added, the caller passes:
 *   int worldY = chunk.cy * Chunk.HEIGHT + localY;
 * and all layer/depth math extends naturally — zero changes needed here.
 *
 * ── SHAFT MATH ────────────────────────────────────────────────────────────
 * For column (wx, wz), horizontal distance r from center:
 *
 *   wallR(worldY) = entranceRadius
 *                 + (entranceY - worldY) * taperRate   ← gentle funnel
 *                 + wave(sinA, cosA, worldY)            ← large bays
 *                 + ridge(sinA, cosA, worldY)           ← rocky protrusions
 *                 + jitter(wx, wz)                      ← block roughness
 *
 *   r < wallR  → inside shaft (AIR, water suppressed)
 *   r ≥ wallR  → wall / terrain
 *
 * Using (sinA, cosA) instead of raw atan2 eliminates the ±π seam.
 * The shaft is perfectly seamless across all chunk boundaries.
 *
 * ── PERFORMANCE ──────────────────────────────────────────────────────────
 * isInOuterZone(): one squared-distance compare, no sqrt.
 *   Skips ALL abyss work for the vast majority of world columns.
 * ColData: one sqrt + two trig calls per column, amortised across 512 Y levels.
 * All noise calls are 2D (cheaper than the 3D cave noise already running).
 *
 * ── THREE HOOKS IN WorldGen.generateChunk ────────────────────────────────
 *
 * ① Before solid[] loop — cull + precompute:
 *     AbyssGenerator.ColData aCol = null;
 *     if (abyss.isInOuterZone(wx, wz)) aCol = abyss.prepareColumn(wx, wz);
 *
 * ② After solid[] is fully built — carve shaft and restore ledges:
 *     if (aCol != null) abyss.carve(solid, aCol);
 *
 * ③ In the block-placement Y loop:
 *
 *   if (!solid[ly]) {
 *       boolean noWater = aCol != null && abyss.suppressWater(aCol, ly);
 *       chunk.setBlock(lx, ly, lz,
 *           (!noWater && ly <= GameConfig.seaLevel) ? Block.WATER : Block.AIR);
 *   } else {
 *       if (aCol != null && abyss.isAbyssBlock(aCol, ly)) {
 *           chunk.setBlock(lx, ly, lz, abyss.wallBlock(aCol, ly, solid));
 *           if (!hitSurface) { hitSurface = true; dirtCount = 0; }
 *       } else {
 *           // ... normal biome surface / dirt / stone logic unchanged ...
 *       }
 *   }
 */
public class AbyssGenerator {

    private final Noise waveNoise;
    private final Noise ridgeNoise;
    private final Noise jitterNoise;
    private final Noise ledgeNoise;
    private final Noise waterfallNoise;

    private final float waterfallBaseOffset;

    public AbyssGenerator(long seed) {
        waveNoise       = new Noise(seed + 20_000L);
        ridgeNoise      = new Noise(seed + 21_000L);
        jitterNoise     = new Noise(seed + 22_000L);
        ledgeNoise      = new Noise(seed + 23_000L);
        waterfallNoise  = new Noise(seed + 24_000L);
        // Seed-specific rotation so waterfall strips aren't always N/S/E/W
        waterfallBaseOffset = waterfallNoise.get(0f, 0f) * (float) Math.PI;
    }

    // =========================================================================
    // ColData — precomputed per-column constants (one sqrt amortised over 512 Y)
    // =========================================================================

    public static final class ColData {
        public final float r;      // horizontal distance from abyss center
        public final float sinA;   // sin(angle) — seamless angular noise axis
        public final float cosA;   // cos(angle) — seamless angular noise axis
        public final float angle;  // raw atan2 — used for waterfall math only
        public final float jitter; // block-level roughness, constant per column

        ColData(int wx, int wz, Noise jNoise) {
            float dx = wx - AbyssConfig.centerX;
            float dz = wz - AbyssConfig.centerZ;
            r     = (float) Math.sqrt(dx * dx + dz * dz);
            angle = (float) Math.atan2(dz, dx);
            sinA  = (float) Math.sin(angle);
            cosA  = (float) Math.cos(angle);
            jitter = jNoise.get(
                    wx * AbyssConfig.jitterFreq,
                    wz * AbyssConfig.jitterFreq) * AbyssConfig.jitterAmplitude;
        }
    }

    // =========================================================================
    // PUBLIC API
    // =========================================================================

    /**
     * HOOK ① — O(1) column cull using squared distance (surface / cy=0 version).
     * For deep chunks use {@link #isInOuterZoneForDepth} — the shaft widens with
     * depth so the maxR must account for the actual chunk offset.
     */
    public boolean isInOuterZone(int wx, int wz) {
        return isInOuterZoneForDepth(wx, wz, 0);
    }

    /**
     * Depth-aware outer zone cull — MUST be used for deep abyss chunks (cy < 0).
     *
     * Bug that caused the ~-500 ceiling: isInOuterZone used a maxR calibrated for
     * the surface (depth ≈ 235 blocks). At worldY = -512 the shaft base widens to
     * 88 + 747*0.05 = 125, and with noise it reaches ~165 — wider than the old
     * maxR of ~141. Columns at r=150 were inside the shaft but failed the cull,
     * leaving solid stone inside it and creating an invisible floor.
     *
     * @param worldYOffset  chunk.cy * Chunk.HEIGHT (0 for surface, negative for deep)
     */
    public boolean isInOuterZoneForDepth(int wx, int wz, int worldYOffset) {
        float dx = wx - AbyssConfig.centerX;
        float dz = wz - AbyssConfig.centerZ;
        // Worst-case wall radius: use the deepest point of the chunk (local y = 0).
        float deepestWorldY = worldYOffset;
        float maxDepth = Math.max(0f, AbyssConfig.entranceY - deepestWorldY);
        float maxR = AbyssConfig.entranceRadius
                   + maxDepth * AbyssConfig.taperRate
                   + AbyssConfig.maxWallVariation
                   + 30f;   // extra margin for bell-mouth flare above entrance
        return (dx * dx + dz * dz) < (maxR * maxR);
    }

    /** HOOK ① — Precompute column constants after isInOuterZone() returns true. */
    public ColData prepareColumn(int wx, int wz) {
        return new ColData(wx, wz, jitterNoise);
    }

    /**
     * HOOK ② — Carve the shaft into solid[].
     *
     * @param solid        The solid-flag array for the chunk column (length = Chunk.HEIGHT).
     * @param col          Precomputed per-column geometry.
     * @param worldYOffset chunk.cy * Chunk.HEIGHT — added to local y to get absolute worldY.
     *                     Pass 0 for surface (cy=0) chunks; negative for deep abyss chunks.
     *
     * Two passes:
     *   Pass 1 — clear any solid voxel where r < wallR(worldY)
     *   Pass 2 — restore ledge shelves just inside the wall face
     */
    public void carve(boolean[] solid, ColData col, int worldYOffset) {
        // Pass 1: clear shaft interior
        for (int wy = 0; wy < solid.length; wy++) {
            int worldY = worldYOffset + wy;
            if (col.r < wallRadius(col, worldY)) {
                solid[wy] = false;
            }
        }

        // Pass 2: restore ledge shelves
        // A ledge forms where:
        //   (a) the block is in the narrow band just inside the wall
        //   (b) ledge noise exceeds the layer-specific threshold
        for (int wy = 1; wy < solid.length - 1; wy++) {
            int   worldY = worldYOffset + wy;
            float wallR  = wallRadius(col, worldY);
            if (col.r >= wallR - AbyssConfig.ledgeMaxDepth && col.r < wallR) {
                if (sampleLedge(col, worldY)) {
                    solid[wy] = true;
                }
            }
        }
    }

    /** Backward-compatible overload for surface (cy=0) chunks. */
    public void carve(boolean[] solid, ColData col) { carve(solid, col, 0); }

    /**
     * HOOK ③a — Water suppression.
     *
     * @param worldY Absolute world Y (cy * Chunk.HEIGHT + localY).
     * Returns true if this voxel must be AIR even when worldY <= seaLevel.
     * Suppresses everywhere inside and just outside the shaft so ocean water
     * cannot cascade in through thin wall sections or ledge gaps.
     *
     * Note: we suppress slightly BEYOND wallR (by ledgeMaxDepth) to also
     * prevent water pooling in small ledge pockets near the wall face.
     */
    public boolean suppressWater(ColData col, int worldY) {
        return col.r < wallRadius(col, worldY) + AbyssConfig.ledgeMaxDepth;
    }

    /**
     * Returns true if this voxel is inside the open shaft void.
     * @param worldY Absolute world Y.
     */
    public boolean isInsideShaft(ColData col, int worldY) {
        return col.r < wallRadius(col, worldY);
    }

    /**
     * Returns true if this SOLID voxel should use the abyss layer palette
     * instead of the normal biome/terrain block logic.
     *
     * @param worldY Absolute world Y (cy * Chunk.HEIGHT + localY).
     *
     * Covers two cases:
     *   (a) Ledge blocks: restored inside the shaft (r < wallR) by Pass 2 of carve()
     *   (b) Wall face band: the ~8-block thick visible wall just outside the shaft
     *
     * Blocks farther than 8 blocks from the wall face are solid terrain that was
     * never touched by the abyss — they keep their normal biome materials.
     * This prevents the abyss palette bleeding into distant underground terrain.
     */
    public boolean isAbyssBlock(ColData col, int worldY) {
        float wallR = wallRadius(col, worldY);
        return col.r < wallR + 8f;
    }

    /**
     * HOOK ③b — Block type for a solid voxel in the abyss zone.
     *
     * @param worldY Absolute world Y (cy * Chunk.HEIGHT + localY).
     * Only call this after isAbyssBlock() returns true.
     *
     * Priority:
     *   1. Waterfall strip on the wall face → WATER (single-block-wide strips)
     *   2. Layer palette from AbyssLayer
     */
    public Block wallBlock(ColData col, int worldY, boolean[] solid) {
        float wallR = wallRadius(col, worldY);

        // Wall face: first ~2 blocks outside the shaft void
        boolean isWallFace = col.r >= wallR && col.r < wallR + 2f;
        if (isWallFace && isWaterfallStrip(col, worldY)) {
            return Block.WATER;
        }

        return AbyssLayer.forDepth(worldY).wallFaceBlock;
    }

    /**
     * Returns the wall radius at the entrance level — useful for computing
     * crater-rim height boosts in WorldGen.
     */
    public float wallRadiusAtEntrance(ColData col) {
        return wallRadius(col, AbyssConfig.entranceY);
    }

    // =========================================================================
    // INTERNAL MATH
    // =========================================================================

    /**
     * Wall radius at worldY for column col.
     *
     * ── Entrance bell-mouth (worldY > entranceY) ─────────────────────────────
     * Above the entrance the shaft flares outward like a trumpet bell,
     * so the surface hole is wider and more dramatic than the shaft below.
     * The flare grows at 0.45 blocks per block of rise, capped at +30 blocks.
     *
     * ── Below entrance (worldY ≤ entranceY) ──────────────────────────────────
     * The shaft tapers gently wider with depth (taperRate), with large wave
     * bays and medium ridges. For deep chunks (worldY << 0) the math extends
     * naturally — no code changes needed when cy * HEIGHT is passed as offset.
     */
    float wallRadius(ColData col, int worldY) {
        float depth    = AbyssConfig.entranceY - worldY;  // 0 at entrance, positive downward
        float ampScale = AbyssLayer.forDepth(worldY).wallAmplitudeScale;

        float base;
        if (depth <= 0) {
            // Above entrance: bell-mouth flare — shaft opens like a crater
            float aboveDepth = -depth;  // blocks above entrance (positive)
            float flare = Math.min(aboveDepth * 0.45f, 30f);  // caps at +30 block radius
            base = AbyssConfig.entranceRadius + flare;
        } else {
            // Below entrance: gentle funnel widening
            base = AbyssConfig.entranceRadius + depth * AbyssConfig.taperRate;
        }

        // Large bays (~3–4 around perimeter), rotating gently with depth
        float wave  = waveNoise.octave(
                col.sinA * AbyssConfig.waveRadialFreq + worldY * AbyssConfig.waveYFreq,
                col.cosA * AbyssConfig.waveRadialFreq,
                2, 0.5f) * AbyssConfig.waveAmplitude * ampScale;

        // Medium ridges and rocky pillar-like protrusions
        float ridge = ridgeNoise.octave(
                col.sinA * AbyssConfig.ridgeRadialFreq + worldY * AbyssConfig.ridgeYFreq,
                col.cosA * AbyssConfig.ridgeRadialFreq,
                2, 0.5f) * AbyssConfig.ridgeAmplitude * ampScale;

        return base + wave + ridge + col.jitter;
    }

    /**
     * Returns true if a ledge shelf should form at worldY.
     * Great Fault and Sea layers have near-zero ledgeFreqScale,
     * pushing effectiveThresh above 1.0 → mathematically impossible to ledge.
     */
    private boolean sampleLedge(ColData col, int worldY) {
        AbyssLayer layer = AbyssLayer.forDepth(worldY);
        float effectiveThresh = AbyssConfig.ledgeThreshold / layer.ledgeFreqScale;
        if (effectiveThresh >= 1.0f) return false;

        float v = ledgeNoise.octave(
                col.sinA * AbyssConfig.ledgeAngularFreq + worldY * AbyssConfig.ledgeYFreq,
                col.cosA * AbyssConfig.ledgeAngularFreq,
                2, 0.5f);
        return (v + 1f) * 0.5f > effectiveThresh;
    }

    /**
     * Returns true if this column's angle is inside a waterfall band.
     * A tiny depthSpin gives a subtle spiral with depth (MiA aesthetic).
     */
    private boolean isWaterfallStrip(ColData col, int worldY) {
        float spacing   = (float)(2.0 * Math.PI / AbyssConfig.waterfallCount);
        float depthSpin = worldY * 0.0006f;
        float a         = col.angle + waterfallBaseOffset + depthSpin;
        float pos       = ((a % spacing) + spacing) % spacing;
        return pos < AbyssConfig.waterfallHalfWidth
                || pos > spacing - AbyssConfig.waterfallHalfWidth;
    }
}