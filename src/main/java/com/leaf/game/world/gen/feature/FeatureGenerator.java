package com.leaf.game.world.gen.feature;

import com.leaf.game.util.Noise;
import com.leaf.game.world.Block;
import com.leaf.game.world.Chunk;
import com.leaf.game.world.gen.WorldGen;

import java.util.ArrayList;
import java.util.List;

/**
 * Post-terrain world feature generator.
 *
 * Handles six visual spectacle systems — applied at the end of surface chunk
 * generation after all terrain blocks are placed:
 *
 *   ① Starfall Craters   — rare bowl-shaped impacts with scorched rims
 *   ② Sky Islands        — floating terrain chunks with waterfalls
 *   ③ Crystal Spires     — semi-transparent crystalline columns
 *   ④ Petrified Forest   — enormous stone trees in a dedicated zone
 *   ⑤ Giant Fossils      — colossal skeletal remains half-buried in ground
 *   ⑥ Wizard Megaliths   — summoning circles and arcane obelisks
 *
 * ── TWO PLACEMENT STRATEGIES ────────────────────────────────────────────────
 *
 * Column-noise (crystals, petrified forest):
 *   Each column evaluated independently. Naturally seamless across borders.
 *   Minor one-block cross-border truncation is acceptable.
 *
 * Region-hash (craters, islands, fossils, megaliths):
 *   The world is divided into regions of fixed size. Each region deterministically
 *   contains at most one instance of each feature type, placed via splitmix64
 *   hashing of (seed, regionX, regionZ, featureType). When generating a chunk we
 *   scan all regions whose feature radius could overlap this chunk and apply only
 *   the blocks that fall within our chunk's XZ bounds.
 *   → guarantees sparse, non-clustered distribution with zero chunk-border seams.
 *
 * ── INTEGRATION ─────────────────────────────────────────────────────────────
 *   WorldGen.generateSurfaceChunk() calls features.applyFeatures(chunk, this)
 *   as its final step. Deep abyss chunks (cy < 0) are skipped.
 */
public class FeatureGenerator {

    // ── Feature-type discriminators for hash mixing ───────────────────────────
    private static final int FT_CRATER   = 1;
    private static final int FT_ISLAND   = 2;
    private static final int FT_FOSSIL   = 3;
    private static final int FT_MEGALITH = 4;

    // ── Region sizes (world blocks per region side) ───────────────────────────
    private static final int CRATER_REGION   = 1600;  // ~1 crater  per 1600×1600
    private static final int ISLAND_REGION   = 256;   // ~1 cluster per 256×256
    private static final int FOSSIL_REGION   = 800;   // ~1 fossil  per 800×800
    private static final int MEGALITH_REGION = 512;   // ~1 site    per 512×512

    // ── Max radius a feature can reach from its region-center point ───────────
    private static final int CRATER_MAX_R   = 42;
    private static final int ISLAND_MAX_R   = 56;
    private static final int FOSSIL_MAX_R   = 40;
    private static final int MEGALITH_MAX_R = 22;

    // ── Column-noise thresholds ───────────────────────────────────────────────
    private static final float CRYSTAL_THRESHOLD    = 0.58f; // top ~5 % of values
    private static final float PETRIFIED_THRESHOLD  = 0.38f; // top ~12%

    private final long  seed;
    private final Noise crystalNoise;    // Crystal spires zone
    private final Noise petrifiedNoise;  // Petrified forest zone

    public FeatureGenerator(long seed) {
        this.seed         = seed;
        crystalNoise   = new Noise(seed + 50_000L);
        petrifiedNoise = new Noise(seed + 51_000L);
    }

    // =========================================================================
    //  PUBLIC ENTRY POINT
    // =========================================================================

    /**
     * Apply all features to a surface chunk. Must be called after all terrain
     * blocks are set; reads and overwrites chunk blocks freely.
     */
    public void applyFeatures(Chunk chunk, WorldGen worldGen) {
        if (chunk.cy != 0) return; // surface world only

        // Phase 1 — subtractive first so later additive features aren't clipped
        applyCraters(chunk, worldGen);

        // Phase 2 — additive floating land
        applySkyIslands(chunk, worldGen);

        // Phase 3 — column-noise surface features
        applyCrystalSpires(chunk, worldGen);
        applyPetrifiedForest(chunk, worldGen);

        // Phase 4 — template-paste point features
        applyFossils(chunk, worldGen);
        applyMegaliths(chunk, worldGen);
    }

