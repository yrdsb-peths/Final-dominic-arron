package com.leaf.game.world;

public enum Block {
    AIR  (0.00f, 0.00f, 0.00f,  0.0f),
    GRASS(0.30f, 0.70f, 0.20f,  0.8f),
    DIRT (0.50f, 0.30f, 0.10f,  1.0f),
    STONE(0.50f, 0.50f, 0.50f,  4.0f),
    WATER(0.10f, 0.42f, 0.80f,  0.0f),
    SAND (0.80f, 0.72f, 0.45f,  0.9f),
    SNOW (0.88f, 0.93f, 0.96f,  0.5f);

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