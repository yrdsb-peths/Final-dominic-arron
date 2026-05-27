package com.leaf.game.entity;

import com.leaf.game.core.GameConfig;
import com.leaf.game.util.Camera;
import com.leaf.game.world.Chunk;
import com.leaf.game.world.World;
import org.joml.Vector3f;
import com.leaf.game.world.Block;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFW.*;

/**
 * AbilityController — four survival-mode player abilities.
 *
 *   Q   — Dash          horizontal burst, ghost trail, wall-stopped
 *   G   — Cannonball    hold to charge, release to launch, camera spin in flight
 *   Z   — State Rewind  hold to scrub back 5 s of player history (not world)
 *   E   — Blink         teleport to crosshair (up to blinkRange blocks)
 *
 * ── Integration contract ────────────────────────────────────────────────────
 *   • Player.update() calls abilities.tick() BEFORE the physics block.
 *   • If tick() returns true, Player.update() must skip physics and sync camera.
 *   • Dash/cannonball expose public dx/dz overrides read by Player's physics.
 *   • All abilities disabled while debugMode (flight) is active.
 *   • Only one ability can be active at a time; attempting to start a second
 *     while one is running is silently ignored.
 *
 * ── No renderer changes required ───────────────────────────────────────────
 *   Ghost trails / trajectory arcs are exposed as List<Vector3f> and rendered
 *   by Window.java using the existing item-mesh + alpha infrastructure.
 *   Overlay vignettes use the new overlayVignette* shader uniforms.
 */
public class AbilityController {

    private final Player player;

    // ── HEALING (hold J) ──────────────────────────────────────────────────────
    public  boolean isHealing         = false;
    private float   healCooldownTimer = 0f;
    // ── DASH (Q) ───────────────────────────────────────────────────────────────
    public  boolean  isDashing    = false;
    public  float    dashDirX     = 0f;   // unit vector, read by Player.update()
    public  float    dashDirZ     = 0f;
    private float    dashTimer    = 0f;
    private float    dashCooldown = 0f;
    private boolean  lastQ        = false;
    /** Ghost-trail positions (most recent last). Window renders these. */
    public  final List<Vector3f> dashTrail  = new ArrayList<>();
    /** Age of the last dash in seconds — for fading ghost trail after dash ends. */
    public  float    dashTrailAge = 999f;

    // ── CANNONBALL (hold G) ────────────────────────────────────────────────────
    public  boolean  isCannonballing = false;
    /** Horizontal velocity components injected into Player's dx/dz each frame. */
    public  float    cannonVelX      = 0f;
    public  float    cannonVelZ      = 0f;
    private boolean  isCharging_     = false;
    private float    chargeTime      = 0f;
    /** 0–1, used by Window for power-bar and FOV boost. */
    public  float    chargePower     = 0f;
    private float    cannonCooldown  = 0f;
    /** Forward pitch oscillation during flight (radians, added to camera.pitch). */
    private float    cannonPitchSpin = 0f;   // accumulated pitch offset
    private float    cannonPitchRate = 0f;   // radians/sec set at fire time
    private boolean  lastG           = false;

    // ── STONE PILLAR (hold K) ─────────────────────────────────────────────────
    public  boolean isPillaring         = false;
    private float   pillarStartY        = 0f; // Where the earth connects to the ground
    private float   pillarStartPlayerY  = 0f; // Altitude the player was at when cast
    private float   pillarCenterX       = 0f;
    private float   pillarCenterZ       = 0f;
    private float   pillarCooldownTimer = 0f;
    private boolean lastK               = false;

    /**
     * Launch direction locked at the moment charging begins (yaw only).
     * Arc preview and chunk preloading use this so they don't change if
     * the player swings the camera mid-charge.
     */
    public  float    lockedYaw       = 0f;
    public  float    lockedPitch     = 0f;
    /** Trajectory preview dots for the charging arc. Window renders these. */
    public  final List<Vector3f> trajectoryArc = new ArrayList<>();