    // =========================================================================
    //  ① STARFALL CRATERS
    // =========================================================================

    private void applyCraters(Chunk chunk, WorldGen worldGen) {
        int worldX = chunk.cx * Chunk.SIZE;
        int worldZ = chunk.cz * Chunk.SIZE;

        forNearbyRegions(worldX, worldZ, CRATER_REGION, CRATER_MAX_R, (rx, rz) -> {
            long rng = regionHash(seed, rx, rz, FT_CRATER);
            if ((rng & 0xFFL) > 127L) return;   // ~50 % of regions have a crater

            rng = nextRng(rng);
            int cX = rx * CRATER_REGION + (int)((rng >>> 1) % CRATER_REGION);
            rng = nextRng(rng);
            int cZ = rz * CRATER_REGION + (int)((rng >>> 1) % CRATER_REGION);
            rng = nextRng(rng);
            // 0 = small (r=9)  1 = medium (r=18)  2 = large (r=35)
            int sizeRoll = (int)((rng >>> 1) % 3);
            int radius   = sizeRoll == 0 ? 9 : sizeRoll == 1 ? 18 : 35;

            for (int lx = 0; lx < Chunk.SIZE; lx++) {
                for (int lz = 0; lz < Chunk.SIZE; lz++) {
                    float dx   = (worldX + lx) - cX;
                    float dz   = (worldZ + lz) - cZ;
                    float dist = (float) Math.sqrt(dx * dx + dz * dz);
                    if (dist > radius + 7) continue;

                    int sy = surfaceY(chunk, lx, lz);

                    if (dist <= radius) {
                        // Parabolic bowl scoop
                        float t     = dist / radius;
                        int   depth = (int)(radius * 0.60f * (1f - t * t));
                        int   floor = Math.max(2, sy - depth);
                        for (int ly = floor + 1; ly <= sy; ly++) chunk.setBlock(lx, ly, lz, Block.AIR);

                        // Floor material — star iron at centre, glass band, scorched rim
                        if (dist < 3f && sizeRoll >= 1) {
                            chunk.setBlock(lx, floor,     lz, Block.STAR_IRON);
                            if (floor > 1) chunk.setBlock(lx, floor - 1, lz, Block.STAR_IRON);
                        } else if (dist < radius * 0.75f) {
                            chunk.setBlock(lx, floor, lz, Block.IMPACT_GLASS);
                        } else {
                            chunk.setBlock(lx, floor, lz, Block.SCORCHED_STONE);
                        }

                        // Crater bloom — sparse violet flowers on inner slope
                        long bSeed = regionHash(seed ^ 0xCAFEL, worldX + lx, worldZ + lz, 0);
                        if ((bSeed & 0xFL) < 2L && dist > 4f) {
                            setY(chunk, lx, floor + 1, lz, Block.CRATER_BLOOM);
                        }

                    } else {
                        // Raised scorched rim
                        float rimT = 1f - (dist - radius) / 7f;
                        int   rimH = (int)(rimT * rimT * 5f);
                        if (rimH < 1) continue;
                        chunk.setBlock(lx, sy, lz, Block.SCORCHED_STONE);
                        for (int h = 1; h <= rimH; h++) setY(chunk, lx, sy + h, lz, Block.SCORCHED_STONE);
                    }
                }
            }
        });
    }

    // =========================================================================
    //  ② SKY ISLANDS
    // =========================================================================

