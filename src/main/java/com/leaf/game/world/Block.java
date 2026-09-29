package com.leaf.game.world;

public enum Block {
    AIR       (0.00f, 0.00f, 0.00f, 0.0f,  0.0f),
    GRASS     (1.00f, 1.00f, 1.00f, 1.0f,  0.8f,  "grass",       true),  // directional: green top, stripe sides, dirt bottom
    DIRT      (1.00f, 1.00f, 1.00f, 1.0f,  1.0f,  "cracked_dirt",true),  // seamless cracked-soil tile
    MUD       (0.32f, 0.18f, 0.04f, 1.0f,  0.5f), // Quagmire — dark bog mud, slows enemies forever
    GATLING_GUN(0.16f, 0.16f, 0.20f, 1.0f, 99f), // held WEAPON item — never placed/mined; hold LMB to rip
    GRAPPLING_HOOK(0.15f, 0.70f, 0.25f, 1.0f, 99f),
    TELESCOPE  (0.20f, 0.20f, 0.20f, 1.0f, 99f),
    TORCH      (1.00f, 0.66f, 0.26f, 1.0f, 0.3f), // warm light source: place it, or hold it for a hand-light
    // ── Voyage materials (collected from biomes to forge weapons) ─────────
    MAT_CRYSTAL_CORE(0.65f, 0.35f, 0.95f, 1.0f, 99f), // Crystal Fields
    MAT_AETHER_SHARD(0.55f, 0.85f, 1.00f, 1.0f, 99f), // Sky Islands
    MAT_GLOW_SPORE  (0.30f, 0.95f, 0.70f, 1.0f, 99f), // Mushroom groves
    MAT_MOLTEN_CORE (1.00f, 0.45f, 0.12f, 1.0f, 99f), // Volcanic tower
    // ── Ability weapons (equip to hotbar, RMB to fire) ────────────────────
    WPN_ORBITAL    (0.90f, 0.60f, 0.15f, 1.0f, 99f), // Orbital Annihilation — RMB fires
    WPN_SNIPER     (0.40f, 0.20f, 0.85f, 1.0f, 99f), // Sniper — hold RMB to charge
    WPN_TIMESTOP   (0.15f, 0.75f, 0.90f, 1.0f, 99f), // Time Domain — RMB toggles
    WPN_STONE_CANNON(0.55f, 0.45f, 0.35f, 1.0f, 99f), // Stone Cannon — hold RMB to charge
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
    CRATER_BLOOM    (0.80f, 0.65f, 0.90f, 0.90f, 0.1f),

    // ── Mesa / Canyon (gelami Shadertoy palette) ─────────────────
    // Warm tan sandstone strata, vivid grass tops, pale sand shores, turquoise
    // pools — tuned to the sample. Flat vertex colours for now.
    MESA_GRASS      (0.48f, 0.80f, 0.18f, 1.0f,  0.8f),  // vivid green top (shader 0.55,1,0.1)
    MESA_DIRT       (0.55f, 0.40f, 0.30f, 1.0f,  0.8f),  // warm soil under grass
    MESA_CLAY       (0.80f, 0.62f, 0.52f, 1.0f,  1.2f),  // dominant warm tan band
    MESA_TERRACOTTA (0.66f, 0.45f, 0.36f, 1.0f,  1.4f),  // muted darker strata band
    MESA_SAND       (0.88f, 0.78f, 0.62f, 1.0f,  0.9f),  // pale light band / shoreline
    MESA_STONE      (0.58f, 0.46f, 0.42f, 1.0f,  4.0f),  // deep foundation rock
    MESA_WATER      (0.30f, 0.85f, 0.85f, 0.72f, 0.0f),  // turquoise pool (shader water ~0.5,1,1)

    // ── Blue / Snow mesa (Shadertoy snow biome — dcol = vec3(0.2,0.6,0.8)) ──
    MESA_BLUE_SNOW  (0.88f, 0.94f, 1.00f, 1.0f,  0.8f),  // white-blue snow top
    MESA_BLUE_SOIL  (0.45f, 0.62f, 0.80f, 1.0f,  0.8f),  // sub-snow blue soil
    MESA_BLUE_LIGHT (0.60f, 0.78f, 0.92f, 1.0f,  1.0f),  // light blue band
    MESA_BLUE_MID   (0.28f, 0.52f, 0.72f, 1.0f,  1.2f),  // mid blue rock (dominant)
    MESA_BLUE_DARK  (0.18f, 0.36f, 0.56f, 1.0f,  1.4f),  // deep blue band
    MESA_BLUE_STONE (0.22f, 0.32f, 0.48f, 1.0f,  4.0f),  // blue foundation

