package com.leaf.game.entity;

import com.leaf.game.core.GameConfig;
import com.leaf.game.util.Camera;
import com.leaf.game.world.Block;
import com.leaf.game.world.World;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFW.*;
import com.leaf.game.world.Chunk;

/**
 * AttackController — two combat abilities wired to F and C.
 *
 *   F  — Runic Cleave   a sweeping melee strike that shatters blocks in a
 *                        wide arc and sends them flying forward.
 *                        Three-phase: WINDUP → STRIKE (instant) → RECOIL.
 *
 *   C  — Void Shard     hold to charge, release fires a glowing crystal bolt
 *                        that explodes on impact with a sphere of destruction
 *                        and a burst of crystal debris.
 *
 * ── Integration contract ────────────────────────────────────────────────────
 *   • Player.update() calls attacks.tick() after abilities.tick().
 *   • Window applies getPitchOffset() non-destructively around getViewMatrix().
 *   • Window composites getOverlayStrength/Color with abilities overlay.
 *   • Window drains pendingDebris into DroppedItems each frame.
 *   • Window reads shakeRequest, triggers shake, then zeros it.
 *   • Window renders activeBolts as spinning CRYSTAL_AMETHYST cubes.
 */
public class AttackController {

    // ── Bolt ──────────────────────────────────────────────────────────────────

    /** A single Void Shard bolt in flight. */
    public static class ActiveBolt {
        public final Vector3f pos;
        public final Vector3f vel;
        public       float    lifetime;   // counts down to 0 then removed
        public final float    chargeF;    // 0–1 — scales explosion radius & shake
        public       float    spinPhase;  // accumulates for visual spin
        public       boolean  alive = true;
        public       boolean  redirected = false; // Prevents multiple redirects on the same stand

        ActiveBolt(Vector3f pos, Vector3f vel, float chargeF) {
            this.pos      = new Vector3f(pos);
            this.vel      = new Vector3f(vel);
            this.lifetime = GameConfig.voidShardLifetime;
            this.chargeF  = chargeF;
            this.spinPhase = 0f;
        }
    }

    // ── Debris spawn request ──────────────────────────────────────────────────