    private void applySkyIslands(Chunk chunk, WorldGen worldGen) {
        int worldX = chunk.cx * Chunk.SIZE;
        int worldZ = chunk.cz * Chunk.SIZE;

        forNearbyRegions(worldX, worldZ, ISLAND_REGION, ISLAND_MAX_R, (rx, rz) -> {
            long rng = regionHash(seed, rx, rz, FT_ISLAND);
            if ((rng & 0xFFL) > 89L) return;   // ~35 % of regions get an island cluster

            rng = nextRng(rng);
            int clusterCount = 1 + (int)((rng >>> 1) % 3);   // 1–3 islands per cluster

            for (int ci = 0; ci < clusterCount; ci++) {
                rng = nextRng(rng);
                int iX = rx * ISLAND_REGION + (int)((rng >>> 1) % ISLAND_REGION);
                rng = nextRng(rng);
                int iZ = rz * ISLAND_REGION + (int)((rng >>> 1) % ISLAND_REGION);
                rng = nextRng(rng);
                int radius    = 18 + (int)((rng >>> 1) % 35);  // 18–52 blocks
                rng = nextRng(rng);
                int liftOff   = 65 + (int)((rng >>> 1) % 90);  // 65–154 blocks above ground
                rng = nextRng(rng);
                int thickness = 9  + (int)((rng >>> 1) % 13);  // 9–21 blocks tall

                for (int lx = 0; lx < Chunk.SIZE; lx++) {
                    for (int lz = 0; lz < Chunk.SIZE; lz++) {
                        float dx   = (worldX + lx) - iX;
                        float dz   = (worldZ + lz) - iZ;
                        float dist = (float) Math.sqrt(dx * dx + dz * dz);
                        if (dist >= radius) continue;

                        // No islands over deep ocean
                        if (worldGen.sampleContinentalness(worldX + lx, worldZ + lz) < 0.15f) continue;

                        int groundY = surfaceY(chunk, lx, lz);
                        int islandBase = groundY + liftOff;
                        if (islandBase + thickness >= Chunk.HEIGHT - 4) continue;

                        // Smoothstep radial fade: underside tapers at edges
                        float edgeFade = smoothstep(1f - (dist / radius));
                        int topY    = islandBase + thickness;
                        int bottomY = islandBase + thickness - (int)(thickness * edgeFade);

                        // Island body
                        for (int ly = bottomY; ly <= topY; ly++) {
                            Block b = (ly >= topY - 1) ? Block.ANCIENT_SOIL : Block.ISLAND_STONE;
                            chunk.setBlock(lx, ly, lz, b);
                        }

                        // Hanging roots from underside
                        if (edgeFade > 0.25f) {
                            long rootRng = regionHash(seed, worldX + lx, worldZ + lz, 77);
                            int  rootLen = 2 + (int)((rootRng >>> 1) % 5);
                            for (int r = 1; r <= rootLen; r++) {
                                int ry = bottomY - r;
                                if (ry < 0 || chunk.getBlock(lx, ry, lz) != Block.AIR) break;
                                chunk.setBlock(lx, ry, lz, Block.HANGING_ROOT);
                            }
                        }

                        // Waterfall sources at exposed outer edge (~12 % of columns)
                        if (dist > radius * 0.72f) {
                            long wfRng = regionHash(seed, worldX + lx, worldZ + lz, 88);
                            if ((wfRng & 0x7L) == 0L) setY(chunk, lx, topY + 1, lz, Block.WATER);
                        }
                    }
                }
            }
        });
    }

    // =========================================================================
    //  ③ CRYSTAL SPIRES
    // =========================================================================

    private void applyCrystalSpires(Chunk chunk, WorldGen worldGen) {
        int worldX = chunk.cx * Chunk.SIZE;
        int worldZ = chunk.cz * Chunk.SIZE;

        for (int lx = 0; lx < Chunk.SIZE; lx++) {
            for (int lz = 0; lz < Chunk.SIZE; lz++) {
                int wx = worldX + lx;
                int wz = worldZ + lz;

                // Zone check — top ~5 %
                float cn = crystalNoise.octave(wx * 0.003f, wz * 0.003f, 3, 0.5f);
                if (cn < CRYSTAL_THRESHOLD) continue;

                // No crystals over open ocean
                if (worldGen.sampleContinentalness(wx, wz) < -0.05f) continue;

                int sy = surfaceY(chunk, lx, lz);
                Block ground = chunk.getBlock(lx, sy, lz);
                if (!ground.isSolid() || ground == Block.SAND || ground == Block.GRAVEL) continue;

                // Crystal variety determined by secondary noise
                float typeN = crystalNoise.get(wx * 0.009f, wz * 0.009f);
                Block crystal = typeN < -0.5f ? Block.CRYSTAL_AMETHYST
                        : typeN <  0.0f ? Block.CRYSTAL_QUARTZ
                        : typeN <  0.5f ? Block.CRYSTAL_CITRINE
                        :                  Block.CRYSTAL_ROSE;

                int height = 5 + (int)((cn - CRYSTAL_THRESHOLD) / (1f - CRYSTAL_THRESHOLD) * 28f);
                long spRng = regionHash(seed, wx, wz, 55);
                // Slight lean: -1, 0, or +1 block of total tip offset
                int leanX = (int)((spRng & 0xFFL) % 3L) - 1;

                // Base: 2×2 opaque anchor
                int baseH = Math.max(1, height / 4);
                for (int h = 0; h < baseH; h++) {
                    int ly = sy + 1 + h;
                    place(chunk, lx,   ly, lz,   Block.CRYSTAL_BASE);
                    place(chunk, lx+1, ly, lz,   Block.CRYSTAL_BASE);
                    place(chunk, lx,   ly, lz+1, Block.CRYSTAL_BASE);
                    place(chunk, lx+1, ly, lz+1, Block.CRYSTAL_BASE);
                }
                // Shaft: 1×1 with linear lean toward tip
                for (int h = baseH; h < height; h++) {
                    int ly = sy + 1 + h;
                    if (ly >= Chunk.HEIGHT) break;
                    int px = lx + Math.round((float)leanX * (h - baseH) / Math.max(1, height - baseH));
                    place(chunk, px, ly, lz, crystal);
                }
            }
        }
    }

