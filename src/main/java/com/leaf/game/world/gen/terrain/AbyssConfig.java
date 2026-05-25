package com.leaf.game.world.gen.terrain;

public class AbyssConfig {

    // ── LOCATION ─────────────────────────────────────────────────────────────
    public static int   centerX        = 2000;
    public static int   centerZ        = 2000;

    // ── ENTRANCE ─────────────────────────────────────────────────────────────
    public static float entranceRadius = 88f;

    /**
     * Estimated world-Y of the shaft entrance (where it punches through terrain).
     * With heightBase=200 and seaLevel=220, plains sit around y=230.
     * Used ONLY for:
     *   (a) layer depth calculations  (depth = entranceY - worldY)
     *   (b) the "above entrance" comment in suppressWater
     * The shaft is carved for ALL y levels regardless of this value.
     *
     * FUTURE: when 3D chunks exist, worldY = chunkYOffset + localY.
     * All depth math below already uses worldY; no generator changes needed.
     */
    public static int   entranceY      = 235;

    // ── SHAFT SHAPE ──────────────────────────────────────────────────────────
    public static float taperRate       = 0.05f;
    /** Conservative outer bound for isInOuterZone() cull. */
    public static float maxWallVariation = 42f;

    // ── WAVE NOISE (3–5 large bays around perimeter) ─────────────────────────
    public static float waveRadialFreq  = 2.0f;
    public static float waveYFreq       = 0.004f;
    public static float waveAmplitude   = 22f;

    // ── RIDGE NOISE (medium rocky protrusions) ────────────────────────────────
    public static float ridgeRadialFreq = 6.0f;
    public static float ridgeYFreq      = 0.010f;
    public static float ridgeAmplitude  = 12f;

    // ── JITTER (block-level roughness, constant per column) ───────────────────
    public static float jitterFreq      = 0.055f;
    public static float jitterAmplitude = 6f;

    // ── LAYER BOUNDARIES (absolute worldY) ───────────────────────────────────
    // With entranceY=235 these give ~40 block layers — comfortable but not vast.
    // Phase 2 expansion: when 3D chunks arrive, entranceY becomes large (e.g.
    // chunk.cy * 512 + 235) and these boundaries extend infinitely downward.
    //
    //  Layer 1 (Edge):        y = 185–235   depth   0– 50
    //  Layer 2 (Forest):      y = 125–185   depth  50–110
    //  Layer 3 (Great Fault): y =  70–125   depth 110–165
    //  Layer 4 (Goblet):      y =  30– 70   depth 165–205
    //  Layer 5 (Sea):         y =   8– 30   depth 205–227
    //  Layer 6 (Capital):     y =   0–  8   depth 227–235
    public static int layer2Start = 185;
    public static int layer3Start = 125;
    public static int layer4Start = 70;
    public static int layer5Start = 30;
    public static int layer6Start = 8;

    // ── LEDGE SYSTEM ─────────────────────────────────────────────────────────
    public static float ledgeAngularFreq = 3.5f;
    public static float ledgeYFreq       = 0.045f;
    public static float ledgeThreshold   = 0.65f;
    public static int   ledgeMaxDepth    = 5;

    // ── WATERFALLS ────────────────────────────────────────────────────────────
    public static int   waterfallCount     = 6;
    public static float waterfallHalfWidth = 0.04f;
}