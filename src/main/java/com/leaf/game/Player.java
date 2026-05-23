package com.leaf.game;

import org.joml.Vector3f;
import static org.lwjgl.glfw.GLFW.*;

public class Player {

    public boolean debugMode = false;
    public Vector3f position;

    private float velocityY = 0.0f;
    private boolean onGround = false;

    // Player box dimensions
    private static final float WIDTH      = 0.6f;
    private static final float HEIGHT     = 1.8f;
    private static final float EYE_HEIGHT = 1.6f;

    // Physics constants
    private static final float GRAVITY      = 35.0f; // Tweaked for a better fall feel
    private static final float JUMP_FORCE   = GameConfig.JUMP_FORCE;
    private static final float WALK_SPEED   = GameConfig.WALK_SPEED;
    private static final float SPRINT_SPEED = GameConfig.SPRINT_SPEED;
    private static final float FLY_SPEED    = GameConfig.FLY_SPEED;

    // Double-Click Tracking
    private boolean lastW = false;
    private double lastWTime = 0;
    private boolean isSprinting = false;

    private boolean lastSpace = false;
    private double lastSpaceTime = 0;

    public Player(float x, float y, float z) {
        position = new Vector3f(x, y, z);
    }

    public void update(long window, Camera camera, World world, float deltaTime) {
        double now = glfwGetTime();

        // ── 1. DOUBLE CLICK TRACKING (W to Sprint) ──
        boolean currentW = glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS;
        if (currentW && !lastW) { // Key was just pressed
            if (now - lastWTime < 0.3) {
                isSprinting = true;
            }
            lastWTime = now;
        }
        if (!currentW) { // Cancel sprint if W is released
            isSprinting = false;
        }
        lastW = currentW;

        // ── 2. DOUBLE CLICK TRACKING (Space to toggle Fly/Debug) ──
        boolean currentSpace = glfwGetKey(window, GLFW_KEY_SPACE) == GLFW_PRESS;
        if (currentSpace && !lastSpace) {
            if (now - lastSpaceTime < 0.3) {
                debugMode = !debugMode; // Toggle mode
                velocityY = 0.0f;       // Stop falling instantly
            }
            lastSpaceTime = now;
        }
        lastSpace = currentSpace;

        // ── 3. CALCULATE MOVEMENT DELTAS ──
        float speed = debugMode ? FLY_SPEED : (isSprinting ? SPRINT_SPEED : WALK_SPEED);

        // We use getForward() so pitch NEVER affects horizontal movement!
        Vector3f forward = camera.getForward();
        Vector3f right   = camera.getRight();

        float dx = 0.0f;
        float dy = 0.0f;
        float dz = 0.0f;

        // WASD Horizontal Input
        if (glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS) {
            dx += forward.x * speed * deltaTime;
            dz += forward.z * speed * deltaTime;
        }
        if (glfwGetKey(window, GLFW_KEY_S) == GLFW_PRESS) {
            dx -= forward.x * speed * deltaTime;
            dz -= forward.z * speed * deltaTime;
        }
        if (glfwGetKey(window, GLFW_KEY_D) == GLFW_PRESS) {
            dx += right.x * speed * deltaTime;
            dz += right.z * speed * deltaTime;
        }
        if (glfwGetKey(window, GLFW_KEY_A) == GLFW_PRESS) {
            dx -= right.x * speed * deltaTime;
            dz -= right.z * speed * deltaTime;
        }

        // ── 4. APPLY MOVEMENT (Axis-By-Axis for perfect collisions) ──
        if (debugMode) {
            // Debug Mode: No collisions, direct Y control
            if (glfwGetKey(window, GLFW_KEY_SPACE) == GLFW_PRESS) {
                dy += speed * deltaTime;
            }
            if (glfwGetKey(window, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS) {
                dy -= speed * deltaTime;
            }
            position.x += dx;
            position.y += dy;
            position.z += dz;
        } else {
            // Survival Mode: Apply Gravity
            velocityY -= GRAVITY * deltaTime;

            // Jump
            if (currentSpace && onGround) {
                velocityY = JUMP_FORCE;
                onGround = false;
            }
            dy = velocityY * deltaTime;

            // ── SUB-STEPPED PHYSICS ──
            // Break large frame movements into tiny steps so we never phase through floor/walls
            int substeps = (int) Math.ceil(Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) * 10.0f);
            substeps = Math.max(1, substeps);

            float stepX = dx / substeps;
            float stepY = dy / substeps;
            float stepZ = dz / substeps;

            onGround = false; // Reset before checking

            for (int i = 0; i < substeps; i++) {
                if (stepX != 0) {
                    position.x += stepX;
                    if (resolveCollisionX(world, stepX)){
                        stepX = 0; // Hit wall, stop X
                        isSprinting = false; // <-- STOP SPRINT
                    }

                }
                if (stepY != 0) {
                    position.y += stepY;
                    if (resolveCollisionY(world, stepY)) stepY = 0; // Hit floor/ceiling, stop Y
                }
                if (stepZ != 0) {
                    position.z += stepZ;
                    if (resolveCollisionZ(world, stepZ)){
                        isSprinting = false; // <-- STOP SPRINT
                        // stepZ = 0; // Hit wall, stop Z
                    }

                }
            }
        }

        // ── 5. UPDATE CAMERA ──
        camera.position.set(position.x, position.y + EYE_HEIGHT, position.z);
    }

