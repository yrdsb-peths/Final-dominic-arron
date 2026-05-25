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
    OAK_LEAVES(0.18f, 0.48f, 0.15f, 0.90f, 0.2f), // Slightly transparent leaves
    // ── Sky Islands ──────────────────────────────────────────────
    ANCIENT_SOIL    (0.18f, 0.15f, 0.12f, 1.0f,  0.8f),
    HANGING_ROOT    (0.28f, 0.20f, 0.12f, 0.70f, 0.1f),
    ISLAND_STONE    (0.42f, 0.46f, 0.40f, 1.0f,  3.5f),

    // ── Fossils ──────────────────────────────────────────────────
    BONE            (0.91f, 0.87f, 0.78f, 1.0f,  2.5f),
    FOSSIL_STONE    (0.55f, 0.52f, 0.48f, 1.0f,  3.8f),
    ANCIENT_MARROW  (0.38f, 0.27f, 0.18f, 1.0f,  1.5f),

    // ── Crystal Spires ───────────────────────────────────────────
    CRYSTAL_AMETHYST(0.60f, 0.30f, 0.85f, 0.80f, 3.0f),
    CRYSTAL_QUARTZ  (0.90f, 0.90f, 0.95f, 0.75f, 2.5f),
    CRYSTAL_CITRINE (0.95f, 0.78f, 0.20f, 0.80f, 2.5f),
    CRYSTAL_ROSE    (0.90f, 0.45f, 0.55f, 0.80f, 2.5f),
    CRYSTAL_BASE    (0.35f, 0.20f, 0.50f, 1.0f,  4.0f),

    // ── Megaliths ────────────────────────────────────────────────
    MEGALITH        (0.35f, 0.33f, 0.40f, 1.0f,  6.0f),
    MEGALITH_CARVED (0.45f, 0.43f, 0.50f, 1.0f,  6.0f),
    MOSSY_MEGALITH  (0.32f, 0.40f, 0.32f, 1.0f,  5.5f),

    // ── Petrified Forest ─────────────────────────────────────────
    PETRIFIED_WOOD  (0.52f, 0.47f, 0.40f, 1.0f,  4.5f),
    PETRIFIED_BARK  (0.38f, 0.34f, 0.28f, 1.0f,  4.5f),
    STONE_LICHEN    (0.58f, 0.62f, 0.52f, 0.85f, 0.5f),

    // ── Starfall Craters ─────────────────────────────────────────
    IMPACT_GLASS    (0.62f, 0.78f, 0.58f, 0.70f, 2.0f),
    SCORCHED_STONE  (0.22f, 0.20f, 0.20f, 1.0f,  4.0f),
    STAR_IRON       (0.28f, 0.32f, 0.48f, 1.0f,  8.0f),
    CRATER_BLOOM    (0.80f, 0.65f, 0.90f, 0.90f, 0.1f);

    public final float r, g, b, a;
    public final float hardness;

    Block(float r, float g, float b, float a, float hardness) {
        this.r = r; this.g = g; this.b = b; this.a = a;
        this.hardness = hardness;
    }

    public boolean isSolid() {
        // HANGING_ROOT and CRATER_BLOOM are decorative; the player walks through them
        return this != AIR && this != WATER && this != HANGING_ROOT && this != CRATER_BLOOM;
    }
    public boolean isLiquid() { return this == WATER; }
    public boolean isOpaque() { return this.a >= 1.0f; }
}