    // =========================================================================
    //  ④ PETRIFIED FOREST
    // =========================================================================

    private void applyPetrifiedForest(Chunk chunk, WorldGen worldGen) {
        int worldX = chunk.cx * Chunk.SIZE;
        int worldZ = chunk.cz * Chunk.SIZE;

        // Zone check at chunk centre — avoids sampling per-column
        int cx8 = worldX + 8, cz8 = worldZ + 8;
        float petN = petrifiedNoise.octave(cx8 * 0.001f, cz8 * 0.001f, 2, 0.5f);
        if (petN < PETRIFIED_THRESHOLD) return;

        // Temperate zone only
        float temp = worldGen.sampleTemperature(cx8, cz8);
        if (temp < -0.5f || temp > 0.65f) return;

        // Not ocean
        if (worldGen.sampleContinentalness(cx8, cz8) < 0.05f) return;

        long rng = regionHash(seed, chunk.cx, chunk.cz, 66);
        int treeCount = 3 + (int)((rng >>> 1) % 6); // 3–8 trees per chunk

        for (int t = 0; t < treeCount; t++) {
            rng = nextRng(rng);
            int tlx = (int)((rng >>> 1) % Chunk.SIZE);
            rng = nextRng(rng);
            int tlz = (int)((rng >>> 1) % Chunk.SIZE);
            rng = nextRng(rng);
            int treeH = 18 + (int)((rng >>> 1) % 38); // 18–55 blocks
            rng = nextRng(rng);
            boolean fallen = (rng & 0xFL) < 3L;       // ~18 % are fallen

            int sy = surfaceY(chunk, tlx, tlz);
            if (sy <= 0) continue;
            Block ground = chunk.getBlock(tlx, sy, tlz);
            if (!ground.isSolid()) continue;

            if (fallen) {
                // Fallen trunk along +X axis up to chunk edge
                int maxLen = Math.min(treeH / 2, Chunk.SIZE - tlx - 1);
                for (int dx = 0; dx <= maxLen; dx++) {
                    Block b = (dx % 3 == 0) ? Block.PETRIFIED_BARK : Block.PETRIFIED_WOOD;
                    place(chunk, tlx + dx, sy + 1, tlz, b);
                }
                // Stump (vertical, 4 blocks tall)
                for (int h = 1; h <= 4; h++) place(chunk, tlx, sy + h, tlz, Block.PETRIFIED_BARK);
                // Debris scatter around base
                for (int dx = 0; dx < 4; dx++) {
                    long dRng = regionHash(seed, worldX + tlx + dx, worldZ + tlz, 12);
                    if ((dRng & 0x3L) == 0L) place(chunk, tlx + dx, sy + 1, tlz + 1, Block.PETRIFIED_BARK);
                }
            } else {
                // Upright trunk: 2×2 base tapering to 1×1 after bottom third
                int trunkBase = Math.min(treeH / 3, 8);
                for (int h = 0; h < treeH; h++) {
                    int ly = sy + 1 + h;
                    if (ly >= Chunk.HEIGHT) break;
                    Block b = (h % 5 == 0) ? Block.PETRIFIED_BARK : Block.PETRIFIED_WOOD;
                    if (h < trunkBase) {
                        place(chunk, tlx,   ly, tlz,   b);
                        place(chunk, tlx+1, ly, tlz,   b);
                        place(chunk, tlx,   ly, tlz+1, b);
                        place(chunk, tlx+1, ly, tlz+1, b);
                    } else {
                        place(chunk, tlx, ly, tlz, b);
                    }
                    // Stone lichen patches on the trunk surface
                    if (h > trunkBase && h % 4 == 2) {
                        place(chunk, tlx - 1, ly, tlz,     Block.STONE_LICHEN);
                        place(chunk, tlx,     ly, tlz - 1, Block.STONE_LICHEN);
                    }
                }

                // Branches at upper half
                rng = nextRng(rng);
                int branchCount = 2 + (int)((rng >>> 1) % 3);
                int branchStart = treeH / 2;
                for (int b = 0; b < branchCount; b++) {
                    rng = nextRng(rng);
                    int bH  = branchStart + (int)((rng >>> 1) % Math.max(1, treeH - branchStart));
                    rng = nextRng(rng);
                    int dir = (int)((rng >>> 1) % 4); // 0=+X 1=−X 2=+Z 3=−Z
                    rng = nextRng(rng);
                    int bLen = 3 + (int)((rng >>> 1) % 6);
                    int bLy  = sy + 1 + bH;
                    if (bLy >= Chunk.HEIGHT) continue;
                    for (int bi = 1; bi <= bLen; bi++) {
                        int bx = tlx + (dir == 0 ?  bi : dir == 1 ? -bi : 0);
                        int bz = tlz + (dir == 2 ?  bi : dir == 3 ? -bi : 0);
                        int by = bLy + (bi > bLen / 2 ? 1 : 0); // slight upward arc
                        place(chunk, bx, by, bz, Block.PETRIFIED_WOOD);
                    }
                }
            }
        }
    }

