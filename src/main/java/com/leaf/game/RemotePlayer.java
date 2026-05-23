package com.leaf.game;

import org.joml.Matrix4f;
import java.util.ArrayList;
import java.util.List;

public class RemotePlayer {

    private final Mesh mesh;

    // Actual visual position
    public float x, y, z;
    public float yaw;

    // Target position from network
    public float targetX, targetY, targetZ;
    public float targetYaw;

    public RemotePlayer() {
        mesh = buildSteveModel();
    }

    // LERP (Linear Interpolation) smooths out the network stutter!
    public void update(float deltaTime) {
        float lerpSpeed = 15.0f; // Higher = snappier, Lower = floatier
        x += (targetX - x) * lerpSpeed * deltaTime;
        y += (targetY - y) * lerpSpeed * deltaTime;
        z += (targetZ - z) * lerpSpeed * deltaTime;

        // Smooth rotation
        yaw += (targetYaw - yaw) * lerpSpeed * deltaTime;
    }

    public void render(Shader shader, Matrix4f projection, Matrix4f view) {
        // Translate to position, then rotate body based on yaw
        Matrix4f model = new Matrix4f()
                .translate(x, y, z)
                .rotateY(yaw);

        Matrix4f mvp = new Matrix4f(projection).mul(view).mul(model);
        shader.setUniform("mvp", mvp);
        mesh.render();
    }

    public void cleanup() {
        mesh.cleanup();
    }

    // --- Builds a hierarchical "Steve" out of colored boxes ---
    private Mesh buildSteveModel() {
        List<Float> verts = new ArrayList<>();
        List<Integer> idx = new ArrayList<>();
        int[] vIndex = {0};

        // Colors
        float[] skin  = {0.90f, 0.67f, 0.52f}; // Peach
        float[] shirt = {0.00f, 0.66f, 0.66f}; // Cyan
        float[] pants = {0.17f, 0.17f, 0.58f}; // Blue
        float[] shoes = {0.25f, 0.25f, 0.25f}; // Grey

        // Legs (Y: 0 to 0.75)
        addBox(verts, idx, vIndex, -0.2f, 0.0f, -0.1f, 0.2f, 0.75f, 0.1f, pants);
        // Torso (Y: 0.75 to 1.5)
        addBox(verts, idx, vIndex, -0.2f, 0.75f, -0.1f, 0.2f, 1.5f, 0.1f, shirt);
        // Head (Y: 1.5 to 1.9)
        addBox(verts, idx, vIndex, -0.2f, 1.5f, -0.2f, 0.2f, 1.9f, 0.2f, skin);
        // Arms (Attached to torso sides)
        addBox(verts, idx, vIndex, -0.3f, 0.75f, -0.1f, -0.2f, 1.5f, 0.1f, shirt); // Left arm
        addBox(verts, idx, vIndex,  0.2f, 0.75f, -0.1f,  0.3f, 1.5f, 0.1f, shirt); // Right arm

        // Convert lists to arrays
        float[] vArr = new float[verts.size()];
        for (int i = 0; i < verts.size(); i++) vArr[i] = verts.get(i);
        int[] iArr = new int[idx.size()];
        for (int i = 0; i < idx.size(); i++) iArr[i] = idx.get(i);

        return new Mesh(vArr, iArr);
    }

    // Helper to generate a 3D box and append it to our Mesh lists
    private void addBox(List<Float> verts, List<Integer> idx, int[] vIndex,
                        float minX, float minY, float minZ,
                        float maxX, float maxY, float maxZ, float[] col) {

        float[][] corners = {
                {minX, minY, maxZ}, {maxX, minY, maxZ}, {maxX, maxY, maxZ}, {minX, maxY, maxZ}, // Front
                {maxX, minY, minZ}, {minX, minY, minZ}, {minX, maxY, minZ}, {maxX, maxY, minZ}, // Back
                {minX, maxY, minZ}, {maxX, maxY, minZ}, {maxX, maxY, maxZ}, {minX, maxY, maxZ}, // Top
                {minX, minY, maxZ}, {maxX, minY, maxZ}, {maxX, minY, minZ}, {minX, minY, minZ}, // Bottom
                {maxX, minY, maxZ}, {maxX, minY, minZ}, {maxX, maxY, minZ}, {maxX, maxY, maxZ}, // Right
                {minX, minY, minZ}, {minX, minY, maxZ}, {minX, maxY, maxZ}, {minX, maxY, minZ}  // Left
        };

        for (int face = 0; face < 6; face++) {
            // Give top/sides different brightness
            float shade = (face == 2) ? 1.0f : (face == 3 ? 0.5f : 0.8f);

            for (int i = 0; i < 4; i++) {
                float[] corner = corners[face * 4 + i];
                verts.add(corner[0]); verts.add(corner[1]); verts.add(corner[2]);
                verts.add(col[0]*shade); verts.add(col[1]*shade); verts.add(col[2]*shade);
                verts.add(0f); verts.add(1f); verts.add(0f); // Dummy normals
            }
            int b = vIndex[0];
            idx.add(b); idx.add(b+1); idx.add(b+2); idx.add(b+2); idx.add(b+3); idx.add(b);
            vIndex[0] += 4;
        }
    }
}