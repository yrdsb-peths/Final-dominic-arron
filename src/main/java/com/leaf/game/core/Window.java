// --- FILE: src/main/java/com/leaf/game/core/Window.java ---
package com.leaf.game.core;

import com.leaf.game.entity.DroppedItem;
import com.leaf.game.entity.Inventory;
import com.leaf.game.entity.Player;
import com.leaf.game.entity.RemotePlayer;
import com.leaf.game.net.NetworkSession;
import com.leaf.game.render.Mesh;
import com.leaf.game.render.Shader;
import com.leaf.game.util.Camera;
import com.leaf.game.util.NoiseVisualizer;
import com.leaf.game.util.RaycastResult;
import com.leaf.game.world.Block;
import com.leaf.game.world.Chunk;
import com.leaf.game.world.World;
import com.leaf.game.world.gen.WorldGen;
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
import imgui.type.ImString;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

public class Window {
    private long window;
    private Player player;
    private World world;
    private WorldGen worldGen;

    private NoiseVisualizer noiseVis;
    private boolean showNoiseViewer = false;
    private RaycastResult lastTarget = null;

    private NetworkSession network;
    private RemotePlayer remotePlayer;

    private boolean networkInitialized = false;
    private final ImString ipInput = new ImString("127.0.0.1", 64);

    private final double[]  lastMouseX = {640.0};
    private final double[]  lastMouseY = {360.0};
    private final boolean[] firstMouse = {true};

    private final ImGuiImplGlfw imguiGlfw = new ImGuiImplGlfw();
    private final ImGuiImplGl3  imguiGl3  = new ImGuiImplGl3();

    // UI STATES
    private boolean isPaused  = false;
    private boolean showDebug = false;
    private boolean showChat  = false;
    private final ImString chatInput = new ImString(256);
    private final List<String> chatHistory = new ArrayList<>();

    private final Inventory inventory = new Inventory();
    private int selectedSlot = 0;
    private Block selectedBlock = Block.AIR;

    // Fixed 9 Slots
    private final Block[] hotbar = {
            Block.GRASS, Block.DIRT, Block.STONE, Block.WATER,
            Block.AIR, Block.AIR, Block.AIR, Block.AIR, Block.AIR
    };

    private final List<DroppedItem> droppedItems = new ArrayList<>();
    private final Map<Block, Mesh> itemMeshes = new HashMap<>();

    private float  breakProgress = 0.0f;
    private int    breakX, breakY, breakZ;
    private boolean breakingActive = false;

    // ── PRE-GENERATION STATES ────────────────────────────────────────────────
    private boolean isPreloading = false;
    private int preloadRadius = 10; // Can be set up to 100 now!
    private final List<Chunk> chunksToGenerate = new ArrayList<>();
    private int totalPreloadCount = 0;
    private int currentPreloadProgress = 0;

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
            if (!networkInitialized || isPreloading) return;

