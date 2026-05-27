package com.leaf.game.entity;

import com.leaf.game.core.GameConfig;
import com.leaf.game.world.Chunk;
import com.leaf.game.world.World;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * EnemyManager — owns all live enemies, routes damage events, and drives
 * automatic wave spawning.
 *
 * ── Integration contract ──────────────────────────────────────────────────
 *   Window.java:
 *     • Instantiates one EnemyManager.
 *     • Passes it to player.stand.setEnemyManager(em) after construction.
 *     • Calls em.update(dt, world, player.position) once per frame.
 *     • Drains AttackController.pendingExplosions → em.processExplosion(…)
 *     • Drains AttackController.pendingMeleeArcs  → em.processMeleeArc(…)
 *     • Drains StandController.pendingExplosions  → em.processExplosion(…)
 *     • Renders alive enemies from em.getEnemies() list.
 *     • Reads em.pendingPlayerDamage and drains it into player.health.
 *     • On P key press: em.spawnAt(crosshairHit).
 *     • Reads em.getWaveNumber() for HUD wave counter display.
 *
 *   StandController.tickStandShoot():
 *     • Calls em.findClosestVisible(world, standPos, maxRange) for auto-aim.
 *
 * ── Wave spawning ─────────────────────────────────────────────────────────
 *   Every GameConfig.spawnWaveInterval seconds, a wave of enemies spawns
 *   around the player.  Wave size = spawnWaveBase + waveNumber / 2.
 *   Composition grows more varied as waves increase:
 *     Wave 1–2 : GRUNTs only
 *     Wave 3–5 : mostly GRUNTs, one STALKER
 *     Wave 6+  : mixed GRUNT / STALKER / BRUTE
 *   Spawn points are chosen by picking a random angle at a random distance
 *   in [spawnMinDist, spawnMaxDist] from the player, then scanning downward
 *   from near sky-limit to find a solid surface to land on.
 *
 * ── Damage event formats ──────────────────────────────────────────────────
 *   Explosion  float[4]: { centreX, centreY, centreZ, radius }
 *   Melee arc  float[7]: { originX, originY, originZ, dirX, dirY, dirZ, range }
 */
public class EnemyManager {

    // ── Damage constants ──────────────────────────────────────────────────────
    public static final float EXPLOSION_DAMAGE = 35f;
    public static final float MELEE_DAMAGE     = 60f;
    /** Half-angle (radians) of the melee cleave arc — ~60° each side. */
    public static final float ARC_HALF_ANGLE   = (float) Math.toRadians(60.0);

    // ── Enemy list ────────────────────────────────────────────────────────────
    private final List<Enemy> enemies = new ArrayList<>();

    // ── Player damage accumulator — drained by Window into player.health ──────
    /** Damage dealt to the player since last drain. Window reads and zeroes this. */
    public float pendingPlayerDamage = 0f;

    // ── Wave spawning state ───────────────────────────────────────────────────
    private float waveTimer  = GameConfig.spawnWaveInterval; // counts down to 0 → spawn
    private int   waveNumber = 0;
    private final Random rng = new Random();

    // ─────────────────────────────────────────────────────────────────────────
    //  Public accessors
    // ─────────────────────────────────────────────────────────────────────────

    /** Read-only view of all enemies (alive and freshly dead) for rendering. */
    public List<Enemy> getEnemies() { return enemies; }

    /** Current wave number (1-based when the first wave has spawned). */
    public int getWaveNumber() { return waveNumber; }

    /** Seconds until the next automatic wave spawns. */
    public float getWaveTimer() { return waveTimer; }

    // ─────────────────────────────────────────────────────────────────────────
    //  Manual spawn (P key from Window)
    // ─────────────────────────────────────────────────────────────────────────

    /** Spawn a GRUNT at the given world position. */
    public Enemy spawnAt(float x, float y, float z) {
        return spawnAt(x, y, z, Enemy.Type.GRUNT);
    }

