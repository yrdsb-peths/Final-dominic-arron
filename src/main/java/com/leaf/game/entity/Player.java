package com.leaf.game.entity;

import com.leaf.game.util.Camera;
import com.leaf.game.util.RaycastResult;
import com.leaf.game.core.GameConfig;
import com.leaf.game.world.Block;
import com.leaf.game.world.World;
import org.joml.Vector3f;

import static org.lwjgl.glfw.GLFW.*;

public class Player {

    public boolean debugMode = false;
    public Vector3f position;

    // ── HEALTH & FALL DAMAGE ──────────────────────────────────────────────────
    public float health    = 20.0f;
    public float maxHealth = 20.0f;
    public float highestY  = -1000f;

    private float   velocityY  = 0.0f;
    private boolean onGround   = false;
    private boolean wasInWater = false;

    private static final float WIDTH      = 0.6f;
    private static final float HEIGHT     = 1.8f;
    private static final float EYE_HEIGHT = 1.6f;

    // ── MOVEMENT STATE ────────────────────────────────────────────────────────
    private boolean lastW     = false;
    private double  lastWTime = 0;
    private boolean isSprinting = false;

    private boolean lastSpace    = false;
    private double  lastSpaceTime = 0;

    // ── FLIGHT ENGINE ─────────────────────────────────────────────────────────
    // Public so Window.java can read roll/fov each frame.
    public final FlightController flightController = new FlightController(this);

    // Track last debugMode state to detect the transition and pick up launch vel
    private boolean wasFlying = false;

    // ── ABILITIES ─────────────────────────────────────────────────────────────
    // Q=Dash  G=Cannonball  Z=Rewind  E=Blink
    // All abilities disabled while debugMode (flight) is active.
    public final AbilityController abilities = new AbilityController(this);

    // ── ATTACKS ───────────────────────────────────────────────────────────────
    // F=Runic Cleave (melee)   C=Void Shard (ranged bolt)
    // All attacks disabled while debugMode (flight) is active.
    public final AttackController attacks = new AttackController(this);

    // ── MANHATTAN TRANSFER (Stand / Drone — X / TAB / LMB) ───────────────────
    // Ticked BEFORE abilities so it can take full positional control first.
    public final StandController stand = new StandController(this);

    // ── MINATO'S SEAL (H / B / N) ────────────────────────────────────────────
    // Ticked after attacks; never takes full positional control.
    public final SealController seals = new SealController(this);

    // ── GROUND SMASH ─────────────────────────────────────────────────────────
    private boolean isSmashing = false;
    private boolean lastShift  = false;   // edge detector for smash trigger

    public int  smashImpactX = Integer.MIN_VALUE;
    public int  smashImpactY, smashImpactZ;
    public int  currentSmashRadius = GameConfig.smashCraterRadius; // Tracks the height-scaled radius
    // ─────────────────────────────────────────────────────────────────────────
    //  Constructor
    // ─────────────────────────────────────────────────────────────────────────

