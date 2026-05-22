package com.leaf.game;

import org.joml.Vector3f;

import static org.lwjgl.glfw.GLFW.*;

public class Player {

    //Debug Mode
    public boolean debugMode = false;
    // Feet position (bottom-center of the player box)
    public Vector3f position;

    // Vertical velocity only. Horizontal movement is direct (not physics-based).
    private float velocityY = 0.0f;
    private boolean onGround = false;

    // Player box dimensions
    private static final float WIDTH      = 0.6f;   // total width and depth
    private static final float HEIGHT     = 1.8f;
    private static final float EYE_HEIGHT = 1.6f;   // camera offset from feet

    // Physics constants
    private static final float GRAVITY    = 40.0f;
    private static final float JUMP_FORCE =  11.0f;
    private static final float MOVE_SPEED =  5.0f;

    public Player(float x, float y, float z) {
        position = new Vector3f(x, y, z);
    }

    // Called every frame from Window.loop()
    public void update(long window, Camera camera, World world, float deltaTime) {
        handleMovement(window, camera, deltaTime);
        if(!debugMode) {
            handleGravityAndJump(window, deltaTime);
            resolveCollisions(world);
        }

        // Place the camera at eye level above feet
        camera.position.set(position.x, position.y + EYE_HEIGHT, position.z);
    }

    // --- HORIZONTAL MOVEMENT ---

    private void handleMovement(long window, Camera camera, float deltaTime) {

        if (debugMode) {
            // ── SPECTATOR / FLY MODE ─────────────────────────────────────────
            Vector3f forward = camera.getLookDirection(); // Fly exactly where we look
            Vector3f right   = camera.getRight();
            float speed      = MOVE_SPEED * 4.0f;         // Fast exploration

            if (glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS) {
                position.add(new Vector3f(forward).mul(speed * deltaTime));
            }
            if (glfwGetKey(window, GLFW_KEY_S) == GLFW_PRESS) {
                position.sub(new Vector3f(forward).mul(speed * deltaTime));
            }
            if (glfwGetKey(window, GLFW_KEY_D) == GLFW_PRESS) {
                position.add(new Vector3f(right).mul(speed * deltaTime));
            }
            if (glfwGetKey(window, GLFW_KEY_A) == GLFW_PRESS) {
                position.sub(new Vector3f(right).mul(speed * deltaTime));
            }
            if (glfwGetKey(window, GLFW_KEY_SPACE) == GLFW_PRESS) {
                position.y += speed * deltaTime;
            }
            if (glfwGetKey(window, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS) {
                position.y -= speed * deltaTime;
            }
        } else {// Reuse camera's forward/right vectors for direction
            // getForward() ignores pitch so you move horizontally only
            Vector3f forward = camera.getForward();
            Vector3f right = camera.getRight();

            if (glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS) {
                position.add(new Vector3f(forward).mul(MOVE_SPEED * deltaTime));
            }
            if (glfwGetKey(window, GLFW_KEY_S) == GLFW_PRESS) {
                position.sub(new Vector3f(forward).mul(MOVE_SPEED * deltaTime));
            }
            if (glfwGetKey(window, GLFW_KEY_D) == GLFW_PRESS) {
                position.add(new Vector3f(right).mul(MOVE_SPEED * deltaTime));
            }
            if (glfwGetKey(window, GLFW_KEY_A) == GLFW_PRESS) {
                position.sub(new Vector3f(right).mul(MOVE_SPEED * deltaTime));
            }
        }
    }

    // --- GRAVITY AND JUMPING ---

    private void handleGravityAndJump(long window, float deltaTime) {
        // Apply gravity every frame
        velocityY -= GRAVITY * deltaTime;

        // Jump only when on the ground (prevents double-jumping)
        if (glfwGetKey(window, GLFW_KEY_SPACE) == GLFW_PRESS && onGround) {
            velocityY = JUMP_FORCE;
            onGround = false;
        }

        // Move vertically by current velocity
        position.y += velocityY * deltaTime;
    }

    // --- COLLISION RESOLUTION ---

    private void resolveCollisions(World world) {
        onGround = false; // reset each frame — will be set true if standing on something

        // --- Y AXIS (vertical) ---
        // Find the range of blocks the player box could overlap
        int minX = (int) Math.floor(position.x - WIDTH / 2);
        int maxX = (int) Math.floor(position.x + WIDTH / 2);
        int minZ = (int) Math.floor(position.z - WIDTH / 2);
        int maxZ = (int) Math.floor(position.z + WIDTH / 2);

        if (velocityY < 0) {
            // Moving downward — check blocks below feet
            int feetBlock = (int) Math.floor(position.y);

            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (world.getBlock(x, feetBlock, z).isSolid()) {
                        // Push feet to top of this block
                        position.y = feetBlock + 1.0f;
                        velocityY  = 0.0f;
                        onGround   = true;
                    }
                }
            }
        } else if (velocityY > 0) {
            // Moving upward — check blocks above head
            int headBlock = (int) Math.floor(position.y + HEIGHT);

            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (world.getBlock(x, headBlock, z).isSolid()) {
                        // Push head below bottom of this block
                        position.y = headBlock - HEIGHT;
                        velocityY  = 0.0f;
                    }
                }
            }
        }

        // --- X AXIS (horizontal) ---
        int minY = (int) Math.floor(position.y + 0.01f); // small offset: skip the floor block
        int maxY = (int) Math.floor(position.y + HEIGHT - 0.01f);

        // Check right (+X)
        int rightBlock = (int) Math.floor(position.x + WIDTH / 2);
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (world.getBlock(rightBlock, y, z).isSolid()) {
                    position.x = rightBlock - WIDTH / 2;
                }
            }
        }

        // Check left (-X)
        int leftBlock = (int) Math.floor(position.x - WIDTH / 2);
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (world.getBlock(leftBlock, y, z).isSolid()) {
                    position.x = leftBlock + 1.0f + WIDTH / 2;
                }
            }
        }

        // Recalculate Z range after X resolution (position.x may have changed)
        minZ = (int) Math.floor(position.z - WIDTH / 2);
        maxZ = (int) Math.floor(position.z + WIDTH / 2);

        // Check front (+Z)
        int frontBlock = (int) Math.floor(position.z + WIDTH / 2);
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                if (world.getBlock(x, y, frontBlock).isSolid()) {
                    position.z = frontBlock - WIDTH / 2;
                }
            }
        }

        // Check back (-Z)
        int backBlock = (int) Math.floor(position.z - WIDTH / 2);
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                if (world.getBlock(x, y, backBlock).isSolid()) {
                    position.z = backBlock + 1.0f + WIDTH / 2;
                }
            }
        }
    }
}