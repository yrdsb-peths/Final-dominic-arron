package com.leaf.game.world.gen.terrain;

import com.leaf.game.world.gen.math.AnalyticalNoise2D;

public class ErodedFbmGenerator implements HeightmapGenerator {

    private static final float COS_A = 0.7954f;
    private static final float SIN_A = 0.6060f;

    private final AnalyticalNoise2D noise;
    private final int   octaves;
    private final float frequency;
    private final float lacunarity;
    private final float gain;

    public ErodedFbmGenerator(long seed, int octaves, float frequency, float lacunarity, float gain) {
        this.noise     = new AnalyticalNoise2D(seed);
        this.octaves   = octaves;
        this.frequency = frequency;
        this.lacunarity = lacunarity;
        this.gain      = gain;
    }

    @Override
    public float getHeight(int wx, int wz) {
        return sampleFull(wx, wz)[0];
    }

    public float[] sampleFull(int wx, int wz) {
        float x = wx * frequency;
        float z = wz * frequency;

        float value    = 0;
        float totalW   = 0;
        float amplitude = 1;

        float dx = 0, dz = 0;
        float rx = x, rz = z;

        for (int i = 0; i < octaves; i++) {
            float[] s  = noise.sample(rx, rz);
            float   n  = s[0]; // strictly [0, 1]
            float   ndx = s[1];
            float   ndz = s[2];

            dx += ndx;
            dz += ndz;

            float slopeSq = dx * dx + dz * dz;
            float w       = amplitude / (1.0f + slopeSq);

            value  += n * w;
            totalW += w;

            float nx = COS_A * rx - SIN_A * rz;
            float nz = SIN_A * rx + COS_A * rz;
            rx = nx * lacunarity;
            rz = nz * lacunarity;

            amplitude *= gain;
        }

        float normalized = totalW > 0 ? value / totalW : 0;

        // ── FIX: STRETCH BEFORE EXPONENT ──
        // Value noise averages around 0.5. We must remap [0.3, 0.7] to [0.0, 1.0]
        // so the mountains actually reach maximum height!
        normalized = (normalized - 0.3f) / 0.4f;
        normalized = Math.max(0f, Math.min(1f, normalized));

        // Exponent < 1 BROADENS peaks (pushes mid-values up → gradual slopes).
        // Exponent > 1 sharpens/pinches — bad for skimmable mountains.
        normalized = (float) Math.pow(normalized, 0.78);

        float slopeMag = (float) Math.sqrt(dx * dx + dz * dz);

        return new float[]{ normalized, slopeMag };
    }
}