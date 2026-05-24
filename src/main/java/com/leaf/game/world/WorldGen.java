package com.leaf.game.world;

import com.leaf.game.core.GameConfig;
import com.leaf.game.util.Noise;

public class WorldGen {

    private Noise continentalness;
    private Noise erosion;
    private Noise warpNoise;
    private Noise peaksValleys;
    private Noise temperature;
    private Noise humidity;
    private Noise density3D;
    private Noise riverNoise;
    private Noise biomeJitter;   // fuzzes biome borders — no hard lines

    // Cave noises
    private Noise cheeseCave;
    private Noise spagNoise1;
    private Noise spagNoise2;

    public WorldGen(long seed) { init(seed); }
    public WorldGen()          { init(GameConfig.seed); }

    private void init(long seed) {
        continentalness = new Noise(seed);
        erosion         = new Noise(seed + 1000L);
        warpNoise       = new Noise(seed + 1500L);
        peaksValleys    = new Noise(seed + 2000L);
        temperature     = new Noise(seed + 3000L);
        humidity        = new Noise(seed + 4000L);
        density3D       = new Noise(seed + 5000L);
        riverNoise      = new Noise(seed + 6000L);
        cheeseCave      = new Noise(seed + 7000L);
        spagNoise1      = new Noise(seed + 8000L);
        spagNoise2      = new Noise(seed + 9000L);
        biomeJitter     = new Noise(seed + 10000L);
    }

    public void resetSeed(long seed) { init(seed); }

    // =========================================================================
    // PUBLIC SAMPLERS
    // =========================================================================

    public float sampleContinentalness(int wx, int wz) {
        float raw = continentalness.octave(
                wx * GameConfig.contFreq, wz * GameConfig.contFreq,
                GameConfig.contOctaves, GameConfig.contPersist);
        return (float)(Math.signum(raw) * Math.pow(Math.abs(raw), 0.6));
    }

    public float sampleErosion(int wx, int wz) {
        float wf = GameConfig.erosWarpFreq;
        float ws = GameConfig.erosWarpStrength;
        float offsetX = ws * warpNoise.get(wx * wf,         wz * wf);
        float offsetZ = ws * warpNoise.get(wx * wf + 31.7f, wz * wf + 17.3f);
        return erosion.octave(
                (wx + offsetX) * GameConfig.erosFreq,
                (wz + offsetZ) * GameConfig.erosFreq,
                GameConfig.erosOctaves, GameConfig.erosPersist);
    }

    public float samplePeaksValleys(int wx, int wz) {
        return peaksValleys.ridgedOctave(
                wx * GameConfig.pvFreq, wz * GameConfig.pvFreq,
                GameConfig.pvOctaves, GameConfig.pvPersist);
    }

    public float sampleTemperature(int wx, int wz) {
        return temperature.octave(
                wx * GameConfig.tempFreq, wz * GameConfig.tempFreq,
                GameConfig.tempOctaves, GameConfig.tempPersist);
    }

    public float sampleHumidity(int wx, int wz) {
        return humidity.octave(
                wx * GameConfig.humFreq, wz * GameConfig.humFreq,
                GameConfig.humOctaves, GameConfig.humPersist);
    }

    public float sampleRiver(int wx, int wz) {
        return riverNoise.octave(
                wx * GameConfig.riverFreq, wz * GameConfig.riverFreq,
                GameConfig.riverOctaves, GameConfig.riverPersist);
    }

    /** Combined terrain height [0,1] before caves. */
    public float sampleHeight(int wx, int wz) {
        return computeFinalShape(
                sampleContinentalness(wx, wz),
                sampleErosion(wx, wz),
                samplePeaksValleys(wx, wz));
    }

    // =========================================================================
    // CHUNK GENERATION
    // =========================================================================

