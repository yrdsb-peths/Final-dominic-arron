package com.leaf.game;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;

import static org.lwjgl.glfw.Callbacks.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.*;

import imgui.ImGui;
import imgui.gl3.ImGuiImplGl3;
import imgui.glfw.ImGuiImplGlfw;

public class Window {
    private long window;
    private Player player;
    private World world;

    private final double[]  lastMouseX = {640.0};
    private final double[]  lastMouseY = {360.0};
    private final boolean[] firstMouse = {true};
    private Block selectedBlock = Block.GRASS;
    private RaycastResult lastTarget = null;

    // ImGui — glfw can init early, gl3 must wait until GL.createCapabilities() has run
    private final ImGuiImplGlfw imguiGlfw = new ImGuiImplGlfw();
    private final ImGuiImplGl3  imguiGl3  = new ImGuiImplGl3();
    private boolean showDebug = false;

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
        glfwWindowHint(GLFW_VISIBLE,               GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE,             GLFW_TRUE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE,        GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);

        window = glfwCreateWindow(1280, 720, "Minecraft Clone", NULL, NULL);
        if (window == NULL) throw new RuntimeException("Failed to create window");

        glfwSetKeyCallback(window, (win, key, scancode, action, mods) -> {
            if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE) {
                glfwSetWindowShouldClose(win, true);
            }
            if (key == GLFW_KEY_F3 && action == GLFW_RELEASE) {
                showDebug = !showDebug;
                glfwSetInputMode(window, GLFW_CURSOR,
                        showDebug ? GLFW_CURSOR_NORMAL : GLFW_CURSOR_DISABLED);
            }
            // Block selection hotbar (keys 1–4)
            if (action == GLFW_PRESS) {
                if (key == GLFW_KEY_1) selectedBlock = Block.GRASS;
                if (key == GLFW_KEY_2) selectedBlock = Block.DIRT;
                if (key == GLFW_KEY_3) selectedBlock = Block.STONE;
                // Add more block types here as you add them to Block.java
            }
        });

        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);
        glfwShowWindow(window);

        // ImGui context + GLFW backend can start now (no GL calls yet)
        ImGui.createContext();
        imguiGlfw.init(window, true); // 'true' = chain our key callback above
    }

    private void setupMouseLook(Camera camera) {
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);

        glfwSetCursorPosCallback(window, (win, xpos, ypos) -> {
            if (showDebug) return; // don't rotate camera while menu is open

            if (firstMouse[0]) {
                lastMouseX[0] = xpos;
                lastMouseY[0] = ypos;
                firstMouse[0] = false;
                return;
            }

            float dx = (float)(xpos - lastMouseX[0]);
            float dy = (float)(ypos - lastMouseY[0]);
            lastMouseX[0] = xpos;
            lastMouseY[0] = ypos;

            camera.yaw   += dx * GameConfig.mouseSensitivity;
            camera.pitch -= dy * GameConfig.mouseSensitivity;
            camera.clampPitch();
        });

        glfwSetMouseButtonCallback(window, (win, button, action, mods) -> {
            // Only act when the debug menu is closed (cursor is captured)
            if (showDebug) return;

            // Only on press (not release)
            if (action != GLFW_PRESS) return;

            if (lastTarget == null || !lastTarget.hit) return;

            if (button == GLFW_MOUSE_BUTTON_LEFT) {
                // BREAK: replace the hit block with air
                world.setBlock(lastTarget.hitX, lastTarget.hitY, lastTarget.hitZ, Block.AIR);

                // If the broken block was on the border between two chunks,
                // mark the neighbor chunk dirty too so its exposed face appears.
                markNeighborChunksDirty(lastTarget.hitX, lastTarget.hitY, lastTarget.hitZ);
            }

            if (button == GLFW_MOUSE_BUTTON_RIGHT) {
                // PLACE: put selected block in the air position adjacent to the hit face
                // Safety check: don't place inside the player's own bounding box
                if (!playerOccupies(lastTarget.placeX, lastTarget.placeY, lastTarget.placeZ)) {
                    world.setBlock(lastTarget.placeX, lastTarget.placeY, lastTarget.placeZ, selectedBlock);
                    markNeighborChunksDirty(lastTarget.placeX, lastTarget.placeY, lastTarget.placeZ);
                }
            }
        });

    }

    private void loop() {
        // GL capabilities must be created before any GL call — including ImGui's GL3 backend
        GL.createCapabilities();
        imguiGl3.init("#version 330"); // <-- HERE, after createCapabilities(), not in init()

        glEnable(GL_DEPTH_TEST);
        glClearColor(0.5f, 0.7f, 0.9f, 1.0f);

        Shader shader = new Shader(
                "src/main/resources/shaders/vertex.glsl",
                "src/main/resources/shaders/fragment.glsl"
        );

        Camera camera = new Camera();
        setupMouseLook(camera);

        this.player  = new Player(16.0f, 60.0f, 16.0f);
        this.world   = new World();
        WorldGen worldGen = new WorldGen();
        world.updateChunks(world, worldGen, player);

        Matrix4f model    = new Matrix4f();
        double   lastTime = glfwGetTime();

        while (!glfwWindowShouldClose(window)) {
            double now       = glfwGetTime();
            float  deltaTime = (float)(now - lastTime);
            lastTime = now;

            player.update(window, camera, world, deltaTime);
            lastTarget = player.getTargetBlock(camera, world);
            world.updateChunks(world, worldGen, player);

            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

            shader.bind();
            Matrix4f view       = camera.getViewMatrix();
            Matrix4f projection = camera.getProjectionMatrix();

            for (Chunk chunk : world.getAllChunks()) {
                if (chunk.dirty) {
                    if (chunk.mesh != null) chunk.mesh.cleanup();
                    chunk.mesh = world.buildChunkMesh(chunk);
                    chunk.dirty = false;
                }
                if (chunk.mesh != null) {
                    Matrix4f mvp = new Matrix4f(projection).mul(view).mul(model);
                    shader.setUniform("mvp", mvp);
                    chunk.mesh.render();
                }
            }
            shader.unbind();

            // ImGui frame
            imguiGlfw.newFrame();
            ImGui.newFrame();
            if (showDebug) renderDebugMenu();
            ImGui.render();
            imguiGl3.renderDrawData(ImGui.getDrawData());
            imguiGlfw.newFrame();
            ImGui.newFrame();
            renderHUD();                          // ← always shown
            if (showDebug) renderDebugMenu();
            ImGui.render();
            imguiGl3.renderDrawData(ImGui.getDrawData());

            glfwSwapBuffers(window);
            glfwPollEvents();
        }

        // Cleanup
        for (Chunk chunk : world.getAllChunks()) {
            if (chunk.mesh != null) chunk.mesh.cleanup();
        }
        shader.cleanup();

        imguiGl3.dispose();   // <-- dispose(), not shutdown()
        imguiGlfw.dispose();  // <-- dispose(), not shutdown()
        ImGui.destroyContext();
    }

    private void renderDebugMenu() {
        ImGui.begin("Settings");  // Simple window title

        // ── FOV SLIDER ───────────────────────────────────────────
        float[] fov = { GameConfig.fov };
        if (ImGui.sliderFloat("FOV", fov, 30f, 120f)) {
            GameConfig.fov = fov[0];
        }

        // ── RENDER DISTANCE SLIDER ──────────────────────────────────
        int[] rd = { GameConfig.renderDistance };
        if (ImGui.sliderInt("Render Distance", rd, 2, 16)) {
            GameConfig.renderDistance = rd[0];
        }

        ImGui.end();
    }

    private void regenerateWorld(World world, WorldGen worldGen) {
        for (Chunk chunk : world.getAllChunks()) {
            if (chunk.mesh != null) chunk.mesh.cleanup();
        }
        world.clearAllChunks();
        worldGen.resetSeed(GameConfig.seed);
        player.position.y = 60.0f;
    }

    private void renderHUD() {
        // Use ImGui's background draw list — draws in screen space, always on top,
        // without needing a separate OpenGL pass.
        var draw = ImGui.getForegroundDrawList();

        // Crosshair — two lines crossing at screen center
        float cx = 1280 / 2.0f;
        float cy = 720  / 2.0f;
        float size = 10.0f;
        int white = ImGui.colorConvertFloat4ToU32(1, 1, 1, 0.9f);
        int black = ImGui.colorConvertFloat4ToU32(0, 0, 0, 0.6f);

        // Draw a dark outline first, then white on top (visible on any background)
        draw.addLine(cx - size - 1, cy, cx + size + 1, cy, black, 3.0f);
        draw.addLine(cx, cy - size - 1, cx, cy + size + 1, black, 3.0f);
        draw.addLine(cx - size, cy, cx + size, cy, white, 1.5f);
        draw.addLine(cx, cy - size, cx, cy + size, white, 1.5f);

        // Selected block indicator (bottom center)
        String blockName = selectedBlock.name();
        ImGui.setNextWindowPos(640 - 60, 700);
        ImGui.setNextWindowSize(120, 22);
        ImGui.begin("##hud", imgui.flag.ImGuiWindowFlags.NoDecoration
                | imgui.flag.ImGuiWindowFlags.NoBackground
                | imgui.flag.ImGuiWindowFlags.NoMove
                | imgui.flag.ImGuiWindowFlags.NoInputs);
        ImGui.text("[ " + blockName + " ]");
        ImGui.end();
    }

    /**
     * When a block on a chunk border is modified, the neighboring chunk's
     * mesh is now wrong — it was built without knowing this block was air.
     * Mark neighbors dirty so they rebuild their exposed faces.
     */
    private void markNeighborChunksDirty(int wx, int wy, int wz) {
        // Check all 6 neighbors of the modified block
        int[][] neighbors = {
                {wx+1, wy, wz}, {wx-1, wy, wz},
                {wx, wy+1, wz}, {wx, wy-1, wz},
                {wx, wy, wz+1}, {wx, wy, wz-1}
        };

        for (int[] n : neighbors) {
            int ncx = Math.floorDiv(n[0], Chunk.SIZE);
            int ncz = Math.floorDiv(n[2], Chunk.SIZE);
            Chunk neighbor = world.getChunk(ncx, ncz);
            if (neighbor != null) neighbor.dirty = true;
        }
    }

    /**
     * Returns true if the given block position overlaps the player's body.
     * Prevents placing a block inside yourself (which traps you).
     */
    private boolean playerOccupies(int bx, int by, int bz) {
        float px = player.position.x;
        float py = player.position.y;
        float pz = player.position.z;
        float halfW = 0.3f;   // half of player width (0.6 / 2)
        float height = 1.8f;

        // Does the block box (bx to bx+1, etc.) overlap the player box?
        return px + halfW > bx && px - halfW < bx + 1
                && py + height > by && py           < by + 1
                && pz + halfW > bz && pz - halfW < bz + 1;
    }
}