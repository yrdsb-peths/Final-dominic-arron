package com.leaf.game;

import org.joml.Matrix4f;
import org.joml.Vector3f;

public class Camera {
    public Vector3f position;

    // Rotation is measured in radians (not degrees)
    public float yaw; //Looking left and right
    public float pitch;// Looking up and down

    public Camera(Vector3f position) {
        this.position = position;

        // In OpenGL math, looking straight ahead means looking down the negative Z-axis.
        // A yaw of -90 degrees points us exactly at -Z.
        this.yaw = (float) Math.toRadians(-90.0f);
        this.pitch = 0.0f;
    }

    public Matrix4f getViewMatrix() {
        // 1. Figure out the direction we are looking using trigonometry
        Vector3f direction = new Vector3f(
                (float) (Math.cos(pitch) * Math.cos(yaw)),
                (float) Math.sin(pitch),
                (float) (Math.cos(pitch) * Math.sin(yaw))
        ).normalize();

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
        float aspectRatio = 1280.0f / 720.0f;

        // Near and Far clipping planes: we don't draw things closer than 0.1 or further than 1000
        float near = 0.1f;
        float far = 1000.0f;

        // Create the projection matrix
        return new Matrix4f().perspective(fov, aspectRatio, near, far);
    }
}