    public void generateChunk(Chunk chunk) {
        int worldX = chunk.cx * Chunk.SIZE;
        int worldZ = chunk.cz * Chunk.SIZE;

        final float seaFrac =
                (GameConfig.seaLevel - GameConfig.heightBase) / (float) GameConfig.heightRange;

        for (int lx = 0; lx < Chunk.SIZE; lx++) {
            for (int lz = 0; lz < Chunk.SIZE; lz++) {
                int wx = worldX + lx;
                int wz = worldZ + lz;

                // ── TERRAIN SHAPE ─────────────────────────────────────────
                float c    = sampleContinentalness(wx, wz);
                float e    = sampleErosion(wx, wz);
                float pv   = samplePeaksValleys(wx, wz);
                float temp = sampleTemperature(wx, wz);
                float hum  = sampleHumidity(wx, wz);

                float eNorm    = (e + 1f) / 2f;
                float flatness = erosionFlatnessSpline(eNorm);
                float shape    = computeFinalShape(c, e, pv);

                // ── RIVER CARVING ─────────────────────────────────────────
                // Carve relative to current terrain height — same depth cut
                // everywhere, so mountain rivers don't become canyons.
                float river    = sampleRiver(wx, wz);
                float absRiver = Math.abs(river);
                boolean isRiver = absRiver < GameConfig.riverThreshold
                        && shape > seaFrac + GameConfig.riverElevationBuffer;

                if (isRiver) {
                    float carveT     = 1f - absRiver / GameConfig.riverThreshold;
                    float carved     = shape - GameConfig.riverCarveDepth * carveT;
                    float riverFloor = seaFrac - GameConfig.riverFloorMargin;
                    shape = Math.max(riverFloor, carved);
                }

                float targetY = GameConfig.heightBase + shape * GameConfig.heightRange;
                int   ty      = (int) targetY;

                // ── BIOME SELECTION ───────────────────────────────────────
                // Small noise jitter on temp/humidity before the table lookup.
                // Features ~110 blocks wide → borders look organic, not a
                // straight line, without affecting ocean/beach/river checks
                // (those use shape + ty, which aren't jittered).
                float jT   = biomeJitter.get(wx * 0.009f, wz * 0.009f)          * 0.22f;
                float jH   = biomeJitter.get(wx * 0.009f + 400f, wz * 0.009f + 400f) * 0.22f;
                Biome biome = selectBiome(shape, seaFrac, isRiver, ty,
                        temp + jT, hum + jH);

                // ── 3D DENSITY FIELD ──────────────────────────────────────
                // Zeroed in rivers (smooth channel walls) and flat biomes.
                float vertScale = lerp(GameConfig.densityVerticalScale, 0.55f, flatness);
                float d3dAmp    = (isRiver || flatness > 0.9f) ? 0f
                        : GameConfig.density3DAmplitude * (1f - flatness);

                boolean[] solid = new boolean[Chunk.HEIGHT];
                for (int ly = 0; ly < Chunk.HEIGHT; ly++) {
                    float heightBias = (targetY - ly) * vertScale;
                    float n3d = 0f;
                    if (d3dAmp > 0.5f) {
                        n3d = density3D.octave3D(
                                wx * GameConfig.density3DFreq,
                                ly * GameConfig.density3DFreq * GameConfig.density3DVerticalCompress,
                                wz * GameConfig.density3DFreq,
                                GameConfig.density3DOctaves,
                                GameConfig.density3DPersist) * d3dAmp;
                    }
                    solid[ly] = (heightBias + n3d) > 0;
                }

                // ── SURFACE Y (for cave depth guard) ─────────────────────
                int surfaceY = 0;
                for (int ly = Chunk.HEIGHT - 1; ly >= 0; ly--) {
                    if (solid[ly]) { surfaceY = ly; break; }
                }

                // ── CHEESE CAVES ──────────────────────────────────────────
                int caveTop = surfaceY - GameConfig.caveSurfaceBuffer;
                for (int ly = GameConfig.caveBedrockFloor; ly < caveTop; ly++) {
                    if (!solid[ly]) continue;
                    float depthT    = 1f - (float) ly / Math.max(1, surfaceY);
                    float adjThresh = GameConfig.cheeseThreshold - depthT * GameConfig.cheeseDepthBoost;
                    float cheese    = cheeseCave.octave3D(
                            wx * GameConfig.cheeseFreq,
                            ly * GameConfig.cheeseFreq * GameConfig.cheeseVertCompress,
                            wz * GameConfig.cheeseFreq,
                            GameConfig.cheeseOctaves, GameConfig.cheesePersist);
                    if ((cheese + 1f) * 0.5f > adjThresh) solid[ly] = false;
                }

                // ── SPAGHETTI CAVES ───────────────────────────────────────
                // Two ridged noises intersected → long branching tunnels.
                for (int ly = GameConfig.caveBedrockFloor; ly < caveTop; ly++) {
                    if (!solid[ly]) continue;
                    float raw1   = spagNoise1.octave3D(
                            wx * GameConfig.spagFreq,
                            ly * GameConfig.spagFreq * GameConfig.spagVertCompress,
                            wz * GameConfig.spagFreq,
                            GameConfig.spagOctaves, GameConfig.spagPersist);
                    float raw2   = spagNoise2.octave3D(
                            wx * GameConfig.spagFreq,
                            ly * GameConfig.spagFreq * GameConfig.spagVertCompress,
                            wz * GameConfig.spagFreq,
                            GameConfig.spagOctaves, GameConfig.spagPersist);
                    float tunnel = Math.min(1f - Math.abs(raw1), 1f - Math.abs(raw2));
                    if (tunnel > GameConfig.spagThreshold) solid[ly] = false;
                }

                // ── PLACE BLOCKS ──────────────────────────────────────────
                boolean hitSurface = false;
                int     dirtCount  = 0;

                for (int ly = Chunk.HEIGHT - 1; ly >= 0; ly--) {
                    if (!solid[ly]) {
                        // Non-solid: water if at/below sea level, else air.
                        chunk.setBlock(lx, ly, lz,
                                ly <= GameConfig.seaLevel ? Block.WATER : Block.AIR);
                    } else {
                        if (!hitSurface) {
                            hitSurface = true;
                            dirtCount  = 0;
                            if (ly >= GameConfig.seaLevel) {
                                // Above water: full biome surface.
                                chunk.setBlock(lx, ly, lz, biome.surfaceBlock());
                            } else if (ly >= GameConfig.seaLevel - 4) {
                                // Shallow ocean floor: sandy transition.
                                chunk.setBlock(lx, ly, lz, Block.SAND);
                            } else {
                                // Deep ocean floor: bare stone.
                                chunk.setBlock(lx, ly, lz, Block.STONE);
                            }
                        } else if (dirtCount < 3) {
                            dirtCount++;
                            chunk.setBlock(lx, ly, lz, biome.subSurfaceBlock());
                        } else {
                            chunk.setBlock(lx, ly, lz, Block.STONE);
                        }
                    }
                }

                // ── RIVER WATER POST-PASS ─────────────────────────────────
                // River channels are carved above seaLevel, so the global
                // "fill non-solid below seaLevel" pass misses them. After
                // placing all blocks, find the river bed (top solid block)
                // and explicitly fill 2 blocks above it with water, giving
                // a shallow swimmable channel at any elevation.
                if (isRiver) {
                    for (int ly = Chunk.HEIGHT - 1; ly >= 1; ly--) {
                        Block b = chunk.getBlock(lx, ly, lz);
                        if (b != Block.AIR && b != Block.WATER) {
                            if (ly + 1 < Chunk.HEIGHT)
                                chunk.setBlock(lx, ly + 1, lz, Block.WATER);
                            if (ly + 2 < Chunk.HEIGHT)
                                chunk.setBlock(lx, ly + 2, lz, Block.WATER);
                            break;
                        }
                    }
                }
            }
        }

        chunk.dirty = true;
    }