            if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE) {
                if (showChat) {
                    showChat = false;
                    glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
                } else if (showNoiseViewer) {
                    showNoiseViewer = false;
                    glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
                } else {
                    isPaused = !isPaused;
                    glfwSetInputMode(window, GLFW_CURSOR, isPaused ? GLFW_CURSOR_NORMAL : GLFW_CURSOR_DISABLED);
                }
            }

            if (isPaused) return;

            if (key == GLFW_KEY_F3 && action == GLFW_RELEASE && !showChat) {
                showDebug = !showDebug;
                glfwSetInputMode(window, GLFW_CURSOR, (showDebug || showNoiseViewer) ? GLFW_CURSOR_NORMAL : GLFW_CURSOR_DISABLED);
            }

            if (key == GLFW_KEY_F4 && action == GLFW_RELEASE && !showChat) {
                showNoiseViewer = !showNoiseViewer;
                glfwSetInputMode(window, GLFW_CURSOR, (showDebug || showNoiseViewer) ? GLFW_CURSOR_NORMAL : GLFW_CURSOR_DISABLED);
            }

            if (key == GLFW_KEY_T && action == GLFW_RELEASE && !showChat && !showDebug && !isPaused) {
                showChat = true;
                glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
            }

            if (action == GLFW_PRESS && !showChat && !showDebug) {
                if (key >= GLFW_KEY_1 && key <= GLFW_KEY_9) {
                    selectedSlot = key - GLFW_KEY_1;
                }
            }
        });

        glfwSetMouseButtonCallback(window, (win, button, action, mods) -> {
            if (!networkInitialized || isPreloading || showDebug || showChat || showNoiseViewer || isPaused) return;

            if (button == GLFW_MOUSE_BUTTON_LEFT) {
                breakingActive = (action == GLFW_PRESS || action == GLFW_REPEAT);
                if (action == GLFW_RELEASE) breakProgress = 0.0f;
            }

            if (button == GLFW_MOUSE_BUTTON_RIGHT && action == GLFW_PRESS) {
                if (lastTarget != null && lastTarget.hit && selectedBlock != Block.AIR) {
                    if (!playerOccupies(lastTarget.placeX, lastTarget.placeY, lastTarget.placeZ) &&
                            !remotePlayerOccupies(lastTarget.placeX, lastTarget.placeY, lastTarget.placeZ)) {

                        if (inventory.useBlock(selectedBlock)) {
                            world.setBlock(lastTarget.placeX, lastTarget.placeY, lastTarget.placeZ, selectedBlock);
                            // Immediately rebuild the mesh so the block appears at once
                            world.rebuildChunkAt(lastTarget.placeX, lastTarget.placeY, lastTarget.placeZ);
                            if (network != null && network.connected) {
                                network.sendPlace(lastTarget.placeX, lastTarget.placeY, lastTarget.placeZ, selectedBlock);
                            }
                        }
                    }
                }
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
            if (!networkInitialized || isPreloading || showDebug || showChat || showNoiseViewer || isPaused) return;

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
    }

    private void startPreload() {
        worldGen.resetSeed(GameConfig.seed);
        world.clearAllChunks();
        world.meshingQueue.clear();

        networkInitialized = true;
        isPreloading = true; // Turn on the loading screen
    }

    private void loop() {
        GL.createCapabilities();
        imguiGl3.init("#version 330");

        glEnable(GL_DEPTH_TEST);
        glClearColor(0.5f, 0.7f, 0.9f, 1.0f);

        Shader shader = new Shader("src/main/resources/shaders/vertex.glsl", "src/main/resources/shaders/fragment.glsl");
        Camera camera = new Camera();
        setupMouseLook(camera);

        this.player   = new Player(16.0f, 100.0f, 16.0f);
        this.world    = new World();
        this.worldGen = new WorldGen();
        this.noiseVis = new NoiseVisualizer(worldGen);

        Matrix4f model = new Matrix4f();
        double lastTime = glfwGetTime();

        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);

        while (!glfwWindowShouldClose(window)) {
            double now       = glfwGetTime();
            float  deltaTime = (float)(now - lastTime);

            // ── FRAME RATE LIMITER & DELTA CLAMP ─────────────────────────────────────
            // Capping the physics loop at 120 FPS prevents extreme hardware/driver
            // overruns and keeps physics and mouse-movement perfectly consistent.
            float targetMinFrameTime = 1.0f / 120.0f;
            if (deltaTime < targetMinFrameTime) {
                try {
                    // Let the CPU rest to maintain a smooth 120 FPS target
                    Thread.sleep((long) ((targetMinFrameTime - deltaTime) * 1000));
                } catch (InterruptedException ignored) {}
                now = glfwGetTime();
                deltaTime = (float)(now - lastTime);
            }

            // Clamp delta-time at 100ms max to prevent physics explosions during lag spikes
            deltaTime = Math.min(deltaTime, 0.1f);
            lastTime = now;

            int[] ww = new int[1], wh = new int[1];
            glfwGetWindowSize(window, ww, wh);

            if (networkInitialized) {
                // ── 1. PRE-LOAD WORKER (Generates chunks frame-by-frame) ──
                // ── 1. TWO-PHASE PRE-LOAD WORKER ──
                // ── 1. ASYNC MESH DRAINER ──
                // Process finished chunks from the background threads.
                // Do more per frame if we are on the loading screen to speed it up.
                // ── 1. ASYNC MESH DRAINER ──
                int maxMeshesPerFrame = isPreloading ? 12 : 3;
                int meshedThisFrame = 0;
                Chunk readyChunk;
                while (meshedThisFrame < maxMeshesPerFrame && (readyChunk = world.meshingQueue.poll()) != null) {
                    world.buildChunkMeshes(readyChunk);
                    readyChunk.state = Chunk.ChunkState.MESHED;
                    meshedThisFrame++;
                }

                // ── 2. PREVENT FALLING THROUGH WORLD ──
                int pCX = Math.floorDiv((int)player.position.x, Chunk.SIZE);
                int pCZ = Math.floorDiv((int)player.position.z, Chunk.SIZE);
                Chunk spawnChunk = world.getChunk(pCX, 0, pCZ);

                boolean isTerrainReady = spawnChunk != null && spawnChunk.state == Chunk.ChunkState.MESHED;

                if (!isTerrainReady) {
                    isPreloading = true;
                    player.position.y = 250.0f; // Freeze player in mid-air
                    world.updateChunks(world, worldGen, player); // Push tasks to background threads
                } else {
                    if (isPreloading) {
                        isPreloading = false;
                        int spawnX = (int)Math.floor(player.position.x);
                        int spawnZ = (int)Math.floor(player.position.z);
                        for (int ly = Chunk.HEIGHT - 1; ly >= 0; ly--) {
                            if (world.getBlock(spawnX, ly, spawnZ).isSolid()) {
                                player.position.y = ly + 2.0f;
                                break;
                            }
                        }
                        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
                    }

                    if (!showChat && !showDebug && !showNoiseViewer && !isPaused) {
                        player.update(window, camera, world, deltaTime);
                        updateBreaking(deltaTime);
                    } else {
                        breakingActive = false;
                    }
                }

                if (network != null && network.connected) {
                    if (network.seedReceived) {
                        GameConfig.seed = network.newSeed;
                        world.clearAllChunks();
                        worldGen.resetSeed(GameConfig.seed);
                        player.position.y = 100.0f;
                        network.seedReceived = false;
                    }

                    network.sendPosition(player.position.x, player.position.y, player.position.z, camera.yaw, camera.pitch);

                    remotePlayer.targetX = network.remoteX;
                    remotePlayer.targetY = network.remoteY;
                    remotePlayer.targetZ = network.remoteZ;
                    remotePlayer.targetYaw = network.remoteYaw;
                    remotePlayer.update(deltaTime);

                    int[] brk = network.pollBreak();
                    if (brk != null) {
                        Block brokenBlock = world.getBlock(brk[0], brk[1], brk[2]);
                        if (brokenBlock.isSolid()) droppedItems.add(new DroppedItem(brk[0], brk[1], brk[2], brokenBlock));
                        world.setBlock(brk[0], brk[1], brk[2], Block.AIR);
                        world.rebuildChunkAt(brk[0], brk[1], brk[2]);
                    }

                    int[] plc = network.pollPlace();
                    if (plc != null) {
                        world.setBlock(plc[0], plc[1], plc[2], Block.values()[plc[3]]);
                        world.rebuildChunkAt(plc[0], plc[1], plc[2]);
                    }

                    String chat = network.pollChat();
                    if (chat != null) chatHistory.add("[Friend]: " + chat);

                    int[] pk = network.pollPickup();
                    if (pk != null) {
                        for (int i = 0; i < droppedItems.size(); i++) {
                            DroppedItem item = droppedItems.get(i);
                            if (item.originX == pk[0] && item.originY == pk[1] && item.originZ == pk[2]) {
                                droppedItems.remove(i);
                                break;
                            }
                        }
                    }
                }

                if (!isPreloading) {
                    lastTarget = player.getTargetBlock(camera, world);
                    world.tickLiquids(deltaTime);
                    world.updateChunks(world, worldGen, player);

                    Vector3f chestPos = new Vector3f(player.position.x, player.position.y + 0.9f, player.position.z);
                    for (int i = droppedItems.size() - 1; i >= 0; i--) {
                        DroppedItem item = droppedItems.get(i);
                        item.update(deltaTime, player.position);

                        if (chestPos.distance(item.position) < 0.5f) {
                            inventory.addBlock(item.blockType);
                            item.alive = false;
                            if (network != null && network.connected) network.sendPickup(item.originX, item.originY, item.originZ);
                            droppedItems.remove(i);
                        }
                    }
                }
            }

            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

            if (networkInitialized) {
                shader.bind();
                shader.setUniform("sunDirection",    new org.joml.Vector3f(GameConfig.sunDirX, GameConfig.sunDirY, GameConfig.sunDirZ));
                shader.setUniform("sunStrength",     GameConfig.sunStrength);
                shader.setUniform("ambientStrength", GameConfig.ambientStrength);

                boolean isCameraUnderwater = world.getBlock(
                        (int)Math.floor(camera.position.x),
                        (int)Math.floor(camera.position.y),
                        (int)Math.floor(camera.position.z)).isLiquid();
                shader.setUniform("isUnderwater", isCameraUnderwater ? 1 : 0);
                shader.setUniform("cameraY",      camera.position.y);

                Matrix4f view       = camera.getViewMatrix();
                Matrix4f projection = camera.getProjectionMatrix();

                // Get local player chunk coordinate to define render limits
                int playerCX = Math.floorDiv((int) player.position.x, Chunk.SIZE);
                int playerCZ = Math.floorDiv((int) player.position.z, Chunk.SIZE);
                int R = GameConfig.renderDistance;

                // Which vertical chunk slab the player occupies (0=surface, -1=first deep, etc.)
                int playerCY = Math.floorDiv((int) player.position.y, Chunk.HEIGHT);
                // Render 4 slabs below the player, always at least down to cy=-4.
                int cyMin = Math.min(playerCY - 4, -4);

                // ── FRUSTUM CULLING SETUP ─────────────────────────────────────────────
                Matrix4f mvp = new Matrix4f(projection).mul(view); // model=identity
                shader.setUniform("mvp", mvp);
                float[] frustumPlanes = extractFrustumPlanes(mvp);

                // ── DISTANCE-SORTED DIRTY MESH REBUILD (pre-render pass) ──────────────
                // Collect all visible dirty chunks, sort nearest-first, rebuild the
                // closest ones.  This ensures player-adjacent chunks are always
                // up-to-date even when many chunks are dirty at once (e.g. after water
                // floods a large area).  Limit is raised to 6 because flat-array meshing
                // is cheaper than the old jagged-array path.
                if (!isPreloading) {
                    int maxMeshCompilesPerFrame = 6;
                    List<int[]> dirtyList = new ArrayList<>();
                    for (int dx = -R; dx <= R; dx++) {
                        for (int dz = -R; dz <= R; dz++) {
                            for (int cy = 0; cy >= cyMin; cy--) {
                                Chunk c = world.getChunk(playerCX + dx, cy, playerCZ + dz);
                                if (c != null && c.dirty && c.state == Chunk.ChunkState.MESHED) {
                                    dirtyList.add(new int[]{dx, dz, dx * dx + dz * dz, cy});
                                }
                            }
                        }
                    }
                    dirtyList.sort(Comparator.comparingInt(e -> e[2]));
                    int compiled = 0;
                    for (int[] e : dirtyList) {
                        if (compiled >= maxMeshCompilesPerFrame) break;
                        Chunk c = world.getChunk(playerCX + e[0], e[3], playerCZ + e[1]);
                        if (c != null && c.dirty && c.state == Chunk.ChunkState.MESHED) {
                            world.buildChunkMeshes(c);
                            compiled++;
                        }
                    }
                }

                // ── PASS 1: OPAQUE (Stone, Dirt, Grass, Sand) ──
                for (int dx = -R; dx <= R; dx++) {
                    for (int dz = -R; dz <= R; dz++) {
                        // Iterate: cy=0 (surface) + any generated deep chunks
                        for (int cy = 0; cy >= cyMin; cy--) {
                            Chunk chunk = world.getChunk(playerCX + dx, cy, playerCZ + dz);
                            if (chunk != null && chunk.opaqueMesh != null
                                    && isAabbInFrustum(frustumPlanes, chunk)) {
                                chunk.opaqueMesh.render();
                            }
                        }
                    }
                }

                // ── PASS 2: TRANSPARENT (Water, Ice, Leaves) ──
                glEnable(GL_BLEND);
                glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

                for (int dx = -R; dx <= R; dx++) {
                    for (int dz = -R; dz <= R; dz++) {
                        for (int cy = 0; cy >= cyMin; cy--) {
                            Chunk chunk = world.getChunk(playerCX + dx, cy, playerCZ + dz);
                            if (chunk != null && chunk.transparentMesh != null
                                    && isAabbInFrustum(frustumPlanes, chunk)) {
                                chunk.transparentMesh.render();
                            }
                        }
                    }
                }

                glDisable(GL_BLEND);

                // Render items
                for (DroppedItem item : droppedItems) {
                    Mesh itemMesh = getItemMesh(item.blockType);
                    float bob = (float) Math.sin(item.age * 3.0f) * 0.05f;
                    Matrix4f itemModel = new Matrix4f()
                            .translate(item.position.x, item.position.y + bob, item.position.z)
                            .rotateY(item.age * 1.5f);
                    Matrix4f itemMvp = new Matrix4f(projection).mul(view).mul(itemModel);
                    shader.setUniform("mvp", itemMvp);
                    itemMesh.render();
                }

                if (network != null && network.connected) {
                    remotePlayer.render(shader, projection, view);
                }
                shader.unbind();
            }

            imguiGlfw.newFrame();
            ImGui.newFrame();

            if (!networkInitialized) {
                renderConnectionMenu(ww[0], wh[0]);
            } else {
                if (isPreloading) {
                    renderPreloadProgress(ww[0], wh[0]);
                } else {
                    renderHUD(ww[0], wh[0]);
                    renderTargetCracks(camera, ww[0], wh[0]);
                    if (showDebug) renderDebugMenu();
                    if (showNoiseViewer) noiseVis.renderWindow(player);
                    if (showChat || !chatHistory.isEmpty()) renderChatBox(wh[0]);
                    if (isPaused) renderPauseMenu(ww[0], wh[0]);
                }
            }

            ImGui.render();
            imguiGl3.renderDrawData(ImGui.getDrawData());

            glfwSwapBuffers(window);
            glfwPollEvents();
        }

        for (Chunk chunk : world.getAllChunks()) {
            if (chunk.opaqueMesh != null) chunk.opaqueMesh.cleanup();
            if (chunk.transparentMesh != null) chunk.transparentMesh.cleanup();
        }
        for (Mesh m : itemMeshes.values()) { m.cleanup(); }
        shader.cleanup();
        imguiGl3.dispose();
        noiseVis.cleanup();
        imguiGlfw.dispose();
        ImGui.destroyContext();
        if (remotePlayer != null) remotePlayer.cleanup();
    }

            private void renderConnectionMenu(float w, float h) {
                ImGui.setNextWindowPos(w / 2.0f - 150.0f, h / 2.0f - 180.0f);
                ImGui.setNextWindowSize(300.0f, 340.0f);
                ImGui.begin("Start Screen", imgui.flag.ImGuiWindowFlags.NoDecoration | imgui.flag.ImGuiWindowFlags.NoMove);

                ImGui.text("Minecraft Voxel Engine");
                ImGui.separator();
                ImGui.spacing();

                ImGui.text("Pre-generate Radius:");
                int[] rad = { preloadRadius };
                if (ImGui.sliderInt("##rad", rad, 0, 100)) { // Slider now goes up to 100!
                    preloadRadius = rad[0];
                }
                if (preloadRadius > 40) {
                    ImGui.textColored(1.0f, 0.4f, 0.4f, 1.0f, "WARNING: Radii > 40 require");
                    ImGui.textColored(1.0f, 0.4f, 0.4f, 1.0f, "allocating extra JVM RAM!");
                } else {
                    ImGui.textDisabled("(0=instant, 10=fast, 50=massive, 100=epic)");
                }
                ImGui.spacing();

                if (ImGui.button("Single Player", 280, 30)) {
                    network = null;
                    startPreload();
                }

                ImGui.spacing();
                if (SaveManager.saveExists()) {
                    if (ImGui.button("Load Saved Game", 280, 30)) {
                        SaveManager.loadGame(world, player, inventory);
                        worldGen.resetSeed(GameConfig.seed);
                        world.clearAllChunks();
                        network = null;
                        startPreload();
                    }
                    ImGui.spacing();
                }

                ImGui.separator();
                ImGui.spacing();

                if (ImGui.button("Host Multiplayer Game", 280, 30)) {
                    network = new NetworkSession(true, null);
                    network.start();
                    remotePlayer = new RemotePlayer();
                    startPreload();
                }

                ImGui.spacing();
                ImGui.inputText("Host IP", ipInput);
                if (ImGui.button("Join Multiplayer Game", 280, 30)) {
                    network = new NetworkSession(false, ipInput.get().trim());
                    network.start();
                    remotePlayer = new RemotePlayer();
                    startPreload();
                }

                ImGui.end();
            }

    private void renderPreloadProgress(float w, float h) {
        ImGui.setNextWindowPos(w / 2.0f - 160.0f, h / 2.0f - 65.0f);
        ImGui.setNextWindowSize(320.0f, 130.0f);
        ImGui.begin("Pre-generating Terrain", imgui.flag.ImGuiWindowFlags.NoDecoration | imgui.flag.ImGuiWindowFlags.NoMove);

        ImGui.text("Generating world in background...");
        ImGui.text("Please wait a moment while the spawn");
        ImGui.text("area finishes compiling...");
        ImGui.spacing();

        // Loop a progress bar continuously to show active work
        float progress = (float) (glfwGetTime() % 2.0) / 2.0f;
        ImGui.progressBar(progress, 300, 24);

        ImGui.end();
    }

            private void renderPauseMenu(float w, float h) {
                ImGui.setNextWindowPos(w / 2.0f - 100.0f, h / 2.0f - 80.0f);
                ImGui.setNextWindowSize(200.0f, 160.0f);
                ImGui.begin("Paused", imgui.flag.ImGuiWindowFlags.NoDecoration | imgui.flag.ImGuiWindowFlags.NoMove);

                ImGui.text("Game Paused");
                ImGui.separator();
                ImGui.spacing();

                if (ImGui.button("Resume", 180, 30)) {
                    isPaused = false;
                    glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
                }
                ImGui.spacing();
                if (ImGui.button("Save Game", 180, 30)) {
                    SaveManager.saveGame(world, player, inventory);
                }
                ImGui.spacing();
                if (ImGui.button("Save & Quit", 180, 30)) {
                    SaveManager.saveGame(world, player, inventory);
                    glfwSetWindowShouldClose(window, true);
                }
                ImGui.end();
            }

        private void renderChatBox(int screenHeight) {
            ImGui.setNextWindowPos(10, screenHeight - 280);
            ImGui.setNextWindowSize(400, 200);

            int flags = imgui.flag.ImGuiWindowFlags.NoDecoration | imgui.flag.ImGuiWindowFlags.NoMove;
            if (!showChat) flags |= imgui.flag.ImGuiWindowFlags.NoBackground;

            ImGui.begin("Chat", flags);
            for (int i = Math.max(0, chatHistory.size() - 10); i < chatHistory.size(); i++) ImGui.text(chatHistory.get(i));

            if (showChat) {
                ImGui.setKeyboardFocusHere();
        if (ImGui.inputText("##chat", chatInput, imgui.flag.ImGuiInputTextFlags.EnterReturnsTrue)) {
            String msg = chatInput.get().trim();
            if (!msg.isEmpty()) {
                chatHistory.add("[You]: " + msg);
                if (network != null && network.connected) network.sendChat(msg);
                chatInput.set("");
            }
            showChat = false;
            glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
            }
        }
    ImGui.end();
    }

    private void renderTargetCracks(Camera camera, float w, float h) {
        if (lastTarget == null || !lastTarget.hit || !breakingActive || breakProgress <= 0) return;
        Matrix4f viewProj = new Matrix4f(camera.getProjectionMatrix()).mul(camera.getViewMatrix());
        int bx = lastTarget.hitX, by = lastTarget.hitY, bz = lastTarget.hitZ;
        float e = 0.005f;
        Vector3f[] corners = {
                new Vector3f(bx-e, by-e, bz-e), new Vector3f(bx+1+e, by-e, bz-e),
                new Vector3f(bx+1+e, by+1+e, bz-e), new Vector3f(bx-e, by+1+e, bz-e),
                new Vector3f(bx-e, by-e, bz+1+e), new Vector3f(bx+1+e, by-e, bz+1+e),
                new Vector3f(bx+1+e, by+1+e, bz+1+e), new Vector3f(bx-e, by+1+e, bz+1+e)
        };

        org.joml.Vector4f[] proj = new org.joml.Vector4f[8];
        for (int i = 0; i < 8; i++) {
            proj[i] = new org.joml.Vector4f(corners[i].x, corners[i].y, corners[i].z, 1.0f).mul(viewProj);
            if (proj[i].w > 0) { proj[i].x /= proj[i].w; proj[i].y /= proj[i].w; }
        }

        int finalColor = ImGui.colorConvertFloat4ToU32(0.1f, 0.1f, 0.1f, 0.5f + (breakProgress * 0.4f));
        float finalThickness = 2.0f + (breakProgress * 3.0f);
        var draw = ImGui.getBackgroundDrawList();

        java.util.function.BiConsumer<Integer, Integer> drawCrack = (i, j) -> {
            if (proj[i].w > 0 && proj[j].w > 0) {
                float x1 = (proj[i].x + 1) * 0.5f * w, y1 = (1 - proj[i].y) * 0.5f * h;
                float x2 = (proj[j].x + 1) * 0.5f * w, y2 = (1 - proj[j].y) * 0.5f * h;
                draw.addLine(x1, y1, x2, y2, finalColor, finalThickness);
            }
        };

        if (breakProgress > 0.1f) { drawCrack.accept(0, 6); drawCrack.accept(3, 5); }
        if (breakProgress > 0.4f) { drawCrack.accept(1, 7); drawCrack.accept(2, 4); }
        if (breakProgress > 0.7f) { drawCrack.accept(0, 2); drawCrack.accept(5, 7); }
    }

    private void renderHUD(float screenW, float screenH) {
        var draw = ImGui.getForegroundDrawList();
        float cx = screenW / 2.0f, cy = screenH / 2.0f;

        // Crosshair
        int white = ImGui.colorConvertFloat4ToU32(1, 1, 1, 0.9f);
        int black = ImGui.colorConvertFloat4ToU32(0, 0, 0, 0.6f);
        draw.addLine(cx - 11, cy, cx + 11, cy, black, 3.0f); draw.addLine(cx, cy - 11, cx, cy + 11, black, 3.0f);
        draw.addLine(cx - 10, cy, cx + 10, cy, white, 1.5f); draw.addLine(cx, cy - 10, cx, cy + 10, white, 1.5f);

        // Health Bar
        float hpWidth = 200f;
        float hpHeight = 15f;
        float hpX = cx - (hpWidth / 2.0f);
        float hpY = screenH - 75f;
        draw.addRectFilled(hpX, hpY, hpX + hpWidth, hpY + hpHeight, ImGui.colorConvertFloat4ToU32(0.2f, 0, 0, 0.8f));
        float fillW = hpWidth * (Math.max(0, player.health) / player.maxHealth);
        draw.addRectFilled(hpX, hpY, hpX + fillW, hpY + hpHeight, ImGui.colorConvertFloat4ToU32(0.8f, 0.1f, 0.1f, 1.0f));
        draw.addRect(hpX, hpY, hpX + hpWidth, hpY + hpHeight, black, 0f, 0, 2.0f);

        // Hotbar
        float slotSize = 40.0f;
        float spacing = 5.0f;
        int numSlots = 9;
        float startX = cx - (((numSlots * slotSize) + ((numSlots - 1) * spacing)) / 2.0f);
        float startY = screenH - slotSize - 10.0f;

        selectedBlock = hotbar[selectedSlot];

        for (int i = 0; i < numSlots; i++) {
            float x = startX + i * (slotSize + spacing);

            int bgCol = (i == selectedSlot) ? ImGui.colorConvertFloat4ToU32(0.6f, 0.6f, 0.6f, 0.8f) : ImGui.colorConvertFloat4ToU32(0.2f, 0.2f, 0.2f, 0.8f);
            draw.addRectFilled(x, startY, x + slotSize, startY + slotSize, bgCol, 4.0f);

            int outCol = (i == selectedSlot) ? ImGui.colorConvertFloat4ToU32(1.0f, 1.0f, 1.0f, 1.0f) : ImGui.colorConvertFloat4ToU32(0.0f, 0.0f, 0.0f, 0.8f);
            draw.addRect(x, startY, x + slotSize, startY + slotSize, outCol, 4.0f, 0, (i == selectedSlot) ? 3.0f : 1.5f);

            Block b = hotbar[i];
            int count = inventory.getCount(b);

            if (b != Block.AIR && count > 0) {
                float shrink = 8.0f;
                int blockCol = ImGui.colorConvertFloat4ToU32(b.r, b.g, b.b, 1.0f);
                draw.addRectFilled(x + shrink, startY + shrink, x + slotSize - shrink, startY + slotSize - shrink, blockCol, 2.0f);
                draw.addRect(x + shrink, startY + shrink, x + slotSize - shrink, startY + slotSize - shrink, black, 2.0f, 0, 1.5f);

                String countStr = String.valueOf(count);
                draw.addText(x + slotSize - 14, startY + slotSize - 18, black, countStr);
                draw.addText(x + slotSize - 15, startY + slotSize - 19, white, countStr);
            }
        }
    }

    private void updateBreaking(float deltaTime) {
        if (!breakingActive || lastTarget == null || !lastTarget.hit) {
            breakProgress = 0.0f;
            return;
        }

        int tx = lastTarget.hitX, ty = lastTarget.hitY, tz = lastTarget.hitZ;
        if (tx != breakX || ty != breakY || tz != breakZ) {
            breakProgress = 0.0f; breakX = tx; breakY = ty; breakZ = tz;
        }

        Block target = world.getBlock(tx, ty, tz);
        if (!target.isSolid()) { breakProgress = 0.0f; return; }

        breakProgress += deltaTime / target.hardness;

        if (breakProgress >= 1.0f) {
            droppedItems.add(new DroppedItem(tx, ty, tz, target));
            world.setBlock(tx, ty, tz, Block.AIR);
            // Immediately rebuild so the broken block disappears at once
            world.rebuildChunkAt(tx, ty, tz);
            if (network != null && network.connected) network.sendBreak(tx, ty, tz);
            breakProgress = 0.0f;
        }
    }

    private void renderDebugMenu() {
        ImGui.begin("Debug");
        ImGui.text(String.format("XYZ: %.3f / %.3f / %.3f", player.position.x, player.position.y, player.position.z));
        // Show live hardware performance data
        ImGui.text(String.format("FPS: %.1f", ImGui.getIO().getFramerate()));
        ImGui.text(String.format("Delta Time: %.4fs", ImGui.getIO().getDeltaTime()));
        ImGui.separator();
        float[] fov = { GameConfig.fov }; if (ImGui.sliderFloat("FOV", fov, 30f, 120f)) GameConfig.fov = fov[0];
        int[] rd = { GameConfig.renderDistance }; if (ImGui.sliderInt("Render Distance", rd, 2, 16)) GameConfig.renderDistance = rd[0];
        ImGui.end();
    }

    private boolean playerOccupies(int bx, int by, int bz) {
        float px = player.position.x, py = player.position.y, pz = player.position.z;
        return px + 0.3f > bx && px - 0.3f < bx + 1 && py + 1.8f > by && py < by + 1 && pz + 0.3f > bz && pz - 0.3f < bz + 1;
    }

    private boolean remotePlayerOccupies(int bx, int by, int bz) {
        if (network == null || !network.connected) return false;
        float px = remotePlayer.x, py = remotePlayer.y, pz = remotePlayer.z;
        return px + 0.3f > bx && px - 0.3f < bx + 1 && py + 1.8f > by && py < by + 1 && pz + 0.3f > bz && pz - 0.3f < bz + 1;
    }

    private Mesh getItemMesh(Block block) {
        return itemMeshes.computeIfAbsent(block, b -> {
            List<Float> verts = new ArrayList<>();
            List<Integer> idx = new ArrayList<>();
            int[] vIndex = {0};

            float w = 0.12f;
            float[] col = {b.r, b.g, b.b};
            addBox(verts, idx, vIndex, -w, -w, -w, w, w, w, col);

            float[] vArr = new float[verts.size()];
            for (int i = 0; i < verts.size(); i++) vArr[i] = verts.get(i);
            int[] iArr = new int[idx.size()];
            for (int i = 0; i < idx.size(); i++) iArr[i] = idx.get(i);

            return new Mesh(vArr, iArr);
        });
    }

    private void addBox(List<Float> verts, List<Integer> idx, int[] vIndex,
                        float minX, float minY, float minZ,
                        float maxX, float maxY, float maxZ, float[] col) {
        float[][] corners = {
                {minX, minY, maxZ}, {maxX, minY, maxZ}, {maxX, maxY, maxZ}, {minX, maxY, maxZ},
                {maxX, minY, minZ}, {minX, minY, minZ}, {minX, maxY, minZ}, {maxX, maxY, minZ},
                {minX, maxY, minZ}, {maxX, maxY, minZ}, {maxX, maxY, maxZ}, {minX, maxY, maxZ},
                {minX, minY, maxZ}, {maxX, minY, maxZ}, {maxX, minY, minZ}, {minX, minY, minZ},
                {maxX, minY, maxZ}, {maxX, minY, minZ}, {maxX, maxY, minZ}, {maxX, maxY, maxZ},
                {minX, minY, minZ}, {minX, minY, maxZ}, {minX, maxY, maxZ}, {minX, maxY, minZ}
        };

        for (int face = 0; face < 6; face++) {
            float shade = (face == 2) ? 1.0f : (face == 3 ? 0.5f : 0.8f);
            for (int i = 0; i < 4; i++) {
                float[] corner = corners[face * 4 + i];
                verts.add(corner[0]); verts.add(corner[1]); verts.add(corner[2]);
                verts.add(col[0]*shade); verts.add(col[1]*shade); verts.add(col[2]*shade);
                verts.add(1.0f);
                verts.add(0f); verts.add(1f); verts.add(0f);
            }
            int b = vIndex[0];
            idx.add(b); idx.add(b+1); idx.add(b+2); idx.add(b+2); idx.add(b+3); idx.add(b);
            vIndex[0] += 4;
        }
    }
    // =========================================================================
    // FRUSTUM CULLING MATH
    // =========================================================================

    /**
     * Extracts the 6 clipping planes (Left, Right, Bottom, Top, Near, Far)
     * from a View-Projection matrix. Returns an array of 24 floats (6 planes * 4 components).
     */
    private float[] extractFrustumPlanes(Matrix4f vp) {
        float[] planes = new float[24];

        // Left plane
        planes[0]  = vp.m03() + vp.m00(); planes[1]  = vp.m13() + vp.m10();
        planes[2]  = vp.m23() + vp.m20(); planes[3]  = vp.m33() + vp.m30();
        // Right plane
        planes[4]  = vp.m03() - vp.m00(); planes[5]  = vp.m13() - vp.m10();
        planes[6]  = vp.m23() - vp.m20(); planes[7]  = vp.m33() - vp.m30();
        // Bottom plane
        planes[8]  = vp.m03() + vp.m01(); planes[9]  = vp.m13() + vp.m11();
        planes[10] = vp.m23() + vp.m21(); planes[11] = vp.m33() + vp.m31();
        // Top plane
        planes[12] = vp.m03() - vp.m01(); planes[13] = vp.m13() - vp.m11();
        planes[14] = vp.m23() - vp.m21(); planes[15] = vp.m33() - vp.m31();
        // Near plane
        planes[16] = vp.m03() + vp.m02(); planes[17] = vp.m13() + vp.m12();
        planes[18] = vp.m23() + vp.m22(); planes[19] = vp.m33() + vp.m32();
        // Far plane
        planes[20] = vp.m03() - vp.m02(); planes[21] = vp.m13() - vp.m12();
        planes[22] = vp.m23() - vp.m22(); planes[23] = vp.m33() - vp.m32();

        // Normalize planes for accurate distance checks
        for (int i = 0; i < 6; i++) {
            float len = (float) Math.sqrt(planes[i*4] * planes[i*4] + planes[i*4+1] * planes[i*4+1] + planes[i*4+2] * planes[i*4+2]);
            planes[i*4] /= len; planes[i*4+1] /= len; planes[i*4+2] /= len; planes[i*4+3] /= len;
        }
        return planes;
    }

    /**
     * Checks if the chunk's tight, non-empty bounding box is inside the camera's view.
     */
    private boolean isAabbInFrustum(float[] planes, Chunk chunk) {
        // If the chunk is completely empty, it has no size.
        if (chunk.minBlockY > chunk.maxBlockY) return false;

        float minX = chunk.cx * Chunk.SIZE;
        float minZ = chunk.cz * Chunk.SIZE;
        // Use the tight Y-bounds tracked when blocks were placed!
        float minY = chunk.cy * Chunk.HEIGHT + chunk.minBlockY;

        float maxX = minX + Chunk.SIZE;
        float maxZ = minZ + Chunk.SIZE;
        float maxY = chunk.cy * Chunk.HEIGHT + chunk.maxBlockY + 1; // +1 to cover the top of the highest block

        // Test the AABB against all 6 planes
        for (int i = 0; i < 6; i++) {
            int p = i * 4;
            // Find the point on the bounding box furthest in the direction of the plane's normal
            float px = planes[p] > 0   ? maxX : minX;
            float py = planes[p+1] > 0 ? maxY : minY;
            float pz = planes[p+2] > 0 ? maxZ : minZ;

            // If that furthest point is behind the plane, the ENTIRE box is outside the frustum
            if (planes[p] * px + planes[p+1] * py + planes[p+2] * pz + planes[p+3] < 0) {
                return false;
            }
        }
        return true;
    }
}