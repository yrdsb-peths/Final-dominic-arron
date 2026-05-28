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
 *     Wave 1–2 : PREDATOR + THROWER
 *     Wave 3–6 : PREDATOR + THROWER + rare GOLEM
 *     Wave 7+  : all three, GOLEM frequency grows each wave
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
    public static final float EXPLOSION_DAMAGE = 10f; // default for non-player explosions
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

    // ── Enemy projectiles (boulders, thrown rocks) ────────────────────────────
    public static class EnemyProjectile {
        public final Vector3f pos;
        public final Vector3f vel;
        public float          lifetime;
        public float          damage;
        public boolean        alive   = true;
        public final int      ownerId; // enemy id that launched this

        public EnemyProjectile(Vector3f pos, Vector3f vel, float damage, int ownerId) {
            this.pos      = new Vector3f(pos);
            this.vel      = new Vector3f(vel);
            this.damage   = damage;
            this.lifetime = GameConfig.projectileLifetime;
            this.ownerId  = ownerId;
        }
    }
    /** Window renders these as small stone blocks. */
    public final List<EnemyProjectile> projectiles = new ArrayList<>();

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

    /** Spawn a THROWER at the given world position. */
    public Enemy spawnAt(float x, float y, float z) {
        return spawnAt(x, y, z, Enemy.Type.THROWER);
    }

    /** Spawn a typed enemy at the given world position. */
    public Enemy spawnAt(float x, float y, float z, Enemy.Type type) {
        Enemy e = new Enemy(x, y, z, type);
        enemies.add(e);
        return e;
    }

    /** Convenience overload — spawns a THROWER. */
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

            // Golem slam: framePlayerDamage is used as the slam signal; check range
            if (e.type == Enemy.Type.GOLEM && e.framePlayerDamage >= GameConfig.golemSlamDamage - 1f) {
                float dx = playerPos.x - e.position.x;
                float dz = playerPos.z - e.position.z;
                float distSq = dx * dx + dz * dz;
                if (distSq <= GameConfig.golemSlamRadius * GameConfig.golemSlamRadius) {
                    pendingPlayerDamage += e.framePlayerDamage;
                } // else: slam missed the player — no damage accumulated
                // zero out so it doesn't also accumulate below
                e.framePlayerDamage = 0f;
            }
            pendingPlayerDamage += e.framePlayerDamage;

            // Spawn projectile when thrower/golem signals wantsToThrow
            if (e.wantsToThrow) {
                spawnProjectileAt(e, playerPos);
            }
        }

        // ── Tick enemy projectiles ─────────────────────────────────────────────
        Vector3f playerCentre = new Vector3f(playerPos.x, playerPos.y + 0.9f, playerPos.z);
        for (EnemyProjectile proj : projectiles) {
            if (!proj.alive) continue;
            proj.lifetime -= dt;
            if (proj.lifetime <= 0f) { proj.alive = false; continue; }

            // Arc gravity
            proj.vel.y -= GameConfig.projectileGravity * dt;
            proj.pos.x += proj.vel.x * dt;
            proj.pos.y += proj.vel.y * dt;
            proj.pos.z += proj.vel.z * dt;

            // Hit ground
            int bx = (int) Math.floor(proj.pos.x);
            int by = (int) Math.floor(proj.pos.y);
            int bz = (int) Math.floor(proj.pos.z);
            if (by < 0 || by >= com.leaf.game.world.Chunk.HEIGHT
                    || world.getBlock(bx, by, bz).isSolid()) {
                proj.alive = false;
                continue;
            }

            // Hit player (1-block radius check)
            float dpx = proj.pos.x - playerCentre.x;
            float dpy = proj.pos.y - playerCentre.y;
            float dpz = proj.pos.z - playerCentre.z;
            if (dpx*dpx + dpy*dpy + dpz*dpz <= 1.0f) {
                pendingPlayerDamage += proj.damage;
                proj.alive = false;
            }
        }
        projectiles.removeIf(p -> !p.alive);

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
    //  Projectile helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void spawnProjectileAt(Enemy e, Vector3f playerPos) {
        // Aim at player's centre with some parabolic arc
        Vector3f from = new Vector3f(e.getCentre());
        float dx = playerPos.x - from.x;
        float dz = playerPos.z - from.z;
        float horizDist = (float) Math.sqrt(dx * dx + dz * dz);
        if (horizDist < 0.5f) return;

        float speed = (e.type == Enemy.Type.GOLEM)
                ? GameConfig.golemThrowSpeed : GameConfig.throwerThrowSpeed;
        float damage = (e.type == Enemy.Type.GOLEM)
                ? GameConfig.golemThrowDamage : GameConfig.throwerThrowDamage;

        float vx = (dx / horizDist) * speed;
        float vz = (dz / horizDist) * speed;
        // Upward arc: enough lift to arc over the horizontal distance
        float vy = horizDist * 0.5f + 5f;

        projectiles.add(new EnemyProjectile(from, new Vector3f(vx, vy, vz), damage, e.id));
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
     * Wave 1–2  : ZOMBIE (slow melee) + THROWER (skeleton archer)
     * Wave 3–6  : All three — mostly ZOMBIE + THROWER, rare GOLEM (tank)
     * Wave 7+   : GOLEM frequency climbs to ~35%, rest split zombie/thrower
     */
    private Enemy.Type pickType() {
        float r = rng.nextFloat();
        if (waveNumber <= 2) {
            // Early waves: shambling zombies and a few skeleton archers
            return r < 0.65f ? Enemy.Type.ZOMBIE : Enemy.Type.THROWER;
        } else if (waveNumber <= 6) {
            // Mid waves: all three; golems start appearing rarely
            if (r < 0.08f)      return Enemy.Type.GOLEM;
            else if (r < 0.55f) return Enemy.Type.ZOMBIE;
            else                 return Enemy.Type.THROWER;
        } else {
            // Late waves: more golems each wave, rest split zombie/archer
            float golemChance = Math.min(0.35f, 0.10f + (waveNumber - 7) * 0.025f);
            if (r < golemChance)             return Enemy.Type.GOLEM;
            else if (r < golemChance + 0.50f) return Enemy.Type.ZOMBIE;
            else                              return Enemy.Type.THROWER;
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
                    // Confirm open sky above — reject cave floors reachable via sky-shafts
                    boolean openSky = true;
                    for (int sy = by + 3; sy < Chunk.HEIGHT; sy++) {
                        if (world.getBlock(bx, sy, bz).isSolid()) { openSky = false; break; }
                    }
                    if (openSky) return new Vector3f(sx, by + 1f, sz);
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
     * @param event float[4] — { cx, cy, cz, radius }  OR  float[5] — { cx, cy, cz, radius, damage }
     */
    public void processExplosion(float[] event) {
        float damage = (event.length >= 5) ? event[4] : EXPLOSION_DAMAGE;
        processExplosion(event, damage);
    }

    /**
     * Process a sphere explosion with a custom damage value.
     * @param event  float[4] — { cx, cy, cz, radius }
     * @param damage hit points to apply to each enemy inside the sphere
     */
    public void processExplosion(float[] event, float damage) {
        float cx = event[0], cy = event[1], cz = event[2], r = event[3];
        float r2  = r * r;
        for (Enemy e : enemies) {
            if (!e.alive) continue;
            Vector3f centre = e.getCentre();
            float dx = centre.x - cx, dy = centre.y - cy, dz = centre.z - cz;
            if (dx*dx + dy*dy + dz*dz <= r2) {
                e.applyDamage(damage);
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
                e.applyDamage(GameConfig.meleeDamage);
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

        // Project the look direction onto the horizontal (XZ) plane so that enemies
        // standing at a different elevation (below a cliff, on a hillside, etc.) are
        // reachable as long as the player is aiming roughly toward them horizontally.
        float lhx = lookDir.x, lhz = lookDir.z;
        float lhLen = (float) Math.sqrt(lhx * lhx + lhz * lhz);
        if (lhLen < 1e-6f) { lhx = 1f; lhz = 0f; lhLen = 1f; }   // looking straight up/down edge-case
        float lookHX = lhx / lhLen;
        float lookHZ = lhz / lhLen;

        for (Enemy e : enemies) {
            if (!e.alive) continue;
            Vector3f centre = e.getCentre();
            float dist = new Vector3f(centre).sub(eyePos).length();
            if (dist > maxRange) continue;

            // Horizontal vector from eye to enemy centre
            float ex = centre.x - eyePos.x;
            float ez = centre.z - eyePos.z;
            float ehLen = (float) Math.sqrt(ex * ex + ez * ez);
            float dot;
            if (ehLen < 1e-6f) {
                // Enemy is almost directly above/below — treat as fully aligned
                dot = 1f;
            } else {
                dot = (lookHX * (ex / ehLen)) + (lookHZ * (ez / ehLen));
            }

            if (dot > bestDot && StandController.hasLOS(world, eyePos, centre)) {
                bestDot = dot;
                best    = e;
            }
        }
        return best;
    }

    /**
     * Deals splash damage and sends every enemy inside the smash blast radius
     * flying outward from the impact point.
     *
     * @param ix  impact centre X (world block coordinate)
     * @param iy  impact centre Y
     * @param iz  impact centre Z
     * @param craterRadius  the smash crater radius (used to derive blast zone)
     */
    public void processSmashKnockback(int ix, int iy, int iz, int craterRadius) {
        float blastRadius = craterRadius * GameConfig.smashSplashRadiusMult;
        float cx = ix + 0.5f;
        float cy = iy + 0.5f;
        float cz = iz + 0.5f;

        for (Enemy e : enemies) {
            if (!e.alive) continue;
            Vector3f centre = e.getCentre();
            float dx = centre.x - cx;
            float dy = centre.y - cy;
            float dz = centre.z - cz;
            float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist > blastRadius) continue;

            // Damage: linear falloff — full at centre, zero at edge
            float t   = 1f - dist / blastRadius;
            e.applyDamage(GameConfig.smashSplashDamage * t);

            // Knockback: radial outward burst + upward component
            float strength = GameConfig.smashKnockbackStrength * t;
            float hDist = (float) Math.sqrt(dx * dx + dz * dz);
            float kx, kz;
            if (hDist < 0.1f) {
                kx = 0f; kz = 0f;
            } else {
                kx = (dx / hDist) * strength;
                kz = (dz / hDist) * strength;
            }
            float ky = strength * 0.55f;   // upward toss
            e.applyKnockback(kx, ky, kz);
        }
    }
}
