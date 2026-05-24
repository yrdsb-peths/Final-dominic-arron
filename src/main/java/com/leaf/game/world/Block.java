package com.leaf.game.world;

public enum Block {
    AIR  (0.00f, 0.00f, 0.00f,  0.0f),
    GRASS(0.30f, 0.70f, 0.20f,  0.8f),
    DIRT (0.50f, 0.30f, 0.10f,  1.0f),
    STONE(0.50f, 0.50f, 0.50f,  4.0f),
    WATER(0.10f, 0.42f, 0.80f,  0.0f),
    SAND (0.80f, 0.72f, 0.45f,  0.9f),
    SNOW (0.88f, 0.93f, 0.96f,  0.5f),

    // New blocks for rich biome textures
    RED_SAND(0.76f, 0.38f, 0.22f, 0.9f),
    GRAVEL  (0.45f, 0.43f, 0.43f, 1.0f),
    CLAY    (0.60f, 0.60f, 0.70f, 1.2f),
    ICE     (0.65f, 0.80f, 0.95f, 1.5f),
    OAK_LOG (0.35f, 0.23f, 0.12f, 2.0f),
    OAK_LEAVES(0.18f, 0.48f, 0.15f, 0.2f);

    public final float r, g, b;
    public final float hardness;

    Block(float r, float g, float b, float hardness) {
        this.r = r; this.g = g; this.b = b;
        this.hardness = hardness;
    }

    /** Water is passable — physics & buoyancy handled by Player, not collision. */
    public boolean isSolid()  { return this != AIR && this != WATER; }
    public boolean isLiquid() { return this == WATER; }
}