package com.leaf.game.entity;

import com.leaf.game.core.GameConfig;
import com.leaf.game.util.Camera;
import com.leaf.game.world.Block;
import com.leaf.game.world.World;
import org.joml.Vector3f;

import static org.lwjgl.glfw.GLFW.*;

public class FlightController {

    public enum FlightMode { SKIM, SOAR, GRAPPLE }

    private final Player player;
    private FlightMode mode = FlightMode.SOAR;
    private final Vector3f velocity = new Vector3f();

    private float smoothRoll     = 0f;
    private float smoothFovBoost = 0f;
    public  float getCameraRoll()  { return smoothRoll; }
    public  float getFovBoost()    { return smoothFovBoost; }

    private float strafeSign = 0f;

    // ── GRAPPLE state ─────────────────────────────────────────────────────────
    private final Vector3f hookPoint  = new Vector3f();
    private float           ropeLength = 0f;
    private boolean         hooked    = false;
    private float           cooldownTimer = 0f;
    private float           hookTime  = 0f;

    private World world_ref = null;
    private boolean lastVHeld = false;

    private final Vector3f launchVelocity = new Vector3f();
    public  Vector3f getLaunchVelocity()  { return new Vector3f(launchVelocity); }
    /** Current flight velocity — use for wind calculations to avoid stutter when terrain blocks movement. */
    public  float    getFlightSpeed()     { return velocity.length(); }

    public FlightController(Player player) {
        this.player = player;
    }

    public void decayEffects(float dt) {
        float lr = GameConfig.rollLerpSpeed * dt;
        float lf = GameConfig.cameraLerpSpeed * dt;
        smoothRoll     += (0f - smoothRoll)     * Math.min(1f, lr);
        smoothFovBoost += (0f - smoothFovBoost) * Math.min(1f, lf);
        if (cooldownTimer > 0) cooldownTimer -= dt;
    }

    public void onFlightDeactivated() {
        if (hooked) {
            hooked = false;
            cooldownTimer = GameConfig.grappleCooldown;
        }
        launchVelocity.set(velocity);
    }

    public void update(long window, Camera camera, World world, float dt) {
        this.world_ref = world;
        if (cooldownTimer > 0) cooldownTimer -= dt;

        handleModeSwitch(window);
        strafeSign = 0f;

        switch (mode) {
            case SKIM    -> updateSkim(window, camera, world, dt);
            case SOAR    -> updateSoar(window, camera, world, dt);
            case GRAPPLE -> updateGrapple(window, camera, world, dt);
        }

        float targetRoll     = computeTargetRoll();
        float targetFovBoost = computeTargetFovBoost();

        float lr = GameConfig.rollLerpSpeed  * dt;
        float lf = GameConfig.cameraLerpSpeed * dt;
        smoothRoll     += (targetRoll     - smoothRoll)     * Math.min(1f, lr);
        smoothFovBoost += (targetFovBoost - smoothFovBoost) * Math.min(1f, lf);
    }

    private void handleModeSwitch(long window) {
        boolean vHeld = glfwGetKey(window, GLFW_KEY_V) == GLFW_PRESS;
        if (vHeld && !lastVHeld) cycleMode();
        lastVHeld = vHeld;
    }

    private void cycleMode() {
        if (mode == FlightMode.GRAPPLE && hooked) {
            hooked = false;
            cooldownTimer = GameConfig.grappleCooldown;
        }
        mode = switch (mode) {
            case SKIM    -> FlightMode.SOAR;
            case SOAR    -> FlightMode.GRAPPLE;
            case GRAPPLE -> FlightMode.SKIM;
        };
    }

    public FlightMode getMode() { return mode; }

