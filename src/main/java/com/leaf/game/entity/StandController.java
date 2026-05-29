package com.leaf.game.entity;

import com.leaf.game.core.GameConfig;
import com.leaf.game.util.Camera;
import com.leaf.game.world.Block;
import com.leaf.game.world.Chunk;
import com.leaf.game.world.World;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFW.*;

/**
 * StandController — "Manhattan Transfer" ability.
 *
 *   X    : deploy / recall the stand (drone)
 *   TAB  : enter / exit stand perspective (freezes player body)
 *   WASD + Space + Shift : fly the drone (while in stand perspective)
 *   LMB  : fire a redirect shot — requires dual LOS:
 *              [player eye → stand]  AND  [stand → crosshair target]
 *
 * ── Integration contract ───────────────────────────────────────────────────
 *   • Player.update() calls stand.tick() BEFORE abilities.tick().
 *   • If tick() returns true, Player.update() must return immediately —
 *     the player body is frozen while piloting the drone.
 *   • Window.java routes cursor δ to applyMouseLook() when inStandPerspective.
 *   • Window.java guards its LMB block-break handler with isInStandPerspective().
 *   • Window.java renders standPos drone + activeBolts each frame.
 *   • Window drains pendingDebris (same as AttackController pattern).
 *   • Window reads shakeRequest, triggers shake, then zeroes it.
 *
 * ── Design notes ──────────────────────────────────────────────────────────
 *   Stand health is tracked so enemies can break it later.
 *   Damage does NOT propagate to the owner (master).
 *   Dual LOS check: player eye → stand  (owner can "see" the stand)
 *                   stand     → target  (stand has line of fire to target)
 *   Both must be clear for the redirect shot to fire.
 */
public class StandController {

    // ─────────────────────────────────────────────────────────────────────────
    //  Stand bolt (in-flight redirect projectile)
    // ─────────────────────────────────────────────────────────────────────────

    public static class StandBolt {
        public final Vector3f pos;
        public final Vector3f vel;
        public       float    lifetime;   // seconds remaining
        public       float    spinPhase;  // for visual rotation
        public       boolean  alive = true;

