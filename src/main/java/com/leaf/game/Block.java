package com.leaf.game;
public enum Block {

    AIR  (0.0f, 0.0f, 0.0f,  0.0f),   // hardness 0 = instant (never called, it's air)
    GRASS(0.3f, 0.7f, 0.2f,  0.8f),   // grass breaks fast
    DIRT (0.5f, 0.3f, 0.1f,  1.0f),   // dirt — normal speed
    STONE(0.5f, 0.5f, 0.5f,  4.0f);   // stone — takes 4 seconds with bare hands

    public final float r, g, b;
    public final float hardness;

    Block(float r, float g, float b, float hardness) {
        this.r = r;
        this.g = g;
        this.b = b;
        this.hardness = hardness;
    }

    public boolean isSolid() { return this != AIR; }
}