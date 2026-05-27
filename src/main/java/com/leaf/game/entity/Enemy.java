package com.leaf.game.entity;

import com.leaf.game.world.World;
import com.leaf.game.world.Chunk;
import org.joml.Vector3f;

/**
 * Enemy — a stationary target dummy for testing all combat abilities.
 *
 * Design notes:
 *   • No AI for now — it just stands still and absorbs damage.
 *   • Health bar is rendered by Window above the model.
 *   • EnemyManager processes damage events; enemies never query the world directly
 *     except during gravity (future AI hook).
 *   • RADIUS is the capsule half-width used for explosion overlap tests.
 *
 * Future hooks (ready but not wired yet):
 *   • onDeath() callback — override in subclasses for drops, animations, etc.
 *   • update(dt, world) — skeleton for future movement / AI.
 */
public class Enemy {

    // ── Collision / hit geometry ───────────────────────────────────────────────
    /** Horizontal radius (blocks) — used for sphere-overlap damage checks. */
    public static final float RADIUS      = 0.5f;
    /** Vertical half-height above position.y — used for melee arc checks. */
    public static final float HALF_HEIGHT = 1.0f;

    // ── Identity ───────────────────────────────────────────────────────────────
    private static int nextId = 0;
    public  final  int id;

    // ── State ──────────────────────────────────────────────────────────────────
    public final  Vector3f position;
    public        float    health;
    public final  float    maxHealth;
    public        boolean  alive = true;

    // ── Visual ────────────────────────────────────────────────────────────────
    /** Accumulated seconds — drives the death-flash / hit-flash effects in Window. */
    public float hitFlashTimer = 0f;

    // ─────────────────────────────────────────────────────────────────────────
    public Enemy(float x, float y, float z, float maxHp) {
        this.id        = nextId++;
        this.position  = new Vector3f(x, y, z);
        this.maxHealth = maxHp;
        this.health    = maxHp;
    }

    /** Convenience constructor with default 50 HP. */
    public Enemy(float x, float y, float z) {
        this(x, y, z, 50f);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Damage
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Apply damage and trigger a hit flash.
     * Returns true if this blow killed the enemy.
     */
    public boolean applyDamage(float amount) {
        if (!alive) return false;
        health        = Math.max(0f, health - amount);
        hitFlashTimer = 0.18f;  // Window reads this for red flash
        if (health <= 0f) {
            alive = false;
            return true;
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Per-frame update (stub — reserved for future AI / movement)
    // ─────────────────────────────────────────────────────────────────────────

    public void update(float dt, World world) {
        // Decay hit flash
        if (hitFlashTimer > 0f) hitFlashTimer = Math.max(0f, hitFlashTimer - dt);

        // Gravity: pin enemy to terrain surface
        if (alive) {
            int bx = (int) Math.floor(position.x);
            int bz = (int) Math.floor(position.z);
            for (int by = (int) position.y; by >= 0; by--) {
                if (world.getBlock(bx, by, bz).isSolid()) {
                    position.y = by + 1f;
                    break;
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Visibility / targeting helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Centre of the enemy's body — used for LOS checks and camera aim.
     * Offset by HALF_HEIGHT so we aim at the chest, not the feet.
     */
    public Vector3f getCentre() {
        return new Vector3f(position.x, position.y + HALF_HEIGHT, position.z);
    }

    /** Returns true if a 3D point lies within this enemy's physical collision cylinder. */
    public boolean isPointInside(Vector3f p) {
        if (p.y < position.y || p.y > position.y + 2.0f * HALF_HEIGHT) return false;
        float dx = p.x - position.x;
        float dz = p.z - position.z;
        return (dx * dx + dz * dz) <= RADIUS * RADIUS;
    }
}