    // =========================================================================
    //  ⑤ GIANT FOSSILS
    // =========================================================================

    private void applyFossils(Chunk chunk, WorldGen worldGen) {
        int worldX = chunk.cx * Chunk.SIZE;
        int worldZ = chunk.cz * Chunk.SIZE;

        forNearbyRegions(worldX, worldZ, FOSSIL_REGION, FOSSIL_MAX_R, (rx, rz) -> {
            long rng = regionHash(seed, rx, rz, FT_FOSSIL);
            if ((rng & 0xFFL) > 127L) return;  // ~50 % of regions have a fossil

            rng = nextRng(rng);
            int fX = rx * FOSSIL_REGION + (int)((rng >>> 1) % FOSSIL_REGION);
            rng = nextRng(rng);
            int fZ = rz * FOSSIL_REGION + (int)((rng >>> 1) % FOSSIL_REGION);
            rng = nextRng(rng);
            int fossilType = (int)((rng >>> 1) % 4); // 0=ribcage 1=skull 2=spine 3=femur
            rng = nextRng(rng);
            int rotY = (int)((rng >>> 1) % 4);       // 0°/90°/180°/270°

            // Determine burial Y — use our chunk data if the fossil centre is inside
            int fossY;
            int fLx = fX - worldX;
            int fLz = fZ - worldZ;
            if (fLx >= 0 && fLx < Chunk.SIZE && fLz >= 0 && fLz < Chunk.SIZE) {
                fossY = surfaceY(chunk, fLx, fLz);
            } else {
                fossY = 232; // approximate plains-level fallback
            }
            int buriedY = fossY - 5; // bury ~40 % of the fossil

            int[][] template = getFossilTemplate(fossilType);
            for (int[] e : template) {
                // e = { dx, dy, dz, blockOrdinal }
                int[] rot = rotateXZ(e[0], e[2], rotY);
                int wx = fX + rot[0];
                int wz = fZ + rot[1];
                int lx = wx - worldX;
                int lz = wz - worldZ;
                if (lx < 0 || lx >= Chunk.SIZE || lz < 0 || lz >= Chunk.SIZE) continue;
                int ly = buriedY + e[1];
                if (ly < 1 || ly >= Chunk.HEIGHT) continue;
                Block b = e[3] == 0 ? Block.BONE
                        : e[3] == 1 ? Block.FOSSIL_STONE
                        :              Block.ANCIENT_MARROW;
                chunk.setBlock(lx, ly, lz, b);
            }
        });
    }

    // =========================================================================
    //  ⑥ WIZARD MEGALITHS — summoning circles & arcane obelisks
    // =========================================================================