    // ── STATE REWIND (hold Z) ─────────────────────────────────────────────────
    public  boolean  isRewinding   = false;
    private float    rewindCooldown = 0f;
    private float    snapshotTimer  = 0f;
    private float    rewindAccum    = 0f;
    // Snapshot layout: [x, y, z, velocityY, cameraYaw, cameraPitch]
    private final ArrayDeque<float[]> snapshots = new ArrayDeque<>();
    /** Recent history positions for the ghost trail visualisation. */
    public  final List<Vector3f> rewindTrail = new ArrayList<>();
    private boolean  lastZ         = false;

    // ── BLINK (E) ─────────────────────────────────────────────────────────────
    /** True for exactly one frame after a blink fires — used by Window for trail. */
    public  boolean  justBlinked     = false;
    public  float    blinkFlashTimer = 0f;
    /** Origin and destination of the most recent blink, for the ghost trail. */
    public  Vector3f blinkOrigin     = new Vector3f();
    public  Vector3f blinkDest       = new Vector3f();
    private float    blinkCooldown   = 0f;
    private boolean  lastE           = false;

    // ── SMOOTH CAMERA EFFECTS ─────────────────────────────────────────────────
    // Composited into Player.getCameraRoll() and Player.getCameraFovBoost()
    // alongside FlightController's values.
    private float smoothRoll     = 0f;
    private float smoothFovBoost = 0f;

    // ── OVERLAY VIGNETTE ──────────────────────────────────────────────────────
    // Window reads these each frame and sends them to the overlayVignette* uniforms.
    private float     overlayStrength = 0f;
    private Vector3f  overlayColor    = new Vector3f();

    // ─────────────────────────────────────────────────────────────────────────
    public AbilityController(Player p) { this.player = p; }

    // ── Public accessors (read by Player + Window) ────────────────────────────
    public boolean   isCharging()         { return isCharging_; }
    public float     getCameraRoll()      { return smoothRoll; }
    public float     getCameraFovBoost()  { return smoothFovBoost; }
    public float     getOverlayStrength() { return overlayStrength; }
    public Vector3f  getOverlayColor()    { return overlayColor; }

    // Cooldown 0 = on cooldown, 1 = fully ready
    public float getDashCooldownFrac()   { return dashCooldown   <= 0 ? 1f : 1f - dashCooldown   / GameConfig.dashCooldown; }
    public float getCannonCooldownFrac() { return cannonCooldown <= 0 ? 1f : 1f - cannonCooldown / GameConfig.cannonCooldown; }
    public float getRewindCooldownFrac() { return rewindCooldown <= 0 ? 1f : 1f - rewindCooldown / GameConfig.rewindCooldown; }
    public float getBlinkCooldownFrac()  { return blinkCooldown  <= 0 ? 1f : 1f - blinkCooldown  / GameConfig.blinkCooldown; }
    public float getPillarCooldownFrac() { return pillarCooldownTimer <= 0 ? 1f : 1f - pillarCooldownTimer / GameConfig.pillarCooldown; }
    public float getHealCooldownFrac()   { return healCooldownTimer  <= 0 ? 1f : 1f - healCooldownTimer  / GameConfig.healCooldown;  }
    // ─────────────────────────────────────────────────────────────────────────
    //  Main tick — call from Player.update() every frame, before physics block
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Updates all abilities.
     * @return true if this controller has full positional control this frame
     *         (Rewind active). Caller must skip normal physics and sync camera.
     */
    public boolean tick(long window, Camera camera, World world, float dt) {
        // Abilities only available in survival mode
        if (player.debugMode) {
            decayAll(dt);
            return false;
        }

        justBlinked = false;
        tickCooldowns(dt);
        recordSnapshot(camera, dt);
        updateRewindTrail();

        // ── STONE PILLAR (hold K) ─────────────────────────────────────────────
        boolean kHeld = glfwGetKey(window, GLFW_KEY_K) == GLFW_PRESS;

        if (kHeld && !lastK && !isAnyAbilityActive() && pillarCooldownTimer <= 0f) {
            int cx = (int) Math.floor(player.position.x);
            int cz = (int) Math.floor(player.position.z);
            int sy = (int) Math.floor(player.position.y);
            if (sy >= Chunk.HEIGHT) sy = Chunk.HEIGHT - 1;

            // Find the solid ground below the player
            while (sy > 0 && !world.getBlock(cx, sy, cz).isSolid()) {
                sy--;
            }

            // 1. MUST BE GROUNDED: Player cannot be more than 1.5 blocks above the surface
            boolean isGrounded = (player.position.y - sy) <= 1.5f;

            // 2. MASS CHECK: Require a substantial foundation (check a 5x5 area, 5 blocks deep)
            // Volume = 5 * 5 * 5 = 125 blocks.
            int solidMass = 0;
            if (isGrounded && sy > 0) {
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        for (int dy = 0; dy >= -4; dy--) {
                            // Ensure we don't check below the bottom of the world
                            if (sy + dy >= 0 && world.getBlock(cx + dx, sy + dy, cz + dz).isSolid()) {
                                solidMass++;
                            }
                        }
                    }
                }
            }

