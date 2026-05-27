package com.leaf.game.entity;

import com.leaf.game.core.GameConfig;
import com.leaf.game.world.World;
import com.leaf.game.world.Chunk;
import org.joml.Vector3f;

import java.util.List;

/**
 * Enemy — an AI-driven combat target.
 *
 * ── Three types ───────────────────────────────────────────────────────────────
 *   GRUNT   standard pursuer — medium speed, medium health
 *   BRUTE   slow tank        — low speed, very high health, hits hard
 *   STALKER fast flanker     — high speed, fragile, long aggro range
 *
 * ── AI state machine ─────────────────────────────────────────────────────────
 *   IDLE     → player enters aggroRange → ALERTED
 *   ALERTED  → brief dramatic pause (alertTimer) → CHASE
 *   CHASE    → within attackRange → ATTACK
 *             → player > leashRange away → IDLE
 *   ATTACK   → player leaves attackRange → CHASE
 *             → enemy dead → removed by EnemyManager
 *
 * ── Movement ─────────────────────────────────────────────────────────────────
 *   Direct horizontal move toward player each frame.
 *   Gravity + velocityY for realistic falling.
 *   Jumps over single blocks (checks one-block clearance above).
 *   If horizontally blocked and can't jump, tries a perpendicular strafe
 *   that alternates direction each STRAFE_FLIP_SECS seconds.
 *   Separation force prevents stacking with other enemies.
 *
 * ── Damage ───────────────────────────────────────────────────────────────────
 *   framePlayerDamage accumulates each update() call.
 *   EnemyManager sums these into pendingPlayerDamage, drained by Window.
 */
public class Enemy {

    // ═════════════════════════════════════════════════════════════════════════
    //  Enums
    // ═════════════════════════════════════════════════════════════════════════

    public enum Type { GRUNT, BRUTE, STALKER }

    public enum State { IDLE, ALERTED, CHASE, ATTACK }

    // ═════════════════════════════════════════════════════════════════════════
    //  Constants
    // ═════════════════════════════════════════════════════════════════════════

    public static final float RADIUS      = 0.5f;
    public static final float HALF_HEIGHT = 1.0f;

    // How often (seconds) the strafe alternation flips to avoid infinite wall-stuck
    private static final float STRAFE_FLIP_SECS = 0.8f;
    private static final float GRAVITY           = 28f;
    private static final float MAX_FALL_SPEED    = 30f;

    // ═════════════════════════════════════════════════════════════════════════
    //  Identity
    // ═════════════════════════════════════════════════════════════════════════

    private static int nextId = 0;
    public  final  int id;
    public  final  Type type;

    // ═════════════════════════════════════════════════════════════════════════
    //  Stats (copied from GameConfig at construction)
    // ═════════════════════════════════════════════════════════════════════════

    public final  float maxHealth;
    public        float health;
    private final float speed;
    private final float damagePerSec;
    private final float aggroRange;
    private final float attackRange;

    // ═════════════════════════════════════════════════════════════════════════
    //  World state
    // ═════════════════════════════════════════════════════════════════════════

    public  final  Vector3f position;
    public         boolean  alive      = true;
    private        float    velocityY  = 0f;
    private        boolean  onGround   = false;

    // ═════════════════════════════════════════════════════════════════════════
    //  AI state
    // ═════════════════════════════════════════════════════════════════════════

    public  State state       = State.IDLE;
    private float alertTimer  = 0f;
    /** Accumulated strafe-toggle clock. */
    private float strafeTimer = 0f;
    /** +1 or -1, flips every STRAFE_FLIP_SECS seconds. */
    private float strafeSign  = 1f;

    // ═════════════════════════════════════════════════════════════════════════
    //  Visual / output
    // ═════════════════════════════════════════════════════════════════════════

    /** Window reads this for the red hit-flash vignette. */
    public float hitFlashTimer = 0f;
    /** Damage dealt to the player this frame — accumulated by EnemyManager. */
    public float framePlayerDamage = 0f;

    // ═════════════════════════════════════════════════════════════════════════
    //  Construction
    // ═════════════════════════════════════════════════════════════════════════

