package com.leaf.game;

import org.joml.Matrix4f;
import org.joml.Vector3f;

public class Camera {
    public Vector3f position;

    // Rotation is measured in radians (not degrees)
    public float yaw; //Looking left and right
    public float pitch;// Looking up and down

    // Pitch limits to prevent flipping upside-down
    private static final float MAX_PITCH = (float) Math.toRadians(89.0f);

    public Camera(Vector3f position) {
        this.position = position;
        // In OpenGL math, looking straight ahead means looking down the negative Z-axis.
        // A yaw of -90 degrees points us exactly at -Z.
        this.yaw = (float) Math.toRadians(-90.0f);
        this.pitch = 0.0f;
    }

    //The direction the camera is looking as a 3d vector with length of 1 (used for the view matrix)
    public Vector3f getLookDirection() {
        return new Vector3f(
                (float)(Math.cos(pitch) * Math.cos(yaw)),
                (float)(Math.sin(pitch)),
                (float)(Math.cos(pitch) * Math.sin(yaw))
        ).normalize();
    }

    //The forward direction for movement as a 3d vector with length of 1, based on yaw. Unaffected by pitch.
    public Vector3f getForward() {
        return new Vector3f(
                (float) Math.cos(yaw),
                0.0f,                  // Y is 0: no vertical component
                (float) Math.sin(yaw)
        ).normalize();
    }

    // Right direction for MOVEMENT (perpendicular to forward)
    // Cross product of forward and world-up gives the right vector.
    public Vector3f getRight() {
        Vector3f forward = getForward();
        Vector3f worldUp = new Vector3f(0.0f, 1.0f, 0.0f);
        return forward.cross(worldUp).normalize();
        // Note: JOML's cross() modifies the vector it's called on.
        // forward.cross(worldUp) stores result in forward and returns it.
    }

    //Clamp pitch to prevent flipping upside down
    public void clampPitch() {
        pitch = Math.max(-MAX_PITCH, Math.min(MAX_PITCH, pitch));
    }

    public Matrix4f getViewMatrix() {
        // 1. Figure out the direction we are looking using trigonometry
        Vector3f direction = getLookDirection();

        // 2. The target we are looking AT is our current position + the direction vector
        Vector3f target = new Vector3f(position).add(direction);

        // 3. Define which way is "Up" (Positive Y axis)
        Vector3f up = new Vector3f(0.0f, 1.0f, 0.0f);

        // 4. Create the view matrix using JOML's lookAt()
        return new Matrix4f().lookAt(position, target, up);
    }

    public Matrix4f getProjectionMatrix() {
        // Field of View: 70 degrees
        float fov = (float) Math.toRadians(70.0f);

        // Aspect Ratio: 1280.0 / 720.0 (Matches your Window size!)
        float aspectRatio = 1280.0f/720.0f;

        // Near and Far clipping planes: we don't draw things closer than 0.1 or further than 1000
        float near = 0.1f;
        float far = 1000.0f;

        // Create the projection matrix
        return new Matrix4f().perspective(fov, aspectRatio, near, far);
    }
}