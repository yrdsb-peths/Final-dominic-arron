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
    long window;
    Player player;
    World world;
    WorldGen worldGen;

    private int portalFboW = 0;
    private int portalFboH = 0;

    // ── SPAWN POINT ───────────────────────────────────────────────────────────
    // Fixed world-spawn XZ. Y is determined by scanning for the surface at load.
    private static final float SPAWN_X = 777.0f;
    private static final float SPAWN_Z = 777.0f;
    /** Actual surface Y found during preload; used for respawning on death. */
    private float spawnSurfaceY = 250.0f;

    NoiseVisualizer noiseVis;
    boolean showNoiseViewer = false;
    RaycastResult lastTarget = null;

    NetworkSession network;
    RemotePlayer remotePlayer;

    private boolean networkInitialized = false;
    final ImString ipInput = new ImString("127.0.0.1", 64);

    private final double[]  lastMouseX = {640.0};
    private final double[]  lastMouseY = {360.0};
    private final boolean[] firstMouse = {true};

    private final ImGuiImplGlfw imguiGlfw = new ImGuiImplGlfw();
    private final ImGuiImplGl3  imguiGl3  = new ImGuiImplGl3();

    // ── UI STATE ──────────────────────────────────────────────────────────────
    boolean isPaused  = false;
    boolean showDebug = false;
    boolean showChat  = false;
    final ImString chatInput = new ImString(256);
    final List<String> chatHistory = new ArrayList<>();
    final ImString seedInput = new ImString(32);

    final Inventory inventory = new Inventory();
    int   selectedSlot  = 0;
    Block selectedBlock = Block.AIR;

    final Block[] hotbar = {
            Block.GRASS, Block.DIRT, Block.STONE, Block.WATER,
            Block.AIR, Block.AIR, Block.AIR, Block.AIR, Block.AIR
    };

    final List<DroppedItem> droppedItems = new ArrayList<>();
    private final Map<Block, Mesh>  itemMeshes   = new HashMap<>();

    // ── ENEMY SYSTEM ──────────────────────────────────────────────────────────
    EnemyManager enemyManager;
    /** Edge-detect for P key to spawn enemies. */
    private boolean lastP = false;

    // ── TODO'S TECHNIQUE (J key) ──────────────────────────────────────────────
    float   todoSwapCooldown = 0f;
    private boolean lastJ            = false;

    // ── QUAGMIRE (M key) ─────────────────────────────────────────────────────
    float   quagmireCooldown = 0f;
    private boolean lastM            = false;
    /**
     * Active mud waves.  Each float[12]:
     *   [0-2] current position   [3-4] direction (x,z normalised)
     *   [5]   speed              [6]   dist travelled   [7] total dist
     *   [8]   target enemy ID    [9]   (reserved)
     *   [10]  last placed block X  [11] last placed block Z
     */
    final List<float[]> mudWaves = new ArrayList<>();

    // ── STONE CANON (I key) ───────────────────────────────────────────────────
    boolean isChargingStoneCanon    = false;
    float   stoneCanonCharge        = 0f;
    private float   stoneCanonNextConsume   = 0f;  // countdown to next block consumed
    private int     stoneCanonBlocksConsumed = 0;
    private Vector3f stoneCanonLockedPos    = null; // position locked when charging starts
    private Vector3f stoneCanonGroundPos   = null; // ground point in front where boulder rises
    float   stoneCanonCooldownTimer = 0f;
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
    boolean substitutePrimed   = false;
    float   substituteCooldown = 0f;
    /**
     * Live paper dummies.  Each entry: float[5] = { x, y, z, timer, maxTimer }.
     * Timer counts down from substituteDummyLifetime to 0, then explodes.
     */
    private final List<float[]> substituteDummies = new ArrayList<>();

    // ── TUTORIAL / HELP ───────────────────────────────────────────────────────
    /** F1 toggles the full controls reference overlay. */
    boolean showHelp       = false;
    private boolean lastF1         = false;
    /** Auto-dismiss welcome banner shown when the game first loads. */
    float   welcomeTimer   = 0f;
    private boolean welcomeStarted = false;
    /** One-liner contextual hint (e.g. first stand deploy, first seal placed). */
    String  hintText       = null;
    float   hintTimer      = 0f;
    private boolean standHintShown = false;
    private boolean sealHintShown  = false;
    /** Edge-detect for contextual hint triggers. */
    private boolean wasStandDeployed = false;
    private int     lastSealCount    = 0;

    float   breakProgress = 0.0f;
    int     breakX, breakY, breakZ;
    boolean breakingActive = false;

    // ── KAMUI DISTORTION FBO ─────────────────────────────────────────────────
    // When Kamui is active the 3-D scene is rendered into an off-screen texture,
    // then a post-process distortion shader warps it before ImGui is composited.
    private int  kamuiFbo        = 0;
    private int  kamuiFboTex     = 0;
    private int  kamuiFboRbo     = 0;   // depth renderbuffer
    private int  kamuiScreenQuad = 0;   // VAO for the full-screen triangle pair
    private int  kamuiFboW       = 0;   // last known FBO size — recreate on resize
    private int  kamuiFboH       = 0;
    private com.leaf.game.render.Shader distortShader = null;
    /** All ImGui HUD rendering — see WindowHud.java */
    WindowHud hud;

    // ── PRE-GENERATION STATE ──────────────────────────────────────────────────
    boolean     isPreloading = false;
    int         preloadRadius = 10;
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
    /** Last-frame mana, used to detect the moment mana hits zero. */
    private float   lastMana         = -1f;
    /** Last-frame camera position — lets us derive listener velocity for Doppler. */
    private final Vector3f lastListenerPos = new Vector3f();
    private boolean listenerPosInit = false;
    /** Last muffle amount sent to OpenAL; only re-sent when it moves meaningfully. */
    private float   lastMuffle       = 0f;
    /** Last reverb environment — only call setEnvironment when this changes. */
    private int     lastEnv          = AudioManager.ENV_NONE;

    // ── Water sound state ────────────────────────────────────────────────────
    private boolean lastFeetInWater  = false;
    private boolean lastCamSubmerged = false;

    // ── Wind stinger timers ──────────────────────────────────────────────────
    // wind_harsh: random gusts fired during fast air movement.
    // caveWindCooldown: wind_cemetery eerie stinger, fires slowly in cave env.
    private float windStingerCooldown = 0f;
    private float caveWindCooldown    = 5f;   // start at 5 s so it doesn't fire on room entry
    // windFade: 0→1 multiplier that smoothly fades wind beds in/out instead of
    // abruptly starting/stopping them (avoids pops and sudden silence on landing).
    private float windFade            = 0f;

    // ── Footstep state ───────────────────────────────────────────────────────
    // Walking/running files are long loops — we keep one looping continuously
    // rather than re-triggering them as one-shots.
    private String  activeStepLoop    = null; // which step loop is currently running

    // ── Flight-stop swoosh ────────────────────────────────────────────────────
    private boolean lastFlightMode    = false; // player.debugMode last frame (before update)

    // ── Crystal dig sequence (plays clank1..4 in shuffled random order) ─────
    // cystal_clank2 has a typo in the filename on disk — keep it to match the file.
    static final String[] CRYSTAL_CLANKS  = {
            "crystal_clank1", "cystal_clank2", "crystal_clank3", "crystal_clank4"
    };
    private int[]   crystalClankOrder = {0, 1, 2, 3};
    private int     crystalClankIdx   = 4;    // force a shuffle on first use

    // ── Dig sound timer (fires while holding break key) ────────────────────
    float digSoundTimer = 0f;   // package-private — reset from WindowHud.updateBreaking()
    float digPreDelay   = 0f;   // how long break key has been held — sounds start after a brief delay
    float   pathReadiness    = 0f;      // 0..1 shown in HUD during charging
    private boolean clientSpawnedAtHost = false;

    // ── PHYSICAL FRAMEBUFFER SIZE ─────────────────────────────────────────────
    // Tracked as instance fields so portal/kamui FBO code can read them without
    // needing them passed as parameters.  Updated once per frame via
    // glfwGetFramebufferSize inside loop().  On Retina/HiDPI displays these are
    // 2× the logical window size — the portal viewportSize uniform MUST use
    // these values, not the hardcoded PORTAL_FBO_W/H, to avoid UV tiling.
    private final int[] fw = {1280};
    private final int[] fh = {720};

    // ═══════════════════════════════════════════════════════════════════════════
    //  NON-EUCLIDEAN GEOMETRY — PORTAL FBO INFRASTRUCTURE
    // ═══════════════════════════════════════════════════════════════════════════
    // Up to 4 FBO slots shared between all non-Euclidean rooms.
    // Slot 0: BOTI entry portal   Slot 1: BOTI exit portal
    // Slot 2: RR portal A→B      Slot 3: RR portal B→A
    private int[] portalFbo      = null;
    private int[] portalColorTex = null;
    private int[] portalDepthRbo = null;
    private static final int PORTAL_FBO_W = 1280;
    private static final int PORTAL_FBO_H = 720;
    private static final int PORTAL_SLOTS = 4;

    // ── "BIGGER ON THE INSIDE" TUNNEL (F5) ───────────────────────────────────
    //
    // Structure A (exterior casing, 5-block arch):
    //   World XZ = (2400, 2400), Y floor = 400,  Depth = 5  blocks (7W × 6H)
    //
    // Structure B (interior tunnel, 50-block):
    //   Same XZ (same chunks!), Y floor = 900,  Depth = 50 blocks (7W × 6H)
    //
    // The portal translates purely in Y (+500 inward, −500 outward).
    // Both structures are in the same CX/CZ columns so their chunks are always
    // loaded together — no extra chunk-load logic needed.
    private static final int BOTI_X0     = 2400; // west wall world-X
    private static final int BOTI_Z0     = 2400; // entrance face world-Z
    private static final int BOTI_A_Y0   = 400;  // Structure A floor Y
    private static final int BOTI_B_Y0   = 900;  // Structure B floor Y (CY=1)
    private static final int BOTI_W      = 7;    // total width  (5 interior + 2 walls)
    private static final int BOTI_H      = 6;    // total height (4 interior + floor + ceil)
    private static final int BOTI_A_D    = 5;    // Structure A depth
    private static final int BOTI_B_D    = 50;   // Structure B depth
    private static final int BOTI_DY     = BOTI_B_Y0 - BOTI_A_Y0; // 500 — A→B Y offset
    // Player teleport X band — only trigger portal if in this X range
    private static final float BOTI_X_LO = BOTI_X0 + 0.5f;
    private static final float BOTI_X_HI = BOTI_X0 + BOTI_W - 0.5f;

    private boolean botiBuilt       = false;
    private boolean botiInside      = false; // true when player is in Structure B
    private Mesh    botiEntryMesh   = null;  // portal quad at A entrance
    private Mesh    botiExitMesh    = null;  // portal quad at B far end
    /** Suppress chunk eviction / generation while non-Euclidean space is active. */
    private boolean nonEuclideanActive = false;
    private boolean lastF5 = false;

    // ── "ROTATING ROOMS" (F6) ────────────────────────────────────────────────
    private static final int RR_W        = 12;   // Room width/depth
    private static final int RR_FLOOR_Y  = 400;  // Floor elevation

    // Grid A (4 rooms) and Grid B (4 rooms) placed exactly 50 blocks apart on X
    private static final int RR_A_X0     = 2450;
    private static final int RR_A_Z0     = 2400;
    private static final int RR_B_X0     = 2500;
    private static final int RR_B_Z0     = 2400;

    private boolean rrBuilt       = false;
    private int     rrRoom        = 0;    // 0=outside, 4=GridA SW, 8=GridB SW
    private Mesh    rrPortal45    = null; // Portal A -> B
    private Mesh    rrPortal61    = null; // Portal B -> A
    private boolean lastF6        = false;
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
                    firstMouse[0] = true;
                } else if (showHelp) {
                    // Help screen takes priority over pause so ESC cleanly dismisses it
                    showHelp = false;
                    boolean stillOverlay = showDebug || showNoiseViewer || isPaused;
                    glfwSetInputMode(window, GLFW_CURSOR,
                            stillOverlay ? GLFW_CURSOR_NORMAL : GLFW_CURSOR_DISABLED);
                    if (!stillOverlay) firstMouse[0] = true;
                } else if (showNoiseViewer) {
                    showNoiseViewer = false;
                    glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
                    firstMouse[0] = true;
                } else {
                    isPaused = !isPaused;
                    glfwSetInputMode(window, GLFW_CURSOR,
                            isPaused ? GLFW_CURSOR_NORMAL : GLFW_CURSOR_DISABLED);
                    if (!isPaused) firstMouse[0] = true;
                }
            }

            if (isPaused) return;

            if (key == GLFW_KEY_F1 && action == GLFW_RELEASE && !showChat) {
                showHelp = !showHelp;
                boolean overlay1 = showHelp || showDebug || showNoiseViewer;
                glfwSetInputMode(window, GLFW_CURSOR,
                        overlay1 ? GLFW_CURSOR_NORMAL : GLFW_CURSOR_DISABLED);
                if (!overlay1) firstMouse[0] = true;
            }

            if (key == GLFW_KEY_F3 && action == GLFW_RELEASE && !showChat) {
                showDebug = !showDebug;
                // F3 releases cursor so ImGui debug elements are clickable.
                // The cursorPos callback returns early when showDebug is true,
                // so camera won't spin when the cursor is free.
                boolean overlay3 = showHelp || showDebug || showNoiseViewer || isPaused;
                glfwSetInputMode(window, GLFW_CURSOR,
                        overlay3 ? GLFW_CURSOR_NORMAL : GLFW_CURSOR_DISABLED);
                if (!overlay3) firstMouse[0] = true;
            }

            if (key == GLFW_KEY_F4 && action == GLFW_RELEASE && !showChat) {
                showNoiseViewer = !showNoiseViewer;
                boolean overlay4 = showHelp || showDebug || showNoiseViewer;
                glfwSetInputMode(window, GLFW_CURSOR,
                        overlay4 ? GLFW_CURSOR_NORMAL : GLFW_CURSOR_DISABLED);
                if (!overlay4) firstMouse[0] = true;
            }

            // F5 — teleport to "Bigger on the Inside" tunnel entrance
            // (Actual build + camera placement is handled in the game loop.)
            if (key == GLFW_KEY_F5 && action == GLFW_RELEASE && !showChat) {
                lastF5 = false; // edge detected in loop
            }

            // F6 — teleport to "Rotating Rooms" entrance
            if (key == GLFW_KEY_F6 && action == GLFW_RELEASE && !showChat) {
                lastF6 = false; // edge detected in loop
            }

            // T opens chat (release event only, so holding T for time-dilation is safe
            // because time-dilation uses glfwGetKey in the game loop, not this callback)
            if (key == GLFW_KEY_T && action == GLFW_RELEASE && !showChat && !showDebug && !isPaused) {
                showChat = true;
                glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
            }

            if (action == GLFW_PRESS && !showChat) {
                if (key >= GLFW_KEY_1 && key <= GLFW_KEY_9) {
                    selectedSlot = key - GLFW_KEY_1;
                }
            }
        });

        glfwSetMouseButtonCallback(window, (win, button, action, mods) -> {
            if (!networkInitialized || isPreloading || showChat || showNoiseViewer || isPaused || showHelp)
                return;

            if (button == GLFW_MOUSE_BUTTON_LEFT) {
                // While piloting the stand drone OR auto-aiming at an enemy, LMB is
                // consumed by the stand — block normal block-breaking.
                // While Kamui is active, LMB drives the absorption system instead.
                boolean standConsumedLMB = player.stand.isInStandPerspective()
                        || player.stand.autoAimedThisFrame;
                boolean kamuiConsumedLMB = player != null && player.abilities.isKamui;
                if (!standConsumedLMB && !kamuiConsumedLMB) {
                    breakingActive = (action == GLFW_PRESS || action == GLFW_REPEAT);
                    if (action == GLFW_RELEASE) { breakProgress = 0.0f; digPreDelay = 0.0f; }
                }
                if (kamuiConsumedLMB && action == GLFW_RELEASE) {
                    // Release resets absorption charge
                    if (player != null) {
                        player.abilities.absorptionCharge = 0f;
                        player.abilities.isAbsorbing      = false;
                    }
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
                            // Play a place sound based on block material
                            String placeSnd = blockPlaceSound(selectedBlock);
                            if (placeSnd != null) AudioManager.play(placeSnd);
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
            if (!player.isSmashing() && !player.abilities.isCharging()
                    && !isChargingStoneCanon) {
                camera.yaw   += dx * GameConfig.mouseSensitivity;
                camera.pitch -= dy * GameConfig.mouseSensitivity;
                if (!player.abilities.isCannonballing) {
                    camera.clampPitch();
                }
            }
        });
    }

    void startPreload() {
        worldGen.resetSeed(GameConfig.seed);
        world.clearAllChunks();
        world.meshingQueue.clear();
        networkInitialized = true;
        isPreloading       = true;
    }

    private void loop() {
        int[] ww = new int[1], wh = new int[1];
        glfwGetWindowSize(window, ww, wh);
        glfwGetFramebufferSize(window, fw, fh);

        GL.createCapabilities();
        imguiGl3.init("#version 330");

        glEnable(GL_DEPTH_TEST);
        glClearColor(0.5f, 0.7f, 0.9f, 1.0f);

        // ── PORTAL FBOs (non-Euclidean rendering) ────────────────────────────
        if (portalFbo == null || fw[0] != portalFboW || fh[0] != portalFboH) {
            createPortalFbos();
            portalFboW = fw[0];
            portalFboH = fh[0];
        }

        Shader shader = new Shader(
                "src/main/resources/shaders/vertex.glsl",
                "src/main/resources/shaders/fragment.glsl");

        // ── KAMUI DISTORTION SHADER + SCREEN QUAD ────────────────────────────
        distortShader = new com.leaf.game.render.Shader(
                "src/main/resources/shaders/distort_vertex.glsl",
                "src/main/resources/shaders/distort_fragment.glsl");

        // Full-screen quad: two triangles covering NDC [-1,1]
        float[] quadVerts = {
            // x      y     u     v
            -1.0f,  1.0f, 0.0f, 1.0f,
            -1.0f, -1.0f, 0.0f, 0.0f,
             1.0f, -1.0f, 1.0f, 0.0f,
            -1.0f,  1.0f, 0.0f, 1.0f,
             1.0f, -1.0f, 1.0f, 0.0f,
             1.0f,  1.0f, 1.0f, 1.0f
        };
        kamuiScreenQuad = org.lwjgl.opengl.GL30.glGenVertexArrays();
        int quadVbo = org.lwjgl.opengl.GL15.glGenBuffers();
        org.lwjgl.opengl.GL30.glBindVertexArray(kamuiScreenQuad);
        org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, quadVbo);
        org.lwjgl.opengl.GL15.glBufferData(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER,
                quadVerts, org.lwjgl.opengl.GL15.GL_STATIC_DRAW);
        // attrib 0: position (xy)
        org.lwjgl.opengl.GL20.glVertexAttribPointer(0, 2, GL_FLOAT, false, 4*4, 0L);
        org.lwjgl.opengl.GL20.glEnableVertexAttribArray(0);
        // attrib 1: texcoord (uv)
        org.lwjgl.opengl.GL20.glVertexAttribPointer(1, 2, GL_FLOAT, false, 4*4, 2L*4);
        org.lwjgl.opengl.GL20.glEnableVertexAttribArray(1);
        org.lwjgl.opengl.GL30.glBindVertexArray(0);

        Camera camera = new Camera();
        setupMouseLook(camera);
        hud = new WindowHud(this);

        // ── AUDIO PRELOAD ──────────────────────────────────────────────────────
        // Warm up the JVM audio mixer so the very first play() call has no delay,
        // then decode every sound file once into heap memory.  Playback from this
        // point on is pure memory copy — no classpath IO, no thread-creation cost.
        AudioManager.warmup();
        // Subtle Doppler — enough to feel projectiles/wind pass by without the
        // cartoonish over-pitch a factor of 1.0 produces.
        AudioManager.setDopplerFactor(0.7f);
        for (String snd : new String[]{
                // Kamui
                "kamui_enter", "kamui_exit", "kamui_duration", "kamui_distortion",
                "distortion_snap",
                // Combat
                "swing", "hit", "block", "charged_release", "blink",
                "grab_start", "grab_slam", "ground_smash", "fall_smash",
                // Snipe / Manhattan Transfer
                "snipe1", "snipe2", "snipe_loadgun", "snipe_redirect",
                // Abilities
                "stone_canon", "charging",
                "quagmire", "clap", "paper_explode",
                "seal_place", "healing",
                // Lightning
                "lightning_charge", "lightning_strike",
                // UI / feedback
                "mana_empty",
                // Wind (layered beds + stingers)
                "wind/wind_soft", "wind/wind_blow", "wind/wind_big",
                "wind/wind_harsh", "wind/wind_cemetery",
                // Water (one-shots + ambience loop)
                "water/water_splash", "water/water_enter",
                "water/water_leave", "water/water_exit2", "water/water_exit3",
                "water/underwater_ambience",
                // Landing impacts
                "fall_hit", "fall_light", "fall_sandy",
                // Flight
                "swoosh",
                // Footsteps
                "walking", "running", "walking_sand",
                // Block sounds (place + break + dig)
                "block_stone", "block_soil", "block_sand", "block_crystal",
                "stone_digging", "soil_digging", "sand_digging",
                "crystal_clank1", "cystal_clank2", "crystal_clank3", "crystal_clank4",
                // (block sounds now live under "block_stone/soil/sand/crystal" — see preload above)
        }) { AudioManager.preload(snd); }

        // ── SPAWN POINT ────────────────────────────────────────────────────────
        // Spawn at (777, 250, 777): these coordinates produce non-integer noise
        // inputs at every frequency used by the terrain samplers, ensuring the
        // terrain is visibly seed-dependent right at the start.
        this.player   = new Player(SPAWN_X, 250.0f, SPAWN_Z);
        this.world    = new World();
        this.worldGen = new WorldGen();
        this.noiseVis = new NoiseVisualizer(worldGen);

        // ── ENEMY SYSTEM ─────────────────────────────────────────────────────
        this.enemyManager = new EnemyManager();
        player.stand.setEnemyManager(enemyManager);
        player.attacks.setEnemyManager(enemyManager);
        player.seals.setEnemyManager(enemyManager);      // enables seal-on-enemy attachment
        player.lightning.setEnemyManager(enemyManager); // enables lightning targeting
        player.grab.setEnemyManager(enemyManager);       // enables grab targeting

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
            ScreenEffectManager.INSTANCE.tick(rawDeltaTime);
            float deltaTime = rawDeltaTime * tc.getScale()
                    * ScreenEffectManager.INSTANCE.getHitStopScale();

            glfwGetWindowSize(window, ww, wh);
            glfwGetFramebufferSize(window, fw, fh);

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
                        player.position.x = SPAWN_X; player.position.z = SPAWN_Z;
                    }
                    world.updateChunks(world, worldGen, player);
                } else {
                    if (isPreloading) {
                        isPreloading = false;
                        int spawnX = (int)Math.floor(player.position.x);
                        int spawnZ = (int)Math.floor(player.position.z);
                        // Scan from ceiling down; find the highest outdoor solid block.
                        // Requires full sky visibility above (no solid block between
                        // the surface and Chunk.HEIGHT) — this prevents spawning on
                        // cave ceilings or inside underground structures.
                        outer:
                        for (int ly = Chunk.HEIGHT - 2; ly >= 1; ly--) {
                            if (!world.getBlock(spawnX, ly, spawnZ).isSolid()) continue;
                            // Require all blocks above to be non-solid (outdoor surface)
                            for (int sy = ly + 1; sy < Chunk.HEIGHT; sy++) {
                                if (world.getBlock(spawnX, sy, spawnZ).isSolid()) continue outer;
                            }
                            spawnSurfaceY     = ly + 1.5f;
                            player.position.y = spawnSurfaceY;
                            break;
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

                    if (!showChat && !showNoiseViewer && !isPaused && !showHelp) {
                        // ── PLAYER UPDATE (time-scaled) ────────────────────────
                        // Save state BEFORE update so we can detect transitions.
                        // player.update() resets highestY on landing and toggles debugMode.
                        float   savedHighestY    = player.highestY;
                        boolean wasOnGroundAudio = player.isOnGround();
                        boolean wasFlightMode    = player.debugMode;
                        player.update(window, camera, world, deltaTime);
                        hud.updateBreaking(deltaTime);

                        // ── 3D AUDIO LISTENER ──────────────────────────────────
                        // Move the OpenAL listener to the camera each frame and
                        // derive its velocity from the position delta (Player has
                        // no full velocity vector). Velocity drives Doppler shift.
                        Vector3f listenerVel = new Vector3f();
                        if (listenerPosInit && deltaTime > 1e-4f) {
                            listenerVel.set(camera.position).sub(lastListenerPos).div(deltaTime);
                            // Clamp absurd teleport spikes (kamui / respawn) so we
                            // don't fire a Doppler screech across the whole map.
                            if (listenerVel.length() > 60f) listenerVel.set(0, 0, 0);
                        }
                        AudioManager.updateListener(camera, listenerVel);
                        lastListenerPos.set(camera.position);
                        listenerPosInit = true;

                        // ── REVERB ENVIRONMENT ─────────────────────────────────
                        // Checked every frame but only applied when the zone
                        // actually changes, so there is no per-frame OpenAL cost.
                        //
                        // Priority: underwater > cave > open air.
                        // isUnderRoof(12): tweak the number to change how "tight"
                        // a space needs to be before reverb kicks in.
                        int targetEnv;
                        if (player.isCameraSubmerged()) {
                            targetEnv = AudioManager.ENV_UNDERWATER;
                        } else if (isUnderRoof(12)) {
                            targetEnv = AudioManager.ENV_CAVE;
                        } else {
                            targetEnv = AudioManager.ENV_NONE;
                        }
                        if (targetEnv != lastEnv) {
                            AudioManager.setEnvironment(targetEnv);
                            lastEnv = targetEnv;
                        }

                        // ── FLIGHT STOP SWOOSH ─────────────────────────────────
                        // Fires on the frame flight mode turns off (double-tap space).
                        if (wasFlightMode && !player.debugMode) {
                            AudioManager.play("swoosh", 0.80f);
                        }

                        // ── FOOTSTEPS ─────────────────────────────────────────
                        // Only while grounded, not submerged, and actually moving.
                        // Surface type: sand/red-sand → walking_sand; sprint → running.
                        float horizSpeedStep = (float) Math.sqrt(
                                listenerVel.x * listenerVel.x + listenerVel.z * listenerVel.z);
                        // Walking/running files are long loops — decide which one
                        // should be playing this frame and switch loops on change.
                        String wantedStep = null;
                        if (player.isOnGround()
                                && !player.isCameraSubmerged()
                                && !player.debugMode
                                && horizSpeedStep > 0.8f) {
                            Block underFoot = world.getBlock(
                                    (int)Math.floor(player.position.x),
                                    (int)Math.floor(player.position.y) - 1,
                                    (int)Math.floor(player.position.z));
                            boolean sandy = (underFoot == Block.SAND
                                         || underFoot == Block.RED_SAND);
                            if (sandy)                  wantedStep = "walking_sand";
                            else if (player.isSprinting()) wantedStep = "running";
                            else                           wantedStep = "walking";
                        }

                        if (!java.util.Objects.equals(wantedStep, activeStepLoop)) {
                            if (activeStepLoop != null)
                                AudioManager.stopContinuous(activeStepLoop);
                            if (wantedStep != null)
                                AudioManager.playContinuous(wantedStep, 0.70f);
                            activeStepLoop = wantedStep;
                        }

                        // ── METEOR SPAWN: smash start → STAR_IRON falling from sky ──
                        // Detects the leading edge of isSmashing so the meteor only
                        // spawns once per smash, not every frame of the descent.
                        // Only spawns on rocky/hard ground — looks wrong on sand/beach.
                        boolean nowSmashing = player.isSmashing();
                        if (nowSmashing && !wasSmashing) {
                            // Scan downward — at smash start the player is still airborne,
                            // so position.y - 1 is AIR. Find the first solid block below.
                            Block groundBlock = Block.AIR;
                            int scanX = (int)Math.floor(player.position.x);
                            int scanZ = (int)Math.floor(player.position.z);
                            int scanYStart = (int)Math.floor(player.position.y);
                            for (int sy = scanYStart; sy >= Math.max(0, scanYStart - 200); sy--) {
                                Block b = world.getBlock(scanX, sy, scanZ);
                                if (b.isSolid()) { groundBlock = b; break; }
                            }
                            boolean isRockyGround = groundBlock.hardness >= 2.5f
                                    || groundBlock == Block.GRAVEL;
                            if (isRockyGround) {
                                Vector3f meteorVel = new Vector3f(0f, -GameConfig.smashDescentSpeed * 1.5f, 0f);
                                droppedItems.add(new DroppedItem(
                                        (int)player.position.x,
                                        (int)(player.position.y + 100),
                                        (int)player.position.z,
                                        Block.STAR_IRON,
                                        meteorVel));
                            }
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

                        // ── GRAB SLAM IMPACT ───────────────────────────────────
                        // GrabController.tick() already polled pendingGrabImpact for
                        // the shake request; here we create the crater + ejecta.
                        for (Enemy grabEnemy : enemyManager.getEnemies()) {
                            if (!grabEnemy.pendingGrabImpact) continue;
                            int gx = grabEnemy.grabImpactX;
                            int gy = grabEnemy.grabImpactY;
                            int gz = grabEnemy.grabImpactZ;
                            int gr = GameConfig.grabCraterRadius;
                            world.createImpactCrater(gx, gy, gz, gr);
                            spawnCraterEjecta(gx, gy, gz, gr);
                            enemyManager.processExplosion(
                                    new float[]{ gx + 0.5f, gy + 0.5f, gz + 0.5f, gr * 1.5f },
                                    grabEnemy.grabImpactIsGround
                                            ? GameConfig.grabGroundDamage * 0.4f
                                            : GameConfig.grabWallDamage   * 0.4f);
                            if (grabEnemy.grabImpactIsGround) AudioManager.play("ground_smash");
                            ScreenEffectManager.INSTANCE.hitStop(3);
                            ScreenEffectManager.INSTANCE.flashGrabSlam();
                            // Brutal impact shake — bigger than standard smash
                            activeShakeDuration  = grabEnemy.grabImpactIsGround ? 0.75f : 0.55f;
                            activeShakeAmplitude = grabEnemy.grabImpactIsGround ? 0.38f : 0.28f;
                            smashShakeTimer = Math.max(smashShakeTimer, activeShakeDuration);
                            // Signal WindowHud to do an orange impact flash
                            player.grab.throwFlash = Math.max(player.grab.throwFlash, 0.40f);
                            // pendingGrabImpact is reset at the top of Enemy.update() next frame
                        }

                        // ── GRAB THROW LAUNCH RECOIL ───────────────────────────
                        // throwFlash is set the frame a wall/slam throw fires.
                        if (player.grab.throwFlash > 0f) {
                            // Brief camera judder — feels like pulling a trigger
                            smashShakeTimer = Math.max(smashShakeTimer, 0.12f);
                            activeShakeDuration  = 0.12f;
                            activeShakeAmplitude = 0.14f;
                            // (throwFlash decays automatically in GrabController.tick)
                        }

                        // ── GRAB SHAKE FALLBACK (controller's shakeRequest) ─────
                        if (player.grab.shakeRequest > 0f) {
                            smashShakeTimer = Math.max(smashShakeTimer,
                                    GameConfig.grabShakeDuration);
                            player.grab.shakeRequest = 0f;
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
                            float cyaw  = camera.yaw;
                            float backX = -(float) Math.cos(cyaw);
                            float backZ = -(float) Math.sin(cyaw);
                            // Perpendicular axes for diagonal escape attempts
                            float leftX  = -backZ,  leftZ  =  backX;
                            float rightX =  backZ,  rightZ = -backX;
                            float bd = GameConfig.substituteBackDist;

                            // Candidate directions: [dx, yShift-for-collision, dz]
                            // Try straight back first, then back-left/right diagonals,
                            // then back with a 1-block vertical offset (handles ledges).
                            float[][] dirs = {
                                { backX,                                  0f, backZ                                 },
                                { backX * 0.707f + leftX  * 0.707f,     0f, backZ * 0.707f + leftZ  * 0.707f  },
                                { backX * 0.707f + rightX * 0.707f,     0f, backZ * 0.707f + rightZ * 0.707f  },
                                { backX,                                  1f, backZ                                 },
                                { backX,                                 -1f, backZ                                 },
                            };

                            float bestTx = oldPos.x, bestTy = oldPos.y, bestTz = oldPos.z;
                            float bestDist = -1f;

                            for (float[] dir : dirs) {
                                float ddx    = dir[0], yShift = dir[1], ddz = dir[2];
                                float hlen   = (float) Math.sqrt(ddx * ddx + ddz * ddz);
                                if (hlen > 0f) { ddx /= hlen; ddz /= hlen; }

                                float candX = oldPos.x, candZ = oldPos.z;
                                int   checkFy = (int) Math.floor(oldPos.y + yShift);
                                for (float step = 0.5f; step <= bd; step += 0.5f) {
                                    float cx = oldPos.x + ddx * step;
                                    float cz = oldPos.z + ddz * step;
                                    int bx2 = (int) Math.floor(cx);
                                    int bz2 = (int) Math.floor(cz);
                                    boolean blocked = world.getBlock(bx2, checkFy,     bz2).isSolid()
                                                   || world.getBlock(bx2, checkFy + 1, bz2).isSolid();
                                    if (blocked) break;
                                    candX = cx; candZ = cz;
                                }

                                float dist2 = (candX - oldPos.x) * (candX - oldPos.x)
                                            + (candZ - oldPos.z) * (candZ - oldPos.z);
                                if (dist2 > bestDist) {
                                    bestDist = dist2;
                                    // Snap Y to ground at this candidate XZ
                                    int bxd = (int) Math.floor(candX);
                                    int bzd = (int) Math.floor(candZ);
                                    float snapY = oldPos.y;
                                    for (int by2 = (int) oldPos.y + 4; by2 >= 1; by2--) {
                                        if (world.getBlock(bxd, by2, bzd).isSolid()
                                                && !world.getBlock(bxd, by2 + 1, bzd).isSolid()) {
                                            snapY = by2 + 1f; break;
                                        }
                                    }
                                    bestTx = candX; bestTy = snapY; bestTz = candZ;
                                }
                            }

                            player.position.set(bestTx, bestTy, bestTz);
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
                            if (player.abilities.isKamui) {
                                // Kamui = invincible — absorb damage with no effect
                                enemyManager.pendingPlayerDamage = 0f;
                            } else {
                                player.health -= enemyManager.pendingPlayerDamage;
                                enemyManager.pendingPlayerDamage = 0f;
                                if (player.health <= 0f) {
                                    System.out.println("You died! Respawning at spawn point.");
                                    player.position.set(SPAWN_X, spawnSurfaceY, SPAWN_Z);
                                    player.setVelocityY(0f);
                                    player.health = player.maxHealth;
                                    // Reset Kamui / lightning state on death
                                    player.abilities.isKamui       = false;
                                    player.abilities.kamuiAutoExited = false;
                                    player.abilities.absorptionCharge = 0f;
                                    AudioManager.play("kamui_exit");
                                    AudioManager.stopContinuous("kamui_duration");
                                    AudioManager.stopContinuous("kamui_distortion");
                                }
                            }
                        }

                        // ── KAMUI MANA DRAIN (passive while active) ───────────────
                        if (player.abilities.isKamui) {
                            player.mana = Math.max(0f,
                                    player.mana - GameConfig.manaKamuiDrain * deltaTime);
                            // Force-exit Kamui when mana is fully exhausted — no cooldown
                            if (player.mana <= 0f) {
                                player.abilities.isKamui          = false;
                                player.abilities.kamuiAutoExited  = false;
                                player.abilities.absorptionCharge = 0f;
                                AudioManager.play("kamui_exit");
                                AudioManager.stopContinuous("kamui_duration");
                                AudioManager.stopContinuous("kamui_distortion");
                            }
                        }

                        // ── KAMUI ABSORPTION (LMB held while in Kamui) ────────────
                        if (player.abilities.isKamui) {
                            boolean lmbHeld = glfwGetMouseButton(window, GLFW_MOUSE_BUTTON_LEFT) == GLFW_PRESS;
                            if (lmbHeld) {
                                // Find best target: enemy in crosshair takes priority, else block.
                                // No range limit — Kamui can reach anything the player looks at.
                                Vector3f eyePos = camera.position;
                                Enemy absorbEnemy = enemyManager.findMostAligned(
                                        world, eyePos, camera.getLookDirection(), 2000f);
                                boolean hasTarget = absorbEnemy != null
                                        || (lastTarget != null && lastTarget.hit
                                            && world.getBlock(lastTarget.hitX, lastTarget.hitY, lastTarget.hitZ) != com.leaf.game.world.Block.AIR);

                                if (hasTarget) {
                                    player.abilities.isAbsorbing     = true;
                                    AudioManager.playContinuous("kamui_distortion");
                                    player.abilities.absorptionCharge = Math.min(1f,
                                            player.abilities.absorptionCharge
                                            + deltaTime / GameConfig.kamuiAbsorptionTime);

                                    // Project target world pos to screen for the vortex visual
                                    Vector3f targetPos = (absorbEnemy != null)
                                            ? absorbEnemy.getCentre()
                                            : new Vector3f(lastTarget.hitX + 0.5f,
                                                           lastTarget.hitY + 0.5f,
                                                           lastTarget.hitZ + 0.5f);
                                    Matrix4f vp = new Matrix4f(camera.getProjectionMatrix()).mul(camera.getViewMatrix());
                                    org.joml.Vector4f cp = new org.joml.Vector4f(
                                            targetPos.x, targetPos.y, targetPos.z, 1f).mul(vp);
                                    if (cp.w > 0f) {
                                        float ndcX = cp.x / cp.w;
                                        float ndcY = cp.y / cp.w;
                                        player.abilities.absorptionScrX = (ndcX + 1f) * 0.5f * ww[0];
                                        player.abilities.absorptionScrY = (1f - ndcY) * 0.5f * wh[0];
                                    }

                                    // When fully charged: absorb (delete) the target
                                    if (player.abilities.absorptionCharge >= 1f) {
                                        player.mana = Math.max(0f,
                                                player.mana - GameConfig.manaKamuiAbsorption);
                                        if (absorbEnemy != null) {
                                            absorbEnemy.applyDamage(999999f); // instant kill
                                        } else if (lastTarget != null && lastTarget.hit) {
                                            // Absorb a sphere of blocks around the target
                                            int bx0 = lastTarget.hitX, by0 = lastTarget.hitY, bz0 = lastTarget.hitZ;
                                            int absorptionR = 3;
                                            java.util.Set<com.leaf.game.world.Chunk> dirty = new java.util.HashSet<>();
                                            for (int ax = -absorptionR; ax <= absorptionR; ax++) {
                                                for (int ay = -absorptionR; ay <= absorptionR; ay++) {
                                                    for (int az = -absorptionR; az <= absorptionR; az++) {
                                                        if (ax*ax + ay*ay + az*az > absorptionR*absorptionR) continue;
                                                        int nx = bx0+ax, ny = by0+ay, nz = bz0+az;
                                                        if (world.getBlock(nx, ny, nz) != com.leaf.game.world.Block.AIR) {
                                                            world.setBlock(nx, ny, nz, com.leaf.game.world.Block.AIR);
                                                            com.leaf.game.world.Chunk c = world.getChunk(
                                                                    Math.floorDiv(nx, Chunk.SIZE), 0, Math.floorDiv(nz, Chunk.SIZE));
                                                            if (c != null) dirty.add(c);
                                                        }
                                                    }
                                                }
                                            }
                                            for (com.leaf.game.world.Chunk c : dirty) world.buildChunkMeshes(c);
                                        }
                                        player.abilities.absorptionCharge = 0f;
                                        player.abilities.isAbsorbing      = false;
                                        AudioManager.stopContinuous("kamui_distortion");
                                        AudioManager.play("distortion_snap", 2.0f); // boosted ~+6 dB
                                    }
                                } else {
                                    // No valid target — drain charge back
                                    AudioManager.stopContinuous("kamui_distortion");
                                    player.abilities.absorptionCharge = Math.max(0f,
                                            player.abilities.absorptionCharge - deltaTime * 2f);
                                }
                            } else {
                                // LMB released — bleed charge away
                                player.abilities.isAbsorbing     = false;
                                AudioManager.stopContinuous("kamui_distortion");
                                player.abilities.absorptionCharge = Math.max(0f,
                                        player.abilities.absorptionCharge - deltaTime * 3f);
                            }
                        }

                        // Tick paper dummies; explode when timer expires
                        for (int di = substituteDummies.size() - 1; di >= 0; di--) {
                            float[] dm = substituteDummies.get(di);
                            dm[3] -= deltaTime;
                            if (dm[3] <= 0f) {
                                AudioManager.play("paper_explode");
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
                        if (jHeld && !lastJ && !player.debugMode && todoSwapCooldown <= 0f
                                && player.mana >= GameConfig.manaTodoSwap) {
                            Vector3f eyePos = new Vector3f(player.position.x,
                                    player.position.y + 1.6f, player.position.z);
                            Enemy swapTarget = enemyManager.findClosestVisible(
                                    world, eyePos, GameConfig.todoRange);
                            if (swapTarget != null) {
                                player.mana -= GameConfig.manaTodoSwap;
                                Vector3f oldPlayerPos = new Vector3f(player.position);
                                Vector3f oldEnemyPos  = new Vector3f(swapTarget.position);
                                player.position.set(oldEnemyPos);
                                swapTarget.position.set(oldPlayerPos);
                                player.abilities.blinkFlashTimer = GameConfig.blinkFlashDecay;
                                player.abilities.blinkOrigin     = oldPlayerPos;
                                player.abilities.blinkDest       = new Vector3f(oldEnemyPos);
                                swapTarget.hitFlashTimer = 0.35f;
                                todoSwapCooldown = GameConfig.todoCooldown;
                                AudioManager.play("clap");
                            }
                        }
                        lastJ = jHeld;

                        // ── QUAGMIRE (M key) ──────────────────────────────────
                        if (quagmireCooldown > 0f) quagmireCooldown -= deltaTime;
                        boolean mHeld = glfwGetKey(window, GLFW_KEY_M) == GLFW_PRESS;
                        if (mHeld && !lastM && !player.debugMode && quagmireCooldown <= 0f
                                && player.mana >= GameConfig.manaQuagmire) {
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
                                    player.mana -= GameConfig.manaQuagmire;
                                    quagmireCooldown = GameConfig.quagmireCooldown;
                                    AudioManager.play("quagmire");
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
                                        // Stamp a large irregular mud pool (~3-block radius)
                                        // around the enemy's feet.
                                        int ex = (int) Math.floor(e.position.x);
                                        int ez = (int) Math.floor(e.position.z);
                                        int baseY2 = (int) Math.floor(e.position.y) + 2;
                                        int poolR = GameConfig.quagmirePoolRadius;
                                        for (int dbx = -poolR - 1; dbx <= poolR + 1; dbx++) {
                                            for (int dbz = -poolR - 1; dbz <= poolR + 1; dbz++) {
                                                // Irregular edge: sine/cosine noise on column coords
                                                float noiseR = (float)(
                                                    Math.sin(dbx * 2.3 + ez * 0.7) *
                                                    Math.cos(dbz * 1.9 + ex * 0.5)) * 0.9f;
                                                float effR = poolR + noiseR;
                                                if (dbx * dbx + dbz * dbz > effR * effR) continue;
                                                int bx2 = ex + dbx, bz2 = ez + dbz;
                                                for (int scanY = baseY2; scanY >= 0; scanY--) {
                                                    if (world.getBlock(bx2, scanY, bz2).isSolid()) {
                                                        world.setBlock(bx2, scanY, bz2, Block.MUD);
                                                        world.rebuildChunkAt(bx2, scanY, bz2);
                                                        break;
                                                    }
                                                }
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
                                && !player.debugMode && stoneCanonCooldownTimer <= 0f
                                && player.mana >= GameConfig.manaStoneCanonBase) {
                            // Start charging — lock position
                            isChargingStoneCanon     = true;
                            AudioManager.playContinuous("charging");
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
                            // Continuous mana drain; cancel if mana runs out
                            player.mana = Math.max(0f,
                                    player.mana - GameConfig.manaStoneCanonBase * deltaTime);
                            if (player.mana <= 0f) {
                                isChargingStoneCanon = false;
                                AudioManager.stopContinuous("charging");
                                stoneCanonCooldownTimer = GameConfig.stoneCanonCooldown * 0.5f;
                            }

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
                                    // One-time fire cost (charge-scaled; deduct whatever's left)
                                    player.mana = Math.max(0f, player.mana
                                            - GameConfig.manaStoneCanonMax * chargeF);
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
                                    // Raise spawn 1 block above the surface so the ball clears the ground
                                    Vector3f firePos = new Vector3f(fpx, (float) groundSpawnY + 1.0f, fpz);

                                    // ── Aim from ground point toward player's look target ──
                                    Vector3f eyePos2 = new Vector3f(player.position.x,
                                            player.position.y + 1.6f, player.position.z);
                                    Vector3f aimTarget = new Vector3f(eyePos2)
                                            .add(new Vector3f(lookDir).mul(60f));
                                    Vector3f fireDir = new Vector3f(aimTarget).sub(firePos);
                                    float fireDirLen = fireDir.length();
                                    if (fireDirLen > 0.001f) fireDir.div(fireDirLen);
                                    // Guarantee a minimum loft so the ball never immediately hits the ground
                                    if (fireDir.y < 0.12f) {
                                        fireDir.y = 0.12f;
                                        float hLen = (float)Math.sqrt(fireDir.x*fireDir.x + fireDir.z*fireDir.z);
                                        if (hLen > 0f) { fireDir.x /= hLen; fireDir.z /= hLen; }
                                        fireDir.normalize();
                                    }
                                    Vector3f fireVel = new Vector3f(fireDir).mul(speed);
                                    stoneShotList.add(new ActiveStoneShot(firePos, fireVel, scale, chargeF));
                                    AudioManager.stopContinuous("charging");
                                    AudioManager.play("stone_canon");
                                    stoneCanonCooldownTimer = GameConfig.stoneCanonCooldown;
                                } else {
                                    // No blocks consumed — fizzle, just stop the charge sound
                                    AudioManager.stopContinuous("charging");
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


                        // ── MANA DEPLETED (edge: just hit zero) ───────────────
                        if (player.mana <= 0f && lastMana > 0f) {
                            AudioManager.play("mana_empty");
                        }
                        lastMana = player.mana;

                        // ── LANDING / FALL IMPACT SOUNDS ──────────────────────
                        // Detected from pre-update ground state vs post-update.
                        // savedHighestY captured before player.update() so it
                        // holds the peak Y — Player resets it to position.y on landing.
                        boolean justLanded = wasOnGroundAudio == false && player.isOnGround();
                        if (justLanded) {
                            float fallDist = savedHighestY - player.position.y;
                            // Check block directly below feet for surface type
                            Block belowBlock = world.getBlock(
                                    (int)Math.floor(player.position.x),
                                    (int)Math.floor(player.position.y) - 1,
                                    (int)Math.floor(player.position.z));
                            boolean isSandy = (belowBlock == Block.SAND
                                           || belowBlock == Block.RED_SAND);
                            if (fallDist > 4.0f) {
                                // Damage-range fall
                                AudioManager.play(isSandy ? "fall_sandy" : "fall_hit", 0.85f);
                            } else if (fallDist > 1.5f) {
                                // Short drop / jump landing — light thud
                                AudioManager.play(isSandy ? "fall_sandy" : "fall_light", 0.55f);
                            }
                        }

                        // ── WATER SOUNDS ──────────────────────────────────────
                        // Feet-level water check (same logic Player uses internally)
                        boolean feetInWater = world.getBlock(
                                (int)Math.floor(player.position.x),
                                (int)Math.floor(player.position.y + 0.1f),
                                (int)Math.floor(player.position.z)).isLiquid();
                        boolean camSubmerged = player.isCameraSubmerged();

                        // Entry: big splash when falling fast, gentle enter otherwise
                        if (feetInWater && !lastFeetInWater) {
                            float entryVy = player.getVelocityY();
                            if (entryVy < -12f) {
                                AudioManager.play("water/water_splash", 1.0f);
                            } else {
                                AudioManager.play("water/water_enter", 0.7f);
                            }
                        }
                        // Exit: pick randomly from 3 exit sounds — avoids the
                        // repetitive "same splash every step" problem when walking on water.
                        if (!feetInWater && lastFeetInWater) {
                            int pick = (int)(Math.random() * 3);
                            String exitSnd = pick == 0 ? "water/water_leave"
                                           : pick == 1 ? "water/water_exit2"
                                                       : "water/water_exit3";
                            AudioManager.play(exitSnd, 0.50f);
                        }
                        // Underwater ambience: loop while camera is submerged
                        if (camSubmerged && !lastCamSubmerged) {
                            AudioManager.playContinuous("water/underwater_ambience", 0.55f);
                        } else if (!camSubmerged && lastCamSubmerged) {
                            AudioManager.stopContinuous("water/underwater_ambience");
                        }
                        lastFeetInWater  = feetInWater;
                        lastCamSubmerged = camSubmerged;

                        // ── WIND / FLIGHT SOUNDS ───────────────────────────────
                        // Three beds that never actually stop — windFade smoothly
                        // brings them to 0 so there are no abrupt pops on landing.
                        //
                        // KEY FIXES vs previous version:
                        //  • inAir uses debugMode as override → touching blocks while
                        //    skimming no longer briefly kills the wind.
                        //  • When camSubmerged, windFade snaps to 0 immediately (water
                        //    has its own sound design).
                        //  • totalAirSpeed includes vertical so gentle rising/descending
                        //    registers — not just horizontal glides.
                        //  • wind_soft is ducked when wind_blow is strong.
                        //  • Tilt pan: rolls the stereo image left/right with camera roll.
                        float vy      = player.getVelocityY();
                        // debugMode = flight mode: ALWAYS treat as in-air so brief block
                        // grazes during skimming don't cut the wind.
                        // Underground (cave env) also suppresses wind — windFade fades
                        // out naturally when lastEnv switches to ENV_CAVE.
                        boolean inAir = (player.debugMode || !player.isOnGround())
                                        && !camSubmerged
                                        && lastEnv != AudioManager.ENV_CAVE;

                        // Snap wind off immediately on water entry — no lingering wind underwater.
                        if (camSubmerged) windFade = 0f;

                        float horizSpeed = (float) Math.sqrt(
                                listenerVel.x * listenerVel.x + listenerVel.z * listenerVel.z);
                        // In flight mode use FlightController's velocity so terrain collisions
                        // (which zero the position delta) don't kill wind volume on uphill skim.
                        float totalAirSpeed = player.debugMode
                                ? player.flightController.getFlightSpeed()
                                : (float) Math.sqrt(horizSpeed * horizSpeed + vy * vy);

                        // ── TUNING KNOBS ───────────────────────────────────────
                        final float BLOW_START   = 3f;    // lower → wind starts earlier/gentler
                        final float BIG_START    = 13f;   // lower → deep roar kicks in sooner
                        final float BLOW_MAX_VOL = 0.70f;
                        final float BIG_MAX_VOL  = 0.85f;
                        final float SOFT_MAX_VOL = 0.20f;
                        final float FADE_IN_SEC  = 0.12f; // faster fade-in for responsive feel
                        final float FADE_OUT_SEC = 0.55f; // longer fade-out — wind lingers a beat

                        if (inAir) {
                            windFade = Math.min(1f, windFade + deltaTime / FADE_IN_SEC);
                        } else {
                            windFade = Math.max(0f, windFade - deltaTime / FADE_OUT_SEC);
                        }

                        // wind_blow: main travel layer
                        float blowVol = Math.min(BLOW_MAX_VOL,
                                Math.max(0f, (totalAirSpeed - BLOW_START) / (BIG_START - BLOW_START))
                                * BLOW_MAX_VOL);

                        // wind_big: deep roar at high speed
                        float bigVol = Math.min(BIG_MAX_VOL,
                                Math.max(0f, (totalAirSpeed - BIG_START) / 10f) * BIG_MAX_VOL);

                        // wind_soft: gentle presence, ducked when blow is already loud
                        float softFade = Math.max(0f, 1f - (blowVol / BLOW_MAX_VOL) * 2f);
                        float softVol  = SOFT_MAX_VOL * softFade
                                * Math.min(1f, Math.max(0f, (totalAirSpeed - 1.5f) / 4f));

                        AudioManager.setContinuousVolume("wind/wind_soft", softVol * windFade);
                        AudioManager.setContinuousVolume("wind/wind_blow", blowVol * windFade);
                        AudioManager.setContinuousVolume("wind/wind_big",  bigVol  * windFade);

                        // ── TILT PAN ───────────────────────────────────────────
                        // When flying and rolling, shift wind_blow left/right to match
                        // the direction you're banking into.
                        // pan = sin(roll): +1 = hard right, -1 = hard left.
                        // Only active during flight mode to avoid weird pan on normal jumps.
                        if (player.debugMode) {
                            float roll    = player.getCameraRoll();
                            float tiltPan = (float) Math.sin(roll) * 0.75f;
                            AudioManager.setLoopPan("wind/wind_blow", tiltPan);
                            AudioManager.setLoopPan("wind/wind_big",  tiltPan * 0.5f);
                        } else {
                            // Return to centre when not in flight mode
                            AudioManager.setLoopPan("wind/wind_blow", 0f);
                            AudioManager.setLoopPan("wind/wind_big",  0f);
                        }

                        // wind_harsh stinger: fires only when windFade is mostly in
                        windStingerCooldown -= deltaTime;
                        if (windStingerCooldown <= 0f && inAir
                                && totalAirSpeed > BLOW_START && windFade > 0.5f) {
                            float sv = 0.25f + 0.30f * Math.min(1f, totalAirSpeed / (BIG_START + 4f));
                            AudioManager.playVaried("wind/wind_harsh", Math.min(0.70f, sv), 0.10f);
                            windStingerCooldown = 1.0f + (float)Math.random() * 2.5f
                                    - Math.min(0.6f, totalAirSpeed / 40f);
                        }

                        // wind_cemetery: cave ambience stinger
                        if (lastEnv == AudioManager.ENV_CAVE) {
                            caveWindCooldown -= deltaTime;
                            if (caveWindCooldown <= 0f) {
                                AudioManager.play("wind/wind_cemetery", 0.35f);
                                caveWindCooldown = 10f + (float)Math.random() * 14f;
                            }
                        }

                        // ── MUFFLE (low-pass on the whole mix) ────────────────
                        // Priority 1 — submerged: very heavy low-pass gives the
                        //   沉闷 underwater feel.  Combined with ENV_UNDERWATER
                        //   reverb, it sells the "thick water pressing on your ears"
                        //   sensation.  Value 0..1 where 1 = fully muffled.
                        // Priority 2 — fast air movement: lighter, speed-scaled cut.
                        //
                        // UNDERWATER_MUFFLE – raise toward 1 for heavier/deafer feel
                        final float UNDERWATER_MUFFLE = 0.82f;
                        final float MUFFLE_MAX        = 0.55f;

                        float targetMuffle;
                        if (camSubmerged) {
                            // Hard cut on all high frequencies — deep, thick, pressured
                            targetMuffle = UNDERWATER_MUFFLE;
                        } else if (windFade > 0f) {
                            targetMuffle = Math.min(MUFFLE_MAX,
                                    Math.max(0f, (totalAirSpeed - BLOW_START) / 30f))
                                    * windFade;
                        } else {
                            targetMuffle = 0f;
                        }
                        if (Math.abs(targetMuffle - lastMuffle) > 0.04f
                                || (targetMuffle == 0f && lastMuffle != 0f)) {
                            AudioManager.setListenerMuffle(targetMuffle);
                            lastMuffle = targetMuffle;
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

                    // ── NON-EUCLIDEAN: F5 / F6 BUILD + TELEPORT ──────────────
                    boolean f5Now = glfwGetKey(window, GLFW_KEY_F5) == GLFW_PRESS;
                    if (f5Now && !lastF5) {
                        if (!botiBuilt) buildBOTI();
                        // Teleport to just outside Structure A entrance
                        player.position.x = BOTI_X0 + BOTI_W / 2.0f;
                        player.position.y = BOTI_A_Y0 + 1.5f;
                        player.position.z = BOTI_Z0 - 3.0f;  // 3 blocks in front of entrance
                        player.setVelocityY(0f);
                        camera.yaw   = (float) Math.toRadians(90.0f); // face +Z (into the arch)
                        camera.pitch = 0f;
                        botiInside = false;
                    }
                    lastF5 = f5Now;

                    boolean f6Now = glfwGetKey(window, GLFW_KEY_F6) == GLFW_PRESS;
                    if (f6Now && !lastF6) {
                        if (!rrBuilt) buildRotatingRooms();
                        // Teleport to just inside Room1 (NW) entry arch, facing south (+Z)
                        // Entry arch is centred at x = RR_A_X0 + RR_ROOM_W/2 = 2456,
                        // outer north wall at z = RR_A_Z0 = 2400 → stand 2 blocks inside.
                        player.position.x = RR_A_X0 + RR_W / 2.0f;
                        player.position.y = RR_FLOOR_Y + 1.5f;
                        player.position.z = RR_A_Z0 + 2.0f;
                        player.setVelocityY(0f);
                        camera.yaw   = (float) Math.toRadians(270.0); // face +Z (south, into room)
                        camera.pitch = 0f;
                        rrRoom = 1;
                    }
                    lastF6 = f6Now;

                    // ── BOTI PORTAL CROSSING DETECTION ───────────────────────
                    if (botiBuilt) {
                        float px = player.position.x, py = player.position.y, pz = player.position.z;
                        boolean inXBand = px >= BOTI_X_LO && px <= BOTI_X_HI;
                        if (inXBand) {
                            if (!botiInside && py >= BOTI_A_Y0 && py < BOTI_A_Y0 + BOTI_H + 2
                                    && pz > BOTI_Z0 + 0.5f && pz < BOTI_Z0 + BOTI_A_D) {
                                // Crossed into A → warp up to B entrance
                                player.position.y += BOTI_DY;
                                player.setVelocityY(0f);
                                botiInside = true;
                            } else if (botiInside && py >= BOTI_B_Y0 && py < BOTI_B_Y0 + BOTI_H + 2) {
                                if (pz > BOTI_Z0 + BOTI_B_D - 1.5f) {
                                    // Near back of B → warp down to A exit
                                    player.position.y -= BOTI_DY;
                                    player.position.z  = BOTI_Z0 + BOTI_A_D + 0.5f;
                                    player.setVelocityY(0f);
                                    botiInside = false;
                                } else if (pz < BOTI_Z0 + 0.5f) {
                                    // Walked back out of B entrance → warp back down to A entrance
                                    player.position.y -= BOTI_DY;
                                    player.position.z  = BOTI_Z0 - 0.5f;
                                    player.setVelocityY(0f);
                                    botiInside = false;
                                }
                            }
                        }
                    }

                    // ── ROTATING ROOMS PORTAL CROSSING DETECTION ─────────────
                    if (rrBuilt && rrRoom > 0) {
                        updateRotatingRoomsPortal();
                    }

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

            // ── KAMUI DISTORTION FBO ──────────────────────────────────────────
            // When Kamui is active the 3-D scene is redirected into an off-screen
            // texture. After all 3-D rendering is done we apply a GLSL distortion
            // shader (swirl / vortex UV warp) to that texture and blit the result
            // to the default framebuffer. ImGui is then composited on top normally.
            boolean doKamuiDistort = player != null && !isPreloading
                    && player.abilities.isKamui && distortShader != null;
            if (doKamuiDistort) {
                // Recreate the FBO whenever the window is resized or on first use
                // CRITICAL FIX: Use physical framebuffer size (fw, fh) for FBO on Retina/High-DPI displays!
                if (fw[0] != kamuiFboW || fh[0] != kamuiFboH) {
                    if (kamuiFbo != 0) {
                        org.lwjgl.opengl.GL30.glDeleteFramebuffers(kamuiFbo);
                        org.lwjgl.opengl.GL11.glDeleteTextures(kamuiFboTex);
                        org.lwjgl.opengl.GL30.glDeleteRenderbuffers(kamuiFboRbo);
                    }
                    kamuiFbo = org.lwjgl.opengl.GL30.glGenFramebuffers();
                    kamuiFboTex = org.lwjgl.opengl.GL11.glGenTextures();
                    kamuiFboRbo = org.lwjgl.opengl.GL30.glGenRenderbuffers();

                    org.lwjgl.opengl.GL30.glBindFramebuffer(
                            org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, kamuiFbo);

                    // Color texture attachment
                    org.lwjgl.opengl.GL11.glBindTexture(
                            org.lwjgl.opengl.GL11.GL_TEXTURE_2D, kamuiFboTex);
                    org.lwjgl.opengl.GL11.glTexImage2D(
                            org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0,
                            org.lwjgl.opengl.GL11.GL_RGB, fw[0], fh[0], 0,
                            org.lwjgl.opengl.GL11.GL_RGB,
                            org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE,
                            (java.nio.ByteBuffer) null);
                    org.lwjgl.opengl.GL11.glTexParameteri(
                            org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
                            org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER,
                            org.lwjgl.opengl.GL11.GL_LINEAR);
                    org.lwjgl.opengl.GL11.glTexParameteri(
                            org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
                            org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER,
                            org.lwjgl.opengl.GL11.GL_LINEAR);
                    org.lwjgl.opengl.GL30.glFramebufferTexture2D(
                            org.lwjgl.opengl.GL30.GL_FRAMEBUFFER,
                            org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0,
                            org.lwjgl.opengl.GL11.GL_TEXTURE_2D, kamuiFboTex, 0);

                    // Depth renderbuffer attachment
                    org.lwjgl.opengl.GL30.glBindRenderbuffer(
                            org.lwjgl.opengl.GL30.GL_RENDERBUFFER, kamuiFboRbo);
                    org.lwjgl.opengl.GL30.glRenderbufferStorage(
                            org.lwjgl.opengl.GL30.GL_RENDERBUFFER,
                            org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT24, fw[0], fh[0]);
                    org.lwjgl.opengl.GL30.glFramebufferRenderbuffer(
                            org.lwjgl.opengl.GL30.GL_FRAMEBUFFER,
                            org.lwjgl.opengl.GL30.GL_DEPTH_ATTACHMENT,
                            org.lwjgl.opengl.GL30.GL_RENDERBUFFER, kamuiFboRbo);

                    kamuiFboW = fw[0];
                    kamuiFboH = fh[0];
                    org.lwjgl.opengl.GL30.glBindFramebuffer(
                            org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, 0);
                }
                // Redirect 3-D render into the off-screen FBO
                org.lwjgl.opengl.GL30.glBindFramebuffer(
                        org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, kamuiFbo);
            }

            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

            if (networkInitialized) {
                shader.bind();
                shader.setUniform("sunDirection",
                        new Vector3f(GameConfig.sunDirX, GameConfig.sunDirY, GameConfig.sunDirZ));
                shader.setUniform("sunStrength", GameConfig.sunStrength);
                shader.setUniform("ambientStrength", GameConfig.ambientStrength);
                shader.setUniform("desaturate", ScreenEffectManager.INSTANCE.getDesaturate());

                boolean isCameraUnderwater = world.getBlock(
                        (int) Math.floor(camera.position.x),
                        (int) Math.floor(camera.position.y),
                        (int) Math.floor(camera.position.z)).isLiquid();
                shader.setUniform("isUnderwater", isCameraUnderwater ? 1 : 0);
                shader.setUniform("cameraY", camera.position.y);

                // ── SNOW BIOME ATMOSPHERE ─────────────────────────────────────
                // Fades in as the player climbs into snow-mountain altitude.
                // Suppressed underground (cave reverb env) and underwater.
                float snowAtmStr = 0f;
                if (!isCameraUnderwater && lastEnv != AudioManager.ENV_CAVE) {
                    float altStart = GameConfig.snowAltitude - 60f;
                    float altEnd   = GameConfig.snowAltitude;
                    float snowT    = (camera.position.y - altStart) / (altEnd - altStart);
                    snowAtmStr = Math.max(0f, Math.min(snowT, 1f)) * 0.18f;
                }
                shader.setUniform("snowAtmosphereStrength", snowAtmStr);

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
                // When Kamui FBO post-process is active the distort shader is the
                // sole source of colour effects — zero the 3-D overlay so the
                // scene captured into the FBO is clean and readable.
                float abilityOverlayStr = doKamuiDistort ? 0f : player.abilities.getOverlayStrength();
                float attackOverlayStr  = player.attacks.getOverlayStrength();
                Vector3f compositeOverlayColor;
                float compositeOverlayStr;
                if (attackOverlayStr >= abilityOverlayStr) {
                    compositeOverlayColor = player.attacks.getOverlayColor();
                    compositeOverlayStr = attackOverlayStr;
                } else {
                    compositeOverlayColor = doKamuiDistort ? new Vector3f(0f) : player.abilities.getOverlayColor();
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
                if (substitutePrimed) {
                    float timeSecs = (float) glfwGetTime();
                    float pulseStr = 0.10f + 0.05f * (float) Math.sin(timeSecs * 8.0f);
                    if (pulseStr > compositeOverlayStr) {
                        compositeOverlayStr   = pulseStr;
                        compositeOverlayColor = new Vector3f(0.95f, 0.97f, 1.0f);
                    }
                }
                // ── STORM OVERLAY (lightning charging / active) ───────────────
                float stormI = player.lightning.stormIntensity;
                if (stormI > 0f) {
                    // Deep purple-black storm that really darkens the scene
                    float stormStr = stormI * 0.72f;
                    if (stormStr > compositeOverlayStr) {
                        compositeOverlayStr   = stormStr;
                        compositeOverlayColor = new Vector3f(0.02f, 0.02f, 0.12f);
                    }
                }

                shader.setUniform("overlayVignetteStrength", compositeOverlayStr);
                shader.setUniform("overlayVignetteColor", compositeOverlayColor);
                // Default alpha multiplier (1.0 = no change). Ghost rendering overrides this.
                shader.setUniform("alphaMultiplier", 1.0f);
                // Default: no texture sampling. Set to 1 + bind texture for ModelMesh rendering.
                shader.setUniform("useTexture", 0);
                // Default: normal rendering (not portal FBO passthrough).
                shader.setUniform("portalMode", 0);

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
                // Also render one slab above surface so Structure B (Y=900, CY=1) is
                // visible when the player is inside it.  Empty CY>0 chunks have no
                // mesh so the extra loop iteration is essentially free.
                int cyTop = Math.max(0, playerCY + 1);

                // Frustum culling uses the CLEAN view (no roll, no shake) to avoid
                // popping at the frustum edges during roll animations.
                Matrix4f cleanMvp = new Matrix4f(projection).mul(baseView);
                shader.setUniform("mvp", cleanMvp);

                // ── DIRTY MESH REBUILD ─────────────────────────────────────────
                if (!isPreloading) {
                    int maxMeshCompilesPerFrame = 6;
                    List<int[]> dirtyList = new ArrayList<>();
                    for (int dx = -R; dx <= R; dx++) {
                        for (int dz = -R; dz <= R; dz++) {
                            for (int cy = cyTop; cy >= cyMin; cy--) {
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
                // ── PORTAL FBO PRE-PASS ───────────────────────────────────────
                // Render each active portal's destination view into its FBO so
                // the portal quad shows the correct scene in PASS 1.
                if (!isPreloading && portalFbo != null) {

                    renderAllPortalFbos(shader, projection, view, camera);
                }

// ── PASS 1: OPAQUE ────────────────────────────────────────────
                // Frustum culling uses the ACTUAL view matrix (including roll/shake)
                Matrix4f renderMvp = new Matrix4f(projection).mul(view);
                float[] frustumPlanes = extractFrustumPlanes(renderMvp);

                // ── DIRTY MESH REBUILD ─────────────────────────────────────────
                if (!isPreloading) {
                    int maxMeshCompilesPerFrame = 6;
                    List<int[]> dirtyList = new ArrayList<>();
                    for (int dx = -R; dx <= R; dx++) {
                        for (int dz = -R; dz <= R; dz++) {
                            for (int cy = cyTop; cy >= cyMin; cy--) {
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

                // ── PORTAL FBO PRE-PASS ───────────────────────────────────────
                if (!isPreloading && portalFbo != null) {
                    renderAllPortalFbos(shader, projection, view, camera);
                    // renderAllPortalFbos always restores to FBO 0; if Kamui is
                    // active we need to re-bind its off-screen FBO so the main
                    // 3D passes render into it (not straight to the screen).
                    if (doKamuiDistort) {
                        org.lwjgl.opengl.GL30.glBindFramebuffer(
                                org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, kamuiFbo);
                    }
                }

                // ── PASS 1: OPAQUE ────────────────────────────────────────────
                shader.setUniform("mvp", renderMvp);
                for (int dx = -R; dx <= R; dx++) {
                    for (int dz = -R; dz <= R; dz++) {
                        for (int cy = cyTop; cy >= cyMin; cy--) {
                            Chunk chunk = world.getChunk(playerCX + dx, cy, playerCZ + dz);
                            if (chunk != null && chunk.opaqueMesh != null
                                    && isAabbInFrustum(frustumPlanes, chunk)) {
                                chunk.opaqueMesh.render();
                            }
                        }
                    }
                }

                // ── PASS 1b: PORTAL QUADS (FBO texture displayed on quad) ────────
                if (!isPreloading && portalFbo != null) {
                    renderAllPortalQuads(shader, renderMvp);
                }

                // ── PASS 2: TRANSPARENT ───────────────────────────────────────
                glEnable(GL_BLEND);
                glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
                shader.setUniform("mvp", renderMvp);
                for (int dx = -R; dx <= R; dx++) {
                    for (int dz = -R; dz <= R; dz++) {
                        for (int cy = cyTop; cy >= cyMin; cy--) {
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
                        // Grabbed — enemy lifted/held: bright pulsing orange
                        if (enemy.isGrabbed) {
                            float pulse = 0.7f + 0.3f * (float) Math.abs(Math.sin(glfwGetTime() * 18.0));
                            typeColor      = new Vector3f(1.0f, 0.45f * pulse, 0.0f);
                            typeOverlayStr = 0.75f;
                        // Thrown — in flight after grab: hot red-orange, full brightness
                        } else if (enemy.isThrown) {
                            typeColor      = new Vector3f(1.0f, 0.25f, 0.0f);
                            typeOverlayStr = 0.80f;
                        // Mud-trapped overrides type colour with a brown tint
                        } else if (enemy.mudTrapTimer > 0f) {
                            typeColor      = new Vector3f(0.45f, 0.28f, 0.05f);
                            typeOverlayStr = 0.45f;
                        } else {
                            switch (enemy.type) {
                                // Stone tank — blue-grey
                                case GOLEM   -> { typeColor = new Vector3f(0.55f, 0.60f, 0.72f); typeOverlayStr = 0.26f; }
                                // Skeleton archer — pale bone white
                                case THROWER -> { typeColor = new Vector3f(0.92f, 0.90f, 0.80f); typeOverlayStr = 0.30f; }
                                // Zombie — sickly green
                                case ZOMBIE  -> { typeColor = new Vector3f(0.22f, 0.62f, 0.18f); typeOverlayStr = 0.32f; }
                                default      -> { typeColor = new Vector3f(0.90f, 0.30f, 0.05f); typeOverlayStr = 0.12f; }
                            }
                        }

                        shader.setUniform("alphaMultiplier", alpha);
                        if (flashF > 0f) {
                            shader.setUniform("overlayVignetteStrength", flashF * 0.65f);
                            shader.setUniform("overlayVignetteColor", new Vector3f(1.0f, 0.25f, 0.15f));
                        } else {
                            shader.setUniform("overlayVignetteStrength", typeOverlayStr);
                            shader.setUniform("overlayVignetteColor", typeColor);
                        }

                        // Non-uniform scale so types look visually distinct
                        float[] sv = enemy.renderScaleVec();
                        Matrix4f enemyMat = new Matrix4f()
                                .translate(enemy.position.x, enemy.position.y, enemy.position.z)
                                .scale(sv[0], sv[1], sv[2]);
                        shader.setUniform("mvp", new Matrix4f(projection).mul(view).mul(enemyMat));
                        enemyModel.render();
                    }
                    // Reset overlay state
                    shader.setUniform("overlayVignetteStrength", 0f);
                    shader.setUniform("overlayVignetteColor", new Vector3f(0f, 0f, 0f));
                    shader.setUniform("alphaMultiplier", 1.0f);
                }

                // ── Render enemy projectiles (boulders / thrown rocks) ────────────
                if (!enemyManager.projectiles.isEmpty()) {
                    com.leaf.game.render.ModelMesh stoneModel =
                            com.leaf.game.render.AssetManager.get().getModel("player");
                    shader.setUniform("overlayVignetteStrength", 0.25f);
                    shader.setUniform("overlayVignetteColor", new Vector3f(0.55f, 0.55f, 0.60f));
                    for (EnemyManager.EnemyProjectile proj : enemyManager.projectiles) {
                        if (!proj.alive) continue;
                        float lifeF = proj.lifetime / GameConfig.projectileLifetime;
                        shader.setUniform("alphaMultiplier", Math.min(1f, lifeF * 4f));
                        float pScale = 0.22f;
                        Matrix4f projMat = new Matrix4f()
                                .translate(proj.pos.x, proj.pos.y, proj.pos.z)
                                .scale(pScale);
                        shader.setUniform("mvp", new Matrix4f(projection).mul(view).mul(projMat));
                        stoneModel.render();
                    }
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

                // 10. Knife view model — disabled (re-enable when model is ready)
                // if (player != null && !player.debugMode && !player.stand.isInStandPerspective()) {
                //     renderKnifeViewModel(shader, projection, view, camera);
                // }

                glDisable(GL_BLEND);
                shader.unbind();

                // ── KAMUI DISTORTION POST-PROCESS ─────────────────────────────
                // Blit the off-screen FBO texture back to the default framebuffer
                // through the swirl/vortex distortion shader.
                if (doKamuiDistort) {
                    org.lwjgl.opengl.GL30.glBindFramebuffer(
                            org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, 0);
                    glClear(GL_COLOR_BUFFER_BIT);

                    distortShader.bind();
                    distortShader.setUniform("screenTexture", 0);
                    distortShader.setUniform("time", (float) glfwGetTime());
                    distortShader.setUniform("kamuiCharge",
                            player.abilities.absorptionCharge);
                    distortShader.setUniform("absorptionPos",
                            player.abilities.absorptionScrX / Math.max(1, ww[0]),
                            player.abilities.absorptionScrY / Math.max(1, wh[0]));
                    distortShader.setUniform("aspectRatio",
                            (float) ww[0] / Math.max(1, wh[0]));

                    org.lwjgl.opengl.GL13.glActiveTexture(
                            org.lwjgl.opengl.GL13.GL_TEXTURE0);
                    org.lwjgl.opengl.GL11.glBindTexture(
                            org.lwjgl.opengl.GL11.GL_TEXTURE_2D, kamuiFboTex);

                    glDisable(GL_DEPTH_TEST);
                    org.lwjgl.opengl.GL30.glBindVertexArray(kamuiScreenQuad);
                    glDrawArrays(GL_TRIANGLES, 0, 6);
                    org.lwjgl.opengl.GL30.glBindVertexArray(0);
                    glEnable(GL_DEPTH_TEST);

                    distortShader.unbind();
                }
            }
            // ── IMGUI ─────────────────────────────────────────────────────────
            imguiGlfw.newFrame();
            ImGui.newFrame();

            if (!networkInitialized) {
                hud.renderConnectionMenu(ww[0], wh[0]);
            } else {
                if (isPreloading) {
                    hud.renderPreloadProgress(ww[0], wh[0]);
                } else {
                    hud.renderHUD(camera, ww[0], wh[0]);
                    hud.renderTargetCracks(camera, ww[0], wh[0]);
                    if (showDebug)       hud.renderDebugMenu();
                    if (showNoiseViewer) noiseVis.renderWindow(player);
                    if (showChat || !chatHistory.isEmpty()) hud.renderChatBox(wh[0]);
                    if (isPaused)        hud.renderPauseMenu(ww[0], wh[0]);
                    if (showHelp)        hud.renderHelpScreen((float)ww[0], (float)wh[0]);
                    // Screen flash overlay (snipe, explosion, melee hit, etc.)
                    ScreenEffectManager.INSTANCE.renderFlash(ww[0], wh[0]);
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

        // 3. Splash damage + knockback for nearby enemies
        enemyManager.processSmashKnockback(ix, iy, iz, r);

        // 4. Dynamic Screen Shake scaling based on the radius size
        AudioManager.play("fall_smash");
        ScreenEffectManager.INSTANCE.hitStop(2);
        ScreenEffectManager.INSTANCE.flashExplosion();
        float scaleFactor = (float) r / GameConfig.smashCraterRadius;
        activeShakeDuration  = GameConfig.smashShakeDuration * Math.min(2.5f, scaleFactor);
        activeShakeAmplitude = GameConfig.smashShakeAmplitude * Math.min(3.0f, scaleFactor);
        smashShakeTimer      = activeShakeDuration;

        // 5. Network sync
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
        // smashImpactY is the player's feet level (first AIR block) — sample one block down for the actual ground material.
        Block ejectBlock = world.getBlock(ix, iy - 1, iz);
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
    //  KNIFE VIEW MODEL
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Renders a simple knife placeholder in the bottom-right of the player's view.
     * Position is computed in world-space as a fixed offset from the camera,
     * which effectively makes it look attached to the hand.
     *
     * When a knife swing is in progress (knifeSwingPhase > 0), the blade
     * rotates around the look axis to simulate a slashing arc.
     */
    private void renderKnifeViewModel(Shader shader, Matrix4f projection, Matrix4f view,
                                      com.leaf.game.util.Camera camera) {
        // Only visible while actively swinging — not a permanent view model
        if (player.attacks.knifeSwingPhase < 0.05f) return;

        // ── Camera basis vectors ───────────────────────────────────────────────
        Vector3f look  = camera.getLookDirection();
        Vector3f right = camera.getRight();
        // True up = right × look (right-handed, pointing upward in camera space)
        Vector3f up = new Vector3f(right).cross(look).normalize();

        // ── Knife rest position (world-space) ─────────────────────────────────
        // Right side of view, below eye level, pushed forward for good size
        float restRight   = 0.28f;
        float restDown    = -0.24f;
        float restForward = 0.50f;

        Vector3f knifePos = new Vector3f(camera.position)
                .add(new Vector3f(right).mul(restRight))
                .add(new Vector3f(up).mul(restDown))
                .add(new Vector3f(look).mul(restForward));

        // ── Swing animation ────────────────────────────────────────────────────
        float swing = player.attacks.knifeSwingPhase;    // 0..1
        float swDir = player.attacks.knifeSwingDir;      // +1 or -1
        // Rotate the knife around the look axis (roll) based on swing phase
        float rollAngle = swDir * swing * (float) (Math.PI * 0.90);   // ±162° dramatic sweep
        // Forward lunge + downward dip during swing for a powerful slash feel
        knifePos.add(new Vector3f(look).mul(swing * 0.18f));
        knifePos.add(new Vector3f(up).mul(-swing * 0.12f));

        // ── Build model matrix ─────────────────────────────────────────────────
        float yaw   = camera.yaw;
        float pitch = camera.pitch;

        // Scale pulses slightly bigger during a swing (makes each hit feel weighty)
        float baseW = 0.15f, baseH = 0.58f, baseD = 0.08f;
        float swingScale = 1f + swing * 0.25f;

        Matrix4f knifeMat = new Matrix4f()
                .translate(knifePos.x, knifePos.y, knifePos.z)
                .rotateY(-yaw - (float)(Math.PI / 2))
                .rotateX(-pitch)
                .rotateZ(rollAngle)
                .scale(baseW * swingScale, baseH * swingScale, baseD * swingScale);

        // ── Render without writing to depth (stays on top of close geometry) ──
        glDepthMask(false);
        // Bright silver-steel sheen: stronger overlay so it reads clearly
        float shimmer = 0.18f + swing * 0.22f;   // glints brighter mid-swing
        shader.setUniform("overlayVignetteStrength", shimmer);
        shader.setUniform("overlayVignetteColor", new Vector3f(0.95f, 0.95f, 1.0f));
        shader.setUniform("alphaMultiplier", 0.95f);
        shader.setUniform("mvp", new Matrix4f(projection).mul(view).mul(knifeMat));
        getItemMesh(Block.STAR_IRON).render();   // metallic grey/silver look
        glDepthMask(true);
        shader.setUniform("overlayVignetteStrength", 0f);
        shader.setUniform("overlayVignetteColor", new Vector3f(0f, 0f, 0f));
        shader.setUniform("alphaMultiplier", 1.0f);
    }

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

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns true when there is at least one solid block directly above the
     * player's head within {@code maxCheck} blocks. Used as a cheap "indoors /
     * cave" test to decide whether to enable cave reverb.
     *
     * Tune: raise maxCheck to react to a higher ceiling; lower it so only
     * tight spaces (narrow caves) trigger reverb. Default 12 feels like a
     * low cave without being too eager on flat terrain with a single floating
     * block overhead.
     */
    private boolean isUnderRoof(int maxCheck) {
        int px = (int) Math.floor(player.position.x);
        int py = (int) Math.floor(player.position.y) + 2;   // start just above head
        int pz = (int) Math.floor(player.position.z);
        for (int y = py; y < py + maxCheck; y++) {
            if (world.getBlock(px, y, pz).isSolid()) return true;
        }
        return false;
    }

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
    void addBlockToHotbar(Block block) {
        if (block == Block.AIR) return;
        for (Block b : hotbar) { if (b == block) return; }
        for (int i = 0; i < hotbar.length; i++) {
            if (hotbar[i] == Block.AIR || inventory.getCount(hotbar[i]) <= 0) {
                hotbar[i] = block; return;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  PORTAL FBO INFRASTRUCTURE
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Allocate PORTAL_SLOTS FBO objects sized to the PHYSICAL framebuffer (fw×fh).
     * Using the physical size ensures gl_FragCoord / viewportSize is always 0..1
     * on both normal and Retina/HiDPI displays, preventing the "4 squares" tiling
     * artefact that occurs when fw[0] > PORTAL_FBO_W.
     * Call once after GL context + glfwGetFramebufferSize are ready.
     */
    private void createPortalFbos() {
        destroyPortalFbos();
        int w = Math.max(1, fw[0]);
        int h = Math.max(1, fh[0]);
        portalFbo      = new int[PORTAL_SLOTS];
        portalColorTex = new int[PORTAL_SLOTS];
        portalDepthRbo = new int[PORTAL_SLOTS];
        for (int i = 0; i < PORTAL_SLOTS; i++) {
            portalColorTex[i] = org.lwjgl.opengl.GL11.glGenTextures();
            org.lwjgl.opengl.GL11.glBindTexture(
                    org.lwjgl.opengl.GL11.GL_TEXTURE_2D, portalColorTex[i]);
            org.lwjgl.opengl.GL11.glTexImage2D(
                    org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0,
                    org.lwjgl.opengl.GL11.GL_RGB, w, h, 0,
                    org.lwjgl.opengl.GL11.GL_RGB,
                    org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE,
                    (java.nio.ByteBuffer) null);
            org.lwjgl.opengl.GL11.glTexParameteri(
                    org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
                    org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER,
                    org.lwjgl.opengl.GL11.GL_LINEAR);
            org.lwjgl.opengl.GL11.glTexParameteri(
                    org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
                    org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER,
                    org.lwjgl.opengl.GL11.GL_LINEAR);

            portalDepthRbo[i] = org.lwjgl.opengl.GL30.glGenRenderbuffers();
            org.lwjgl.opengl.GL30.glBindRenderbuffer(
                    org.lwjgl.opengl.GL30.GL_RENDERBUFFER, portalDepthRbo[i]);
            org.lwjgl.opengl.GL30.glRenderbufferStorage(
                    org.lwjgl.opengl.GL30.GL_RENDERBUFFER,
                    org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT24,
                    w, h);

            portalFbo[i] = org.lwjgl.opengl.GL30.glGenFramebuffers();
            org.lwjgl.opengl.GL30.glBindFramebuffer(
                    org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, portalFbo[i]);
            org.lwjgl.opengl.GL30.glFramebufferTexture2D(
                    org.lwjgl.opengl.GL30.GL_FRAMEBUFFER,
                    org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0,
                    org.lwjgl.opengl.GL11.GL_TEXTURE_2D, portalColorTex[i], 0);
            org.lwjgl.opengl.GL30.glFramebufferRenderbuffer(
                    org.lwjgl.opengl.GL30.GL_FRAMEBUFFER,
                    org.lwjgl.opengl.GL30.GL_DEPTH_ATTACHMENT,
                    org.lwjgl.opengl.GL30.GL_RENDERBUFFER, portalDepthRbo[i]);
            org.lwjgl.opengl.GL30.glBindFramebuffer(
                    org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, 0);
        }
    }

    private void destroyPortalFbos() {
        if (portalFbo == null) return;
        for (int i = 0; i < PORTAL_SLOTS; i++) {
            if (portalFbo[i]      != 0) org.lwjgl.opengl.GL30.glDeleteFramebuffers(portalFbo[i]);
            if (portalColorTex[i] != 0) org.lwjgl.opengl.GL11.glDeleteTextures(portalColorTex[i]);
            if (portalDepthRbo[i] != 0) org.lwjgl.opengl.GL30.glDeleteRenderbuffers(portalDepthRbo[i]);
        }
        portalFbo = portalColorTex = portalDepthRbo = null;
    }

    /**
     * Render all visible chunks into a portal FBO using virtual camera MVP.
     * {@code vcx}/{@code vcz} are the chunk coords of the virtual camera's position.
     * {@code cyHi}/{@code cyLo} control which vertical CY slabs to include.
     */
    private void renderChunksToFbo(Shader shader, Matrix4f mvp, int vcx, int vcz,
                                   int cyHi, int cyLo) {
        int R = GameConfig.renderDistance + 1;
        float[] planes = extractFrustumPlanes(mvp);
        shader.setUniform("mvp", mvp);
        for (int dx = -R; dx <= R; dx++) {
            for (int dz = -R; dz <= R; dz++) {
                for (int cy = cyHi; cy >= cyLo; cy--) {
                    Chunk chunk = world.getChunk(vcx + dx, cy, vcz + dz);
                    if (chunk != null && chunk.opaqueMesh != null
                            && isAabbInFrustum(planes, chunk)) {
                        chunk.opaqueMesh.render();
                    }
                }
            }
        }
    }
    /** Pre-render all active portals into their FBOs before the main scene draw. */
    private void renderAllPortalFbos(Shader shader, Matrix4f projection, Matrix4f view, Camera camera) {
        glDisable(GL_STENCIL_TEST);

        // ── BOTI ENTRY PORTAL (slot 0) ──────
        if (botiBuilt && !botiInside && botiEntryMesh != null) {
            Matrix4f vMvp = new Matrix4f(projection).mul(view).translate(0f, -BOTI_DY, 0f);
            int vcx = Math.floorDiv((int) camera.position.x, Chunk.SIZE);
            int vcz = Math.floorDiv((int) camera.position.z, Chunk.SIZE);
            org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, portalFbo[0]);
            glClearColor(0.03f, 0.02f, 0.05f, 1f);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            glViewport(0, 0, Math.max(1, fw[0]), Math.max(1, fh[0]));
            renderChunksToFbo(shader, vMvp, vcx, vcz, 2, -1);
        }

        // ── BOTI EXIT PORTAL (slot 1) ─
        if (botiBuilt && botiInside && botiExitMesh != null) {
            Matrix4f vMvp = new Matrix4f(projection).mul(view).translate(0f, BOTI_DY, (BOTI_B_D - BOTI_A_D));
            int vcx = Math.floorDiv((int) camera.position.x, Chunk.SIZE);
            int vcz = Math.floorDiv((int) (camera.position.z - (BOTI_B_D - BOTI_A_D)), Chunk.SIZE);
            org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, portalFbo[1]);
            glClearColor(0.5f, 0.7f, 0.9f, 1f);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            glViewport(0, 0, Math.max(1, fw[0]), Math.max(1, fh[0]));
            renderChunksToFbo(shader, vMvp, vcx, vcz, 1, -4);
        }

        // ── RR PORTAL 4→5 (slot 2) ──────────
        if (rrBuilt && rrPortal45 != null && rrRoom == 4) {
            Matrix4f vMvp = new Matrix4f(projection).mul(view).translate(-50f, 0f, 0f);
            int vcx = Math.floorDiv((int) (camera.position.x + 50f), Chunk.SIZE);
            int vcz = Math.floorDiv((int) camera.position.z, Chunk.SIZE);
            org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, portalFbo[2]);
            glClearColor(0.03f, 0.02f, 0.05f, 1f);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            glViewport(0, 0, Math.max(1, fw[0]), Math.max(1, fh[0]));
            renderChunksToFbo(shader, vMvp, vcx, vcz, 1, -1);
        }

        // ── RR PORTAL 6→1 (slot 3) ──────────
        if (rrBuilt && rrPortal61 != null && rrRoom == 8) {
            Matrix4f vMvp = new Matrix4f(projection).mul(view).translate(50f, 0f, 0f);
            int vcx = Math.floorDiv((int) (camera.position.x - 50f), Chunk.SIZE);
            int vcz = Math.floorDiv((int) camera.position.z, Chunk.SIZE);
            org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, portalFbo[3]);
            glClearColor(0.03f, 0.02f, 0.05f, 1f);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            glViewport(0, 0, Math.max(1, fw[0]), Math.max(1, fh[0]));
            renderChunksToFbo(shader, vMvp, vcx, vcz, 1, -1);
        }

        org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, 0);
        glClearColor(0.5f, 0.7f, 0.9f, 1.0f);
        glViewport(0, 0, Math.max(1, fw[0]), Math.max(1, fh[0]));
    }

    /** Draw portal quads using the FBO textures rendered above. */
    private void renderAllPortalQuads(Shader shader, Matrix4f mvp) {
        glDisable(GL_STENCIL_TEST);
        glDepthFunc(GL_LEQUAL);

        if (botiBuilt && !botiInside && botiEntryMesh != null) {
            drawPortalQuad(shader, mvp, portalColorTex[0], botiEntryMesh);
        }
        if (botiBuilt && botiInside && botiExitMesh != null) {
            drawPortalQuad(shader, mvp, portalColorTex[1], botiExitMesh);
        }
        if (rrBuilt && rrPortal45 != null && rrRoom == 4) {
            drawPortalQuad(shader, mvp, portalColorTex[2], rrPortal45);
        }
        if (rrBuilt && rrPortal61 != null && rrRoom == 8) {
            drawPortalQuad(shader, mvp, portalColorTex[3], rrPortal61);
        }

        glDepthFunc(GL_LESS);
        shader.setUniform("portalMode", 0);
        shader.setUniform("useTexture",  0);
        org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0);
    }

    private void drawPortalQuad(Shader shader, Matrix4f mvp, int texId, Mesh quad) {
        org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
        org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, texId);
        shader.setUniform("texSampler",   0);
        shader.setUniform("portalMode",   1);
        // CRITICAL: must pass the PHYSICAL framebuffer size, not the logical window size.
        // gl_FragCoord is in physical pixels; dividing by the logical size on a Retina
        // display gives UV in [0,2] instead of [0,1], causing 2×2 = 4-quad tiling.
        shader.setUniform("viewportSize", (float) Math.max(1, fw[0]), (float) Math.max(1, fh[0]));
        shader.setUniform("mvp", mvp);
        quad.render();
    }

    /**
     * Build a portal-surface quad (4 verts, 2 triangles).
     * The vertices span the interior opening of the arch/doorway.
     * Normal is always (0,1,0) — not used by portalMode but required by the mesh format.
     */
    private Mesh makeQuad(float x0, float y0, float z0,
                          float x1, float y1, float z1,
                          float x2, float y2, float z2,
                          float x3, float y3, float z3) {
        // Neutral white RGBA so the FBO texture is not tinted.
        float r = 1f, g = 1f, b = 1f, a = 1f;
        float nx = 0f, ny = 1f, nz = 0f;
        float[] verts = {
            x0, y0, z0,  r, g, b, a,  nx, ny, nz,
            x1, y1, z1,  r, g, b, a,  nx, ny, nz,
            x2, y2, z2,  r, g, b, a,  nx, ny, nz,
            x3, y3, z3,  r, g, b, a,  nx, ny, nz,
        };
        return new Mesh(verts, new int[]{ 0, 1, 2,  0, 2, 3 });
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  "BIGGER ON THE INSIDE" TUNNEL
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Build Structure A (exterior arch, 5-deep) and Structure B (interior tunnel,
     * 50-deep) at the same XZ but different Y (A=400, B=900).  Also allocates FBOs
     * and creates portal quad meshes.
     */
    private void buildBOTI() {
        if (portalFbo == null) createPortalFbos();

        // ── Structure A — exterior casing ─────────────────────────────────────
        buildTunnel(BOTI_X0, BOTI_A_Y0, BOTI_Z0, BOTI_W, BOTI_H, BOTI_A_D,
                    Block.STONE, Block.STONE_LICHEN);

        // ── Structure B — interior tunnel ─────────────────────────────────────
        // Same XZ, higher Y (CY=1).  Alternating stone and fossil every 10 blocks
        // gives the player visual feedback of distance (like stripes on a tunnel).
        buildTunnel(BOTI_X0, BOTI_B_Y0, BOTI_Z0, BOTI_W, BOTI_H, BOTI_B_D,
                    Block.STONE, Block.FOSSIL_STONE);

        // ── Portal quad meshes ─────────────────────────────────────────────────
        // Entry portal: flush with Structure A's entrance face (z = BOTI_Z0)
        float ax0 = BOTI_X0 + 1f, ax1 = BOTI_X0 + BOTI_W - 1f;
        float ay0 = BOTI_A_Y0 + 1f, ay1 = BOTI_A_Y0 + BOTI_H - 1f;
        float az  = BOTI_Z0 + 0.01f; // tiny offset to avoid z-fight with entrance wall
        botiEntryMesh = makeQuad(ax0, ay0, az,  ax1, ay0, az,
                                 ax1, ay1, az,  ax0, ay1, az);

        // Exit portal: just inside the back wall of Structure B.
        // B's back wall block is at z = BOTI_Z0+BOTI_B_D-1 = 2449.
        // The portal quad is placed at z = 2448.99 — the inner face of that wall,
        // so the player in B sees the outside-world view through the portal.
        float bx0 = BOTI_X0 + 1f, bx1 = BOTI_X0 + BOTI_W - 1f;
        float by0 = BOTI_B_Y0 + 1f, by1 = BOTI_B_Y0 + BOTI_H - 1f;
        float bz  = BOTI_Z0 + BOTI_B_D - 1 - 0.01f;  // = 2448.99
        botiExitMesh = makeQuad(bx0, by0, bz,  bx1, by0, bz,
                                bx1, by1, bz,  bx0, by1, bz);

        botiBuilt = true;
    }


    private void buildRotatingRooms() {
        if (portalFbo == null) createPortalFbos();
        java.util.Set<Long> chunks = new java.util.HashSet<>();

        // Build two identical 2x2 looping grids
        buildGrid(RR_A_X0, RR_A_Z0, chunks, true);
        buildGrid(RR_B_X0, RR_B_Z0, chunks, false);

        // Rebuild meshes
        for (long key : chunks) {
            int cz2 = (int)((key & 0xFFFFF) - 4096);
            int cy2 = (int)(((key >> 20) & 0xFF) - 64);
            int cx2 = (int)((key >> 28) - 4096);
            Chunk c = world.getOrCreateChunk(cx2, cy2, cz2);
            c.noEvict = true;
            world.buildChunkMeshes(c);
            c.state = Chunk.ChunkState.MESHED;
        }

        float py0 = RR_FLOOR_Y + 1f;
        float py1 = RR_FLOOR_Y + 5f;

        // Portal 1 (A -> B): Placed in Grid A's SW room, North door.
        float pA_x0 = RR_A_X0 + RR_W / 2f - 1f;
        float pA_x1 = RR_A_X0 + RR_W / 2f + 2f;
        float pA_z  = RR_A_Z0 + RR_W + 0.01f;
        rrPortal45 = makeQuad(pA_x1, py0, pA_z,  pA_x0, py0, pA_z,  pA_x0, py1, pA_z,  pA_x1, py1, pA_z);

        // Portal 2 (B -> A): Placed in Grid B's SW room, North door.
        float pB_x0 = RR_B_X0 + RR_W / 2f - 1f;
        float pB_x1 = RR_B_X0 + RR_W / 2f + 2f;
        float pB_z  = RR_B_Z0 + RR_W + 0.01f;
        rrPortal61 = makeQuad(pB_x1, py0, pB_z,  pB_x0, py0, pB_z,  pB_x0, py1, pB_z,  pB_x1, py1, pB_z);

        rrBuilt = true;
    }
    private void buildTunnel(int x0, int y0, int z0,
                             int w, int h, int d,
                             Block wallBlock, Block accentBlock) {
        java.util.Set<Long> chunksToRebuild = new java.util.HashSet<>();

        for (int x = x0; x < x0 + w; x++) {
            for (int y = y0; y < y0 + h; y++) {
                for (int z = z0; z < z0 + d; z++) {
                    boolean isWall    = (x == x0 || x == x0 + w - 1);
                    boolean isFloor   = (y == y0);
                    boolean isCeiling = (y == y0 + h - 1);

                    // FIX: No back wall! It is an open tube.
                    boolean isSolid   = isWall || isFloor || isCeiling;

                    Block block = Block.AIR;
                    if (isSolid) {
                        block = ((z - z0) % 10 < 2) ? accentBlock : wallBlock;
                    }
                    world.setBlockWithMeta(x, y, z, block, (byte) 0, false);

                    int cx = Math.floorDiv(x, Chunk.SIZE);
                    int cy = Math.floorDiv(y, Chunk.HEIGHT);
                    int cz = Math.floorDiv(z, Chunk.SIZE);
                    long key = ((long)(cx + 4096) << 28) | ((long)(cy + 64) << 20) | (cz + 4096);
                    chunksToRebuild.add(key);
                }
            }
        }

        for (long key : chunksToRebuild) {
            int cz2 = (int)((key & 0xFFFFF) - 4096);
            int cy2 = (int)(((key >> 20) & 0xFF) - 64);
            int cx2 = (int)((key >> 28) - 4096);
            Chunk c = world.getOrCreateChunk(cx2, cy2, cz2);
            c.noEvict = true;
            world.buildChunkMeshes(c);
            c.state = Chunk.ChunkState.MESHED;
        }
    }

    private void buildGrid(int gx, int gz, java.util.Set<Long> chunks, boolean isGridA) {
        int y0 = RR_FLOOR_Y;
        int xDiv = gx + RR_W, zDiv = gz + RR_W;
        int doorN = gz + RR_W / 2, doorS = gz + RR_W + RR_W / 2;
        int doorW = gx + RR_W / 2, doorE = gx + RR_W + RR_W / 2;

        for (int x = gx; x <= gx + 2*RR_W; x++) {
            for (int z = gz; z <= gz + 2*RR_W; z++) {
                for (int y = y0; y <= y0 + 6; y++) {
                    boolean floor = (y == y0), ceil = (y == y0 + 6);
                    boolean outerW = (x == gx), outerE = (x == gx + 2*RR_W);
                    boolean outerN = (z == gz), outerS = (z == gz + 2*RR_W);
                    boolean xDivCol = (x == xDiv), zDivRow = (z == zDiv);
                    boolean pillar = (xDivCol && zDivRow);

                    // Clockwise doorways around the pillar
                    boolean doorNW_NE = xDivCol && Math.abs(z - doorN) <= 1 && y > y0 && y < y0 + 4;
                    boolean doorNE_SE = zDivRow && Math.abs(x - doorE) <= 1 && y > y0 && y < y0 + 4;
                    boolean doorSE_SW = xDivCol && Math.abs(z - doorS) <= 1 && y > y0 && y < y0 + 4;
                    boolean doorSW_NW = zDivRow && Math.abs(x - doorW) <= 1 && y > y0 && y < y0 + 4;

                    boolean entryArch = isGridA && outerN && Math.abs(x - doorW) <= 1 && y > y0 && y < y0 + 4;

                    Block b = Block.AIR;
                    if (floor || ceil || pillar) b = Block.STONE;
                    else if (doorNW_NE || doorNE_SE || doorSE_SW || doorSW_NW) b = Block.AIR;
                    else if (outerW || outerE || outerN || outerS || xDivCol || zDivRow) {
                        b = entryArch ? Block.AIR : Block.STONE;
                    }

                    // FIX: Both grids use identical materials so the illusion is perfectly seamless
                    if (b == Block.STONE && !floor && !ceil && !pillar) {
                        if ((x + z) % 8 == 0) b = Block.STONE_LICHEN;
                    }

                    world.setBlockWithMeta(x, y, z, b, (byte)0, false);
                    int cx = Math.floorDiv(x, Chunk.SIZE), cy = Math.floorDiv(y, Chunk.HEIGHT), cz = Math.floorDiv(z, Chunk.SIZE);
                    chunks.add(((long)(cx+4096)<<28)|((long)(cy+64)<<20)|(cz+4096));
                }
            }
        }
    }

    // ── BLOCK SOUND HELPERS ───────────────────────────────────────────────────
    // Place and break use the same file per material (block_stone/soil/sand/crystal).
    // Dig sounds fire periodically while holding the break key; crystals use a
    // shuffled sequence of four clank notes instead of a single looped file.

    static String blockPlaceSound(Block b) {
        if (b == null) return null;
        return switch (b) {
            case STONE, ISLAND_STONE, FOSSIL_STONE, SCORCHED_STONE,
                 MEGALITH, MEGALITH_CARVED, MOSSY_MEGALITH,
                 CRYSTAL_BASE, STAR_IRON,
                 OAK_LOG, OAK_LEAVES, PETRIFIED_WOOD,
                 PETRIFIED_BARK, HANGING_ROOT                    -> "block_stone";
            case DIRT, GRASS, MUD, ANCIENT_SOIL                  -> "block_soil";
            case SAND, RED_SAND, GRAVEL, SNOW                    -> "block_sand";
            case CRYSTAL_AMETHYST, CRYSTAL_QUARTZ,
                 CRYSTAL_CITRINE, CRYSTAL_ROSE                   -> "block_crystal";
            default                                              -> "block_stone";
        };
    }

    static String blockBreakSound(Block b) {
        if (b == null) return null;
        return switch (b) {
            case STONE, ISLAND_STONE, FOSSIL_STONE, SCORCHED_STONE,
                 MEGALITH, MEGALITH_CARVED, MOSSY_MEGALITH,
                 CRYSTAL_BASE, STAR_IRON,
                 OAK_LOG, OAK_LEAVES, PETRIFIED_WOOD,
                 PETRIFIED_BARK, HANGING_ROOT                    -> "block_stone";
            case DIRT, GRASS, MUD, ANCIENT_SOIL                  -> "block_soil";
            case SAND, RED_SAND, GRAVEL, SNOW                    -> "block_sand";
            case CRYSTAL_AMETHYST, CRYSTAL_QUARTZ,
                 CRYSTAL_CITRINE, CRYSTAL_ROSE                   -> "block_crystal";
            default                                              -> "block_stone";
        };
    }

    /**
     * Returns the dig sound to play while the player is actively breaking a block.
     * Crystal returns the special sentinel {@code "crystal_clank_seq"} — the caller
     * should call {@link #nextCrystalClank()} to get the actual shuffled sound name.
     */
    static String blockDigSound(Block b) {
        if (b == null) return null;
        return switch (b) {
            case STONE, ISLAND_STONE, FOSSIL_STONE, SCORCHED_STONE,
                 MEGALITH, MEGALITH_CARVED, MOSSY_MEGALITH,
                 CRYSTAL_BASE, STAR_IRON,
                 OAK_LOG, OAK_LEAVES, PETRIFIED_WOOD,
                 PETRIFIED_BARK, HANGING_ROOT                    -> "stone_digging";
            case DIRT, GRASS, MUD, ANCIENT_SOIL                  -> "soil_digging";
            case SAND, RED_SAND, GRAVEL, SNOW                    -> "sand_digging";
            case CRYSTAL_AMETHYST, CRYSTAL_QUARTZ,
                 CRYSTAL_CITRINE, CRYSTAL_ROSE                   -> "crystal_clank_seq";
            default                                              -> "stone_digging";
        };
    }

    /**
     * Returns the next crystal-clank sound name from a shuffled sequence.
     * When the sequence is exhausted it reshuffles so consecutive plays never
     * sound machine-gunned.
     */
    String nextCrystalClank() {
        if (crystalClankIdx >= CRYSTAL_CLANKS.length) {
            // Fisher-Yates shuffle
            for (int i = CRYSTAL_CLANKS.length - 1; i > 0; i--) {
                int j = (int)(Math.random() * (i + 1));
                int tmp = crystalClankOrder[i];
                crystalClankOrder[i] = crystalClankOrder[j];
                crystalClankOrder[j] = tmp;
            }
            crystalClankIdx = 0;
        }
        return CRYSTAL_CLANKS[crystalClankOrder[crystalClankIdx++]];
    }

    private void updateRotatingRoomsPortal() {
        float px = player.position.x, pz = player.position.z;

        if (px >= RR_A_X0 && px <= RR_A_X0 + 2*RR_W && pz >= RR_A_Z0 && pz <= RR_A_Z0 + 2*RR_W) {
            boolean inSW = px < RR_A_X0 + RR_W && pz > RR_A_Z0 + RR_W;
            rrRoom = inSW ? 4 : 1;

            if (inSW && Math.abs(px - (RR_A_X0 + RR_W / 2f)) < 2f) {
                // Crossing Portal 1 walking North
                if (pz < RR_A_Z0 + RR_W + 0.01f) {
                    player.position.x += 50f; // Instant mathematical warp to Grid B
                    rrRoom = 8;
                }
            }
        } else if (px >= RR_B_X0 && px <= RR_B_X0 + 2*RR_W && pz >= RR_B_Z0 && pz <= RR_B_Z0 + 2*RR_W) {
            boolean inSW = px < RR_B_X0 + RR_W && pz > RR_B_Z0 + RR_W;
            rrRoom = inSW ? 8 : 5;

            if (inSW && Math.abs(px - (RR_B_X0 + RR_W / 2f)) < 2f) {
                // Crossing Portal 2 walking North
                if (pz < RR_B_Z0 + RR_W + 0.01f) {
                    player.position.x -= 50f; // Instant mathematical warp to Grid A
                    rrRoom = 4;
                }
            }
        } else {
            rrRoom = 0;
        }
    }
}