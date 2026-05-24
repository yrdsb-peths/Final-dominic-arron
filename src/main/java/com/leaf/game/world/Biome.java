package com.leaf.game.world;

/**
 * All biomes in the world. To add a new one:
 *   1. Add the enum constant here with a comment.
 *   2. Wire it into WorldGen.selectBiome().
 *   3. Override surfaceBlock() / subSurfaceBlock() if needed.
 *
 * Height shaping per biome (flatness boosts, PV scaling, etc.)
 * will live in WorldGen.biomeHeightMod() — added when biome terrain
 * sculpting is implemented.
 */
public enum Biome {

    // ── AQUATIC ───────────────────────────────────────────────────────────────
    OCEAN,           // deep or shallow basin below sea level
    BEACH,           // narrow coastal strip straddling sea level

    // ── TRANSITIONAL ─────────────────────────────────────────────────────────
    RIVER,           // carved channel — always has water post-pass

    // ── HOT (temp > 0.40) ────────────────────────────────────────────────────
    DESERT,          // hot + dry  — sand dunes, no life
    SAVANNA,         // hot + moist — flat warm grassland

    // ── TEMPERATE (temp 0.0 → 0.40) ──────────────────────────────────────────
    PLAINS,          // warm + dry  — open grassy flatlands
    FOREST,          // warm + wet  — rolling hills, dense trees (future)

    // ── COLD (temp -0.30 → 0.0) ──────────────────────────────────────────────
    TAIGA,           // cold boreal — dark grass, pines (future)

    // ── FROZEN (temp < -0.30) ─────────────────────────────────────────────────
    TUNDRA,          // frozen + wet  — patchy snow over dirt
    SNOWY_PLAINS,    // frozen + dry  — white plains, stone subsurface

    // ── ALTITUDE OVERRIDE (any temp, ty >= snowAltitude) ─────────────────────
    ICY_PEAKS;       // forced by elevation — snow capping stone

    // ─────────────────────────────────────────────────────────────────────────

    /** Top-most visible block at terrain surface. */
    public Block surfaceBlock() {
        return switch (this) {
            case DESERT, BEACH, RIVER       -> Block.SAND;
            case TUNDRA, SNOWY_PLAINS,
                 ICY_PEAKS                  -> Block.SNOW;
            default                         -> Block.GRASS;
        };
    }

    /**
     * The 3 layers of subsurface material below the surface block.
     * Everything deeper is always STONE.
     */
    public Block subSurfaceBlock() {
        return switch (this) {
            case DESERT, BEACH, RIVER       -> Block.SAND;
            case ICY_PEAKS, SNOWY_PLAINS    -> Block.STONE;
            default                         -> Block.DIRT;
        };
    }
}