    /**
     * Queued by tick(); drained by Window into DroppedItems next frame.
     * Avoids giving this controller a direct reference to the dropped-item list.
     */
    public static class DebrisSpawn {
        public final int bx, by, bz;
        public final Block block;
        public final Vector3f vel;
        DebrisSpawn(int bx, int by, int bz, Block block, Vector3f vel) {
            this.bx = bx; this.by = by; this.bz = bz;
            this.block = block;
            this.vel   = new Vector3f(vel);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    private final Player player;

    // ── Melee state (F — Runic Cleave) ───────────────────────────────────────
    private enum MeleePhase { IDLE, WINDUP, RECOIL }
    private MeleePhase meleePhase     = MeleePhase.IDLE;
    private float      meleeTimer     = 0f;
    private float      meleeCooldown  = 0f;
    private boolean    lastF          = false;

    // ── Ranged state (C — Void Shard) ────────────────────────────────────────
    private boolean isCharging         = false;
    private float   chargeTime         = 0f;
    private float   rangedCooldown     = 0f;
    private boolean lastC              = false;

    /** Live bolts in flight — Window renders these. */
    public final List<ActiveBolt> activeBolts = new ArrayList<>();

    /** Debris waiting to be spawned — drained by Window each frame. */
    public final List<DebrisSpawn> pendingDebris = new ArrayList<>();

    /**
     * Sphere explosions queued this frame — drained by Window into EnemyManager.
     * Format: float[4] { centreX, centreY, centreZ, radius }
     */
    public final List<float[]> pendingExplosions = new ArrayList<>();

    /**
     * Melee arc sweeps queued this frame — drained by Window into EnemyManager.
     * Format: float[7] { originX, originY, originZ, dirX, dirY, dirZ, range }
     */
    public final List<float[]> pendingMeleeArcs = new ArrayList<>();

    // ── Enemy Manager reference ───────────────────────────────────────────────
    private EnemyManager enemyManager = null;
    public void setEnemyManager(EnemyManager em) { this.enemyManager = em; }

    // ── Fast Knife Combo (; key) ──────────────────────────────────────────────
    // Three rapid slashes: deals damage but never destroys terrain.
    private int     knifeComboStep   = 0;    // 0 = idle, 1-3 = combo in progress
    private float   knifeHitTimer    = 0f;   // countdown per swing animation
    private float   knifeComboTimer  = 0f;   // window to continue the combo
    private float   knifeCooldown_   = 0f;   // post-combo cooldown
    private boolean lastSemicolon    = false;
    /** 0=idle, 1=peak-of-swing, decays; used by Window for the knife view model. */
    public  float   knifeSwingPhase  = 0f;
    /** +1 = right slash, -1 = left slash; alternates each hit. */
    public  float   knifeSwingDir    = 1f;

    // ── Camera effects ────────────────────────────────────────────────────────
    // pitchOffset:  target value set per-phase, smoothed into smoothPitch.
    // Window applies smoothPitch non-destructively around getViewMatrix().
    private float     pitchTarget    = 0f;
    private float     smoothPitch    = 0f;
    private float     fovBoost       = 0f;
    private float     overlayStrength = 0f;
    private final Vector3f overlayColor = new Vector3f();

    /**
     * Set by tick() when a strike or bolt impacts.
     * Window reads this once, triggers shake, then zeroes it.
     */
    public float shakeRequest = 0f;

    // ─────────────────────────────────────────────────────────────────────────
    public AttackController(Player p) { this.player = p; }

    // ── Public accessors (Window + Player) ───────────────────────────────────
    /** Smooth pitch offset (radians) — apply around getViewMatrix() in Window. */
    public float    getPitchOffset()     { return smoothPitch; }
    /** Add to FlightController + abilities FOV boosts. */
    public float    getFovBoost()        { return fovBoost; }
    public float    getOverlayStrength() { return overlayStrength; }
    public Vector3f getOverlayColor()    { return overlayColor; }
    /** 0–1 charge progress for optional HUD display. */
    public float    getChargeFrac()      { return isCharging ? Math.min(1f, chargeTime / GameConfig.voidShardMaxCharge) : 0f; }

    // ─────────────────────────────────────────────────────────────────────────
    //  Main tick — called from Player.update() every survival-mode frame
    // ─────────────────────────────────────────────────────────────────────────

    public void tick(long window, Camera camera, World world, float dt) {
        if (player.debugMode) {
            decayAll(dt);
            return;
        }

        tickCooldowns(dt);
        tickBolts(world, dt);
        tickMelee(window, camera, world, dt);
        tickRanged(window, camera, world, dt);
        // knife combo disabled — code kept, re-enable when model is ready
        // tickKnifeCombo(window, camera, dt);

        // Smooth pitch toward target
        float pitchLerp = (meleePhase == MeleePhase.IDLE && !isCharging) ? 10f : 20f;
        smoothPitch += (pitchTarget - smoothPitch) * Math.min(1f, pitchLerp * dt);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  MELEE — Runic Cleave  (F)
    // ─────────────────────────────────────────────────────────────────────────

    private void tickMelee(long window, Camera camera, World world, float dt) {
        boolean fHeld = glfwGetKey(window, GLFW_KEY_F) == GLFW_PRESS;

        switch (meleePhase) {

            case IDLE -> {
                // Leading edge of F, not on cooldown, no ability stealing control
                if (fHeld && !lastF && meleeCooldown <= 0f
                        && !player.abilities.isAnyAbilityActive()
                        && player.mana >= GameConfig.manaCleave) {
                    player.mana -= GameConfig.manaCleave;
                    meleePhase = MeleePhase.WINDUP;
                    meleeTimer = GameConfig.meleeWindupDuration;
                }
            }

            case WINDUP -> {
                meleeTimer -= dt;
                // windup: 0 (start) → 1 (end)
                float prog = 1f - Math.max(0f, meleeTimer / GameConfig.meleeWindupDuration);

                // Camera floats upward slightly — coiling energy
                pitchTarget = -0.35f * prog;
                fovBoost    = lerp(fovBoost, -6f * prog, 14f * dt);

                // Amber-gold vignette intensifies
                blendOverlay(new Vector3f(1.0f, 0.60f, 0.06f), 0.22f * prog, dt);

                if (meleeTimer <= 0f) {
                    // ── INSTANT STRIKE ────────────────────────────────────────
                    executeStrike(camera, world);

                    pitchTarget  = +0.55f;          // snap camera downward — recoil kick
                    fovBoost     = +24f;
                    // White-hot flash
                    overlayColor.set(1.0f, 0.96f, 0.88f);
                    overlayStrength = 0.58f;

                    shakeRequest = GameConfig.meleeShakeStrength;

                    meleePhase    = MeleePhase.RECOIL;
                    meleeTimer    = GameConfig.meleeRecoilDuration;
                    meleeCooldown = GameConfig.meleeCooldown;
                }
            }

            case RECOIL -> {
                meleeTimer -= dt;
                float prog = Math.max(0f, meleeTimer / GameConfig.meleeRecoilDuration);

                // Camera and FOV decay smoothly back to normal
                pitchTarget = +0.55f * prog;
                fovBoost    = lerp(fovBoost, +24f * prog, 12f * dt);

                // Orange afterglow fades
                blendOverlay(new Vector3f(0.95f, 0.32f, 0.04f), 0.18f * prog, dt);

                if (meleeTimer <= 0f) {
                    meleePhase  = MeleePhase.IDLE;
                    pitchTarget = 0f;
                }
            }
        }

        lastF = fHeld;
    }

    /**
     * Destroys solid blocks in a wide, mathematical horizontal semicircle (crescent),
     * launching shattered blocks as ejecta and baking a scorched stone scar into
     * the newly exposed walls to leave a menacing visual trace.
     */
    /**
     * Carves a double-tapered, 3D ellipsoidal crescent in the direction the player is facing,
     * cleanly supporting vertical pitch and tilt if enabled, while leaving a scorched
     * stone scar along the boundary wall.
     */
    /**
     * Carves a clean, double-tapered 3D ellipsoidal crescent in the direction
     * the player is facing. This version removes the noisy boundary blocks,
     * leaving a sharp, clean incision in the terrain.
     */
    private void executeStrike(Camera camera, World world) {
        // 1. Establish the 3D basis vectors based on the configuration toggle
        com.leaf.game.core.AudioManager.play(Math.random() > 0.5 ? "slash1" : "slash2");
        com.leaf.game.core.ScreenEffectManager.INSTANCE.hitStop(2);
        com.leaf.game.core.ScreenEffectManager.INSTANCE.flashMeleeHit();
        // Collect affected chunks for immediate rebuild (prevents transparent-ghost frames)
        java.util.Set<Chunk> cleaveChunks = new java.util.HashSet<>();
        Vector3f lookVec  = new Vector3f();
        Vector3f rightVec = new Vector3f(camera.getRight());
        Vector3f upVec    = new Vector3f();

        if (GameConfig.melee3DAiming) {
            lookVec.set(camera.getLookDirection());
            // Up vector is orthogonal to the look direction and horizontal right vector
            new Vector3f(lookVec).cross(rightVec, upVec).normalize();
        } else {
            lookVec.set(camera.getForward()); // Lock to horizontal forward only
            upVec.set(0f, 1f, 0f);            // Lock to vertical up only
        }

        // Origin point: player's mid-torso/eye-level
        Vector3f origin = new Vector3f(player.position.x, player.position.y + 1.1f, player.position.z);

        // Emit a melee arc damage event — drained by Window into EnemyManager.
        // Range matches the crescent depth (R = 7 blocks).
        pendingMeleeArcs.add(new float[] {
                origin.x, origin.y, origin.z,
                lookVec.x, lookVec.y, lookVec.z,
                7f
        });

        int R = 7; // Maximum horizontal sweep radius of the crescent (blocks)
        int vMin = -1;
        int vMax = 2;

        // 2. Iterate vertically from bottom (-1) to top (2)
        for (int vert = vMin; vert <= vMax; vert++) {
            // Calculate distance fraction from the vertical center (0.5 is the midpoint of [-1, 2])
            float dy = (vert - 0.5f) / 2.0f;

            // Semicircular vertical scaling factor (tapering the top and bottom)
            float scaleV = (float) Math.sqrt(Math.max(0.0, 1.0 - dy * dy));

            // Effective horizontal radius for this vertical slice
            float rEff = R * scaleV;
            int maxSide = (int) Math.floor(rEff);

            // 3. Iterate horizontally across the tapered width
            for (int side = -maxSide; side <= maxSide; side++) {
                // Calculate the final depth threshold at this specific 3D coordinate
                float maxDepthFloat = (float) Math.sqrt(Math.max(0.0, rEff * rEff - side * side));
                int maxDepth = (int) Math.floor(maxDepthFloat);

                // 4. Carve along the look direction (loops strictly up to maxDepth for a clean cut)
                for (int depth = 1; depth <= maxDepth; depth++) {
                    // Map local (side, vert, depth) offsets directly to 3D world space
                    float wx = origin.x + side * rightVec.x + vert * upVec.x + depth * lookVec.x;
                    float wy = origin.y + side * rightVec.y + vert * upVec.y + depth * lookVec.y;
                    float wz = origin.z + side * rightVec.z + vert * upVec.z + depth * lookVec.z;

                    int bx = (int) Math.floor(wx);
                    int by = (int) Math.floor(wy);
                    int bz = (int) Math.floor(wz);

                    // Skip out-of-world coordinates
                    if (by < 0 || by >= Chunk.HEIGHT) continue;

                    Block b = world.getBlock(bx, by, bz);

                    // Keep solid structure blocks and air intact
                    if (!b.isSolid()) continue;
                    if (b == Block.STAR_IRON || b == Block.MEGALITH
                            || b == Block.MEGALITH_CARVED || b == Block.MOSSY_MEGALITH) continue;

                    // Carve to AIR
                    world.setBlock(bx, by, bz, Block.AIR);
                    int cx = Math.floorDiv(bx, Chunk.SIZE), cy = Math.floorDiv(by, Chunk.HEIGHT), cz = Math.floorDiv(bz, Chunk.SIZE);
                    Chunk cc = world.getChunk(cx, cy, cz);
                    if (cc != null) cleaveChunks.add(cc);
                    // Also rebuild face-neighbors if block is on a chunk boundary (mirrors rebuildChunkAt)
                    int lxc = Math.floorMod(bx, Chunk.SIZE), lzc = Math.floorMod(bz, Chunk.SIZE);
                    if (lxc == 0)              { Chunk n = world.getChunk(cx-1,cy,cz); if(n!=null) cleaveChunks.add(n); }
                    if (lxc == Chunk.SIZE - 1) { Chunk n = world.getChunk(cx+1,cy,cz); if(n!=null) cleaveChunks.add(n); }
                    if (lzc == 0)              { Chunk n = world.getChunk(cx,cy,cz-1); if(n!=null) cleaveChunks.add(n); }
                    if (lzc == Chunk.SIZE - 1) { Chunk n = world.getChunk(cx,cy,cz+1); if(n!=null) cleaveChunks.add(n); }

                    // Eject debris along the actual direction of the strike
                    float speed = 12f + (float) Math.random() * 9f;
                    Vector3f ejectVel = new Vector3f(lookVec).mul(speed);

                    // Add slight randomized vertical and lateral spread
                    ejectVel.add(
                            (float) (Math.random() - 0.5) * 4f,
                            (float) (Math.random() - 0.5) * 4f + 2f,
                            (float) (Math.random() - 0.5) * 4f
                    );

                    pendingDebris.add(new DebrisSpawn(bx, by, bz, b, ejectVel));
                }
            }
        }
        // Immediately rebuild all affected chunks so no transparent-ghost frames appear
        for (Chunk c : cleaveChunks) world.buildChunkMeshes(c);
    }

    private boolean isMegalith(Block b) {
        return b == Block.MEGALITH || b == Block.MEGALITH_CARVED || b == Block.MOSSY_MEGALITH;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  RANGED — Void Shard  (C)
    // ─────────────────────────────────────────────────────────────────────────

    private void startChargeSound() {
        com.leaf.game.core.AudioManager.play("snipe_loadgun");
    }

    private void tickRanged(long window, Camera camera, World world, float dt) {
        boolean cHeld = glfwGetKey(window, GLFW_KEY_C) == GLFW_PRESS;

        if (!isCharging && meleePhase == MeleePhase.IDLE) {
            blendOverlay(new Vector3f(0f, 0f, 0f), 0f, dt);
            fovBoost = lerp(fovBoost, 0f, 14f * dt);
        }

        // Melee / cooldown interrupt — cancel charge
        if (rangedCooldown > 0f || meleePhase != MeleePhase.IDLE) {
            isCharging = false;
            lastC = cHeld;
            return;
        }

        // Entering stand view while charging — cancel cleanly
        if (player.stand.isInStandPerspective()) {
            isCharging = false;
        }

        if (cHeld) {
            // Leading edge of C, not in stand view, no other ability active
            if (!isCharging && !lastC && !player.abilities.isAnyAbilityActive()
                    && !player.stand.isInStandPerspective()) {
                isCharging = true;
                chargeTime = 0f;
                startChargeSound();
            }
            if (isCharging) {
                chargeTime = Math.min(chargeTime + dt, GameConfig.voidShardMaxCharge);
                float cf = chargeTime / GameConfig.voidShardMaxCharge;
                fovBoost = lerp(fovBoost, -35f * cf, 12f * dt);
            }
        } else if (isCharging) {
            // C released — fire
            float cf = Math.min(1f, chargeTime / GameConfig.voidShardMaxCharge);
            if (player.mana >= GameConfig.manaVoidShard) {
                player.mana -= GameConfig.manaVoidShard;
                fireBolt(camera, world, cf);
                rangedCooldown = GameConfig.voidShardCooldown;
            }
            isCharging    = false;
            overlayColor.set(0.75f, 0.40f, 1.0f);
            overlayStrength = 0f;
        }

        lastC = cHeld;
    }

    private void fireBolt(Camera camera, World world, float chargeF) {
        float speed = GameConfig.voidShardMinSpeed + chargeF * (GameConfig.voidShardMaxSpeed - GameConfig.voidShardMinSpeed);
        // 1. If piloting the drone -> Fire from the drone, with line-of-fire check
        if (player.stand.isInStandPerspective()) {
            Vector3f dir   = camera.getLookDirection();
            Vector3f start = new Vector3f(camera.position).add(new Vector3f(dir).mul(1.4f));

            // Line-of-fire check: is there a clear path at least 1 block in front?
            // Prevents firing through walls when the drone is pushed against solid terrain.
            Vector3f probePos = new Vector3f(start).add(new Vector3f(dir).mul(1.0f));
            int pbx = (int)Math.floor(probePos.x), pby = (int)Math.floor(probePos.y), pbz = (int)Math.floor(probePos.z);
            if (pby >= 0 && pby < com.leaf.game.world.Chunk.HEIGHT
                    && world.getBlock(pbx, pby, pbz).isSolid()) {
                // Wall directly in front — abort, play blocked indicator
                player.stand.blockedFlashTimer = GameConfig.standBlockedFlashTime;
                return;
            }

            ActiveBolt bolt = new ActiveBolt(start, new Vector3f(dir).mul(speed), chargeF);
            bolt.redirected = true; // Prevents it from colliding with the drone it just fired from
            activeBolts.add(bolt);
            // Play the redirect/transfer sound for stand-perspective shots
            com.leaf.game.core.AudioManager.play("snipe_redirect");

            overlayColor.set(0.75f, 0.40f, 1.0f);
            overlayStrength = 0f;
            return;
        }

        com.leaf.game.core.AudioManager.play(Math.random() > 0.5 ? "snipe1" : "snipe2");

        // 2. Player shooting AT the drone -> Homing missile to the drone!
        if (player.stand.isDeployed() && isAimingAtStand(camera)) {
            // Lock direction dead-center on the drone. This guarantees it hits the 1.5f hitbox!
            Vector3f dirToStand = new Vector3f(player.stand.standPos).sub(camera.position).normalize();
            Vector3f start = new Vector3f(camera.position).add(new Vector3f(dirToStand).mul(1.4f));

            activeBolts.add(new ActiveBolt(start, new Vector3f(dirToStand).mul(speed), chargeF));

            overlayColor.set(1.0f, 0.85f, 0.1f);
            overlayStrength = 0.30f;
            return;
        }

        // 3. Normal Player Shot
        Vector3f dir = camera.getLookDirection();
        Vector3f start = new Vector3f(camera.position).add(new Vector3f(dir).mul(1.4f));
        activeBolts.add(new ActiveBolt(start, new Vector3f(dir).mul(speed), chargeF));
    }

    private void fireFromStand(Camera camera, World world, float chargeF) {
        if (enemyManager == null) return;
        Enemy target = enemyManager.findClosestVisible(
                world, player.stand.standPos, GameConfig.standShotRange);

        Vector3f dir;
        if (target != null) {
            dir = new Vector3f(target.getCentre()).sub(player.stand.standPos).normalize();
        } else {
            // No enemy? Fire in the player's camera look direction so you see the redirect happen!
            dir = camera.getLookDirection();
        }

        float speed = GameConfig.voidShardMinSpeed
                + chargeF * (GameConfig.voidShardMaxSpeed - GameConfig.voidShardMinSpeed);
        Vector3f start = new Vector3f(player.stand.standPos);
        ActiveBolt bolt = new ActiveBolt(start, new Vector3f(dir).mul(speed), chargeF);
        bolt.redirected = true;  // skip the in-flight redirect check
        activeBolts.add(bolt);

        // Gold flash on HUD to confirm redirect fired
        overlayColor.set(1.0f, 0.85f, 0.1f);
        overlayStrength = 0.25f;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  BOLT TICK
    // ─────────────────────────────────────────────────────────────────────────

    private void tickBolts(World world, float dt) {
        for (ActiveBolt bolt : activeBolts) {
            if (!bolt.alive) continue;

            bolt.lifetime  -= dt;
            bolt.spinPhase += dt * 9f;

            if (bolt.lifetime <= 0f) {
                bolt.alive = false;
                continue;
            }

            // Sub-step to avoid tunnelling through thin walls at high speed
            float totalDist = bolt.vel.length() * dt;
            int   substeps  = Math.max(1, (int) Math.ceil(totalDist / 0.4f));
            float subDt     = dt / substeps;
            boolean hit     = false;

            for (int s = 0; s < substeps && !hit; s++) {
                bolt.pos.add(new Vector3f(bolt.vel).mul(subDt));

                // ── MANHATTAN TRANSFER REDIRECT ──────────────────────────────────────
                // When a bolt (fired toward the stand) reaches the drone, redirect it.
                if (!bolt.redirected && player.stand.isDeployed()) {
                    float distToStand = new Vector3f(bolt.pos).distance(player.stand.standPos);
                    if (distToStand < 1.5f) {
                        bolt.redirected = true;
                        bolt.pos.set(player.stand.standPos); // snap cleanly to drone centre

                        com.leaf.game.core.AudioManager.play("snipe_redirect"); // PLAY REDIRECT PIN

                        Enemy targetEnemy = (enemyManager != null)
                                ? enemyManager.findClosestVisible(world, player.stand.standPos,
                                GameConfig.standShotRange)
                                : null;

                        float currentSpeed = bolt.vel.length();

                        if (targetEnemy != null) {
                            // ── Redirect to enemy ─────────────────────────────────────────────
                            Vector3f targetDir = new Vector3f(targetEnemy.getCentre())
                                    .sub(player.stand.standPos).normalize();
                            bolt.vel.set(targetDir).mul(currentSpeed);
                        } else {
                            // ── Reflect off stand (no enemy in range) ────────────────────────
                            Vector3f incomingDir = new Vector3f(bolt.vel).normalize();

                            // Treat the saucer like a flat mirror (bounce off the top or bottom)
                            Vector3f surfaceNormal = new Vector3f(0f, incomingDir.y > 0 ? -1f : 1f, 0f);

                            float dot = incomingDir.dot(surfaceNormal);
                            Vector3f reflected = new Vector3f(incomingDir).sub(new Vector3f(surfaceNormal).mul(2f * dot));
                            bolt.vel.set(reflected).mul(currentSpeed);
                        }
                    }
                }

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

                int bx = (int) Math.floor(bolt.pos.x);
                int by = (int) Math.floor(bolt.pos.y);
                int bz = (int) Math.floor(bolt.pos.z);
                if (world.getBlock(bx, by, bz).isSolid()) {
                    boltImpact(bolt, world, bx, by, bz);
                    bolt.alive = false;
                    hit = true;
                }
            }
        }
        activeBolts.removeIf(b -> !b.alive);
    }

    /**
     * Sphere explosion centred on impact block — shatters terrain and
     * launches a burst of crystal + block-type debris outward.
     */
    private void boltImpact(ActiveBolt bolt, World world, int cx, int cy, int cz) {
        float radius = GameConfig.voidShardMinRadius
                + bolt.chargeF * (GameConfig.voidShardMaxRadius - GameConfig.voidShardMinRadius);
        int   ri     = (int) Math.ceil(radius);

        Vector3f boltDir   = new Vector3f(bolt.vel).normalize();
        int      maxDebris = 8 + (int) (bolt.chargeF * 7f);
        int      spawned   = 0;
        java.util.Set<Chunk> impactChunks = new java.util.HashSet<>();

        for (int dx = -ri; dx <= ri; dx++) {
            for (int dy = -ri; dy <= ri; dy++) {
                for (int dz = -ri; dz <= ri; dz++) {
                    if ((float)(dx*dx + dy*dy + dz*dz) > radius * radius) continue;
                    int bx = cx + dx, by = cy + dy, bz = cz + dz;
                    Block b = world.getBlock(bx, by, bz);
                    if (!b.isSolid()) continue;

                    world.setBlock(bx, by, bz, Block.AIR);
                    int icx = Math.floorDiv(bx, Chunk.SIZE), icy = Math.floorDiv(by, Chunk.HEIGHT), icz = Math.floorDiv(bz, Chunk.SIZE);
                    Chunk ic = world.getChunk(icx, icy, icz);
                    if (ic != null) impactChunks.add(ic);
                    int ilx = Math.floorMod(bx, Chunk.SIZE), ilz = Math.floorMod(bz, Chunk.SIZE);
                    if (ilx == 0)              { Chunk n = world.getChunk(icx-1,icy,icz); if(n!=null) impactChunks.add(n); }
                    if (ilx == Chunk.SIZE - 1) { Chunk n = world.getChunk(icx+1,icy,icz); if(n!=null) impactChunks.add(n); }
                    if (ilz == 0)              { Chunk n = world.getChunk(icx,icy,icz-1); if(n!=null) impactChunks.add(n); }
                    if (ilz == Chunk.SIZE - 1) { Chunk n = world.getChunk(icx,icy,icz+1); if(n!=null) impactChunks.add(n); }

                    if (spawned < maxDebris) {
                        float speed = 4f + (float) Math.random() * 9f * (0.5f + bolt.chargeF * 0.5f);
                        // Random radial direction + forward momentum from bolt
                        Vector3f ejVel = new Vector3f(
                                (float) (Math.random() - 0.5),
                                0.2f + (float) Math.random() * 0.8f,
                                (float) (Math.random() - 0.5)
                        ).normalize().mul(speed);
                        ejVel.add(new Vector3f(boltDir).mul(speed * 0.4f));
                        ejVel.y += 2f + (float) Math.random() * 3f;

                        Block debrisType = (spawned % 3 == 0) ? b : pickCrystalDebris();
                        pendingDebris.add(new DebrisSpawn(bx, by, bz, debrisType, ejVel));
                        spawned++;
                    }
                }
            }
        }

        // Immediately rebuild all affected chunks so no transparent-ghost frames appear
        for (Chunk c : impactChunks) world.buildChunkMeshes(c);

        // Emit explosion damage event — drained by Window into EnemyManager.
        // Damage scales linearly with charge: min at 0, max at full charge.
        float damage = GameConfig.voidShardMinDamage
                + bolt.chargeF * (GameConfig.voidShardMaxDamage - GameConfig.voidShardMinDamage);
        pendingExplosions.add(new float[] { cx, cy, cz, radius, damage });

        // Scale shake with charge
        shakeRequest = Math.max(shakeRequest,
                GameConfig.voidShardShakeStrength * (0.5f + bolt.chargeF * 0.5f));

        // Screen effects: hit-stop + desaturate flash for heavy shots
        if (bolt.chargeF > 0.5f) {
            com.leaf.game.core.ScreenEffectManager.INSTANCE.hitStop(2);
            com.leaf.game.core.ScreenEffectManager.INSTANCE.flashSnipe();
        }
    }

    private Block pickCrystalDebris() {
        Block[] options = { Block.CRYSTAL_AMETHYST, Block.CRYSTAL_QUARTZ,
                Block.CRYSTAL_CITRINE,  Block.CRYSTAL_ROSE };
        return options[(int) (Math.random() * options.length)];
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  FAST KNIFE COMBO  (;  key)
    // ─────────────────────────────────────────────────────────────────────────

    private void tickKnifeCombo(long window, Camera camera, float dt) {
        boolean semiHeld = glfwGetKey(window, GLFW_KEY_SEMICOLON) == GLFW_PRESS;

        // Decay swing phase every frame
        if (knifeHitTimer <= 0f) {
            knifeSwingPhase = Math.max(0f, knifeSwingPhase - dt * 7f);
        }

        // Cooldowns
        if (knifeCooldown_  > 0f) { knifeCooldown_  -= dt; lastSemicolon = semiHeld; return; }
        if (knifeComboTimer > 0f)   knifeComboTimer -= dt;
        if (knifeHitTimer   > 0f) {
            knifeHitTimer -= dt;
            lastSemicolon  = semiHeld;
            return;
        }

        // Combo window expired → reset
        if (knifeComboStep > 0 && knifeComboTimer <= 0f) {
            knifeComboStep  = 0;
            knifeSwingPhase = 0f;
        }

        // Trigger next hit on leading edge of semicolon
        if (semiHeld && !lastSemicolon && !player.abilities.isAnyAbilityActive()) {
            knifeComboStep++;
            knifeSwingDir   = (knifeComboStep % 2 == 1) ? 1f : -1f;  // alternate L/R
            knifeSwingPhase = 1f;
            knifeHitTimer   = GameConfig.knifeHitDuration;
            knifeComboTimer = GameConfig.knifeComboWindow;

            // ── Damage enemies in a forward cone ─────────────────────────────
            if (enemyManager != null) {
                Vector3f origin = new Vector3f(
                        player.position.x,
                        player.position.y + 1.0f,
                        player.position.z);
                Vector3f look = camera.getLookDirection();
                for (Enemy e : enemyManager.getEnemies()) {
                    if (!e.alive) continue;
                    Vector3f toE = new Vector3f(e.getCentre()).sub(origin);
                    float dist = toE.length();
                    if (dist > GameConfig.knifeRange) continue;
                    // ~66 degree half-angle cone (dot > 0.4)
                    if (new Vector3f(toE).normalize().dot(look) > 0.4f) {
                        e.applyDamage(GameConfig.knifeDamage);
                        // Light stagger push
                        e.applyKnockback(look.x * 2.5f, 0.8f, look.z * 2.5f);
                    }
                }
            }

            // Small camera punch each hit
            pitchTarget = 0.10f * knifeSwingDir;
            shakeRequest = Math.max(shakeRequest, 0.035f);

            // After 3rd hit, go on cooldown and reset
            if (knifeComboStep >= 3) {
                knifeCooldown_ = GameConfig.knifeCooldown;
                knifeComboStep = 0;
                knifeComboTimer = 0f;
            }
        }

        lastSemicolon = semiHeld;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void tickCooldowns(float dt) {
        if (meleeCooldown  > 0f) meleeCooldown  -= dt;
        if (rangedCooldown > 0f) rangedCooldown -= dt;
    }

    public float getMeleeCooldownFrac() {
        return meleeCooldown <= 0f ? 1f : 1f - meleeCooldown / GameConfig.meleeCooldown;
    }

    private void blendOverlay(Vector3f targetColor, float targetStrength, float dt) {
        float t = Math.min(1f, 13f * dt);
        overlayColor.lerp(targetColor, t);
        overlayStrength += (targetStrength - overlayStrength) * t;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * Math.min(1f, t);
    }

    private void decayAll(float dt) {
        pitchTarget = 0f;
        smoothPitch   += (0f - smoothPitch)   * Math.min(1f, 10f * dt);
        fovBoost      += (0f - fovBoost)      * Math.min(1f, 8f  * dt);
        knifeSwingPhase = Math.max(0f, knifeSwingPhase - dt * 7f);
        blendOverlay(new Vector3f(0f, 0f, 0f), 0f, dt);
    }

    /** Raycasts up to 100 blocks to find where the Void Shard is currently aiming. */
    public Vector3f getAimTarget(Camera camera, World world) {
        Vector3f dir  = camera.getLookDirection();
        float    step = 0.5f;
        float    max  = 100.0f;

        float rx = camera.position.x, ry = camera.position.y, rz = camera.position.z;

        for (float dist = 0f; dist < max; dist += step) {
            rx += dir.x * step;
            ry += dir.y * step;
            rz += dir.z * step;

            int bx = (int)Math.floor(rx);
            int by = (int)Math.floor(ry);
            int bz = (int)Math.floor(rz);

            if (by >= 0 && by < Chunk.HEIGHT && world.getBlock(bx, by, bz).isSolid()) {
                return new Vector3f(rx, ry, rz);
            }
        }
        return new Vector3f(camera.position).add(dir.mul(max));
    }

    /**
     * Returns true if the player's crosshair is aiming close enough
     * to the stand drone to trigger a redirect.
     */
    /**
     * Returns true if the player's crosshair is aiming close enough
     * to the stand drone to trigger a redirect.
     */
    public boolean isAimingAtStand(Camera camera) {
        if (!player.stand.isDeployed()) return false;
        Vector3f toStand = new Vector3f(player.stand.standPos).sub(camera.position);
        float dist = toStand.length();

        // 0.92f gives a highly forgiving ~23 degree auto-aim cone
        return new Vector3f(toStand).normalize().dot(camera.getLookDirection()) > 0.995f;
    }

    public boolean isRedirectAvailable(World world) {
        if (!player.stand.isDeployed() || enemyManager == null) return false;
        return enemyManager.findClosestVisible(
                world, player.stand.standPos, GameConfig.standShotRange) != null;
    }
}