    private void applyMegaliths(Chunk chunk, WorldGen worldGen) {
        int worldX = chunk.cx * Chunk.SIZE;
        int worldZ = chunk.cz * Chunk.SIZE;

        forNearbyRegions(worldX, worldZ, MEGALITH_REGION, MEGALITH_MAX_R, (rx, rz) -> {
            long rng = regionHash(seed, rx, rz, FT_MEGALITH);
            if ((rng & 0xFFL) > 127L) return;   // ~50 % of regions get a megalith

            rng = nextRng(rng);
            int mX = rx * MEGALITH_REGION + (int)((rng >>> 1) % MEGALITH_REGION);
            rng = nextRng(rng);
            int mZ = rz * MEGALITH_REGION + (int)((rng >>> 1) % MEGALITH_REGION);
            rng = nextRng(rng);
            boolean isRing = (rng & 0x3L) != 0L; // 75 % ring, 25 % lone obelisk

            // Surface Y at megalith centre
            int mLx = mX - worldX, mLz = mZ - worldZ;
            int sy = (mLx >= 0 && mLx < Chunk.SIZE && mLz >= 0 && mLz < Chunk.SIZE)
                    ? surfaceY(chunk, mLx, mLz) : 232;

            if (isRing) {
                // ── Summoning Circle ──────────────────────────────────────────
                rng = nextRng(rng);
                int stoneCount  = 6 + (int)((rng >>> 1) % 7); // 6–12 standing stones
                rng = nextRng(rng);
                int ringRadius  = 8 + (int)((rng >>> 1) % 12); // 8–19 blocks
                rng = nextRng(rng);
                float rotOffset = (float)((rng & 0xFFL) * Math.PI * 2.0 / 256.0);

                // Central altar platform (2×2 raised slab)
                for (int ax = 0; ax <= 1; ax++) {
                    for (int az = 0; az <= 1; az++) {
                        placeW(chunk, worldX, worldZ, mX+ax, sy+1, mZ+az, Block.MEGALITH_CARVED);
                    }
                }
                // Altar capstone — flat lintel across centre
                placeW(chunk, worldX, worldZ, mX,   sy+2, mZ,   Block.MEGALITH_CARVED);
                placeW(chunk, worldX, worldZ, mX+1, sy+2, mZ,   Block.MEGALITH_CARVED);
                // Crystal focus on the altar
                placeW(chunk, worldX, worldZ, mX, sy+3, mZ, Block.CRYSTAL_AMETHYST);

                for (int i = 0; i < stoneCount; i++) {
                    float angle = rotOffset + i * (float)(2.0 * Math.PI / stoneCount);
                    int   sx    = mX + Math.round((float)(ringRadius * Math.cos(angle)));
                    int   sz    = mZ + Math.round((float)(ringRadius * Math.sin(angle)));
                    rng = nextRng(rng);
                    int stoneH = 6 + (int)((rng >>> 1) % 6); // 6–11 blocks

                    int sLx = sx - worldX, sLz = sz - worldZ;
                    int stSy = (sLx >= 0 && sLx < Chunk.SIZE && sLz >= 0 && sLz < Chunk.SIZE)
                            ? surfaceY(chunk, sLx, sLz) : sy;

                    // Standing stone: MEGALITH base → CARVED mid → MOSSY top
                    for (int h = 0; h < stoneH; h++) {
                        Block b = h < stoneH / 3 ? Block.MEGALITH
                                : h < stoneH * 2 / 3 ? Block.MEGALITH_CARVED
                                : Block.MOSSY_MEGALITH;
                        placeW(chunk, worldX, worldZ, sx, stSy + 1 + h, sz, b);
                    }

                    // Lintel capstone on every other stone — arcane arch effect
                    if (i % 2 == 0) {
                        placeW(chunk, worldX, worldZ, sx - 1, stSy + stoneH + 1, sz, Block.MEGALITH_CARVED);
                        placeW(chunk, worldX, worldZ, sx,     stSy + stoneH + 1, sz, Block.MEGALITH_CARVED);
                        placeW(chunk, worldX, worldZ, sx + 1, stSy + stoneH + 1, sz, Block.MEGALITH_CARVED);
                    }

                    // Every 3rd stone gets a crystal — glowing conduit aesthetic
                    if (i % 3 == 0) {
                        rng = nextRng(rng);
                        Block crystal = (rng & 0x3L) == 0L ? Block.CRYSTAL_CITRINE
                                : (rng & 0x3L) == 1L ? Block.CRYSTAL_ROSE
                                : Block.CRYSTAL_AMETHYST;
                        placeW(chunk, worldX, worldZ, sx, stSy + stoneH + 2, sz, crystal);
                    }

                    // Ground runes — carved megalith block at base of each stone
                    placeW(chunk, worldX, worldZ, sx, stSy, sz, Block.MEGALITH_CARVED);
                }

            } else {
                // ── Lone Arcane Obelisk ───────────────────────────────────────
                rng = nextRng(rng);
                int obeliskH = 15 + (int)((rng >>> 1) % 18); // 15–32 blocks

                for (int h = 0; h <= obeliskH; h++) {
                    int ly = sy + 1 + h;
                    if (ly >= Chunk.HEIGHT) break;
                    Block b = h < 4 ? Block.MEGALITH
                            : h < obeliskH * 2 / 3 ? Block.MEGALITH_CARVED
                            : Block.MOSSY_MEGALITH;

                    if (h < obeliskH * 3 / 4) {
                        // 2×2 shaft
                        placeW(chunk, worldX, worldZ, mX,   ly, mZ,   b);
                        placeW(chunk, worldX, worldZ, mX+1, ly, mZ,   b);
                        placeW(chunk, worldX, worldZ, mX,   ly, mZ+1, b);
                        placeW(chunk, worldX, worldZ, mX+1, ly, mZ+1, b);
                    } else {
                        // 1×1 tapering tip
                        placeW(chunk, worldX, worldZ, mX, ly, mZ, Block.MOSSY_MEGALITH);
                    }
                }

                // Runic platform around base (3×3 ring of carved stone)
                for (int dx = -1; dx <= 2; dx++) {
                    for (int dz = -1; dz <= 2; dz++) {
                        if (dx >= 0 && dx <= 1 && dz >= 0 && dz <= 1) continue; // leave base clear
                        placeW(chunk, worldX, worldZ, mX + dx, sy, mZ + dz, Block.MEGALITH_CARVED);
                    }
                }

                // Pinnacle crystal — the arcane focus
                placeW(chunk, worldX, worldZ, mX, sy + obeliskH + 2, mZ, Block.CRYSTAL_AMETHYST);
            }
        });
    }