    /** Spawn a typed enemy at the given world position. */
    public Enemy spawnAt(float x, float y, float z, Enemy.Type type) {
        Enemy e = new Enemy(x, y, z, type);
        enemies.add(e);
        return e;
    }

    /** Convenience overload — spawns a GRUNT. */
    public Enemy spawnAt(Vector3f pos) {
        return spawnAt(pos.x, pos.y, pos.z);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Per-frame update
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Advance all enemies, accumulate player damage, tick the wave spawner,
     * and prune dead enemies whose death flash has expired.
     *
     * @param dt        scaled delta time
     * @param world     world reference for collision and LOS
     * @param playerPos player feet position
     */
    public void update(float dt, World world, Vector3f playerPos) {
        // ── Update every enemy ─────────────────────────────────────────────────
        for (Enemy e : enemies) {
            e.update(dt, world, playerPos, enemies);
            pendingPlayerDamage += e.framePlayerDamage;
        }

        // ── Remove dead enemies once death flash is done ───────────────────────
        enemies.removeIf(e -> !e.alive && e.hitFlashTimer <= 0f);

        // ── Wave spawner ───────────────────────────────────────────────────────
        if (enemies.size() < GameConfig.spawnMaxEnemies) {
            waveTimer -= dt;
            if (waveTimer <= 0f) {
                waveTimer = GameConfig.spawnWaveInterval;
                spawnWave(world, playerPos);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Wave spawning logic
    // ─────────────────────────────────────────────────────────────────────────

    private void spawnWave(World world, Vector3f playerPos) {
        waveNumber++;

        int count = Math.min(
            GameConfig.spawnWaveBase + waveNumber / 2,
            GameConfig.spawnMaxEnemies - enemies.size()
        );
        if (count <= 0) return;

        for (int i = 0; i < count; i++) {
            Vector3f spawnPos = findSpawnPoint(world, playerPos);
            if (spawnPos == null) continue; // no valid surface found
            Enemy.Type type = pickType();
            spawnAt(spawnPos.x, spawnPos.y, spawnPos.z, type);
        }
    }

    /**
     * Choose an enemy type biased toward tougher enemies in later waves.
     *
     * Wave 1–2  : GRUNT only
     * Wave 3–5  : 70% GRUNT, 30% STALKER
     * Wave 6–9  : 50% GRUNT, 30% STALKER, 20% BRUTE
     * Wave 10+  : 35% GRUNT, 40% STALKER, 25% BRUTE
     */
    private Enemy.Type pickType() {
        float r = rng.nextFloat();
        if (waveNumber <= 2) {
            return Enemy.Type.GRUNT;
        } else if (waveNumber <= 5) {
            return r < 0.70f ? Enemy.Type.GRUNT : Enemy.Type.STALKER;
        } else if (waveNumber <= 9) {
            if (r < 0.50f) return Enemy.Type.GRUNT;
            if (r < 0.80f) return Enemy.Type.STALKER;
            return Enemy.Type.BRUTE;
        } else {
            if (r < 0.35f) return Enemy.Type.GRUNT;
            if (r < 0.75f) return Enemy.Type.STALKER;
            return Enemy.Type.BRUTE;
        }
    }

    /**
     * Find a valid surface spawn point at a random angle around the player,
     * at a random horizontal distance in [spawnMinDist, spawnMaxDist].
     *
     * Scans downward from just below the world ceiling to find the first
     * solid block, then places the enemy one block above it.
     *
     * Returns null if no suitable surface is found after several attempts.
     */
    private Vector3f findSpawnPoint(World world, Vector3f playerPos) {
        float minD = GameConfig.spawnMinDist;
        float maxD = GameConfig.spawnMaxDist;

        // Try up to 8 random angles
        for (int attempt = 0; attempt < 8; attempt++) {
            float angle = rng.nextFloat() * (float)(2 * Math.PI);
            float dist  = minD + rng.nextFloat() * (maxD - minD);

            float sx = playerPos.x + (float) Math.cos(angle) * dist;
            float sz = playerPos.z + (float) Math.sin(angle) * dist;

            // Scan downward from near the top of the world to find a surface
            int bx = (int) Math.floor(sx);
            int bz = (int) Math.floor(sz);

            for (int by = Chunk.HEIGHT - 2; by >= 1; by--) {
                if (world.getBlock(bx, by, bz).isSolid()
                        && !world.getBlock(bx, by + 1, bz).isSolid()
                        && !world.getBlock(bx, by + 2, bz).isSolid()) {
                    // Two clear blocks above solid ground — good spawn
                    return new Vector3f(sx, by + 1f, sz);
                }
            }
        }
        return null; // all attempts failed (e.g. all water/cave)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Damage routing
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Process a sphere explosion.
     * @param event float[4] — { cx, cy, cz, radius }
     */
    public void processExplosion(float[] event) {
        float cx = event[0], cy = event[1], cz = event[2], r = event[3];
        float r2  = r * r;
        for (Enemy e : enemies) {
            if (!e.alive) continue;
            Vector3f centre = e.getCentre();
            float dx = centre.x - cx, dy = centre.y - cy, dz = centre.z - cz;
            if (dx*dx + dy*dy + dz*dz <= r2) {
                e.applyDamage(EXPLOSION_DAMAGE);
            }
        }
    }

    /**
     * Process a melee arc sweep.
     * @param event float[7] — { ox, oy, oz, dx, dy, dz, range }
     */
    public void processMeleeArc(float[] event) {
        float ox = event[0], oy = event[1], oz = event[2];
        float dx = event[3], dy = event[4], dz = event[5], range = event[6];

        float dLen = (float) Math.sqrt(dx*dx + dy*dy + dz*dz);
        if (dLen < 0.001f) return;
        float ndx = dx/dLen, ndy = dy/dLen, ndz = dz/dLen;

        float cosHalf = (float) Math.cos(ARC_HALF_ANGLE);

        for (Enemy e : enemies) {
            if (!e.alive) continue;
            Vector3f centre = e.getCentre();
            float ex = centre.x - ox, ey = centre.y - oy, ez = centre.z - oz;
            float dist = (float) Math.sqrt(ex*ex + ey*ey + ez*ez);
            if (dist > range || dist < 0.001f) continue;
            float dot = (ex/dist)*ndx + (ey/dist)*ndy + (ez/dist)*ndz;
            if (dot >= cosHalf) {
                e.applyDamage(MELEE_DAMAGE);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Targeting helpers (used by StandController for auto-aim)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Find the closest alive enemy with LOS from {@code fromPos}.
     *
     * @param world    for LOS raycasting
     * @param fromPos  origin (e.g. stand position)
     * @param maxRange ignore enemies farther than this (blocks)
     */
    public Enemy findClosestVisible(World world, Vector3f fromPos, float maxRange) {
        Enemy best     = null;
        float bestDist = maxRange + 1f;

        for (Enemy e : enemies) {
            if (!e.alive) continue;
            Vector3f centre = e.getCentre();
            float dist = new Vector3f(centre).sub(fromPos).length();
            if (dist >= bestDist) continue;
            if (StandController.hasLOS(world, fromPos, centre)) {
                best     = e;
                bestDist = dist;
            }
        }
        return best;
    }

    /**
     * Find the enemy whose centre is most aligned with the player's look direction.
     *
     * @param eyePos  player eye position
     * @param lookDir player look direction (normalised)
     * @param maxRange maximum targeting range
     */
    public Enemy findMostAligned(World world, Vector3f eyePos, Vector3f lookDir, float maxRange) {
        Enemy best    = null;
        float bestDot = -2f;

        for (Enemy e : enemies) {
            if (!e.alive) continue;
            Vector3f centre = e.getCentre();
            float dist = new Vector3f(centre).sub(eyePos).length();
            if (dist > maxRange) continue;
            Vector3f toEnemy = new Vector3f(centre).sub(eyePos).normalize();
            float dot = lookDir.dot(toEnemy);
            if (dot > bestDot && StandController.hasLOS(world, eyePos, centre)) {
                bestDot = dot;
                best    = e;
            }
        }
        return best;
    }
}
