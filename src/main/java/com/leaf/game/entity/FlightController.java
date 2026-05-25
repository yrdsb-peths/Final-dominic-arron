package com.leaf.game.entity;

import com.leaf.game.core.GameConfig;
import com.leaf.game.util.Camera;
import com.leaf.game.world.Block;
import com.leaf.game.world.World;
import org.joml.Vector3f;

import static org.lwjgl.glfw.GLFW.*;

/**
 * FlightController — owns all airborne locomotion logic.
 *
 * Three switchable modes (press V mid-air to cycle):
 *
 *   SKIM    — ultra-low altitude, hugs terrain contours, extreme horizontal
 *             speed, camera pitches slightly down, FOV widens proportionally.
 *
 *   SOAR    — full 3D directional free flight, camera rolls smoothly into
 *             turns, FOV at peak width. Replaces the old flat debugMode flight.
 *
 *   GRAPPLE — fires a hook toward the first solid surface in look direction,
 *             player swings on a physics pendulum. Pressing SPACE while hooked
 *             releases and launches the player along current momentum. Camera
 *             lags behind the velocity direction then snaps — AOT gear feel.
 *
 * ── Camera effects (all modes) ───────────────────────────────────────────────
 *   • Roll:    Exposed via getCameraRoll() as radians. Window.java applies it
 *              as a SEPARATE matrix rotation AFTER getViewMatrix(), never inside
 *              it. This keeps the view matrix, frustum extraction, and up-vector
 *              fully clean and reversible.
 *   • FOV:     Exposed via getFovBoost(). Window.java adds this to GameConfig.fov
 *              before building the projection matrix.
 *   • Lerp:    Both effects smooth via lerp each frame so there are no snaps.
 *
 * ── Integration ──────────────────────────────────────────────────────────────
 *   Player holds one FlightController instance (public field for Window access).
 *   When debugMode is true, Player.update() delegates here instead of its own
 *   fly-mode block. Player calls decayEffects() every frame so roll/fov bleed
 *   back to neutral even when flight is inactive.
 */
public class FlightController {

    // ── Flight mode enum ─────────────────────────────────────────────────────
    public enum FlightMode { SKIM, SOAR, GRAPPLE }

    // ── Back-reference (set in constructor) ──────────────────────────────────
    private final Player player;

    // ── Active mode ───────────────────────────────────────────────────────────
    private FlightMode mode = FlightMode.SOAR;

    // ── Physics velocity (used mainly by SOAR / GRAPPLE) ─────────────────────
    // SKIM overrides Y each frame to hug terrain.
    private final Vector3f velocity = new Vector3f();

    // ── Camera effects (smoothed, read by Window.java) ───────────────────────
    private float smoothRoll     = 0f;   // current smoothed roll, in radians
    private float smoothFovBoost = 0f;   // current smoothed FOV boost, in degrees
    public  float getCameraRoll()  { return smoothRoll; }
    public  float getFovBoost()    { return smoothFovBoost; }

    // Lateral strafe sign (-1 = A, 0 = none, 1 = D) — set each mode-update frame.
    private float strafeSign = 0f;

    // ── GRAPPLE state ─────────────────────────────────────────────────────────
    private final Vector3f hookPoint  = new Vector3f();
    private float           ropeLength = 0f;
    private boolean         hooked    = false;
    private float           cooldownTimer = 0f;

    // Stored per-update so tickPendulum can do collision checks
    private World world_ref = null;

    // SPACE edge detector for grapple fire/release
    private boolean lastSpaceHeld = false;

    // ── Mode-switch V key edge detector ──────────────────────────────────────
    private boolean lastVHeld = false;

    // ── Launch velocity handed back to Player on flight deactivation ─────────
    // Window reads this once after flight ends to give Player its momentum.
    private final Vector3f launchVelocity = new Vector3f();
    public  Vector3f getLaunchVelocity()  { return new Vector3f(launchVelocity); }