    public Enemy(float x, float y, float z, Type type) {
        this.id       = nextId++;
        this.type     = type;
        this.position = new Vector3f(x, y, z);

        switch (type) {
            case GRUNT -> {
                maxHealth    = GameConfig.gruntHealth;
                speed        = GameConfig.gruntSpeed;
                damagePerSec = GameConfig.gruntDamagePerSec;
                aggroRange   = GameConfig.gruntAggroRange;
                attackRange  = GameConfig.gruntAttackRange;
            }
            case BRUTE -> {
                maxHealth    = GameConfig.bruteHealth;
                speed        = GameConfig.bruteSpeed;
                damagePerSec = GameConfig.bruteDamagePerSec;
                aggroRange   = GameConfig.bruteAggroRange;
                attackRange  = GameConfig.bruteAttackRange;
            }
            case STALKER -> {
                maxHealth    = GameConfig.stalkerHealth;
                speed        = GameConfig.stalkerSpeed;
                damagePerSec = GameConfig.stalkerDamagePerSec;
                aggroRange   = GameConfig.stalkerAggroRange;
                attackRange  = GameConfig.stalkerAttackRange;
            }
            default -> {
                maxHealth    = 50f;
                speed        = 3f;
                damagePerSec = 8f;
                aggroRange   = 20f;
                attackRange  = 1.5f;
            }
        }
        this.health = maxHealth;
    }