    public Player(float x, float y, float z) {
        position = new Vector3f(x, y, z);
        highestY = y;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Main update (called with time-scaled deltaTime from Window.java)
    // ─────────────────────────────────────────────────────────────────────────

    public void update(long window, Camera camera, World world, float deltaTime) {
        double now = glfwGetTime();

        // ── Clear per-frame smash signal ──────────────────────────────────────
        smashImpactX = Integer.MIN_VALUE;

        // ── DOUBLE-TAP W → SPRINT ─────────────────────────────────────────────
        boolean currentW = glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS;
        if (currentW && !lastW) {
            if (now - lastWTime < 0.3) isSprinting = true;
            lastWTime = now;
        }
        if (!currentW) isSprinting = false;
        lastW = currentW;

        // ── DOUBLE-TAP SPACE → TOGGLE FLIGHT ─────────────────────────────────
        boolean currentSpace = glfwGetKey(window, GLFW_KEY_SPACE) == GLFW_PRESS;
        if (currentSpace && !lastSpace) {
            if (now - lastSpaceTime < 0.3) {
                debugMode = !debugMode;
                velocityY = 0f;
                isSmashing = false;
                if (!debugMode) {
                    flightController.onFlightDeactivated();
                    Vector3f lv = flightController.getLaunchVelocity();
                    velocityY = lv.y;
                    position.x += lv.x * deltaTime;
                    position.z += lv.z * deltaTime;
                }
            }
            lastSpaceTime = now;
        }
        lastSpace = currentSpace;

        boolean shiftHeld = glfwGetKey(window, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS;

        float speed = debugMode ? GameConfig.FLY_SPEED
                : isSprinting ? GameConfig.SPRINT_SPEED
                  : GameConfig.WALK_SPEED;

        boolean isCameraInWater = isBlockLiquid(world,
                camera.position.x, camera.position.y, camera.position.z);
        Vector3f forward = isCameraInWater ? camera.getLookDirection() : camera.getForward();
        Vector3f right   = camera.getRight();

        // ── FLIGHT MODE — delegate to FlightController ───────────────────────
        if (debugMode) {
            flightController.update(window, camera, world, deltaTime);
            wasFlying = true;
            camera.position.set(position.x, position.y + EYE_HEIGHT, position.z);
            highestY = position.y;
            return;
        }

        if (wasFlying) {
            wasFlying = false;
        }

        flightController.decayEffects(deltaTime);

        // ── STAND TICK (Manhattan Transfer) ───────────────────────────────────
        // Must run before abilities so that drone-perspective takes priority.
        // Returns true when the player is piloting the drone — body is frozen.
        if (stand.tick(window, camera, world, deltaTime)) {
            attacks.tick(window, stand.standCamera, world, deltaTime);
            // Gravity still applies to the player body while piloting the drone
            boolean inWaterD = isBlockLiquid(world, position.x, position.y + 0.1f, position.z);
            if (inWaterD && !wasInWater) highestY = position.y;
            wasInWater = inWaterD;
            if (inWaterD) {
                velocityY *= (float) Math.pow(0.85f, deltaTime * 60f);
                velocityY  = Math.max(-4.0f, Math.min(4.0f, velocityY));
            } else {
                velocityY -= GameConfig.GRAVITY * deltaTime;
            }
            float dyD = velocityY * deltaTime;
            if (dyD != 0f) {
                position.y += dyD;
                if (resolveCollisionY(world, dyD)) { velocityY = 0f; onGround = true; }
                else                                { onGround = false; }
            }
            if (onGround) highestY = position.y;
            else if (position.y > highestY) highestY = position.y;
            return;
        }

        // ── ABILITY TICK ───────────────────────────────────────────────────────
        // Runs before physics. Returns true when ability has full positional
        // control (Rewind) — caller skips the physics block entirely.
        if (abilities.tick(window, camera, world, deltaTime)) {
            camera.position.set(position.x, position.y + EYE_HEIGHT, position.z);
            return;
        }

        // ── ATTACK TICK ────────────────────────────────────────────────────────
        // Runs after abilities (so rewind takeover is already handled above).
        // Never takes full positional control — attacks are cosmetic + destructive only.
        attacks.tick(window, camera, world, deltaTime);

        // ── SEAL TICK (Minato's Seal) ──────────────────────────────────────────
        // Runs after attacks; projectiles and teleport are handled here.
        // Never takes full positional control.
        seals.tick(window, camera, world, deltaTime);

        float dx = 0f, dy = 0f, dz = 0f;

        // ── GROUND SMASH — pre-empt normal input while smashing ───────────────
        if (isSmashing) {
            velocityY = -GameConfig.smashDescentSpeed;
            float targetPitch = -(float)(Math.PI * 0.305);
            camera.pitch += (targetPitch - camera.pitch) * Math.min(1f, 4f * deltaTime);

        } else if (abilities.isDashing) {
            // ── DASH — override WASD with dash velocity ────────────────────────
            // Vertical physics (gravity, jumping) still runs normally below.
            dx = abilities.dashDirX * GameConfig.dashSpeed * deltaTime;
            dz = abilities.dashDirZ * GameConfig.dashSpeed * deltaTime;

        } else if (abilities.isCannonballing) {
            // ── CANNONBALL — override horizontal movement ──────────────────────
            // Vertical physics runs normally (gravity decelerates velocityY).
            // Camera is NOT rotated — player can look around freely mid-flight.
            dx = abilities.cannonVelX * deltaTime;
            dz = abilities.cannonVelZ * deltaTime;

        } else if (abilities.isPillaring || abilities.isHealing) {    
            // Lock horizontal movement while performing stone pillar rise or channeling heal
            dx = 0f;
            dz = 0f;

        }else {
            if (glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS) {
                dx += forward.x * speed * deltaTime;
                dz += forward.z * speed * deltaTime;
                if (isCameraInWater) velocityY += forward.y * speed * 3.5f * deltaTime;
            }
            if (glfwGetKey(window, GLFW_KEY_S) == GLFW_PRESS) {
                dx -= forward.x * speed * deltaTime;
                dz -= forward.z * speed * deltaTime;
                if (isCameraInWater) velocityY -= forward.y * speed * 3.5f * deltaTime;
            }
            if (glfwGetKey(window, GLFW_KEY_D) == GLFW_PRESS) { dx += right.x * speed * deltaTime; dz += right.z * speed * deltaTime; }
            if (glfwGetKey(window, GLFW_KEY_A) == GLFW_PRESS) { dx -= right.x * speed * deltaTime; dz -= right.z * speed * deltaTime; }
        }

        // ── SURVIVAL PHYSICS ──────────────────────────────────────────────────
        boolean inWater   = isBlockLiquid(world, position.x, position.y + 0.1f,      position.z);
        boolean submerged = isBlockLiquid(world, position.x, position.y + EYE_HEIGHT, position.z);

        if (inWater && !wasInWater) highestY = position.y;

        if (inWater) {
            velocityY -= 2.5f * deltaTime;
            if (submerged) velocityY += 8.0f * deltaTime;

            if (currentSpace) velocityY += 35f * deltaTime;
            if (shiftHeld)    velocityY -= 35f * deltaTime;

            velocityY *= (float)Math.pow(0.85f, deltaTime * 60f);
            velocityY  = Math.max(-4.0f, Math.min(4.0f, velocityY));

            if (isSprinting) { dx *= 0.90f; dz *= 0.90f; } else { dx *= 0.55f; dz *= 0.55f; }
            highestY = position.y;
            isSmashing = false;
            abilities.cancelCannonball(); // water takes over, cannonball ends cleanly

        } else if (!isSmashing) {
            velocityY -= GameConfig.GRAVITY * deltaTime;

            if (wasInWater && currentSpace) {
                velocityY = GameConfig.JUMP_FORCE * 0.85f;
            } else if (currentSpace && onGround) {
                velocityY = GameConfig.JUMP_FORCE;
                onGround  = false;
            }

            boolean shiftJustPressed = shiftHeld && !lastShift;
            if (!onGround
                    && shiftJustPressed
                    && velocityY < GameConfig.smashTriggerVelocity
                    && (highestY - position.y) > GameConfig.smashMinHeight) {
                isSmashing = true;
            }
        }

        lastShift  = shiftHeld;
        wasInWater = inWater;
        dy = velocityY * deltaTime;

        int substeps = (int)Math.ceil(
                Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) * 10f);
        substeps = Math.max(1, substeps);

        float stepX = dx / substeps, stepY = dy / substeps, stepZ = dz / substeps;

        boolean wasOnGround = onGround;
        onGround = false;

        for (int i = 0; i < substeps; i++) {
            if (stepX != 0f) { position.x += stepX; if (resolveCollisionX(world, stepX)) { stepX = 0f; isSprinting = false; } }
            if (stepY != 0f) { position.y += stepY; if (resolveCollisionY(world, stepY)) stepY = 0f; }
            if (stepZ != 0f) { position.z += stepZ; if (resolveCollisionZ(world, stepZ)) { stepZ = 0f; isSprinting = false; } }
        }

        if (!wasOnGround && onGround) {
            if (isSmashing) {
                smashImpactX = (int)Math.floor(position.x);
                smashImpactY = (int)Math.floor(position.y);
                smashImpactZ = (int)Math.floor(position.z);

                // Calculate dynamic smash radius based on how far we fell
                float fallDist = highestY - position.y;
                currentSmashRadius = GameConfig.smashCraterRadius
                        + (int) Math.floor((fallDist - GameConfig.smashMinHeight) * 0.08f);

                // Cap the radius at 12 blocks to keep performance stable during massive drops
                currentSmashRadius = Math.max(GameConfig.smashCraterRadius, Math.min(12, currentSmashRadius));

                isSmashing   = false;
                velocityY    = 0f;
                camera.pitch = (float)Math.toRadians(-30.0);
            } else if (abilities.isCannonballing) {
                // Cannonball landing: end ballistic flight, no fall damage
                abilities.isCannonballing = false;
                abilities.cannonVelX      = 0f;
                abilities.cannonVelZ      = 0f;
                velocityY = 0f;
                // highestY already set to launch point so fallDist ≤ 0; no damage
            } else {
                float fallDist = highestY - position.y;
                if (fallDist > 4.0f) {
                    health -= (fallDist * 0.5f - 2.0f);
                    if (health <= 0f) {
                        System.out.println("You died!");
                        position.set(1000, 255, 1000);
                        health = maxHealth;
                    }
                }
            }
            highestY = position.y;
        } else if (onGround) {
            highestY = position.y;
            isSmashing = false;
        } else if (position.y > highestY) {
            highestY = position.y;
        }

        camera.position.set(position.x, position.y + EYE_HEIGHT, position.z);
    }

    public float getCameraRoll() {
        return flightController.getCameraRoll() + abilities.getCameraRoll();
    }
    public float getCameraFovBoost() {
        if (isSmashing) return -8f;
        return flightController.getFovBoost() + abilities.getCameraFovBoost() + attacks.getFovBoost();
    }
    public boolean isSmashing() { return isSmashing; }

    // ── Package-private accessors for AbilityController ───────────────────────
    // AbilityController is in the same package so these stay package-visible.
    public float getVelocityY()  { return velocityY; }
    public void setVelocityY(float v) { this.velocityY = v; }

    private boolean isBlockLiquid(World world, float x, float y, float z) {
        return world.getBlock(
                (int)Math.floor(x), (int)Math.floor(y), (int)Math.floor(z)).isLiquid();
    }

    private static final float EPSILON = 0.01f;

    private boolean resolveCollisionX(World world, float dx) {
        float halfW = WIDTH / 2f;
        int minY = (int)Math.floor(position.y + EPSILON),           maxY = (int)Math.floor(position.y + HEIGHT - EPSILON);
        int minZ = (int)Math.floor(position.z - halfW + EPSILON),   maxZ = (int)Math.floor(position.z + halfW - EPSILON);

        if (dx > 0) {
            int leadX = (int)Math.floor(position.x + halfW);
            for (int y = minY; y <= maxY; y++) for (int z = minZ; z <= maxZ; z++)
                if (world.getBlock(leadX, y, z).isSolid()) { position.x = leadX - halfW; return true; }
        } else if (dx < 0) {
            int trailX = (int)Math.floor(position.x - halfW);
            for (int y = minY; y <= maxY; y++) for (int z = minZ; z <= maxZ; z++)
                if (world.getBlock(trailX, y, z).isSolid()) { position.x = trailX + 1f + halfW; return true; }
        }
        return false;
    }

    private boolean resolveCollisionY(World world, float dy) {
        float halfW = WIDTH / 2f;
        int minX = (int)Math.floor(position.x - halfW + EPSILON), maxX = (int)Math.floor(position.x + halfW - EPSILON);
        int minZ = (int)Math.floor(position.z - halfW + EPSILON), maxZ = (int)Math.floor(position.z + halfW - EPSILON);

        if (dy > 0) {
            int headY = (int)Math.floor(position.y + HEIGHT);
            for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++)
                if (world.getBlock(x, headY, z).isSolid()) { position.y = headY - HEIGHT; velocityY = 0f; return true; }
        } else if (dy < 0) {
            int feetY = (int)Math.floor(position.y);
            for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++)
                if (world.getBlock(x, feetY, z).isSolid()) { position.y = feetY + 1f; velocityY = 0f; onGround = true; return true; }
        }
        return false;
    }

    private boolean resolveCollisionZ(World world, float dz) {
        float halfW = WIDTH / 2f;
        int minX = (int)Math.floor(position.x - halfW + EPSILON), maxX = (int)Math.floor(position.x + halfW - EPSILON);
        int minY = (int)Math.floor(position.y + EPSILON),          maxY = (int)Math.floor(position.y + HEIGHT - EPSILON);

        if (dz > 0) {
            int leadZ = (int)Math.floor(position.z + halfW);
            for (int x = minX; x <= maxX; x++) for (int y = minY; y <= maxY; y++)
                if (world.getBlock(x, y, leadZ).isSolid()) { position.z = leadZ - halfW; return true; }
        } else if (dz < 0) {
            int trailZ = (int)Math.floor(position.z - halfW);
            for (int x = minX; x <= maxX; x++) for (int y = minY; y <= maxY; y++)
                if (world.getBlock(x, y, trailZ).isSolid()) { position.z = trailZ + 1f + halfW; return true; }
        }
        return false;
    }

    public RaycastResult getTargetBlock(Camera camera, World world) {
        final float STEP = 0.05f, MAX_REACH = 5.0f;
        float rx = camera.position.x, ry = camera.position.y, rz = camera.position.z;
        org.joml.Vector3f dir = camera.getLookDirection();
        float ddx = dir.x * STEP, ddy = dir.y * STEP, ddz = dir.z * STEP;

        int lastBX = (int)Math.floor(rx), lastBY = (int)Math.floor(ry), lastBZ = (int)Math.floor(rz);
        float dist = 0;

        while (dist < MAX_REACH) {
            rx += ddx; ry += ddy; rz += ddz; dist += STEP;
            int bx = (int)Math.floor(rx), by = (int)Math.floor(ry), bz = (int)Math.floor(rz);

            if (world.getBlock(bx, by, bz).isSolid()) {
                RaycastResult res = new RaycastResult();
                res.hit = true; res.hitX = bx; res.hitY = by; res.hitZ = bz;
                res.placeX = lastBX; res.placeY = lastBY; res.placeZ = lastBZ;
                return res;
            }
            lastBX = bx; lastBY = by; lastBZ = bz;
        }
        return null;
    }
}