    // =========================================================================
    //  FOSSIL TEMPLATES  {dx, dy, dz, blockType}  0=BONE 1=FOSSIL_STONE 2=MARROW
    // =========================================================================

    private static final int[][] RIBCAGE = buildRibcage();
    private static final int[][] SKULL   = buildSkull();
    private static final int[][] SPINE   = buildSpine();
    private static final int[][] FEMUR   = buildFemur();

    private static int[][] getFossilTemplate(int type) {
        switch (type) {
            case 0: return RIBCAGE;
            case 1: return SKULL;
            case 2: return SPINE;
            default: return FEMUR;
        }
    }

    /** Ribcage: spine along X, rib pairs arcing in Z/Y. */
    private static int[][] buildRibcage() {
        List<int[]> b = new ArrayList<>();
        for (int x = -8; x <= 8; x++) b.add(new int[]{x, 0, 0, 0}); // spine
        for (int x = -7; x <= 7; x += 2) {
            for (int side : new int[]{-1, 1}) {
                for (int z = 1; z <= 6; z++) {
                    int y = (int)(Math.sin(z * Math.PI / 7.0) * 3.5);
                    b.add(new int[]{x, y, z * side, 0});
                }
                b.add(new int[]{x, 0, 7 * side, 1}); // rib tip → fossil stone
            }
        }
        return b.toArray(new int[0][]);
    }

