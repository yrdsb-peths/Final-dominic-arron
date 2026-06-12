package com.leaf.game.entity;

import com.leaf.game.core.GameConfig;

/**
 * EnemyArchetype — the SINGLE source of truth for an enemy type's numbers.
 *
 * ── Why this exists ──────────────────────────────────────────────────────────
 *  Adding an enemy used to mean hand-editing several scattered {@code switch (type)}
 *  blocks (constructor stats, knockback resist, render scale…) that could silently
 *  drift out of sync. Everything that describes "what an enemy IS" now lives in one
 *  place: {@link #forType}. The Enemy constructor just copies these values out.
 *
 * ── How to add a NEW enemy ───────────────────────────────────────────────────
 *   1. Add the constant to {@link Enemy.Type}.
 *   2. Add ONE {@code case} here describing its stats (the compiler will refuse to
 *      build until you do — that's intentional, so a new type can never be
 *      half-defined).
 *   3. Add its AI to the dispatch switch in {@code Enemy.update(...)} (behaviour is
 *      genuinely per-type, so that stays as code) and load its model where the
 *      others are loaded.
 *
 * ── Note on GameConfig ───────────────────────────────────────────────────────
 *  Several stats read from {@link GameConfig}, whose fields are runtime-tunable.
 *  {@code forType} is therefore called fresh at each spawn (NOT cached), so live
 *  config tweaks keep working exactly as before. Spawns are occasional, so the tiny
 *  allocation is free.
 *
 *  Fields are authored with a fluent builder; anything left unset keeps the sane
 *  default below, so a minimal enemy is just a line or two.
 */
public final class EnemyArchetype {

    // ── Sensible defaults (overridden per type below) ─────────────────────────
    public float health         = 100f;
    public float speed          = 4f;
    public float damagePerSec   = 10f;
    public float aggroRange     = 40f;
    public float attackRange    = 2f;
    public float attackInterval = 1f;
    public float radius         = 0.5f;   // collision radius
    public float halfHeight     = 1.0f;
    public float scaleX = 1f, scaleY = 1f, scaleZ = 1f;  // render scale
    public float knockbackResist = 1.0f;  // 1 = full knockback, 0 = immovable

    // ── Fluent authoring helpers ──────────────────────────────────────────────
    private EnemyArchetype hp(float v)              { health = v; return this; }
    private EnemyArchetype moveSpeed(float v)       { speed = v; return this; }
    private EnemyArchetype dps(float v)             { damagePerSec = v; return this; }
    private EnemyArchetype aggro(float v)           { aggroRange = v; return this; }
    private EnemyArchetype attack(float r, float i) { attackRange = r; attackInterval = i; return this; }
    private EnemyArchetype body(float r, float hh)  { radius = r; halfHeight = hh; return this; }
    private EnemyArchetype scale(float x, float y, float z) { scaleX = x; scaleY = y; scaleZ = z; return this; }
    private EnemyArchetype scale(float s)           { return scale(s, s, s); }
    private EnemyArchetype kbResist(float v)        { knockbackResist = v; return this; }

    private static EnemyArchetype a() { return new EnemyArchetype(); }

    /** The defining stat block for each enemy type. Exhaustive on purpose. */
    public static EnemyArchetype forType(Enemy.Type type) {
        return switch (type) {
            case GOLEM -> a()
                .hp(GameConfig.golemHealth).moveSpeed(GameConfig.golemSpeed)
                .dps(GameConfig.golemDamagePerSec).aggro(GameConfig.golemAggroRange)
                .attack(GameConfig.golemAttackRange, GameConfig.golemAttackInterval)
                .body(1.1f, 1.8f).scale(1.40f, 1.60f, 1.40f).kbResist(0.15f);

            case THROWER -> a()
                .hp(GameConfig.throwerHealth).moveSpeed(GameConfig.throwerSpeed)
                .dps(GameConfig.throwerDamagePerSec).aggro(GameConfig.throwerAggroRange)
                .attack(GameConfig.throwerAttackRange, GameConfig.throwerAttackInterval)
                .body(0.5f, 1.1f).scale(0.48f, 0.92f, 0.48f);

            case ZOMBIE -> a()
                .hp(GameConfig.zombieHealth).moveSpeed(GameConfig.zombieSpeed)
                .dps(GameConfig.zombieDamagePerSec).aggro(GameConfig.zombieAggroRange)
                .attack(GameConfig.zombieAttackRange, GameConfig.zombieAttackInterval)
                .body(0.5f, 1.0f).scale(0.75f, 0.88f, 0.75f);

            case SLIME -> a()
                .hp(GameConfig.slimeHealth).moveSpeed(GameConfig.slimeSpeed)
                .dps(GameConfig.slimeDamagePerSec).aggro(GameConfig.slimeAggroRange)
                .attack(GameConfig.slimeAttackRange, GameConfig.slimeAttackInterval)
                .body(0.6f, 0.5f).scale(0.85f, 0.85f, 0.85f);

            case GUARDIAN -> a()
                .hp(GameConfig.guardianHealth).moveSpeed(GameConfig.guardianSpeed)
                .dps(GameConfig.guardianHitDamage).aggro(GameConfig.guardianAggroRange)
                .attack(GameConfig.guardianAttackRange, GameConfig.guardianAttackTime)
                .body(1.1f, 1.4f).scale(1.0f, 1.0f, 1.0f);

            case DUMMY -> a()   // practice target: never moves, never attacks, very high HP
                .hp(999f).moveSpeed(0f).dps(0f).aggro(0f).attack(0f, 999f)
                .body(0.5f, 1.0f).scale(0.75f, 0.88f, 0.75f);

            case SPIDER -> a()
                .hp(180f).moveSpeed(8.0f).dps(25f).aggro(45f).attack(2.5f, 1.2f)
                .body(1.2f, 0.8f).scale(1.0f).kbResist(0.30f);   // wide, squat hitbox

            case LAVA_SLIME -> a()
                .hp(GameConfig.lavaSlimeHealth).moveSpeed(GameConfig.lavaSlimeSpeed)
                .dps(GameConfig.lavaSlimeDamagePerSec).aggro(GameConfig.lavaSlimeAggroRange)
                .attack(GameConfig.lavaSlimeAttackRange, GameConfig.lavaSlimeAttackInterval)
                .body(0.6f, 0.5f).scale(0.92f, 0.92f, 0.92f);    // chunkier slime body

            case INFERNO_TOWER -> a()   // stationary high-HP spawner; inert but active
                .hp(GameConfig.infernoTowerHealth).moveSpeed(0f).dps(0f)
                .aggro(GameConfig.infernoAggroRange).attack(0f, 999f)
                .body(3.0f, 6.0f).scale(3.0f);                   // ~12-block landmark

            case TREANT -> a()
                .hp(450f).moveSpeed(3.0f).dps(45f).aggro(0f).attack(3.5f, 1.5f)
                .body(1.0f, 2.5f).scale(1.0f).kbResist(0.30f);   // slow, massive HP, won't auto-aggro
        };
    }
}
