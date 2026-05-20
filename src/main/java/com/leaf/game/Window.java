package com.leaf.game;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import org.lwjgl.system.*;

import static org.lwjgl.glfw.Callbacks.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.*;

public class Window {
    private long window;

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
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);

        // MAC SPECIFIC: Required for modern OpenGL
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);

        window = glfwCreateWindow(1280, 720, "Minecraft Clone", NULL, NULL);
        if (window == NULL) throw new RuntimeException("Failed to create window");

        glfwSetKeyCallback(window, (window, key, scancode, action, mods) -> {
            if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE) {
                glfwSetWindowShouldClose(window, true);
            }
        });

        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);
        glfwShowWindow(window);
    }

    private void loop() {
        GL.createCapabilities();
        glEnable(GL_DEPTH_TEST);
        glClearColor(0.5f, 0.7f, 0.9f, 1.0f);

        // Path to shaders - double check these files exist in src/main/resources/shaders/
        Shader shader = new Shader(
                "src/main/resources/shaders/vertex.glsl",
                "src/main/resources/shaders/fragment.glsl"
        );

        Camera camera = new Camera(new Vector3f(0.0f, 0.0f, 0.0f));

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
                0, 1, 2, 2, 3, 0,       // Front
                4, 5, 6, 6, 7, 4,       // Back
                8, 9, 10, 10, 11, 8,    // Top
                12, 13, 14, 14, 15, 12, // Bottom
                16, 17, 18, 18, 19, 16, // Right
                20, 21, 22, 22, 23, 20  // Left
        };

        Mesh mesh = new Mesh(vertices, indices);

        while (!glfwWindowShouldClose(window)) {
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

            shader.bind();

            //Matrix4f model = new Matrix4f().translate(0.0f, 0.0f, -3.0f);
            float time = (float) glfwGetTime(); // Get how many seconds have passed since the game started
            Matrix4f model = new Matrix4f().translate(0.0f, 0.0f, -3.0f).rotate(time, new Vector3f(0.5f, 1.0f, 0.0f));
            Matrix4f view = camera.getViewMatrix();
            Matrix4f projection = camera.getProjectionMatrix();
            Matrix4f mvp = new Matrix4f(projection).mul(view).mul(model);

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