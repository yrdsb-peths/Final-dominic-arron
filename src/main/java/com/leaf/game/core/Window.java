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

    // ── TODO'S TECHNIQUE (J key) ──────────────────────────────────────────────
    private float   todoSwapCooldown = 0f;
    private boolean lastJ            = false;

    // ── QUAGMIRE (M key) ─────────────────────────────────────────────────────
    private float   quagmireCooldown = 0f;
    private boolean lastM            = false;
    /**
     * Active mud waves.  Each float[12]:
     *   [0-2] current position   [3-4] direction (x,z normalised)
     *   [5]   speed              [6]   dist travelled   [7] total dist
     *   [8]   target enemy ID    [9]   (reserved)
     *   [10]  last placed block X  [11] last placed block Z
     */
    private final List<float[]> mudWaves = new ArrayList<>();

    // ── STONE CANON (I key) ───────────────────────────────────────────────────
    private boolean isChargingStoneCanon    = false;
    private float   stoneCanonCharge        = 0f;
    private float   stoneCanonNextConsume   = 0f;  // countdown to next block consumed
    private int     stoneCanonBlocksConsumed = 0;
    private Vector3f stoneCanonLockedPos    = null; // position locked when charging starts
    private Vector3f stoneCanonGroundPos   = null; // ground point in front where boulder rises
    private float   stoneCanonCooldownTimer = 0f;
    private boolean lastI                   = false;
    private final List<ActiveStoneShot> stoneShotList = new ArrayList<>();

    /** A stone projectile fired by the Stone Canon ability. */
    private static class ActiveStoneShot {
        final Vector3f pos;
        final Vector3f vel;
        float scale;
        final float chargeF;
        float lifetime;
        ActiveStoneShot(Vector3f pos, Vector3f vel, float scale, float chargeF) {
            this.pos = new Vector3f(pos);
            this.vel = new Vector3f(vel);
            this.scale   = scale;
            this.chargeF = chargeF;
            this.lifetime = GameConfig.stoneCanonLifetime;
        }
    }

    // ── PAPER FIGURINE SUBSTITUTE (V hold) ────────────────────────────────────
    /** True while V is held and the ability is ready — next hit will be negated. */
    private boolean substitutePrimed   = false;
    private float   substituteCooldown = 0f;
    /**
     * Live paper dummies.  Each entry: float[5] = { x, y, z, timer, maxTimer }.
     * Timer counts down from substituteDummyLifetime to 0, then explodes.
     */
    private final List<float[]> substituteDummies = new ArrayList<>();

    // ── TUTORIAL / HELP ───────────────────────────────────────────────────────
    /** F1 toggles the full controls reference overlay. */
    private boolean showHelp       = false;
    private boolean lastF1         = false;
    /** Auto-dismiss welcome banner shown when the game first loads. */
    private float   welcomeTimer   = 0f;
    private boolean welcomeStarted = false;
    /** One-liner contextual hint (e.g. first stand deploy, first seal placed). */
    private String  hintText       = null;
    private float   hintTimer      = 0f;
    private boolean standHintShown = false;
    private boolean sealHintShown  = false;
    /** Edge-detect for contextual hint triggers. */
    private boolean wasStandDeployed = false;
    private int     lastSealCount    = 0;

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
                } else if (showHelp) {
                    // Help screen takes priority over pause so ESC cleanly dismisses it
                    showHelp = false;
                    glfwSetInputMode(window, GLFW_CURSOR,
                            (showDebug || showNoiseViewer || isPaused)
                                    ? GLFW_CURSOR_NORMAL : GLFW_CURSOR_DISABLED);
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

            if (key == GLFW_KEY_F1 && action == GLFW_RELEASE && !showChat) {
                showHelp = !showHelp;
                glfwSetInputMode(window, GLFW_CURSOR,
                        (showHelp || showDebug || showNoiseViewer)
                                ? GLFW_CURSOR_NORMAL : GLFW_CURSOR_DISABLED);
            }

            if (key == GLFW_KEY_F3 && action == GLFW_RELEASE && !showChat) {
                showDebug = !showDebug;
                glfwSetInputMode(window, GLFW_CURSOR,
                        (showHelp || showDebug || showNoiseViewer) ? GLFW_CURSOR_NORMAL : GLFW_CURSOR_DISABLED);
            }

            if (key == GLFW_KEY_F4 && action == GLFW_RELEASE && !showChat) {
                showNoiseViewer = !showNoiseViewer;
                glfwSetInputMode(window, GLFW_CURSOR,
                        (showHelp || showDebug || showNoiseViewer) ? GLFW_CURSOR_NORMAL : GLFW_CURSOR_DISABLED);
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
            if (!networkInitialized || isPreloading || showDebug || showChat || showNoiseViewer || isPaused || showHelp)
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
            if (!networkInitialized || isPreloading || showDebug || showChat || showNoiseViewer || isPaused || showHelp)
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
                    && !player.abilities.isCharging()
                    && !isChargingStoneCanon) {
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
        player.attacks.setEnemyManager(enemyManager);

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
                        // Start welcome banner once per session
                        if (!welcomeStarted) {
                            welcomeTimer   = 6.0f;
                            welcomeStarted = true;
                        }
                    }

                    if (!showChat && !showDebug && !showNoiseViewer && !isPaused && !showHelp) {
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

                        // Update all enemies (gravity, AI, death fade, etc.)
                        enemyManager.update(deltaTime, world, player.position);

                        // ── PAPER FIGURINE SUBSTITUTE (V hold) ────────────────
                        // Must run BEFORE the damage drain so it can intercept.
                        if (substituteCooldown > 0f) substituteCooldown -= deltaTime;
                        boolean vHeld = glfwGetKey(window, GLFW_KEY_V) == GLFW_PRESS;
                        substitutePrimed = vHeld && !player.debugMode && substituteCooldown <= 0f;

                        if (substitutePrimed && enemyManager.pendingPlayerDamage > 0f) {
                            Vector3f oldPos = new Vector3f(player.position);
                            // Step backward along look direction, one block at a time
                            // to avoid clipping through walls
                            float cyaw = camera.yaw;
                            float backX = -(float)Math.cos(cyaw);
                            float backZ = -(float)Math.sin(cyaw);
                            float bd    = GameConfig.substituteBackDist;
                            float tx    = oldPos.x;
                            float tz    = oldPos.z;
                            for (float step = 0.5f; step <= bd; step += 0.5f) {
                                float cx = oldPos.x + backX * step;
                                float cz = oldPos.z + backZ * step;
                                int bx2 = (int) Math.floor(cx);
                                int bz3 = (int) Math.floor(cz);
                                int fy   = (int) Math.floor(oldPos.y);
                                boolean solid = world.getBlock(bx2, fy,   bz3).isSolid()
                                             || world.getBlock(bx2, fy+1, bz3).isSolid();
                                if (solid) break;
                                tx = cx; tz = cz;
                            }
                            // Snap Y to ground at destination
                            int bxd = (int)Math.floor(tx), bzd = (int)Math.floor(tz);
                            float ty = oldPos.y;
                            for (int by2 = (int)oldPos.y + 4; by2 >= 1; by2--) {
                                if (world.getBlock(bxd, by2, bzd).isSolid()
                                        && !world.getBlock(bxd, by2+1, bzd).isSolid()) {
                                    ty = by2 + 1f; break;
                                }
                            }
                            player.position.set(tx, ty, tz);
                            player.setVelocityY(0f);
                            player.abilities.blinkFlashTimer = GameConfig.blinkFlashDecay;
                            player.abilities.blinkOrigin     = oldPos;
                            player.abilities.blinkDest       = new Vector3f(player.position);
                            float lt = GameConfig.substituteDummyLifetime;
                            substituteDummies.add(new float[]{ oldPos.x, oldPos.y, oldPos.z, lt, lt });
                            enemyManager.pendingPlayerDamage = 0f;
                            substitutePrimed   = false;
                            substituteCooldown = GameConfig.substituteCooldown;
                        }

                        // ── DRAIN remaining enemy damage into player health ────
                        if (enemyManager.pendingPlayerDamage > 0f) {
                            player.health -= enemyManager.pendingPlayerDamage;
                            enemyManager.pendingPlayerDamage = 0f;
                            if (player.health <= 0f) {
                                System.out.println("You died!");
                                player.position.set(1000, 255, 1000);
                                player.setVelocityY(0f);
                                player.health = player.maxHealth;
                            }
                        }

                        // Tick paper dummies; explode when timer expires
                        for (int di = substituteDummies.size() - 1; di >= 0; di--) {
                            float[] dm = substituteDummies.get(di);
                            dm[3] -= deltaTime;
                            if (dm[3] <= 0f) {
                                float[] blastEv = { dm[0], dm[1], dm[2],
                                        GameConfig.substituteBlastRadius };
                                enemyManager.processExplosion(blastEv,
                                        GameConfig.substituteBlastDamage);
                                Random fragRng = new Random();
                                for (int fi = 0; fi < 14; fi++) {
                                    float ang = fragRng.nextFloat() * (float)(2 * Math.PI);
                                    float spd = 4f + fragRng.nextFloat() * 6f;
                                    Vector3f fv = new Vector3f(
                                            (float)Math.cos(ang) * spd,
                                            2f + fragRng.nextFloat() * 5f,
                                            (float)Math.sin(ang) * spd);
                                    droppedItems.add(new DroppedItem(
                                            (int)dm[0], (int)dm[1], (int)dm[2],
                                            Block.SNOW, fv));
                                }
                                activeShakeDuration  = 0.3f;
                                activeShakeAmplitude = 0.18f;
                                smashShakeTimer      = Math.max(smashShakeTimer, activeShakeDuration);
                                substituteDummies.remove(di);
                            }
                        }

                        // P key — spawn test enemy at crosshair hit point
                        boolean pHeld = glfwGetKey(window, GLFW_KEY_P) == GLFW_PRESS;
                        if (pHeld && !lastP) {
                            RaycastResult hit = player.getTargetBlock(camera, world);
                            if (hit != null && hit.hit) {
                                enemyManager.spawnAt(
                                        hit.placeX + 0.5f,
                                        hit.placeY,
                                        hit.placeZ + 0.5f);
                            }
                        }
                        lastP = pHeld;

                        // ── TODO'S TECHNIQUE (J key) ──────────────────────────
                        if (todoSwapCooldown > 0f) todoSwapCooldown -= deltaTime;
                        boolean jHeld = glfwGetKey(window, GLFW_KEY_J) == GLFW_PRESS;
                        if (jHeld && !lastJ && !player.debugMode && todoSwapCooldown <= 0f) {
                            Vector3f eyePos = new Vector3f(player.position.x,
                                    player.position.y + 1.6f, player.position.z);
                            Enemy swapTarget = enemyManager.findClosestVisible(
                                    world, eyePos, GameConfig.todoRange);
                            if (swapTarget != null) {
                                Vector3f oldPlayerPos = new Vector3f(player.position);
                                Vector3f oldEnemyPos  = new Vector3f(swapTarget.position);
                                player.position.set(oldEnemyPos);
                                swapTarget.position.set(oldPlayerPos);
                                player.abilities.blinkFlashTimer = GameConfig.blinkFlashDecay;
                                player.abilities.blinkOrigin     = oldPlayerPos;
                                player.abilities.blinkDest       = new Vector3f(oldEnemyPos);
                                swapTarget.hitFlashTimer = 0.35f;
                                todoSwapCooldown = GameConfig.todoCooldown;
                            }
                        }
                        lastJ = jHeld;

                        // ── QUAGMIRE (M key) ──────────────────────────────────
                        if (quagmireCooldown > 0f) quagmireCooldown -= deltaTime;
                        boolean mHeld = glfwGetKey(window, GLFW_KEY_M) == GLFW_PRESS;
                        if (mHeld && !lastM && !player.debugMode && quagmireCooldown <= 0f) {
                            Vector3f eyePos = new Vector3f(player.position.x,
                                    player.position.y + 1.6f, player.position.z);
                            Enemy target = enemyManager.findMostAligned(
                                    world, eyePos, camera.getLookDirection(), GameConfig.quagmireRange);
                            if (target != null) {
                                // Wave starts 2 blocks in front of player
                                float wdx = target.position.x - player.position.x;
                                float wdz = target.position.z - player.position.z;
                                float wdist = (float)Math.sqrt(wdx*wdx + wdz*wdz);
                                if (wdist > 0.1f) {
                                    float ndx2 = wdx / wdist, ndz2 = wdz / wdist;
                                    float startX = player.position.x + ndx2 * 2f;
                                    float startZ = player.position.z + ndz2 * 2f;
                                    float startY = player.position.y;
                                    float totalDist = Math.max(0.1f, wdist - 2f);
                                    mudWaves.add(new float[]{
                                        startX, startY, startZ,         // [0-2] pos
                                        ndx2, ndz2,                      // [3-4] dir
                                        GameConfig.quagmireSpreadSpeed,  // [5] speed
                                        0f,                              // [6] dist travelled
                                        totalDist,                       // [7] total dist
                                        (float) target.id,               // [8] enemy id
                                        0f,                              // [9] reserved
                                        -99999f, -99999f                 // [10-11] last placed block col
                                    });
                                    quagmireCooldown = GameConfig.quagmireCooldown;
                                }
                            }
                        }
                        lastM = mHeld;

                        // Advance mud waves — permanently paint MUD blocks on the ground
                        for (int wi = mudWaves.size() - 1; wi >= 0; wi--) {
                            float[] w = mudWaves.get(wi);
                            float stepDist = w[5] * deltaTime;
                            w[6] += stepDist;
                            w[0] += w[3] * stepDist;
                            w[2] += w[4] * stepDist;

                            // Place a MUD block each time the wave enters a new block column
                            int curBx = (int) Math.floor(w[0]);
                            int curBz = (int) Math.floor(w[2]);
                            if (curBx != (int) w[10] || curBz != (int) w[11]) {
                                w[10] = curBx;
                                w[11] = curBz;
                                // Scan downward from wave Y to find the ground surface
                                int baseY = (int) Math.floor(w[1]) + 2;
                                for (int scanY = baseY; scanY >= 0; scanY--) {
                                    if (world.getBlock(curBx, scanY, curBz).isSolid()) {
                                        // Replace the surface block with MUD
                                        world.setBlock(curBx, scanY, curBz, Block.MUD);
                                        world.rebuildChunkAt(curBx, scanY, curBz);
                                        break;
                                    }
                                }
                            }

                            // Reached target — burst MUD chunks around enemy feet, remove wave
                            if (w[6] >= w[7]) {
                                int eid = (int) w[8];
                                for (Enemy e : enemyManager.getEnemies()) {
                                    if (e.id == eid && e.alive) {
                                        e.hitFlashTimer = 0.25f;
                                        // Burst of flying MUD chunks at enemy position
                                        for (int mi = 0; mi < 10; mi++) {
                                            Vector3f mv = new Vector3f(
                                                    (shakeRng.nextFloat()-0.5f)*5f,
                                                    2.5f + shakeRng.nextFloat()*3.5f,
                                                    (shakeRng.nextFloat()-0.5f)*5f);
                                            droppedItems.add(new DroppedItem(
                                                    (int) e.position.x,
                                                    (int) e.position.y,
                                                    (int) e.position.z,
                                                    Block.MUD, mv));
                                        }
                                        // Also stamp MUD under the enemy
                                        int ex = (int) Math.floor(e.position.x);
                                        int ez = (int) Math.floor(e.position.z);
                                        for (int scanY = (int) Math.floor(e.position.y) + 1; scanY >= 0; scanY--) {
                                            if (world.getBlock(ex, scanY, ez).isSolid()) {
                                                world.setBlock(ex, scanY, ez, Block.MUD);
                                                world.rebuildChunkAt(ex, scanY, ez);
                                                break;
                                            }
                                        }
                                        break;
                                    }
                                }
                                mudWaves.remove(wi);
                            }
                        }

                        // ── STONE CANON (I key) ───────────────────────────────
                        if (stoneCanonCooldownTimer > 0f) stoneCanonCooldownTimer -= deltaTime;
                        boolean iHeld = glfwGetKey(window, GLFW_KEY_I) == GLFW_PRESS;

                        if (!isChargingStoneCanon && iHeld && !lastI
                                && !player.debugMode && stoneCanonCooldownTimer <= 0f) {
                            // Start charging — lock position
                            isChargingStoneCanon     = true;
                            stoneCanonCharge         = 0f;
                            stoneCanonBlocksConsumed = 0;
                            stoneCanonNextConsume    = GameConfig.stoneCanonConsumeRate;
                            stoneCanonLockedPos      = new Vector3f(player.position);

                            // Compute boulder ground-spawn point (same logic as fire)
                            Vector3f ld0 = camera.getLookDirection();
                            float hLen0 = (float) Math.sqrt(ld0.x*ld0.x + ld0.z*ld0.z);
                            float hn0x = hLen0 > 0.001f ? ld0.x / hLen0 : 0f;
                            float hn0z = hLen0 > 0.001f ? ld0.z / hLen0 : 1f;
                            float gfpx = player.position.x + hn0x * 2.5f;
                            float gfpz = player.position.z + hn0z * 2.5f;
                            int gfpBx = (int) Math.floor(gfpx);
                            int gfpBz = (int) Math.floor(gfpz);
                            int gSpawnY = (int) Math.floor(player.position.y);
                            for (int sy2 = (int) Math.floor(player.position.y) + 3; sy2 >= 0; sy2--) {
                                if (world.getBlock(gfpBx, sy2, gfpBz).isSolid()) {
                                    gSpawnY = sy2 + 1;
                                    break;
                                }
                            }
                            stoneCanonGroundPos = new Vector3f(gfpx, (float) gSpawnY, gfpz);
                        }

                        if (isChargingStoneCanon) {
                            // Lock position
                            player.position.set(stoneCanonLockedPos);
                            player.setVelocityY(0f);

                            stoneCanonCharge += deltaTime;
                            stoneCanonNextConsume -= deltaTime;

                            // Consume one stone block per interval
                            if (stoneCanonNextConsume <= 0f) {
                                stoneCanonNextConsume = GameConfig.stoneCanonConsumeRate;
                                int sr = (int)GameConfig.stoneCanonScanRadius;
                                int px = (int)Math.floor(player.position.x);
                                int py = (int)Math.floor(player.position.y);
                                int pz = (int)Math.floor(player.position.z);
                                outer:
                                for (int r = 1; r <= sr; r++) {
                                    for (int bx2 = px-r; bx2 <= px+r; bx2++) {
                                        for (int bz2 = pz-r; bz2 <= pz+r; bz2++) {
                                            for (int by2 = py-r; by2 <= py+r; by2++) {
                                                if (world.getBlock(bx2, by2, bz2) == Block.STONE) {
                                                    world.setBlock(bx2, by2, bz2, Block.AIR);
                                                    world.rebuildChunkAt(bx2, by2, bz2);
                                                    // Stone flies toward player
                                                    Vector3f sv = new Vector3f(
                                                            player.position.x - bx2,
                                                            player.position.y - by2 + 1f,
                                                            player.position.z - bz2)
                                                            .normalize().mul(8f);
                                                    droppedItems.add(new DroppedItem(
                                                            bx2, by2, bz2, Block.STONE, sv));
                                                    stoneCanonBlocksConsumed++;
                                                    break outer;
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // Release I or exceed max charge → fire
                            if (!iHeld || stoneCanonCharge >= GameConfig.stoneCanonMaxCharge) {
                                isChargingStoneCanon = false;
                                if (stoneCanonBlocksConsumed > 0) {
                                    float chargeF = Math.min(1f,
                                            stoneCanonCharge / GameConfig.stoneCanonMaxCharge);
                                    float speed = GameConfig.stoneCanonMinSpeed
                                            + chargeF * (GameConfig.stoneCanonMaxSpeed - GameConfig.stoneCanonMinSpeed);
                                    float scale = GameConfig.stoneCanonMinScale
                                            + chargeF * (GameConfig.stoneCanonMaxScale - GameConfig.stoneCanonMinScale);
                                    // Scale also by number of blocks consumed
                                    float blockBonus = Math.min(1f, stoneCanonBlocksConsumed / 6f);
                                    scale *= (0.7f + 0.3f * blockBonus);
                                    Vector3f lookDir = camera.getLookDirection();

                                    // ── Spawn from ground 2.5 blocks in front ────
                                    float hdLen = (float) Math.sqrt(
                                            lookDir.x * lookDir.x + lookDir.z * lookDir.z);
                                    float nhx = hdLen > 0.001f ? lookDir.x / hdLen : 0f;
                                    float nhz = hdLen > 0.001f ? lookDir.z / hdLen : 1f;
                                    float fpx = player.position.x + nhx * 2.5f;
                                    float fpz = player.position.z + nhz * 2.5f;

                                    // Scan downward to find solid ground at that column
                                    int groundSpawnY = (int) Math.floor(player.position.y);
                                    int fpBx = (int) Math.floor(fpx);
                                    int fpBz = (int) Math.floor(fpz);
                                    for (int sy = (int) Math.floor(player.position.y) + 3; sy >= 0; sy--) {
                                        if (world.getBlock(fpBx, sy, fpBz).isSolid()) {
                                            groundSpawnY = sy + 1;
                                            break;
                                        }
                                    }
                                    Vector3f firePos = new Vector3f(fpx, (float) groundSpawnY, fpz);

                                    // ── Aim from ground point toward player's look target ──
                                    Vector3f eyePos2 = new Vector3f(player.position.x,
                                            player.position.y + 1.6f, player.position.z);
                                    Vector3f aimTarget = new Vector3f(eyePos2)
                                            .add(new Vector3f(lookDir).mul(60f));
                                    Vector3f fireDir = new Vector3f(aimTarget).sub(firePos);
                                    float fireDirLen = fireDir.length();
                                    if (fireDirLen > 0.001f) fireDir.div(fireDirLen);
                                    Vector3f fireVel = new Vector3f(fireDir).mul(speed);
                                    stoneShotList.add(new ActiveStoneShot(firePos, fireVel, scale, chargeF));
                                    stoneCanonCooldownTimer = GameConfig.stoneCanonCooldown;
                                }
                                stoneCanonLockedPos  = null;
                                stoneCanonGroundPos  = null;
                            }
                        }
                        lastI = iHeld;

                        // Advance stone shots
                        for (int si = stoneShotList.size() - 1; si >= 0; si--) {
                            ActiveStoneShot shot = stoneShotList.get(si);
                            shot.lifetime -= deltaTime;
                            shot.pos.add(new Vector3f(shot.vel).mul(deltaTime));
                            // Slow down slightly for visual feel
                            shot.vel.mul(0.998f);

                            boolean hitSomething = false;
                            int sx = (int)Math.floor(shot.pos.x);
                            int sy = (int)Math.floor(shot.pos.y);
                            int sz = (int)Math.floor(shot.pos.z);

                            if (world.getBlock(sx, sy, sz).isSolid() || shot.lifetime <= 0f) {
                                hitSomething = true;
                            }
                            if (hitSomething) {
                                float blastR = GameConfig.stoneCanonMinRadius
                                        + shot.chargeF * (GameConfig.stoneCanonMaxRadius - GameConfig.stoneCanonMinRadius);
                                float blastD = GameConfig.stoneCanonMinDamage
                                        + shot.chargeF * (GameConfig.stoneCanonMaxDamage - GameConfig.stoneCanonMinDamage);
                                enemyManager.processExplosion(
                                        new float[]{ shot.pos.x, shot.pos.y, shot.pos.z, blastR }, blastD);
                                // Crater
                                int cr = Math.max(1, Math.round(blastR * 0.6f));
                                world.createImpactCrater(sx, sy, sz, cr);
                                spawnCraterEjecta(sx, sy, sz, cr);
                                float shakeStr = 0.2f + shot.chargeF * 0.4f;
                                activeShakeDuration  = shakeStr;
                                activeShakeAmplitude = 0.15f + shot.chargeF * 0.2f;
                                smashShakeTimer      = Math.max(smashShakeTimer, activeShakeDuration);
                                stoneShotList.remove(si);
                            }
                        }

                        // ── CONTEXTUAL ABILITY HINTS ──────────────────────────
                        // Stand first-deploy: show a one-liner explaining TAB/LMB/X
                        boolean nowStandDeployed = player.stand.isDeployed();
                        if (nowStandDeployed && !wasStandDeployed && !standHintShown) {
                            hintText  = "Stand deployed!   TAB = pilot drone  ·  LMB = auto-fire bolt  ·  X = recall";
                            hintTimer = 5.0f;
                            standHintShown = true;
                        }
                        wasStandDeployed = nowStandDeployed;

                        // Seal first-placement: show a one-liner explaining B/N
                        int nowSealCount = player.seals.getSealCount();
                        if (nowSealCount > 0 && lastSealCount == 0 && !sealHintShown) {
                            hintText  = "Seal placed!   B = warp to it  ·  N = reclaim it  ·  Place up to 5";
                            hintTimer = 5.0f;
                            sealHintShown = true;
                        }
                        lastSealCount = nowSealCount;

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

            // ── TUTORIAL TIMER TICKS (always, regardless of pause/help state) ──
            if (welcomeTimer > 0f) welcomeTimer = Math.max(0f, welcomeTimer - rawDeltaTime);
            if (hintTimer    > 0f) hintTimer    = Math.max(0f, hintTimer    - rawDeltaTime);

            // ── RENDER ────────────────────────────────────────────────────────
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

            if (networkInitialized) {
                shader.bind();
                shader.setUniform("sunDirection",
                        new Vector3f(GameConfig.sunDirX, GameConfig.sunDirY, GameConfig.sunDirZ));
                shader.setUniform("sunStrength", GameConfig.sunStrength);
                shader.setUniform("ambientStrength", GameConfig.ambientStrength);

                boolean isCameraUnderwater = world.getBlock(
                        (int) Math.floor(camera.position.x),
                        (int) Math.floor(camera.position.y),
                        (int) Math.floor(camera.position.z)).isLiquid();
                shader.setUniform("isUnderwater", isCameraUnderwater ? 1 : 0);
                shader.setUniform("cameraY", camera.position.y);

                // ── TIME DILATION VIGNETTE ────────────────────────────────────
                // Slow motion: subtle blue-grey wash
                // Fast time: warm orange tint
                float slowFactor = tc.getSlownessFactor();
                float fastFactor = tc.getFastnessFactor();
                float vignetteStrength;
                Vector3f vignetteColor;
                if (slowFactor > 0.001f) {
                    vignetteStrength = slowFactor * 0.28f;
                    vignetteColor = new Vector3f(0.52f, 0.58f, 0.70f); // blue-grey
                } else if (fastFactor > 0.001f) {
                    vignetteStrength = fastFactor * 0.22f;
                    vignetteColor = new Vector3f(0.8f, 0.55f, 0.18f); // warm orange
                } else {
                    vignetteStrength = 0f;
                    vignetteColor = new Vector3f(0f, 0f, 0f);
                }
                shader.setUniform("timeVignetteStrength", vignetteStrength);
                shader.setUniform("timeVignetteColor", vignetteColor);

                // ── ABILITY + ATTACK OVERLAY VIGNETTE ────────────────────────
                // Use whichever overlay is currently stronger so neither system
                // silently stomps the other during simultaneous effects.
                float abilityOverlayStr = player.abilities.getOverlayStrength();
                float attackOverlayStr = player.attacks.getOverlayStrength();
                Vector3f compositeOverlayColor;
                float compositeOverlayStr;
                if (attackOverlayStr >= abilityOverlayStr) {
                    compositeOverlayColor = player.attacks.getOverlayColor();
                    compositeOverlayStr = attackOverlayStr;
                } else {
                    compositeOverlayColor = player.abilities.getOverlayColor();
                    compositeOverlayStr = abilityOverlayStr;
                }
                // ── SEAL TELEPORT OVERLAY ─────────────────────────────────
                // White-ish flash when the player zips to a seal.
                if (player.seals.teleportFlash > 0f) {
                    float flashStr = (player.seals.teleportFlash / GameConfig.sealTeleportFlash) * 0.55f;
                    if (flashStr > compositeOverlayStr) {
                        compositeOverlayStr = flashStr;
                        compositeOverlayColor = new Vector3f(0.95f, 0.98f, 1.0f);
                    }
                }
                // ── SUBSTITUTE PRIMED OVERLAY ─────────────────────────────
                // Subtle white pulsing vignette while the substitute is ready.
                if (substitutePrimed) {
                    float timeSecs = (float) glfwGetTime();
                    float pulseStr = 0.10f + 0.05f * (float) Math.sin(timeSecs * 8.0f);
                    if (pulseStr > compositeOverlayStr) {
                        compositeOverlayStr   = pulseStr;
                        compositeOverlayColor = new Vector3f(0.95f, 0.97f, 1.0f);
                    }
                }

                shader.setUniform("overlayVignetteStrength", compositeOverlayStr);
                shader.setUniform("overlayVignetteColor", compositeOverlayColor);
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
                    // Use Math.abs so negative zoom-in (sniper charge) is applied as well as
                    // positive zoom-out (flight speed). Previously the condition fovBoost > 0.1f
                    // silently discarded any negative value, making sniper zoom do nothing.
                    camera.dynamicFov = (Math.abs(fovBoost) > 0.1f) ? GameConfig.fov + fovBoost : -1f;
                }

                // ── VIEW MATRIX + ROLL ────────────────────────────────────────
                // When piloting the drone, all cosmetic effects are suppressed:
                // no roll, no screen shake, no attack pitch — clean drone view only.
                // All rendering uses standCamera so the player sees through the drone.
                Matrix4f baseView;
                float rollAngle;
                Matrix4f projection;
                Vector3f shakeOffset;

                if (inStandView) {
                    Camera sc = player.stand.standCamera;
                    float droneFovBoost = player.attacks.getFovBoost();
                    sc.dynamicFov = (Math.abs(droneFovBoost) > 0.1f) ? GameConfig.fov + droneFovBoost : -1f;
                    baseView = sc.getViewMatrix();
                    rollAngle = 0f;
                    projection = sc.getProjectionMatrix();
                    shakeOffset = new Vector3f(0f);
                } else {
                    // Attack pitch offset is non-destructive: add, build, subtract.
                    float attackPitch = player.attacks.getPitchOffset();
                    camera.pitch += attackPitch;
                    baseView = camera.getViewMatrix();
                    camera.pitch -= attackPitch;
                    rollAngle = player.getCameraRoll();
                    projection = camera.getProjectionMatrix();
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

                int playerCX = Math.floorDiv((int) player.position.x, Chunk.SIZE);
                int playerCZ = Math.floorDiv((int) player.position.z, Chunk.SIZE);
                int R = GameConfig.renderDistance;
                int playerCY = Math.floorDiv((int) player.position.y, Chunk.HEIGHT);
                int cyMin = Math.min(playerCY - 4, -4);

                // Frustum culling uses the CLEAN view (no roll, no shake) to avoid
                // popping at the frustum edges during roll animations.
                Matrix4f cleanMvp = new Matrix4f(projection).mul(baseView);
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
// ── PASS 1: OPAQUE ────────────────────────────────────────────
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

                // ── PASS 3: OVERLAYS, ENTITIES & PROJECTILES (Blend Enabled) ──
                glEnable(GL_BLEND);
                glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

                // 1. Render Grapple Cable & Laser Sight
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
                        targetPoint = player.attacks.getAimTarget(camera, world);
                        isLaser = true;
                        renderBlock = Block.CRYSTAL_AMETHYST;
                    }

                    if (targetPoint != null) {
                        Vector3f ropeDir = new Vector3f(targetPoint).sub(playerHand);
                        float ropeDist = ropeDir.length();

                        if (ropeDist > 0.1f) {
                            ropeDir.normalize();
                            org.joml.Quaternionf ropeRot = new org.joml.Quaternionf().rotationTo(new org.joml.Vector3f(0, 0, 1), ropeDir);

                            float thickness = isLaser ? 0.008f : 0.04f;
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

                // 2. Render Void Shard Bolts
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

                // 3. Render Stand Drone (Manhattan Transfer)
                if (player.stand.isDeployed()) {
                    float bob = (float) Math.sin(player.stand.bobPhase) * GameConfig.standHoverBob;
                    float droneSpin = (float) (glfwGetTime() * 1.5);
                    Vector3f sp = player.stand.standPos;

                    if (!inStandView) {
                        // A. Physical Drone Body
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

                        // B. Permanent Pulsing Energy Aura (The Yellow Glow)
                        float timeSecs = (float) glfwGetTime();
                        float pulseScale = 1.1f + 0.05f * (float) Math.sin(timeSecs * 4.0f);
                        float pulseAlpha = 0.40f + 0.15f * (float) Math.cos(timeSecs * 4.0f);


                        shader.setUniform("alphaMultiplier", pulseAlpha);
                        Matrix4f glowModel = new Matrix4f()
                                .translate(sp.x, sp.y + bob, sp.z)
                                .rotateY(-droneSpin * 0.5f)
                                .rotateX(timeSecs * 0.3f)
                                .scale(pulseScale);

                        shader.setUniform("mvp", new Matrix4f(projection).mul(view).mul(glowModel));
                        getItemMesh(Block.CRYSTAL_CITRINE).render();

                        // C. Inner Core Flare (Actively Targeted Highlight)
                        boolean aimingAtStand = player.attacks.isAimingAtStand(camera);
                        if (aimingAtStand) {
                            shader.setUniform("alphaMultiplier", 0.85f);
                            Matrix4f targetCore = new Matrix4f()
                                    .translate(sp.x, sp.y + bob, sp.z)
                                    .rotateY(droneSpin * 2.0f)
                                    .scale(0.85f);
                            shader.setUniform("mvp", new Matrix4f(projection).mul(view).mul(targetCore));
                            getItemMesh(Block.CRYSTAL_CITRINE).render();
                        }

                        // Blocked-LOS Warning
                        float blockedF = player.stand.getBlockedFlash();
                        if (blockedF > 0f) {
                            float alpha = (blockedF / GameConfig.standBlockedFlashTime) * 0.55f;
                            shader.setUniform("alphaMultiplier", alpha);
                            shader.setUniform("mvp", new Matrix4f(projection).mul(view).mul(droneModel));
                            getItemMesh(Block.CRATER_BLOOM).render();
                        }

                        shader.setUniform("alphaMultiplier", 1.0f);
                    }

                    // D. Stand Redirect Bolts
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
                }

                // 4. In-Flight Seal Projectiles
                for (SealController.SealProjectile proj : player.seals.inFlightSeals) {
                    Matrix4f projModel = new Matrix4f()
                            .translate(proj.pos.x, proj.pos.y, proj.pos.z)
                            .rotateY(proj.spinPhase)
                            .scale(0.15f);
                    shader.setUniform("alphaMultiplier", 1.0f);
                    shader.setUniform("mvp", new Matrix4f(projection).mul(view).mul(projModel));
                    getItemMesh(Block.CRYSTAL_CITRINE).render();
                }

                // 5. Placed Seals (Normal Pass & Ghost Pass)
                if (!player.seals.placedSeals.isEmpty()) {
                    com.leaf.game.render.Texture sealTex = com.leaf.game.render.AssetManager.get().getTexture("seal");
                    if (sealTex != null) {
                        shader.setUniform("useTexture", 1);
                        sealTex.bind();
                    }
                    for (SealController.SealEntry seal : player.seals.placedSeals) {
                        float pulse = 0.85f + 0.15f * (float) Math.sin(seal.pulsePhase);
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
                    if (sealTex != null) {
                        shader.setUniform("useTexture", 0);
                    }

                    // Through-wall ghost pass
                    glDisable(GL_DEPTH_TEST);
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
                    glEnable(GL_DEPTH_TEST);
                    shader.setUniform("alphaMultiplier", 1.0f);
                }

                // 6. Render Enemies
                if (!enemyManager.getEnemies().isEmpty()) {
                    com.leaf.game.render.ModelMesh enemyModel =
                            com.leaf.game.render.AssetManager.get().getModel("player");
                    for (Enemy enemy : enemyManager.getEnemies()) {
                        float flashF = enemy.hitFlashTimer > 0f ? (enemy.hitFlashTimer / 0.18f) : 0f;
                        float alpha  = enemy.alive ? 1.0f : flashF;
                        if (alpha < 0.02f) continue;

                        Vector3f typeColor;
                        float    typeOverlayStr;
                        // Mud-trapped overrides type colour with a brown tint
                        if (enemy.mudTrapTimer > 0f) {
                            typeColor      = new Vector3f(0.45f, 0.28f, 0.05f); // brown
                            typeOverlayStr = 0.45f;
                        } else {
                            switch (enemy.type) {
                                case BRUTE   -> { typeColor = new Vector3f(0.55f, 0.10f, 0.80f); typeOverlayStr = 0.18f; }
                                case STALKER -> { typeColor = new Vector3f(0.55f, 0.90f, 0.10f); typeOverlayStr = 0.18f; }
                                default      -> { typeColor = new Vector3f(0.90f, 0.30f, 0.05f); typeOverlayStr = 0.12f; }
                            }
                        }

                        shader.setUniform("alphaMultiplier", alpha);
                        if (flashF > 0f) {
                            // White-hot hit flash overrides type colour
                            shader.setUniform("overlayVignetteStrength", flashF * 0.65f);
                            shader.setUniform("overlayVignetteColor", new Vector3f(1.0f, 0.25f, 0.15f));
                        } else {
                            // Ambient type-colour tint
                            shader.setUniform("overlayVignetteStrength", typeOverlayStr);
                            shader.setUniform("overlayVignetteColor", typeColor);
                        }

                        float scale = enemy.renderScale();
                        Matrix4f enemyMat = new Matrix4f()
                                .translate(enemy.position.x, enemy.position.y, enemy.position.z)
                                .scale(scale);
                        shader.setUniform("mvp", new Matrix4f(projection).mul(view).mul(enemyMat));
                        enemyModel.render();
                    }
                    // Reset overlay state
                    shader.setUniform("overlayVignetteStrength", 0f);
                    shader.setUniform("overlayVignetteColor", new Vector3f(0f, 0f, 0f));
                    shader.setUniform("alphaMultiplier", 1.0f);
                }

                // 6b. Render charging boulder rising from ground while I is held
                if (isChargingStoneCanon && stoneCanonGroundPos != null) {
                    float chargeProgress = Math.min(1f,
                            stoneCanonCharge / GameConfig.stoneCanonMaxCharge);
                    // Boulder rises from ground level up by ~1.5 blocks at full charge
                    float riseY = stoneCanonGroundPos.y + chargeProgress * 1.5f;
                    // Scale grows from 0.08 → max scale as charge builds
                    float minSc = GameConfig.stoneCanonMinScale * 0.22f;
                    float maxSc = GameConfig.stoneCanonMinScale
                            + chargeProgress * (GameConfig.stoneCanonMaxScale - GameConfig.stoneCanonMinScale);
                    float blockBonus = Math.min(1f, stoneCanonBlocksConsumed / 6f);
                    maxSc *= (0.7f + 0.3f * blockBonus);
                    float chargeScale = minSc + chargeProgress * (maxSc - minSc);
                    // Slow spin that speeds up as charge increases
                    float timeSecs2 = (float) glfwGetTime();
                    float spin2 = timeSecs2 * (1.5f + chargeProgress * 5f);
                    // Stone-grey with growing orange glow
                    float glow2 = chargeProgress * 0.45f;
                    shader.setUniform("alphaMultiplier", 0.7f + chargeProgress * 0.3f);
                    shader.setUniform("overlayVignetteStrength", 0.10f + glow2);
                    shader.setUniform("overlayVignetteColor",
                            new Vector3f(0.65f + glow2, 0.55f + glow2 * 0.3f, 0.4f));
                    Matrix4f chargeMat = new Matrix4f()
                            .translate(stoneCanonGroundPos.x, riseY, stoneCanonGroundPos.z)
                            .rotateY(spin2)
                            .rotateX(spin2 * 0.6f)
                            .scale(chargeScale);
                    shader.setUniform("mvp", new Matrix4f(projection).mul(view).mul(chargeMat));
                    getItemMesh(Block.STONE).render();
                    shader.setUniform("overlayVignetteStrength", 0f);
                    shader.setUniform("overlayVignetteColor", new Vector3f(0f));
                    shader.setUniform("alphaMultiplier", 1.0f);
                }

                // 6c. Render Stone Canon shots
                if (!stoneShotList.isEmpty()) {
                    float timeSecs = (float) glfwGetTime();
                    for (ActiveStoneShot shot : stoneShotList) {
                        float spin = timeSecs * (3f + shot.chargeF * 4f);
                        shader.setUniform("alphaMultiplier", 1.0f);
                        // Stone-grey base, slight orange glow at high charge
                        float glow = shot.chargeF * 0.35f;
                        shader.setUniform("overlayVignetteStrength", 0.15f + glow);
                        shader.setUniform("overlayVignetteColor",
                                new Vector3f(0.65f + glow, 0.55f + glow * 0.3f, 0.4f));
                        Matrix4f shotMat = new Matrix4f()
                                .translate(shot.pos.x, shot.pos.y, shot.pos.z)
                                .rotateY(spin)
                                .rotateX(spin * 0.7f)
                                .scale(shot.scale);
                        shader.setUniform("mvp", new Matrix4f(projection).mul(view).mul(shotMat));
                        getItemMesh(Block.STONE).render();
                    }
                    shader.setUniform("overlayVignetteStrength", 0f);
                    shader.setUniform("overlayVignetteColor", new Vector3f(0f));
                    shader.setUniform("alphaMultiplier", 1.0f);
                }

                // 7a. Render Paper Figurine Substitute dummies
                if (!substituteDummies.isEmpty()) {
                    com.leaf.game.render.ModelMesh dummyModel =
                            com.leaf.game.render.AssetManager.get().getModel("player");
                    if (dummyModel != null) {
                        for (float[] dm : substituteDummies) {
                            float lifeFrac = dm[3] / dm[4]; // 1=fresh … 0=about to explode
                            // Scale expands slightly as timer runs out (0.9 → 1.2)
                            float dscale = 0.9f + (1f - lifeFrac) * 0.3f;
                            float alpha  = Math.max(0.15f, lifeFrac * 0.90f);
                            shader.setUniform("alphaMultiplier", alpha);
                            // Dark silhouette — near-black with faint blue tinge
                            shader.setUniform("overlayVignetteStrength", 0.80f + (1f - lifeFrac) * 0.15f);
                            shader.setUniform("overlayVignetteColor", new Vector3f(0.02f, 0.02f, 0.06f));
                            Matrix4f dummyMat = new Matrix4f()
                                    .translate(dm[0], dm[1], dm[2])
                                    .scale(dscale);
                            shader.setUniform("mvp", new Matrix4f(projection).mul(view).mul(dummyMat));
                            dummyModel.render();
                        }
                        shader.setUniform("overlayVignetteStrength", 0f);
                        shader.setUniform("overlayVignetteColor", new Vector3f(0f, 0f, 0f));
                        shader.setUniform("alphaMultiplier", 1.0f);
                    }
                }

                // 7. Render Items
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

                // 8. Render Remote Player
                if (network != null && network.connected) {
                    remotePlayer.render(shader, projection, view);
                }

                // 9. Render Ability Ghost Trails
                renderAbilityGhosts(shader, projection, view);

                glDisable(GL_BLEND);
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
                    if (showHelp)        renderHelpScreen(ww[0], wh[0]);
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
        ImGui.setNextWindowPos(w / 2.0f - 100.0f, h / 2.0f - 110.0f);
        ImGui.setNextWindowSize(200.0f, 210.0f);
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
        if (ImGui.button("Controls  [F1]", 180, 30)) {
            // Open help without closing pause — player can read then ESC back
            showHelp = true;
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
                // Outline the exclamation mark so it pops beautifully inside the gold box
                draw.addRect(centerIconX - 2.5f, centerIconY - 9f, centerIconX + 2.5f, centerIconY + 3f, outline, 0f, 0, 1.5f);
                draw.addRect(centerIconX - 2.5f, centerIconY + 6f, centerIconX + 2.5f, centerIconY + 11f, outline, 0f, 0, 1.5f);
            } else {
                // ── ON-SCREEN 2D DRONE OUTLINE (Always bright, always visible!) ──
                float dScrX = (ndcX * 0.5f + 0.5f) * screenW;
                float dScrY = (1f - (ndcY * 0.5f + 0.5f)) * screenH;

                float dist = new Vector3f(dronePos).sub(camera.position).length();
                // Scale the 2D UI box so it shrinks slightly at a distance, but never gets too tiny
                float s = Math.max(14f, Math.min(45f, 400f / dist));

                boolean aimingAtDrone = player.attacks.isAimingAtStand(camera);

                int yellow    = ImGui.colorConvertFloat4ToU32(1.0f, 0.85f, 0.10f, 1.0f);
                int dimYellow = ImGui.colorConvertFloat4ToU32(1.0f, 0.85f, 0.10f, 0.45f); // 45% opacity when idle

                int brightRed = ImGui.colorConvertFloat4ToU32(1.0f, 0.0f, 0.0f, 1.0f); // 100% opacity bright red
                int dimRed    = ImGui.colorConvertFloat4ToU32(1.0f, 0.0f, 0.0f, 0.45f); // 45% opacity dim red

                int color     = aimingAtDrone ? brightRed : dimRed;
                float thick = aimingAtDrone ? 3.0f : 1.5f;

                // Draw a 2D Diamond Outline exactly over the drone
                draw.addQuad(
                        dScrX, dScrY - s,
                        dScrX + s, dScrY,
                        dScrX, dScrY + s,
                        dScrX - s, dScrY, color, thick);

                if (aimingAtDrone) {
                    // Draw a striking inner crosshair when locked on
                    float in = s * 0.5f;
                    draw.addLine(dScrX - in, dScrY, dScrX + in, dScrY, yellow, 2.0f);
                    draw.addLine(dScrX, dScrY - in, dScrX, dScrY + in, yellow, 2.0f);
                }
            }
        }
        // Crosshair
        int black = ImGui.colorConvertFloat4ToU32(0, 0, 0, 0.6f);
        int white = ImGui.colorConvertFloat4ToU32(1.0f, 1.0f, 1.0f, 1.0f);

        float chargeF = player.attacks.getChargeFrac();

        if (chargeF > 0f) {
            // ── SNIPER / CHARGING MODE — red shrinking circle crosshair ──────────
            int red = ImGui.colorConvertFloat4ToU32(1.0f, 0.12f, 0.12f, 0.95f);
            float ring = Math.max(4f, 18f - 13f * chargeF); // shrinks from 18 → 5 px as charge builds

            draw.addCircle(cx, cy, ring, red, 32, 1.8f);
            draw.addCircleFilled(cx, cy, 2.2f, red, 8);

            // Four radiating lines — sniper style
            float gap = ring + 2f, ext = gap + 9f;
            draw.addLine(cx - ext, cy,  cx - gap, cy,  red, 1.5f);
            draw.addLine(cx + gap, cy,  cx + ext, cy,  red, 1.5f);
            draw.addLine(cx, cy - ext,  cx, cy - gap,  red, 1.5f);
            draw.addLine(cx, cy + gap,  cx, cy + ext,  red, 1.5f);

            // Charge % below crosshair
            String pct = String.format("%.0f%%", chargeF * 100f);
            draw.addText(cx - 10f, cy + ring + 8f, black, pct);
            draw.addText(cx - 11f, cy + ring + 7f, red,   pct);

        } else {
            // ── NORMAL — plain white crosshair ────────────────────────────────────
            draw.addLine(cx - 11, cy, cx + 11, cy, black, 3.0f);
            draw.addLine(cx, cy - 11, cx, cy + 11, black, 3.0f);
            draw.addLine(cx - 10, cy, cx + 10, cy, white, 1.5f);
            draw.addLine(cx, cy - 10, cx, cy + 10, white, 1.5f);
        }

        // ── REDIRECT INDICATOR ──
        boolean aimingAtDrone  = !player.stand.isInStandPerspective() && player.attacks.isAimingAtStand(camera);
        boolean redirectReady  = aimingAtDrone && player.attacks.isRedirectAvailable(world);

        if (aimingAtDrone) {
            // Both states are now high-contrast Yellow/Gold
            int yellow  = ImGui.colorConvertFloat4ToU32(1.0f, 0.85f, 0.10f, 1.0f);
            int grey = ImGui.colorConvertFloat4ToU32(0.5f, 0.5f, 0.5f, 1.0f);

            int blackBg = ImGui.colorConvertFloat4ToU32(0.0f, 0.0f, 0.0f, 0.85f);

            float circleRadius = 12.0f; // Shorter, tighter radius (down from 18)

            if (redirectReady) {
                draw.addRectFilled(cx + 12f, cy - 20f, cx + 115f, cy + 4f, blackBg, 4f);
                draw.addText(cx + 18f, cy - 12f, yellow, "REDIRECT [C]");
                draw.addCircle(cx, cy, circleRadius, yellow, 32, 2.5f);
            } else {
                draw.addRectFilled(cx + 12f, cy - 20f, cx + 110f, cy + 4f, blackBg, 4f);
                draw.addText(cx + 18f, cy - 12f, grey, "REFLECT [C]"); // Yellow indicator
                draw.addCircle(cx, cy, circleRadius, grey, 32, 2.0f); // Yellow circle
            }
        }
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
                    draw.addText(x + slotSize - 15, startY + slotSize - 19, white , countStr);
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

        // ── SEAL HUD MARKERS ─────────────────────────────────────────────────
        // Always-visible indicators so seals are spottable at any distance:
        //   • On-screen seal  → pulsing cyan ring + distance label
        //   • Off-screen seal → small cyan arrow on screen edge
        if (!player.stand.isInStandPerspective()
                && !player.seals.placedSeals.isEmpty()) {

            Matrix4f sealVP = new Matrix4f(camera.getProjectionMatrix())
                    .mul(camera.getViewMatrix());

            for (SealController.SealEntry seal : player.seals.placedSeals) {
                org.joml.Vector4f clip = new org.joml.Vector4f(
                        seal.position.x, seal.position.y, seal.position.z, 1f)
                        .mul(sealVP);

                boolean inFront = clip.w > 0f;
                float absW = Math.abs(clip.w);
                float ndcX = clip.x / absW;
                float ndcY = clip.y / absW;
                boolean onScreen = inFront
                        && Math.abs(ndcX) <= 1.0f && Math.abs(ndcY) <= 1.0f;

                float dist = new Vector3f(seal.position).sub(camera.position).length();
                String distLabel = String.format("%.0fm", dist);

                int sealRingColor = seal.targeted
                        ? ImGui.colorConvertFloat4ToU32(0.2f, 1.0f, 1.0f, 1.0f)
                        : ImGui.colorConvertFloat4ToU32(0.1f, 0.8f, 0.8f, 0.85f);
                int sealDimText = ImGui.colorConvertFloat4ToU32(0.2f, 0.9f, 0.9f, 0.75f);

                if (onScreen) {
                    // Project to pixel coords
                    float sx = (ndcX  * 0.5f + 0.5f) * screenW;
                    float sy = (1f - (ndcY * 0.5f + 0.5f)) * screenH;

                    // Pulse: ring radius grows/shrinks slightly
                    float pulse = 1f + 0.12f * (float)Math.sin(seal.pulsePhase * 2f);
                    float outerR = (seal.targeted ? 18f : 11f) * pulse;
                    float innerR = outerR * 0.55f;

                    // Outer ring
                    draw.addCircle(sx, sy, outerR, sealRingColor, 20, seal.targeted ? 2.5f : 1.8f);
                    // Inner dot for targeted seal
                    if (seal.targeted) {
                        draw.addCircleFilled(sx, sy, innerR,
                                ImGui.colorConvertFloat4ToU32(0.2f, 1.0f, 1.0f, 0.35f), 16);
                    }
                    // Distance label just below the ring
                    float labelY = sy + outerR + 3f;
                    draw.addText(sx - 12f, labelY + 1f, black, distLabel);
                    draw.addText(sx - 13f, labelY,      sealDimText, distLabel);

                } else {
                    // Off-screen arrow — same dot-product technique as the stand indicator
                    Vector3f toSeal = new Vector3f(seal.position)
                            .sub(camera.position).normalize();
                    Vector3f right = camera.getRight();
                    Vector3f up    = new Vector3f(right)
                            .cross(camera.getLookDirection()).normalize();

                    float dirX  = toSeal.dot(right);
                    float dirY  = -toSeal.dot(up);
                    float angle = (float)Math.atan2(dirY, dirX);
                    float edgeR = Math.min(cx, cy) * 0.80f;

                    float ax = cx + (float)Math.cos(angle) * edgeR;
                    float ay = cy + (float)Math.sin(angle) * edgeR;

                    // Small triangle arrow
                    float cosA = (float)Math.cos(angle);
                    float sinA = (float)Math.sin(angle);
                    float tl = 12f, tw = 7f;
                    // tip, left-base, right-base
                    float t1x = ax + cosA * tl * 0.5f, t1y = ay + sinA * tl * 0.5f;
                    float t2x = ax - cosA * tl * 0.5f + sinA * tw * 0.5f;
                    float t2y = ay - sinA * tl * 0.5f - cosA * tw * 0.5f;
                    float t3x = ax - cosA * tl * 0.5f - sinA * tw * 0.5f;
                    float t3y = ay - sinA * tl * 0.5f + cosA * tw * 0.5f;
                    draw.addTriangleFilled(t1x, t1y, t2x, t2y, t3x, t3y, sealRingColor);
                    draw.addTriangle(t1x, t1y, t2x, t2y, t3x, t3y, black, 1.2f);

                    // Distance label beside arrow
                    draw.addText(ax + cosA * (tl + 3f) - 10f,
                                 ay + sinA * (tl + 3f) - 6f, black, distLabel);
                    draw.addText(ax + cosA * (tl + 3f) - 11f,
                                 ay + sinA * (tl + 3f) - 7f, sealDimText, distLabel);
                }
            }
        }

        // ── ENEMY HP BARS ─────────────────────────────────────────────────────
        // World-space health bar projected above each enemy's head.
        // Use the active camera: stand camera in drone mode, player camera otherwise.
        if (!enemyManager.getEnemies().isEmpty() && !player.stand.isInStandPerspective()) {
            // Build VP from the actual player eye position/orientation
            float aspect = screenW / screenH;
            float fovRad = (float) Math.toRadians(
                    (camera.dynamicFov >= 0f) ? camera.dynamicFov : GameConfig.fov);
            Matrix4f hpProj = new Matrix4f().perspective(fovRad, aspect, 0.1f, 1000f);
            Matrix4f hpView = camera.getViewMatrix();
            Matrix4f enemyVP = new Matrix4f(hpProj).mul(hpView);

            // Camera eye position and look direction for in-front culling
            Vector3f eyePos  = new Vector3f(camera.position);
            Vector3f lookDir = camera.getLookDirection();

            for (Enemy enemy : enemyManager.getEnemies()) {
                if (!enemy.alive) continue;

                // Cull enemies more than 60 blocks away
                Vector3f headPos = new Vector3f(
                        enemy.position.x,
                        enemy.position.y + 2.3f,    // just above model top
                        enemy.position.z);
                Vector3f toEnemy = new Vector3f(headPos).sub(eyePos);
                float dist = toEnemy.length();
                if (dist > 60f) continue;

                // Cull enemies behind the camera (dot product < 0)
                if (dist > 0.001f && toEnemy.dot(lookDir) / dist < 0.0f) continue;

                // Project to clip space
                org.joml.Vector4f clip = new org.joml.Vector4f(
                        headPos.x, headPos.y, headPos.z, 1f).mul(enemyVP);
                if (clip.w <= 0.001f) continue; // behind near plane

                float ndcX = clip.x / clip.w;
                float ndcY = clip.y / clip.w;
                // Tight cull — only show when enemy is well within screen bounds
                if (ndcX < -1.05f || ndcX > 1.05f || ndcY < -1.05f || ndcY > 1.05f) continue;

                float sx = (ndcX  * 0.5f + 0.5f) * screenW;
                float sy = (1f - (ndcY * 0.5f + 0.5f)) * screenH;

                float hpFrac = Math.max(0f, enemy.health / enemy.maxHealth);
                float barW   = 44f, barH = 6f;
                float barX   = sx - barW / 2f, barY = sy - barH;

                // Background
                draw.addRectFilled(barX, barY, barX + barW, barY + barH,
                        ImGui.colorConvertFloat4ToU32(0.1f, 0f, 0f, 0.8f), 2f);
                // HP fill — green → red as health drops
                float r = 0.15f + 0.85f * (1f - hpFrac);
                float g = 0.9f * hpFrac;
                draw.addRectFilled(barX, barY, barX + barW * hpFrac, barY + barH,
                        ImGui.colorConvertFloat4ToU32(r, g, 0.05f, 1.0f), 2f);
                draw.addRect(barX, barY, barX + barW, barY + barH,
                        ImGui.colorConvertFloat4ToU32(0f, 0f, 0f, 0.9f), 2f, 0, 1.2f);

                // HP numbers
                String hpStr = String.format("%.0f", enemy.health);
                draw.addText(sx - 7f, barY - 12f, black, hpStr);
                draw.addText(sx - 8f, barY - 13f,
                        ImGui.colorConvertFloat4ToU32(1f, 0.85f, 0.85f, 0.85f), hpStr);
            }
        }

        // ── ABILITY COOLDOWN ICONS ────────────────────────────────────────────
        if (!player.debugMode) {
            renderAbilityHUD(draw, screenW, screenH);
        }

        // ── WELCOME BANNER ───────────────────────────────────────────────────
        // Shown for ~6 s after the world first loads. Guides new players to F1.
        if (welcomeTimer > 0f && !showHelp) {
            renderWelcomeBanner(draw, screenW, screenH, welcomeTimer);
        }

        // ── CONTEXTUAL HINT BANNER ────────────────────────────────────────────
        // One-liners that fire on first stand deploy / first seal placement.
        if (hintTimer > 0f && hintText != null && !showHelp) {
            renderHintBanner(draw, screenW, screenH, hintTimer, hintText);
        }

        // ── WAVE COUNTER ──────────────────────────────────────────────────────
        // Top-left: "WAVE N — next in Xs" or "MAX ENEMIES REACHED"
        if (!player.stand.isInStandPerspective() && !player.debugMode) {
            int   waveNum   = enemyManager.getWaveNumber();
            float waveTimer = enemyManager.getWaveTimer();
            int   liveCount = (int) enemyManager.getEnemies().stream().filter(e -> e.alive).count();
            int   maxCount  = GameConfig.spawnMaxEnemies;

            String waveLabel;
            int    waveColor;
            if (liveCount >= maxCount) {
                waveLabel = String.format("WAVE %d  ■ MAX ENEMIES (%d/%d)", waveNum, liveCount, maxCount);
                waveColor = ImGui.colorConvertFloat4ToU32(1.0f, 0.4f, 0.15f, 0.95f);
            } else if (waveNum == 0) {
                waveLabel = String.format("Next wave in %.0fs", waveTimer);
                waveColor = ImGui.colorConvertFloat4ToU32(0.8f, 0.8f, 0.8f, 0.65f);
            } else {
                waveLabel = String.format("WAVE %d  — next in %.0fs  [%d alive]",
                        waveNum, waveTimer, liveCount);
                // Pulse red as the next wave approaches (< 8 s)
                float urgency = Math.max(0f, 1f - waveTimer / 8f);
                waveColor = ImGui.colorConvertFloat4ToU32(
                        0.75f + 0.25f * urgency,
                        0.85f - 0.50f * urgency,
                        0.85f - 0.75f * urgency,
                        0.85f);
            }
            draw.addText(12f, 13f, black,      waveLabel);
            draw.addText(11f, 12f, waveColor,  waveLabel);
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
        // Row 1 (top): Q  E  F  G  Z  K  J   — combat abilities + swap
        // Row 2 (bot): X  H  B  V             — Stand + Seal + Substitute
        float iconSize = 28f;
        float spacing  = 6f;
        int   black    = ImGui.colorConvertFloat4ToU32(0f, 0f, 0f, 0.8f);
        int   grey     = ImGui.colorConvertFloat4ToU32(0.2f, 0.2f, 0.2f, 0.8f);

        // Row 1 — Q / E / F / G / Z / K / J / M / I
        {
            float totalW = 9 * iconSize + 8 * spacing;
            float startX = screenW - totalW - 14f;
            float startY = screenH - iconSize * 2f - spacing - 14f;

            String[] labels   = { "Q",    "E",     "F",     "G",      "Z",      "K",      "J",    "M",        "I"    };
            String[] tooltips = { "Dash","Blink","Slash","Cannon","Rewind","Pillar","Swap","Quagmire","Stone" };
            float todoFrac = (GameConfig.todoCooldown > 0f)
                    ? Math.max(0f, Math.min(1f, todoSwapCooldown / GameConfig.todoCooldown))
                    : 0f;
            float quagFrac = (GameConfig.quagmireCooldown > 0f)
                    ? Math.max(0f, Math.min(1f, quagmireCooldown / GameConfig.quagmireCooldown))
                    : 0f;
            float stoneFrac = isChargingStoneCanon
                    ? Math.min(1f, stoneCanonCharge / GameConfig.stoneCanonMaxCharge)
                    : ((GameConfig.stoneCanonCooldown > 0f)
                        ? Math.max(0f, Math.min(1f, 1f - stoneCanonCooldownTimer / GameConfig.stoneCanonCooldown))
                        : 1f);
            float[]  fracs = {
                    player.abilities.getDashCooldownFrac(),
                    player.abilities.getBlinkCooldownFrac(),
                    player.attacks.getMeleeCooldownFrac(),
                    player.abilities.getCannonCooldownFrac(),
                    player.abilities.getRewindCooldownFrac(),
                    player.abilities.getPillarCooldownFrac(),
                    todoFrac,
                    quagFrac,
                    stoneFrac
            };
            // Stone canon color pulses orange while charging
            float stoneR = isChargingStoneCanon
                    ? 0.85f + (float)Math.sin(glfwGetTime() * 6) * 0.15f : 0.65f;
            int[] colors = {
                    ImGui.colorConvertFloat4ToU32(0.45f, 0.88f, 1.0f, 1.0f),  // Q dash: cyan
                    ImGui.colorConvertFloat4ToU32(0.93f, 0.95f, 1.0f, 1.0f),  // E blink: white
                    ImGui.colorConvertFloat4ToU32(1.0f,  0.55f, 0.06f, 1.0f), // F slash: amber
                    ImGui.colorConvertFloat4ToU32(1.0f,  0.75f, 0.1f, 1.0f),  // G cannon: gold
                    ImGui.colorConvertFloat4ToU32(0.3f,  0.6f,  1.0f, 1.0f),  // Z rewind: blue
                    ImGui.colorConvertFloat4ToU32(0.6f,  0.6f,  0.65f, 1.0f), // K pillar: grey
                    ImGui.colorConvertFloat4ToU32(0.95f, 0.4f,  1.0f, 1.0f),  // J swap: pink-magenta
                    ImGui.colorConvertFloat4ToU32(0.55f, 0.82f, 0.20f, 1.0f), // M quagmire: muddy green
                    ImGui.colorConvertFloat4ToU32(stoneR, 0.60f, 0.30f, 1.0f) // I stone: orange-grey
            };

            for (int i = 0; i < labels.length; i++) {
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

        // Row 2 — X (Stand)  H (Seal place)  B (Seal teleport)  V (Substitute)  L (Heal)
        {
            float totalW = 5 * iconSize + 4 * spacing;
            float startX = screenW - totalW - 14f;
            float startY = screenH - iconSize - 14f;

            // X: stand deploy — gold when deployed, blue-grey on cooldown
            boolean standDeployed = player.stand.isDeployed();
            float   standFrac     = player.stand.getRedeployCooldownFrac();
            int     standColor    = standDeployed
                    ? ImGui.colorConvertFloat4ToU32(1.0f, 0.82f, 0.15f, 1.0f)
                    : ImGui.colorConvertFloat4ToU32(0.4f, 0.65f, 0.9f,  1.0f);

            // H: seal place — cyan when ready
            float sealPlaceFrac  = player.seals.getPlaceCooldownFrac();
            int   sealPlaceColor = ImGui.colorConvertFloat4ToU32(0.2f, 0.92f, 0.92f, 1.0f);

            // B: seal teleport — teal when ready
            float sealTpFrac  = player.seals.getTeleportCooldownFrac();
            int   sealTpColor = ImGui.colorConvertFloat4ToU32(0.1f, 0.75f, 0.65f, 1.0f);

            // V: substitute — bright white when primed, dim when on cooldown
            float subFrac  = (GameConfig.substituteCooldown > 0f)
                    ? Math.max(0f, Math.min(1f, substituteCooldown / GameConfig.substituteCooldown))
                    : 0f;
            // When primed the icon is fully lit regardless of cooldown display
            float subFracDisplay = substitutePrimed ? 1f : (1f - subFrac);
            int   subColor = substitutePrimed
                    ? ImGui.colorConvertFloat4ToU32(1.0f, 1.0f, 1.0f, 1.0f) // bright white when primed
                    : ImGui.colorConvertFloat4ToU32(0.85f, 0.87f, 0.95f, 1.0f);

            // L: heal — green, pulses brighter when actively healing
            float healFrac  = player.abilities.getHealCooldownFrac();
            int   healColor = player.abilities.isHealing
                    ? ImGui.colorConvertFloat4ToU32(0.1f, 1.0f, 0.35f, 1.0f) // bright green when healing
                    : ImGui.colorConvertFloat4ToU32(0.15f, 0.75f, 0.30f, 1.0f);

            String[] labels2   = { "X",     "H",    "B",    "V",   "L"    };
            String[] tooltips2 = { "Stand", "Seal", "Warp", "Sub", "Heal" };
            float[]  fracs2    = { standDeployed ? 1f : standFrac,
                                   sealPlaceFrac, sealTpFrac, subFracDisplay, healFrac };
            int[]    colors2   = { standColor, sealPlaceColor, sealTpColor, subColor, healColor };

            for (int i = 0; i < labels2.length; i++) {
                float x = startX + i * (iconSize + spacing);
                draw.addRectFilled(x, startY, x + iconSize, startY + iconSize, grey, 5f);
                float fillH = iconSize * fracs2[i];
                if (fillH > 0.5f) {
                    draw.addRectFilled(x, startY + iconSize - fillH,
                            x + iconSize, startY + iconSize, colors2[i], 5f);
                }
                // Stand icon: bright outline when deployed
                // Substitute icon: bright pulsing outline when primed
                boolean specialBorder = (i == 0 && standDeployed) || (i == 3 && substitutePrimed);
                float outlineThick = specialBorder ? 2.5f : 1.5f;
                int   outlineCol   = specialBorder ? colors2[i] : black;
                draw.addRect(x, startY, x + iconSize, startY + iconSize,
                        outlineCol, 5f, 0, outlineThick);
                draw.addText(x + 9f, startY + 7f, black, labels2[i]);
                draw.addText(x + 9f, startY + 7f,
                        ImGui.colorConvertFloat4ToU32(1f, 1f, 1f, 0.9f), labels2[i]);
                draw.addText(x, startY + iconSize + 2f,
                        ImGui.colorConvertFloat4ToU32(0.8f, 0.8f, 0.8f, 0.7f), tooltips2[i]);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  HELP SCREEN  (F1)
    // ─────────────────────────────────────────────────────────────────────────

    private void renderHelpScreen(float screenW, float screenH) {
        float winW = Math.min(680f, screenW - 40f);
        float winH = Math.min(680f, screenH - 40f);
        ImGui.setNextWindowPos(screenW / 2f - winW / 2f, screenH / 2f - winH / 2f);
        ImGui.setNextWindowSize(winW, winH);
        ImGui.setNextWindowBgAlpha(0.94f);
        ImGui.begin("Controls & Abilities",
                imgui.flag.ImGuiWindowFlags.NoResize |
                imgui.flag.ImGuiWindowFlags.NoMove);

        ImGui.textDisabled("  [F1] or [ESC] to close.");
        ImGui.separator();
        ImGui.spacing();

        // ── MOVEMENT ──────────────────────────────────────────────────────────
        ImGui.textColored(0.5f, 0.9f, 1.0f, 1.0f, "MOVEMENT");
        ImGui.separator();
        helpRow("WASD",              "Move around.");
        helpRow("Space",             "Jump.");
        helpRow("Shift",             "Sprint.");
        helpRow("Fall + Shift land", "Ground Smash — craters the terrain and hurts all enemies nearby. The higher you fall from, the bigger the crater.");
        ImGui.spacing();

        // ── COMBAT ABILITIES ──────────────────────────────────────────────────
        ImGui.textColored(1.0f, 0.55f, 0.1f, 1.0f, "COMBAT ABILITIES");
        ImGui.separator();
        helpRow("[Q]   Dash",     "Instant burst in the direction you're moving. Short cooldown. Leaves a fading ghost trail behind you.");
        helpRow("[E]   Blink",    "Teleport to where you're looking (up to ~22 blocks away). Short cooldown.");
        helpRow("[F]   Slash",    "Wide swing that hits every enemy in a cone in front of you.");
        helpRow("[K]   Pillar",   "A stone spire shoots up beneath you and launches you into the air.");
        helpRow("[C]   Snipe",    "Hold [C] to charge up a crystal bolt, release to fire. Longer charge = bigger explosion on hit.");
        helpRow("[G]   Cannonball","Hold [G] to charge, release to launch yourself like a cannonball. Explodes on impact. Dotted arc shows your trajectory while charging.");
        ImGui.spacing();

        // ── SPECIAL ABILITIES ─────────────────────────────────────────────────
        ImGui.textColored(0.95f, 0.4f, 1.0f, 1.0f, "SPECIAL ABILITIES");
        ImGui.separator();
        helpRow("[J]   Position Swap",  "Instantly swap places with the nearest enemy in range. Good for getting out of a bad spot — or dropping them off a cliff.");
        helpRow("[V]   Substitute",     "Hold [V] to get ready. The next hit you take is completely negated — you teleport backward and a paper dummy is left at your old spot. A second later it explodes, damaging nearby enemies.");
        helpRow("[M]   Quagmire",       "Shoot a mud wave toward the enemy you're looking at. It travels along the ground and traps them on contact — they can't move for several seconds.");
        helpRow("[I]   Stone Canon",    "Hold [I] while near stone blocks to charge up. Nearby stone is absorbed into a growing projectile. Release to fire. Bigger charge = more stone consumed = bigger explosion. You can't move while charging.");
        ImGui.spacing();

        // ── MANHATTAN TRANSFER ────────────────────────────────────────────────
        ImGui.textColored(1.0f, 0.85f, 0.15f, 1.0f, "MANHATTAN TRANSFER  (Stand / Drone)");
        ImGui.separator();
        helpRow("[X]",                  "Deploy your drone above you. Press [X] again to recall it.");
        helpRow("[TAB]",                "Swap into the drone's perspective (pilot it). Press [TAB] again to return to your body.");
        helpRow("Piloting — WASD",      "Fly the drone. Space = up, Shift = down.");
        helpRow("Piloting — click",     "Fire a shot in the direction the drone is facing.");
        helpRow("Not piloting — click", "The drone auto-targets and shoots the nearest enemy it can see.");
        helpRow("Two dots (top right)", "Show whether you and the drone both have a clear shot. Both must be green to fire.");
        helpRow("Gold diamond",         "Marks your drone on screen. When it's out of view, an arrow points to it from the edge of the screen.");
        ImGui.spacing();

        // ── MINATO'S SEAL ─────────────────────────────────────────────────────
        ImGui.textColored(0.2f, 0.95f, 0.95f, 1.0f, "MINATO'S SEAL  (Teleport Anchors)");
        ImGui.separator();
        helpRow("[H]",   "Throw a seal — it sticks to the first surface it hits.");
        helpRow("[B]",   "Teleport instantly to the seal closest to your crosshair. The targeted seal glows bigger and brighter.");
        helpRow("[N]",   "Pull the targeted seal back to you without teleporting.");
        helpRow("Tip",   "You can place up to 5 seals at a time. Great for escape routes, high ground, and repositioning. Arrows on the screen edges point to seals that are out of view.");
        ImGui.spacing();

        // ── WORLD & UI ────────────────────────────────────────────────────────
        ImGui.textColored(0.75f, 0.75f, 0.75f, 1.0f, "WORLD & BUILDING");
        ImGui.separator();
        helpRow("Hold left click",  "Break the block you're looking at.");
        helpRow("Right click",      "Place the block selected in your hotbar.");
        helpRow("1 – 9",            "Switch hotbar slot.");
        helpRow("[P]",              "Spawn a test enemy where you're looking. Enemies also spawn automatically in waves.");
        helpRow("[ESC]",            "Pause menu.");
        helpRow("[F1]",             "This screen.");
        helpRow("[F3]",             "Debug info (your position, frame rate, render distance).");
        ImGui.spacing();

        // ── ENEMY TYPES ───────────────────────────────────────────────────────
        ImGui.textColored(0.9f, 0.3f, 0.1f, 1.0f, "ENEMIES");
        ImGui.separator();
        helpRow("Red-orange",   "Grunt — standard enemy. Medium speed and health.");
        helpRow("Yellow-green", "Stalker — very fast but fragile. Has a long detection range so it will spot you first.");
        helpRow("Purple",       "Brute — slow but very tanky and hits hard up close.");
        helpRow("Waves",        "Enemies keep spawning in waves. The wave counter is in the top-left corner.");
        helpRow("Health bars",  "Float above each enemy. Green = healthy, red = almost dead.");

        ImGui.end();
    }

    /**
     * Single row in the help screen: yellow key label on the left,
     * wrapped description on the right. Fixed column split at 230px.
     */
    private void helpRow(String key, String desc) {
        ImGui.textColored(1.0f, 0.88f, 0.25f, 1.0f, "  " + key);
        ImGui.sameLine(230f);
        ImGui.pushTextWrapPos(0f);   // wrap at window right edge
        ImGui.text(desc);
        ImGui.popTextWrapPos();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  TUTORIAL BANNERS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * "Press F1 for controls" banner shown for the first few seconds of play.
     * Fades in over 0.4 s and out over 1 s.
     */
    private void renderWelcomeBanner(imgui.ImDrawList draw,
                                     float screenW, float screenH, float timer) {
        float alpha;
        if      (timer > 5.6f) alpha = (6f - timer) / 0.4f;   // fade-in 0→0.4 s
        else if (timer < 1.0f) alpha = timer;                   // fade-out last 1 s
        else                   alpha = 1f;
        alpha = Math.max(0f, Math.min(1f, alpha));

        int bg     = ImGui.colorConvertFloat4ToU32(0.04f, 0.04f, 0.14f, 0.90f * alpha);
        int border = ImGui.colorConvertFloat4ToU32(0.4f,  0.85f, 1.0f,  0.55f * alpha);
        int white  = ImGui.colorConvertFloat4ToU32(1.0f,  1.0f,  1.0f,  alpha);
        int cyan   = ImGui.colorConvertFloat4ToU32(0.4f,  0.9f,  1.0f,  alpha);
        int black  = ImGui.colorConvertFloat4ToU32(0f,    0f,    0f,    alpha);

        float bW = 400f, bH = 56f;
        float bX = screenW / 2f - bW / 2f;
        float bY = screenH * 0.19f;

        draw.addRectFilled(bX, bY, bX + bW, bY + bH, bg, 8f);
        draw.addRect(bX, bY, bX + bW, bY + bH, border, 8f, 0, 1.8f);

        String line1 = "You have lots of abilities!";
        String line2 = "Press  [F1]  for the full controls & ability guide.";

        // Approximate centering (default font ~7 px/char)
        draw.addText(bX + bW / 2f - line1.length() * 3.6f,     bY + 8f,  black, line1);
        draw.addText(bX + bW / 2f - line1.length() * 3.6f - 1, bY + 7f,  white, line1);
        draw.addText(bX + bW / 2f - line2.length() * 3.6f,     bY + 29f, black, line2);
        draw.addText(bX + bW / 2f - line2.length() * 3.6f - 1, bY + 28f, cyan,  line2);
    }

    /**
     * One-liner contextual hint (e.g. first stand deploy or first seal placed).
     * Fades out over the last 0.6 s of its duration.
     */
    private void renderHintBanner(imgui.ImDrawList draw,
                                   float screenW, float screenH,
                                   float timer, String text) {
        float alpha = (timer < 0.6f) ? timer / 0.6f : 1f;
        alpha = Math.max(0f, Math.min(1f, alpha));

        int bg    = ImGui.colorConvertFloat4ToU32(0.0f, 0.0f, 0.0f,  0.72f * alpha);
        int fg    = ImGui.colorConvertFloat4ToU32(1.0f, 0.88f, 0.3f, alpha);
        int black = ImGui.colorConvertFloat4ToU32(0f,   0f,   0f,    alpha);

        // Rough text width: 7 px/char
        float tw = text.length() * 7.0f;
        float bX = screenW / 2f - tw / 2f - 12f;
        float bY = 36f; // just below the wave counter line

        draw.addRectFilled(bX, bY, bX + tw + 24f, bY + 22f, bg, 4f);
        draw.addText(bX + 13f, bY + 5f, black, text);
        draw.addText(bX + 12f, bY + 4f, fg,    text);
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