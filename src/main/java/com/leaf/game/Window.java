package com.leaf.game;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;

import static org.lwjgl.glfw.Callbacks.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.*;

public class Window {
    private long window;

    // PHASE 2: Mouse tracking state
    // Arrays instead of plain floats because lambdas can only capture final/effectively-final variables.
    // A final array reference is legal; its contents can still change.
    private final double[] lastMouseX = {640.0};
    private final double[] lastMouseY = {360.0};
    private final boolean[] firstMouse = {true};  // ignore the first delta (it's huge)

    private static final float MOUSE_SENSITIVITY = 0.001f;
    private static final float MOVE_SPEED        = 2.0f;   // units per second

    public void run() {
        init();
        loop();

        glfwFreeCallbacks(window);
        glfwDestroyWindow(window);
        glfwTerminate();
        glfwSetErrorCallback(null).free();
    }

    private void init() {
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) throw new IllegalStateException("Unable to initialize GLFW");

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE,              GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE,            GLFW_TRUE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE,        GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);

        window = glfwCreateWindow(1280, 720, "Minecraft Clone", NULL, NULL);
        if (window == NULL) throw new RuntimeException("Failed to create window");

        // Escape still closes the window
        glfwSetKeyCallback(window, (win, key, scancode, action, mods) -> {
            if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE) {
                glfwSetWindowShouldClose(win, true);
            }
        });

        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);
        glfwShowWindow(window);
    }

    private void setupMouseLook(Camera camera) {
        // PHASE 2: Lock cursor to window, hide it
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);

        // PHASE 2: Mouse movement callback — fires whenever mouse moves
        glfwSetCursorPosCallback(window, (win, xpos, ypos) -> {

            // On first callback, just store position without moving camera.
            // The first event can have a huge delta (cursor jumps to center).
            if (firstMouse[0]) {
                lastMouseX[0] = xpos;
                lastMouseY[0] = ypos;
                firstMouse[0] = false;
                return;
            }

            // How much did the mouse move since last callback?
            float dx = (float)(xpos - lastMouseX[0]);
            float dy = (float)(ypos - lastMouseY[0]);
            lastMouseX[0] = xpos;
            lastMouseY[0] = ypos;

            // Apply rotation to camera
            // dx positive = mouse moved right = yaw increases (turn right)
            camera.yaw   += dx * MOUSE_SENSITIVITY;

            // dy positive = mouse moved down (screen Y goes top-to-bottom)
            //   = looking down = pitch decreases
            camera.pitch -= dy * MOUSE_SENSITIVITY;

            // Prevent flipping upside-down
            camera.clampPitch();
        });
    }

    // PHASE 2: Called every frame — reads keyboard, moves camera
    private void processInput(Camera camera, float deltaTime) {
        // glfwGetKey returns GLFW_PRESS if the key is currently held down.
        // This is checked every frame, not event-based.

        if (glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS) {
            // Move in the forward direction (flat, ignores pitch)
            Vector3f forward = camera.getForward();
            camera.position.add(forward.mul(MOVE_SPEED * deltaTime));
        }
        if (glfwGetKey(window, GLFW_KEY_S) == GLFW_PRESS) {
            Vector3f forward = camera.getForward();
            camera.position.sub(forward.mul(MOVE_SPEED * deltaTime));
        }
        if (glfwGetKey(window, GLFW_KEY_D) == GLFW_PRESS) {
            Vector3f right = camera.getRight();
            camera.position.add(right.mul(MOVE_SPEED * deltaTime));
        }
        if (glfwGetKey(window, GLFW_KEY_A) == GLFW_PRESS) {
            Vector3f right = camera.getRight();
            camera.position.sub(right.mul(MOVE_SPEED * deltaTime));
        }

        // Bonus: Space = fly up, Left Shift = fly down
        if (glfwGetKey(window, GLFW_KEY_SPACE) == GLFW_PRESS) {
            camera.position.y += MOVE_SPEED * deltaTime;
        }
        if (glfwGetKey(window, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS) {
            camera.position.y -= MOVE_SPEED * deltaTime;
        }
    }

    private void loop() {
        GL.createCapabilities();
        glEnable(GL_DEPTH_TEST);
        glClearColor(0.5f, 0.7f, 0.9f, 1.0f);

        Shader shader = new Shader(
                "src/main/resources/shaders/vertex.glsl",
                "src/main/resources/shaders/fragment.glsl"
        );

        Camera camera = new Camera(new Vector3f(0.0f, 0.0f, 3.0f));
        // PHASE 2: Start slightly in front of the cube (at z=3, looking toward z=-3)
        // The cube is at (0,0,-3) and camera is now at (0,0,3) — 6 units apart.

        // PHASE 2: Set up mouse capture and rotation callback
        setupMouseLook(camera);

        float[] vertices = {
                // FRONT FACE
                -0.5f, -0.5f,  0.5f,  0.2f, 0.7f, 0.2f,
                0.5f, -0.5f,  0.5f,  0.2f, 0.7f, 0.2f,
                0.5f,  0.5f,  0.5f,  0.2f, 0.7f, 0.2f,
                -0.5f,  0.5f,  0.5f,  0.2f, 0.7f, 0.2f,
                // BACK FACE
                -0.5f, -0.5f, -0.5f,  0.2f, 0.7f, 0.2f,
                0.5f, -0.5f, -0.5f,  0.2f, 0.7f, 0.2f,
                0.5f,  0.5f, -0.5f,  0.2f, 0.7f, 0.2f,
                -0.5f,  0.5f, -0.5f,  0.2f, 0.7f, 0.2f,
                // TOP FACE
                -0.5f,  0.5f,  0.5f,  0.4f, 1.0f, 0.4f,
                0.5f,  0.5f,  0.5f,  0.4f, 1.0f, 0.4f,
                0.5f,  0.5f, -0.5f,  0.4f, 1.0f, 0.4f,
                -0.5f,  0.5f, -0.5f,  0.4f, 1.0f, 0.4f,
                // BOTTOM FACE
                -0.5f, -0.5f,  0.5f,  0.1f, 0.4f, 0.1f,
                0.5f, -0.5f,  0.5f,  0.1f, 0.4f, 0.1f,
                0.5f, -0.5f, -0.5f,  0.1f, 0.4f, 0.1f,
                -0.5f, -0.5f, -0.5f,  0.1f, 0.4f, 0.1f,
                // RIGHT FACE
                0.5f, -0.5f,  0.5f,  0.15f, 0.6f, 0.15f,
                0.5f, -0.5f, -0.5f,  0.15f, 0.6f, 0.15f,
                0.5f,  0.5f, -0.5f,  0.15f, 0.6f, 0.15f,
                0.5f,  0.5f,  0.5f,  0.15f, 0.6f, 0.15f,
                // LEFT FACE
                -0.5f, -0.5f,  0.5f,  0.15f, 0.6f, 0.15f,
                -0.5f, -0.5f, -0.5f,  0.15f, 0.6f, 0.15f,
                -0.5f,  0.5f, -0.5f,  0.15f, 0.6f, 0.15f,
                -0.5f,  0.5f,  0.5f,  0.15f, 0.6f, 0.15f
        };

        int[] indices = {
                0, 1, 2, 2, 3, 0,
                4, 5, 6, 6, 7, 4,
                8, 9, 10, 10, 11, 8,
                12, 13, 14, 14, 15, 12,
                16, 17, 18, 18, 19, 16,
                20, 21, 22, 22, 23, 20
        };

        Mesh mesh = new Mesh(vertices, indices);

        // PHASE 2: Delta time tracking
        double lastTime = glfwGetTime();

        while (!glfwWindowShouldClose(window)) {

            // PHASE 2: Calculate how long the last frame took
            double now = glfwGetTime();
            float deltaTime = (float)(now - lastTime);
            lastTime = now;

            // PHASE 2: Read keyboard input and update camera position
            processInput(camera, deltaTime);

            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

            shader.bind();

            // PHASE 2: Removed spinning. Cube stays still, you move around it.
            Matrix4f model      = new Matrix4f().translate(0.0f, 0.0f, -3.0f);
            Matrix4f view       = camera.getViewMatrix();
            Matrix4f projection = camera.getProjectionMatrix();
            Matrix4f mvp        = new Matrix4f(projection).mul(view).mul(model);

            shader.setUniform("mvp", mvp);
            mesh.render();
            shader.unbind();

            glfwSwapBuffers(window);
            glfwPollEvents();
        }

        mesh.cleanup();
        shader.cleanup();
    }
}