    // ─────────────────────────────────────────────────────────────────────────
    //  SKIM
    // ─────────────────────────────────────────────────────────────────────────
    private void updateSkim(long window, Camera camera, World world, float dt) {
        Vector3f fwd   = camera.getForward();
        Vector3f right = camera.getRight();

        float speed = GameConfig.skimSpeed;
        float dx = 0f, dz = 0f;
        boolean wHeld = glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS;
        boolean sHeld = glfwGetKey(window, GLFW_KEY_S) == GLFW_PRESS;
        boolean aHeld = glfwGetKey(window, GLFW_KEY_A) == GLFW_PRESS;
        boolean dHeld = glfwGetKey(window, GLFW_KEY_D) == GLFW_PRESS;

        if (wHeld) { dx += fwd.x; dz += fwd.z; }
        if (sHeld) { dx -= fwd.x; dz -= fwd.z; }
        if (dHeld) { dx += right.x; dz += right.z; strafeSign =  1f; }
        if (aHeld) { dx -= right.x; dz -= right.z; strafeSign = -1f; }

        float len2 = dx*dx + dz*dz;
        if (len2 > 1f) { float inv = 1f / (float)Math.sqrt(len2); dx *= inv; dz *= inv; }

        player.position.x += dx * speed * dt;
        player.position.z += dz * speed * dt;
        velocity.set(dx * speed, 0, dz * speed);

        int groundY = findGroundBelow(world, player.position.x, player.position.y, player.position.z, 80);
        float horizSpeed    = (float)Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        float speedFraction = Math.min(1f, horizSpeed / Math.max(1f, GameConfig.skimSpeed));
        float dynamicHeight = GameConfig.skimHeightTarget * (0.5f + 0.7f * speedFraction);
        float targetY       = groundY + dynamicHeight;
        float yErr          = targetY - player.position.y;

        float yGain = (yErr > 0) ? 10f : 3.5f;
        player.position.y += yErr * yGain * dt;
        // Keep player at least (dynamicHeight * 0.5) above the surface so they
        // never clip into the ground or water.
        player.position.y = Math.max(groundY + dynamicHeight * 0.5f, player.position.y);

        Vector3f horizPos = new Vector3f(player.position);
        if (collidesAt(world, horizPos)) {
            Vector3f tryX = new Vector3f(player.position.x - dx * speed * dt, player.position.y, player.position.z);
            if (!collidesAt(world, tryX)) { player.position.x = tryX.x; }
            Vector3f tryZ = new Vector3f(player.position.x, player.position.y, player.position.z - dz * speed * dt);
            if (!collidesAt(world, tryZ)) { player.position.z = tryZ.z; }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  SOAR
    // ─────────────────────────────────────────────────────────────────────────
    private void updateSoar(long window, Camera camera, World world, float dt) {
        Vector3f lookDir  = camera.getLookDirection();
        Vector3f rightDir = camera.getRight();
        Vector3f upDir    = new Vector3f(0f, 1f, 0f);

        float speed = GameConfig.soarSpeed;
        float accel = GameConfig.soarAccel;

        Vector3f desired = new Vector3f();
        boolean wHeld    = glfwGetKey(window, GLFW_KEY_W)          == GLFW_PRESS;
        boolean sHeld    = glfwGetKey(window, GLFW_KEY_S)          == GLFW_PRESS;
        boolean aHeld    = glfwGetKey(window, GLFW_KEY_A)          == GLFW_PRESS;
        boolean dHeld    = glfwGetKey(window, GLFW_KEY_D)          == GLFW_PRESS;
        boolean upHeld   = glfwGetKey(window, GLFW_KEY_SPACE)      == GLFW_PRESS;
        boolean downHeld = glfwGetKey(window, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS;

        if (wHeld) desired.add(lookDir);
        if (sHeld) desired.sub(lookDir);
        if (dHeld) { desired.add(rightDir); strafeSign =  1f; }
        if (aHeld) { desired.sub(rightDir); strafeSign = -1f; }
        if (upHeld)   desired.add(upDir);
        if (downHeld) desired.sub(upDir);

        boolean anyInput = wHeld || sHeld || aHeld || dHeld || upHeld || downHeld;

        if (desired.lengthSquared() > 0.001f) desired.normalize().mul(speed);

        velocity.x += (desired.x - velocity.x) * accel * dt;
        velocity.y += (desired.y - velocity.y) * accel * dt;
        velocity.z += (desired.z - velocity.z) * accel * dt;

        if (!upHeld && !downHeld) {
            velocity.y -= GameConfig.soarGravity * dt;
        }

        if (!anyInput) {
            float drag = (float)Math.pow(GameConfig.soarAirDrag, dt * 60.0);
            velocity.mul(drag);
        }

        Vector3f prevPos = new Vector3f(player.position);
        Vector3f step    = new Vector3f(velocity).mul(dt);
        player.position.add(step);

        if (collidesAt(world, player.position)) {
            player.position.set(prevPos);
            Vector3f tryX = new Vector3f(prevPos.x + step.x, prevPos.y, prevPos.z);
            Vector3f tryY = new Vector3f(prevPos.x, prevPos.y + step.y, prevPos.z);
            Vector3f tryZ = new Vector3f(prevPos.x, prevPos.y, prevPos.z + step.z);

            if (!collidesAt(world, tryX)) { player.position.x = tryX.x; } else { velocity.x *= -0.1f; }
            if (!collidesAt(world, tryY)) { player.position.y = tryY.y; } else { velocity.y *= -0.1f; }
            if (!collidesAt(world, tryZ)) { player.position.z = tryZ.z; } else { velocity.z *= -0.1f; }
        }
    }

    private boolean collidesAt(World world, Vector3f pos) {
        final float hw = 0.29f;
        final float h  = 1.79f;
        int x0 = (int)Math.floor(pos.x - hw), x1 = (int)Math.floor(pos.x + hw);
        int y0 = (int)Math.floor(pos.y + 0.01f), y1 = (int)Math.floor(pos.y + h);
        int z0 = (int)Math.floor(pos.z - hw), z1 = (int)Math.floor(pos.z + hw);
        for (int x = x0; x <= x1; x++)
            for (int y = y0; y <= y1; y++)
                for (int z = z0; z <= z1; z++)
                    if (world.getBlock(x, y, z).isSolid()) return true;
        return false;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  GRAPPLE — ARCADE ZIP-LINE & NORMAL GROUND PHYSICS
    // ─────────────────────────────────────────────────────────────────────────
    private void updateGrapple(long window, Camera camera, World world, float dt) {

        // STRICT Grapple Input: Only Right-Click or F. Absolutely NO SPACE!
        boolean grappleInput = glfwGetMouseButton(window, GLFW_MOUSE_BUTTON_RIGHT) == GLFW_PRESS
                || glfwGetKey(window, GLFW_KEY_F) == GLFW_PRESS;

        Vector3f groundCheck = new Vector3f(player.position.x, player.position.y - 0.05f, player.position.z);
        boolean onGround = collidesAt(world, groundCheck);

        // ── Unhooked: Normal Walking / Air Strafing ──────────────────────────
        if (!hooked) {
            velocity.y -= GameConfig.GRAVITY * dt;
            velocity.y = Math.max(-50f, velocity.y);

            if (onGround) {
                velocity.y = Math.max(0, velocity.y);

                // Normal WASD Ground Movement
                Vector3f fwd = camera.getForward();
                Vector3f right = camera.getRight();
                Vector3f move = new Vector3f();

                if (glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS) move.add(fwd);
                if (glfwGetKey(window, GLFW_KEY_S) == GLFW_PRESS) move.sub(fwd);
                if (glfwGetKey(window, GLFW_KEY_A) == GLFW_PRESS) { move.sub(right); strafeSign = -1f; }
                if (glfwGetKey(window, GLFW_KEY_D) == GLFW_PRESS) { move.add(right); strafeSign = 1f; }

                if (move.lengthSquared() > 0.001f) {
                    float speed = glfwGetKey(window, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS ? GameConfig.SPRINT_SPEED : GameConfig.WALK_SPEED;
                    move.normalize().mul(speed);
                    velocity.x = move.x;
                    velocity.z = move.z;
                } else {
                    velocity.x = 0;
                    velocity.z = 0;
                }

                // ── JUMP: SPACE IS 100% DEDICATED TO JUMPING ──
                if (glfwGetKey(window, GLFW_KEY_SPACE) == GLFW_PRESS) {
                    velocity.y = GameConfig.JUMP_FORCE;
                }
            } else {
                // Mid-air control
                Vector3f fwd = camera.getForward();
                Vector3f right = camera.getRight();
                Vector3f move = new Vector3f();

                if (glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS) move.add(fwd);
                if (glfwGetKey(window, GLFW_KEY_S) == GLFW_PRESS) move.sub(fwd);
                if (glfwGetKey(window, GLFW_KEY_A) == GLFW_PRESS) { move.sub(right); strafeSign = -1f; }
                if (glfwGetKey(window, GLFW_KEY_D) == GLFW_PRESS) { move.add(right); strafeSign = 1f; }

                if (move.lengthSquared() > 0.001f) {
                    move.normalize().mul(12.0f * dt);
                    velocity.x += move.x;
                    velocity.z += move.z;
                }

                float drag = (float)Math.pow(0.98f, dt * 60f);
                velocity.x *= drag;
                velocity.z *= drag;
            }

            // Move & Collide
            int substeps = (int)Math.ceil(velocity.length() * dt * 2.0f);
            substeps = Math.max(1, substeps);
            float stepDt = dt / substeps;

            for (int i = 0; i < substeps; i++) {
                Vector3f prevPos = new Vector3f(player.position);
                Vector3f step    = new Vector3f(velocity).mul(stepDt);
                player.position.add(step);

                if (collidesAt(world, player.position)) {
                    player.position.set(prevPos);
                    boolean moved = false;

                    Vector3f tryX = new Vector3f(prevPos.x + step.x, prevPos.y, prevPos.z);
                    if (!collidesAt(world, tryX)) { player.position.x = tryX.x; moved = true; } else { velocity.x = 0; }

                    Vector3f tryZ = new Vector3f(player.position.x, prevPos.y, prevPos.z + step.z);
                    if (!collidesAt(world, tryZ)) { player.position.z = tryZ.z; moved = true; } else { velocity.z = 0; }

                    Vector3f tryY = new Vector3f(player.position.x, prevPos.y + step.y, player.position.z);
                    if (!collidesAt(world, tryY)) { player.position.y = tryY.y; moved = true; } else { velocity.y = 0; }

                    if (!moved && step.lengthSquared() > 0) {
                        player.position.y += 0.05f;
                    }
                }
            }

            if (grappleInput && cooldownTimer <= 0f) {
                fireHook(camera, world);
            }

        } else {
            // ── Hooked: Direct Zip-line Pull with Hang-Time & Acceleration ────────
            if (!grappleInput) {
                releaseHook(); // Instantly lets go when you release the button
                return;
            }

            hookTime += dt;
            Vector3f toHook = new Vector3f(hookPoint).sub(player.position);
            float dist = toHook.length();

            // Reached destination!
            if (dist < 1.8f) {
                releaseHook();
                return;
            }

            Vector3f pullDir = new Vector3f(toHook).normalize();

            // ── PHASE 1: "Hang Time" (First 0.3 seconds) ──
            if (hookTime < 0.3f) {
                // Pull horizontally very gently, giving you time to look around
                velocity.x = pullDir.x * 4.0f;
                velocity.z = pullDir.z * 4.0f;

                // Lighten gravity so you float upward smoothly to gain altitude
                velocity.y -= (GameConfig.GRAVITY * 0.4f) * dt;
                velocity.y = Math.max(velocity.y, 2.0f); // Prevents falling, keeps you hovering
            }
            // ── PHASE 2: "The Zip" (Rapid Slingshot) ──
            else {
                // Smoothly ramp up speed over 0.35 seconds
                float t = Math.min(1.0f, (hookTime - 0.3f) / 0.35f);
                float currentSpeed = 4.0f + (t * t * 75.0f); // Max speed is huge (79 blocks/sec)

                velocity.x = pullDir.x * currentSpeed;
                velocity.y = pullDir.y * currentSpeed;
                velocity.z = pullDir.z * currentSpeed;
            }

            // Move & Collide
            int substeps = (int)Math.ceil(velocity.length() * dt * 2.0f);
            substeps = Math.max(1, substeps);
            float stepDt = dt / substeps;

            for (int i = 0; i < substeps; i++) {
                Vector3f prevPos = new Vector3f(player.position);
                Vector3f step    = new Vector3f(velocity).mul(stepDt);
                player.position.add(step);

                if (collidesAt(world_ref, player.position)) {
                    player.position.set(prevPos);
                    Vector3f tryX = new Vector3f(prevPos.x + step.x, prevPos.y, prevPos.z);
                    Vector3f tryY = new Vector3f(prevPos.x, prevPos.y + step.y, prevPos.z);
                    Vector3f tryZ = new Vector3f(prevPos.x, prevPos.y, prevPos.z + step.z);

                    if (!collidesAt(world_ref, tryX)) { player.position.x = tryX.x; } else { velocity.x = 0; }
                    if (!collidesAt(world_ref, tryY)) { player.position.y = tryY.y; } else { velocity.y = 0; }
                    if (!collidesAt(world_ref, tryZ)) { player.position.z = tryZ.z; } else { velocity.z = 0; }
                }
            }
        }
    }

    private boolean fireHook(Camera camera, World world) {
        Vector3f target = getAimTarget(camera, world);
        if (target != null) {
            hookPoint.set(target);
            hooked = true;
            hookTime = 0f; // Reset the phase timer!

            // Give an upward pop so the player rises toward the hook point.
            velocity.y = Math.max(velocity.y, 14.0f);

            // Give a tiny bump in the direction of the hook so it feels immediately responsive
            Vector3f toHook = new Vector3f(hookPoint).sub(player.position).normalize();
            velocity.x += toHook.x * 5.0f;
            velocity.z += toHook.z * 5.0f;

            return true;
        }
        cooldownTimer = 0.15f;
        return false;
    }

    private void releaseHook() {
        hooked        = false;
        cooldownTimer = GameConfig.grappleCooldown;
        hookTime      = 0f;
        launchVelocity.set(velocity);
    }

    public Vector3f getAimTarget(Camera camera, World world) {
        Vector3f dir  = camera.getLookDirection();
        float    step = 0.4f;
        float    max  = GameConfig.grappleRange;

        float rx = camera.position.x, ry = camera.position.y, rz = camera.position.z;

        for (float dist = 0f; dist < max; dist += step) {
            rx += dir.x * step;
            ry += dir.y * step;
            rz += dir.z * step;

            int bx = (int)Math.floor(rx);
            int by = (int)Math.floor(ry);
            int bz = (int)Math.floor(rz);

            if (world.getBlock(bx, by, bz).isSolid()) {
                return new Vector3f(rx, ry, rz);
            }
        }
        return null;
    }

    private float computeTargetRoll() {
        if (mode == FlightMode.GRAPPLE) {
            if (hooked) {
                float maxRad = (float)Math.toRadians(GameConfig.rollMaxAngle);
                float lat = velocity.x;
                return Math.max(-maxRad, Math.min(maxRad, -lat * 0.003f)); // Subtler roll, only when swinging
            }
            return 0f; // No disorienting roll while unhooked
        }

        if (mode == FlightMode.SOAR) {
            float maxRad = (float)Math.toRadians(GameConfig.soarRollMaxAngle);
            return -strafeSign * maxRad;
        }

        float maxRad = (float)Math.toRadians(GameConfig.rollMaxAngle);
        return -strafeSign * maxRad;
    }

    private float computeTargetFovBoost() {
        float speed    = velocity.length();
        float refSpeed = (mode == FlightMode.SOAR) ? GameConfig.soarSpeed : GameConfig.skimSpeed;
        float t        = Math.min(1f, speed / Math.max(1f, refSpeed));
        return t * GameConfig.flightFovMax;
    }

    private int findGroundBelow(World world, float x, float y, float z, int maxDrop) {
        int bx     = (int)Math.floor(x);
        int bz     = (int)Math.floor(z);
        int startY = (int)Math.floor(y);
        // Clamp scan floor to y=1 so skim never targets below the world floor.
        // Also limit maxDrop to 30 — prevents diving down into open cave mouths.
        int floorY = Math.max(1, startY - Math.min(maxDrop, 30));

        for (int by = startY; by >= floorY; by--) {
            Block b = world.getBlock(bx, by, bz);
            // Treat the water surface as ground so skim glides over water, not
            // down to the seabed.
            if (b.isSolid() || b.isLiquid()) return by + 1;
        }
        return floorY;
    }

    public boolean isHooked() { return hooked; }
    public Vector3f getHookPoint() { return hookPoint; }
}