    // =========================================================================
    // BIOME SELECTION
    // =========================================================================

    /**
     * Picks a biome for a column.
     *
     * Priority order (highest wins):
     *   1. Ocean   — well below sea level regardless of climate
     *   2. River   — carved channel, always water-filled
     *   3. Beach   — narrow coastal strip straddling sea level
     *   4. Icy peaks — very high altitude, always snowy
     *   5. Temperature × Humidity table
     *
     * temp and hum should already have jitter applied before calling.
     */
    private Biome selectBiome(float shape, float seaFrac, boolean isRiver,
                              int ty, float temp, float hum) {
        // Ocean: shape clearly below sea level
        if (shape < seaFrac - 0.01f) return Biome.OCEAN;

        // River channel
        if (isRiver) return Biome.RIVER;

        // Coastal beach strip
        if (ty >= GameConfig.seaLevel - 1
                && ty <= GameConfig.seaLevel + GameConfig.beachMaxAltitude)
            return Biome.BEACH;

        // Altitude snow override — above this, always icy regardless of biome
        if (ty >= GameConfig.snowAltitude) return Biome.ICY_PEAKS;

        // ── Temperature × Humidity table ──────────────────────────────────
        //
        //              ARID          NEUTRAL         HUMID
        //  SCORCHING   DESERT        DESERT          SAVANNA
        //  HOT         SAVANNA       SAVANNA         SAVANNA
        //  WARM        PLAINS        PLAINS          FOREST
        //  COOL        PLAINS        FOREST          TAIGA
        //  COLD        SNOWY_PLAINS  TUNDRA          TUNDRA
        //  FROZEN      SNOWY_PLAINS  SNOWY_PLAINS    TUNDRA
        //
        if (temp > 0.55f) {
            return hum < 0.10f ? Biome.DESERT   : Biome.SAVANNA;
        }
        if (temp > 0.20f) {
            return Biome.SAVANNA;
        }
        if (temp > -0.05f) {
            return hum < -0.10f ? Biome.PLAINS  : Biome.FOREST;
        }
        if (temp > -0.30f) {
            return hum < -0.15f ? Biome.PLAINS  : Biome.TAIGA;
        }
        // Frozen
        return hum < 0.05f ? Biome.SNOWY_PLAINS : Biome.TUNDRA;
    }

