package com.leaf.game.entity;

import com.leaf.game.core.GameConfig;
import com.leaf.game.world.Chunk;
import com.leaf.game.world.World;
import com.leaf.game.world.gen.WorldGen;
import com.leaf.game.world.gen.biome.Biome;
import com.leaf.game.world.gen.feature.FeatureGenerator;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

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
 *   Clear-based: a wave spawns around the player, and the next one begins
 *   after it is cleared and Window dismisses the unlock card. Wave size and
 *   type mix are authored per wave in {@link WaveTable}.
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

    // ── Food drops — positions where a hotdog should spawn (drained by Window) ─
    /** Each entry = {x, y, z} of an enemy that just finished dying. */
    public final List<float[]> pendingFoodDrops = new ArrayList<>();
    /** Each entry = {x, y, z} for a death-particle burst to spawn in Window. */
    public final List<float[]> pendingDeathFX   = new ArrayList<>();

    // ── Wave spawning state ───────────────────────────────────────────────────
    private float waveTimer  = GameConfig.spawnWaveInterval; // (legacy; unused by clear-based waves)
    private int   waveNumber = 0;

    // ── Clear-based wave flow ──────────────────────────────────────────────────
    // A wave spawns, the player clears it, THEN the next wave (and its ability
    // unlock) happens. Window drives the unlock card during the pause.
    private boolean waveInProgress  = false;
    /** True when a wave was just cleared and we're waiting for Window to start the next. */
    public  boolean awaitingNextWave = false;
    /** The wave number that was just cleared (drives the unlock card). */
    public  int     lastClearedWave  = 0;

    /** Window calls this when the unlock card is dismissed, to spawn the next wave. */
    public void beginNextWave() { awaitingNextWave = false; }

    /** Full reset for a new run (player death → restart). Wave goes back to 1. */
    public void resetForNewRun() {
        for (Enemy e : enemies) e.alive = false;   // fade them out
        projectiles.clear();
        waveNumber       = 0;
        waveInProgress   = false;
        awaitingNextWave = false;
        lastClearedWave  = 0;
        totalKills       = 0;
        wavesEnabled     = true;
        // Back to the intro survival loop — open-world systems off until the
        // final wave re-enables them. Destroyed-tower memory persists for the
        // session so felled towers stay gone even across a restart.
        freeExploreMode  = false;
        activeTowerSites.clear();
        towerIdToSite.clear();
        towerDeathHandled.clear();
        pendingTowerErupts.clear();
        pendingTowerDeaths.clear();
    }

    /** Total enemies the player has killed this session (drives the demo objective). */
    public int totalKills = 0;

    /**
     * When false, the automatic infinite wave spawner is suppressed. The tutorial
     * holds this false and spawns enemies by hand at controlled distances/counts,
     * then Window flips it true on graduation so endless survival begins.
     */
    public boolean wavesEnabled = false;

    private final Random rng = new Random();

    // ── World-gen handle (biome queries for the tower director + ambient mix) ──
    private WorldGen worldGen;
    public void setWorldGen(WorldGen wg) { this.worldGen = wg; }

    // ── Free-explore open world (enabled by Window after the final wave) ───────
    /** When true, ambient roaming spawns and Inferno-Tower sites activate. */
    public boolean freeExploreMode = false;
    private float  ambientTimer    = 0f;
    private float  towerScanTimer  = 0f;

    // ── Inferno-Tower site bookkeeping ─────────────────────────────────────────
    //   A site is spawned once when the player nears it, and recorded in
    //   destroyedTowerSites on death so it never returns.
    private final Set<Long>             activeTowerSites    = new HashSet<>();
    private final Set<Long>             destroyedTowerSites = new HashSet<>();
    private final HashMap<Integer,Long> towerIdToSite       = new HashMap<>();
    private final Set<Integer>          towerDeathHandled   = new HashSet<>();

    /** VFX events drained by Window. Erupt = {mouthX,Y,Z, landX,Y,Z}; Death = {x,y,z}. */
    public final List<float[]> pendingTowerErupts = new ArrayList<>();
    public final List<float[]> pendingTowerDeaths = new ArrayList<>();

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
        Enemy e;
        if (type == Enemy.Type.SPIDER) {
            e = new com.leaf.game.entity.spider.SpiderEnemy(x, y, z);
        } else if (type == Enemy.Type.TREANT) {
            e = new TreantEnemy(x, y, z);
        } else {
            e = new Enemy(x, y, z, type);
        }
        enemies.add(e);
        return e;
    }

    /** Convenience overload — spawns a THROWER. */
    public Enemy spawnAt(Vector3f pos) {
        return spawnAt(pos.x, pos.y, pos.z);
    }

    /**
     * Spawns a small starter pack immediately when the game loads so there is
     * always something to fight from the first second. Uses the same surface-scan
     * logic as the wave system so enemies land on solid ground. The normal wave
     * timer is reset so the first automatic wave still comes after spawnWaveInterval.
     */
    /** Number of enemies that are still alive (excludes those playing the death flash). */
    public int aliveCount() {
        int n = 0;
        // DUMMY is a passive target (practice dummy / MP remote-player proxy) — it must
        // not count toward "wave cleared", or it would block wave progression forever.
        for (Enemy e : enemies) if (e.alive && e.type != Enemy.Type.DUMMY) n++;
        return n;
    }

    /**
     * Spawn {@code count} enemies of {@code type} for the tutorial at a roughly
     * fixed distance from the player (so steps are predictable, not random
     * waves). Falls back to the random surface scan, but biases toward the
     * requested ring so dummies appear in front of a new player.
     */
    public void spawnTutorialEnemies(com.leaf.game.world.World world,
                                     org.joml.Vector3f playerPos,
                                     Enemy.Type type, int count, float distance) {
        for (int i = 0; i < count; i++) {
            // Even spread around the player at the requested ring.
            float ang = (float) (Math.PI * 2 * i / Math.max(1, count))
                      + rng.nextFloat() * 0.6f;
            Vector3f sp = surfaceAt(world,
                    playerPos.x + (float) Math.cos(ang) * distance,
                    playerPos.z + (float) Math.sin(ang) * distance);
            if (sp == null) sp = findSpawnPoint(world, playerPos); // fallback
            if (sp != null) spawnAt(sp.x, sp.y, sp.z, type);
        }
    }

    /** Find the open-sky surface at a specific (x,z), or null if none. */
    private Vector3f surfaceAt(World world, float sx, float sz) {
        int bx = (int) Math.floor(sx), bz = (int) Math.floor(sz);
        for (int by = Chunk.HEIGHT - 2; by >= 1; by--) {
            if (world.getBlock(bx, by, bz).isSolid()
                    && !world.getBlock(bx, by + 1, bz).isSolid()
                    && !world.getBlock(bx, by + 2, bz).isSolid()) {
                boolean openSky = true;
                for (int sy = by + 3; sy < Chunk.HEIGHT; sy++) {
                    if (world.getBlock(bx, sy, bz).isSolid()) { openSky = false; break; }
                }
                if (openSky) return new Vector3f(sx, by + 1f, sz);
            }
        }
        return null;
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
        // Refresh each tower's live-minion count so its AI sees fresh numbers.
        refreshTowerMinionCounts();

        // ── Update every enemy ─────────────────────────────────────────────────
        for (int i = 0; i < enemies.size(); i++) {
            Enemy e = enemies.get(i);
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

            // Inferno Tower erupts a lava slime
            if (e.wantsToSpawnMinion) {
                spawnTowerMinion(world, e);
            }

            // Inferno Tower just died → record the site (so it never respawns) + VFX
            if (e.type == Enemy.Type.INFERNO_TOWER && !e.alive
                    && !towerDeathHandled.contains(e.id)) {
                handleTowerDeath(e);
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
        enemies.removeIf(e -> {
            boolean cull = !e.alive && e.hitFlashTimer <= 0f;
            if (cull) {
                totalKills++;
                pendingDeathFX.add(new float[]{ e.position.x, e.position.y + 0.8f, e.position.z });
                if (e.type == Enemy.Type.INFERNO_TOWER) {
                    towerDeathHandled.remove(e.id);
                    towerIdToSite.remove(e.id);   // site already moved to destroyed in handleTowerDeath
                    // A felled tower is a feast — drop a trio of hotdogs.
                    for (int h = 0; h < 3; h++)
                        pendingFoodDrops.add(new float[]{
                                e.position.x + (float)(Math.random() - 0.5) * 3f,
                                e.position.y + 1f,
                                e.position.z + (float)(Math.random() - 0.5) * 3f });
                } else {
                    // Every kill feeds you: drop a hotdog where the enemy fell.
                    pendingFoodDrops.add(new float[]{ e.position.x, e.position.y + 0.5f, e.position.z });
                }
            }
            return cull;
        });

        // ── Clear-based wave flow (suppressed during the tutorial) ──────────────
        //   no wave active → spawn the next one
        //   wave active and all enemies dead → mark cleared, wait for Window's card
        if (wavesEnabled) {
            if (awaitingNextWave) {
                // Paused between waves: Window is showing the ability-unlock card.
            } else if (!waveInProgress) {
                int spawned = spawnWave(world, playerPos); // increments waveNumber
                if (spawned > 0) waveInProgress = true;     // else retry next frame (no valid spawn yet)
            } else if (aliveCount() == 0) {
                waveInProgress   = false;
                lastClearedWave  = waveNumber;
                awaitingNextWave = true;                    // Window unlocks + shows card, then beginNextWave()
            }
        }

        // ── Free-explore open world: Inferno-Tower sites + ambient roamers ──────
        if (freeExploreMode) {
            towerScanTimer -= dt;
            if (towerScanTimer <= 0f) {
                towerScanTimer = 1.0f;
                scanForTowerSites(world, playerPos);
            }
            tickAmbientSpawning(dt, world, playerPos);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Inferno Tower director + lava-slime minions
    // ─────────────────────────────────────────────────────────────────────────

    /** Recompute every tower's live-minion count from its surviving lava slimes. */
    private void refreshTowerMinionCounts() {
        boolean anyTower = false;
        for (Enemy e : enemies) if (e.type == Enemy.Type.INFERNO_TOWER) { anyTower = true; break; }
        if (!anyTower) return;
        HashMap<Integer,Integer> counts = new HashMap<>();
        for (Enemy e : enemies) if (e.alive && e.ownerTowerId >= 0) counts.merge(e.ownerTowerId, 1, Integer::sum);
        for (Enemy e : enemies) if (e.type == Enemy.Type.INFERNO_TOWER) e.liveMinions = counts.getOrDefault(e.id, 0);
    }

    /** Erupt a lava slime from a tower: spawn it near the base, queue eruption VFX. */
    private void spawnTowerMinion(World world, Enemy tower) {
        float ang = rng.nextFloat() * (float)(2 * Math.PI);
        float d   = 3.5f + rng.nextFloat() * 3f;
        Vector3f sp = surfaceAt(world,
                tower.position.x + (float) Math.cos(ang) * d,
                tower.position.z + (float) Math.sin(ang) * d);
        if (sp == null) sp = new Vector3f(tower.position.x, tower.position.y, tower.position.z);
        Enemy slime = spawnAt(sp.x, sp.y, sp.z, Enemy.Type.LAVA_SLIME);
        slime.ownerTowerId = tower.id;
        // Erupt VFX: fireball from the tower mouth (~11 blocks up) arcing to the landing spot.
        pendingTowerErupts.add(new float[]{
                tower.position.x, tower.position.y + 11f, tower.position.z, sp.x, sp.y, sp.z });
    }

    /** Record a tower's death so its site never respawns, and queue the death VFX. */
    private void handleTowerDeath(Enemy tower) {
        towerDeathHandled.add(tower.id);
        pendingTowerDeaths.add(new float[]{ tower.position.x, tower.position.y, tower.position.z });
        Long site = towerIdToSite.get(tower.id);
        if (site != null) {
            activeTowerSites.remove(site);
            destroyedTowerSites.add(site);   // permanent — "when destroyed doesn't spawn anymore"
        }
    }

    /** Pack a tower-site (wx,wz) into a stable long key. */
    private static long siteKey(int wx, int wz) {
        return ((long) wx << 32) | (wz & 0xFFFFFFFFL);
    }

    /**
     * Spawn Inferno Towers at deterministic volcanic sites as the player nears them.
     * Sites already active or destroyed are skipped, so each tower appears once and,
     * once felled, never returns. Uses the same site function as the world-gen
     * foundation ({@link FeatureGenerator#infernoTowerSite}).
     */
    private void scanForTowerSites(World world, Vector3f playerPos) {
        if (worldGen == null) return;
        float R = GameConfig.infernoActivateRange;
        int region = FeatureGenerator.TOWER_REGION;
        int loX = Math.floorDiv((int)(playerPos.x - R), region);
        int hiX = Math.floorDiv((int)(playerPos.x + R), region);
        int loZ = Math.floorDiv((int)(playerPos.z - R), region);
        int hiZ = Math.floorDiv((int)(playerPos.z + R), region);

        for (int rx = loX; rx <= hiX; rx++) {
            for (int rz = loZ; rz <= hiZ; rz++) {
                int[] site = FeatureGenerator.infernoTowerSite(GameConfig.seed, rx, rz);
                if (site == null) continue;
                long key = siteKey(site[0], site[1]);
                if (activeTowerSites.contains(key) || destroyedTowerSites.contains(key)) continue;

                float dx = site[0] - playerPos.x, dz = site[1] - playerPos.z;
                if (dx * dx + dz * dz > R * R) continue;
                if (worldGen.biomeAt(site[0], site[1]) != Biome.VOLCANIC) continue;

                Vector3f sp = surfaceAt(world, site[0] + 0.5f, site[1] + 0.5f);
                if (sp == null) continue;   // chunk not meshed yet — retry next scan

                Enemy tower = spawnAt(sp.x, sp.y, sp.z, Enemy.Type.INFERNO_TOWER);
                activeTowerSites.add(key);
                towerIdToSite.put(tower.id, key);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Ambient open-world spawning (free-explore)
    // ─────────────────────────────────────────────────────────────────────────

    /** Maintain a roaming population around the player; cull roamers that wander off. */
    private void tickAmbientSpawning(float dt, World world, Vector3f playerPos) {
        float despawnSq = GameConfig.ambientDespawn * GameConfig.ambientDespawn;
        int roamers = 0;
        for (Enemy e : enemies) {
            if (!e.ambient || !e.alive) continue;
            float dx = e.position.x - playerPos.x, dz = e.position.z - playerPos.z;
            if (dx * dx + dz * dz > despawnSq) { e.alive = false; continue; }
            roamers++;
        }

        ambientTimer -= dt;
        if (ambientTimer > 0f) return;
        ambientTimer = GameConfig.ambientInterval;
        if (roamers >= GameConfig.ambientMaxNearby) return;

        Vector3f sp = findAmbientSpawn(world, playerPos);
        if (sp == null) return;
        Enemy e = spawnAt(sp.x, sp.y, sp.z, pickAmbientType(sp));
        e.ambient = true;
    }

    /** Pick a roamer type, biased to lava slimes in volcanic terrain, spiders elsewhere. */
    private Enemy.Type pickAmbientType(Vector3f sp) {
        Biome b = (worldGen != null) ? worldGen.biomeAt((int) sp.x, (int) sp.z) : null;
        float r = rng.nextFloat();
        if (b == Biome.VOLCANIC) return r < 0.6f ? Enemy.Type.LAVA_SLIME : Enemy.Type.ZOMBIE;
        if (r < 0.25f) return Enemy.Type.SPIDER;
        if (r < 0.50f) return Enemy.Type.ZOMBIE;
        if (r < 0.72f) return Enemy.Type.SLIME;
        return Enemy.Type.THROWER;
    }

    /** Like findSpawnPoint but at the ambient ring distance. */
    private Vector3f findAmbientSpawn(World world, Vector3f playerPos) {
        float minD = GameConfig.ambientMinDist, maxD = GameConfig.ambientMaxDist;
        for (int attempt = 0; attempt < 8; attempt++) {
            float angle = rng.nextFloat() * (float)(2 * Math.PI);
            float dist  = minD + rng.nextFloat() * (maxD - minD);
            Vector3f sp = surfaceAt(world,
                    playerPos.x + (float) Math.cos(angle) * dist,
                    playerPos.z + (float) Math.sin(angle) * dist);
            if (sp != null) return sp;
        }
        return null;
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

    /** Spawn the next wave's enemies. @return how many actually spawned. */
    private int spawnWave(World world, Vector3f playerPos) {
        waveNumber++;

        // Composition is authored in WaveTable — edit the curve there, not here.
        WaveTable.WaveDef def = WaveTable.forWave(waveNumber);
        int count = Math.min(def.count, GameConfig.spawnMaxEnemies - enemies.size());
        if (count <= 0) { waveNumber--; return 0; } // couldn't spawn — undo the increment, retry later

        int spawned = 0;
        for (int i = 0; i < count; i++) {
            Vector3f spawnPos = findSpawnPoint(world, playerPos);
            if (spawnPos == null) continue; // no valid surface found
            spawnAt(spawnPos.x, spawnPos.y, spawnPos.z, def.pick(rng));
            spawned++;
        }
        if (spawned == 0) waveNumber--; // nothing placed this frame — let it retry without burning a wave number
        return spawned;
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
            // Clamp to the enemy's vertical span so tall targets like the Inferno Tower
            // (halfHeight=6) still take hits from ground-level explosions.
            float nearestY = Math.max(e.position.y, Math.min(e.position.y + 2f * e.halfHeight, cy));
            float dx = e.position.x - cx, dy = nearestY - cy, dz = e.position.z - cz;
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
