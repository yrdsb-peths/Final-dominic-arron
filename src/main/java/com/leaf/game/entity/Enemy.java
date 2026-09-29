package com.leaf.game.entity;

import com.leaf.game.core.GameConfig;
import com.leaf.game.world.World;
import com.leaf.game.world.Chunk;
import com.leaf.game.world.Block;
import org.joml.Vector3f;

import java.util.*;

/**
 * Enemy — an AI-driven combat target.
 *
 * ── Two types ─────────────────────────────────────────────────────────────────
 *   GOLEM    stone colossus   — very slow, huge, slam + boulder throw
 *   THROWER  ranged attacker  — keeps distance, arc-throws rock projectiles
 *
 * ── State machine ─────────────────────────────────────────────────────────────
 *   IDLE → (in aggro range) → ALERTED → (alert timer) → CHASE
 *   CHASE → (in attack range) → ATTACK  |  (leash) → IDLE
 *   ATTACK → (range lost) → CHASE
 *   RETREATING  [THROWER only]  — backs away when player is too close
 *   SLAMMING    [GOLEM only]    — brief delay before shockwave AoE
 *
 * ── Pathfinding ───────────────────────────────────────────────────────────────
 *   A* on a flat integer grid (Y = ground floor).  Runs every PATH_REPATH_SECS
 *   seconds or immediately when the current path is exhausted/blocked.
 *   Golems use a 2-block jump height; other enemies use 1.
 *   Hole avoidance: a node is only walkable when the block directly below is
 *   solid (no walking off edges into pits).
 *   Golems can break any block in their way; other enemies jump or strafe.
 */
public class Enemy {

    // ═════════════════════════════════════════════════════════════════════════
    //  Enums
    // ═════════════════════════════════════════════════════════════════════════

    public enum Type  { GOLEM, THROWER, ZOMBIE, SLIME, GUARDIAN, DUMMY, SPIDER, LAVA_SLIME, INFERNO_TOWER , TREANT}
    public enum State { IDLE, ALERTED, CHASE, ATTACK, RETREATING, SLAMMING }

    // ═════════════════════════════════════════════════════════════════════════
    //  Static constants
    // ═════════════════════════════════════════════════════════════════════════

    public static final float RADIUS      = 0.5f;
    public static final float HALF_HEIGHT = 1.0f;

    private static final float STRAFE_FLIP_SECS = 0.8f;
    private static final float GRAVITY          = 28f;
    private static final float MAX_FALL_SPEED   = 30f;

    private static final float PLAYER_RADIUS    = 0.3f;

    // ── A* pathfinding constants ──────────────────────────────────────────────
    /** Seconds between full re-paths while the enemy has a valid route. */
    private static final float PATH_REPATH_SECS  = 1.2f;
    /** Maximum A* search nodes before we give up (prevents frame spikes). */
    private static final int   PATH_MAX_NODES    = 512;
    /** How close (blocks) the enemy must get to a waypoint before advancing. */
    private static final float PATH_WAYPOINT_REACH = 1.1f;
    /** Golem can break blocks; other enemies only step up 1 block. */
    private static final int   GOLEM_JUMP_HEIGHT = 2;
    private static final int   DEFAULT_JUMP_HEIGHT = 1;

    // ═════════════════════════════════════════════════════════════════════════
    //  Identity
    // ═════════════════════════════════════════════════════════════════════════

    private static int nextId = 0;
    public  final  int  id;
    public  final  Type type;

    // ═════════════════════════════════════════════════════════════════════════
    //  Stats
    // ═════════════════════════════════════════════════════════════════════════

    public  final float maxHealth;
    public        float health;
    public final float speed;
    public final float damagePerSec;
    public final float aggroRange;
    public final float attackRange;
    public final float attackInterval;

    // ═════════════════════════════════════════════════════════════════════════
    //  Per-instance hitbox
    // ═════════════════════════════════════════════════════════════════════════

    public final float collisionRadius;
    public final float halfHeight;

    // Render scale + knockback resistance, sourced from EnemyArchetype.
    public final float scaleX, scaleY, scaleZ;
    public final float knockbackResist;

    /**
     * Finale GIANT support: multiplies render scale and hitbox dimensions.
     * healthScale keeps the HP bar fraction correct when health was boosted
     * beyond the type's (final) maxHealth.
     */
    public float scaleMul    = 1f;
    public float healthScale = 1f;
    /** Movement-speed multiplier (finale arena enemies are far more aggressive). */
    public float speedMul    = 1f;

