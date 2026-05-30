package com.leaf.game.world;

public enum Block {
    AIR       (0.00f, 0.00f, 0.00f, 0.0f,  0.0f),
    GRASS     (1.00f, 1.00f, 1.00f, 1.0f,  0.8f,  "grass",       true),  // directional: green top, stripe sides, dirt bottom
    DIRT      (1.00f, 1.00f, 1.00f, 1.0f,  1.0f,  "cracked_dirt",true),  // seamless cracked-soil tile
    MUD       (0.32f, 0.18f, 0.04f, 1.0f,  0.5f), // Quagmire — dark bog mud, slows enemies forever
    STONE     (1.00f, 1.00f, 1.00f, 1.0f,  4.0f,  "normalStone", true),  // seamless; all abilities/worldgen use Block.STONE so texture swap is automatic
    WATER     (0.10f, 0.42f, 0.80f, 0.65f, 0.0f), // 65% Opacity!
    SAND      (0.80f, 0.72f, 0.45f, 1.0f,  0.9f),
    SNOW      (1.00f, 1.00f, 1.00f, 1.0f,  0.5f,  "snow", true),  // seamless; falls back to white until snow.png is drawn
    RED_SAND  (0.76f, 0.38f, 0.22f, 1.0f,  0.9f),
    GRAVEL    (1.00f, 1.00f, 1.00f, 1.0f,  1.0f,  "cobblestone", true),  // seamless cobblestone tile
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

    /**
     * Name of the PNG file for this block (without extension).
     * The file lives at:  src/main/resources/textures/blocks/<texName>.png
     *
     * TWO supported sizes:
     *   48 × 64  — 6 unique faces in a cube-net (cross) layout:
     *                   [front ]   ← row 0, col 1
     *               [L ][ TOP  ][R ]  ← row 1  (TOP in centre)
     *                   [bottom]   ← row 2, col 1
     *                   [ back ]   ← row 3, col 1
     *   16 × 16  — single tile, same on all 6 faces.
     *
     * null = no texture → block renders as its flat vertex colour.
     *
     * Examples:
     *   STONE (1,1,1, 1, hard, "stone")   ← stone.png (48×64 cross)
     *   GRASS (1,1,1, 1, hard, "grass")   ← grass.png (48×64 cross)
     *   DIRT  (r,g,b, a, hard)            ← no PNG, flat colour only
     */
    public final String  texName;

    /**
     * When true, UV rotation and side-face shuffling are disabled for this block.
     *
     * Use this for two kinds of blocks:
     *
     *  • SEAMLESS tiles (cobblestone-style): a tile whose left/right and top/bottom
     *    edges match up. Rotating it would break the seam with the non-rotated
     *    neighbour and make block boundaries reappear. Leave rotation OFF and the
     *    tile flows across the wall with no visible grid.
     *
     *  • DIRECTIONAL faces (grass, logs): faces where orientation matters —
     *    e.g. grass side has green at top and brown at bottom. Rotating that
     *    90° would put the green strip sideways. Shuffling would show the top-face
     *    art on a side wall. Both look wrong, so disable both.
     *
     * Default false = current behaviour (random UV rotation + side-face shuffle).
     * Set true in the Block() call once you've drawn a seamless or directional tile.
     *
     * Examples:
     *   STONE  (..., "stone",  true)   ← seamless cobble-style tile
     *   GRASS  (..., "grass",  true)   ← directional (green top, brown bottom)
     *   OAK_LOG(..., "oak_log",true)   ← directional (ring top, bark sides)
     */
    public final boolean seamless;

    /** No texture — renders as flat vertex colour. */
    Block(float r, float g, float b, float a, float hardness) {
        this.r = r; this.g = g; this.b = b; this.a = a;
        this.hardness = hardness;
        this.texName  = null;
        this.seamless = false;
    }

    /** Textured block — rotation and face-shuffle enabled by default. */
    Block(float r, float g, float b, float a, float hardness, String texName) {
        this.r = r; this.g = g; this.b = b; this.a = a;
        this.hardness = hardness;
        this.texName  = texName;
        this.seamless = false;
    }

    /** Textured block with explicit seamless/directional flag. */
    Block(float r, float g, float b, float a, float hardness, String texName, boolean seamless) {
        this.r = r; this.g = g; this.b = b; this.a = a;
        this.hardness = hardness;
        this.texName  = texName;
        this.seamless = seamless;
    }

    /**
     * Returns which face index (0–5) corresponds to normal (nx, ny, nz).
     *   0 = top    (ny > 0)
     *   1 = bottom (ny < 0)
     *   2 = front  (nz > 0)   — cross row 1, col 1
     *   3 = back   (nz < 0)   — cross row 3, col 1
     *   4 = right  (nx > 0)   — cross row 1, col 2
     *   5 = left   (nx < 0)   — cross row 1, col 0
     */
    public int getFaceIndex(float nx, float ny, float nz) {
        if (ny >  0.5f) return 0;
        if (ny < -0.5f) return 1;
        if (nz >  0.5f) return 2;
        if (nz < -0.5f) return 3;
        if (nx >  0.5f) return 4;
        return 5;
    }

    public boolean isSolid() {
        // HANGING_ROOT and CRATER_BLOOM are decorative; the player walks through them
        return this != AIR && this != WATER && this != HANGING_ROOT && this != CRATER_BLOOM;
    }
    public boolean isLiquid() { return this == WATER; }
    public boolean isOpaque() { return this.a >= 1.0f; }
}