        StandBolt(Vector3f pos, Vector3f vel) {
            this.pos       = new Vector3f(pos);
            this.vel       = new Vector3f(vel);
            // TTL = range / speed so bolt vanishes at max range
            this.lifetime  = GameConfig.standShotRange / vel.length();
            this.spinPhase = 0f;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    private final Player player;

    // ── Stand state ───────────────────────────────────────────────────────────
    private boolean  isDeployed       = false;
    /** World-space centre of the deployed stand drone. */
    public  Vector3f standPos         = new Vector3f();
    public  float    standHealth      = GameConfig.standMaxHealth;
    /** Cooldown before the stand can be re-deployed (after recall or destruction). */
    private float    redeployCooldown = 0f;

    // ── Drone perspective state ────────────────────────────────────────────────
    private boolean  inStandPerspective = false;
    /** Separate camera used when the player is piloting the drone. */
    public  final Camera standCamera  = new Camera();
    private boolean  lastTab          = false;

    // ── Hover bob (cosmetic when deployed) ────────────────────────────────────
    /** Accumulated time in seconds — drives the idle up/down bob. */
    public  float    bobPhase         = 0f;

    // ── LOS status (updated each tick, read by Window for HUD) ────────────────
    public  boolean  losOwnerToStand  = false;
    public  boolean  losStandToTarget = false;

    // ── Blocked-LOS warning flash ─────────────────────────────────────────────
    float    blockedFlashTimer = 0f;  // package-private so AttackController can set it

    // ── Deploy / recall key edge detector ────────────────────────────────────
    private boolean  lastX            = false;

    // ── LMB edge detector ─────────────────────────────────────────────────────

    // ── Auto-aim flag ─────────────────────────────────────────────────────────
    /**
     * Set to true when the stand fires at an enemy in auto-aim mode this frame.
     * Window checks this to suppress block-breaking on the same LMB press.
     */
    public  boolean autoAimedThisFrame = false;

    // ── Output — drained / read by Window ─────────────────────────────────────
    public  final List<StandBolt>                    activeBolts   = new ArrayList<>();
    /** Debris to be spawned by Window (same pattern as AttackController). */
    public  final List<AttackController.DebrisSpawn> pendingDebris = new ArrayList<>();
    /** Set when a bolt impacts — Window reads, triggers shake, then zeroes it. */
    public  float shakeRequest = 0f;
    /**
     * Sphere explosions queued by bolt impacts — drained by Window into EnemyManager.
     * Format: float[4] { centreX, centreY, centreZ, radius }
     */
    public  final List<float[]> pendingExplosions = new ArrayList<>();

    // ── Enemy manager reference (injected by Window after construction) ────────
    private EnemyManager enemyManager = null;

    /** Called by Window.java after EnemyManager is created. */
    public void setEnemyManager(EnemyManager em) { this.enemyManager = em; }

    // ─────────────────────────────────────────────────────────────────────────
    public StandController(Player p) {
        this.player = p;
    }

    // ── Public accessors ──────────────────────────────────────────────────────
    public boolean isDeployed()           { return isDeployed; }
    public boolean isInStandPerspective() { return inStandPerspective; }
    public float   getBlockedFlash()      { return Math.max(0f, blockedFlashTimer); }

    /** 0 = on cooldown, 1 = ready. */
    public float getRedeployCooldownFrac() {
        float max = Math.max(GameConfig.standRecallCooldown, GameConfig.standDestroyCooldown);
        return redeployCooldown <= 0f ? 1f : 1f - redeployCooldown / max;
    }

    /**
     * Called by Window's cursor callback when inStandPerspective is true.
     * Routes mouse look to the stand's own camera instead of the player camera.
     */
    public void applyMouseLook(double dx, double dy) {
        standCamera.yaw   += (float)dx * GameConfig.mouseSensitivity;
        standCamera.pitch -= (float)dy * GameConfig.mouseSensitivity;
        standCamera.clampPitch();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Main tick — call from Player.update() BEFORE abilities.tick()
    //
    //  Returns true  → Player body frozen; Window must use standCamera this frame.
    //  Returns false → Normal player physics proceeds.
    // ─────────────────────────────────────────────────────────────────────────

    public boolean tick(long window, Camera camera, World world, float deltaTime) {
        tickCooldowns(deltaTime);
        tickBolts(world, deltaTime);
        updateLOS(camera, world);

        // ── X — Deploy / Recall ────────────────────────────────────────────────
        boolean xHeld = glfwGetKey(window, GLFW_KEY_X) == GLFW_PRESS;
        if (xHeld && !lastX) {
            if (isDeployed) {
                recall();
            } else if (redeployCooldown <= 0f) {
                deploy(camera, world);
            }
        }
        lastX = xHeld;

        // ── TAB — Enter / Exit Stand Perspective ──────────────────────────────
        boolean tabHeld = glfwGetKey(window, GLFW_KEY_TAB) == GLFW_PRESS;
        if (tabHeld && !lastTab) {
            if (inStandPerspective) {
                inStandPerspective = false;
            } else if (isDeployed) {
                inStandPerspective = true;
                // Smooth entry: inherit player look direction so there's no snap
                standCamera.yaw   = camera.yaw;
                standCamera.pitch = camera.pitch;
            }
        }
        lastTab = tabHeld;

        // Force exit perspective if stand was recalled or destroyed
        if (!isDeployed && inStandPerspective) {
            inStandPerspective = false;
        }

        // ── Idle hover bob ─────────────────────────────────────────────────────
        if (isDeployed) {
            bobPhase += deltaTime * GameConfig.standHoverBobSpeed * (float)(2 * Math.PI);
        }

        // ── Stand Perspective: move drone + shoot ─────────────────────────────
        if (inStandPerspective && isDeployed) {
            tickStandFlight(window, world, deltaTime);
            tickStandShoot(window, world, deltaTime);

            // Keep standCamera position locked to drone centre, raised above
            // the saucer body so the model doesn't block the view.
            standCamera.position.set(standPos.x, standPos.y + 0.5f, standPos.z);
            return true;   // caller must skip physics
        }

        // LMB does nothing while the stand is deployed outside stand perspective.
        autoAimedThisFrame = false;
        return false;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Deploy / Recall / Destroy
    // ─────────────────────────────────────────────────────────────────────────

    private void deploy(Camera camera, com.leaf.game.world.World world) {
        isDeployed  = true;
        standHealth = GameConfig.standMaxHealth;

        float spawnX = player.position.x;
        float spawnZ = player.position.z;
        float wantY  = player.position.y + GameConfig.standDeployHeight;

        // Scan upward from the desired spawn Y to find the first unobstructed block.
        // This prevents the drone from materialising inside solid geometry.
        int sx = (int)Math.floor(spawnX), sz = (int)Math.floor(spawnZ);
        int startY = (int)Math.floor(wantY);
        float clearY = wantY;
        for (int sy = startY; sy < com.leaf.game.world.Chunk.HEIGHT - 1; sy++) {
            if (!world.getBlock(sx, sy, sz).isSolid()) {
                clearY = sy + 0.5f;
                break;
            }
        }
        standPos.set(spawnX, clearY, spawnZ);

        // Face the same direction as the player so the drone is immediately usable
        standCamera.yaw   = camera.yaw;
        standCamera.pitch = 0f;
        bobPhase          = 0f;
    }

    private void recall() {
        isDeployed         = false;
        inStandPerspective = false;
        redeployCooldown   = GameConfig.standRecallCooldown;
    }

    /**
     * Called by enemies (future feature) when stand HP reaches zero.
     * Damage does NOT transfer to the owner — stand simply vanishes.
     */
    public void onDestroyed() {
        isDeployed         = false;
        inStandPerspective = false;
        redeployCooldown   = GameConfig.standDestroyCooldown;
        standHealth        = 0f;
    }

    /** Apply damage to the stand (called by future enemy AI / projectile system). */
    public void applyDamage(float amount) {
        standHealth = Math.max(0f, standHealth - amount);
        if (standHealth <= 0f) onDestroyed();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Drone flight (WASD + Space + Shift while in stand perspective)
    // ─────────────────────────────────────────────────────────────────────────

    private void tickStandFlight(long window, World world, float dt) {
        Vector3f fwd   = standCamera.getLookDirection();
        Vector3f right = standCamera.getRight();
        Vector3f move  = new Vector3f();

        if (glfwGetKey(window, GLFW_KEY_W)          == GLFW_PRESS) move.add(fwd);
        if (glfwGetKey(window, GLFW_KEY_S)          == GLFW_PRESS) move.sub(fwd);
        if (glfwGetKey(window, GLFW_KEY_D)          == GLFW_PRESS) move.add(right);
        if (glfwGetKey(window, GLFW_KEY_A)          == GLFW_PRESS) move.sub(right);
        if (glfwGetKey(window, GLFW_KEY_SPACE)      == GLFW_PRESS) move.y += 1f;
        if (glfwGetKey(window, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS) move.y -= 1f;

        float len = move.length();
        if (len > 0.01f) {
            move.div(len).mul(GameConfig.standSpeed * dt);

            // Per-axis collision — probe 0.4 blocks ahead so the camera never
            // clips into a wall face and causes see-through rendering.
            final float MARGIN = 0.4f;
            float nx = standPos.x + move.x;
            float probeX = move.x != 0 ? nx + Math.signum(move.x) * MARGIN : nx;
            if (!world.getBlock((int)Math.floor(probeX),
                                (int)Math.floor(standPos.y),
                                (int)Math.floor(standPos.z)).isSolid()) {
                standPos.x = nx;
            }
            float ny = Math.max(1f, Math.min(Chunk.HEIGHT - 2f, standPos.y + move.y));
            float probeY = move.y != 0 ? ny + Math.signum(move.y) * MARGIN : ny;
            probeY = Math.max(1f, Math.min(Chunk.HEIGHT - 2f, probeY));
            if (!world.getBlock((int)Math.floor(standPos.x),
                                (int)Math.floor(probeY),
                                (int)Math.floor(standPos.z)).isSolid()) {
                standPos.y = ny;
            } else {
                standPos.y = Math.max(1f, Math.min(Chunk.HEIGHT - 2f, standPos.y));
            }
            float nz = standPos.z + move.z;
            float probeZ = move.z != 0 ? nz + Math.signum(move.z) * MARGIN : nz;
            if (!world.getBlock((int)Math.floor(standPos.x),
                                (int)Math.floor(standPos.y),
                                (int)Math.floor(probeZ)).isSolid()) {
                standPos.z = nz;
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Redirect shot (LMB while in stand perspective)
    // ─────────────────────────────────────────────────────────────────────────

    private void tickStandShoot(long window, World world, float dt) {
        //EMPTY - NO HITTING BY LEFT CLICKING, ONLY HOLDING C
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Line-of-sight helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Updates losOwnerToStand and losStandToTarget from the current state. */
    private void updateLOS(Camera playerCamera, World world) {
        if (!isDeployed) {
            losOwnerToStand  = false;
            losStandToTarget = false;
            return;
        }
        losOwnerToStand = hasLOS(world, playerCamera.position, standPos);

        if (inStandPerspective) {
            Vector3f target = findLookTarget(world, standCamera, GameConfig.standShotRange);
            losStandToTarget = hasLOS(world, standPos, target);
        } else {
            losStandToTarget = false;
        }
    }

    /**
     * Static LOS raycast — returns true if no solid block lies between 'from' and 'to'.
     * Step size 0.4 blocks gives reliable detection through thin walls.
     * Public so Window can also call it (e.g. for future visualisation).
     */
    public static boolean hasLOS(World world, Vector3f from, Vector3f to) {
        Vector3f diff = new Vector3f(to).sub(from);
        float dist = diff.length();
        if (dist < 0.5f) return true;

        Vector3f dir  = new Vector3f(diff).div(dist);
        float    step = 0.4f;
        float    rx   = from.x, ry = from.y, rz = from.z;

        for (float d = step; d < dist - 0.5f; d += step) {
            rx += dir.x * step;
            ry += dir.y * step;
            rz += dir.z * step;
            int bx = (int)Math.floor(rx), by = (int)Math.floor(ry), bz = (int)Math.floor(rz);
            if (by < 0 || by >= Chunk.HEIGHT) return false;
            if (world.getBlock(bx, by, bz).isSolid()) return false;
        }
        return true;
    }

    /** Step along standCamera's look direction until we hit something (or reach max range). */
    private Vector3f findLookTarget(World world, Camera cam, float maxRange) {
        Vector3f dir = cam.getLookDirection();
        float step   = 0.5f;
        float rx     = cam.position.x, ry = cam.position.y, rz = cam.position.z;

        for (float d = step; d < maxRange; d += step) {
            rx += dir.x * step;
            ry += dir.y * step;
            rz += dir.z * step;
            int bx = (int)Math.floor(rx), by = (int)Math.floor(ry), bz = (int)Math.floor(rz);
            if (by < 0 || by >= Chunk.HEIGHT) break;
            if (world.getBlock(bx, by, bz).isSolid()) return new Vector3f(rx, ry, rz);
        }
        return new Vector3f(rx, ry, rz);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Bolt tick
    // ─────────────────────────────────────────────────────────────────────────

    private void tickBolts(World world, float dt) {
        for (StandBolt bolt : activeBolts) {
            if (!bolt.alive) continue;

            bolt.lifetime  -= dt;
            bolt.spinPhase += dt * 9f;

            if (bolt.lifetime <= 0f) { bolt.alive = false; continue; }

            float totalDist = bolt.vel.length() * dt;
            int   substeps  = Math.max(1, (int)Math.ceil(totalDist / 0.4f));
            float subDt     = dt / substeps;
            boolean hit     = false;

            for (int s = 0; s < substeps && !hit; s++) {
                bolt.pos.add(new Vector3f(bolt.vel).mul(subDt));

                // Check enemy collision first so we hit them directly
                if (enemyManager != null) {
                    for (Enemy enemy : enemyManager.getEnemies()) {
                        if (enemy.alive && new Vector3f(bolt.pos).distance(enemy.getCentre()) < 1.8f) {
                            boltImpact(bolt, world, (int)Math.floor(bolt.pos.x), (int)Math.floor(bolt.pos.y), (int)Math.floor(bolt.pos.z));
                            bolt.alive = false;
                            hit = true;
                            break;
                        }
                    }
                }
                if (hit) break;

                int bx = (int)Math.floor(bolt.pos.x);
                int by = (int)Math.floor(bolt.pos.y);
                int bz = (int)Math.floor(bolt.pos.z);
                if (by < 0 || by >= Chunk.HEIGHT) { bolt.alive = false; hit = true; break; }
                if (world.getBlock(bx, by, bz).isSolid()) {
                    boltImpact(bolt, world, bx, by, bz);
                    bolt.alive = false;
                    hit = true;
                }
            }
        }
        activeBolts.removeIf(b -> !b.alive);
    }

    private void boltImpact(StandBolt bolt, World world, int cx, int cy, int cz) {
        float radius = GameConfig.standShotRadius;
        int   ri     = (int)Math.ceil(radius);
        Vector3f dir = new Vector3f(bolt.vel).normalize();
        int maxDeb   = 6, spawned = 0;
        java.util.Set<com.leaf.game.world.Chunk> impactChunks = new java.util.HashSet<>();

        for (int dx = -ri; dx <= ri; dx++) {
            for (int dy = -ri; dy <= ri; dy++) {
                for (int dz = -ri; dz <= ri; dz++) {
                    if ((float)(dx*dx + dy*dy + dz*dz) > radius * radius) continue;
                    int bx = cx + dx, by = cy + dy, bz = cz + dz;
                    if (by < 0 || by >= Chunk.HEIGHT) continue;
                    Block b = world.getBlock(bx, by, bz);
                    if (!b.isSolid()) continue;
                    world.setBlock(bx, by, bz, Block.AIR);
                    int icx = Math.floorDiv(bx, Chunk.SIZE), icy = Math.floorDiv(by, com.leaf.game.world.Chunk.HEIGHT), icz = Math.floorDiv(bz, Chunk.SIZE);
                    com.leaf.game.world.Chunk ic = world.getChunk(icx, icy, icz);
                    if (ic != null) impactChunks.add(ic);
                    int ilx = Math.floorMod(bx, Chunk.SIZE), ilz = Math.floorMod(bz, Chunk.SIZE);
                    if (ilx == 0)              { com.leaf.game.world.Chunk n = world.getChunk(icx-1,icy,icz); if(n!=null) impactChunks.add(n); }
                    if (ilx == Chunk.SIZE - 1) { com.leaf.game.world.Chunk n = world.getChunk(icx+1,icy,icz); if(n!=null) impactChunks.add(n); }
                    if (ilz == 0)              { com.leaf.game.world.Chunk n = world.getChunk(icx,icy,icz-1); if(n!=null) impactChunks.add(n); }
                    if (ilz == Chunk.SIZE - 1) { com.leaf.game.world.Chunk n = world.getChunk(icx,icy,icz+1); if(n!=null) impactChunks.add(n); }
                    if (spawned < maxDeb) {
                        float speed = 5f + (float)Math.random() * 8f;
                        Vector3f ejVel = new Vector3f(
                                (float)(Math.random() - 0.5),
                                0.3f + (float)Math.random() * 0.7f,
                                (float)(Math.random() - 0.5)
                        ).normalize().mul(speed);
                        ejVel.add(new Vector3f(dir).mul(speed * 0.3f));
                        ejVel.y += 2f;
                        pendingDebris.add(new AttackController.DebrisSpawn(bx, by, bz, b, ejVel));
                        spawned++;
                    }
                }
            }
        }
        // Immediately rebuild affected chunks so no transparent-ghost frames appear
        for (com.leaf.game.world.Chunk c : impactChunks) world.buildChunkMeshes(c);

        shakeRequest = Math.max(shakeRequest, 0.12f);

        // Emit explosion damage event — drained by Window into EnemyManager.
        pendingExplosions.add(new float[] { cx, cy, cz, radius });
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Cooldown helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void tickCooldowns(float dt) {
        if (redeployCooldown  > 0f) redeployCooldown  -= dt;
        if (blockedFlashTimer > 0f) blockedFlashTimer -= dt;
    }
}
