package com.leaf.game.entity;

import com.leaf.game.world.Chunk;
import com.leaf.game.world.World;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * EnemyManager — owns all live enemies and routes damage events to them.
 *
 * ── Integration contract ──────────────────────────────────────────────────
 *   Window.java:
 *     • Instantiates one EnemyManager.
 *     • Passes it to player.stand.setEnemyManager(em) after construction.
 *     • Calls em.update(dt, world) once per frame.
 *     • Drains AttackController.pendingExplosions → em.processExplosion(…)
 *     • Drains AttackController.pendingMeleeArcs  → em.processMeleeArc(…)
 *     • Drains StandController.pendingExplosions  → em.processExplosion(…)
 *     • Renders alive enemies from em.getEnemies() list.
 *     • On P key press: em.spawnAt(crosshairHit).
 *
 *   StandController.tickStandShoot():
 *     • Calls em.findClosestVisible(world, standPos, lookDir) for auto-aim
 *       when the player shoots while NOT in stand perspective.
 *
 * ── Damage event formats ──────────────────────────────────────────────────
 *   Explosion  float[4]: { centreX, centreY, centreZ, radius }
 *              Any enemy whose centre falls within the sphere takes
 *              EXPLOSION_DAMAGE points.
 *
 *   Melee arc  float[7]: { originX, originY, originZ, dirX, dirY, dirZ, range }
 *              Any enemy within `range` blocks of the origin whose centre
 *              is within ARC_HALF_ANGLE of `dir` takes MELEE_DAMAGE points.
 */
public class EnemyManager {

    // ── Damage constants — tweak freely ───────────────────────────────────────
    public static final float EXPLOSION_DAMAGE   = 35f;
    public static final float MELEE_DAMAGE       = 60f;
    /** Half-angle (radians) of the melee cleave arc — ~60° each side. */
    public static final float ARC_HALF_ANGLE     = (float) Math.toRadians(60.0);

    // ─────────────────────────────────────────────────────────────────────────

    private final List<Enemy> enemies = new ArrayList<>();

    // ─────────────────────────────────────────────────────────────────────────
    //  Public accessors
    // ─────────────────────────────────────────────────────────────────────────

    /** Read-only view of all enemies (alive and freshly dead) for rendering. */
    public List<Enemy> getEnemies() { return enemies; }

    // ─────────────────────────────────────────────────────────────────────────
    //  Spawn
    // ─────────────────────────────────────────────────────────────────────────

    /** Spawn a new enemy at the given world position. */
    public Enemy spawnAt(float x, float y, float z) {
        Enemy e = new Enemy(x, y, z);
        enemies.add(e);
        return e;
    }

    /** Convenience overload. */
    public Enemy spawnAt(Vector3f pos) {
        return spawnAt(pos.x, pos.y, pos.z);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Per-frame update
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Advance all enemies and remove dead ones that have fully faded out.
     * Call once per frame from Window.java.
     */
    public void update(float dt, World world) {
        for (Enemy e : enemies) {
            e.update(dt, world);
        }
        // Remove dead enemies whose hit-flash has expired (gives Window a frame to
        // play the death visuals before the enemy is removed).
        enemies.removeIf(e -> !e.alive && e.hitFlashTimer <= 0f);
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

        // Normalise direction
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
            // Dot product between normalised enemy direction and strike direction
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
     * Find the closest alive enemy that has LOS from {@code fromPos}.
     * Returns null if no visible enemy exists.
     *
     * Used by StandController so it can auto-aim the redirect bolt at the
     * nearest enemy when the player fires without being in stand perspective.
     *
     * @param world    for LOS raycasting
     * @param fromPos  the stand's world position
     * @param maxRange ignore enemies farther than this (blocks)
     */
    public Enemy findClosestVisible(World world, Vector3f fromPos, float maxRange) {
        Enemy  best     = null;
        float  bestDist = maxRange + 1f;

        for (Enemy e : enemies) {
            if (!e.alive) continue;
            Vector3f centre = e.getCentre();
            float dist = new Vector3f(centre).sub(fromPos).length();
            if (dist >= bestDist) continue;
            // LOS check using StandController's static helper
            if (StandController.hasLOS(world, fromPos, centre)) {
                best     = e;
                bestDist = dist;
            }
        }
        return best;
    }

    /**
     * Find the enemy whose centre is closest to the player's crosshair direction.
     * Used by the player-body (non-stand) Manhattan Transfer auto-aim.
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
