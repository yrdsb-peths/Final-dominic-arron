package com.leaf.game.core;

import com.leaf.game.entity.DroppedItem;
import com.leaf.game.entity.FlightController;
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
import java.util.Random;

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

    // ── UI STATE ──────────────────────────────────────────────────────────────
    private boolean isPaused  = false;
    private boolean showDebug = false;
    private boolean showChat  = false;
    private final ImString chatInput = new ImString(256);
    private final List<String> chatHistory = new ArrayList<>();
    private final ImString seedInput = new ImString(32);

    private final Inventory inventory = new Inventory();
    private int   selectedSlot  = 0;
    private Block selectedBlock = Block.AIR;

    private final Block[] hotbar = {
            Block.GRASS, Block.DIRT, Block.STONE, Block.WATER,
            Block.AIR, Block.AIR, Block.AIR, Block.AIR, Block.AIR
    };

    private final List<DroppedItem> droppedItems = new ArrayList<>();
    private final Map<Block, Mesh>  itemMeshes   = new HashMap<>();

    private float   breakProgress = 0.0f;
    private int     breakX, breakY, breakZ;
    private boolean breakingActive = false;

    // ── PRE-GENERATION STATE ──────────────────────────────────────────────────
    private boolean     isPreloading = false;
    private int         preloadRadius = 10;
    private final List<Chunk> chunksToGenerate = new ArrayList<>();
    private int         totalPreloadCount    = 0;
    private int         currentPreloadProgress = 0;

    // ── TIME CONTROLLER ───────────────────────────────────────────────────────
    // Accessed every frame: TimeController.getInstance().
    // Keybindings:
    //   Hold R → slow motion  (GameConfig.timeSlowScale ≈ 0.15)
    //   Hold Y → fast time    (GameConfig.timeFastScale  ≈ 4.0)
    // (T is reserved for chat; R was chosen as the slow-time key.)

    // ── SCREEN SHAKE (Ground Smash landing effect) ────────────────────────────
    // A damped sinusoidal camera offset applied for smashShakeDuration seconds.
    // Window temporarily offsets camera.position before rendering, then restores
    // it so Player's position accounting is unaffected.
    private float smashShakeTimer = 0f;   // counts down from smashShakeDuration
    private final Random shakeRng = new Random();

    // ── METEOR EFFECT (Smash visual) ─────────────────────────────────────────
    // When a ground smash begins, a STAR_IRON DroppedItem is spawned high above
    // the player and falls at high speed — giving the descent a "meteor crashing
    // from the sky" visual without requiring a particle system.
    private boolean wasSmashing = false;

    // ─────────────────────────────────────────────────────────────────────────

    public void run() {
        init();
        loop();
        glfwFreeCallbacks(window);
        glfwDestroyWindow(window);
        glfwTerminate();
        glfwSetErrorCallback(null).free();
        // Shut down the background chunk-generation thread pool so the JVM can
        // exit cleanly.  Without this, non-daemon worker threads kept the Java
        // process alive indefinitely after the window was closed.
        if (world != null) world.shutdown();
        System.exit(0);
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
                    glfwSetInputMode(window, GLFW_CURSOR,
                            isPaused ? GLFW_CURSOR_NORMAL : GLFW_CURSOR_DISABLED);
                }
            }

            if (isPaused) return;

            if (key == GLFW_KEY_F3 && action == GLFW_RELEASE && !showChat) {
                showDebug = !showDebug;
                glfwSetInputMode(window, GLFW_CURSOR,
                        (showDebug || showNoiseViewer) ? GLFW_CURSOR_NORMAL : GLFW_CURSOR_DISABLED);
            }

            if (key == GLFW_KEY_F4 && action == GLFW_RELEASE && !showChat) {
                showNoiseViewer = !showNoiseViewer;
                glfwSetInputMode(window, GLFW_CURSOR,
                        (showDebug || showNoiseViewer) ? GLFW_CURSOR_NORMAL : GLFW_CURSOR_DISABLED);
            }

            // T opens chat (release event only, so holding T for time-dilation is safe
            // because time-dilation uses glfwGetKey in the game loop, not this callback)
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
            if (!networkInitialized || isPreloading || showDebug || showChat || showNoiseViewer || isPaused)
                return;

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
                            world.rebuildChunkAt(lastTarget.placeX, lastTarget.placeY, lastTarget.placeZ);
                            if (network != null && network.connected)
                                network.sendPlace(lastTarget.placeX, lastTarget.placeY, lastTarget.placeZ, selectedBlock);
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
            if (!networkInitialized || isPreloading || showDebug || showChat || showNoiseViewer || isPaused)
                return;
            if (firstMouse[0]) {
                lastMouseX[0] = xpos; lastMouseY[0] = ypos; firstMouse[0] = false; return;
            }
            float dx = (float)(xpos - lastMouseX[0]);
            float dy = (float)(ypos - lastMouseY[0]);
            lastMouseX[0] = xpos; lastMouseY[0] = ypos;

            // Suppress mouse look while smashing (camera is auto-pitched)
            if (!player.isSmashing()) {
                camera.yaw   += dx * GameConfig.mouseSensitivity;
                camera.pitch -= dy * GameConfig.mouseSensitivity;
                camera.clampPitch();
            }
        });
    }

    private void startPreload() {
        worldGen.resetSeed(GameConfig.seed);
        world.clearAllChunks();
        world.meshingQueue.clear();
        networkInitialized = true;
        isPreloading       = true;
    }

    private void loop() {
        GL.createCapabilities();
        imguiGl3.init("#version 330");

        glEnable(GL_DEPTH_TEST);
        glClearColor(0.5f, 0.7f, 0.9f, 1.0f);

        Shader shader = new Shader(
                "src/main/resources/shaders/vertex.glsl",
                "src/main/resources/shaders/fragment.glsl");
        Camera camera = new Camera();
        setupMouseLook(camera);

        // ── SPAWN POINT ────────────────────────────────────────────────────────
        // Spawn at (777, 250, 777): these coordinates produce non-integer noise
        // inputs at every frequency used by the terrain samplers, ensuring the
        // terrain is visibly seed-dependent right at the start.
        this.player   = new Player(777.0f, 250.0f, 777.0f);
        this.world    = new World();
        this.worldGen = new WorldGen();
        this.noiseVis = new NoiseVisualizer(worldGen);

        TimeController tc = TimeController.getInstance();

        Matrix4f model    = new Matrix4f();
        double   lastTime = glfwGetTime();

        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);

        while (!glfwWindowShouldClose(window)) {
            double now          = glfwGetTime();
            float  rawDeltaTime = (float)(now - lastTime);

            // ── FRAME RATE LIMITER ────────────────────────────────────────────
            float targetMinFrameTime = 1.0f / 120.0f;
            if (rawDeltaTime < targetMinFrameTime) {
                try { Thread.sleep((long)((targetMinFrameTime - rawDeltaTime) * 1000)); }
                catch (InterruptedException ignored) {}
                now          = glfwGetTime();
                rawDeltaTime = (float)(now - lastTime);
            }
            rawDeltaTime = Math.min(rawDeltaTime, 0.1f);
            lastTime = now;

            // ── TIME CONTROLLER UPDATE ────────────────────────────────────────
            // Must happen before anything reads tc.getScale() this frame.
            // Key policy (checked with glfwGetKey so they work as hold-keys):
            //   R → slow  (0.15 scale)
            //   Y → fast  (4.0 scale)
            //   Neither → normal (1.0)
            // Chat box suppresses time dilation so Y/R text doesn't glitch.
            if (!showChat && !isPaused && networkInitialized && !isPreloading) {
                boolean rHeld = glfwGetKey(window, GLFW_KEY_R) == GLFW_PRESS;
                boolean yHeld = glfwGetKey(window, GLFW_KEY_Y) == GLFW_PRESS;
                if (rHeld) {
                    tc.setTargetScale(GameConfig.timeSlowScale);
                } else if (yHeld) {
                    tc.setTargetScale(GameConfig.timeFastScale);
                } else {
                    tc.setTargetScale(1.0f);
                }
            } else {
                // Menu open or not yet in-game — return to normal
                tc.setTargetScale(1.0f);
            }
            tc.update(rawDeltaTime);

            // ── SCALED DELTA TIME for physics ─────────────────────────────────
            // Every system that involves physics or animation uses this.
            float deltaTime = rawDeltaTime * tc.getScale();

            int[] ww = new int[1], wh = new int[1];
            glfwGetWindowSize(window, ww, wh);

            if (networkInitialized) {
                // ── ASYNC MESH DRAINER ─────────────────────────────────────────
                int maxMeshesPerFrame = isPreloading ? 12 : 3;
                int meshedThisFrame = 0;
                Chunk readyChunk;
                while (meshedThisFrame < maxMeshesPerFrame
                        && (readyChunk = world.meshingQueue.poll()) != null) {
                    world.buildChunkMeshes(readyChunk);
                    readyChunk.state = Chunk.ChunkState.MESHED;
                    meshedThisFrame++;
                }

                // ── PREVENT FALLING THROUGH WORLD ─────────────────────────────
                int pCX = Math.floorDiv((int)player.position.x, Chunk.SIZE);
                int pCZ = Math.floorDiv((int)player.position.z, Chunk.SIZE);
                Chunk spawnChunk = world.getChunk(pCX, 0, pCZ);
                boolean isTerrainReady = spawnChunk != null
                        && spawnChunk.state == Chunk.ChunkState.MESHED;

                if (!isTerrainReady) {
                    isPreloading = true;
                    player.position.y = 250.0f;
                    // Keep XZ at spawn if player hasn't moved yet (avoids drift during load)
                    if (player.position.x == 0f && player.position.z == 0f) {
                        player.position.x = 777.0f; player.position.z = 777.0f;
                    }
                    world.updateChunks(world, worldGen, player);
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
                        // ── PLAYER UPDATE (time-scaled) ────────────────────────
                        player.update(window, camera, world, deltaTime);
                        updateBreaking(deltaTime);

                        // ── METEOR SPAWN: smash start → STAR_IRON falling from sky ──
                        // Detects the leading edge of isSmashing so the meteor only
                        // spawns once per smash, not every frame of the descent.
                        boolean nowSmashing = player.isSmashing();
                        if (nowSmashing && !wasSmashing) {
                            // Spawn a glowing STAR_IRON block 100 blocks above the player,
                            // falling at 1.5× smash speed — it arrives just before or as
                            // the player hits the ground, reinforcing the meteor-impact feel.
                            Vector3f meteorVel = new Vector3f(0f, -GameConfig.smashDescentSpeed * 1.5f, 0f);
                            droppedItems.add(new DroppedItem(
                                    (int)player.position.x,
                                    (int)(player.position.y + 100),
                                    (int)player.position.z,
                                    Block.STAR_IRON,
                                    meteorVel));
                        }
                        wasSmashing = nowSmashing;

                        // ── SMASH IMPACT HANDLING ──────────────────────────────
                        handleSmashImpact(camera);

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

                    network.sendPosition(player.position.x, player.position.y,
                            player.position.z, camera.yaw, camera.pitch);

                    remotePlayer.targetX   = network.remoteX;
                    remotePlayer.targetY   = network.remoteY;
                    remotePlayer.targetZ   = network.remoteZ;
                    remotePlayer.targetYaw = network.remoteYaw;
                    remotePlayer.update(deltaTime);

                    int[] brk = network.pollBreak();
                    if (brk != null) {
                        Block brokenBlock = world.getBlock(brk[0], brk[1], brk[2]);
                        if (brokenBlock.isSolid())
                            droppedItems.add(new DroppedItem(brk[0], brk[1], brk[2], brokenBlock));
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

                    // ── NETWORK CRATER SYNC ────────────────────────────────────
                    int[] crt = network.pollCrater();
                    if (crt != null) {
                        world.createImpactCrater(crt[0], crt[1], crt[2], crt[3]);
                        spawnCraterEjecta(crt[0], crt[1], crt[2], crt[3]);
                        // Remote craters get screen shake too (smaller amplitude)
                        float dist = new Vector3f(crt[0], crt[1], crt[2])
                                .distance(player.position);
                        if (dist < 80f) smashShakeTimer = GameConfig.smashShakeDuration * 0.5f;
                    }

                    int[] pk = network.pollPickup();
                    Vector3f chestPos = new Vector3f(player.position.x,
                            player.position.y + 0.9f, player.position.z);
                    for (int i = droppedItems.size() - 1; i >= 0; i--) {
                        DroppedItem item = droppedItems.get(i);
                        item.update(deltaTime, player.position);
                        if (chestPos.distance(item.position) < 0.5f) {
                            inventory.addBlock(item.blockType);
                            addBlockToHotbar(item.blockType);
                            item.alive = false;
                            if (network != null && network.connected)
                                network.sendPickup(item.originX, item.originY, item.originZ);
                            droppedItems.remove(i);
                        }
                    }
                }

                if (!isPreloading) {
                    lastTarget = player.getTargetBlock(camera, world);
                    // tickLiquids uses scaled deltaTime — fast time makes water flow faster
                    world.tickLiquids(deltaTime);
                    world.updateChunks(world, worldGen, player);

                    Vector3f chestPos = new Vector3f(player.position.x,
                            player.position.y + 0.9f, player.position.z);
                    for (int i = droppedItems.size() - 1; i >= 0; i--) {
                        DroppedItem item = droppedItems.get(i);
                        item.update(deltaTime, player.position);
                        if (chestPos.distance(item.position) < 0.5f) {
                            inventory.addBlock(item.blockType);
                            addBlockToHotbar(item.blockType);
                            item.alive = false;
                            if (network != null && network.connected)
                                network.sendPickup(item.originX, item.originY, item.originZ);
                            droppedItems.remove(i);
                        }
                    }
                }
            }

            // ── RENDER ────────────────────────────────────────────────────────
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

            if (networkInitialized) {
                shader.bind();
                shader.setUniform("sunDirection",
                        new Vector3f(GameConfig.sunDirX, GameConfig.sunDirY, GameConfig.sunDirZ));
                shader.setUniform("sunStrength",     GameConfig.sunStrength);
                shader.setUniform("ambientStrength", GameConfig.ambientStrength);

                boolean isCameraUnderwater = world.getBlock(
                        (int)Math.floor(camera.position.x),
                        (int)Math.floor(camera.position.y),
                        (int)Math.floor(camera.position.z)).isLiquid();
                shader.setUniform("isUnderwater", isCameraUnderwater ? 1 : 0);
                shader.setUniform("cameraY",      camera.position.y);

                // ── TIME DILATION VIGNETTE ────────────────────────────────────
                // Slow motion: subtle blue-grey wash
                // Fast time: warm orange tint
                float slowFactor = tc.getSlownessFactor();
                float fastFactor = tc.getFastnessFactor();
                float vignetteStrength;
                Vector3f vignetteColor;
                if (slowFactor > 0.001f) {
                    vignetteStrength = slowFactor * 0.28f;
                    vignetteColor    = new Vector3f(0.52f, 0.58f, 0.70f); // blue-grey
                } else if (fastFactor > 0.001f) {
                    vignetteStrength = fastFactor * 0.22f;
                    vignetteColor    = new Vector3f(0.80f, 0.55f, 0.18f); // warm orange
                } else {
                    vignetteStrength = 0f;
                    vignetteColor    = new Vector3f(0f, 0f, 0f);
                }
                shader.setUniform("timeVignetteStrength", vignetteStrength);
                shader.setUniform("timeVignetteColor",    vignetteColor);

                // ── FLIGHT CAMERA EFFECTS ─────────────────────────────────────
                // Set dynamic FOV from flight controller boost
                float fovBoost = player.getCameraFovBoost();
                camera.dynamicFov = (fovBoost > 0.1f) ? GameConfig.fov + fovBoost : -1f;

                // ── VIEW MATRIX + ROLL ────────────────────────────────────────
                // Roll is NOT inside getViewMatrix() — that stays clean for frustum
                // culling. Instead we apply a separate Z-rotation after the view
                // matrix so the effect is purely cosmetic and fully reversible.
                Matrix4f baseView  = camera.getViewMatrix();
                float    rollAngle = player.getCameraRoll();
                Matrix4f view;
                if (Math.abs(rollAngle) > 0.0005f) {
                    // Rotate around the camera's forward axis (Z in view space)
                    // by applying rotateZ BEFORE the view matrix (in world space
                    // this means we tilt the camera around its own look axis).
                    // We use a roll matrix applied in VIEW space: mul on the right.
                    Matrix4f rollMat = new Matrix4f().rotateZ(rollAngle);
                    view = rollMat.mul(baseView);
                } else {
                    view = baseView;
                }

                // ── SCREEN SHAKE ──────────────────────────────────────────────
                // Damped sinusoidal offset on camera position for smashShakeDuration.
                // We temporarily move camera.position, build the MVP, then restore it.
                Vector3f shakeOffset = computeShakeOffset(rawDeltaTime);
                if (shakeOffset.lengthSquared() > 0f) {
                    camera.position.add(shakeOffset);
                    view = camera.getViewMatrix(); // recompute with shaken position
                    if (Math.abs(rollAngle) > 0.0005f) {
                        view = new Matrix4f().rotateZ(rollAngle).mul(view);
                    }
                }

                Matrix4f projection = camera.getProjectionMatrix();

                // Restore camera position after shake (BEFORE any other use)
                if (shakeOffset.lengthSquared() > 0f) {
                    camera.position.sub(shakeOffset);
                }

                int playerCX = Math.floorDiv((int)player.position.x, Chunk.SIZE);
                int playerCZ = Math.floorDiv((int)player.position.z, Chunk.SIZE);
                int R        = GameConfig.renderDistance;
                int playerCY = Math.floorDiv((int)player.position.y, Chunk.HEIGHT);
                int cyMin    = Math.min(playerCY - 4, -4);

                // Frustum culling uses the CLEAN view (no roll, no shake) to avoid
                // popping at the frustum edges during roll animations.
                Matrix4f cleanMvp  = new Matrix4f(projection).mul(baseView);
                shader.setUniform("mvp", cleanMvp);
                float[] frustumPlanes = extractFrustumPlanes(cleanMvp);

                // ── DIRTY MESH REBUILD ─────────────────────────────────────────
                if (!isPreloading) {
                    int maxMeshCompilesPerFrame = 6;
                    List<int[]> dirtyList = new ArrayList<>();
                    for (int dx = -R; dx <= R; dx++) {
                        for (int dz = -R; dz <= R; dz++) {
                            for (int cy = 0; cy >= cyMin; cy--) {
                                Chunk c = world.getChunk(playerCX + dx, cy, playerCZ + dz);
                                if (c != null && c.dirty && c.state == Chunk.ChunkState.MESHED) {
                                    dirtyList.add(new int[]{dx, dz, dx*dx + dz*dz, cy});
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
                            world.buildChunkMeshes(c); compiled++;
                        }
                    }
                }

                // ── PASS 1: OPAQUE ────────────────────────────────────────────
                // Use the shaken/rolled MVP for actual rendering
                Matrix4f renderMvp = new Matrix4f(projection).mul(view);
                shader.setUniform("mvp", renderMvp);

                for (int dx = -R; dx <= R; dx++) {
                    for (int dz = -R; dz <= R; dz++) {
                        for (int cy = 0; cy >= cyMin; cy--) {
                            Chunk chunk = world.getChunk(playerCX + dx, cy, playerCZ + dz);
                            if (chunk != null && chunk.opaqueMesh != null
                                    && isAabbInFrustum(frustumPlanes, chunk)) {
                                chunk.opaqueMesh.render();
                            }
                        }
                    }
                }

                // ── PASS 2: TRANSPARENT ───────────────────────────────────────
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
                // ── RENDER GRAPPLE CABLE & LASER SIGHT ─────────────────────────
                FlightController fc = player.flightController;
                if (player.debugMode && fc.getMode() == FlightController.FlightMode.GRAPPLE) {
                    Vector3f playerHand = new Vector3f(player.position.x, player.position.y + 0.9f, player.position.z);
                    Vector3f targetPoint = fc.isHooked() ? fc.getHookPoint() : fc.getAimTarget(camera, world);
                    boolean isLaser = !fc.isHooked();

                    if (targetPoint != null) {
                        Vector3f ropeDir  = new Vector3f(targetPoint).sub(playerHand);
                        float    ropeDist = ropeDir.length();

                        if (ropeDist > 0.1f) {
                            ropeDir.normalize();
                            org.joml.Quaternionf ropeRot = new org.joml.Quaternionf().rotationTo(new org.joml.Vector3f(0, 0, 1), ropeDir);

                            float thickness = isLaser ? 0.008f : 0.04f;

                            // CRITICAL FIX: Translate by half distance in Z so it actually starts at the hand!
                            Matrix4f ropeModel = new Matrix4f()
                                    .translate(playerHand.x, playerHand.y, playerHand.z)
                                    .rotate(ropeRot)
                                    .translate(0f, 0f, ropeDist * 0.5f)
                                    .scale(thickness, thickness, ropeDist / 0.24f);

                            Matrix4f ropeMvp = new Matrix4f(projection).mul(view).mul(ropeModel);
                            shader.setUniform("mvp", ropeMvp);

                            Block renderBlock = isLaser ? Block.CRYSTAL_ROSE : Block.CRYSTAL_AMETHYST;
                            getItemMesh(renderBlock).render();
                        }
                    }
                }
                // ── ITEMS ─────────────────────────────────────────────────────
                for (DroppedItem item : droppedItems) {
                    Mesh itemMesh = getItemMesh(item.blockType);
                    float bob = (float)Math.sin(item.age * 3.0f) * 0.05f;
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

            // ── IMGUI ─────────────────────────────────────────────────────────
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
                    if (showDebug)       renderDebugMenu();
                    if (showNoiseViewer) noiseVis.renderWindow(player);
                    if (showChat || !chatHistory.isEmpty()) renderChatBox(wh[0]);
                    if (isPaused)        renderPauseMenu(ww[0], wh[0]);
                }
            }

            ImGui.render();
            imguiGl3.renderDrawData(ImGui.getDrawData());

            glfwSwapBuffers(window);
            glfwPollEvents();
        }

        // ── CLEANUP ───────────────────────────────────────────────────────────
        for (Chunk chunk : world.getAllChunks()) {
            if (chunk.opaqueMesh      != null) chunk.opaqueMesh.cleanup();
            if (chunk.transparentMesh != null) chunk.transparentMesh.cleanup();
        }
        for (Mesh m : itemMeshes.values()) m.cleanup();
        shader.cleanup();
        imguiGl3.dispose();
        noiseVis.cleanup();
        imguiGlfw.dispose();
        ImGui.destroyContext();
        if (remotePlayer != null) remotePlayer.cleanup();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  SMASH IMPACT HANDLER
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Called each frame after player.update(). Checks player.smashImpactX to
     * detect a fresh smash landing and responds:
     *   1. Carves the crater in the world.
     *   2. Spawns ejecta DroppedItems in a radial burst.
     *   3. Starts the screen shake timer.
     *   4. Sends the CRATER network message to the remote peer if multiplayer.
     */
    private void handleSmashImpact(Camera camera) {
        if (player.smashImpactX == Integer.MIN_VALUE) return; // no impact this frame

        int ix = player.smashImpactX;
        int iy = player.smashImpactY;
        int iz = player.smashImpactZ;
        int r  = GameConfig.smashCraterRadius;

        // 1. Carve crater
        world.createImpactCrater(ix, iy, iz, r);

        // 2. Ejecta burst
        spawnCraterEjecta(ix, iy, iz, r);

        // 3. Screen shake
        smashShakeTimer = GameConfig.smashShakeDuration;

        // 4. Network sync
        if (network != null && network.connected) {
            network.sendCrater(ix, iy, iz, r);
        }

        // Reset camera pitch to neutral after smash (already done in Player.java
        // but this is a belt-and-suspenders clear)
    }

    /**
     * Spawns a burst of DroppedItems flying outward from the impact point.
     * These use the new DroppedItem velocity field added for crater ejecta.
     * Only blocks that were actually solid at the impact site are sampled;
     * if the crater is in empty air (unlikely) we default to GRAVEL.
     */
    private void spawnCraterEjecta(int ix, int iy, int iz, int radius) {
        int ejectedCount = 12;
        // Sample the impacted block type for a flavourful ejecta colour
        Block ejectBlock = world.getBlock(ix, iy, iz);
        if (ejectBlock == Block.AIR || !ejectBlock.isSolid()) ejectBlock = Block.GRAVEL;

        for (int i = 0; i < ejectedCount; i++) {
            // Random hemisphere outward-and-upward direction
            double azimuth  = shakeRng.nextDouble() * 2.0 * Math.PI;
            double elevation = shakeRng.nextDouble() * Math.PI * 0.5 + 0.1; // 0.1..PI/2+0.1
            float vx = (float)(Math.cos(azimuth) * Math.cos(elevation));
            float vy = (float)(Math.sin(elevation));
            float vz = (float)(Math.sin(azimuth) * Math.cos(elevation));

            float ejectionSpeed = 6f + shakeRng.nextFloat() * 10f;
            Vector3f launchVel = new Vector3f(vx, vy, vz).mul(ejectionSpeed);

            // Offset origin slightly so items aren't inside the crater wall
            int ox = ix + (int)(vx * (radius + 1));
            int oy = iy + (int)(vy * (radius + 1));
            int oz = iz + (int)(vz * (radius + 1));

            droppedItems.add(new DroppedItem(ox, oy, oz, ejectBlock, launchVel));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  SCREEN SHAKE
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns a camera-position offset for the current frame.
     * Damped sinusoidal: amplitude decays linearly from smashShakeAmplitude → 0
     * over smashShakeDuration seconds.
     *
     * The caller adds this to camera.position before building the view matrix,
     * then subtracts it afterward — player position accounting is unaffected.
     *
     * @param rawDt raw (unscaled) frame time, used to tick the shake timer
     * @return offset vector (zero when no shake active)
     */
    private Vector3f computeShakeOffset(float rawDt) {
        if (smashShakeTimer <= 0f) return new Vector3f(0f);

        smashShakeTimer = Math.max(0f, smashShakeTimer - rawDt);

        float progress    = smashShakeTimer / GameConfig.smashShakeDuration; // 1→0
        float amplitude   = progress * GameConfig.smashShakeAmplitude;
        float timeSecs    = (float)glfwGetTime();
        float freq        = GameConfig.smashShakeFrequency;

        // Two-axis shake: X and Y channels at slightly different frequencies
        // for an organic non-repeating feel.
        float shakeX = amplitude * (float)Math.sin(timeSecs * freq * 1.0);
        float shakeY = amplitude * (float)Math.sin(timeSecs * freq * 1.3 + 0.7);

        return new Vector3f(shakeX, shakeY, 0f);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  UI — Connection Menu
    // ─────────────────────────────────────────────────────────────────────────

    private void renderConnectionMenu(float w, float h) {
        ImGui.setNextWindowPos(w / 2.0f - 150.0f, h / 2.0f - 180.0f);
        ImGui.setNextWindowSize(300.0f, 340.0f);
        ImGui.begin("Start Screen",
                imgui.flag.ImGuiWindowFlags.NoDecoration | imgui.flag.ImGuiWindowFlags.NoMove);

        ImGui.text("Minecraft Voxel Engine");
        ImGui.separator();
        ImGui.spacing();

        ImGui.text("Pre-generate Radius:");
        int[] rad = {preloadRadius};
        if (ImGui.sliderInt("##rad", rad, 0, 100)) preloadRadius = rad[0];
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
                java.util.Arrays.fill(hotbar, Block.AIR);
                SaveManager.loadGame(world, player, inventory);
                for (Block b : Block.values()) {
                    if (inventory.getCount(b) > 0) addBlockToHotbar(b);
                }
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
        ImGui.begin("Pre-generating Terrain",
                imgui.flag.ImGuiWindowFlags.NoDecoration | imgui.flag.ImGuiWindowFlags.NoMove);
        ImGui.text("Generating world in background...");
        ImGui.text("Please wait a moment while the spawn");
        ImGui.text("area finishes compiling...");
        ImGui.spacing();
        float progress = (float)(glfwGetTime() % 2.0) / 2.0f;
        ImGui.progressBar(progress, 300, 24);
        ImGui.end();
    }

    private void renderPauseMenu(float w, float h) {
        ImGui.setNextWindowPos(w / 2.0f - 100.0f, h / 2.0f - 80.0f);
        ImGui.setNextWindowSize(200.0f, 160.0f);
        ImGui.begin("Paused",
                imgui.flag.ImGuiWindowFlags.NoDecoration | imgui.flag.ImGuiWindowFlags.NoMove);
        ImGui.text("Game Paused");
        ImGui.separator();
        ImGui.spacing();
        if (ImGui.button("Resume", 180, 30)) {
            isPaused = false;
            glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
        }
        ImGui.spacing();
        if (ImGui.button("Save Game", 180, 30))  SaveManager.saveGame(world, player, inventory);
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
        for (int i = Math.max(0, chatHistory.size() - 10); i < chatHistory.size(); i++)
            ImGui.text(chatHistory.get(i));
        if (showChat) {
            ImGui.setKeyboardFocusHere();
            if (ImGui.inputText("##chat", chatInput,
                    imgui.flag.ImGuiInputTextFlags.EnterReturnsTrue)) {
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
                new Vector3f(bx-e,   by-e,   bz-e),   new Vector3f(bx+1+e, by-e,   bz-e),
                new Vector3f(bx+1+e, by+1+e, bz-e),   new Vector3f(bx-e,   by+1+e, bz-e),
                new Vector3f(bx-e,   by-e,   bz+1+e), new Vector3f(bx+1+e, by-e,   bz+1+e),
                new Vector3f(bx+1+e, by+1+e, bz+1+e), new Vector3f(bx-e,   by+1+e, bz+1+e)
        };
        org.joml.Vector4f[] proj = new org.joml.Vector4f[8];
        for (int i = 0; i < 8; i++) {
            proj[i] = new org.joml.Vector4f(corners[i].x, corners[i].y, corners[i].z, 1.0f).mul(viewProj);
            if (proj[i].w > 0) { proj[i].x /= proj[i].w; proj[i].y /= proj[i].w; }
        }
        int finalColor = ImGui.colorConvertFloat4ToU32(0.1f, 0.1f, 0.1f, 0.5f + breakProgress * 0.4f);
        float finalThickness = 2.0f + breakProgress * 3.0f;
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
        draw.addLine(cx - 11, cy, cx + 11, cy, black, 3.0f);
        draw.addLine(cx, cy - 11, cx, cy + 11, black, 3.0f);
        draw.addLine(cx - 10, cy, cx + 10, cy, white, 1.5f);
        draw.addLine(cx, cy - 10, cx, cy + 10, white, 1.5f);

        // Health bar
        float hpWidth = 200f, hpHeight = 15f;
        float hpX = cx - hpWidth / 2.0f, hpY = screenH - 75f;
        draw.addRectFilled(hpX, hpY, hpX + hpWidth, hpY + hpHeight,
                ImGui.colorConvertFloat4ToU32(0.2f, 0, 0, 0.8f));
        float fillW = hpWidth * (Math.max(0, player.health) / player.maxHealth);
        draw.addRectFilled(hpX, hpY, hpX + fillW, hpY + hpHeight,
                ImGui.colorConvertFloat4ToU32(0.8f, 0.1f, 0.1f, 1.0f));
        draw.addRect(hpX, hpY, hpX + hpWidth, hpY + hpHeight, black, 0f, 0, 2.0f);

        // Hotbar
        float slotSize = 40.0f, spacing = 5.0f;
        int   numSlots = 9;
        float startX = cx - ((numSlots * slotSize + (numSlots - 1) * spacing) / 2.0f);
        float startY = screenH - slotSize - 10.0f;
        selectedBlock = hotbar[selectedSlot];

        for (int i = 0; i < numSlots; i++) {
            float x = startX + i * (slotSize + spacing);
            int bgCol  = (i == selectedSlot)
                    ? ImGui.colorConvertFloat4ToU32(0.6f, 0.6f, 0.6f, 0.8f)
                    : ImGui.colorConvertFloat4ToU32(0.2f, 0.2f, 0.2f, 0.8f);
            draw.addRectFilled(x, startY, x + slotSize, startY + slotSize, bgCol, 4.0f);
            int outCol = (i == selectedSlot)
                    ? ImGui.colorConvertFloat4ToU32(1.0f, 1.0f, 1.0f, 1.0f)
                    : ImGui.colorConvertFloat4ToU32(0.0f, 0.0f, 0.0f, 0.8f);
            draw.addRect(x, startY, x + slotSize, startY + slotSize, outCol, 4.0f, 0,
                    (i == selectedSlot) ? 3.0f : 1.5f);
            Block b     = hotbar[i];
            int   count = inventory.getCount(b);
            if (b != Block.AIR && count > 0) {
                float shrink = 8.0f;
                int blockCol = ImGui.colorConvertFloat4ToU32(b.r, b.g, b.b, 1.0f);
                draw.addRectFilled(x + shrink, startY + shrink,
                        x + slotSize - shrink, startY + slotSize - shrink, blockCol, 2.0f);
                draw.addRect(x + shrink, startY + shrink,
                        x + slotSize - shrink, startY + slotSize - shrink, black, 2.0f, 0, 1.5f);
                String countStr = String.valueOf(count);
                draw.addText(x + slotSize - 14, startY + slotSize - 18, black, countStr);
                draw.addText(x + slotSize - 15, startY + slotSize - 19, white, countStr);
            }
        }

        // ── FLIGHT MODE INDICATOR ─────────────────────────────────────────────
        if (player.debugMode) {
            FlightController.FlightMode mode = player.flightController.getMode();
            String modeLabel = switch (mode) {
                case SKIM    -> "✦ SKIM";
                case SOAR    -> "✦ SOAR";
                case GRAPPLE -> "✦ GRAPPLE";
            };
            int modeColor = switch (mode) {
                case SKIM    -> ImGui.colorConvertFloat4ToU32(0.4f, 0.9f, 0.4f, 0.9f);
                case SOAR    -> ImGui.colorConvertFloat4ToU32(0.4f, 0.7f, 1.0f, 0.9f);
                case GRAPPLE -> ImGui.colorConvertFloat4ToU32(1.0f, 0.75f, 0.2f, 0.9f);
            };
            draw.addText(cx - 30, cy - 60, black, modeLabel);
            draw.addText(cx - 31, cy - 61, modeColor, modeLabel);
            draw.addText(cx - 55, cy - 44, black, "[V] cycle mode");
            draw.addText(cx - 56, cy - 45,
                    ImGui.colorConvertFloat4ToU32(0.8f, 0.8f, 0.8f, 0.7f), "[V] cycle mode");

            // ── GRAPPLE FEEDBACK ─────────────────────────────────────────────
            // Gives the player clear state feedback so they always know whether
            // they are hooked, where the anchor is, and how to use it.
            if (mode == FlightController.FlightMode.GRAPPLE) {
                FlightController fc = player.flightController;
                int grappleGold = ImGui.colorConvertFloat4ToU32(1.0f, 0.78f, 0.1f, 1.0f);
                int grappleRed  = ImGui.colorConvertFloat4ToU32(1.0f, 0.35f, 0.1f, 0.9f);
                int grappleGrey = ImGui.colorConvertFloat4ToU32(0.75f, 0.75f, 0.75f, 0.7f);

                if (fc.isHooked()) {
                    // Show hook distance
                    float hookDist = new Vector3f(fc.getHookPoint()).sub(player.position).length();
                    String hookedStr = String.format("⦿ ZIPPING  %.0fm  Release to launch", hookDist);
                    draw.addText(cx - 72, cy + 28, black, hookedStr);
                    draw.addText(cx - 73, cy + 27, grappleGold, hookedStr);

                    // Draw a small "anchor" diamond at crosshair center to distinguish state
                    float d = 7f;
                    draw.addLine(cx,     cy - d, cx + d, cy,     grappleGold, 2.0f);
                    draw.addLine(cx + d, cy,     cx,     cy + d, grappleGold, 2.0f);
                    draw.addLine(cx,     cy + d, cx - d, cy,     grappleGold, 2.0f);
                    draw.addLine(cx - d, cy,     cx,     cy - d, grappleGold, 2.0f);
                } else {
                    // Unhooked: show targeting guide
                    String aimStr = "[ ] AIM + Hold [RIGHT-CLICK] or [F]";
                    draw.addText(cx - 88, cy + 28, black, aimStr);
                    draw.addText(cx - 89, cy + 27, grappleGrey, aimStr);

                    // Draw a square targeting reticle around the crosshair
                    float s = 12f;
                    // Four corner brackets
                    draw.addLine(cx - s, cy - s, cx - s + 5, cy - s, grappleRed, 1.5f);
                    draw.addLine(cx - s, cy - s, cx - s, cy - s + 5, grappleRed, 1.5f);
                    draw.addLine(cx + s, cy - s, cx + s - 5, cy - s, grappleRed, 1.5f);
                    draw.addLine(cx + s, cy - s, cx + s, cy - s + 5, grappleRed, 1.5f);
                    draw.addLine(cx - s, cy + s, cx - s + 5, cy + s, grappleRed, 1.5f);
                    draw.addLine(cx - s, cy + s, cx - s, cy + s - 5, grappleRed, 1.5f);
                    draw.addLine(cx + s, cy + s, cx + s - 5, cy + s, grappleRed, 1.5f);
                    draw.addLine(cx + s, cy + s, cx + s, cy + s - 5, grappleRed, 1.5f);
                }
            }
        }

        // ── SMASH INDICATOR ───────────────────────────────────────────────────
        if (player.isSmashing()) {
            draw.addText(cx - 35, cy - 90, black, "▼ SMASHING ▼");
            draw.addText(cx - 36, cy - 91,
                    ImGui.colorConvertFloat4ToU32(1.0f, 0.3f, 0.1f, 1.0f), "▼ SMASHING ▼");
        }

        // ── TIME SCALE INDICATOR ──────────────────────────────────────────────
        TimeController tc = TimeController.getInstance();
        if (tc.getSlownessFactor() > 0.05f || tc.getFastnessFactor() > 0.05f) {
            String timeLabel = tc.getSlownessFactor() > 0.05f
                    ? String.format("⧗ %.2fx", tc.getScale())
                    : String.format("⚡ %.1fx", tc.getScale());
            int timeColor = tc.getSlownessFactor() > 0.05f
                    ? ImGui.colorConvertFloat4ToU32(0.6f, 0.7f, 1.0f, 0.9f)
                    : ImGui.colorConvertFloat4ToU32(1.0f, 0.65f, 0.2f, 0.9f);
            draw.addText(screenW - 80f, 12f, black, timeLabel);
            draw.addText(screenW - 81f, 11f, timeColor, timeLabel);
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
            world.rebuildChunkAt(tx, ty, tz);
            if (network != null && network.connected) network.sendBreak(tx, ty, tz);
            breakProgress = 0.0f;
        }
    }

    private void renderDebugMenu() {
        ImGui.begin("Debug");
        ImGui.text(String.format("XYZ: %.3f / %.3f / %.3f",
                player.position.x, player.position.y, player.position.z));
        ImGui.text(String.format("FPS: %.1f",   ImGui.getIO().getFramerate()));
        ImGui.text(String.format("Seed: %d", GameConfig.seed));
        ImGui.text(String.format("DeltaTime: %.4fs", ImGui.getIO().getDeltaTime()));
        ImGui.text(String.format("TimeScale: %.3f",  TimeController.getInstance().getScale()));
        if (player.debugMode) {
            ImGui.text("Flight mode: " + player.flightController.getMode().name());
        }
        ImGui.separator();
        float[] fov = {GameConfig.fov};
        if (ImGui.sliderFloat("FOV", fov, 30f, 120f)) GameConfig.fov = fov[0];
        int[] rd = {GameConfig.renderDistance};
        if (ImGui.sliderInt("Render Distance", rd, 2, 16)) GameConfig.renderDistance = rd[0];
        ImGui.separator();
        // ── Seed editor ───────────────────────────────────────────────────────
        ImGui.text("World Seed:");
        ImGui.setNextItemWidth(140);
        // Pre-fill with current seed on first open
        if (seedInput.get().isEmpty()) seedInput.set(String.valueOf(GameConfig.seed));
        ImGui.inputText("##seedEdit", seedInput);
        ImGui.sameLine();
        if (ImGui.button("New World")) {
            try {
                long newSeed = Long.parseLong(seedInput.get().trim());
                GameConfig.seed  = newSeed;
                worldGen.resetSeed(newSeed);
                world.clearAllChunks();
                world.meshingQueue.clear();
                player.position.set(777f, 250f, 777f);
                isPreloading = true;
                // Close debug menu so the preload screen shows
                showDebug = false;
                glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
            } catch (NumberFormatException ignored) {
                // Bad input — do nothing
            }
        }
        ImGui.end();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private boolean playerOccupies(int bx, int by, int bz) {
        float px = player.position.x, py = player.position.y, pz = player.position.z;
        return px + 0.3f > bx && px - 0.3f < bx + 1
                && py + 1.8f > by && py < by + 1
                && pz + 0.3f > bz && pz - 0.3f < bz + 1;
    }

    private boolean remotePlayerOccupies(int bx, int by, int bz) {
        if (network == null || !network.connected) return false;
        float px = remotePlayer.x, py = remotePlayer.y, pz = remotePlayer.z;
        return px + 0.3f > bx && px - 0.3f < bx + 1
                && py + 1.8f > by && py < by + 1
                && pz + 0.3f > bz && pz - 0.3f < bz + 1;
    }

    private Mesh getItemMesh(Block block) {
        return itemMeshes.computeIfAbsent(block, b -> {
            List<Float>   verts   = new ArrayList<>();
            List<Integer> idx     = new ArrayList<>();
            int[]         vIndex  = {0};
            float w   = 0.12f;
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
            idx.add(b); idx.add(b+1); idx.add(b+2);
            idx.add(b+2); idx.add(b+3); idx.add(b);
            vIndex[0] += 4;
        }
    }

    // ── Frustum culling ───────────────────────────────────────────────────────

    private float[] extractFrustumPlanes(Matrix4f vp) {
        float[] planes = new float[24];
        planes[0]  = vp.m03() + vp.m00(); planes[1]  = vp.m13() + vp.m10();
        planes[2]  = vp.m23() + vp.m20(); planes[3]  = vp.m33() + vp.m30();
        planes[4]  = vp.m03() - vp.m00(); planes[5]  = vp.m13() - vp.m10();
        planes[6]  = vp.m23() - vp.m20(); planes[7]  = vp.m33() - vp.m30();
        planes[8]  = vp.m03() + vp.m01(); planes[9]  = vp.m13() + vp.m11();
        planes[10] = vp.m23() + vp.m21(); planes[11] = vp.m33() + vp.m31();
        planes[12] = vp.m03() - vp.m01(); planes[13] = vp.m13() - vp.m11();
        planes[14] = vp.m23() - vp.m21(); planes[15] = vp.m33() - vp.m31();
        planes[16] = vp.m03() + vp.m02(); planes[17] = vp.m13() + vp.m12();
        planes[18] = vp.m23() + vp.m22(); planes[19] = vp.m33() + vp.m32();
        planes[20] = vp.m03() - vp.m02(); planes[21] = vp.m13() - vp.m12();
        planes[22] = vp.m23() - vp.m22(); planes[23] = vp.m33() - vp.m32();
        for (int i = 0; i < 6; i++) {
            float len = (float)Math.sqrt(
                    planes[i*4] * planes[i*4] + planes[i*4+1] * planes[i*4+1]
                            + planes[i*4+2] * planes[i*4+2]);
            planes[i*4] /= len; planes[i*4+1] /= len;
            planes[i*4+2] /= len; planes[i*4+3] /= len;
        }
        return planes;
    }

    private boolean isAabbInFrustum(float[] planes, Chunk chunk) {
        if (chunk.minBlockY > chunk.maxBlockY) return false;
        float minX = chunk.cx * Chunk.SIZE;
        float minZ = chunk.cz * Chunk.SIZE;
        float minY = chunk.cy * Chunk.HEIGHT + chunk.minBlockY;
        float maxX = minX + Chunk.SIZE;
        float maxZ = minZ + Chunk.SIZE;
        float maxY = chunk.cy * Chunk.HEIGHT + chunk.maxBlockY + 1;
        for (int i = 0; i < 6; i++) {
            int p = i * 4;
            float px = planes[p]   > 0 ? maxX : minX;
            float py = planes[p+1] > 0 ? maxY : minY;
            float pz = planes[p+2] > 0 ? maxZ : minZ;
            if (planes[p]*px + planes[p+1]*py + planes[p+2]*pz + planes[p+3] < 0) return false;
        }
        return true;
    }

    /**
     * Maps a collected block to an empty hotbar slot.
     * Preserved from the original codebase.
     */
    private void addBlockToHotbar(Block block) {
        if (block == Block.AIR) return;
        for (Block b : hotbar) { if (b == block) return; }
        for (int i = 0; i < hotbar.length; i++) {
            if (hotbar[i] == Block.AIR || inventory.getCount(hotbar[i]) <= 0) {
                hotbar[i] = block; return;
            }
        }
    }
}