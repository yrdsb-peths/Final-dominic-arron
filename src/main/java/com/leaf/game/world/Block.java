package com.leaf.game.world;

public enum Block {
    AIR       (0.00f, 0.00f, 0.00f, 0.0f,  0.0f),
    GRASS     (0.30f, 0.70f, 0.20f, 1.0f,  0.8f),
    DIRT      (0.50f, 0.30f, 0.10f, 1.0f,  1.0f),
    STONE     (0.50f, 0.50f, 0.50f, 1.0f,  4.0f),
    WATER     (0.10f, 0.42f, 0.80f, 0.65f, 0.0f), // 65% Opacity!
    SAND      (0.80f, 0.72f, 0.45f, 1.0f,  0.9f),
    SNOW      (0.88f, 0.93f, 0.96f, 1.0f,  0.5f),
    RED_SAND  (0.76f, 0.38f, 0.22f, 1.0f,  0.9f),
    GRAVEL    (0.45f, 0.43f, 0.43f, 1.0f,  1.0f),
    CLAY      (0.60f, 0.60f, 0.70f, 1.0f,  1.2f),
    ICE       (0.65f, 0.80f, 0.95f, 0.85f, 1.5f), // Slightly transparent ice
    OAK_LOG   (0.35f, 0.23f, 0.12f, 1.0f,  2.0f),
    OAK_LEAVES(0.18f, 0.48f, 0.15f, 0.90f, 0.2f); // Slightly transparent leaves

    public final float r, g, b, a;
    public final float hardness;

    Block(float r, float g, float b, float a, float hardness) {
        this.r = r; this.g = g; this.b = b; this.a = a;
        this.hardness = hardness;
    }

    public boolean isSolid()  { return this != AIR && this != WATER; }
    public boolean isLiquid() { return this == WATER; }
    public boolean isOpaque() { return this.a >= 1.0f; }
}