    /** Convenience: default GRUNT at given position. */
    public Enemy(float x, float y, float z) {
        this(x, y, z, Type.GRUNT);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Damage API
    // ═════════════════════════════════════════════════════════════════════════

    /** Apply damage and trigger hit-flash. Returns true if the kill shot. */
    public boolean applyDamage(float amount) {
        if (!alive) return false;
        health        = Math.max(0f, health - amount);
        hitFlashTimer = 0.18f;
        if (health <= 0f) {
            alive = false;
            return true;
        }
        return false;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Main update — called by EnemyManager each frame
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * @param dt          scaled delta time
     * @param world       for block collision and LOS
     * @param playerPos   player feet position
     * @param allEnemies  full list for separation force
     */
    public void update(float dt, World world, Vector3f playerPos, List<Enemy> allEnemies) {
        framePlayerDamage = 0f;
        if (!alive) {
            if (hitFlashTimer > 0f) hitFlashTimer = Math.max(0f, hitFlashTimer - dt);
            return;
        }

        if (hitFlashTimer > 0f) hitFlashTimer = Math.max(0f, hitFlashTimer - dt);

        // Player chest for targeting (more reliable than feet)
        Vector3f playerCentre = new Vector3f(playerPos.x, playerPos.y + 0.9f, playerPos.z);
        float    distToPlayer = new Vector3f(playerCentre).sub(getCentre()).length();

        tickAI(dt, distToPlayer);
        tickMovement(dt, world, playerCentre, distToPlayer, allEnemies);
        tickGravity(dt, world);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  AI State Machine
    // ═════════════════════════════════════════════════════════════════════════

    private void tickAI(float dt, float distToPlayer) {
        float leash = aggroRange * 1.6f; // give up chasing past this distance

        switch (state) {
            case IDLE -> {
                if (distToPlayer <= aggroRange) {
                    state      = State.ALERTED;
                    alertTimer = (type == Type.BRUTE) ? 1.1f : 0.55f; // brutes react slowly
                }
            }
            case ALERTED -> {
                alertTimer -= dt;
                if (alertTimer <= 0f) state = State.CHASE;
            }
            case CHASE -> {
                if (distToPlayer <= attackRange)  state = State.ATTACK;
                else if (distToPlayer > leash)    state = State.IDLE;
            }
            case ATTACK -> {
                framePlayerDamage = damagePerSec * dt;
                if (distToPlayer > attackRange * 1.25f) state = State.CHASE;
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Horizontal movement + obstacle avoidance
    // ═════════════════════════════════════════════════════════════════════════

    private void tickMovement(float dt, World world, Vector3f target,
                              float distToTarget, List<Enemy> allEnemies) {

        // Only move when actively chasing
        if (state != State.CHASE && state != State.ATTACK) return;

        // Direction to target (horizontal only)
        float dx = target.x - position.x;
        float dz = target.z - position.z;
        float hd = (float) Math.sqrt(dx * dx + dz * dz);
        if (hd < 0.1f) return;

        float ndx = dx / hd;
        float ndz = dz / hd;

        float step = speed * dt;

        // ── Try X then Z independently ────────────────────────────────────────
        float nx = position.x + ndx * step;
        float nz = position.z + ndz * step;

        int footY = (int) Math.floor(position.y + 0.1f);

        boolean blockedX = isSolidColumn(world, nx, footY, position.z);
        boolean blockedZ = isSolidColumn(world, position.x, footY, nz);

        if (!blockedX) {
            position.x = nx;
        }
        if (!blockedZ) {
            position.z = nz;
        }

        // ── Jump if still blocked ─────────────────────────────────────────────
        if ((blockedX || blockedZ) && onGround) {
            // Check that two blocks above the obstacle are clear
            int blockAheadX = (int) Math.floor(position.x + ndx * 0.9f);
            int blockAheadZ = (int) Math.floor(position.z + ndz * 0.9f);
            boolean clearAbove1 = !world.getBlock(blockAheadX, footY + 1, blockAheadZ).isSolid();
            boolean clearAbove2 = !world.getBlock(blockAheadX, footY + 2, blockAheadZ).isSolid();
            if (clearAbove1 && clearAbove2) {
                velocityY = 7.5f;
            } else {
                // Can't jump — strafe around obstacle
                strafeTimer += dt;
                if (strafeTimer >= STRAFE_FLIP_SECS) {
                    strafeSign  = -strafeSign;
                    strafeTimer = 0f;
                }
                float perpX = -ndz * strafeSign;
                float perpZ =  ndx * strafeSign;
                float sx = position.x + perpX * step;
                float sz = position.z + perpZ * step;
                if (!isSolidColumn(world, sx, footY, sz)) {
                    position.x = sx;
                    position.z = sz;
                }
            }
        }

        // ── Separation force — prevent enemies stacking ───────────────────────
        for (Enemy other : allEnemies) {
            if (other == this || !other.alive) continue;
            float sepX = position.x - other.position.x;
            float sepZ = position.z - other.position.z;
            float dist = (float) Math.sqrt(sepX * sepX + sepZ * sepZ);
            if (dist < 1.3f && dist > 0.001f) {
                float pushStrength = (1.3f - dist) / 1.3f * 3.5f;
                position.x += (sepX / dist) * pushStrength * dt;
                position.z += (sepZ / dist) * pushStrength * dt;
            }
        }
    }

    /** Returns true if any of the two foot-level blocks at (x, y, z) and
     *  (x, y+1, z) would block horizontal movement. */
    private boolean isSolidColumn(World world, float x, int footY, float z) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        // Check two blocks tall (enemy capsule height)
        return world.getBlock(bx, footY,     bz).isSolid()
            || world.getBlock(bx, footY + 1, bz).isSolid();
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Gravity + vertical collision
    // ═════════════════════════════════════════════════════════════════════════

    private void tickGravity(float dt, World world) {
        onGround   = false;
        velocityY -= GRAVITY * dt;
        velocityY  = Math.max(-MAX_FALL_SPEED, velocityY);

        float dy   = velocityY * dt;
        int   bx   = (int) Math.floor(position.x);
        int   bz   = (int) Math.floor(position.z);

        if (dy < 0f) {
            // Falling — check block below feet
            int feetY = (int) Math.floor(position.y + dy);
            if (feetY >= 0 && feetY < Chunk.HEIGHT
                    && world.getBlock(bx, feetY, bz).isSolid()) {
                position.y = feetY + 1f;
                velocityY  = 0f;
                onGround   = true;
            } else {
                position.y += dy;
            }
        } else if (dy > 0f) {
            // Rising (jump) — check block above head
            int headY = (int) Math.floor(position.y + 2f * HALF_HEIGHT + dy);
            if (headY >= 0 && headY < Chunk.HEIGHT
                    && world.getBlock(bx, headY, bz).isSolid()) {
                velocityY = 0f;
            } else {
                position.y += dy;
            }
        } else {
            // Check if still on ground when not moving vertically
            int feetY = (int) Math.floor(position.y - 0.05f);
            if (feetY >= 0 && world.getBlock(bx, feetY, bz).isSolid()) {
                onGround = true;
            }
        }

        // Clamp to world bounds
        position.y = Math.max(1f, Math.min(Chunk.HEIGHT - 2f, position.y));
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Targeting helpers
    // ═════════════════════════════════════════════════════════════════════════

    /** Body centre — used for LOS checks, HP bar projection, and targeting. */
    public Vector3f getCentre() {
        return new Vector3f(position.x, position.y + HALF_HEIGHT, position.z);
    }

    /** Returns true if a 3D point lies within this enemy's collision cylinder. */
    public boolean isPointInside(Vector3f p) {
        if (p.y < position.y || p.y > position.y + 2f * HALF_HEIGHT) return false;
        float dx = p.x - position.x;
        float dz = p.z - position.z;
        return (dx * dx + dz * dz) <= RADIUS * RADIUS;
    }

    /** Visual scale multiplier used by Window for per-type size. */
    public float renderScale() {
        return switch (type) {
            case BRUTE   -> 0.80f;
            case STALKER -> 0.38f;
            default      -> 0.50f; // GRUNT
        };
    }
}