    // ── GRAPPLE camera lag ────────────────────────────────────────────────────
    // Smooth yaw/pitch offsets that chase the velocity direction with a lag.
    // Applied in Window.java by rotating camera yaw/pitch toward these targets.
    private float grappleLagYaw   = 0f;
    private float grappleLagPitch = 0f;
    public  float getGrappleLagYaw()   { return grappleLagYaw; }
    public  float getGrappleLagPitch() { return grappleLagPitch; }

    // ─────────────────────────────────────────────────────────────────────────
    //  Constructor
    // ─────────────────────────────────────────────────────────────────────────

    public FlightController(Player player) {
        this.player = player;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Called every frame even when flight is OFF — decays effects to neutral
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Smoothly returns roll and FOV boost to zero when not flying.
     * Player.update() calls this regardless of debugMode state.
     */
    public void decayEffects(float dt) {
        float lr = GameConfig.rollLerpSpeed * dt;
        float lf = GameConfig.cameraLerpSpeed * dt;
        smoothRoll     += (0f - smoothRoll)     * Math.min(1f, lr);
        smoothFovBoost += (0f - smoothFovBoost) * Math.min(1f, lf);
        grappleLagYaw   = 0f;
        grappleLagPitch = 0f;
        // Cooldown still ticks down even when flight is inactive
        if (cooldownTimer > 0) cooldownTimer -= dt;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Called when flight mode is deactivated (debugMode → false)
    // ─────────────────────────────────────────────────────────────────────────

    public void onFlightDeactivated() {
        if (hooked) {
            // Release grapple and preserve momentum
            hooked = false;
            cooldownTimer = GameConfig.grappleCooldown;
        }
        launchVelocity.set(velocity);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Main update — called by Player.update() when debugMode is true
    // ─────────────────────────────────────────────────────────────────────────

    public void update(long window, Camera camera, World world, float dt) {
        // Store world reference for sub-calls that need it (e.g. pendulum collision)
        this.world_ref = world;

        // Cooldown always ticks
        if (cooldownTimer > 0) cooldownTimer -= dt;

        // Mode switching (V key)
        handleModeSwitch(window);

        // Reset strafe sign; mode-updates will set it
        strafeSign = 0f;

        // Dispatch to active mode
        switch (mode) {
            case SKIM    -> updateSkim(window, camera, world, dt);
            case SOAR    -> updateSoar(window, camera, world, dt);
            case GRAPPLE -> updateGrapple(window, camera, world, dt);
        }

        // ── Smooth camera effects ─────────────────────────────────────────────
        float targetRoll     = computeTargetRoll();
        float targetFovBoost = computeTargetFovBoost();

        float lr = GameConfig.rollLerpSpeed  * dt;
        float lf = GameConfig.cameraLerpSpeed * dt;
        smoothRoll     += (targetRoll     - smoothRoll)     * Math.min(1f, lr);
        smoothFovBoost += (targetFovBoost - smoothFovBoost) * Math.min(1f, lf);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Mode switching
    // ─────────────────────────────────────────────────────────────────────────

    private void handleModeSwitch(long window) {
        boolean vHeld = glfwGetKey(window, GLFW_KEY_V) == GLFW_PRESS;
        if (vHeld && !lastVHeld) cycleMode();
        lastVHeld = vHeld;
    }

    private void cycleMode() {
        if (mode == FlightMode.GRAPPLE && hooked) {
            // Releasing grapple on mode-switch preserves momentum cleanly
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
    //  SKIM — ultra-low altitude burst flight, hugs terrain
    // ─────────────────────────────────────────────────────────────────────────

    private void updateSkim(long window, Camera camera, World world, float dt) {
        Vector3f fwd   = camera.getForward();   // yaw-only, no pitch
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

        // Normalise diagonal input
        float len2 = dx*dx + dz*dz;
        if (len2 > 1f) { float inv = 1f / (float)Math.sqrt(len2); dx *= inv; dz *= inv; }

        // Apply movement
        player.position.x += dx * speed * dt;
        player.position.z += dz * speed * dt;

        // Store 2D velocity for roll/FOV computation
        velocity.set(dx * speed, 0, dz * speed);

        // ── Terrain-hugging Y (dynamic hover height) ─────────────────────────
        // The hover height scales with horizontal speed — at full speed the
        // player rides higher (ground-effect feel); decelerating brings them
        // closer to the ground.  Large terrain bumps briefly launch the player
        // up rather than blocking, making SKIM feel like surfing over terrain
        // rather than rolling on an invisible glass floor.
        int groundY = findGroundBelow(world,
                player.position.x, player.position.y, player.position.z, 80);

        float horizSpeed    = (float)Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        float speedFraction = Math.min(1f, horizSpeed / Math.max(1f, GameConfig.skimSpeed));
        // At rest: hover at 30% of base target; at full speed: up to 110% (slight overshoot)
        float dynamicHeight = GameConfig.skimHeightTarget * (0.3f + 0.8f * speedFraction);
        float targetY       = groundY + dynamicHeight;
        float yErr          = targetY - player.position.y;

        // Proportional controller: quick snap up (terrain rise), gentle float down.
        // A softer downward gain means bumps launch the player briefly into the air.
        float yGain = (yErr > 0) ? 10f : 3.5f;
        player.position.y += yErr * yGain * dt;

        // Safety: hard clamp prevents clipping into the ground face
        player.position.y = Math.max(groundY + 0.3f, player.position.y);

        // ── Horizontal terrain collision (prevents flying through walls) ──────
        // Attempt the horizontal move then check the AABB.  If blocked, revert
        // the axis that caused the collision (allows sliding along walls).
        // (Vertical is handled by the Y controller above.)
        Vector3f horizPos = new Vector3f(player.position);
        if (collidesAt(world, horizPos)) {
            // Try backing out X
            Vector3f tryX = new Vector3f(player.position.x - dx * speed * dt,
                    player.position.y, player.position.z);
            if (!collidesAt(world, tryX)) { player.position.x = tryX.x; }

            // Try backing out Z
            Vector3f tryZ = new Vector3f(player.position.x, player.position.y,
                    player.position.z - dz * speed * dt);
            if (!collidesAt(world, tryZ)) { player.position.z = tryZ.z; }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  SOAR — full 3D free flight with smooth acceleration, gravity, and collision
    // ─────────────────────────────────────────────────────────────────────────

    private void updateSoar(long window, Camera camera, World world, float dt) {
        Vector3f lookDir  = camera.getLookDirection();  // includes pitch
        Vector3f rightDir = camera.getRight();
        Vector3f upDir    = new Vector3f(0f, 1f, 0f);

        float speed = GameConfig.soarSpeed;
        float accel = GameConfig.soarAccel;

        // Build desired velocity from key input
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

        // Smooth velocity toward desired
        velocity.x += (desired.x - velocity.x) * accel * dt;
        velocity.y += (desired.y - velocity.y) * accel * dt;
        velocity.z += (desired.z - velocity.z) * accel * dt;

        // ── Passive gravity: makes SOAR feel aerodynamic not zero-g ──────────
        // Only applies when not actively ascending or holding SPACE.
        // The player can overcome it by pitching upward and pressing W, just
        // like a real aircraft — satisfying and grounded in feel.
        if (!upHeld && !downHeld) {
            velocity.y -= GameConfig.soarGravity * dt;
        }

        // ── Air drag: velocity bleeds off when no directional input is held ──
        // This prevents endless sliding and makes flight feel aerodynamic.
        if (!anyInput) {
            float drag = (float)Math.pow(GameConfig.soarAirDrag, dt * 60.0);
            velocity.mul(drag);
        }

        // ── Terrain collision with axis-by-axis sliding (prevents no-clip) ───
        Vector3f prevPos = new Vector3f(player.position);
        Vector3f step    = new Vector3f(velocity).mul(dt);

        // Try full move first
        player.position.add(step);
        if (collidesAt(world, player.position)) {
            player.position.set(prevPos);

            // Try X axis alone
            Vector3f tryX = new Vector3f(prevPos.x + step.x, prevPos.y, prevPos.z);
            Vector3f tryY = new Vector3f(prevPos.x, prevPos.y + step.y, prevPos.z);
            Vector3f tryZ = new Vector3f(prevPos.x, prevPos.y, prevPos.z + step.z);

            boolean xOk = !collidesAt(world, tryX);
            boolean yOk = !collidesAt(world, tryY);
            boolean zOk = !collidesAt(world, tryZ);

            if (xOk) { player.position.x = tryX.x; } else { velocity.x *= -0.1f; }
            if (yOk) { player.position.y = tryY.y; } else { velocity.y *= -0.1f; }
            if (zOk) { player.position.z = tryZ.z; } else { velocity.z *= -0.1f; }
        }
    }

    /**
     * AABB terrain collision test for the player body (WIDTH=0.6, HEIGHT=1.8).
     * Returns true if ANY solid block intersects the player bounding box at pos.
     */
    private boolean collidesAt(World world, Vector3f pos) {
        final float hw = 0.29f;  // half-width with epsilon
        final float h  = 1.79f;  // height with epsilon
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
    //  GRAPPLE — hook-and-pendulum, AOT maneuver gear feel
    // ─────────────────────────────────────────────────────────────────────────

    private void updateGrapple(long window, Camera camera, World world, float dt) {
        boolean spaceHeld = glfwGetKey(window, GLFW_KEY_SPACE) == GLFW_PRESS;
        boolean spaceTap  = spaceHeld && !lastSpaceHeld;
        lastSpaceHeld = spaceHeld;

        if (!hooked) {
            // ── Unhooked: carry momentum + light gravity while aiming ─────────
            velocity.y -= 9.81f * GameConfig.grappleSwingStrength * dt;
            velocity.y = Math.max(-50f, velocity.y); // Clamp terminal velocity

            // ── FIX: Added Collision Detection to Unhooked Free-fall ──────────
            int substeps = (int)Math.ceil(velocity.length() * dt * 2.0f);
            substeps = Math.max(1, substeps);
            float stepDt = dt / substeps;

            for (int i = 0; i < substeps; i++) {
                Vector3f prevPos = new Vector3f(player.position);
                Vector3f step    = new Vector3f(velocity).mul(stepDt);
                player.position.add(step);

                if (collidesAt(world, player.position)) {
                    player.position.set(prevPos);
                    Vector3f tryX = new Vector3f(prevPos.x + step.x, prevPos.y, prevPos.z);
                    Vector3f tryY = new Vector3f(prevPos.x, prevPos.y + step.y, prevPos.z);
                    Vector3f tryZ = new Vector3f(prevPos.x, prevPos.y, prevPos.z + step.z);

                    if (!collidesAt(world, tryX)) { player.position.x = tryX.x; } else { velocity.x = 0; }
                    if (!collidesAt(world, tryY)) { player.position.y = tryY.y; } else { velocity.y = 0; }
                    if (!collidesAt(world, tryZ)) { player.position.z = tryZ.z; } else { velocity.z = 0; }
                }
            }

            // SPACE while ready → fire hook toward look target
            if (spaceTap && cooldownTimer <= 0f) {
                fireHook(camera, world);
            }

        } else {
            // ── Hooked: pendulum physics ──────────────────────────────────────
            tickPendulum(camera, dt);

            // SPACE while hooked → release, player launches along momentum
            if (spaceTap) {
                releaseHook();
            }
        }
    }

    /**
     * Raycast in look direction to find the nearest solid surface.
     * On hit: anchors hookPoint, sets ropeLength, transitions to hooked state.
     */
    /**
     * Raycast in look direction to find the nearest solid surface.
     * On hit: anchors hookPoint, sets ropeLength, transitions to hooked state.
     */
    private void fireHook(Camera camera, World world) {
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
                hookPoint.set(rx, ry, rz);

                Vector3f toHook = new Vector3f(hookPoint).sub(player.position);
                ropeLength = Math.max(3f, toHook.length());
                hooked = true;

                // ── THE FIX: Initial Launch Impulse! ──────────────────────────
                // Actively yanks the player toward the hook point so you actually
                // launch into the air instead of just hanging there!
                Vector3f yank = new Vector3f(toHook).normalize().mul(22.0f);
                velocity.add(yank);

                return;
            }
        }
        // Miss — short cooldown so the player can re-aim quickly
        cooldownTimer = 0.25f;
    }

    /**
     * Verlet pendulum step:
     *   1. Apply gravity to velocity.
     *   2. Integrate position (with terrain collision — prevents swinging into walls).
     *   3. Project position back onto the rope sphere.
     *   4. Remove the outward (tension) component from velocity.
     *
     * This gives a correct, stable pendulum without needing a constraint solver.
     */
    /**
     * Verlet pendulum step:
     *   1. Apply gravity to velocity.
     *   2. Project proposed position onto rope sphere FIRST.
     *   3. Apply terrain collision LAST.
     */
    private void tickPendulum(Camera camera, float dt) {
        // 1. Gravity
        float g = 9.81f * GameConfig.grappleSwingStrength;
        velocity.y -= g * dt;

        // Substep the integration to prevent high-speed tunneling
        int substeps = (int)Math.ceil(velocity.length() * dt * 2.0f);
        substeps = Math.max(1, substeps);
        float stepDt = dt / substeps;

        for (int i = 0; i < substeps; i++) {
            // 2. Propose new position based on velocity
            Vector3f step = new Vector3f(velocity).mul(stepDt);
            Vector3f nextPos = new Vector3f(player.position).add(step);

            // 3. Apply rope constraint FIRST
            Vector3f toPlayer = new Vector3f(nextPos).sub(hookPoint);
            float currentLen  = toPlayer.length();
            if (currentLen > 0.001f && currentLen > ropeLength) {
                // Add a tiny bit of elasticity so the rope isn't completely rigid
                float stretch = currentLen - ropeLength;
                toPlayer.normalize().mul(ropeLength + stretch * 0.1f);
                nextPos.set(new Vector3f(hookPoint).add(toPlayer));

                // 4. Remove outward radial velocity (tension)
                Vector3f radialDir  = new Vector3f(toPlayer).normalize();
                float    radialComp = velocity.dot(radialDir);
                if (radialComp > 0f) {
                    // Multiply by 0.95 to add slight friction to the swing
                    velocity.sub(new Vector3f(radialDir).mul(radialComp * 0.95f));
                }
            }

            // Calculate actual constrained step
            Vector3f finalStep = new Vector3f(nextPos).sub(player.position);
            Vector3f prevPos = new Vector3f(player.position);

            // 5. Apply Terrain Collision LAST
            player.position.add(finalStep);
            if (collidesAt(world_ref, player.position)) {
                player.position.set(prevPos);
                Vector3f tryX = new Vector3f(prevPos.x + finalStep.x, prevPos.y, prevPos.z);
                Vector3f tryY = new Vector3f(prevPos.x, prevPos.y + finalStep.y, prevPos.z);
                Vector3f tryZ = new Vector3f(prevPos.x, prevPos.y, prevPos.z + finalStep.z);

                if (!collidesAt(world_ref, tryX)) { player.position.x = tryX.x; } else { velocity.x = 0; }
                if (!collidesAt(world_ref, tryY)) { player.position.y = tryY.y; } else { velocity.y = 0; }
                if (!collidesAt(world_ref, tryZ)) { player.position.z = tryZ.z; } else { velocity.z = 0; }
            }
        }

        // ── Grapple camera lag ────────────────────────────────────────────────
        if (velocity.lengthSquared() > 0.1f) {
            Vector3f velDir = new Vector3f(velocity).normalize();
            float velYaw   = (float)Math.atan2(velDir.z, velDir.x);
            float velPitch = (float)Math.asin(Math.max(-1f, Math.min(1f, velDir.y)));
            float lagT = 0.20f * dt;
            grappleLagYaw   = grappleLagYaw   + (velYaw   - grappleLagYaw)   * lagT;
            grappleLagPitch = grappleLagPitch + (velPitch - grappleLagPitch) * lagT;
        }
    }

    private void releaseHook() {
        hooked        = false;
        cooldownTimer = GameConfig.grappleCooldown;
        launchVelocity.set(velocity);
        // Snap camera lag back to zero so it doesn't bleed into next frame
        grappleLagYaw   = 0f;
        grappleLagPitch = 0f;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Camera effects computation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Target roll angle in radians, derived from strafe input.
     *
     * Roll tilts INTO horizontal acceleration — right strafe = negative roll
     * (right side dips down). In GRAPPLE mode the swing's lateral velocity
     * component acts as the proxy for strafe.
     *
     * The roll is never injected into Camera.getViewMatrix(). Window.java
     * applies it as: viewWithRoll = Matrix4f.rotateZ(roll) * getViewMatrix()
     *
     * SOAR uses a smaller maximum roll angle (soarRollMaxAngle) and the global
     * rollLerpSpeed has been reduced from 6→2.5 — both prevent the rapid
     * banking that users found disorienting in SOAR mode.
     */
    private float computeTargetRoll() {
        if (mode == FlightMode.GRAPPLE && hooked) {
            float maxRad = (float)Math.toRadians(GameConfig.rollMaxAngle);
            // Use lateral velocity as the roll proxy in swing mode
            float lat = velocity.x; // crude but stable
            return Math.max(-maxRad, Math.min(maxRad, -lat * 0.006f));
        }

        if (mode == FlightMode.SOAR) {
            // Softer roll for SOAR: distinct smaller angle so banking is subtle
            float maxRad = (float)Math.toRadians(GameConfig.soarRollMaxAngle);
            return -strafeSign * maxRad;
        }

        // SKIM + GRAPPLE (unhooked): full roll angle
        float maxRad = (float)Math.toRadians(GameConfig.rollMaxAngle);
        return -strafeSign * maxRad;
    }

    /**
     * Target FOV boost (degrees above GameConfig.fov).
     * Scales from 0 at rest to GameConfig.flightFovMax at peak speed.
     */
    private float computeTargetFovBoost() {
        float speed    = velocity.length();
        float refSpeed = (mode == FlightMode.SOAR) ? GameConfig.soarSpeed : GameConfig.skimSpeed;
        float t        = Math.min(1f, speed / Math.max(1f, refSpeed));
        return t * GameConfig.flightFovMax;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Terrain probe for SKIM
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the world Y of the first solid block at or below the given
     * position, scanning down by up to {@code maxDrop} blocks.
     * Returns {@code (int)y - maxDrop} if nothing is found (void below).
     */
    private int findGroundBelow(World world, float x, float y, float z, int maxDrop) {
        int bx     = (int)Math.floor(x);
        int bz     = (int)Math.floor(z);
        int startY = (int)Math.floor(y);

        for (int by = startY; by >= startY - maxDrop; by--) {
            if (world.getBlock(bx, by, bz).isSolid()) return by + 1;
        }
        return startY - maxDrop;
    }

    public boolean isHooked() { return hooked; }
    public Vector3f getHookPoint() { return hookPoint; }
}