    /** Skull: hollow oblate sphere with eye sockets. */
    private static int[][] buildSkull() {
        List<int[]> b = new ArrayList<>();
        int r = 5;
        for (int x = -r; x <= r; x++) {
            for (int y = 0; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    float d = x*x + y*y*1.4f + z*z;
                    if (d <= r*r && d >= (r-2)*(r-2)) {
                        int type = (x*x + z*z < 4 && y == 0) ? 2 : 0; // marrow hollow at base
                        b.add(new int[]{x, y, z, type});
                    }
                }
            }
        }
        // Eye socket hollows on front face
        b.add(new int[]{-2, 2, -4, 2});
        b.add(new int[]{ 2, 2, -4, 2});
        return b.toArray(new int[0][]);
    }

    /** Spine: 20-block vertebrae column with disc fins. */
    private static int[][] buildSpine() {
        List<int[]> b = new ArrayList<>();
        for (int y = 0; y <= 20; y++) {
            b.add(new int[]{0, y, 0, 0});
            if (y % 3 == 0) {
                b.add(new int[]{-1, y,  0, 1});
                b.add(new int[]{ 1, y,  0, 1});
                b.add(new int[]{ 0, y, -1, 1});
                b.add(new int[]{ 0, y,  1, 1});
            }
        }
        return b.toArray(new int[0][]);
    }

    /** Femur: 24-block shaft with ball head and condyle. */
    private static int[][] buildFemur() {
        List<int[]> b = new ArrayList<>();
        for (int z = -12; z <= 12; z++) {
            b.add(new int[]{0, 0, z, 0});
            b.add(new int[]{1, 0, z, 0});
            if (z % 4 == 0) b.add(new int[]{0, 1, z, 1});
        }
        // Ball head (end −12)
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = 0; dy <= 4; dy++) {
                if (dx*dx + dy*dy <= 4) b.add(new int[]{dx, dy, -12, 0});
            }
        }
        // Condyle (end +12)
        b.add(new int[]{-1, 0, 12, 1}); b.add(new int[]{2, 0, 12, 1});
        b.add(new int[]{-1, 1, 12, 0}); b.add(new int[]{2, 1, 12, 0});
        return b.toArray(new int[0][]);
    }

    // =========================================================================
    //  HELPERS
    // =========================================================================

    /** Highest non-AIR Y in column (lx, lz), starting from chunk.maxBlockY. */
    private static int surfaceY(Chunk chunk, int lx, int lz) {
        int yTop = chunk.maxBlockY;
        if (yTop < 0) return 0;
        for (int ly = yTop; ly >= 0; ly--) {
            if (chunk.getBlock(lx, ly, lz) != Block.AIR) return ly;
        }
        return 0;
    }

    /** setBlock ignoring out-of-local-bounds lx/lz (cross-chunk writes silently dropped). */
    private static void place(Chunk chunk, int lx, int ly, int lz, Block b) {
        if (lx < 0 || lx >= Chunk.SIZE || lz < 0 || lz >= Chunk.SIZE) return;
        if (ly < 0 || ly >= Chunk.HEIGHT) return;
        chunk.setBlock(lx, ly, lz, b);
    }

    /** Clamp Y only (lx/lz always in-bounds). */
    private static void setY(Chunk chunk, int lx, int ly, int lz, Block b) {
        if (ly >= 0 && ly < Chunk.HEIGHT) chunk.setBlock(lx, ly, lz, b);
    }

    /** Place using absolute world coords, converting to local and dropping out-of-chunk writes. */
    private static void placeW(Chunk chunk, int worldX, int worldZ,
                                int wx, int wy, int wz, Block b) {
        place(chunk, wx - worldX, wy, wz - worldZ, b);
    }

    /** Rotate (dx, dz) by rotations × 90° CW around origin. */
    private static int[] rotateXZ(int dx, int dz, int rotations) {
        for (int i = 0; i < rotations; i++) { int t = dx; dx = -dz; dz = t; }
        return new int[]{dx, dz};
    }

    /** Smoothstep: 3t²−2t³ clamped to [0,1]. */
    private static float smoothstep(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3f - 2f * t);
    }

    // ── Region-hash loop helper ───────────────────────────────────────────────

    @FunctionalInterface
    private interface RegionTask { void run(int rx, int rz); }

    private static void forNearbyRegions(int worldX, int worldZ,
                                         int regionSize, int maxReach,
                                         RegionTask task) {
        int loX = Math.floorDiv(worldX - maxReach, regionSize);
        int hiX = Math.floorDiv(worldX + Chunk.SIZE + maxReach, regionSize);
        int loZ = Math.floorDiv(worldZ - maxReach, regionSize);
        int hiZ = Math.floorDiv(worldZ + Chunk.SIZE + maxReach, regionSize);
        for (int rx = loX; rx <= hiX; rx++)
            for (int rz = loZ; rz <= hiZ; rz++)
                task.run(rx, rz);
    }

    // ── Deterministic RNG — splitmix64 ────────────────────────────────────────

    private static long regionHash(long seed, long a, long b, int featureType) {
        long h = seed;
        h ^= a * 0x9E3779B97F4A7C15L;
        h ^= b * 0x6C62272E07BB0142L;
        h ^= (long)featureType * 0xD2B74407B1CE6E93L;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }

    private static long nextRng(long h) {
        h += 0x9E3779B97F4A7C15L;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }
}
