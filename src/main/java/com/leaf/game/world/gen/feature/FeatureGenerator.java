package com.leaf.game.world.gen.feature;

import com.leaf.game.util.Noise;
import com.leaf.game.world.Block;
import com.leaf.game.world.Chunk;
import com.leaf.game.world.gen.WorldGen;
import com.leaf.game.world.gen.biome.Biome;

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
    private static final int FT_TOWER    = 5;

    // ── Inferno Tower siting (shared with EnemyManager's tower director) ───────
    /** World blocks per Inferno-Tower candidate region. */
    public static final int TOWER_REGION = 160;
    /** Max reach of a tower foundation from its site centre (for chunk overlap). */
    public static final int TOWER_MAX_R  = 9;

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
    private final Noise volcanicNoise;   // Volcanic lava-pond / vent scatter

    public FeatureGenerator(long seed) {
        this.seed         = seed;
        crystalNoise   = new Noise(seed + 50_000L);
        petrifiedNoise = new Noise(seed + 51_000L);
        volcanicNoise  = new Noise(seed + 52_000L);
    }

    /**
     * Deterministic Inferno-Tower site for a candidate region, or {@code null} if
     * that region hosts no tower. Shared by world-gen (foundation placement) and
     * the EnemyManager tower director (entity spawning) so the two always coincide.
     * Callers must still confirm {@code worldGen.biomeAt(site) == VOLCANIC}.
     */
    public static int[] infernoTowerSite(long seed, int rx, int rz) {
        long rng = regionHash(seed, rx, rz, FT_TOWER);
        if ((rng & 0xFFL) > 200L) return null;   // ~78 % of regions host a candidate
        rng = nextRng(rng);
        int x = rx * TOWER_REGION + (int)((rng >>> 1) % TOWER_REGION);
        rng = nextRng(rng);
        int z = rz * TOWER_REGION + (int)((rng >>> 1) % TOWER_REGION);
        return new int[]{ x, z };
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

        // Phase 5 — General Surface Polish (Trees & Shrines)
        applyVegetationAndShrines(chunk, worldGen);

        // Phase 6 — Special-biome dressing (sakura, mushroom, crystal, autumn, volcanic)
        applyBiomeFeatures(chunk, worldGen);

        // Phase 6.5 — Great Ancient Sakuras (rare colossal centerpiece trees)
        applyGreatSakuras(chunk, worldGen);

        // Phase 7 — Inferno-Tower foundations (volcanic biome landmarks)
        applyInfernoTowers(chunk, worldGen);
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
                int   radius  = 16 + (int)((rng >>> 1) % 30);            // 16–45 top radius
                rng = nextRng(rng);
                int   liftOff = 90 + (int)((rng >>> 1) % 120);           // float high into the sky
                rng = nextRng(rng);
                float phase   = ((rng >>> 1) % 628) / 100f;             // organic rim-wobble phase
                rng = nextRng(rng);
                int   peak    = 3 + (int)((rng >>> 1) % 7);             // gentle central dome
                rng = nextRng(rng);
                // Underside spike depth: how far the centre tapers to a point (≈1.4–2.1×R).
                float spikeMul = 1.4f + ((rng >>> 1) % 70) / 100f;
                int   spike    = (int)(radius * spikeMul);

                for (int lx = 0; lx < Chunk.SIZE; lx++) {
                    for (int lz = 0; lz < Chunk.SIZE; lz++) {
                        int wx = worldX + lx, wz = worldZ + lz;
                        float dx = wx - iX, dz = wz - iZ;
                        float dist = (float) Math.sqrt(dx * dx + dz * dz);

                        // Organic, lumpy rim — not a perfect circle (matches the refs).
                        float ang  = (float) Math.atan2(dz, dx);
                        float wob  = 1f + 0.17f * (float) Math.sin(ang * 3 + phase)
                                        + 0.09f * (float) Math.sin(ang * 5 - phase * 1.7f);
                        float effR = radius * wob;
                        if (dist >= effR) continue;

                        int groundY = surfaceY(chunk, lx, lz);
                        int base    = groundY + liftOff;
                        float nd    = 1f - dist / effR;                  // 0 rim → 1 centre

                        // Flat grassy plateau with a soft central peak.
                        int topY = base + (int) (peak * smoothstep(nd));
                        // Dramatic underside: thin at the rim, plunging to a point at the centre.
                        int depth   = 2 + (int) (spike * nd * nd);
                        int bottomY = topY - depth;
                        if (topY >= Chunk.HEIGHT - 4) continue;
                        if (bottomY < groundY + 18) bottomY = groundY + 18;   // keep an air gap
                        if (bottomY > topY) continue;

                        int span = Math.max(1, topY - bottomY);
                        for (int ly = bottomY; ly <= topY; ly++) {
                            int   fromTop   = topY - ly;
                            float depthFrac = (float) (ly - bottomY) / span;
                            Block b;
                            if      (fromTop == 0)        b = Block.GRASS;            // green plateau
                            else if (fromTop <= 2)        b = Block.DIRT;             // soil layer
                            else if (depthFrac < 0.30f)   b = Block.MESA_STONE;       // rocky point
                            else {                                                   // banded tan cliffs
                                int band = Math.floorMod(ly, 7);
                                b = (band < 2) ? Block.MESA_TERRACOTTA : Block.MESA_CLAY;
                            }
                            chunk.setBlock(lx, ly, lz, b);
                        }

                        // Long stalactite drips hanging from the central mass.
                        if (nd > 0.45f) {
                            long dr = regionHash(seed, wx, wz, 77);
                            if ((dr & 0x3L) == 0L) {
                                int len = 2 + (int)((dr >>> 2) % (long)(nd * 9 + 2));
                                for (int r = 1; r <= len; r++) {
                                    int ry = bottomY - r;
                                    if (ry < 1 || chunk.getBlock(lx, ry, lz) != Block.AIR) break;
                                    chunk.setBlock(lx, ry, lz, Block.MESA_STONE);
                                }
                            }
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

    // =========================================================================
    //  ⑦ SURFACE VEGETATION & SUMMIT SHRINES
    // =========================================================================

    private void applyVegetationAndShrines(Chunk chunk, WorldGen worldGen) {
        int worldX = chunk.cx * Chunk.SIZE;
        int worldZ = chunk.cz * Chunk.SIZE;

        for (int lx = 0; lx < Chunk.SIZE; lx++) {
            for (int lz = 0; lz < Chunk.SIZE; lz++) {
                int wx = worldX + lx;
                int wz = worldZ + lz;

                int sy = surfaceY(chunk, lx, lz);
                if (sy < 220) continue; // No underwater trees

                Block ground = chunk.getBlock(lx, sy, lz);
                if (ground != Block.GRASS && ground != Block.DIRT && ground != Block.STONE && ground != Block.SNOW) continue;

                // Use regionHash to get a consistent random number for this column
                long rng = regionHash(seed, wx, wz, 99);

                // 1. Summit Shrines (Only on majestic peaks > 360)
                if (sy > 360 && (rng % 300) == 0) {
                    buildShrine(chunk, lx, sy, lz);
                    continue;
                }

                // 2. Temperate Oak Trees (Only on standard plains/forests <= 240 - Keeps Mountains pristine!)
                if (sy <= 240 && ground == Block.GRASS) {
                    float hum = worldGen.sampleHumidity(wx, wz);
                    int treeChance = (hum > 0.1f) ? 15 : 60; // Dense in forests, sparse in plains
                    if ((rng % treeChance) == 0) {
                        buildOakTree(chunk, lx, sy, lz);
                        continue;
                    }
                }

                // 3. Ground cover: grass tufts everywhere on grass, wildflowers in
                //    loose patches (a low-frequency hash gates the patches so they
                //    cluster like real meadows instead of spreading evenly).
                if (ground == Block.GRASS && sy + 1 < Chunk.HEIGHT
                        && chunk.getBlock(lx, sy + 1, lz) == Block.AIR) {
                    int roll = (int) ((rng >>> 12) % 100);
                    long patch = regionHash(seed, Math.floorDiv(wx, 9), Math.floorDiv(wz, 9), 77);
                    boolean meadow = (patch & 7) < 3;                       // ~3/8 of 9×9 cells
                    if (meadow && roll < 9) {
                        Block[] fl = { Block.FLOWER_POPPY, Block.FLOWER_DANDELION, Block.FLOWER_CORNFLOWER };
                        place(chunk, lx, sy + 1, lz, fl[(int) ((patch >>> 5) % 3)]);
                    } else if (roll < 27) {
                        place(chunk, lx, sy + 1, lz, Block.TALL_GRASS);
                    }
                }
            }
        }
    }

    private void buildShrine(Chunk chunk, int lx, int sy, int lz) {
        // 3x3 platform, 4 corner pillars, glowing center
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                place(chunk, lx + dx, sy + 1, lz + dz, Block.MEGALITH);
                if (Math.abs(dx) == 1 && Math.abs(dz) == 1) {
                    place(chunk, lx + dx, sy + 2, lz + dz, Block.MEGALITH_CARVED);
                }
            }
        }
        place(chunk, lx, sy + 2, lz, Block.CRYSTAL_QUARTZ);
    }

    private void buildPineTree(Chunk chunk, int lx, int sy, int lz) {
        int height = 6 + (int)(Math.random() * 4);
        for (int y = 1; y <= height; y++) place(chunk, lx, sy + y, lz, Block.OAK_LOG);

        for (int y = height / 2; y <= height + 1; y++) {
            int radius = (y > height - 1) ? 1 : 2 - ((y % 2 == 0) ? 0 : 1);
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx == 0 && dz == 0 && y <= height) continue;
                    if (Math.abs(dx) == radius && Math.abs(dz) == radius && radius > 1) continue;
                    int px = lx + dx, pz = lz + dz;
                    // Don't overwrite solid blocks
                    if (px >= 0 && px < Chunk.SIZE && pz >= 0 && pz < Chunk.SIZE) {
                        if (chunk.getBlock(px, sy + y, pz) == Block.AIR) {
                            place(chunk, px, sy + y, pz, Block.OAK_LEAVES);
                        }
                    }
                }
            }
        }
    }

    private void buildOakTree(Chunk chunk, int lx, int sy, int lz) {
        int height = 4 + (int)(Math.random() * 3);
        for (int y = 1; y <= height; y++) place(chunk, lx, sy + y, lz, Block.OAK_LOG);

        for (int y = height - 2; y <= height + 1; y++) {
            int radius = (y >= height) ? 1 : 2;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx == 0 && dz == 0 && y <= height) continue;
                    if (Math.abs(dx) == radius && Math.abs(dz) == radius && (y == height + 1 || Math.random() < 0.5)) continue;
                    int px = lx + dx, pz = lz + dz;
                    if (px >= 0 && px < Chunk.SIZE && pz >= 0 && pz < Chunk.SIZE) {
                        if (chunk.getBlock(px, sy + y, pz) == Block.AIR) {
                            place(chunk, px, sy + y, pz, Block.OAK_LEAVES);
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    //  ⑧ SPECIAL-BIOME DRESSING  (sakura / mushroom / crystal / autumn / volcanic)
    // =========================================================================

    /**
     * Per-column biome dressing: queries the surface biome and scatters that
     * biome's signature features (blossom trees, glowing mushrooms, geodes,
     * maples, lava ponds). Deterministic via per-column {@code regionHash}.
     */
    private void applyBiomeFeatures(Chunk chunk, WorldGen worldGen) {
        int worldX = chunk.cx * Chunk.SIZE;
        int worldZ = chunk.cz * Chunk.SIZE;

        // Cheap gate: only pay the per-column biome lookups when the chunk's
        // centre or a corner actually sits in a special patch biome. Ordinary
        // chunks (the vast majority) skip this pass entirely.
        boolean anySpecial = false;
        int[][] probes = { {8, 8}, {0, 0}, {15, 0}, {0, 15}, {15, 15} };
        for (int[] p : probes) {
            if (isSpecialBiome(worldGen.biomeAt(worldX + p[0], worldZ + p[1]))) { anySpecial = true; break; }
        }
        if (!anySpecial) return;

        for (int lx = 0; lx < Chunk.SIZE; lx++) {
            for (int lz = 0; lz < Chunk.SIZE; lz++) {
                int wx = worldX + lx, wz = worldZ + lz;
                int sy = surfaceY(chunk, lx, lz);
                if (sy < 200) continue;                  // no underwater dressing
                Block ground = chunk.getBlock(lx, sy, lz);
                Biome biome  = worldGen.biomeAt(wx, wz);
                long rng     = regionHash(seed, wx, wz, 91);

                switch (biome) {
                    case SAKURA -> {
                        if (ground == Block.SAKURA_GRASS && (rng % 20) == 0)      buildSakuraTree(chunk, lx, sy, lz, rng);
                        else if (ground == Block.SAKURA_GRASS && (rng % 6) == 0)  place(chunk, lx, sy + 1, lz, Block.PINK_PETALS);
                    }
                    case AUTUMN -> {
                        if (ground == Block.AUTUMN_GRASS && (rng % 14) == 0)      buildMapleTree(chunk, lx, sy, lz, rng);
                    }
                    case MUSHROOM -> {
                        if (ground == Block.MYCELIUM && (rng % 24) == 0)          buildGiantMushroom(chunk, lx, sy, lz, rng);
                        else if (ground == Block.MYCELIUM && (rng % 9) == 0)      place(chunk, lx, sy + 1, lz, Block.GLOW_LICHEN);
                    }
                    case CRYSTAL_FIELDS -> {
                        if (ground == Block.AMETHYST_GRASS && (rng % 30) == 0)    buildGeode(chunk, lx, sy, lz, rng);
                    }
                    case VOLCANIC -> applyVolcanicColumn(chunk, lx, sy, lz, wx, wz);
                    default -> { }
                }
            }
        }
    }

    /** True for the rare patch biomes that get the per-column dressing pass. */
    private static boolean isSpecialBiome(Biome b) {
        return b == Biome.SAKURA || b == Biome.AUTUMN || b == Biome.MUSHROOM
                || b == Biome.CRYSTAL_FIELDS || b == Biome.VOLCANIC;
    }

    /** Wide pink-canopy cherry tree with a petal carpet around the base. */
    private void buildSakuraTree(Chunk chunk, int lx, int sy, int lz, long rng) {
        int height = 5 + (int)((rng >>> 1) % 3);     // 5–7
        for (int y = 1; y <= height; y++) place(chunk, lx, sy + y, lz, Block.SAKURA_LOG);
        int top = sy + height;
        for (int dy = -1; dy <= 2; dy++) {
            int radius = (dy == 2) ? 1 : (dy == -1 ? 2 : 3);
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) == radius && Math.abs(dz) == radius && ((dx * 7 + dz * 13 + dy) & 1) == 0) continue;
                    int px = lx + dx, pz = lz + dz, py = top + dy;
                    if (px >= 0 && px < Chunk.SIZE && pz >= 0 && pz < Chunk.SIZE
                            && py < Chunk.HEIGHT && chunk.getBlock(px, py, pz) == Block.AIR) {
                        place(chunk, px, py, pz, Block.SAKURA_LEAVES);
                    }
                }
            }
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int px = lx + dx, pz = lz + dz;
                if (px >= 0 && px < Chunk.SIZE && pz >= 0 && pz < Chunk.SIZE
                        && chunk.getBlock(px, sy + 1, pz) == Block.AIR
                        && chunk.getBlock(px, sy, pz).isSolid()) {
                    place(chunk, px, sy + 1, pz, Block.PINK_PETALS);
                }
            }
        }
    }

    /** Maple tree with a canopy of mixed red / gold / orange leaves. */
    private void buildMapleTree(Chunk chunk, int lx, int sy, int lz, long rng) {
        int height = 4 + (int)((rng >>> 1) % 3);     // 4–6
        for (int y = 1; y <= height; y++) place(chunk, lx, sy + y, lz, Block.MAPLE_LOG);
        for (int y = height - 2; y <= height + 1; y++) {
            int radius = (y >= height) ? 1 : 2;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx == 0 && dz == 0 && y <= height) continue;
                    if (Math.abs(dx) == radius && Math.abs(dz) == radius && (((dx + dz + y) & 1) == 0)) continue;
                    int px = lx + dx, pz = lz + dz, py = sy + y;
                    if (px >= 0 && px < Chunk.SIZE && pz >= 0 && pz < Chunk.SIZE
                            && py < Chunk.HEIGHT && chunk.getBlock(px, py, pz) == Block.AIR) {
                        long lh = regionHash(seed, lx * 31 + dx, lz * 17 + dz, (int) (y + 5));
                        Block leaf = switch ((int) (lh & 0x3L)) {
                            case 0  -> Block.MAPLE_LEAVES_GOLD;
                            case 1  -> Block.MAPLE_LEAVES_ORANGE;
                            default -> Block.MAPLE_LEAVES_RED;
                        };
                        place(chunk, px, py, pz, leaf);
                    }
                }
            }
        }
    }

    /** Giant bioluminescent mushroom — cream stem with a glowing domed cap. */
    private void buildGiantMushroom(Chunk chunk, int lx, int sy, int lz, long rng) {
        int height = 5 + (int)((rng >>> 1) % 5);     // 5–9
        Block cap = switch ((int)((rng >>> 4) % 3)) {
            case 0  -> Block.GLOWCAP_RED;
            case 1  -> Block.GLOWCAP_TEAL;
            default -> Block.GLOWCAP_BLUE;
        };
        for (int y = 1; y <= height; y++) place(chunk, lx, sy + y, lz, Block.MUSHROOM_STEM);
        int capY = sy + height;
        int r = 2 + (int)((rng >>> 8) % 2);          // cap radius 2–3
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r + 1) continue;
                place(chunk, lx + dx, capY, lz + dz, cap);
                if (Math.abs(dx) == r || Math.abs(dz) == r) place(chunk, lx + dx, capY - 1, lz + dz, cap); // drooping rim
            }
        }
        place(chunk, lx, capY + 1, lz, cap);
    }

    /** Amethyst geode mound — rocky shell wrapped around a glowing crystal core. */
    private void buildGeode(Chunk chunk, int lx, int sy, int lz, long rng) {
        int r = 2 + (int)((rng >>> 1) % 2);          // 2–3
        Block core = switch ((int)((rng >>> 4) % 4)) {
            case 0  -> Block.CRYSTAL_AMETHYST;
            case 1  -> Block.CRYSTAL_QUARTZ;
            case 2  -> Block.CRYSTAL_CITRINE;
            default -> Block.CRYSTAL_ROSE;
        };
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = 0; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    float d = dx * dx + dy * dy + dz * dz;
                    if (d > r * r) continue;
                    Block b = (d >= (r - 1) * (r - 1)) ? Block.GEODE_SHELL : core;
                    place(chunk, lx + dx, sy + 1 + dy, lz + dz, b);
                }
            }
        }
        place(chunk, lx, sy + 2 + r, lz, core);      // crystal point poking out the top
    }

    /** Volcanic surface dressing — lava ponds, magma crust rims, basalt vents. */
    private void applyVolcanicColumn(Chunk chunk, int lx, int sy, int lz, int wx, int wz) {
        float vn = volcanicNoise.octave(wx * 0.05f, wz * 0.05f, 2, 0.5f);
        if (vn > 0.55f) {
            place(chunk, lx, sy, lz, Block.LAVA);             // glowing lava pond
        } else if (vn > 0.42f) {
            place(chunk, lx, sy, lz, Block.MAGMA);            // hot crust around ponds
        } else {
            long h = regionHash(seed, wx, wz, 88);
            if ((h % 60) == 0) {
                int vh = 2 + (int)((h >>> 4) % 4);            // small basalt vent
                for (int y = 1; y <= vh; y++) place(chunk, lx, sy + y, lz, (y == vh) ? Block.MAGMA : Block.BASALT);
            } else if ((h % 30) == 0) {
                place(chunk, lx, sy + 1, lz, Block.OBSIDIAN); // obsidian shard
            }
        }
    }

    // =========================================================================
    //  ⑧b GREAT ANCIENT SAKURAS — rare colossal blossom trees (region-hash)
    // =========================================================================

    private static final int FT_GREAT_SAKURA   = 6;
    private static final int GREAT_SAKURA_REGION = 384;  // ~1 candidate per 384×384
    private static final int GREAT_SAKURA_MAX_R  = 14;   // canopy reach (chunk overlap)

    /**
     * A once-per-region colossal sakura: 2×2 trunk ~15 blocks tall, a vast domed
     * blossom canopy, hanging petal strands, and a petal carpet below. Placed with
     * the region-hash pattern so the canopy crosses chunk borders seamlessly; the
     * ground height comes from {@link WorldGen#sampleHeight} so every chunk agrees
     * on where the tree stands.
     */
    private void applyGreatSakuras(Chunk chunk, WorldGen worldGen) {
        int worldX = chunk.cx * Chunk.SIZE;
        int worldZ = chunk.cz * Chunk.SIZE;

        forNearbyRegions(worldX, worldZ, GREAT_SAKURA_REGION, GREAT_SAKURA_MAX_R, (rx, rz) -> {
            long rng = regionHash(seed, rx, rz, FT_GREAT_SAKURA);
            if ((rng & 0xFFL) > 150L) return;   // ~60 % of regions host a candidate
            rng = nextRng(rng);
            int tX = rx * GREAT_SAKURA_REGION + (int)((rng >>> 1) % GREAT_SAKURA_REGION);
            rng = nextRng(rng);
            int tZ = rz * GREAT_SAKURA_REGION + (int)((rng >>> 1) % GREAT_SAKURA_REGION);
            if (worldGen.biomeAt(tX, tZ) != Biome.SAKURA) return;

            rng = nextRng(rng);
            int trunkH  = 13 + (int)((rng >>> 1) % 5);   // 13–17
            rng = nextRng(rng);
            int canopyR = 8 + (int)((rng >>> 1) % 4);    // 8–11

            // Chunk-independent ground height (same estimate biomeAt uses).
            int gy = (int)(com.leaf.game.core.GameConfig.heightBase
                    + worldGen.sampleHeight(tX, tZ) * com.leaf.game.core.GameConfig.heightRange);
            int topY = gy + trunkH;

            for (int lx = 0; lx < Chunk.SIZE; lx++) {
                for (int lz = 0; lz < Chunk.SIZE; lz++) {
                    int wx = worldX + lx, wz = worldZ + lz;
                    int dx = wx - tX,     dz = wz - tZ;
                    float dist = (float) Math.sqrt(dx * dx + dz * dz);
                    if (dist > canopyR + 2) continue;

                    // 2×2 trunk (dx, dz in {0,1})
                    if (dx >= 0 && dx <= 1 && dz >= 0 && dz <= 1) {
                        for (int y = gy + 1; y <= topY; y++) place(chunk, lx, y, lz, Block.SAKURA_LOG);
                    }

                    // Domed canopy: thick ellipsoid shell drooping at the rim
                    if (dist <= canopyR) {
                        float nd    = dist / canopyR;                       // 0 centre → 1 rim
                        int   crown = topY + Math.round((1f - nd * nd) * 4f);
                        int   under = crown - 3 - Math.round((1f - nd) * 2f);
                        for (int y = under; y <= crown; y++) {
                            if (y <= gy + 2 || y >= Chunk.HEIGHT) continue;
                            if (chunk.getBlock(lx, y, lz) == Block.AIR) place(chunk, lx, y, lz, Block.SAKURA_LEAVES);
                        }
                        // Hanging petal strands from the canopy underside
                        long sRng = regionHash(seed, wx, wz, 73);
                        if ((sRng & 0x7L) == 0L && nd > 0.3f) {
                            int len = 2 + (int)((sRng >>> 3) % 4);
                            for (int s = 1; s <= len; s++) {
                                int sy2 = under - s;
                                if (sy2 <= gy + 1 || chunk.getBlock(lx, sy2, lz) != Block.AIR) break;
                                place(chunk, lx, sy2, lz, Block.PINK_PETALS);
                            }
                        }
                        // Petal carpet on the ground beneath the crown
                        if ((sRng & 0x3L) != 0L && dist < canopyR - 1) {
                            int sy3 = surfaceY(chunk, lx, lz);
                            if (sy3 > 2 && chunk.getBlock(lx, sy3, lz).isSolid()
                                    && chunk.getBlock(lx, sy3 + 1, lz) == Block.AIR) {
                                place(chunk, lx, sy3 + 1, lz, Block.PINK_PETALS);
                            }
                        }
                    }
                }
            }
        });
    }

    // =========================================================================
    //  ⑨ INFERNO-TOWER FOUNDATIONS  (volcanic-biome landmarks)
    // =========================================================================

    /**
     * Carve a basalt foundation + lava moat at every deterministic tower site whose
     * footprint overlaps this chunk and whose centre is in the VOLCANIC biome. The
     * tower model itself is an entity spawned by EnemyManager's tower director at
     * the same site (see {@link #infernoTowerSite}).
     */
    private void applyInfernoTowers(Chunk chunk, WorldGen worldGen) {
        int worldX = chunk.cx * Chunk.SIZE;
        int worldZ = chunk.cz * Chunk.SIZE;

        forNearbyRegions(worldX, worldZ, TOWER_REGION, TOWER_MAX_R, (rx, rz) -> {
            int[] site = infernoTowerSite(seed, rx, rz);
            if (site == null) return;
            if (worldGen.biomeAt(site[0], site[1]) != Biome.VOLCANIC) return;
            buildTowerFoundation(chunk, worldX, worldZ, site[0], site[1]);
        });
    }

    /** Basalt platform, obsidian pillars, and a ring of lava — the tower's base. */
    private void buildTowerFoundation(Chunk chunk, int worldX, int worldZ, int cx, int cz) {
        int R = 6;
        for (int dx = -R; dx <= R; dx++) {
            for (int dz = -R; dz <= R; dz++) {
                int lx = cx + dx - worldX, lz = cz + dz - worldZ;
                if (lx < 0 || lx >= Chunk.SIZE || lz < 0 || lz >= Chunk.SIZE) continue;
                float dist = (float) Math.sqrt(dx * dx + dz * dz);
                if (dist > R) continue;
                int sy = surfaceY(chunk, lx, lz);
                if (sy < 2) continue;
                if (dist <= R - 2) {
                    place(chunk, lx, sy, lz, dist < 1.6f ? Block.OBSIDIAN : Block.BASALT);
                    place(chunk, lx, sy + 1, lz, Block.AIR);     // clear footprint for the model
                } else if (dist <= R - 1) {
                    place(chunk, lx, sy, lz, Block.MAGMA);        // glowing crust ring
                } else {
                    place(chunk, lx, sy, lz, Block.LAVA);         // lava moat
                }
            }
        }
        int[][] corners = { {-3, -3}, {3, -3}, {-3, 3}, {3, 3} };
        for (int[] c : corners) {
            int lx = cx + c[0] - worldX, lz = cz + c[1] - worldZ;
            if (lx < 0 || lx >= Chunk.SIZE || lz < 0 || lz >= Chunk.SIZE) continue;
            int sy = surfaceY(chunk, lx, lz);
            if (sy < 2) continue;
            for (int h = 1; h <= 3; h++) place(chunk, lx, sy + h, lz, (h == 3) ? Block.MAGMA : Block.OBSIDIAN);
        }
    }
}
