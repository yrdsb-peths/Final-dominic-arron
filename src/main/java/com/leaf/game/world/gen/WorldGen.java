package com.leaf.game.world.gen;

import com.leaf.game.core.GameConfig;
import com.leaf.game.util.Noise;
import com.leaf.game.world.gen.biome.Biome;
import com.leaf.game.world.gen.biome.BiomeRegistry;
import com.leaf.game.world.Block;
import com.leaf.game.world.Chunk;
import com.leaf.game.world.gen.terrain.ErodedFbmGenerator;
import com.leaf.game.world.gen.biome.BlockCladder;

public class WorldGen {

    private Noise continentalness;
    private Noise erosion;
    private Noise warpNoise;
    private Noise peaksValleys;
    private Noise temperature;
    private Noise humidity;
    private Noise density3D;
    private Noise riverNoise;
    private Noise biomeJitter;

    private Noise mountainMask;

    private ErodedFbmGenerator eroFbm;
    private BlockCladder       cladder;

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
        mountainMask    = new Noise(seed + 30000L);

        eroFbm  = new ErodedFbmGenerator(
                seed + 20000L,
                7,
                0.0025f,
                2.0f,
                0.5f
        );
        cladder = new BlockCladder(GameConfig.mountainSnowAltitude);
    }

    public void resetSeed(long seed) { init(seed); }

    public float sampleContinentalness(int wx, int wz) {
        float raw = continentalness.octave(wx * GameConfig.contFreq, wz * GameConfig.contFreq, GameConfig.contOctaves, GameConfig.contPersist);
        return (float)(Math.signum(raw) * Math.pow(Math.abs(raw), 0.6));
    }
    public float sampleErosion(int wx, int wz) {
        float wf = GameConfig.erosWarpFreq;
        float ws = GameConfig.erosWarpStrength;
        float offsetX = ws * warpNoise.get(wx * wf, wz * wf);
        float offsetZ = ws * warpNoise.get(wx * wf + 31.7f, wz * wf + 17.3f);
        return erosion.octave((wx + offsetX) * GameConfig.erosFreq, (wz + offsetZ) * GameConfig.erosFreq, GameConfig.erosOctaves, GameConfig.erosPersist);
    }
    public float samplePeaksValleys(int wx, int wz) {
        return peaksValleys.ridgedOctave(wx * GameConfig.pvFreq, wz * GameConfig.pvFreq, GameConfig.pvOctaves, GameConfig.pvPersist);
    }
    public float sampleTemperature(int wx, int wz) { return temperature.octave(wx * GameConfig.tempFreq, wz * GameConfig.tempFreq, GameConfig.tempOctaves, GameConfig.tempPersist); }
    public float sampleHumidity(int wx, int wz) { return humidity.octave(wx * GameConfig.humFreq, wz * GameConfig.humFreq, GameConfig.humOctaves, GameConfig.humPersist); }
    public float sampleRiver(int wx, int wz) { return riverNoise.octave(wx * GameConfig.riverFreq, wz * GameConfig.riverFreq, GameConfig.riverOctaves, GameConfig.riverPersist); }
    public float sampleHeight(int wx, int wz) { return computeFinalShape(sampleContinentalness(wx, wz), sampleErosion(wx, wz), samplePeaksValleys(wx, wz)); }

    public void generateChunk(Chunk chunk) {
        int worldX = chunk.cx * Chunk.SIZE;
        int worldZ = chunk.cz * Chunk.SIZE;
        final float seaFrac = (GameConfig.seaLevel - GameConfig.heightBase) / (float) GameConfig.heightRange;

        for (int lx = 0; lx < Chunk.SIZE; lx++) {
            for (int lz = 0; lz < Chunk.SIZE; lz++) {
                int wx = worldX + lx;
                int wz = worldZ + lz;

                float c    = sampleContinentalness(wx, wz);
                float e    = sampleErosion(wx, wz);
                float pv   = samplePeaksValleys(wx, wz);
                float temp = sampleTemperature(wx, wz);
                float hum  = sampleHumidity(wx, wz);

                float shape = computeFinalShape(c, e, pv);
                float targetY = GameConfig.heightBase + shape * GameConfig.heightRange;

                boolean isAlpine = false;
                float fbmSlope = 0f;

                // ── FIX: TECTONIC RIDGE MASK ──
                // Use ridged noise! It naturally creates long, connected mountain lines
                // instead of isolated circular blobs.
                float mMask = mountainMask.ridgedOctave(wx * 0.001f, wz * 0.001f, 2, 0.5f);

                // Ridged noise is [-1, 1]. A threshold of 0.3 creates nice wide mountain bases.
                float mountainSpawnThreshold = 0.30f;

                if (c > 0.05f && mMask > mountainSpawnThreshold) {
                    float[] ero = eroFbm.sampleFull(wx, wz);
                    float fbmH = ero[0];
                    fbmSlope = ero[1];

                    // Map [0.3, 1.0] to [0.0, 1.0] for blending
                    float blend = (mMask - mountainSpawnThreshold) / (1f - mountainSpawnThreshold);
                    blend = Math.max(0f, Math.min(1f, blend));
                    blend = blend * blend * (3f - 2f * blend);

                    float coastFade = Math.max(0f, Math.min(1f, (c - 0.05f) / 0.15f));
                    blend *= coastFade;

                    float massiveY = GameConfig.seaLevel + 10f + (fbmH * 380f);

                    targetY = lerp(targetY, massiveY, blend);

                    if (blend > 0.05f) {
                        isAlpine = true;
                    }
                }

                int ty = (int) targetY;
                float eNorm = (e + 1f) / 2f;
                float flatness = erosionFlatnessSpline(eNorm);

                float river = sampleRiver(wx, wz);
                float absRiver = Math.abs(river);
                boolean isRiver = absRiver < GameConfig.riverThreshold && shape >= (seaFrac - 0.01f);
                if (isRiver && !isAlpine) {
                    float carveT = 1f - absRiver / GameConfig.riverThreshold;
                    carveT = carveT * carveT * (3f - 2f * carveT);
                    targetY = Math.max(seaFrac - GameConfig.riverFloorMargin, shape - GameConfig.riverCarveDepth * carveT);
                }

                boolean[] solid = new boolean[Chunk.HEIGHT];
                for (int ly = 0; ly < Chunk.HEIGHT; ly++) {
                    if (ly > targetY + 1) {
                        solid[ly] = false;
                    } else if (isAlpine && ly > GameConfig.seaLevel + 20) {
                        solid[ly] = (ly <= targetY);
                    } else {
                        float vertScale = lerp(GameConfig.densityVerticalScale, 0.55f, flatness);
                        float d3dAmp = (isRiver || flatness > 0.9f) ? 0f : GameConfig.density3DAmplitude * (1f - flatness);
                        float heightBias = (targetY - ly) * vertScale;
                        float n3d = 0f;
                        if (d3dAmp > 0.5f) {
                            n3d = density3D.octave3D(wx * GameConfig.density3DFreq, ly * GameConfig.density3DFreq * GameConfig.density3DVerticalCompress, wz * GameConfig.density3DFreq, GameConfig.density3DOctaves, GameConfig.density3DPersist) * d3dAmp;
                        }
                        solid[ly] = (heightBias + n3d) > 0;
                    }
                }

                int surfaceY = 0;
                for (int ly = Chunk.HEIGHT - 1; ly >= 0; ly--) {
                    if (solid[ly]) { surfaceY = ly; break; }
                }

                if (!isAlpine) {
                    int caveTop = surfaceY - GameConfig.caveSurfaceBuffer;
                    for (int ly = GameConfig.caveBedrockFloor; ly < caveTop; ly++) {
                        if (!solid[ly]) continue;
                        float cheese = cheeseCave.octave3D(wx * GameConfig.cheeseFreq, ly * GameConfig.cheeseFreq * GameConfig.cheeseVertCompress, wz * GameConfig.cheeseFreq, GameConfig.cheeseOctaves, GameConfig.cheesePersist);
                        if ((cheese + 1f) * 0.5f > GameConfig.cheeseThreshold) solid[ly] = false;
                    }
                }

                Biome biome = BiomeRegistry.evaluate(shape, seaFrac, isRiver, ty, temp, hum);
                boolean hitSurface = false;
                int dirtCount = 0;

                for (int ly = Chunk.HEIGHT - 1; ly >= 0; ly--) {
                    if (!solid[ly]) {
                        chunk.setBlock(lx, ly, lz, ly <= GameConfig.seaLevel ? Block.WATER : Block.AIR);
                    } else {
                        if (!hitSurface) {
                            hitSurface = true;
                            dirtCount = 0;
                            if (ly >= GameConfig.seaLevel) {
                                Block surf = isAlpine ? cladder.surfaceBlock(ly, fbmSlope) : biome.surfaceBlock();
                                chunk.setBlock(lx, ly, lz, surf);
                            } else if (ly >= GameConfig.seaLevel - 4) {
                                chunk.setBlock(lx, ly, lz, Block.SAND);
                            } else {
                                chunk.setBlock(lx, ly, lz, (wx + wz) % 2 == 0 ? Block.GRAVEL : Block.CLAY);
                            }
                        } else if (dirtCount < 3) {
                            dirtCount++;
                            Block sub = isAlpine ? cladder.subSurfaceBlock(ly, fbmSlope) : biome.subSurfaceBlock();
                            chunk.setBlock(lx, ly, lz, sub);
                        } else {
                            chunk.setBlock(lx, ly, lz, Block.STONE);
                        }
                    }
                }

                if (isRiver && !isAlpine) {
                    for (int ly = Chunk.HEIGHT - 1; ly >= 1; ly--) {
                        Block b = chunk.getBlock(lx, ly, lz);
                        if (b != Block.AIR && b != Block.WATER) {
                            if (ly + 1 < Chunk.HEIGHT) chunk.setBlock(lx, ly + 1, lz, Block.WATER);
                            break;
                        }
                    }
                }
            }
        }
    }

    private float computeFinalShape(float c, float e, float pv) {
        float eNorm = (e + 1f) / 2f;
        float contH = continentalnessSpline(c);
        float seaFrac = (GameConfig.seaLevel - GameConfig.heightBase) / (float) GameConfig.heightRange;
        float pvScale = Math.max(0.0f, Math.min(1.0f, (contH - seaFrac) / 0.08f));
        float pvContrib = pvContribMax(eNorm) * ((pv + 1f) / 2f) * pvScale;
        return lerp(Math.min(1f, contH + pvContrib), contH, erosionFlatnessSpline(eNorm));
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
        if (eNorm < 0.25f) return remap(eNorm, 0f, 0.25f, 0f, 0.05f);
        if (eNorm < 0.55f) return remap(eNorm, 0.25f, 0.55f, 0.05f, 0.40f);
        if (eNorm < 0.75f) return remap(eNorm, 0.55f, 0.75f, 0.40f, 0.85f);
        return remap(eNorm, 0.75f, 1f, 0.85f, 1.00f);
    }
    private float pvContribMax(float eNorm) {
        if (eNorm < 0.30f) return 0.45f;
        if (eNorm < 0.60f) return remap(eNorm, 0.30f, 0.60f, 0.45f, 0.10f);
        return remap(eNorm, 0.60f, 1.00f, 0.10f, 0.00f);
    }
    private float lerp(float a, float b, float t) { return a + t * (b - a); }
    private float remap(float val, float inMin, float inMax, float outMin, float outMax) {
        float t = Math.max(0f, Math.min(1f, (val - inMin) / (inMax - inMin)));
        return outMin + t * (outMax - outMin);
    }
}