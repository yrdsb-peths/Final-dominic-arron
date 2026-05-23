package com.leaf.game;

import org.joml.Matrix4f;

/**
 * The other player's body — rendered as an orange/red shaded box.
 * Same proportions as the player hitbox: 0.6 wide, 1.8 tall.
 *
 * The box is built once at the origin (0,0,0) and moved each frame
 * by changing the model matrix. This is much cheaper than rebuilding
 * the mesh every frame.
 */
public class RemotePlayer {

    private final Mesh mesh;

    // World position (feet, same convention as Player.position)
    // Updated every frame from NetworkSession.remoteX/Y/Z
    public float x, y, z;

    public RemotePlayer() {
        mesh = buildBox();
    }

    /**
     * Render the other player at their current (x, y, z).
     * Call this inside your game loop, after worldMesh.render().
     * The shader must already be bound.
     */
    public void render(Shader shader, Matrix4f projection, Matrix4f view) {
        // Translate the unit box to the remote player's world position
        Matrix4f model = new Matrix4f().translate(x, y, z);
        Matrix4f mvp   = new Matrix4f(projection).mul(view).mul(model);
        shader.setUniform("mvp", mvp);
        mesh.render();
    }

    public void cleanup() {
        mesh.cleanup();
    }

    // ------------------------------------------------------------------
    // Box geometry — 0.6 wide, 1.8 tall, centered on X/Z, base at Y=0
    // Vertex format: x, y, z, r, g, b  (same as world mesh)
    // ------------------------------------------------------------------

    private Mesh buildBox() {
        float hw = 0.3f;  // half-width  (full = 0.6)
        float h  = 1.8f;  // full height

        // Warm orange, with brightness per face (same shading as world blocks)
        float[] topCol    = {0.95f, 0.55f, 0.1f};
        float[] sideCol   = {0.71f, 0.41f, 0.07f};
        float[] bottomCol = {0.47f, 0.27f, 0.05f};

        float[] v = {
                // TOP (y = h)
                -hw, h, -hw,  topCol[0], topCol[1], topCol[2],
                hw, h, -hw,  topCol[0], topCol[1], topCol[2],
                hw, h,  hw,  topCol[0], topCol[1], topCol[2],
                -hw, h,  hw,  topCol[0], topCol[1], topCol[2],
                // BOTTOM (y = 0)
                -hw, 0,  hw,  bottomCol[0], bottomCol[1], bottomCol[2],
                hw, 0,  hw,  bottomCol[0], bottomCol[1], bottomCol[2],
                hw, 0, -hw,  bottomCol[0], bottomCol[1], bottomCol[2],
                -hw, 0, -hw,  bottomCol[0], bottomCol[1], bottomCol[2],
                // FRONT (+Z)
                -hw, 0,  hw,  sideCol[0], sideCol[1], sideCol[2],
                hw, 0,  hw,  sideCol[0], sideCol[1], sideCol[2],
                hw, h,  hw,  sideCol[0], sideCol[1], sideCol[2],
                -hw, h,  hw,  sideCol[0], sideCol[1], sideCol[2],
                // BACK (-Z)
                hw, 0, -hw,  sideCol[0], sideCol[1], sideCol[2],
                -hw, 0, -hw,  sideCol[0], sideCol[1], sideCol[2],
                -hw, h, -hw,  sideCol[0], sideCol[1], sideCol[2],
                hw, h, -hw,  sideCol[0], sideCol[1], sideCol[2],
                // RIGHT (+X)
                hw, 0, -hw,  sideCol[0], sideCol[1], sideCol[2],
                hw, 0,  hw,  sideCol[0], sideCol[1], sideCol[2],
                hw, h,  hw,  sideCol[0], sideCol[1], sideCol[2],
                hw, h, -hw,  sideCol[0], sideCol[1], sideCol[2],
                // LEFT (-X)
                -hw, 0,  hw,  sideCol[0], sideCol[1], sideCol[2],
                -hw, 0, -hw,  sideCol[0], sideCol[1], sideCol[2],
                -hw, h, -hw,  sideCol[0], sideCol[1], sideCol[2],
                -hw, h,  hw,  sideCol[0], sideCol[1], sideCol[2],
        };

        int[] idx = {
                0,  1,  2,   2,  3,  0,
                4,  5,  6,   6,  7,  4,
                8,  9, 10,  10, 11,  8,
                12, 13, 14,  14, 15, 12,
                16, 17, 18,  18, 19, 16,
                20, 21, 22,  22, 23, 20,
        };

        return new Mesh(v, idx);
    }
}