    // ────────────────────────────────────────────────────────────────────────
    // EPSILON COLLISION RESOLUTION (Anti-Sticking & Anti-Scraping)
    // ────────────────────────────────────────────────────────────────────────
    private static final float EPSILON = 0.01f; // 1cm shave off the hitbox edges

    private boolean resolveCollisionX(World world, float dx) {
        float halfW = WIDTH / 2.0f;
        int minY = (int) Math.floor(position.y + EPSILON);
        int maxY = (int) Math.floor(position.y + HEIGHT - EPSILON);
        int minZ = (int) Math.floor(position.z - halfW + EPSILON);
        int maxZ = (int) Math.floor(position.z + halfW - EPSILON);

        if (dx > 0) { // Moving East (+X) - only check leading East edge
            int leadingX = (int) Math.floor(position.x + halfW);
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (world.getBlock(leadingX, y, z).isSolid()) {
                        position.x = leadingX - halfW;
                        return true;
                    }
                }
            }
        } else if (dx < 0) { // Moving West (-X) - only check trailing West edge
            int trailingX = (int) Math.floor(position.x - halfW);
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (world.getBlock(trailingX, y, z).isSolid()) {
                        position.x = trailingX + 1.0f + halfW;
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean resolveCollisionY(World world, float dy) {
        float halfW = WIDTH / 2.0f;
        int minX = (int) Math.floor(position.x - halfW + EPSILON);
        int maxX = (int) Math.floor(position.x + halfW - EPSILON);
        int minZ = (int) Math.floor(position.z - halfW + EPSILON);
        int maxZ = (int) Math.floor(position.z + halfW - EPSILON);

        if (dy > 0) { // Moving UP - only check ceiling blocks at head height
            int headY = (int) Math.floor(position.y + HEIGHT);
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (world.getBlock(x, headY, z).isSolid()) {
                        position.y = headY - HEIGHT;
                        velocityY = 0.0f;
                        return true;
                    }
                }
            }
        } else if (dy < 0) { // Moving DOWN - only check floor blocks at feet height
            int feetY = (int) Math.floor(position.y);
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (world.getBlock(x, feetY, z).isSolid()) {
                        position.y = feetY + 1.0f;
                        velocityY = 0.0f;
                        onGround = true;
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean resolveCollisionZ(World world, float dz) {
        float halfW = WIDTH / 2.0f;
        int minX = (int) Math.floor(position.x - halfW + EPSILON);
        int maxX = (int) Math.floor(position.x + halfW - EPSILON);
        int minY = (int) Math.floor(position.y + EPSILON);
        int maxY = (int) Math.floor(position.y + HEIGHT - EPSILON);

        if (dz > 0) { // Moving North (+Z) - only check leading North edge
            int leadingZ = (int) Math.floor(position.z + halfW);
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    if (world.getBlock(x, y, leadingZ).isSolid()) {
                        position.z = leadingZ - halfW;
                        return true;
                    }
                }
            }
        } else if (dz < 0) { // Moving South (-Z) - only check trailing South edge
            int trailingZ = (int) Math.floor(position.z - halfW);
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    if (world.getBlock(x, y, trailingZ).isSolid()) {
                        position.z = trailingZ + 1.0f + halfW;
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Steps a ray from the camera forward in small increments.
     * Returns which block we're looking at and where to place a new one.
     * Returns null if no solid block is within reach.
     */
    public RaycastResult getTargetBlock(Camera camera, World world) {
        final float STEP      = 0.05f;  // ray step size in world units
        final float MAX_REACH = 5.0f;   // how far the player can reach

        // Start at the camera's eye position
        float rx = camera.position.x;
        float ry = camera.position.y;
        float rz = camera.position.z;

        // The direction the camera is looking (already normalized in Camera)
        org.joml.Vector3f dir = camera.getLookDirection();
        float dx = dir.x * STEP;
        float dy = dir.y * STEP;
        float dz = dir.z * STEP;

        // Track the previous position (the last air block before entering solid)
        int lastBX = (int) Math.floor(rx);
        int lastBY = (int) Math.floor(ry);
        int lastBZ = (int) Math.floor(rz);

        float distance = 0;
        while (distance < MAX_REACH) {
            rx += dx;
            ry += dy;
            rz += dz;
            distance += STEP;

            int bx = (int) Math.floor(rx);
            int by = (int) Math.floor(ry);
            int bz = (int) Math.floor(rz);

            if (world.getBlock(bx, by, bz).isSolid()) {
                RaycastResult result = new RaycastResult();
                result.hit    = true;

                // The block we hit
                result.hitX   = bx;
                result.hitY   = by;
                result.hitZ   = bz;

                // The block position just before we entered — where to place
                result.placeX = lastBX;
                result.placeY = lastBY;
                result.placeZ = lastBZ;

                return result;
            }

            // Remember this position as "last air"
            lastBX = (int) Math.floor(rx);
            lastBY = (int) Math.floor(ry);
            lastBZ = (int) Math.floor(rz);
        }

        return null; // nothing in reach
    }
}