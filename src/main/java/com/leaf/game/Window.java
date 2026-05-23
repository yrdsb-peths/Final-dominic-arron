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
    private WorldGen worldGen;   // field so renderDebugMenu can reach it

    //the last raycast result (updated every frame, used by click handlers)
    private RaycastResult lastTarget = null;

    // MULTIPLAYER
    private NetworkSession network;
    private RemotePlayer   remotePlayer;

    // BLOCK INTERACTION — track mouse button clicks between frames
    private final boolean[] leftClickThisFrame  = {false};
    private final boolean[] rightClickThisFrame = {false};

    // Which block to place (cycle with number keys later)
    private Block selectedBlock = Block.STONE;
    private final double[]  lastMouseX = {640.0};
    private final double[]  lastMouseY = {360.0};
    private final boolean[] firstMouse = {true};

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
            if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE)
                glfwSetWindowShouldClose(win, true);
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

        // Listen for mouse button clicks
        glfwSetMouseButtonCallback(window, (win, button, action, mods) -> {
            if (action == GLFW_PRESS) {
                if (button == GLFW_MOUSE_BUTTON_LEFT)  leftClickThisFrame[0]  = true;
                if (button == GLFW_MOUSE_BUTTON_RIGHT) rightClickThisFrame[0] = true;
            }
        });
        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);
        glfwShowWindow(window);

        ImGui.createContext();
        imguiGlfw.init(window, true);
    }

    private void setupMouseLook(Camera camera) {
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);

        glfwSetCursorPosCallback(window, (win, xpos, ypos) -> {
            if (showDebug) return;

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
                // BREAK
                world.setBlock(lastTarget.hitX, lastTarget.hitY, lastTarget.hitZ, Block.AIR);
                markNeighborChunksDirty(lastTarget.hitX, lastTarget.hitY, lastTarget.hitZ);

                // MULTIPLAYER: Tell friend we broke a block
                if (network != null && network.connected) {
                    network.sendBreak(lastTarget.hitX, lastTarget.hitY, lastTarget.hitZ);
                }
            }

            if (button == GLFW_MOUSE_BUTTON_RIGHT) {
                // PLACE
                if (!playerOccupies(lastTarget.placeX, lastTarget.placeY, lastTarget.placeZ)) {
                    world.setBlock(lastTarget.placeX, lastTarget.placeY, lastTarget.placeZ, selectedBlock);
                    markNeighborChunksDirty(lastTarget.placeX, lastTarget.placeY, lastTarget.placeZ);

                    // MULTIPLAYER: Tell friend we placed a block
                    if (network != null && network.connected) {
                        network.sendPlace(lastTarget.placeX, lastTarget.placeY, lastTarget.placeZ, selectedBlock);
                    }
                }
            }
        });
    }

    private void loop() {
        // ── 1. WAKE UP OPENGL ──
        GL.createCapabilities();
        imguiGl3.init("#version 330");

        glEnable(GL_DEPTH_TEST);
        glClearColor(0.5f, 0.7f, 0.9f, 1.0f);

        Shader shader = new Shader(
                "src/main/resources/shaders/vertex.glsl",
                "src/main/resources/shaders/fragment.glsl"
        );

        Camera camera = new Camera();
        setupMouseLook(camera);

        this.player   = new Player(16.0f, 60.0f, 16.0f);
        this.world    = new World();
        this.worldGen = new WorldGen();

        // ── MULTIPLAYER SETUP ──
        // Host: new NetworkSession(true, null)
        // Join: new NetworkSession(false, "HOST_IP_HERE")
        network = new NetworkSession(true, null);
        network.start();
        RemotePlayer remotePlayer = new RemotePlayer();

        world.updateChunks(world, worldGen, player);
        Matrix4f model = new Matrix4f();
        double lastTime = glfwGetTime();

        while (!glfwWindowShouldClose(window)) {
            double now       = glfwGetTime();
            float  deltaTime = (float)(now - lastTime);
            lastTime = now;

            player.update(window, camera, world, deltaTime);

            // ── MULTIPLAYER SYNC ──
            if (network != null && network.connected) {
                // 1. Send our position
                network.sendPosition(player.position.x, player.position.y, player.position.z, camera.yaw, camera.pitch);

                // 2. Update friend's body position
                remotePlayer.x = network.remoteX;
                remotePlayer.y = network.remoteY;
                remotePlayer.z = network.remoteZ;

                // 3. Process blocks friend broke
                int[] brk = network.pollBreak();
                if (brk != null) {
                    world.setBlock(brk[0], brk[1], brk[2], Block.AIR);
                    markNeighborChunksDirty(brk[0], brk[1], brk[2]);
                }

                // 4. Process blocks friend placed
                int[] plc = network.pollPlace();
                if (plc != null) {
                    Block placedBlock = Block.values()[plc[3]]; // Convert ordinal back to Block enum
                    world.setBlock(plc[0], plc[1], plc[2], placedBlock);
                    markNeighborChunksDirty(plc[0], plc[1], plc[2]);
                }
            }

            lastTarget = player.getTargetBlock(camera, world);
            world.updateChunks(world, worldGen, player);

            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

            shader.bind();
            shader.setUniform("sunDirection",    new org.joml.Vector3f(GameConfig.sunDirX, GameConfig.sunDirY, GameConfig.sunDirZ));
            shader.setUniform("sunStrength",     GameConfig.sunStrength);
            shader.setUniform("ambientStrength", GameConfig.ambientStrength);
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
            // MULTIPLAYER: render friend's body
            if (network != null && network.connected) {
                remotePlayer.render(shader, projection, view);
            }
            shader.unbind();

            // ── IMGUI FRAME ──────────────────────────────────
            imguiGlfw.newFrame();
            ImGui.newFrame();
            renderHUD();                          // ← always shown
            if (showDebug) renderDebugMenu();
            ImGui.render();
            imguiGl3.renderDrawData(ImGui.getDrawData());

            glfwSwapBuffers(window);
            glfwPollEvents();
        }

        for (Chunk chunk : world.getAllChunks()) {
            if (chunk.mesh != null) chunk.mesh.cleanup();
        }
        shader.cleanup();
        imguiGl3.dispose();
        imguiGlfw.dispose();
        ImGui.destroyContext();
        remotePlayer.cleanup();
    }

    private void renderDebugMenu() {
        ImGui.begin("Settings");

        // ── INFO ─────────────────────────────────────────────────────────────
        ImGui.text("Chunks loaded: " + world.getAllChunks().size());
        ImGui.text(String.format("Position: %.1f  %.1f  %.1f",
                player.position.x, player.position.y, player.position.z));
        ImGui.text("Debug/fly mode: " + (player.debugMode ? "ON" : "OFF"));
        ImGui.spacing();

        // ── CAMERA ───────────────────────────────────────────────────────────
        ImGui.text("Camera");
        ImGui.separator();
        float[] fov = { GameConfig.fov };
        if (ImGui.sliderFloat("FOV", fov, 30f, 120f))
            GameConfig.fov = fov[0];

        float[] sens = { GameConfig.mouseSensitivity };
        if (ImGui.sliderFloat("Sensitivity", sens, 0.0001f, 0.005f))
            GameConfig.mouseSensitivity = sens[0];

        // ── WORLD ────────────────────────────────────────────────────────────
        ImGui.text("World");
        ImGui.separator();
        int[] rd = { GameConfig.renderDistance };
        if (ImGui.sliderInt("Render Distance", rd, 2, 16))
            GameConfig.renderDistance = rd[0];

        // ── CONTINENTALNESS ──────────────────────────────────────────────────
        ImGui.text("Continentalness");
        ImGui.separator();
        float[] cf = { GameConfig.contFreq };
        if (ImGui.sliderFloat("Frequency##cont", cf, 0.001f, 0.05f))
            GameConfig.contFreq = cf[0];
        int[] co = { GameConfig.contOctaves };
        if (ImGui.sliderInt("Octaves##cont", co, 1, 8))
            GameConfig.contOctaves = co[0];
        float[] cp = { GameConfig.contPersist };
        if (ImGui.sliderFloat("Persistence##cont", cp, 0.1f, 0.9f))
            GameConfig.contPersist = cp[0];

        // ── EROSION ──────────────────────────────────────────────────────────
        ImGui.text("Erosion");
        ImGui.separator();
        float[] ef = { GameConfig.erosFreq };
        if (ImGui.sliderFloat("Frequency##eros", ef, 0.001f, 0.05f))
            GameConfig.erosFreq = ef[0];
        int[] eo = { GameConfig.erosOctaves };
        if (ImGui.sliderInt("Octaves##eros", eo, 1, 8))
            GameConfig.erosOctaves = eo[0];
        float[] ep = { GameConfig.erosPersist };
        if (ImGui.sliderFloat("Persistence##eros", ep, 0.1f, 0.9f))
            GameConfig.erosPersist = ep[0];

        // ── PEAKS & VALLEYS ──────────────────────────────────────────────────
        ImGui.text("Peaks and Valleys");
        ImGui.separator();
        float[] pf = { GameConfig.pvFreq };
        if (ImGui.sliderFloat("Frequency##pv", pf, 0.001f, 0.05f))
            GameConfig.pvFreq = pf[0];
        int[] po = { GameConfig.pvOctaves };
        if (ImGui.sliderInt("Octaves##pv", po, 1, 8))
            GameConfig.pvOctaves = po[0];
        float[] pp = { GameConfig.pvPersist };
        if (ImGui.sliderFloat("Persistence##pv", pp, 0.1f, 0.9f))
            GameConfig.pvPersist = pp[0];

        // ── HEIGHT ───────────────────────────────────────────────────────────
        ImGui.text("Height");
        ImGui.separator();
        int[] hb = { GameConfig.heightBase };
        if (ImGui.sliderInt("Base Y", hb, 0, 30))
            GameConfig.heightBase = hb[0];
        int[] hr = { GameConfig.heightRange };
        if (ImGui.sliderInt("Range", hr, 10, Chunk.HEIGHT - GameConfig.heightBase))
            GameConfig.heightRange = hr[0];

        // ── 3D DENSITY ───────────────────────────────────────────────────────
        ImGui.text("3D Density Noise");
        ImGui.separator();
        float[] df = { GameConfig.density3DFreq };
        if (ImGui.sliderFloat("Frequency##d3d", df, 0.01f, 0.15f))
            GameConfig.density3DFreq = df[0];
        float[] dvc = { GameConfig.density3DVerticalCompress };
        if (ImGui.sliderFloat("Vertical Compress", dvc, 0.1f, 2.0f))
            GameConfig.density3DVerticalCompress = dvc[0];
        int[] dOct = { GameConfig.density3DOctaves };
        if (ImGui.sliderInt("Octaves##d3d", dOct, 1, 6))
            GameConfig.density3DOctaves = dOct[0];
        float[] dp = { GameConfig.density3DPersist };
        if (ImGui.sliderFloat("Persistence##d3d", dp, 0.1f, 0.9f))
            GameConfig.density3DPersist = dp[0];
        float[] da = { GameConfig.density3DAmplitude };
        if (ImGui.sliderFloat("Amplitude", da, 0f, 30f))
            GameConfig.density3DAmplitude = da[0];

        // ── DENSITY SHAPE ────────────────────────────────────────────────────
        ImGui.text("Density Shape");
        ImGui.separator();
        float[] dvs = { GameConfig.densityVerticalScale };
        if (ImGui.sliderFloat("Vertical Scale", dvs, 0.01f, 0.5f))
            GameConfig.densityVerticalScale = dvs[0];
        float[] deb = { GameConfig.densityErosionBoost };
        if (ImGui.sliderFloat("Erosion Boost", deb, 0.0f, 0.5f))
            GameConfig.densityErosionBoost = deb[0];
        ImGui.text("Lighting");
        ImGui.separator();
        float[] sdx = { GameConfig.sunDirX };
        if (ImGui.sliderFloat("Sun X", sdx, -1f, 1f)) GameConfig.sunDirX = sdx[0];
        float[] sdy = { GameConfig.sunDirY };
        if (ImGui.sliderFloat("Sun Y", sdy, 0f, 1f))  GameConfig.sunDirY = sdy[0];
        float[] sdz = { GameConfig.sunDirZ };
        if (ImGui.sliderFloat("Sun Z", sdz, -1f, 1f)) GameConfig.sunDirZ = sdz[0];
        float[] ss = { GameConfig.sunStrength };
        if (ImGui.sliderFloat("Sun Strength", ss, 0f, 1f))     GameConfig.sunStrength = ss[0];
        float[] as = { GameConfig.ambientStrength };
        if (ImGui.sliderFloat("Ambient", as, 0f, 1f))           GameConfig.ambientStrength = as[0];

        // ── REGENERATE ───────────────────────────────────────────────────────
        ImGui.text("Rebuild");
        ImGui.separator();
        if (ImGui.button("Regenerate World"))
            regenerateWorld();

        ImGui.end();
    }

    private void regenerateWorld() {
        for (Chunk chunk : world.getAllChunks()) {
            if (chunk.mesh != null) chunk.mesh.cleanup();
        }
        world.clearAllChunks();
        worldGen.resetSeed(GameConfig.seed);
        player.position.y = 60.0f;
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
}