package com.leaf.game.entity;

import com.leaf.game.core.GameConfig;

import java.util.Random;

/**
 * WaveTable — the SINGLE place the difficulty curve is authored.
 *
 * ── Why this exists ──────────────────────────────────────────────────────────
 *  Wave composition used to be hardcoded probability bands inside
 *  {@code EnemyManager.pickType()}. Now each wave is one readable line: how many
 *  enemies, and a weighted mix of types. Themed waves ("all spiders"), boss waves
 *  and the whole curve are authored here without touching spawn code.
 *
 * ── How to author a wave ─────────────────────────────────────────────────────
 *  Add or edit a row in {@link #forWave}. Weights are relative (they don't need to
 *  sum to 1). {@code count} is how many enemies the wave tries to place; it is
 *  still capped by {@link GameConfig#spawnMaxEnemies} at spawn time.
 *
 *    case 4 -> wave(8).mix(SPIDER, 1)                        // "all spiders" wave
 *    case 8 -> wave(1).mix(TREANT, 1)                        // boss wave
 *
 *  Waves past the authored ones fall through to {@link #endless}, which keeps
 *  scaling so the game never runs out of content.
 */
public final class WaveTable {

    /** One wave's definition: a count plus a weighted type mix. */
    public static final class WaveDef {
        public final int count;
        private final Enemy.Type[] types  = new Enemy.Type[Enemy.Type.values().length];
        private final float[]      weights = new float[Enemy.Type.values().length];
        private int   n     = 0;
        private float total = 0f;

        private WaveDef(int count) { this.count = count; }

        /** Add a type with a relative weight. Chainable. */
        public WaveDef mix(Enemy.Type type, float weight) {
            if (weight <= 0f) return this;
            types[n] = type; weights[n] = weight; n++;
            total += weight;
            return this;
        }

        /** Roll one enemy type from this wave's weighted mix. */
        public Enemy.Type pick(Random rng) {
            if (n == 0) return Enemy.Type.ZOMBIE;   // empty mix — never leave a wave blank
            float r = rng.nextFloat() * total;
            for (int i = 0; i < n; i++) {
                r -= weights[i];
                if (r < 0f) return types[i];
            }
            return types[n - 1];
        }
    }

    private WaveTable() {}

    private static WaveDef wave(int count) { return new WaveDef(count); }

    /** Default size curve: base + wave/2 (the original formula). */
    private static int defaultCount(int waveNumber) {
        return GameConfig.spawnWaveBase + waveNumber / 2;
    }

    /**
     * The definition of wave {@code waveNumber} (1-based). Built fresh each call so
     * live GameConfig tweaks (e.g. spawnWaveBase) keep working.
     */
    public static WaveDef forWave(int waveNumber) {
        int c = defaultCount(waveNumber);
        return switch (waveNumber) {
            // Waves 1–2: zombies, slimes and a few throwers — learn to fight.
            case 1, 2 -> wave(c)
                    .mix(Enemy.Type.ZOMBIE,  40)
                    .mix(Enemy.Type.SLIME,   30)
                    .mix(Enemy.Type.THROWER, 30);

            // Waves 3–6: spiders join, the rare golem tank appears.
            case 3, 4, 5, 6 -> wave(c)
                    .mix(Enemy.Type.GOLEM,    8)
                    .mix(Enemy.Type.SLIME,   32)
                    .mix(Enemy.Type.SPIDER,  15)
                    .mix(Enemy.Type.ZOMBIE,  15)
                    .mix(Enemy.Type.THROWER, 30);

            default -> endless(waveNumber, c);
        };
    }

    /** Waves 7+: golem share climbs 10% → 35%; the rest split slime/zombie/thrower. */
    private static WaveDef endless(int waveNumber, int count) {
        float golem = Math.min(0.35f, 0.10f + (waveNumber - 7) * 0.025f);
        return wave(count)
                .mix(Enemy.Type.GOLEM,   golem)
                .mix(Enemy.Type.SLIME,   0.25f)
                .mix(Enemy.Type.ZOMBIE,  0.25f)
                .mix(Enemy.Type.THROWER, 0.50f - golem);
    }
}
