package com.leaf.game.entity;

import com.leaf.game.core.GameConfig;
import com.leaf.game.util.Camera;
import com.leaf.game.world.World;
import org.joml.Vector3f;

import static org.lwjgl.glfw.GLFW.*;

/**
 * GrabController — O key: grab nearest crosshair enemy → lift overhead → slam into ground.
 *
 * The grabbed enemy is held for grabGroundLiftTime seconds (floats in front,
 * pulses orange) then released straight down at grabGroundSlamSpeed.
 * Floor impact → crater + damage + screen shake + orange flash.
 */
public class GrabController {

    // ── Phase ─────────────────────────────────────────────────────────────────
    private enum Phase { NONE, LIFTING }

    // ── State ─────────────────────────────────────────────────────────────────
    private Phase  phase        = Phase.NONE;
    private Enemy  grabbed      = null;
    private float  liftTimer    = 0f;
    private float  liftStartY   = 0f;

    /** After an impact, Window reads and applies this shake. Reset each frame. */
    public float shakeRequest      = 0f;
    /**
     * Set to 0.18f the frame a throw/slam is released.
     * Window reads this to trigger a brief launch-recoil shake and orange flash.
     * Decays each frame.
     */
    public float throwFlash        = 0f;
    /** True while the enemy is in the LIFTING phase (ground slam wind-up). */
    public boolean isLifting       = false;

    private float   cooldown    = 0f;
    private boolean lastO       = false;

    private final Player player;
    private EnemyManager enemyManager;

    // ─────────────────────────────────────────────────────────────────────────
    public GrabController(Player player) { this.player = player; }

    public void setEnemyManager(EnemyManager em) { this.enemyManager = em; }

    // ─────────────────────────────────────────────────────────────────────────
    //  Main tick
    // ─────────────────────────────────────────────────────────────────────────

    public void tick(long window, Camera camera, World world, float dt) {
        shakeRequest = 0f;
        if (throwFlash > 0f) throwFlash = Math.max(0f, throwFlash - dt * 4f);
        if (cooldown > 0f) cooldown -= dt;

        boolean oHeld = glfwGetKey(window, GLFW_KEY_O) == GLFW_PRESS;

        switch (phase) {

            // ── IDLE — listen for grab trigger ─────────────────────────────────
            case NONE -> {
                isLifting = false;
                if (oHeld && !lastO
                        && !player.debugMode
                        && cooldown <= 0f
                        && enemyManager != null
                        && player.mana >= GameConfig.manaGrab) {

                    Enemy target = findGrabbable(camera);
                    if (target != null) {
                        player.mana -= GameConfig.manaGrab;
                        grabbed = target;
                        target.isGrabbed = true;
                        // ── GROUND SLAM — lift then smash (always) ────────────
                        phase      = Phase.LIFTING;
                        liftTimer  = GameConfig.grabGroundLiftTime;
                        liftStartY = target.position.y;
                    }
                }
            }

            // ── LIFTING — rise enemy up then slam ──────────────────────────────
            case LIFTING -> {
                isLifting = true;
                if (grabbed == null || !grabbed.alive) {
                    // Target died while being lifted — abort gracefully
                    isLifting = false;
                    cancelGrab();
                    break;
                }

                liftTimer -= dt;
                float prog = 1f - Math.max(0f, liftTimer / GameConfig.grabGroundLiftTime);

                // Smoothly lift the enemy in front of the player
                Vector3f fwd = camera.getForward();
                grabbed.position.x = player.position.x + fwd.x * 1.5f;
                grabbed.position.z = player.position.z + fwd.z * 1.5f;
                grabbed.position.y = liftStartY + prog * GameConfig.grabGroundLiftHeight;

                // Keep the lifted enemy pulsing bright orange so the player sees it
                grabbed.hitFlashTimer = Math.max(grabbed.hitFlashTimer, 0.20f);

                if (liftTimer <= 0f) {
                    // ── RELEASE STRAIGHT DOWN ─────────────────────────────────
                    grabbed.isGrabbed   = false;
                    grabbed.isThrown    = true;
                    grabbed.thrownVelX  = 0f;
                    grabbed.thrownVelY  = -GameConfig.grabGroundSlamSpeed;
                    grabbed.thrownVelZ  = 0f;
                    grabbed.grabImpactIsGround = true;   // pre-mark as ground slam
                    throwFlash = 0.22f;   // slam release flash

                    isLifting = false;
                    phase   = Phase.NONE;
                    cooldown = GameConfig.grabCooldown;
                    grabbed = null;
                }
            }
        }

        // ── Poll for crater impacts from previously thrown enemies ─────────────
        // We trigger shake here so Window's loop sees the request immediately.
        if (enemyManager != null) {
            for (Enemy e : enemyManager.getEnemies()) {
                if (e.pendingGrabImpact) {
                    shakeRequest = Math.max(shakeRequest, GameConfig.grabShakeDuration);
                    // (Window reads pendingGrabImpact directly for crater creation)
                }
            }
        }

        lastO = oHeld;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Wall throw — release the enemy in the camera's look direction
    // ─────────────────────────────────────────────────────────────────────────

    private void releaseThrow(Camera camera) {
        Vector3f look = camera.getLookDirection();
        float s = GameConfig.grabThrowSpeed;
        grabbed.isGrabbed = false;
        grabbed.isThrown  = true;
        grabbed.thrownVelX = look.x * s;
        grabbed.thrownVelY = look.y * s + 6f;   // slight upward boost so they arc nicely
        grabbed.thrownVelZ = look.z * s;
        grabbed.grabImpactIsGround = false;       // expect wall hit
        throwFlash = 0.18f;   // launch recoil flash

        phase    = Phase.NONE;
        cooldown = GameConfig.grabCooldown;
        grabbed  = null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Find the closest alive enemy within grabRange that is roughly in the crosshair. */
    private Enemy findGrabbable(Camera camera) {
        Vector3f look   = camera.getLookDirection();
        Vector3f camPos = camera.position;
        float    bestDot = 0.55f; // ~57 degree cone — generous for close range
        Enemy    best    = null;

        for (Enemy e : enemyManager.getEnemies()) {
            if (!e.alive || e.isGrabbed || e.isThrown) continue;
            Vector3f toEnemy = new Vector3f(e.getCentre()).sub(camPos);
            float    dist    = toEnemy.length();
            if (dist > GameConfig.grabRange) continue;
            float dot = new Vector3f(toEnemy).normalize().dot(look);
            if (dot > bestDot) {
                bestDot = dot;
                best    = e;
            }
        }
        return best;
    }

    private void cancelGrab() {
        if (grabbed != null) grabbed.isGrabbed = false;
        grabbed  = null;
        phase    = Phase.NONE;
        cooldown = GameConfig.grabCooldown * 0.5f;
    }

    // ── Public accessors ──────────────────────────────────────────────────────

    public boolean isActive()     { return phase != Phase.NONE; }
    public float   getCooldown()  { return cooldown; }
    public float   getCooldownFrac() {
        return cooldown <= 0f ? 1f : 1f - cooldown / GameConfig.grabCooldown;
    }
}