    // ── FLAPPY BIRD CLASSIC GREEN PIPES ──
    PIPE_BODY       (0.43f, 0.78f, 0.08f, 1.0f,  4.0f),  // Rich shaded green pipe column
    PIPE_LIP        (0.52f, 0.86f, 0.12f, 1.0f,  4.0f),  // Brighter highlighted pipe collar
    LAVA            (1.00f, 0.22f, 0.05f, 0.90f, 0.0f),  // Liquid glowing red/orange lava

    // ── Volcanic Ashlands ────────────────────────────────────────
    // Black basalt foundations, glowing magma veins, glassy obsidian, soft ash.
    BASALT          (0.16f, 0.15f, 0.18f, 1.0f,  4.5f),  // dark columnar volcanic rock
    SMOOTH_BASALT   (0.11f, 0.10f, 0.13f, 1.0f,  4.5f),  // polished darker basalt
    MAGMA           (0.98f, 0.42f, 0.10f, 1.0f,  3.0f),  // glowing orange crust (walkable, hot)
    OBSIDIAN        (0.10f, 0.07f, 0.17f, 1.0f,  8.0f),  // glassy near-black violet
    ASH             (0.42f, 0.40f, 0.40f, 1.0f,  0.5f),  // soft grey ashfall
    EMBER_ROCK      (0.52f, 0.20f, 0.11f, 1.0f,  3.5f),  // dark red rock veined with embers
    CHARRED_LOG     (0.14f, 0.12f, 0.11f, 1.0f,  2.0f),  // burnt blackened wood

    // ── Sakura Grove ─────────────────────────────────────────────
    // Pastel pink canopy, mauve bark, petal carpet — calm and anime-flavoured.
    SAKURA_GRASS    (0.96f, 0.70f, 0.80f, 1.0f,  0.8f),  // pale pink grass
    SAKURA_SOIL     (0.45f, 0.32f, 0.34f, 1.0f,  0.8f),  // pinkish-brown soil
    SAKURA_LOG      (0.40f, 0.26f, 0.30f, 1.0f,  2.0f),  // dark mauve bark
    SAKURA_LEAVES   (1.00f, 0.72f, 0.86f, 0.90f, 0.2f),  // pink blossom canopy (translucent)
    PINK_PETALS     (1.00f, 0.80f, 0.90f, 0.85f, 0.1f),  // drifting petal carpet (decorative)

    // ── Bioluminescent Mushroom Forest ───────────────────────────
    // Purple mycelium floor, cream stems, glowing caps that light the night.
    MYCELIUM        (0.38f, 0.30f, 0.45f, 1.0f,  0.8f),  // purple-grey fungal floor
    MUSHROOM_STEM   (0.90f, 0.88f, 0.80f, 1.0f,  1.0f),  // cream mushroom stem
    GLOWCAP_RED     (1.00f, 0.28f, 0.30f, 1.0f,  0.4f),  // glowing red cap
    GLOWCAP_TEAL    (0.20f, 0.95f, 0.85f, 1.0f,  0.4f),  // glowing teal cap
    GLOWCAP_BLUE    (0.35f, 0.55f, 1.00f, 1.0f,  0.4f),  // glowing blue cap
    GLOW_LICHEN     (0.55f, 1.00f, 0.70f, 0.85f, 0.2f),  // glowing lichen (decorative)

    // ── Amethyst Crystal Fields ──────────────────────────────────
    // Lavender meadows studded with geodes (cores reuse the CRYSTAL_* blocks).
    AMETHYST_GRASS  (0.62f, 0.52f, 0.80f, 1.0f,  0.8f),  // lavender grass
    CRYSTAL_SOIL    (0.30f, 0.24f, 0.40f, 1.0f,  0.9f),  // dark purple soil
    GEODE_SHELL     (0.40f, 0.36f, 0.46f, 1.0f,  4.0f),  // rocky geode outer shell

    // ── Autumn Maple Woods ───────────────────────────────────────
    // Warm golden-brown grass and maples blazing red/gold/orange.
    AUTUMN_GRASS    (0.62f, 0.52f, 0.22f, 1.0f,  0.8f),  // golden-brown grass
    MAPLE_LOG       (0.36f, 0.24f, 0.16f, 1.0f,  2.0f),  // warm brown bark
    MAPLE_LEAVES_RED   (0.78f, 0.18f, 0.12f, 0.90f, 0.2f),  // crimson canopy
    MAPLE_LEAVES_GOLD  (0.90f, 0.70f, 0.16f, 0.90f, 0.2f),  // gold canopy
    MAPLE_LEAVES_ORANGE(0.88f, 0.45f, 0.12f, 0.90f, 0.2f),  // orange canopy