    // =========================================================================
    // SPLINE SYSTEM
    // =========================================================================

    private float computeFinalShape(float c, float e, float pv) {
        float eNorm     = (e + 1f) / 2f;
        float contH     = continentalnessSpline(c);
        float pvContrib = pvContribMax(eNorm) * ((pv + 1f) / 2f);
        float mountainH = Math.min(1f, contH + pvContrib);
        float flatness  = erosionFlatnessSpline(eNorm);
        return lerp(mountainH, contH, flatness);
    }

    private float continentalnessSpline(float c) {
        if (c < -0.45f) return remap(c, -1.00f, -0.45f, 0.02f, 0.08f);
        if (c < -0.10f) return remap(c, -0.45f, -0.10f, 0.08f, 0.20f);
        if (c <  0.05f) return remap(c, -0.10f,  0.05f, 0.20f, 0.25f);
        if (c <  0.30f) return remap(c,  0.05f,  0.30f, 0.25f, 0.36f);
        if (c <  0.65f) return remap(c,  0.30f,  0.65f, 0.36f, 0.78f);
        if (c <  0.85f) return remap(c,  0.65f,  0.85f, 0.78f, 0.88f);
        return             remap(c,  0.85f,  1.00f, 0.88f, 0.92f);
    }

    private float erosionFlatnessSpline(float eNorm) {
        if (eNorm < 0.25f) return remap(eNorm, 0f,    0.25f, 0f,    0.05f);
        if (eNorm < 0.55f) return remap(eNorm, 0.25f, 0.55f, 0.05f, 0.40f);
        if (eNorm < 0.75f) return remap(eNorm, 0.55f, 0.75f, 0.40f, 0.85f);
        return               remap(eNorm, 0.75f, 1f,   0.85f, 1.00f);
    }

    private float pvContribMax(float eNorm) {
        if (eNorm < 0.30f) return 0.45f;
        if (eNorm < 0.60f) return remap(eNorm, 0.30f, 0.60f, 0.45f, 0.10f);
        return               remap(eNorm, 0.60f, 1.00f, 0.10f, 0.00f);
    }

    private float lerp(float a, float b, float t) { return a + t * (b - a); }

    private float remap(float val, float inMin, float inMax,
                        float outMin, float outMax) {
        float t = (val - inMin) / (inMax - inMin);
        t = Math.max(0f, Math.min(1f, t));
        return outMin + t * (outMax - outMin);
    }
}