            // Require at least 40 out of 125 possible blocks to be solid.
            // Extremely lenient for jagged terrain/slopes, but mathematically rejects
            // thin floating platforms or narrow pillars!
            if (isGrounded && solidMass >= 90) {
                isPillaring = true;
                pillarCenterX = player.position.x;
                pillarCenterZ = player.position.z;
                pillarStartPlayerY = player.position.y;
                pillarStartY = sy + 1;
            }
        }

        if (isPillaring) {
            if (!kHeld || (player.position.y - pillarStartPlayerY) > GameConfig.pillarMaxHeight) {
                // End ability
                isPillaring = false;
                pillarCooldownTimer = GameConfig.pillarCooldown;
            } else {
                // Ascend
                player.setVelocityY(GameConfig.pillarRiseSpeed);

                // Build the expanding pillar frame
                buildPillarFrame(world);

                // ── SENSORY OVERLOAD FOR THE CASTER ──

                // 1. Heavy screen shake
                player.attacks.shakeRequest = Math.max(player.attacks.shakeRequest, 0.18f);

                // 2. FOV Boost (Pulls the camera back to simulate intense upward velocity)
                smoothFovBoost += (25f - smoothFovBoost) * Math.min(1f, 15f * dt);

                // 3. Dusty stone overlay to make the screen feel like it's inside an eruption
                blendOverlay(new Vector3f(0.50f, 0.50f, 0.52f), 0.35f, dt);

                // 4. Particle Debris: Eject stone blocks outwards from the edges of the platform
                int debrisCount = 2 + (int)(Math.random() * 3);
                for (int i = 0; i < debrisCount; i++) {
                    float angle = (float) (Math.random() * Math.PI * 2);
                    float edgeRadius = 1.6f; // Spawn just outside the 3x3 center

                    int bx = (int) Math.floor(pillarCenterX + Math.cos(angle) * edgeRadius);
                    int bz = (int) Math.floor(pillarCenterZ + Math.sin(angle) * edgeRadius);
                    int by = (int) Math.floor(player.position.y) - 1;

                    // Launch outwards and slightly up
                    float speed = 12f + (float) Math.random() * 8f;
                    Vector3f ejectVel = new Vector3f((float) Math.cos(angle), 0.2f, (float) Math.sin(angle)).normalize().mul(speed);

                    // We can reuse the attack controller's debris system!
                    player.attacks.pendingDebris.add(new AttackController.DebrisSpawn(bx, by, bz, Block.STONE, ejectVel));
                }
            }
        }
        lastK = kHeld;

        // ── BLINK (E) — instant ───────────────────────────────────────────────
        boolean eHeld = glfwGetKey(window, GLFW_KEY_E) == GLFW_PRESS;
        if (eHeld && !lastE && blinkCooldown <= 0f && !isAnyAbilityActive()) {
            executeBlink(camera, world);
        }
        lastE = eHeld;
        if (blinkFlashTimer > 0f) {
            blinkFlashTimer = Math.max(0f, blinkFlashTimer - dt);
            float s = blinkFlashTimer / GameConfig.blinkFlashDecay;
            blendOverlay(new Vector3f(0.93f, 0.95f, 1.0f), s * 0.38f, dt);
        }

        // ── DASH (Q) — tap ────────────────────────────────────────────────────
        boolean qHeld = glfwGetKey(window, GLFW_KEY_Q) == GLFW_PRESS;
        if (qHeld && !lastQ && dashCooldown <= 0f && !isAnyAbilityActive()) {
            startDash(window, camera);
        }
        lastQ = qHeld;
        if (isDashing) {
            dashTimer -= dt;
            dashTrailAge = 0f;
            dashTrail.add(new Vector3f(player.position)); // record ghost position
            if (dashTrail.size() > GameConfig.dashGhostCount) dashTrail.remove(0);
            blendOverlay(new Vector3f(0.45f, 0.88f, 1.0f), 0.14f, dt);
            decayCameraEffects(0f, 5f, dt); // slight FOV boost
            if (dashTimer <= 0f) isDashing = false;
        } else {
            dashTrailAge += dt;
            if (dashTrailAge > 0.45f) dashTrail.clear();
        }

        // ── CANNONBALL (hold G) ───────────────────────────────────────────────
        boolean gHeld = glfwGetKey(window, GLFW_KEY_G) == GLFW_PRESS;
        if (isCannonballing) {
            float drag = (float)Math.pow(GameConfig.cannonHorizDrag, dt * 60f);
            cannonVelX *= drag;
            cannonVelZ *= drag;
            // Forward pitch spin: accumulate and apply to camera.pitch directly.
            // This tilts the view forward (toward ground) continuously, giving
            // the tumbling sensation. Player can still adjust yaw freely.
            cannonPitchSpin += cannonPitchRate * dt;
            camera.pitch    += cannonPitchRate * dt;
            // No clampPitch — Window already skips it during cannonball so full
            // 360° is maintained.
            chargePower = Math.max(0f, chargePower - dt * 0.4f);
            blendOverlay(new Vector3f(1.0f, 0.62f, 0.1f), 0.13f, dt);
            float fovTarget = 10f + chargePower * 18f;
            smoothFovBoost += (fovTarget - smoothFovBoost) * Math.min(1f, GameConfig.cameraLerpSpeed * dt);
        } else if (cannonCooldown <= 0f) {
            // ── KEY FIX: charging is outside isAnyAbilityActive() gate ──────
            // Once isCharging_ is true it must always be able to continue or
            // fire on release. Putting it inside isAnyAbilityActive() caused
            // isCharging_ to get permanently stuck (it excluded itself).
            if (gHeld) {
                if (!isCharging_ && !isAnyAbilityActive()) {
                    isCharging_ = true;
                    chargeTime  = 0f;
                    // Lock look direction at charge-start — arc and chunk preload
                    // use this fixed direction; camera still rotates freely.
                    lockedYaw   = camera.yaw;
                    lockedPitch = camera.pitch;
                }
                if (isCharging_) {
                    chargeTime  = Math.min(chargeTime + dt, GameConfig.cannonMaxCharge);
                    chargePower = chargeTime / GameConfig.cannonMaxCharge;
                    updateTrajectoryArc(camera);
                    decayCameraEffects(0f, 0f, dt);
                }
            } else if (isCharging_) {
                // G released → FIRE
                fireCannonball(camera);
                isCharging_    = false;
                trajectoryArc.clear();
            } else {
                // Clean up any stale state
                isCharging_ = false;
                trajectoryArc.clear();
            }
        }
        lastG = gHeld;

        // ── HEALING (hold L) ──────────────────────────────────────────────────
        boolean lHeld = glfwGetKey(window, GLFW_KEY_L) == GLFW_PRESS;
        if (lHeld && healCooldownTimer <= 0f && !player.debugMode
                && player.health < player.maxHealth) {
            isHealing = true;
            float gain = GameConfig.healPerSecond * dt;
            player.health = Math.min(player.maxHealth, player.health + gain);
            blendOverlay(new Vector3f(0.15f, 0.85f, 0.35f), 0.18f, dt);
        } else {
            if (isHealing && !lHeld) {
                healCooldownTimer = GameConfig.healCooldown;
            }
            isHealing = false;
        }

        // ── STATE REWIND (hold Z) ─────────────────────────────────────────────
        boolean zHeld = glfwGetKey(window, GLFW_KEY_Z) == GLFW_PRESS;
        if (!isRewinding && zHeld && !lastZ
                && rewindCooldown <= 0f && !snapshots.isEmpty() && !isAnyAbilityActive()) {
            isRewinding = true;
            rewindAccum  = 0f;
        }
        if (isRewinding) {
            if (!zHeld) {
                // Z released — end rewind
                isRewinding    = false;
                rewindCooldown = GameConfig.rewindCooldown;
            } else {
                // Rewind is active — consume snapshots, show blue vignette, take control
                applyRewind(camera, dt);
                blendOverlay(new Vector3f(0.3f, 0.6f, 1.0f), 0.28f, dt);
                decayCameraEffects(0f, 0f, dt);
                lastZ = zHeld;
                return true; // Player.update() skips physics this frame
            }
        }
        lastZ = zHeld;

        // Decay effects when nothing is happening
        boolean quiet = !isDashing && !isCannonballing && !isCharging_
                && blinkFlashTimer <= 0f && !isPillaring && !isRewinding;
        if (quiet) {
            decayCameraEffects(0f, 0f, dt);
            blendOverlay(new Vector3f(0f, 0f, 0f), 0f, dt);
        }

        return false;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  DASH
    // ─────────────────────────────────────────────────────────────────────────

    private void startDash(long window, Camera camera) {
        isDashing    = true;
        dashTimer    = GameConfig.dashDuration;
        dashCooldown = GameConfig.dashCooldown;
        dashTrail.clear();

        // Direction: WASD input, fallback to look direction
        Vector3f fwd   = camera.getForward();
        Vector3f right = camera.getRight();
        float dx = 0, dz = 0;
        if (glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS) { dx += fwd.x; dz += fwd.z; }
        if (glfwGetKey(window, GLFW_KEY_S) == GLFW_PRESS) { dx -= fwd.x; dz -= fwd.z; }
        if (glfwGetKey(window, GLFW_KEY_D) == GLFW_PRESS) { dx += right.x; dz += right.z; }
        if (glfwGetKey(window, GLFW_KEY_A) == GLFW_PRESS) { dx -= right.x; dz -= right.z; }
        float len = (float)Math.sqrt(dx*dx + dz*dz);
        if (len < 0.01f) { dx = fwd.x; dz = fwd.z; len = 1f; }
        dashDirX = dx / len;
        dashDirZ = dz / len;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  CANNONBALL
    // ─────────────────────────────────────────────────────────────────────────

    private void fireCannonball(Camera camera) {
        isCannonballing = true;
        cannonCooldown  = GameConfig.cannonCooldown;

        float speed = GameConfig.cannonMinPower
                + chargePower * (GameConfig.cannonMaxPower - GameConfig.cannonMinPower);
        // Fire along the locked direction, not where player is currently looking
        Vector3f dir = new Vector3f(
                (float)(Math.cos(lockedPitch) * Math.cos(lockedYaw)),
                (float)(Math.sin(lockedPitch)),
                (float)(Math.cos(lockedPitch) * Math.sin(lockedYaw))
        ).normalize();

        cannonVelX = dir.x * speed;
        cannonVelZ = dir.z * speed;
        player.setVelocityY(dir.y * speed);
        player.highestY = player.position.y;

        // Forward pitch-roll: spin around the lateral (left-right) axis so the
        // player tumbles forward — ground sweeps into view from below, sky from
        // above. Rate scales with speed so a full charge spins visibly fast.
        cannonPitchRate = speed * 0.005f;  // ~0.7 rad/s at max power ≈ one rotation per 9s
        cannonPitchSpin = 0f;
    }

    private void updateTrajectoryArc(Camera camera) {
        trajectoryArc.clear();
        float speed = GameConfig.cannonMinPower
                + chargePower * (GameConfig.cannonMaxPower - GameConfig.cannonMinPower);
        // Use locked direction, not current camera look — arc stays stable while aiming
        Vector3f dir = new Vector3f(
                (float)(Math.cos(lockedPitch) * Math.cos(lockedYaw)),
                (float)(Math.sin(lockedPitch)),
                (float)(Math.cos(lockedPitch) * Math.sin(lockedYaw))
        ).normalize();
        float vx = dir.x * speed, vy = dir.y * speed, vz = dir.z * speed;
        float px = player.position.x, py = player.position.y + 0.9f, pz = player.position.z;
        float simDt = 0.075f;
        for (int i = 0; i < GameConfig.cannonArcPoints; i++) {
            trajectoryArc.add(new Vector3f(px, py, pz));
            vy -= GameConfig.GRAVITY * simDt;
            px += vx * simDt; py += vy * simDt; pz += vz * simDt;
            if (py < 0f) break;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  STATE REWIND
    // ─────────────────────────────────────────────────────────────────────────

    private void recordSnapshot(Camera camera, float dt) {
        if (isRewinding) return; // don't record during rewind
        snapshotTimer += dt;
        if (snapshotTimer < 1f / GameConfig.rewindSnapshotHz) return;
        snapshotTimer = 0f;

        snapshots.addLast(new float[]{
                player.position.x, player.position.y, player.position.z,
                player.getVelocityY(), camera.yaw, camera.pitch, player.health
        });
        int maxSnaps = (int)(GameConfig.rewindBufferSecs * GameConfig.rewindSnapshotHz);
        while (snapshots.size() > maxSnaps) snapshots.pollFirst();
    }

    private void applyRewind(Camera camera, float dt) {
        float rewindInterval = 1f / (GameConfig.rewindSnapshotHz * GameConfig.rewindSpeed);
        rewindAccum += dt;
        while (rewindAccum >= rewindInterval && !snapshots.isEmpty()) {
            rewindAccum -= rewindInterval;
            float[] snap = snapshots.pollLast();
            player.position.set(snap[0], snap[1], snap[2]);
            player.setVelocityY(snap[3]);
            camera.yaw    = snap[4];
            camera.pitch  = snap[5];
            player.highestY = snap[1]; // prevent fall-damage surprise when rewind ends
            player.health   = snap[6]; // restore health state
        }
        if (snapshots.isEmpty()) {
            isRewinding    = false;
            rewindCooldown = GameConfig.rewindCooldown;
        }
    }

    private void updateRewindTrail() {
        rewindTrail.clear();
        // Show the most-recent 28 snapshots as a ghost trail
        float[][] arr = snapshots.toArray(new float[0][]);
        int start = Math.max(0, arr.length - 28);
        for (int i = start; i < arr.length; i++) {
            rewindTrail.add(new Vector3f(arr[i][0], arr[i][1], arr[i][2]));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  BLINK
    // ─────────────────────────────────────────────────────────────────────────

    private void executeBlink(Camera camera, World world) {
        Vector3f dir  = camera.getLookDirection();
        float    step = 0.45f;
        float    rx   = camera.position.x, ry = camera.position.y, rz = camera.position.z;
        float    lastFX = rx, lastFY = ry - 1.6f, lastFZ = rz; // feet

        // No hard range cap: cast until we hit a solid block OR reach an unloaded
        // chunk. Unloaded chunks return AIR and would send the player into the void,
        // so we stop there — "if you can see it, you get there" is naturally bounded
        // by render distance. Loop cap (10000 steps * 0.45 = 4500 blocks) is a
        // safety ceiling that should never be hit in practice.
        for (int step_i = 0; step_i < 10000; step_i++) {
            rx += dir.x * step;
            ry += dir.y * step;
            rz += dir.z * step;
            int bx = (int)Math.floor(rx), by = (int)Math.floor(ry), bz = (int)Math.floor(rz);
            // Prevent teleporting to the sky or below the world floor.
            if (by < 0 || by >= Chunk.HEIGHT) break;
            // Stop at unloaded chunk boundary
            int cx = Math.floorDiv(bx, 16), cz = Math.floorDiv(bz, 16);
            if (world.getChunk(cx, 0, cz) == null) break;
            if (world.getBlock(bx, by, bz).isSolid()) break;
            lastFX = rx;
            lastFY = ry - 1.6f;
            lastFZ = rz;
        }

        blinkOrigin.set(player.position);
        blinkDest.set(lastFX, lastFY, lastFZ);
        player.position.set(lastFX, lastFY, lastFZ);
        player.highestY  = lastFY; // suppress fall-damage at destination
        justBlinked      = true;
        blinkFlashTimer  = GameConfig.blinkFlashDecay;
        blinkCooldown    = GameConfig.blinkCooldown;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** True when any ability is running (dash, cannonball charging/firing, rewind). */
    public boolean isAnyAbilityActive() {
        return isDashing || isCannonballing || isCharging_ || isRewinding|| isPillaring|| isHealing;
    }

    /**
     * Cancel cannonball mid-flight (e.g. player enters water).
     * Zeros out horizontal override velocity so normal water physics take over.
     */
    public void cancelCannonball() {
        if (isCannonballing || isCharging_) {
            isCannonballing = false;
            isCharging_     = false;
            cannonVelX      = 0f;
            cannonVelZ      = 0f;
            cannonPitchRate = 0f;
            trajectoryArc.clear();
        }
    }

    private void tickCooldowns(float dt) {
        if (dashCooldown   > 0f) dashCooldown   -= dt;
        if (cannonCooldown > 0f) cannonCooldown -= dt;
        if (rewindCooldown > 0f) rewindCooldown -= dt;
        if (blinkCooldown  > 0f) blinkCooldown  -= dt;
        if (pillarCooldownTimer > 0f) pillarCooldownTimer -= dt;
        if (healCooldownTimer > 0f) healCooldownTimer -= dt;
    }

    /**
     * Smooth-lerps roll and FOV boost toward target values.
     */
    private void decayCameraEffects(float targetRoll, float targetFov, float dt) {
        // Since cannonball now uses pitch-tumbles (cannonPitchSpin),
        // we can let roll always smoothly track targetRoll (including manual leans)
        smoothRoll     += (targetRoll - smoothRoll)     * Math.min(1f, GameConfig.rollLerpSpeed * dt);
        smoothFovBoost += (targetFov - smoothFovBoost) * Math.min(1f, GameConfig.cameraLerpSpeed * dt);
    }

    private void blendOverlay(Vector3f color, float strength, float dt) {
        overlayColor.lerp(color, Math.min(1f, 14f * dt));
        overlayStrength += (strength - overlayStrength) * Math.min(1f, 14f * dt);
    }

    private void decayAll(float dt) {
        decayCameraEffects(0f, 0f, dt);
        blendOverlay(new Vector3f(0f, 0f, 0f), 0f, dt);
    }

    /**
     * Builds and actively expands a solid stone column from the ground anchor
     * up to the player's current altitude, automatically filling gaps underneath.
     */
    private void buildPillarFrame(World world) {
        java.util.Set<Chunk> dirtyChunks = new java.util.HashSet<>();

        int pY = (int) Math.floor(player.position.y);
        pY = Math.min(Chunk.HEIGHT - 1, pY);

        // Max radius is determined by the distance from the player to the original anchor
        float maxDepth = Math.max(0f, (float) player.position.y - pillarStartY);
        float maxR = 1.5f + maxDepth * GameConfig.pillarTaper;
        int R = (int) Math.ceil(maxR);

        for (int dx = -R; dx <= R; dx++) {
            for (int dz = -R; dz <= R; dz++) {
                float dist = (float) Math.sqrt(dx * dx + dz * dz);

                // Outside the maximum cone bound
                if (dist > maxR) continue;

                int bx = (int) Math.floor(pillarCenterX) + dx;
                int bz = (int) Math.floor(pillarCenterZ) + dz;

                // Calculate where the surface of the pillar should be at this horizontal distance
                float depthAtDist = 0f;
                if (dist > 1.5f) {
                    depthAtDist = (dist - 1.5f) / GameConfig.pillarTaper;
                }

                int pillarSurfaceY = (int) Math.floor(player.position.y - depthAtDist);
                pillarSurfaceY = Math.min(pillarSurfaceY, pY); // Cap at player's current level

                // Fill downwards from the pillar's surface until we hit solid ground
                // This ensures uneven terrain and gaps are perfectly filled
                int localY = pillarSurfaceY;

                // Stop dropping blocks infinitely if we launch over an empty ravine void
                int lowestAllowedY = Math.max(0, (int) pillarStartY - (int) GameConfig.pillarMaxHeight);

                while (localY >= lowestAllowedY && !world.getBlock(bx, localY, bz).isSolid()) {
                    world.setBlock(bx, localY, bz, Block.STONE); // SOLID STONE

                    Chunk c = world.getChunk(Math.floorDiv(bx, Chunk.SIZE), Math.floorDiv(localY, Chunk.HEIGHT), Math.floorDiv(bz, Chunk.SIZE));
                    if (c != null) dirtyChunks.add(c);
                    localY--;
                }
            }
        }

        // Rebuild meshes on the same frame for immediate collision and visuals
        for (Chunk c : dirtyChunks) {
            world.buildChunkMeshes(c);
        }
    }
}