    /** Turn this enemy into a giant: bigger body, bigger hitbox, more HP. */
    public void makeGiant(float sizeMul, float hpMul) {
        scaleMul    = sizeMul;
        healthScale = hpMul;
        health      = maxHealth * hpMul;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  World state
    // ═════════════════════════════════════════════════════════════════════════

    public  final  Vector3f position;
    public         boolean  alive    = true;
    private        float    velocityY = 0f;
    public       boolean  onGround  = false;

    public float knockbackVelX = 0f;
    public float knockbackVelZ = 0f;

    // ═════════════════════════════════════════════════════════════════════════
    //  AI state
    // ═════════════════════════════════════════════════════════════════════════

    public  State state       = State.IDLE;
    private float alertTimer  = 0f;
    private float strafeTimer = 0f;
    private float strafeSign  = 1f;
    private float attackCooldown = 0f;

    // ── GOLEM special ─────────────────────────────────────────────────────────
    private float slamCooldown  = 0f;
    private float slamWindUp    = 0f;
    public  boolean wantsToThrow = false;
    private float throwCooldown  = 0f;

    // ── INFERNO TOWER special ───────────────────────────────────────────────────
    /** Signal (read + cleared by EnemyManager) that the tower should erupt a slime. */
    public  boolean wantsToSpawnMinion = false;
    /** Lava slimes this tower currently owns (cap via GameConfig.infernoMinionCap). */
    public  int     liveMinions        = 0;
    private float   minionSpawnTimer    = 0f;
    /** Id of the Inferno Tower that spawned this minion, or -1 if not tower-spawned. */
    public  int     ownerTowerId       = -1;
    /** True for free-explore ambient roamers (despawn when the player wanders away). */
    public  boolean ambient            = false;

    // ── GUARDIAN special ──────────────────────────────────────────────────────
    public  float   facingYaw    = 0f;
    public  String  attackAnim   = "idle";
    private int     comboIndex   = 0;
    private float   swingTimer   = 0f;
    private boolean patrolWalking = false;
    private float   patrolTimer  = 0f;

    // ── Block Breaking State ──────────────────────────────────────────────────
    public boolean pendingBlockBreak = false;
    public int     breakX, breakY, breakZ;
    private float  stuckTimer = 0f;

    // ── A* Pathfinding State ──────────────────────────────────────────────────
    /**
     * Current waypoint list produced by A*.
     * Index 0 = next waypoint (world X,Z centre), index N-1 = destination.
     * Each entry is a float[2]: { worldX + 0.5, worldZ + 0.5 }.
     */
    private final List<float[]> currentPath = new ArrayList<>();
    private float repathTimer  = 0f;   // counts up; re-path when >= PATH_REPATH_SECS
    /** Grid coords of the last A* target — used to skip redundant re-paths. */
    private int   lastPathTargetGX = Integer.MIN_VALUE;
    private int   lastPathTargetGZ = Integer.MIN_VALUE;
    /** True while the golem is mid-jump to clear a 2-tall obstacle. */
    private boolean pendingHighJump = false;

    // ═════════════════════════════════════════════════════════════════════════
    //  Visual / output
    // ═════════════════════════════════════════════════════════════════════════

    public float hitFlashTimer    = 0f;
    public float framePlayerDamage = 0f;
    public float mudTrapTimer     = 0f;

    // ── GRAB STATE ────────────────────────────────────────────────────────────
    public boolean isGrabbed    = false;
    public boolean isThrown     = false;
    public float thrownVelX = 0f, thrownVelY = 0f, thrownVelZ = 0f;
    public boolean pendingGrabImpact = false;
    public int     grabImpactX = 0, grabImpactY = 0, grabImpactZ = 0;
    public boolean grabImpactIsGround = false;

    // ═════════════════════════════════════════════════════════════════════════
    //  Construction
    // ═════════════════════════════════════════════════════════════════════════

    public Enemy(float x, float y, float z, Type type) {
        this.id       = nextId++;
        this.type     = type;
        this.position = new Vector3f(x, y, z);

        // All per-type stats live in one place now — see EnemyArchetype.
        // Read fresh each spawn so runtime GameConfig tweaks still apply.
        EnemyArchetype a = EnemyArchetype.forType(type);
        this.maxHealth       = a.health;
        this.health          = a.health;
        this.speed           = a.speed;
        this.damagePerSec    = a.damagePerSec;
        this.aggroRange      = a.aggroRange;
        this.attackRange     = a.attackRange;
        this.attackInterval  = a.attackInterval;
        this.collisionRadius = a.radius;
        this.halfHeight      = a.halfHeight;
        this.scaleX          = a.scaleX;
        this.scaleY          = a.scaleY;
        this.scaleZ          = a.scaleZ;
        this.knockbackResist = a.knockbackResist;
    }

    public Enemy(float x, float y, float z) { this(x, y, z, Type.THROWER); }

    // ═════════════════════════════════════════════════════════════════════════
    //  Damage / knockback API
    // ═════════════════════════════════════════════════════════════════════════

    public boolean applyDamage(float amount) {
        if (!alive) return false;
        if (type == Type.GOLEM) amount *= GameConfig.golemSmashResist + (1f - GameConfig.golemSmashResist);
        float before  = health;
        health        = Math.max(0f, health - amount);
        hitFlashTimer = 0.18f;
        boolean killed = health <= 0f;
        if (killed) alive = false;
        // Sparks, damage numbers, hit-stop — see core.CombatFeedback.
        com.leaf.game.core.CombatFeedback.publish(id, position.x, position.y + halfHeight,
                position.z, halfHeight, before - health, killed);
        return killed;
    }

    public void applyKnockback(float kx, float ky, float kz) {
        if (type == Type.INFERNO_TOWER) return;
        float resist = knockbackResist;
        knockbackVelX = kx * resist;
        knockbackVelZ = kz * resist;
        if (ky > 0f) velocityY = Math.max(velocityY, ky * resist);
        // Knock the golem off its current path so it recalculates from new position
        currentPath.clear();
        repathTimer = PATH_REPATH_SECS; // repath immediately next tick
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Main update
    // ═════════════════════════════════════════════════════════════════════════

    public void update(float dt, World world, Vector3f playerPos, List<Enemy> allEnemies) {
        framePlayerDamage    = 0f;
        wantsToThrow         = false;
        wantsToSpawnMinion   = false;
        pendingGrabImpact    = false;

        if (!alive) {
            if (hitFlashTimer > 0f) hitFlashTimer = Math.max(0f, hitFlashTimer - dt);
            return;
        }
        if (hitFlashTimer > 0f) hitFlashTimer = Math.max(0f, hitFlashTimer - dt);

        if (isGrabbed) return;

        if (isThrown) {
            tickThrownFlight(dt, world);
            return;
        }

        Vector3f playerCentre = new Vector3f(playerPos.x, playerPos.y + 0.9f, playerPos.z);
        float    distToPlayer = new Vector3f(playerCentre).sub(getCentre()).length();

        tickCooldowns(dt);
        tickAI(dt, distToPlayer, playerCentre, playerPos);
        tickMovement(dt, world, playerCentre, distToPlayer, allEnemies);
        tickGravity(dt, world);

        if (knockbackVelX != 0f || knockbackVelZ != 0f) {
            position.x += knockbackVelX * dt;
            position.z += knockbackVelZ * dt;
            float decay = (float) Math.pow(GameConfig.knockbackDecay, dt);
            knockbackVelX *= decay;
            knockbackVelZ *= decay;
            if (Math.abs(knockbackVelX) < 0.05f) knockbackVelX = 0f;
            if (Math.abs(knockbackVelZ) < 0.05f) knockbackVelZ = 0f;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Cooldown ticks
    // ═════════════════════════════════════════════════════════════════════════

    private void tickCooldowns(float dt) {
        if (attackCooldown > 0f) attackCooldown -= dt;
        if (slamCooldown  > 0f) slamCooldown  -= dt;
        if (throwCooldown > 0f) throwCooldown  -= dt;
        if (mudTrapTimer  > 0f) mudTrapTimer   -= dt;
        repathTimer += dt;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  AI State Machine
    // ═════════════════════════════════════════════════════════════════════════

    private void tickAI(float dt, float distToPlayer,
                        Vector3f playerCentre, Vector3f playerPos) {
        if (mudTrapTimer > 0f) return;

        float leash = aggroRange * 1.8f;

        switch (type) {
            case GOLEM    -> tickGolemAI(dt, distToPlayer, leash, playerCentre);
            case THROWER  -> tickThrowerAI(dt, distToPlayer, leash);
            case ZOMBIE, SLIME, LAVA_SLIME -> tickZombieAI(dt, distToPlayer, leash);
            case GUARDIAN -> tickGuardianAI(dt, distToPlayer, leash, playerCentre);
            case INFERNO_TOWER -> tickTowerAI(dt, distToPlayer);
            case DUMMY -> { /* stationary practice target — no AI */ }
            default -> { /* SPIDER overrides update() entirely */ }
        }
    }

    // ─── INFERNO TOWER ────────────────────────────────────────────────────────
    /**
     * Stationary spawner. While the player is within aggro range, count down to the
     * next eruption; on fire, raise {@link #wantsToSpawnMinion} (EnemyManager spawns
     * the lava slime + VFX) unless the minion cap is already reached.
     */
    private void tickTowerAI(float dt, float distToPlayer) {
        if (distToPlayer > aggroRange) {
            minionSpawnTimer = GameConfig.infernoSpawnInterval * 0.5f; // re-arm, half delay
            return;
        }
        minionSpawnTimer -= dt;
        if (minionSpawnTimer <= 0f) {
            minionSpawnTimer = GameConfig.infernoSpawnInterval;
            if (liveMinions < GameConfig.infernoMinionCap) wantsToSpawnMinion = true;
        }
    }

    // ─── GUARDIAN ────────────────────────────────────────────────────────────

    private void tickGuardianAI(float dt, float dist, float leash, Vector3f playerCentre) {
        switch (state) {
            case IDLE -> {
                tickPatrol(dt);
                if (dist <= aggroRange) {
                    state = State.ALERTED;
                    alertTimer = 0.6f;
                    patrolWalking = false;
                    // (alert sound removed)
                }
            }
            case ALERTED -> {
                faceToward(playerCentre);
                alertTimer -= dt;
                if (alertTimer <= 0f) state = State.CHASE;
            }
            case CHASE -> {
                faceToward(playerCentre);
                if (dist > leash) { state = State.IDLE; patrolTimer = 0f; patrolWalking = false; break; }
                if (dist <= attackRange) { state = State.ATTACK; beginSwing(); }
            }
            case ATTACK -> {
                faceToward(playerCentre);
                swingTimer -= dt;
                // Damage at 60% of swing (0.6 * guardianAttackTime), not the end
                float damageWindow = GameConfig.guardianAttackTime * 0.6f;
                if (swingTimer <= (GameConfig.guardianAttackTime - damageWindow) && attackCooldown <= 0f) {
                    if (dist <= attackRange * 1.2f) {
                        boolean both = (comboIndex == 2);
                        framePlayerDamage = GameConfig.guardianHitDamage * (both ? 2f : 1f);
                        attackCooldown = GameConfig.guardianAttackTime; // prevent double-hit this swing
                    }
                }
                if (swingTimer <= 0f) {
                    comboIndex = (comboIndex + 1) % 3;
                    if (dist > attackRange * 1.4f) state = State.CHASE;
                    else beginSwing();
                }
            }
            default -> state = State.CHASE;
        }
    }

    private void beginSwing() {
        swingTimer = GameConfig.guardianAttackTime;
        attackCooldown = GameConfig.guardianAttackTime; // reset per-swing guard
        attackAnim = switch (comboIndex) {
            case 0  -> "attack_left";
            case 1  -> "attack_right";
            default -> "attack";
        };
        com.leaf.game.core.AudioManager.playAt("enemy_swing", position, (Vector3f) null, 30f);
    }

    private void tickPatrol(float dt) {
        patrolTimer -= dt;
        if (patrolTimer <= 0f) {
            if (patrolWalking) {
                patrolWalking = false;
                patrolTimer   = 2f + (float) Math.random() * 4f;
                if (Math.random() < 0.6) facingYaw = randomYaw();
            } else {
                patrolWalking = true;
                facingYaw     = randomYaw();
                patrolTimer   = 2f + (float) Math.random() * 3f;
            }
        }
    }

    private void faceToward(Vector3f p) {
        facingYaw = (float) Math.atan2(p.x - position.x, p.z - position.z);
    }
    private static float randomYaw() { return (float) (Math.random() * Math.PI * 2.0); }

    public String getAnimName() {
        if (!alive) return "idle";
        return switch (state) {
            case ATTACK, SLAMMING -> (type == Type.GUARDIAN && attackAnim != null) ? attackAnim : "attack";
            case CHASE, ALERTED   -> "walk";
            case IDLE             -> patrolWalking ? "walk" : "idle";
            default               -> "idle";
        };
    }

    // ─── GOLEM ────────────────────────────────────────────────────────────────

    private void tickGolemAI(float dt, float dist, float leash, Vector3f playerCentre) {
        switch (state) {
            case IDLE    -> {
                if (dist <= aggroRange) {
                    state = State.ALERTED;
                    alertTimer = 1.5f;
                }
            }
            case ALERTED -> { alertTimer -= dt; if (alertTimer <= 0f) state = State.CHASE; }

            case CHASE -> {
                if (dist > leash) { state = State.IDLE; break; }
                if (dist <= GameConfig.golemSlamRange && slamCooldown <= 0f) {
                    state = State.SLAMMING;
                    slamWindUp = 0.6f;
                    break;
                }
                if (dist >= GameConfig.golemSlamRange && dist <= GameConfig.golemThrowRange
                        && throwCooldown <= 0f) {
                    wantsToThrow  = true;
                    throwCooldown = GameConfig.golemThrowCooldown;
                }
                if (dist <= attackRange) state = State.ATTACK;
            }

            case SLAMMING -> {
                slamWindUp -= dt;
                if (slamWindUp <= 0f) {
                    framePlayerDamage = GameConfig.golemSlamDamage;
                    slamCooldown = GameConfig.golemSlamCooldown;
                    state        = State.CHASE;
                }
            }

            case ATTACK -> {
                // Damage at 0.6 s into the attack interval, not at end
                if (attackCooldown <= (attackInterval - 0.6f) && attackCooldown > (attackInterval - 0.65f)) {
                    if (dist <= attackRange * 1.1f) {
                        framePlayerDamage = damagePerSec * attackInterval;
                    }
                }
                if (attackCooldown <= 0f) {
                    attackCooldown = attackInterval;
                }
                if (dist > attackRange * 1.3f) state = State.CHASE;
            }
            default -> state = State.CHASE;
        }
    }

    // ─── THROWER ──────────────────────────────────────────────────────────────

    private void tickThrowerAI(float dt, float dist, float leash) {
        switch (state) {
            case IDLE    -> {
                if (dist <= aggroRange) {
                    state = State.ALERTED;
                    alertTimer = 0.4f;
                }
            }
            case ALERTED -> { alertTimer -= dt; if (alertTimer <= 0f) state = State.CHASE; }

            case CHASE -> {
                if (dist > leash) { state = State.IDLE; break; }
                if (dist < GameConfig.throwerPreferredDist * 0.6f) {
                    state = State.RETREATING; break;
                }
                if (dist <= GameConfig.throwerAggroRange && throwCooldown <= 0f) {
                    wantsToThrow  = true;
                    throwCooldown = GameConfig.throwerThrowCooldown;
                }
                if (dist <= attackRange) state = State.ATTACK;
            }

            case RETREATING -> {
                if (dist >= GameConfig.throwerPreferredDist) state = State.CHASE;
                if (dist > leash) state = State.IDLE;
            }

            case ATTACK -> {
                // Damage at 0.6 s into the interval
                if (attackCooldown <= (attackInterval - 0.6f) && attackCooldown > (attackInterval - 0.65f)) {
                    if (dist <= attackRange * 1.1f) {
                        framePlayerDamage = damagePerSec * attackInterval;
                    }
                }
                if (attackCooldown <= 0f) attackCooldown = attackInterval;
                if (dist > attackRange * 1.5f) state = State.CHASE;
            }
            default -> state = State.CHASE;
        }
    }

    // ─── ZOMBIE ───────────────────────────────────────────────────────────────

    private void tickZombieAI(float dt, float dist, float leash) {
        switch (state) {
            case IDLE    -> {
                if (dist <= aggroRange) {
                    state = State.ALERTED;
                    alertTimer = 0.7f;
                    com.leaf.game.core.AudioManager.playAt("enemy_alert", position, (Vector3f) null, 30f);
                }
            }
            case ALERTED -> { alertTimer -= dt; if (alertTimer <= 0f) state = State.CHASE; }
            case CHASE   -> {
                if (dist > leash)      { state = State.IDLE;   break; }
                if (dist <= attackRange) state = State.ATTACK;
            }
            case ATTACK  -> {
                // Damage at 0.6 s into the interval
                if (attackCooldown <= (attackInterval - 0.6f) && attackCooldown > (attackInterval - 0.65f)) {
                    if (dist <= attackRange * 1.1f) {
                        framePlayerDamage = damagePerSec * attackInterval;
                    }
                }
                if (attackCooldown <= 0f) attackCooldown = attackInterval;
                if (dist > attackRange * 1.6f) state = State.CHASE;
                if (dist > leash)              state = State.IDLE;
            }
            default -> state = State.CHASE;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  A* Pathfinding
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Compact grid node for the A* open set.
     * gx, gz = integer grid coordinates.
     * g      = cost from start.
     * f      = g + heuristic.
     * parent = encoded parent node index into a flat map (or -1 for start).
     */
    private static final class ANode {
        final int gx, gz;
        float g, f;
        int parentGx, parentGz;
        boolean hasParent;

        ANode(int gx, int gz, float g, float f, int px, int pz, boolean hasParent) {
            this.gx = gx; this.gz = gz;
            this.g = g;   this.f = f;
            this.parentGx = px; this.parentGz = pz;
            this.hasParent = hasParent;
        }
    }

    /**
     * Run A* from the enemy's current position to (targetGX, targetGZ).
     *
     * The search is done on the integer voxel grid at footY (the Y level the
     * enemy is standing on).  A cell is walkable when:
     *   • The block AT footY is AIR (can walk through).
     *   • The block AT footY+1 is AIR (head clearance for 2-tall enemies).
     *   • The block AT footY-1 is SOLID (something to stand on — no holes).
     *     Exception: Golems ignore the hole-check because they can use a
     *     2-block drop freely.
     *   • (Optional step-up) The cell is 1 block higher but the gap above is clear.
     *   • (Golem only)       2-block step-up is also tried.
     *
     * Returns true and populates {@code currentPath} if a path was found.
     * Returns false and leaves {@code currentPath} empty on failure.
     */
    private boolean runAStar(World world, int startGX, int startGZ,
                             int targetGX, int targetGZ, int footY) {

        currentPath.clear();

        if (startGX == targetGX && startGZ == targetGZ) return true;

        int jumpH = (type == Type.GOLEM) ? GOLEM_JUMP_HEIGHT : DEFAULT_JUMP_HEIGHT;
        boolean isGolem = (type == Type.GOLEM);

        // Open set — min-heap by f
        PriorityQueue<ANode> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
        // Closed set and best-g map — key = gx * 65536 + gz (safe for ±32k range)
        Map<Long, Float> bestG  = new HashMap<>();
        Map<Long, ANode> cameFrom = new HashMap<>();

        long startKey = nodeKey(startGX, startGZ);
        ANode startNode = new ANode(startGX, startGZ, 0f,
                heuristic(startGX, startGZ, targetGX, targetGZ),
                0, 0, false);
        open.add(startNode);
        bestG.put(startKey, 0f);
        cameFrom.put(startKey, startNode);

        int explored = 0;

        // 4-direction + diagonal neighbours
        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};

        while (!open.isEmpty() && explored < PATH_MAX_NODES) {
            ANode cur = open.poll();
            explored++;

            if (cur.gx == targetGX && cur.gz == targetGZ) {
                // Reconstruct path
                reconstructPath(cameFrom, cur, startGX, startGZ);
                return !currentPath.isEmpty();
            }

            long curKey = nodeKey(cur.gx, cur.gz);
            // Skip if we've found a better path to this node already
            Float recorded = bestG.get(curKey);
            if (recorded != null && cur.g > recorded + 0.001f) continue;

            for (int[] d : dirs) {
                int nx = cur.gx + d[0];
                int nz = cur.gz + d[1];

                // Try walking at the current footY, or stepping up by 1 (or 2 for golem)
                int walkableY = findWalkableY(world, nx, nz, footY, jumpH, isGolem);
                if (walkableY < 0) continue; // not walkable at any height

                float moveCost = (d[0] != 0 && d[1] != 0) ? 1.414f : 1.0f;
                // Small penalty for height changes (golem prefers flat routes)
                if (walkableY != footY) moveCost += 0.5f * Math.abs(walkableY - footY);

                float newG = cur.g + moveCost;
                long nKey  = nodeKey(nx, nz);
                Float prev = bestG.get(nKey);
                if (prev != null && newG >= prev) continue;

                bestG.put(nKey, newG);
                float f = newG + heuristic(nx, nz, targetGX, targetGZ);
                ANode next = new ANode(nx, nz, newG, f, cur.gx, cur.gz, true);
                open.add(next);
                cameFrom.put(nKey, next);
            }
        }

        return false; // No path found within budget
    }

    /**
     * Determine the Y at which the enemy can walk at column (gx, gz) given a
     * starting footY.  Returns -1 if unpassable.
     *
     * Logic:
     *   • Try footY exactly: floor below solid, two air blocks above (body).
     *   • Try footY+1 … footY+jumpH: step up.
     *   • Golems also accept footY-1 (one-block drop) and footY-2 (two-block drop).
     *   • Hole check: non-golem enemies require a solid block at footY-1 to prevent
     *     walking off edges into pits.
     */
    private int findWalkableY(World world, int gx, int gz,
                              int footY, int jumpH, boolean isGolem) {
        // Try current level first, then step-up, then (golem) step-down
        int[] tryYs;
        if (isGolem) {
            tryYs = new int[]{ footY, footY + 1, footY + 2, footY - 1, footY - 2 };
        } else {
            tryYs = new int[]{ footY, footY + 1 };
        }

        for (int ty : tryYs) {
            if (ty < 1 || ty >= Chunk.HEIGHT - 3) continue;
            if (!isWalkableCell(world, gx, gz, ty, isGolem)) continue;
            return ty;
        }
        return -1;
    }

    /**
     * Returns true iff the enemy can occupy column (gx,gz) with feet at ty.
     *
     * Conditions:
     *   1. The cell at ty   is not solid (feet don't clip).
     *   2. The cell at ty+1 is not solid (body doesn't clip — standard 2-tall).
     *   3. There is a solid block at ty-1 (won't fall into a hole) — UNLESS isGolem.
     */
    private boolean isWalkableCell(World world, int gx, int gz, int ty, boolean isGolem) {
        // Feet and body must be clear
        if (world.getBlock(gx, ty, gz).isSolid())     return false;
        if (world.getBlock(gx, ty + 1, gz).isSolid()) return false;
        // Must have ground underfoot (hole avoidance) — golems skip this check
        if (!isGolem && !world.getBlock(gx, ty - 1, gz).isSolid()) return false;
        return true;
    }

    private float heuristic(int ax, int az, int bx, int bz) {
        // Octile distance — admissible for 8-direction grid
        int dx = Math.abs(ax - bx);
        int dz = Math.abs(az - bz);
        return Math.max(dx, dz) + (float)(Math.sqrt(2) - 1) * Math.min(dx, dz);
    }

    private long nodeKey(int gx, int gz) {
        // Safe for ±32767 coords
        return ((long)(gx + 32768) << 17) | (gz + 32768);
    }

    /** Walk cameFrom map backwards to build currentPath (waypoints in forward order). */
    private void reconstructPath(Map<Long, ANode> cameFrom, ANode goal,
                                 int startGX, int startGZ) {
        List<float[]> reversed = new ArrayList<>();
        ANode cur = goal;
        // Guard against runaway loops
        int safety = PATH_MAX_NODES * 2;
        while (cur.hasParent && safety-- > 0) {
            reversed.add(new float[]{ cur.gx + 0.5f, cur.gz + 0.5f });
            long pk = nodeKey(cur.parentGx, cur.parentGz);
            ANode parent = cameFrom.get(pk);
            if (parent == null || (parent.gx == startGX && parent.gz == startGZ)) break;
            cur = parent;
        }
        // Reverse so index 0 = first step
        for (int i = reversed.size() - 1; i >= 0; i--) {
            currentPath.add(reversed.get(i));
        }
    }

    /**
     * Refresh the A* path to the given target if needed.
     * Called each frame from tickMovement.
     */
    private void maybeRepath(World world, float targetX, float targetZ) {
        int footY    = (int) Math.floor(position.y);
        int startGX  = (int) Math.floor(position.x);
        int startGZ  = (int) Math.floor(position.z);
        int targetGX = (int) Math.floor(targetX);
        int targetGZ = (int) Math.floor(targetZ);

        boolean targetMoved = (targetGX != lastPathTargetGX || targetGZ != lastPathTargetGZ);
        boolean timerElapsed = repathTimer >= PATH_REPATH_SECS;
        boolean pathEmpty    = currentPath.isEmpty();

        if (!pathEmpty && !timerElapsed && !targetMoved) return;

        repathTimer = 0f;
        lastPathTargetGX = targetGX;
        lastPathTargetGZ = targetGZ;

        boolean found = runAStar(world, startGX, startGZ, targetGX, targetGZ, footY);
        if (!found) {
            // A* failed (blocked or budget exceeded): fall back to direct vector
            currentPath.clear();
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Horizontal movement (A*-guided)
    // ═════════════════════════════════════════════════════════════════════════

    private void tickMovement(float dt, World world, Vector3f target,
                              float distToTarget, List<Enemy> allEnemies) {
        if (mudTrapTimer > 0f) return;

        boolean guardianPatrol = (type == Type.GUARDIAN && state == State.IDLE && patrolWalking);
        boolean shouldMove;
        if (type == Type.GUARDIAN) {
            shouldMove = (state == State.CHASE) || guardianPatrol;
        } else {
            shouldMove = switch (state) {
                case CHASE, ATTACK, SLAMMING, RETREATING -> true;
                default                                  -> false;
            };
        }
        if (!shouldMove) return;

        // ── Determine raw desired direction ──────────────────────────────────
        float dx, dz;
        if (guardianPatrol) {
            dx = (float) Math.sin(facingYaw);
            dz = (float) Math.cos(facingYaw);
        } else if (state == State.RETREATING) {
            dx = position.x - target.x;
            dz = position.z - target.z;
        } else {
            // Chase / Attack: use A* waypoints
            maybeRepath(world, target.x, target.z);
            advanceWaypoints();

            if (!currentPath.isEmpty()) {
                float[] wp = currentPath.get(0);
                dx = wp[0] - position.x;
                dz = wp[1] - position.z;
            } else {
                // Fallback: direct vector (no valid path)
                dx = target.x - position.x;
                dz = target.z - position.z;
            }
        }

        float hd = (float) Math.sqrt(dx * dx + dz * dz);
        if (hd < 0.1f) return;

        float ndx = dx / hd;
        float ndz = dz / hd;

        int underX = (int) Math.floor(position.x);
        int underY = (int) Math.floor(position.y - 0.1f);
        int underZ = (int) Math.floor(position.z);
        boolean onMud  = world.getBlock(underX, underY, underZ) == Block.MUD;
        float   moveSpeed = guardianPatrol         ? GameConfig.guardianPatrolSpeed
                : (state == State.RETREATING) ? GameConfig.throwerRetreatSpeed
                  : speed;
        float step = moveSpeed * speedMul * dt * (onMud ? 0.10f : 1.0f);

        float nx = position.x + ndx * step;
        float nz = position.z + ndz * step;
        int   footY = (int) Math.floor(position.y + 0.1f);

        boolean blockedX = isSolidColumn(world, nx, footY, position.z);
        boolean blockedZ = isSolidColumn(world, position.x, footY, nz);

        // ── 1-BLOCK STEP-UP ──────────────────────────────────────────────────
        // A single ledge used to stop walkers dead (they'd just grind against
        // it). If the obstacle is exactly one block tall with headroom above,
        // climb it and keep moving — enemies now chase properly over terrain.
        if (onGround && (blockedX || blockedZ)) {
            int sbx = (int) Math.floor(blockedX ? nx : position.x);
            int sbz = (int) Math.floor(blockedZ ? nz : position.z);
            if (world.getBlock(sbx, footY, sbz).isSolid()
                    && !world.getBlock(sbx, footY + 1, sbz).isSolid()
                    && !world.getBlock(sbx, footY + 2, sbz).isSolid()) {
                position.y = footY + 1f;
                position.x = nx;
                position.z = nz;
                return;
            }
        }

        // Wall-slide along the open axis
        if (blockedX && !blockedZ) {
            position.z += Math.signum(ndz) * step * 1.5f;
        } else if (blockedZ && !blockedX) {
            position.x += Math.signum(ndx) * step * 1.5f;
        }

        if (!blockedX) position.x = nx;
        if (!blockedZ) position.z = nz;

        // ── Wall-escape: push out of embedded solid ───────────────────────────
        if (isSolidColumn(world, position.x, footY, position.z)) {
            float escape = collisionRadius + 0.2f;
            float[][] dirs8 = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{-1,1},{1,-1},{-1,-1}};
            for (float[] d : dirs8) {
                float len = (float) Math.sqrt(d[0]*d[0]+d[1]*d[1]);
                float ex = position.x + d[0]/len * escape;
                float ez = position.z + d[1]/len * escape;
                if (!isSolidColumn(world, ex, footY, ez)) {
                    position.x = ex; position.z = ez; break;
                }
            }
        }

        // ── Obstacle handling: jump, break, or strafe ─────────────────────────
        if ((blockedX || blockedZ) && onGround) {
            if (guardianPatrol) patrolTimer = 0f;

            int blockAheadX = (int) Math.floor(position.x + ndx * 0.9f);
            int blockAheadZ = (int) Math.floor(position.z + ndz * 0.9f);

            // How many blocks tall is the wall?  (up to 3 high check)
            int wallHeight = 0;
            for (int wy = footY; wy <= footY + 3; wy++) {
                if (world.getBlock(blockAheadX, wy, blockAheadZ).isSolid()) wallHeight++;
                else break;
            }

            if (wallHeight == 1) {
                // Single-block wall: all enemies can hop over it
                velocityY = 7.5f;
                currentPath.clear(); // recompute next tick so we don't run into it again
            } else if (wallHeight == 2 && type == Type.GOLEM) {
                // Two-block wall: golem high-jump
                if (!pendingHighJump) {
                    velocityY = 11.0f;
                    pendingHighJump = true;
                    currentPath.clear();
                }
            } else {
                stuckTimer += dt;

                if (stuckTimer > 0.5f && (type == Type.GOLEM || type == Type.GUARDIAN)) {
                    // ── Golem precision block-break ──────────────────────────
                    // Break the lowest wall block first (clear a doorway from bottom up)
                    int breakAtY = footY;
                    for (int wy = footY; wy <= footY + 3; wy++) {
                        if (world.getBlock(blockAheadX, wy, blockAheadZ).isSolid()) {
                            breakAtY = wy;
                            break;
                        }
                    }
                    pendingBlockBreak = true;
                    breakX = blockAheadX;
                    breakY = breakAtY;
                    breakZ = blockAheadZ;
                    stuckTimer = 0f;
                    currentPath.clear();    // repath around the new gap next tick

                    if (type == Type.GUARDIAN) {
                        state = State.ATTACK;
                        beginSwing();
                    } else {
                        // Golem smash-break sound + brief slam-wind-up for effect
                        state     = State.SLAMMING;
                        slamWindUp = 0.4f;
                    }
                } else if (stuckTimer > 0.3f) {
                    // Non-golem enemies: strafe
                    strafeTimer += dt;
                    if (strafeTimer >= STRAFE_FLIP_SECS) { strafeSign = -strafeSign; strafeTimer = 0f; }
                    float sx = position.x + (-ndz * strafeSign) * step;
                    float sz = position.z + ( ndx * strafeSign) * step;
                    if (!isSolidColumn(world, sx, footY, sz)) { position.x = sx; position.z = sz; }
                    currentPath.clear(); // recompute to find the detour
                }
            }
        } else {
            stuckTimer    = 0f;
            pendingHighJump = false;
        }

        // ── Separation from other enemies ─────────────────────────────────────
        float sepRadius = collisionRadius * 2.2f;
        for (Enemy other : allEnemies) {
            if (other == this || !other.alive) continue;
            float sx = position.x - other.position.x;
            float sz = position.z - other.position.z;
            float dist = (float) Math.sqrt(sx * sx + sz * sz);
            if (dist < sepRadius && dist > 0.001f) {
                float push = (sepRadius - dist) / sepRadius * 3.5f;
                position.x += (sx / dist) * push * dt;
                position.z += (sz / dist) * push * dt;
            }
        }

        // ── Keep out of player's personal space ───────────────────────────────
        float minPlayerDist = collisionRadius + 0.3f;
        float pdx = position.x - target.x;
        float pdz = position.z - target.z;
        float pd  = (float) Math.sqrt(pdx * pdx + pdz * pdz);
        if (pd < minPlayerDist && pd > 0.001f) {
            float push = minPlayerDist - pd;
            position.x += (pdx / pd) * push;
            position.z += (pdz / pd) * push;
        }
    }

    /**
     * Remove any waypoints the enemy has already passed through.
     * A waypoint is considered reached when the enemy is within PATH_WAYPOINT_REACH
     * blocks of its (X,Z) centre.
     */
    private void advanceWaypoints() {
        while (!currentPath.isEmpty()) {
            float[] wp = currentPath.get(0);
            float ex = wp[0] - position.x;
            float ez = wp[1] - position.z;
            if (Math.sqrt(ex*ex + ez*ez) <= PATH_WAYPOINT_REACH) {
                currentPath.remove(0);
            } else {
                break;
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Thrown flight
    // ═════════════════════════════════════════════════════════════════════════

    private static final float THROW_GRAVITY = 32f;

    private void tickThrownFlight(float dt, World world) {
        thrownVelY -= THROW_GRAVITY * dt;

        float newX = position.x + thrownVelX * dt;
        float newY = position.y + thrownVelY * dt;
        float newZ = position.z + thrownVelZ * dt;

        newY = Math.max(1f, Math.min(Chunk.HEIGHT - 2f, newY));

        int bx = (int) Math.floor(newX);
        int by = (int) Math.floor(newY);
        int bz = (int) Math.floor(newZ);

        boolean destSolid  = world.getBlock(bx, by, bz).isSolid();
        boolean axisHitX   = world.getBlock(bx, (int) Math.floor(position.y), (int) Math.floor(position.z)).isSolid();
        boolean axisHitY   = world.getBlock((int) Math.floor(position.x), by,  (int) Math.floor(position.z)).isSolid();
        boolean axisHitZ   = world.getBlock((int) Math.floor(position.x), (int) Math.floor(position.y),  bz).isSolid();
        boolean atFloor    = newY <= 1.1f;

        if (destSolid || axisHitX || axisHitY || axisHitZ || atFloor) {
            int impX = bx;
            int impY = atFloor ? 0 : by;
            int impZ = bz;
            if (!destSolid && !atFloor) {
                impX = axisHitX ? bx : (int) Math.floor(position.x);
                impY = axisHitY ? by : (int) Math.floor(position.y);
                impZ = axisHitZ ? bz : (int) Math.floor(position.z);
            }

            boolean slamIsGround = (thrownVelY < 0f)
                    && (Math.abs(thrownVelY) >= Math.abs(thrownVelX))
                    && (Math.abs(thrownVelY) >= Math.abs(thrownVelZ));

            grabImpactX        = impX;
            grabImpactY        = impY;
            grabImpactZ        = impZ;
            grabImpactIsGround = slamIsGround || atFloor;
            pendingGrabImpact  = true;

            float impactDamage = grabImpactIsGround
                    ? GameConfig.grabGroundDamage
                    : GameConfig.grabWallDamage;
            applyDamage(impactDamage);

            isThrown   = false;
            thrownVelX = 0f;
            thrownVelY = 0f;
            thrownVelZ = 0f;

            position.x = newX;
            position.y = Math.max(1f, (float) impY + 1.01f);
            position.z = newZ;

            // Invalidate path — position teleported
            currentPath.clear();
        } else {
            position.x = newX;
            position.y = newY;
            position.z = newZ;
        }
    }

    // ─── isSolidColumn ────────────────────────────────────────────────────────

    /**
     * Returns true if either the foot or head block at (x, footY, z) is solid.
     * Used as a quick horizontal collision check before A* supplies a real path.
     */
    private boolean isSolidColumn(World world, float x, int footY, float z) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
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

        float dy = velocityY * dt;
        int   bx = (int) Math.floor(position.x);
        int   bz = (int) Math.floor(position.z);

        if (dy < 0f) {
            int feetY = (int) Math.floor(position.y + dy);
            if (feetY >= 0 && feetY < Chunk.HEIGHT && world.getBlock(bx, feetY, bz).isSolid()) {
                position.y = feetY + 1f;
                velocityY  = 0f;
                onGround   = true;
                // Land sound for golem only (other enemies are too frequent)
                if (type == Type.GOLEM && dy < -8f) {
                }
            } else {
                position.y += dy;
            }
        } else if (dy > 0f) {
            int headY = (int) Math.floor(position.y + 2f * halfHeight + dy);
            if (headY >= 0 && headY < Chunk.HEIGHT && world.getBlock(bx, headY, bz).isSolid()) {
                velocityY = 0f;
            } else {
                position.y += dy;
            }
        } else {
            int feetY = (int) Math.floor(position.y - 0.05f);
            if (feetY >= 0 && world.getBlock(bx, feetY, bz).isSolid()) onGround = true;
        }
        position.y = Math.max(1f, Math.min(Chunk.HEIGHT - 2f, position.y));
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Helpers / accessors
    // ═════════════════════════════════════════════════════════════════════════

    public Vector3f getCentre() {
        return new Vector3f(position.x, position.y + halfHeight, position.z);
    }

    public boolean isHitByPlayer(Vector3f p) {
        if (p.y < position.y || p.y > position.y + 2f * halfHeight * scaleMul) return false;
        float dx = p.x - position.x;
        float dz = p.z - position.z;
        float r  = collisionRadius * scaleMul;
        return (dx * dx + dz * dz) <= r * r;
    }

    public float[] renderScaleVec() {
        float[] base = baseScaleVec();
        if (scaleMul != 1f) {
            base = new float[]{ base[0] * scaleMul, base[1] * scaleMul, base[2] * scaleMul };
        }
        return base;
    }

    private float[] baseScaleVec() {
        return new float[]{ scaleX, scaleY, scaleZ };   // sourced from EnemyArchetype
    }

    public float renderScale() {
        float[] v = renderScaleVec();
        return v[1];
    }
}