    // ── Pickups (appended LAST — chunk saves store ordinals) ─────
    HOTDOG          (0.78f, 0.32f, 0.12f, 1.0f, 99f),  // heal pickup dropped by enemies
    // (appended after HOTDOG to keep saved ordinals stable)
    WPN_DEPRIVATION (0.95f, 0.78f, 0.20f, 1.0f, 99f);  // Deprivation Domain — golden hemisphere, LMB toggles
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
        // HANGING_ROOT, CRATER_BLOOM, PINK_PETALS, GLOW_LICHEN are decorative —
        // the player walks through them.
        return this != AIR && this != WATER && this != MESA_WATER
                && this != HANGING_ROOT && this != CRATER_BLOOM
                && this != PINK_PETALS && this != GLOW_LICHEN;
    }
    public boolean isLiquid() { return this == WATER || this == MESA_WATER || this == LAVA; }

    // ── Surface styles for untextured blocks ─────────────────────────────────
    // Blocks without a PNG used to render as one flat colour. The terrain shader
    // now paints procedural pixel-art detail on them, chosen by this style id
    // (the mesher packs it into the unused UV channel). Keep these ids in sync
    // with the STYLE_* constants in fragment.glsl.
    public static final int STYLE_ROCK    = 0;  // grainy stone: pixel grain + mineral blotches
    public static final int STYLE_SOFT    = 1;  // sand / soil / ash / grass tops: fine low-contrast grain
    public static final int STYLE_FOLIAGE = 2;  // leaves & canopy: clumpy high-contrast speckle
    public static final int STYLE_WOOD    = 3;  // logs: bark grooves on sides, growth rings on top
    public static final int STYLE_WATER   = 4;  // animated ripples, fresnel sky reflection, sun glint
    public static final int STYLE_GLOW    = 5;  // self-lit: lava, magma, glowcaps (pulses, blooms)
    public static final int STYLE_CRYSTAL = 6;  // ice & crystals: smooth facets with an inner gleam
    public static final int STYLE_PLAIN   = 7;  // man-made / items: untouched flat colour
    public static final int STYLE_BIOLUM  = 8;  // glowcaps & lichen: lit by day, glowing at night

    /** Which procedural surface the terrain shader paints on this (untextured) block. */
    public int surfaceStyle() {
        return switch (this) {
            case SAND, RED_SAND, ASH, MUD, CLAY, ANCIENT_SOIL, MESA_GRASS, MESA_DIRT, MESA_SAND,
                 MESA_BLUE_SNOW, MESA_BLUE_SOIL, SAKURA_GRASS, SAKURA_SOIL, MYCELIUM,
                 AMETHYST_GRASS, CRYSTAL_SOIL, AUTUMN_GRASS, PINK_PETALS, CRATER_BLOOM,
                 STONE_LICHEN -> STYLE_SOFT;
            case OAK_LEAVES, SAKURA_LEAVES, MAPLE_LEAVES_RED, MAPLE_LEAVES_GOLD,
                 MAPLE_LEAVES_ORANGE, HANGING_ROOT -> STYLE_FOLIAGE;
            case OAK_LOG, SAKURA_LOG, MAPLE_LOG, CHARRED_LOG, PETRIFIED_WOOD, PETRIFIED_BARK,
                 MUSHROOM_STEM -> STYLE_WOOD;
            case WATER, MESA_WATER -> STYLE_WATER;
            case LAVA, MAGMA -> STYLE_GLOW;
            case GLOWCAP_RED, GLOWCAP_TEAL, GLOWCAP_BLUE, GLOW_LICHEN -> STYLE_BIOLUM;
            case ICE, CRYSTAL_AMETHYST, CRYSTAL_QUARTZ, CRYSTAL_CITRINE, CRYSTAL_ROSE,
                 IMPACT_GLASS, OBSIDIAN -> STYLE_CRYSTAL;
            case PIPE_BODY, PIPE_LIP, TORCH, HOTDOG, GATLING_GUN, GRAPPLING_HOOK, TELESCOPE,
                 MAT_CRYSTAL_CORE, MAT_AETHER_SHARD, MAT_GLOW_SPORE, MAT_MOLTEN_CORE,
                 WPN_ORBITAL, WPN_SNIPER, WPN_TIMESTOP, WPN_STONE_CANNON, WPN_DEPRIVATION,
                 AIR -> STYLE_PLAIN;
            default -> STYLE_ROCK;
        };
    }
    public boolean isOpaque() { return this.a >= 1.0f; }
}
