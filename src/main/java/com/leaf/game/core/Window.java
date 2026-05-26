package com.leaf.game.core;

import com.leaf.game.entity.AttackController;
import com.leaf.game.entity.DroppedItem;
import com.leaf.game.entity.Enemy;
import com.leaf.game.entity.EnemyManager;
import com.leaf.game.entity.FlightController;
import com.leaf.game.entity.Inventory;
import com.leaf.game.entity.Player;
import com.leaf.game.entity.RemotePlayer;
import com.leaf.game.entity.SealController;
import com.leaf.game.entity.StandController;
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
import com.leaf.game.util.Camera;
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

    // ── ENEMY SYSTEM ──────────────────────────────────────────────────────────
    private EnemyManager enemyManager;
    /** Edge-detect for P key to spawn enemies. */
    private boolean lastP = false;

    private float   breakProgress = 0.0f;
    private int     breakX, breakY, breakZ;
    private boolean breakingActive = false;

    // ── PRE-GENERATION STATE ──────────────────────────────────────────────────
    private boolean     isPreloading = false;
    private int         preloadRadius = 10;
    private final List<Chunk> chunksToGenerate = new ArrayList<>();
    private int         totalPreloadCount    = 0;
    private int         currentPreloadProgress = 0;
    // Network Timing & State Trackers
    private double lastNetSendTime = 0;
    private int    lastNetState = 0;
    private boolean lastNetHooked = false;

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
    private float activeShakeAmplitude = GameConfig.smashShakeAmplitude; // Dynamic amplitude
    private float activeShakeDuration  = GameConfig.smashShakeDuration;  // Dynamic duration

    // ── METEOR EFFECT (Smash visual) ─────────────────────────────────────────
    // When a ground smash begins, a STAR_IRON DroppedItem is spawned high above
    // the player and falls at high speed — giving the descent a "meteor crashing
    // from the sky" visual without requiring a particle system.
    private boolean wasSmashing      = false;
    private boolean wasCannonballing = false;
    private boolean wasCharging      = false;   // edge-detect for preload trigger
    private float   pathReadiness    = 0f;      // 0..1 shown in HUD during charging
    private boolean clientSpawnedAtHost = false;
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
        // Free all GPU model/texture assets before the GL context is destroyed.
        com.leaf.game.render.AssetManager.get().cleanup();
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
                // While piloting the stand drone OR auto-aiming at an enemy, LMB is
                // consumed by the stand — block normal block-breaking.
                boolean standConsumedLMB = player.stand.isInStandPerspective()
                        || player.stand.autoAimedThisFrame;
                if (!standConsumedLMB) {
                    breakingActive = (action == GLFW_PRESS || action == GLFW_REPEAT);
                    if (action == GLFW_RELEASE) breakProgress = 0.0f;
                }
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

            // Stand perspective: route look to stand's own camera, not the player.
            if (player.stand.isInStandPerspective()) {
                player.stand.applyMouseLook(dx, dy);
                return;
            }

            // Smashing/Rewinding: camera auto-driven, block mouse entirely.
            // Charging: camera locked to aim direction — the system needs this
            //   window to preload exactly the chunks the player will see.
            // Flying (isCannonballing): full 360° free look, no pitch clamp.
            if (!player.isSmashing() && !player.abilities.isRewinding
                    && !player.abilities.isCharging()) {
                camera.yaw   += dx * GameConfig.mouseSensitivity;
                camera.pitch -= dy * GameConfig.mouseSensitivity;
                if (!player.abilities.isCannonballing) {
                    camera.clampPitch();
                }
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

        // ── ENEMY SYSTEM ─────────────────────────────────────────────────────
        this.enemyManager = new EnemyManager();
        player.stand.setEnemyManager(enemyManager);

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
                // During cannonball and charging: drain the mesh queue much faster.
                // At 140 blocks/s the player crosses ~4 chunk columns per second;
                // the default cap of 3 per frame can't keep up. 20 per frame clears
                // a full preloaded queue within one second without spiking frame time
                // (each buildChunkMeshes call is ~0.5–1 ms on average hardware).
                boolean cannonActive = player.abilities.isCannonballing
                        || player.abilities.isCharging();
                int maxMeshesPerFrame = isPreloading ? 24 : cannonActive ? 20 : 4;
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
                        // Zero out any velocity accumulated during loading so the player
                        // doesn't punch straight through the freshly-meshed ground.
                        player.setVelocityY(0f);
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

                        // ── ATTACK DEBRIS DRAIN ────────────────────────────────
                        // AttackController queues DebrisSpawn records rather than
                        // touching droppedItems directly.  Drain them here each frame.
                        for (AttackController.DebrisSpawn d : player.attacks.pendingDebris) {
                            droppedItems.add(new DroppedItem(d.bx, d.by, d.bz, d.block, d.vel));
                        }
                        player.attacks.pendingDebris.clear();

                        // ── ATTACK SHAKE REQUEST ───────────────────────────────
                        if (player.attacks.shakeRequest > 0f) {
                            float req = player.attacks.shakeRequest;
                            activeShakeDuration  = req * 0.7f;
                            activeShakeAmplitude = 0.12f + req * 0.25f;
                            smashShakeTimer      = activeShakeDuration;
                            player.attacks.shakeRequest = 0f;
                        }

                        // ── STAND DEBRIS DRAIN (Manhattan Transfer) ────────────
                        for (AttackController.DebrisSpawn d : player.stand.pendingDebris) {
                            droppedItems.add(new DroppedItem(d.bx, d.by, d.bz, d.block, d.vel));
                        }
                        player.stand.pendingDebris.clear();

                        // ── STAND SHAKE REQUEST ────────────────────────────────
                        if (player.stand.shakeRequest > 0f) {
                            float req = player.stand.shakeRequest;
                            activeShakeDuration  = req * 0.7f;
                            activeShakeAmplitude = 0.12f + req * 0.25f;
                            smashShakeTimer      = Math.max(smashShakeTimer, activeShakeDuration);
                            player.stand.shakeRequest = 0f;
                        }

                        // ── ENEMY SYSTEM UPDATE ────────────────────────────────
                        // Drain explosion events from attack and stand bolts.
                        for (float[] ev : player.attacks.pendingExplosions) {
                            enemyManager.processExplosion(ev);
                        }
                        player.attacks.pendingExplosions.clear();

                        for (float[] ev : player.stand.pendingExplosions) {
                            enemyManager.processExplosion(ev);
                        }
                        player.stand.pendingExplosions.clear();

                        for (float[] ev : player.attacks.pendingMeleeArcs) {
                            enemyManager.processMeleeArc(ev);
                        }
                        player.attacks.pendingMeleeArcs.clear();

                        // Update all enemies (gravity, death fade, etc.)
                        enemyManager.update(deltaTime, world);

                        // P key — spawn test enemy at crosshair hit point
                        boolean pHeld = glfwGetKey(window, GLFW_KEY_P) == GLFW_PRESS;
                        if (pHeld && !lastP) {
                            RaycastResult hit = player.getTargetBlock(camera, world);
                            if (hit != null && hit.hit) {
                                // Spawn on top of the targeted surface block
                                enemyManager.spawnAt(
                                        hit.placeX + 0.5f,
                                        hit.placeY,
                                        hit.placeZ + 0.5f);
                            }
                        }
                        lastP = pHeld;

                        // ── CANNONBALL: preload chunks at CHARGE START ─────────
                        // The charge window (~2.5 s) is used as preload time.
                        // On the leading edge of isCharging(), immediately queue
                        // a full tube of chunks around the max-power trajectory.
                        // Camera is locked during charging so the preload direction
                        // exactly matches what the player will see in flight.
                        //
                        // pathReadiness polls on every frame during charging so the
                        // HUD shows a live percentage as chunks become meshed.
                        boolean nowCharging      = player.abilities.isCharging();
                        boolean nowCannonballing = player.abilities.isCannonballing;

                        if (nowCharging && !wasCharging) {
                            // Leading edge: calculate max-power velocity along locked dir
                            float lYaw   = player.abilities.lockedYaw;
                            float lPitch = player.abilities.lockedPitch;
                            float speed  = GameConfig.cannonMaxPower;
                            float mvx = (float)(Math.cos(lPitch) * Math.cos(lYaw)) * speed;
                            float mvy = (float)(Math.sin(lPitch))                  * speed;
                            float mvz = (float)(Math.cos(lPitch) * Math.sin(lYaw)) * speed;
                            int sideR = Math.min(GameConfig.renderDistance, 4);
                            world.preloadChunksAroundPath(
                                    player.position.x, player.position.y, player.position.z,
                                    mvx, mvy, mvz, worldGen, sideR);
                            pathReadiness = 0f;
                        }

                        if (nowCharging) {
                            // Poll readiness fraction every frame for the HUD
                            float lYaw   = player.abilities.lockedYaw;
                            float lPitch = player.abilities.lockedPitch;
                            float speed  = GameConfig.cannonMaxPower;
                            float mvx = (float)(Math.cos(lPitch) * Math.cos(lYaw)) * speed;
                            float mvy = (float)(Math.sin(lPitch))                  * speed;
                            float mvz = (float)(Math.cos(lPitch) * Math.sin(lYaw)) * speed;
                            pathReadiness = world.pathReadinessFraction(
                                    player.position.x, player.position.y, player.position.z,
                                    mvx, mvy, mvz);
                        } else {
                            pathReadiness = 0f;
                        }

                        wasCharging      = nowCharging;
                        wasCannonballing = nowCannonballing;

                    } else {
                        breakingActive = false;
                    }
                }

                if (network != null && network.connected) {
                    if (network.seedReceived) {
                        GameConfig.seed = network.newSeed;
                        world.clearAllChunks();
                        worldGen.resetSeed(GameConfig.seed);

                        // We remove the old "player.position.y = 100.0f;" drop
                        // because we are going to teleport to the host instead.
                        network.seedReceived = false;
                    }

                    // ── TELEPORT CLIENT TO HOST SPAWN ──
                    if (!network.isHost() && !clientSpawnedAtHost && (network.remoteX != 0f || network.remoteZ != 0f)) {
                        // Place the client 2 blocks away from the host horizontally to avoid physics collisions,
                        // and set Y to 250.0f. This forces the preloader to build the chunks around the host
                        // and drop the client safely onto the terrain surface once loaded.
                        player.position.set(network.remoteX + 2.0f, 250.0f, network.remoteZ + 2.0f);
                        player.highestY = 250.0f;
                        clientSpawnedAtHost = true;
                    }

                    // 1. Detect & Send Discrete State Changes (Instantly)
                    int currentState = 0;
                    if (player.abilities.isDashing) currentState = 1;
                    else if (player.abilities.isCannonballing) currentState = 2;
                    else if (player.abilities.isRewinding) currentState = 3;
                    else if (player.isSmashing()) currentState = 4;
                    else if (player.debugMode) currentState = player.flightController.getMode() == FlightController.FlightMode.SOAR ? 5 : 6;

                    if (currentState != lastNetState) {
                        network.sendState(currentState);
                        lastNetState = currentState;
                    }

                    boolean currentHooked = player.flightController.isHooked();
                    if (currentHooked != lastNetHooked) {
                        Vector3f hp = player.flightController.getHookPoint();
                        network.sendGrapple(currentHooked, hp != null ? hp.x : 0, hp != null ? hp.y : 0, hp != null ? hp.z : 0);
                        lastNetHooked = currentHooked;
                    }

                    // 2. Rate-Limit Position Sync to 30Hz (Bandwidth Optimization)
                    if (now - lastNetSendTime >= (1.0 / 30.0)) {
                        network.sendPosition(player.position.x, player.position.y, player.position.z,
                                camera.yaw, camera.pitch, player.getCameraRoll());
                        lastNetSendTime = now;
                    }

                    // 3. Update Remote Player state from network
                    remotePlayer.targetX = network.remoteX;
                    remotePlayer.targetY = network.remoteY;
                    remotePlayer.targetZ = network.remoteZ;
                    remotePlayer.targetYaw = network.remoteYaw;
                    remotePlayer.targetPitch = network.remotePitch;
                    remotePlayer.targetRoll = network.remoteRoll;
                    remotePlayer.targetState = network.remoteState;
                    remotePlayer.targetHooked = network.remoteHooked;
                    remotePlayer.targetHookX = network.remoteHookX;
                    remotePlayer.targetHookY = network.remoteHookY;
                    remotePlayer.targetHookZ = network.remoteHookZ;

                    // CRITICAL: Update remote player using rawDeltaTime
                    // This prevents the remote player from stuttering if local time dilation is active
                    remotePlayer.update(rawDeltaTime);

                    // ... [rest of the polling block: pollBreak, pollChat, etc.] ...
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

                // ── ABILITY + ATTACK OVERLAY VIGNETTE ────────────────────────
                // Use whichever overlay is currently stronger so neither system
                // silently stomps the other during simultaneous effects.
                float abilityOverlayStr = player.abilities.getOverlayStrength();
                float attackOverlayStr  = player.attacks.getOverlayStrength();
                Vector3f compositeOverlayColor;
                float    compositeOverlayStr;
                if (attackOverlayStr >= abilityOverlayStr) {
                    compositeOverlayColor = player.attacks.getOverlayColor();
                    compositeOverlayStr   = attackOverlayStr;
                } else {
                    compositeOverlayColor = player.abilities.getOverlayColor();
                    compositeOverlayStr   = abilityOverlayStr;
                }
                // ── SEAL TELEPORT OVERLAY ─────────────────────────────────
                // White-ish flash when the player zips to a seal.
                if (player.seals.teleportFlash > 0f) {
                    float flashStr = (player.seals.teleportFlash / GameConfig.sealTeleportFlash) * 0.55f;
                    if (flashStr > compositeOverlayStr) {
                        compositeOverlayStr   = flashStr;
                        compositeOverlayColor = new Vector3f(0.95f, 0.98f, 1.0f);
                    }
                }

                shader.setUniform("overlayVignetteStrength", compositeOverlayStr);
                shader.setUniform("overlayVignetteColor",    compositeOverlayColor);
                // Default alpha multiplier (1.0 = no change). Ghost rendering overrides this.
                shader.setUniform("alphaMultiplier", 1.0f);
                // Default: no texture sampling. Set to 1 + bind texture for ModelMesh rendering.
                shader.setUniform("useTexture", 0);

                // ── FLIGHT CAMERA EFFECTS ─────────────────────────────────────
                // Set dynamic FOV from flight controller boost (player camera only).
                // Suppressed when piloting the stand drone.
                boolean inStandView = player.stand.isInStandPerspective();
                if (!inStandView) {
                    float fovBoost = player.getCameraFovBoost();
                    camera.dynamicFov = (fovBoost > 0.1f) ? GameConfig.fov + fovBoost : -1f;
                }

                // ── VIEW MATRIX + ROLL ────────────────────────────────────────
                // When piloting the drone, all cosmetic effects are suppressed:
                // no roll, no screen shake, no attack pitch — clean drone view only.
                // All rendering uses standCamera so the player sees through the drone.
                Matrix4f baseView;
                float    rollAngle;
                Matrix4f projection;
                Vector3f shakeOffset;

                if (inStandView) {
                    Camera sc   = player.stand.standCamera;
                    sc.dynamicFov = -1f; // standard FOV while piloting
                    baseView    = sc.getViewMatrix();
                    rollAngle   = 0f;
                    projection  = sc.getProjectionMatrix();
                    shakeOffset = new Vector3f(0f);
                } else {
                    // Attack pitch offset is non-destructive: add, build, subtract.
                    float attackPitch = player.attacks.getPitchOffset();
                    camera.pitch += attackPitch;
                    baseView = camera.getViewMatrix();
                    camera.pitch -= attackPitch;
                    rollAngle   = player.getCameraRoll();
                    projection  = camera.getProjectionMatrix();
                    shakeOffset = computeShakeOffset(rawDeltaTime);
                }

                Matrix4f view;
                if (Math.abs(rollAngle) > 0.0005f) {
                    // Rotate around the camera's forward axis (Z in view space)
                    // by applying rotateZ BEFORE the view matrix (in world space
                    // this means we tilt the camera around its own look axis).
                    Matrix4f rollMat = new Matrix4f().rotateZ(rollAngle);
                    view = rollMat.mul(baseView);
                } else {
                    view = baseView;
                }

                // ── SCREEN SHAKE ──────────────────────────────────────────────
                // Damped sinusoidal offset on camera position for smashShakeDuration.
                // We temporarily move camera.position, build the MVP, then restore it.
                if (shakeOffset.lengthSquared() > 0f) {
                    camera.position.add(shakeOffset);
                    view = camera.getViewMatrix(); // recompute with shaken position
                    if (Math.abs(rollAngle) > 0.0005f) {
                        view = new Matrix4f().rotateZ(rollAngle).mul(view);
                    }
                }

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
                boolean grappleActive = player.debugMode && fc.getMode() == FlightController.FlightMode.GRAPPLE;
                boolean voidAiming = player.attacks.getChargeFrac() > 0f;

                if (grappleActive || voidAiming) {
                    Vector3f playerHand = new Vector3f(player.position.x, player.position.y + 0.9f, player.position.z);
                    Vector3f targetPoint = null;
                    boolean isLaser = false;
                    Block renderBlock = Block.CRYSTAL_AMETHYST;

                    if (grappleActive) {
                        targetPoint = fc.isHooked() ? fc.getHookPoint() : fc.getAimTarget(camera, world);
                        isLaser = !fc.isHooked();
                        renderBlock = isLaser ? Block.CRYSTAL_ROSE : Block.CRYSTAL_AMETHYST;
                    } else if (voidAiming) {
                        // Void shard laser sight!
                        targetPoint = player.attacks.getAimTarget(camera, world);
                        isLaser = true;
                        renderBlock = Block.CRYSTAL_AMETHYST;
                    }

                    if (targetPoint != null) {
                        Vector3f ropeDir  = new Vector3f(targetPoint).sub(playerHand);
                        float    ropeDist = ropeDir.length();

                        if (ropeDist > 0.1f) {
                            ropeDir.normalize();
                            org.joml.Quaternionf ropeRot = new org.joml.Quaternionf().rotationTo(new org.joml.Vector3f(0, 0, 1), ropeDir);

                            float thickness = isLaser ? 0.008f : 0.04f;
                            // Make Void Shard laser pulse slightly with charge
                            if (voidAiming) thickness += player.attacks.getChargeFrac() * 0.015f;

                            Matrix4f ropeModel = new Matrix4f()
                                    .translate(playerHand.x, playerHand.y, playerHand.z)
                                    .rotate(ropeRot)
                                    .translate(0f, 0f, ropeDist * 0.5f)
                                    .scale(thickness, thickness, ropeDist / 0.24f);

                            Matrix4f ropeMvp = new Matrix4f(projection).mul(view).mul(ropeModel);
                            shader.setUniform("mvp", ropeMvp);

                            getItemMesh(renderBlock).render();
                        }
                    }
                }
                // ── VOID SHARD BOLTS ──────────────────────────────────────────
                // Each bolt is rendered as a spinning, slightly scaled crystal cube.
                // Scale grows with charge so a full-power shot looks visibly larger.
                glEnable(GL_BLEND);
                glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
                for (AttackController.ActiveBolt bolt : player.attacks.activeBolts) {
                    float scale = 0.20f + bolt.chargeF * 0.24f;
                    Matrix4f boltModel = new Matrix4f()
                            .translate(bolt.pos.x, bolt.pos.y, bolt.pos.z)
                            .rotateY(bolt.spinPhase)
                            .rotateX(bolt.spinPhase * 0.6f)
                            .rotateZ(bolt.spinPhase * 0.4f)
                            .scale(scale);
                    shader.setUniform("mvp", new Matrix4f(projection).mul(view).mul(boltModel));
                    getItemMesh(Block.CRYSTAL_AMETHYST).render();
                }
                glDisable(GL_BLEND);

                // ── STAND DRONE + BOLTS (Manhattan Transfer) ─────────────────
                if (player.stand.isDeployed()) {
                    float bob      = (float)Math.sin(player.stand.bobPhase) * GameConfig.standHoverBob;
                    float droneSpin = (float)(glfwGetTime() * 1.5);
                    Vector3f sp    = player.stand.standPos;

                    // Render drone using AssetManager model (or procedural saucer fallback)
                    glEnable(GL_BLEND);
                    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
                    shader.setUniform("alphaMultiplier", 1.0f);
                    Matrix4f droneModel = new Matrix4f()
                            .translate(sp.x, sp.y + bob, sp.z)
                            .rotateY(droneSpin)
                            .scale(0.55f);
                    shader.setUniform("mvp", new Matrix4f(projection).mul(view).mul(droneModel));
                    // Optionally bind texture if available
                    com.leaf.game.render.Texture standTex = com.leaf.game.render.AssetManager.get().getTexture("stand");
                    if (standTex != null) {
                        shader.setUniform("useTexture", 1);
                        standTex.bind();
                    }
                    com.leaf.game.render.AssetManager.get().getModel("stand").render();
                    if (standTex != null) {
                        shader.setUniform("useTexture", 0);
                    }

                    // Blocked-LOS warning: overlay orange flash on drone when shot is blocked
                    float blockedF = player.stand.getBlockedFlash();
                    if (blockedF > 0f) {
                        float alpha = (blockedF / GameConfig.standBlockedFlashTime) * 0.55f;
                        shader.setUniform("alphaMultiplier", alpha);
                        shader.setUniform("mvp", new Matrix4f(projection).mul(view).mul(droneModel));
                        getItemMesh(Block.CRATER_BLOOM).render(); // warm orange tint
                    }
                    glDisable(GL_BLEND);

                    // Render stand redirect bolts as spinning CRYSTAL_CITRINE cubes
                    glEnable(GL_BLEND);
                    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
                    for (StandController.StandBolt bolt : player.stand.activeBolts) {
                        Matrix4f boltModel = new Matrix4f()
                                .translate(bolt.pos.x, bolt.pos.y, bolt.pos.z)
                                .rotateY(bolt.spinPhase)
                                .rotateX(bolt.spinPhase * 0.6f)
                                .rotateZ(bolt.spinPhase * 0.4f)
                                .scale(0.18f);
                        shader.setUniform("alphaMultiplier", 1.0f);
                        shader.setUniform("mvp", new Matrix4f(projection).mul(view).mul(boltModel));
                        getItemMesh(Block.CRYSTAL_CITRINE).render();
                    }

                    glDisable(GL_BLEND);
                }

                // ── SEALS (Minato's Seal) ────────────────────────────────────
                // In-flight seal projectiles
                glEnable(GL_BLEND);
                glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
                for (SealController.SealProjectile proj : player.seals.inFlightSeals) {
                    Matrix4f projModel = new Matrix4f()
                            .translate(proj.pos.x, proj.pos.y, proj.pos.z)
                            .rotateY(proj.spinPhase)
                            .scale(0.15f);
                    shader.setUniform("alphaMultiplier", 1.0f);
                    shader.setUniform("mvp", new Matrix4f(projection).mul(view).mul(projModel));
                    getItemMesh(Block.CRYSTAL_CITRINE).render();
                }
                // ── STAND DRONE + BOLTS (Manhattan Transfer) ─────────────────
                if (player.stand.isDeployed()) {
                    float bob      = (float)Math.sin(player.stand.bobPhase) * GameConfig.standHoverBob;
                    float droneSpin = (float)(glfwGetTime() * 1.5);
                    Vector3f sp    = player.stand.standPos;

                    // ── FIX: Only render the physical drone body if we are NOT piloting it
                    if (!inStandView) {
                        // Render drone using AssetManager model (or procedural saucer fallback)
                        glEnable(GL_BLEND);
                        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
                        shader.setUniform("alphaMultiplier", 1.0f);
                        Matrix4f droneModel = new Matrix4f()
                                .translate(sp.x, sp.y + bob, sp.z)
                                .rotateY(droneSpin)
                                .scale(0.55f);
                        shader.setUniform("mvp", new Matrix4f(projection).mul(view).mul(droneModel));

                        com.leaf.game.render.Texture standTex = com.leaf.game.render.AssetManager.get().getTexture("stand");
                        if (standTex != null) {
                            shader.setUniform("useTexture", 1);
                            standTex.bind();
                        }
                        com.leaf.game.render.AssetManager.get().getModel("stand").render();
                        if (standTex != null) {
                            shader.setUniform("useTexture", 0);
                        }

                        // Blocked-LOS warning: overlay orange flash on drone when shot is blocked
                        float blockedF = player.stand.getBlockedFlash();
                        if (blockedF > 0f) {
                            float alpha = (blockedF / GameConfig.standBlockedFlashTime) * 0.55f;
                            shader.setUniform("alphaMultiplier", alpha);
                            shader.setUniform("mvp", new Matrix4f(projection).mul(view).mul(droneModel));
                            getItemMesh(Block.CRATER_BLOOM).render(); // warm orange tint
                        }
                        glDisable(GL_BLEND);
                    }

                    // Render stand redirect bolts as spinning CRYSTAL_CITRINE cubes
                    glEnable(GL_BLEND);
                    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
                    for (StandController.StandBolt bolt : player.stand.activeBolts) {
                        Matrix4f boltModel = new Matrix4f()
                                .translate(bolt.pos.x, bolt.pos.y, bolt.pos.z)
                                .rotateY(bolt.spinPhase)
                                .rotateX(bolt.spinPhase * 0.6f)
                                .rotateZ(bolt.spinPhase * 0.4f)
                                .scale(0.18f);
                        shader.setUniform("alphaMultiplier", 1.0f);
                        shader.setUniform("mvp", new Matrix4f(projection).mul(view).mul(boltModel));
                        getItemMesh(Block.CRYSTAL_CITRINE).render();
                    }
                    glDisable(GL_BLEND);
                }

                // (Note: The old 3D Pointer code that was here has been cleanly removed
                // because we are using your awesome 2D UI Edge Arrow now!)
                // because we are using your awesome 2D UI Edge Arrow now!)
                // Placed seals — Pass A: normal depth (visible when unobstructed)
                if (!player.seals.placedSeals.isEmpty()) {
                    glEnable(GL_BLEND);
                    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
                    com.leaf.game.render.Texture sealTex = com.leaf.game.render.AssetManager.get().getTexture("seal");
                    if (sealTex != null) { shader.setUniform("useTexture", 1); sealTex.bind(); }
                    for (SealController.SealEntry seal : player.seals.placedSeals) {
                        float pulse = 0.85f + 0.15f * (float)Math.sin(seal.pulsePhase);
                        float scale = seal.targeted
                                ? 0.45f * GameConfig.sealTargetedScale
                                : 0.45f;
                        Matrix4f sealModel = new Matrix4f()
                                .translate(seal.position.x, seal.position.y, seal.position.z)
                                .rotateY(seal.spinPhase)
                                .scale(scale * pulse);
                        shader.setUniform("alphaMultiplier", 1.0f);
                        shader.setUniform("mvp", new Matrix4f(projection).mul(view).mul(sealModel));
                        com.leaf.game.render.AssetManager.get().getModel("seal").render();
                    }
                    if (sealTex != null) { shader.setUniform("useTexture", 0); }
                    glDisable(GL_BLEND);

                    // Placed seals — Pass B: through-wall ghost (depth-test OFF)
                    // This lets the player always see their seals even through terrain.
                    glDisable(GL_DEPTH_TEST);
                    glEnable(GL_BLEND);
                    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
                    for (SealController.SealEntry seal : player.seals.placedSeals) {
                        float ghostAlpha = GameConfig.sealThroughWallAlpha
                                * (seal.targeted ? 1.0f : 0.7f);
                        float scale = seal.targeted
                                ? 0.28f * GameConfig.sealTargetedScale
                                : 0.28f;
                        Matrix4f sealModel = new Matrix4f()
                                .translate(seal.position.x, seal.position.y, seal.position.z)
                                .rotateY(seal.spinPhase)
                                .scale(scale);
                        shader.setUniform("alphaMultiplier", ghostAlpha);
                        shader.setUniform("mvp", new Matrix4f(projection).mul(view).mul(sealModel));
                        com.leaf.game.render.AssetManager.get().getModel("seal").render();
                    }
                    glDisable(GL_BLEND);
                    glEnable(GL_DEPTH_TEST);
                    shader.setUniform("alphaMultiplier", 1.0f);
                }

                // ── ENEMIES ───────────────────────────────────────────────────
                // Render each enemy as a red-tinted player-capsule model.
                // Dead enemies that still have a hit-flash timer get a bright flash.
                if (!enemyManager.getEnemies().isEmpty()) {
                    glEnable(GL_BLEND);
                    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
                    com.leaf.game.render.ModelMesh enemyModel =
                            com.leaf.game.render.AssetManager.get().getModel("player");
                    for (Enemy enemy : enemyManager.getEnemies()) {
                        float flashF = enemy.hitFlashTimer > 0f
                                ? (enemy.hitFlashTimer / 0.18f)
                                : 0f;
                        // Alpha: alive = 1.0, dead but still flashing → fade out
                        float alpha = enemy.alive ? 1.0f : flashF;
                        if (alpha < 0.02f) continue;

                        shader.setUniform("alphaMultiplier", alpha);
                        // Hit-flash: white for alive, brighter red for dead
                        if (flashF > 0f) {
                            shader.setUniform("overlayVignetteStrength", flashF * 0.55f);
                            shader.setUniform("overlayVignetteColor",
                                    new Vector3f(1.0f, 0.2f, 0.15f));
                        }

                        Matrix4f enemyMat = new Matrix4f()
                                .translate(enemy.position.x, enemy.position.y, enemy.position.z)
                                .scale(0.5f);
                        shader.setUniform("mvp",
                                new Matrix4f(projection).mul(view).mul(enemyMat));
                        enemyModel.render();

                        // Restore overlay after per-enemy flash
                        if (flashF > 0f) {
                            shader.setUniform("overlayVignetteStrength", 0f);
                            shader.setUniform("overlayVignetteColor", new Vector3f(0f, 0f, 0f));
                        }
                    }
                    shader.setUniform("alphaMultiplier", 1.0f);
                    glDisable(GL_BLEND);
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

                // ── ABILITY GHOST TRAILS ──────────────────────────────────────
                // Rendered in transparent pass (GL_BLEND already disabled above,
                // re-enable just for this section).
                renderAbilityGhosts(shader, projection, view);

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
                    renderHUD(camera, ww[0], wh[0]);
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
        int r  = player.currentSmashRadius; // Read the dynamic radius

        // 1. Carve crater
        world.createImpactCrater(ix, iy, iz, r);

        // 2. Ejecta burst
        spawnCraterEjecta(ix, iy, iz, r);

        // 3. Dynamic Screen Shake scaling based on the radius size
        float scaleFactor = (float) r / GameConfig.smashCraterRadius;
        activeShakeDuration  = GameConfig.smashShakeDuration * Math.min(2.5f, scaleFactor);
        activeShakeAmplitude = GameConfig.smashShakeAmplitude * Math.min(3.0f, scaleFactor);
        smashShakeTimer      = activeShakeDuration;

        // 4. Network sync
        if (network != null && network.connected) {
            network.sendCrater(ix, iy, iz, r);
        }
    }

    /**
     * Spawns a burst of DroppedItems flying outward from the impact point.
     * These use the new DroppedItem velocity field added for crater ejecta.
     * Only blocks that were actually solid at the impact site are sampled;
     * if the crater is in empty air (unlikely) we default to GRAVEL.
     */
    private void spawnCraterEjecta(int ix, int iy, int iz, int radius) {
        // Scale ejecta particle count with crater size
        int ejectedCount = Math.min(96, 6 * radius);
        Block ejectBlock = world.getBlock(ix, iy, iz);
        if (ejectBlock == Block.AIR || !ejectBlock.isSolid()) ejectBlock = Block.GRAVEL;

        for (int i = 0; i < ejectedCount; i++) {
            double azimuth  = shakeRng.nextDouble() * 2.0 * Math.PI;
            double elevation = shakeRng.nextDouble() * Math.PI * 0.5 + 0.1;
            float vx = (float)(Math.cos(azimuth) * Math.cos(elevation));
            float vy = (float)(Math.sin(elevation));
            float vz = (float)(Math.sin(azimuth) * Math.cos(elevation));

            // Scale speed so that higher falls launch particles faster and wider
            float speedScale = 0.6f + 0.4f * ((float) radius / GameConfig.smashCraterRadius);
            float ejectionSpeed = (18f + shakeRng.nextFloat() * 22f) * speedScale;
            Vector3f launchVel = new Vector3f(vx, vy, vz).mul(ejectionSpeed);

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

        float progress    = smashShakeTimer / activeShakeDuration; // Dynamic duration
        float amplitude   = progress * activeShakeAmplitude;       // Dynamic amplitude
        float timeSecs    = (float)glfwGetTime();
        float freq        = GameConfig.smashShakeFrequency;

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

    private void renderHUD(Camera camera, float screenW, float screenH) {
        var draw = ImGui.getForegroundDrawList();
        float cx = screenW / 2.0f, cy = screenH / 2.0f;
        // ── MANHATTAN TRANSFER 2D OFF-SCREEN INDICATOR ────────────────────────
        if (player.stand.isDeployed() && !player.stand.isInStandPerspective()) {

            // 1. Use the Matrix only to check if the drone is visible on-screen
            Matrix4f viewProj = new Matrix4f(camera.getProjectionMatrix()).mul(camera.getViewMatrix());
            Vector3f dronePos = player.stand.standPos;

            org.joml.Vector4f clipPos = new org.joml.Vector4f(dronePos.x, dronePos.y, dronePos.z, 1.0f).mul(viewProj);
            boolean inFront = clipPos.w > 0.0f;
            float ndcX = clipPos.x / Math.abs(clipPos.w);
            float ndcY = clipPos.y / Math.abs(clipPos.w);
            boolean onScreen = inFront && Math.abs(ndcX) <= 1.0f && Math.abs(ndcY) <= 1.0f;

            // 2. Draw the edge arrow ONLY if it is off-screen
            if (!onScreen) {
                // To avoid matrix flipping bugs, we use simple Dot Products with
                // the camera's local axes to find the true, stable direction.
                Vector3f toDrone = new Vector3f(dronePos).sub(camera.position).normalize();
                Vector3f right   = camera.getRight();
                // Cross product: Right x Forward = Local Up Vector
                Vector3f up      = new Vector3f(right).cross(camera.getLookDirection()).normalize();

                // Screen X grows right. Screen Y grows DOWN (so we invert Y).
                float dirX = toDrone.dot(right);
                float dirY = -toDrone.dot(up);

                float angle = (float) Math.atan2(dirY, dirX);
                float radius = Math.min(cx, cy) * 0.85f;

                float indicatorX = cx + (float)Math.cos(angle) * radius;
                float indicatorY = cy + (float)Math.sin(angle) * radius;

                // 3. Draw Custom "Tag" Shape (Made slightly larger)
                float L = 46f;      // Total length of the shape
                float W = 32f;      // Total width
                float tipL = 16f;   // Length of the pointy triangle tip

                float[][] pts = {
                        { L/2, 0 },               // 0: Tip
                        { L/2 - tipL, W/2 },      // 1: Top Right corner
                        { -L/2, W/2 },            // 2: Top Left corner
                        { -L/2, -W/2 },           // 3: Bottom Left corner
                        { L/2 - tipL, -W/2 }      // 4: Bottom Right corner
                };

                // Rotate and translate vertices to the edge of the screen
                float cosA = (float)Math.cos(angle);
                float sinA = (float)Math.sin(angle);
                for(int i = 0; i < 5; i++) {
                    float px = pts[i][0];
                    float py = pts[i][1];
                    float rotX = px * cosA - py * sinA;
                    float rotY = px * sinA + py * cosA;
                    pts[i][0] = indicatorX + rotX;
                    pts[i][1] = indicatorY + rotY;
                }

                // Colors
                int goldFill = ImGui.colorConvertFloat4ToU32(1.0f, 0.85f, 0.15f, 0.95f);
                int outline  = ImGui.colorConvertFloat4ToU32(0.1f, 0.1f, 0.1f, 1.0f);
                int redAlert = ImGui.colorConvertFloat4ToU32(1.0f, 0.15f, 0.15f, 1.0f);

                // Fill the shape
                draw.addQuadFilled(pts[1][0], pts[1][1], pts[2][0], pts[2][1], pts[3][0], pts[3][1], pts[4][0], pts[4][1], goldFill);
                draw.addTriangleFilled(pts[0][0], pts[0][1], pts[1][0], pts[1][1], pts[4][0], pts[4][1], goldFill);

                // Draw thick outline
                float thick = 2.5f;
                draw.addLine(pts[0][0], pts[0][1], pts[1][0], pts[1][1], outline, thick);
                draw.addLine(pts[1][0], pts[1][1], pts[2][0], pts[2][1], outline, thick);
                draw.addLine(pts[2][0], pts[2][1], pts[3][0], pts[3][1], outline, thick);
                draw.addLine(pts[3][0], pts[3][1], pts[4][0], pts[4][1], outline, thick);
                draw.addLine(pts[4][0], pts[4][1], pts[0][0], pts[0][1], outline, thick);

                // 4. Draw large, bright RED "!"
                float textOffsetX = -tipL / 2.0f;
                float centerIconX = indicatorX + textOffsetX * cosA;
                float centerIconY = indicatorY + textOffsetX * sinA;

                // Draw exclamation mark using filled rectangles (bypassing text limits so it's massive)
                // Top bar
                draw.addRectFilled(centerIconX - 2.5f, centerIconY - 9f, centerIconX + 2.5f, centerIconY + 3f, redAlert);
                // Bottom dot
                draw.addRectFilled(centerIconX - 2.5f, centerIconY + 6f, centerIconX + 2.5f, centerIconY + 11f, redAlert);

                // Outline the exclamation mark so it pops beautifully inside the gold box
                draw.addRect(centerIconX - 2.5f, centerIconY - 9f, centerIconX + 2.5f, centerIconY + 3f, outline, 0f, 0, 1.5f);
                draw.addRect(centerIconX - 2.5f, centerIconY + 6f, centerIconX + 2.5f, centerIconY + 11f, outline, 0f, 0, 1.5f);
            }
        }
        // Crosshair
        int white = ImGui.colorConvertFloat4ToU32(1, 1, 1, 0.9f);
        int black = ImGui.colorConvertFloat4ToU32(0, 0, 0, 0.6f);
        draw.addLine(cx - 11, cy, cx + 11, cy, black, 3.0f);
        draw.addLine(cx, cy - 11, cx, cy + 11, black, 3.0f);
        draw.addLine(cx - 10, cy, cx + 10, cy, white, 1.5f);
        draw.addLine(cx, cy - 10, cx, cy + 10, white, 1.5f);

        // Health bar and hotbar — hidden while piloting the drone
        if (!player.stand.isInStandPerspective()) {

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
        } // end !isInStandPerspective (health bar + hotbar guard)

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

        // ── CANNONBALL CHARGE BAR + READINESS ────────────────────────────────
        if (player.abilities.isCharging()) {
            float barW = 180f, barH = 12f;
            float barX = cx - barW / 2f, barY = cy + 42f;

            // Power bar (gold → red-orange)
            draw.addRectFilled(barX, barY, barX + barW, barY + barH,
                    ImGui.colorConvertFloat4ToU32(0.1f, 0.1f, 0.1f, 0.7f), 4f);
            float fill = player.abilities.chargePower;
            int barColor = fill >= 0.99f
                    ? ImGui.colorConvertFloat4ToU32(1.0f, 0.3f, 0.05f, 1.0f)
                    : ImGui.colorConvertFloat4ToU32(1.0f, 0.75f, 0.1f, 1.0f);
            draw.addRectFilled(barX, barY, barX + barW * fill, barY + barH, barColor, 4f);
            draw.addRect(barX, barY, barX + barW, barY + barH, black, 4f, 0, 1.5f);

            String powerLabel = fill >= 0.99f ? "FULL POWER" : String.format("CHARGING %.0f%%", fill * 100f);
            draw.addText(cx - 43, barY + 16f, black, powerLabel);
            draw.addText(cx - 44, barY + 15f, barColor, powerLabel);

            // Readiness bar (blue — shows chunk generation progress)
            float rdyBarY = barY + 30f;
            draw.addRectFilled(barX, rdyBarY, barX + barW, rdyBarY + barH,
                    ImGui.colorConvertFloat4ToU32(0.05f, 0.05f, 0.15f, 0.7f), 4f);
            int rdyColor = pathReadiness >= 0.95f
                    ? ImGui.colorConvertFloat4ToU32(0.2f, 1.0f, 0.4f, 1.0f)   // ready = green
                    : ImGui.colorConvertFloat4ToU32(0.3f, 0.6f, 1.0f, 1.0f);  // loading = blue
            draw.addRectFilled(barX, rdyBarY, barX + barW * pathReadiness, rdyBarY + barH, rdyColor, 4f);
            draw.addRect(barX, rdyBarY, barX + barW, rdyBarY + barH, black, 4f, 0, 1.5f);

            String rdyLabel = pathReadiness >= 0.95f
                    ? "PATH CLEAR — release [G] to fire!"
                    : String.format("Loading terrain... %.0f%%", pathReadiness * 100f);
            int rdyTextColor = pathReadiness >= 0.95f
                    ? ImGui.colorConvertFloat4ToU32(0.2f, 1.0f, 0.4f, 0.95f)
                    : ImGui.colorConvertFloat4ToU32(0.5f, 0.75f, 1.0f, 0.85f);
            draw.addText(cx - 70, rdyBarY + 16f, black, rdyLabel);
            draw.addText(cx - 71, rdyBarY + 15f, rdyTextColor, rdyLabel);

            // Crosshair lock indicator (camera is frozen to aim direction)
            draw.addText(cx - 55, cy - 22, black, "[ AIM LOCKED ]");
            draw.addText(cx - 56, cy - 23,
                    ImGui.colorConvertFloat4ToU32(1.0f, 0.85f, 0.3f, 0.85f), "[ AIM LOCKED ]");
        }

        // ── REWIND TRAIL INDICATOR ────────────────────────────────────────────
        if (player.abilities.isRewinding) {
            draw.addText(cx - 42, cy - 90, black, "⟲ REWINDING ⟲");
            draw.addText(cx - 43, cy - 91,
                    ImGui.colorConvertFloat4ToU32(0.3f, 0.6f, 1.0f, 1.0f), "⟲ REWINDING ⟲");
        }

        // ── SMASH INDICATOR ───────────────────────────────────────────────────
        if (player.isSmashing()) {
            draw.addText(cx - 35, cy - 90, black, "▼ SMASHING ▼");
            draw.addText(cx - 36, cy - 91,
                    ImGui.colorConvertFloat4ToU32(1.0f, 0.3f, 0.1f, 1.0f), "▼ SMASHING ▼");
        }

        // ── STAND PERSPECTIVE OVERLAY ────────────────────────────────────────
        // When piloting the drone: replace most HUD with a clean drone cockpit view.
        if (player.stand.isInStandPerspective()) {
            // Dim border vignette to indicate we are in drone mode
            int vigCol  = ImGui.colorConvertFloat4ToU32(0.05f, 0.1f, 0.35f, 0.55f);
            float vEdge = 80f;
            draw.addRectFilled(0f,                screenH - vEdge, screenW, screenH,          vigCol);
            draw.addRectFilled(0f,                0f,               screenW, vEdge,            vigCol);
            draw.addRectFilled(0f,                0f,               vEdge,   screenH,          vigCol);
            draw.addRectFilled(screenW - vEdge,   0f,               screenW, screenH,          vigCol);

            // ── Stand health bar ──────────────────────────────────────────────
            float shW = 160f, shH = 12f;
            float shX = cx - shW / 2f, shY = 18f;
            int shBg  = ImGui.colorConvertFloat4ToU32(0.05f, 0.05f, 0.2f,  0.85f);
            int shFg  = ImGui.colorConvertFloat4ToU32(0.25f, 0.65f, 1.0f,  1.0f);
            draw.addRectFilled(shX, shY, shX + shW, shY + shH, shBg, 4f);
            float shFill = (player.stand.standHealth / GameConfig.standMaxHealth) * shW;
            draw.addRectFilled(shX, shY, shX + shFill, shY + shH, shFg, 4f);
            draw.addRect(shX, shY, shX + shW, shY + shH, black, 4f, 0, 1.5f);
            String shLabel = String.format("STAND HP  %.0f / %.0f",
                    player.stand.standHealth, GameConfig.standMaxHealth);
            draw.addText(cx - 50f, shY + shH + 4f, black, shLabel);
            draw.addText(cx - 51f, shY + shH + 3f, ImGui.colorConvertFloat4ToU32(0.6f, 0.85f, 1.0f, 0.9f), shLabel);

            // ── LOS status indicators ─────────────────────────────────────────
            // Two dots: owner→stand (left), stand→target (right).
            // Green = clear, red = blocked.
            float dotY   = shY + shH + 26f;
            float dotR   = 5f;
            int losGreen = ImGui.colorConvertFloat4ToU32(0.2f, 1.0f, 0.35f, 1.0f);
            int losRed   = ImGui.colorConvertFloat4ToU32(1.0f, 0.2f, 0.15f, 1.0f);
            int losDim   = ImGui.colorConvertFloat4ToU32(0.4f, 0.4f, 0.4f,  0.6f);

            // Owner→Stand
            float d1X = cx - 28f;
            draw.addCircleFilled(d1X, dotY, dotR,
                    player.stand.losOwnerToStand ? losGreen : losRed, 16);
            draw.addCircle(d1X, dotY, dotR, black, 16, 1.2f);
            draw.addText(d1X - 14f, dotY + 8f,
                    ImGui.colorConvertFloat4ToU32(0.75f, 0.75f, 0.75f, 0.8f), "OWNER");

            // Connecting line
            draw.addLine(cx - 22f, dotY, cx + 22f, dotY, losDim, 1.2f);

            // Stand→Target
            float d2X = cx + 28f;
            draw.addCircleFilled(d2X, dotY, dotR,
                    player.stand.losStandToTarget ? losGreen : losRed, 16);
            draw.addCircle(d2X, dotY, dotR, black, 16, 1.2f);
            draw.addText(d2X - 12f, dotY + 8f,
                    ImGui.colorConvertFloat4ToU32(0.75f, 0.75f, 0.75f, 0.8f), "TGT");

            // Fire-ready composite state
            boolean readyToFire = player.stand.losOwnerToStand && player.stand.losStandToTarget;
            String fireHint = readyToFire ? "[LMB] Fire redirect shot" : "No clear line of fire";
            int fireColor = readyToFire
                    ? ImGui.colorConvertFloat4ToU32(0.2f, 1.0f, 0.4f, 0.95f)
                    : ImGui.colorConvertFloat4ToU32(1.0f, 0.4f, 0.2f, 0.75f);
            draw.addText(cx - 65f, dotY + 20f, black, fireHint);
            draw.addText(cx - 66f, dotY + 19f, fireColor, fireHint);

            // ── Master position marker (glowing dot + distance) ───────────────
            // Project player body position to screen so pilot can see where master is.
            Camera sc      = player.stand.standCamera;
            Matrix4f svp   = new Matrix4f(sc.getProjectionMatrix()).mul(sc.getViewMatrix());
            org.joml.Vector4f masterClip = new org.joml.Vector4f(
                    player.position.x, player.position.y + 0.9f, player.position.z, 1f).mul(svp);
            boolean masterInFront = masterClip.w > 0f;
            float masterDist = new Vector3f(player.stand.standPos).sub(player.position).length();
            String distLabel = String.format("%.0fm", masterDist);

            if (masterInFront) {
                float mNdcX = masterClip.x / masterClip.w;
                float mNdcY = masterClip.y / masterClip.w;
                boolean mOnScreen = Math.abs(mNdcX) <= 0.92f && Math.abs(mNdcY) <= 0.92f;
                float mScrX = (mNdcX  * 0.5f + 0.5f) * screenW;
                float mScrY = (1f - (mNdcY * 0.5f + 0.5f)) * screenH;

                if (mOnScreen) {
                    // Pulsing gold ring — master is visible from drone camera
                    int masterGold = ImGui.colorConvertFloat4ToU32(1.0f, 0.85f, 0.15f, 0.9f);
                    draw.addCircle(mScrX, mScrY, 11f, masterGold, 24, 2.5f);
                    draw.addCircle(mScrX, mScrY, 7f,  masterGold, 24, 1.5f);
                    draw.addText(mScrX - 12f, mScrY + 14f, black, distLabel);
                    draw.addText(mScrX - 13f, mScrY + 13f, masterGold, distLabel);
                }
            }

            // ── Return hint ───────────────────────────────────────────────────
            String returnHint = "[TAB]  Return to body";
            draw.addText(cx - 52f, screenH - 30f, black, returnHint);
            draw.addText(cx - 53f, screenH - 31f,
                    ImGui.colorConvertFloat4ToU32(0.7f, 0.82f, 1.0f, 0.85f), returnHint);
        }

        // ── SEAL COUNT DISPLAY ────────────────────────────────────────────────
        // Show placed seal count + place cooldown tick only when NOT in drone view.
        if (!player.stand.isInStandPerspective() && !player.debugMode) {
            int placed  = player.seals.getSealCount();
            int maxSeals = GameConfig.sealMaxCount;
            // Pip row: filled diamond = placed, hollow = available slot
            float pipY     = screenH - 120f;
            float pipGap   = 14f;
            float pipStartX = cx - (maxSeals - 1) * pipGap / 2f;
            int sealCyan  = ImGui.colorConvertFloat4ToU32(0.2f, 0.95f, 0.95f, 1.0f);
            int sealDim   = ImGui.colorConvertFloat4ToU32(0.3f, 0.3f, 0.3f,  0.55f);

            for (int i = 0; i < maxSeals; i++) {
                float px  = pipStartX + i * pipGap;
                float half = 5f;
                if (i < placed) {
                    // Filled diamond
                    draw.addQuadFilled(px,       pipY - half,
                                       px + half, pipY,
                                       px,        pipY + half,
                                       px - half, pipY, sealCyan);
                    draw.addQuad(px,       pipY - half,
                                 px + half, pipY,
                                 px,        pipY + half,
                                 px - half, pipY, black, 1.2f);
                } else {
                    // Empty diamond outline
                    draw.addQuad(px,       pipY - half,
                                 px + half, pipY,
                                 px,        pipY + half,
                                 px - half, pipY, sealDim, 1.2f);
                }
            }

            // Label: seal key hints on first and last slot
            float placeF    = player.seals.getPlaceCooldownFrac();
            float teleportF = player.seals.getTeleportCooldownFrac();
            String sealLabel = String.format("[H] Place  [B] Teleport  [N] Reclaim   %d/%d",
                    placed, maxSeals);
            int labelCol = (placeF >= 1f)
                    ? ImGui.colorConvertFloat4ToU32(0.6f, 0.95f, 0.95f, 0.75f)
                    : ImGui.colorConvertFloat4ToU32(0.45f, 0.6f,  0.6f,  0.55f);
            draw.addText(cx - 95f, pipY + 10f, black, sealLabel);
            draw.addText(cx - 96f, pipY + 9f,  labelCol, sealLabel);
        }

        // ── ABILITY COOLDOWN ICONS ────────────────────────────────────────────
        if (!player.debugMode) {
            renderAbilityHUD(draw, screenW, screenH);
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

            // ── VOID SHARD AIMING RETICLE ─────────────────────────────────────────
            float chargeF = player.attacks.getChargeFrac();
            if (chargeF > 0f) {
                int purpleBg = ImGui.colorConvertFloat4ToU32(0.4f, 0.1f, 0.8f, 0.5f);
                int purpleFg = ImGui.colorConvertFloat4ToU32(0.7f, 0.3f, 1.0f, 0.9f);

                // Reticle shrinks as the shot reaches maximum power
                float reticleRadius = 20f - (10f * chargeF);
                draw.addCircle(cx, cy, reticleRadius, purpleBg, 16, 2.0f);

                float chLen = 8f + 12f * chargeF;
                draw.addLine(cx - chLen, cy, cx - 4f, cy, purpleFg, 2.0f);
                draw.addLine(cx + 4f, cy, cx + chLen, cy, purpleFg, 2.0f);
                draw.addLine(cx, cy - chLen, cx, cy - 4f, purpleFg, 2.0f);
                draw.addLine(cx, cy + 4f, cx, cy + chLen, purpleFg, 2.0f);

                String aimText = String.format("POWER %.0f%%", chargeF * 100f);
                draw.addText(cx - 30, cy + 25, black, aimText);
                draw.addText(cx - 31, cy + 24, purpleFg, aimText);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  ABILITY VISUAL RENDERING
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Renders ghost trails for dash/rewind and trajectory arc for cannonball.
     * Called after normal item rendering, inside the main GL block.
     * Uses the alphaMultiplier uniform to control transparency per ghost.
     */
    private void renderAbilityGhosts(Shader shader, Matrix4f projection, Matrix4f view) {
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(false); // don't write to depth buffer for ghosts

        Mesh ghostMesh = getItemMesh(Block.SNOW); // clean white-ish block colour
        Mesh arcDotMesh = getItemMesh(Block.CRATER_BLOOM); // orange/gold dot

        // ── DASH GHOST TRAIL ─────────────────────────────────────────────────
        // Show last dashTrail.size() positions as fading cyan ghosts.
        // Alpha: 0.05 at oldest, 0.35 at newest.
        List<Vector3f> dashT = player.abilities.dashTrail;
        if (!dashT.isEmpty()) {
            float ageBoost = Math.max(0f, 1f - player.abilities.dashTrailAge * 2.5f);
            for (int i = 0; i < dashT.size(); i++) {
                float t      = (float)(i + 1) / dashT.size();
                float alpha  = (0.05f + t * 0.30f) * ageBoost;
                if (alpha < 0.01f) continue;
                Vector3f pos = dashT.get(i);
                Matrix4f ghostModel = new Matrix4f()
                        .translate(pos.x - 0.2f, pos.y + 0.6f, pos.z - 0.2f)
                        .scale(0.4f, 0.9f, 0.4f);
                shader.setUniform("alphaMultiplier", alpha);
                shader.setUniform("mvp", new Matrix4f(projection).mul(view).mul(ghostModel));
                ghostMesh.render();
            }
        }

        // ── REWIND TRAIL ─────────────────────────────────────────────────────
        // Always visible (very faint) so player can see their history.
        // Becomes brighter and blue-shifted when actively rewinding.
        List<Vector3f> rewindT = player.abilities.rewindTrail;
        if (!rewindT.isEmpty()) {
            boolean rewinding = player.abilities.isRewinding;
            for (int i = 0; i < rewindT.size(); i++) {
                float t     = (float)(i + 1) / rewindT.size(); // 0=oldest, 1=newest
                float alpha = rewinding ? (0.08f + t * 0.25f) : (0.02f + t * 0.06f);
                if (alpha < 0.01f) continue;
                Vector3f pos = rewindT.get(i);
                Matrix4f ghostModel = new Matrix4f()
                        .translate(pos.x - 0.15f, pos.y + 0.5f, pos.z - 0.15f)
                        .scale(0.3f, 0.8f, 0.3f);
                shader.setUniform("alphaMultiplier", alpha);
                // Rewind ghosts use CRYSTAL_AMETHYST (blue-purple) for clear identification
                shader.setUniform("mvp", new Matrix4f(projection).mul(view).mul(ghostModel));
                getItemMesh(Block.CRYSTAL_AMETHYST).render();
            }
        }

        // ── BLINK TRAIL ──────────────────────────────────────────────────────
        // For blinkFlashDecay seconds after a blink: show dots along the blink line.
        if (player.abilities.blinkFlashTimer > 0f) {
            float progress = player.abilities.blinkFlashTimer / GameConfig.blinkFlashDecay;
            Vector3f o = player.abilities.blinkOrigin;
            Vector3f d = player.abilities.blinkDest;
            for (int i = 0; i <= 6; i++) {
                float t   = (float)i / 6f;
                float alpha = progress * (0.1f + t * 0.3f);
                Vector3f pos = new Vector3f(o).lerp(d, t);
                Matrix4f ghostModel = new Matrix4f()
                        .translate(pos.x, pos.y + 0.9f, pos.z)
                        .scale(0.25f, 0.25f, 0.25f);
                shader.setUniform("alphaMultiplier", alpha);
                shader.setUniform("mvp", new Matrix4f(projection).mul(view).mul(ghostModel));
                getItemMesh(Block.CRYSTAL_QUARTZ).render(); // white/clear
            }
        }

        // ── CANNONBALL TRAJECTORY ARC ─────────────────────────────────────────
        // Show predicted ballistic path when charging. Dots fade from bright at
        // player to dim at end. Alternating size gives dotted-line appearance.
        List<Vector3f> arc = player.abilities.trajectoryArc;
        if (!arc.isEmpty()) {
            for (int i = 0; i < arc.size(); i++) {
                float t      = (float)i / arc.size();
                float alpha  = 0.7f - t * 0.5f;
                float scale  = (i % 2 == 0) ? 0.18f : 0.10f;
                Vector3f pos = arc.get(i);
                Matrix4f dotModel = new Matrix4f()
                        .translate(pos.x, pos.y, pos.z)
                        .scale(scale, scale, scale);
                shader.setUniform("alphaMultiplier", alpha);
                shader.setUniform("mvp", new Matrix4f(projection).mul(view).mul(dotModel));
                arcDotMesh.render();
            }
        }

        // Restore defaults
        shader.setUniform("alphaMultiplier", 1.0f);
        glDepthMask(true);
        glDisable(GL_BLEND);
    }

    /**
     * Renders four ability cooldown icons (Q / E / G / Z) in the bottom-right
     * corner of the screen. Each icon shows a coloured fill based on the
     * ability's ready fraction: full = coloured, on-cooldown = grey fill.
     */
    private void renderAbilityHUD(imgui.ImDrawList draw, float screenW, float screenH) {
        // ── Ability icon layout: two rows, bottom-right ───────────────────────
        // Row 1 (top, further from edge): Q  E  G  Z   — original four abilities
        // Row 2 (bottom, near edge):      X  H  B       — Stand + Seal abilities
        float iconSize = 28f;
        float spacing  = 6f;
        int   black    = ImGui.colorConvertFloat4ToU32(0f, 0f, 0f, 0.8f);
        int   grey     = ImGui.colorConvertFloat4ToU32(0.2f, 0.2f, 0.2f, 0.8f);

        // Row 1 — Q / E / G / Z
        {
            float totalW = 4 * iconSize + 3 * spacing;
            float startX = screenW - totalW - 14f;
            float startY = screenH - iconSize * 2f - spacing - 14f;

            String[] labels   = { "Q",  "E",  "G",  "Z"  };
            String[] tooltips = { "Dash", "Blink", "Canon", "Rewind" };
            float[]  fracs    = {
                    player.abilities.getDashCooldownFrac(),
                    player.abilities.getBlinkCooldownFrac(),
                    player.abilities.getCannonCooldownFrac(),
                    player.abilities.getRewindCooldownFrac()
            };
            int[] colors = {
                    ImGui.colorConvertFloat4ToU32(0.45f, 0.88f, 1.0f, 1.0f),  // dash: cyan
                    ImGui.colorConvertFloat4ToU32(0.93f, 0.95f, 1.0f, 1.0f),  // blink: white
                    ImGui.colorConvertFloat4ToU32(1.0f,  0.65f, 0.1f, 1.0f),  // cannonball: gold
                    ImGui.colorConvertFloat4ToU32(0.3f,  0.6f,  1.0f, 1.0f)   // rewind: blue
            };

            for (int i = 0; i < 4; i++) {
                float x = startX + i * (iconSize + spacing);
                draw.addRectFilled(x, startY, x + iconSize, startY + iconSize, grey, 5f);
                float fillH = iconSize * fracs[i];
                if (fillH > 0.5f) {
                    draw.addRectFilled(x, startY + iconSize - fillH,
                            x + iconSize, startY + iconSize, colors[i], 5f);
                }
                draw.addRect(x, startY, x + iconSize, startY + iconSize, black, 5f, 0, 1.5f);
                draw.addText(x + 9f, startY + 7f, black, labels[i]);
                draw.addText(x + 9f, startY + 7f,
                        ImGui.colorConvertFloat4ToU32(1f, 1f, 1f, 0.9f), labels[i]);
                draw.addText(x, startY + iconSize + 2f,
                        ImGui.colorConvertFloat4ToU32(0.8f, 0.8f, 0.8f, 0.7f), tooltips[i]);
            }
        }

        // Row 2 — X (Stand)  H (Seal place)  B (Seal teleport)
        {
            float totalW = 3 * iconSize + 2 * spacing;
            float startX = screenW - totalW - 14f;
            float startY = screenH - iconSize - 14f;

            // X: stand deploy — gold when deployed, blue-grey on cooldown
            boolean standDeployed = player.stand.isDeployed();
            float   standFrac     = player.stand.getRedeployCooldownFrac();
            int     standColor    = standDeployed
                    ? ImGui.colorConvertFloat4ToU32(1.0f, 0.82f, 0.15f, 1.0f)   // gold: deployed
                    : ImGui.colorConvertFloat4ToU32(0.4f, 0.65f, 0.9f,  1.0f);  // blue: ready

            // H: seal place — cyan when ready, dim on cooldown
            float sealPlaceFrac = player.seals.getPlaceCooldownFrac();
            int   sealPlaceColor = ImGui.colorConvertFloat4ToU32(0.2f, 0.92f, 0.92f, 1.0f);

            // B: seal teleport — teal when ready, darker on cooldown
            float sealTpFrac   = player.seals.getTeleportCooldownFrac();
            int   sealTpColor  = ImGui.colorConvertFloat4ToU32(0.1f, 0.75f, 0.65f, 1.0f);

            String[] labels2   = { "X",     "H",     "B"      };
            String[] tooltips2 = { "Stand", "Seal",  "Warp"   };
            float[]  fracs2    = { standDeployed ? 1f : standFrac, sealPlaceFrac, sealTpFrac };
            int[]    colors2   = { standColor, sealPlaceColor, sealTpColor };

            for (int i = 0; i < 3; i++) {
                float x = startX + i * (iconSize + spacing);
                draw.addRectFilled(x, startY, x + iconSize, startY + iconSize, grey, 5f);
                float fillH = iconSize * fracs2[i];
                if (fillH > 0.5f) {
                    draw.addRectFilled(x, startY + iconSize - fillH,
                            x + iconSize, startY + iconSize, colors2[i], 5f);
                }
                // Stand icon: bright outline when deployed
                float outlineThick = (i == 0 && standDeployed) ? 2.5f : 1.5f;
                draw.addRect(x, startY, x + iconSize, startY + iconSize,
                        (i == 0 && standDeployed) ? colors2[0] : black, 5f, 0, outlineThick);
                draw.addText(x + 9f, startY + 7f, black, labels2[i]);
                draw.addText(x + 9f, startY + 7f,
                        ImGui.colorConvertFloat4ToU32(1f, 1f, 1f, 0.9f), labels2[i]);
                draw.addText(x, startY + iconSize + 2f,
                        ImGui.colorConvertFloat4ToU32(0.8f, 0.8f, 0.8f, 0.7f), tooltips2[i]);
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
                player.position.set(2000f, 250f, 2000f);
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