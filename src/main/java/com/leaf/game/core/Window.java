package com.leaf.game.core;

import com.leaf.game.entity.*;
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
import static org.lwjgl.opengl.GL33.*;
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
    com.leaf.game.util.Camera camera;
    /** The endgame: summons -> portal -> arena gauntlet -> ascension. */
    FinaleManager finale;
    WorldGen worldGen;


    // ── SPAWN POINT ───────────────────────────────────────────────────────────
    // Fixed world-spawn XZ. Y is determined by scanning for the surface at load.
    private static final float SPAWN_X = 777.0f;
    private static final float SPAWN_Z = 777.0f;
    /** Actual surface Y found during preload; used for respawning on death. */
    private float spawnSurfaceY = 250.0f;

    NoiseVisualizer noiseVis;
    boolean showNoiseViewer = false;
    RaycastResult lastTarget = null;

    public com.leaf.game.entity.spider.SpiderEnemy riddenSpider = null;
    public boolean spiderLaserActive = false;

    NetworkSession network;
    RemotePlayer remotePlayer;
    private Shader starShader = null;
    private int starVao = 0;
    private int starVbo = 0;

    // ── UI & COMMANDS ────────────────────────────────────────────────────────
    public final CommandHandler commandHandler = new CommandHandler(this);
    public final BackpackUI backpackUI = new BackpackUI(this);

    // ── MULTIPLAYER COMBAT (PvP + summonable troops) ───────────────────────────
    /** Troops WE spawned (simulated locally, charge the opponent, streamed to peer). */
    final java.util.List<com.leaf.game.entity.Summon> mySummons = new java.util.ArrayList<>();
    /** Invisible DUMMY mirroring the remote player so our abilities can hit them (PvP). */
    com.leaf.game.entity.Enemy mpProxy = null;
    boolean mpLastSummonKey = false;
    float   mpSummonSendTimer = 0f;
    com.leaf.game.render.Mesh summonCubeMine, summonCubeFoe;   // cyan = ours, red = theirs
    float   killBannerTimer = 0f;   // >0 shows "ENEMY ELIMINATED" after a PvP kill

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

    // ── ABILITY-UNLOCK CARD (shown between waves) ──────────────────────────────
    boolean showUnlockCard = false;
    int     unlockCardWave  = 0;
    java.util.List<Progression.Ability> unlockCardAbilities = new java.util.ArrayList<>();
    private boolean lastCardSpace = false;

    // ── CUTSCENE (intro / ending) ──────────────────────────────────────────────
    final CutsceneManager cutscene = new CutsceneManager();
    /** Set by the "Play Game" button; the intro plays once the spawn finishes loading. */
    boolean playIntroOnSpawn = false;

    /** Counts down after the player takes damage; HUD pulses the health bar red. */
    float damageFlashTimer = 0f;

    // ── SMASH HINT (first 3 high falls, PILLAR must be unlocked) ─────────────
    boolean showSmashHint   = false;
    int     smashHintCount  = 0;   // how many separate fall events have shown the hint

    // ── DEATH SCREEN ──────────────────────────────────────────────────────────
    boolean      showDeathScreen     = false;
    String[]     deathScreenLines    = null;
    private boolean lastDeathEnter   = false;


    /**
     * Set when the player dies and a cutscene sequence is pending.
     * KAMUI_AWAKEN plays first (on 3rd death), then REVIVAL auto-plays, then
     * Window restores the player automatically when the revival ends.
     * While any death cutscene is active: player update is frozen (same gate as showDeathScreen).
     */
    boolean      deathCutscenePending  = false;
    /** Set when the 3rd death fires the Kamui awakening; consumed once revival ends. */
    private boolean kamuiJustUnlockedThisRevival = false;
    /** Seconds of post-revival immunity remaining (player is invincible, flickers). */
    float        immunityTimer = 0f;
    static final float REVIVAL_IMMUNITY_SECS = 4f;

    /** Showcase / demo mode (toggled by {@code /showcase}). While on: mana is kept
     *  topped, health stays full and the player is invincible, so every ability can
     *  be demoed back-to-back without fizzling or dying. Set in CommandHandler. */
    public boolean showcaseMode = false;

    /** When the PLAY button is pressed, this arms the full playtest sandbox the
     *  moment the world finishes loading (all abilities, weapons in the hotbar,
     *  free-explore enemies, no tutorial walls). Consumed once at spawn. */
    public boolean armPlaytestOnSpawn = false;
    /** True for the whole session once a playtest run begins  -  drives the
     *  always-on controls legend so the player never needs to be told anything. */
    public boolean playtestMode = false;

    // ── THE VOYAGE  -  guided exploration quest (replaces wave grinding) ──────────
    Voyage  voyage        = null;
    boolean voyageStarted = false;
    // Weapon-reveal overlay (fancy WEAPON FORGED card) + queue for back-to-back grants
    Progression.Ability weaponRevealAbility = null;
    int                 weaponRevealSlot    = -1;
    float               weaponRevealTimer   = 0f;
    private final java.util.ArrayDeque<Progression.Ability> weaponRevealQueue = new java.util.ArrayDeque<>();

    // ── TRY IT prompts  -  after each unlock card, big non-blocking guided steps
    //    per new ability. Each completes (chime + flash + NICE!) the moment the
    //    player actually does it; times out quietly otherwise. Hard-to-grasp
    //    abilities (drone / seal / substitute) get multi-step mini-tutorials. ──
    static final class TryStep {
        final Progression.Ability ability;  // owning power (celebration label)
        final String chip;                  // big key chip text ("HOLD G", "X"…)
        final String headline;              // main instruction (large)
        final String detail;                // one line of guidance (smaller)
        final int    detect;                // detector id  -  see tryStepDone()
        final float  timeout;
        TryStep(Progression.Ability ability, String chip, String headline,
                String detail, int detect, float timeout) {
            this.ability = ability; this.chip = chip; this.headline = headline;
            this.detail = detail; this.detect = detect; this.timeout = timeout;
        }
    }
    // Detector ids for TryStep.detect
    static final int TRY_COOLDOWN_DIP = 0;  // ability's ready-fraction dipped
    static final int TRY_STAND_OUT    = 1;  // drone deployed
    static final int TRY_STAND_PILOT  = 2;  // entered drone perspective
    static final int TRY_SEAL_PLACED  = 3;  // a seal is out
    static final int TRY_SEAL_WARP    = 4;  // teleported to a seal
    static final int TRY_SUB_PRIMED   = 5;  // substitute held primed
    static final int TRY_FLYING       = 6;  // flight mode entered
    static final int TRY_INFO_ONLY    = 7;  // no detection  -  timeout advances

    private final java.util.ArrayDeque<TryStep> tryQueue = new java.util.ArrayDeque<>();
    TryStep             tryStep       = null;   // currently shown step
    float               tryTimer      = 0f;     // countdown until silent skip
    Progression.Ability tryDoneAbility = null;  // shows the NICE! celebration
    float               tryDoneTimer  = 0f;
    private float   trySnapshot  = 0f;
    private boolean trySnapInit  = false;
    // Night-time torch hint (shown once)
    boolean shownNightHint = false;
    /** Big centred reveal/directive banner the Voyage pushes (forge moments, next step). */
    String  voyageMsg     = null;
    float   voyageMsgTimer = 0f;
    private float beaconFxTimer = 0f;

    /** Called by {@link Voyage} to flash a directive or forge-reveal on screen. */
    public void showVoyageForge(String msg) { voyageMsg = msg; voyageMsgTimer = 7.5f; }

    /**
     * Emit the in-world beam of light marking the current Voyage objective  -  a tall,
     * glowing column visible from across the map so the player always knows where to go.
     */
    private void renderVoyageBeacon(float dt) {
        if (voyage == null || !voyage.beaconReady()) return;
        Vector3f b = voyage.beaconPos();
        float[] c = voyage.beaconColor();
        if (b == null || c == null) return;
        beaconFxTimer -= dt;
        if (beaconFxTimer > 0f) return;
        beaconFxTimer = 0.05f;
        // A 70-block column of upward streaks + a pulsing base ring.
        for (int i = 0; i < 7; i++) {
            float y = b.y + i * 10f;
            fxBolt(b.x, y, b.z, 0f, 1f, 0f, 9f, 0.55f, 0.5f, c[0], c[1], c[2]);
        }
        fxRing(b.x, b.y + 0.4f, b.z, 1.0f, 6.5f, 0.7f, c[0], c[1], c[2]);
        fxBurst(b.x, b.y + 1.2f, b.z, 0.2f, 2.2f, 0.6f, c[0], c[1], c[2]);
    }

    // ── PRACTICE SESSION  -  multi-step hands-on ability tutorial ──────────────
    /** Which ability is currently being taught; null when no session is active. */
    Progression.Ability practiceAbility  = null;
    /** Per-step list for the active tutorial; null = not running. */
    java.util.List<AbilityPractice.Step> practiceSteps = null;
    /** Index into practiceSteps for the current step. */
    int practiceStepIndex = 0;
    /** Seconds elapsed on the current step. */
    float practiceStepAge = 0f;
    /** Context passed to step lambdas (reused across steps). */
    final AbilityPractice.StepCtx practiceCtx = new AbilityPractice.StepCtx();
    /** True once the current step's done() predicate fired once (latch). */
    boolean practiceStepDone = false;
    /** Celebration pause: seconds remaining after done() fired before advancing. */
    float practiceCelebration = 0f;
    static final float PRACTICE_TIMEOUT = 60f;   // per step
    static final float PRACTICE_CELEBRATE_SECS = 1.4f;
    /** Short warning shown at the top of the practice card; cleared when timer expires. */
    String practiceWarnText  = null;
    float  practiceWarnTimer = 0f;
    /** Queued practice sessions for the current wave (e.g. wave 8 = Seal then Kamui). */
    final java.util.ArrayDeque<Progression.Ability> practiceQueue = new java.util.ArrayDeque<>();
    /** ENTER edge-detect (so the card-dismiss ENTER can't immediately skip). */
    private boolean lastPracticeEnter = false;
    /** Set after the wave-9 ending cutscene fires; stops further waves. */
    boolean gameEnded = false;
    /** Camera yaw snapped each frame for AbilityPractice.spawnDummy. */
    float lastCameraYaw = 0f;

    final ImString chatInput = new ImString(256);
    final List<String> chatHistory = new ArrayList<>();
    final ImString seedInput = new ImString(32);

    final Inventory inventory = new Inventory();
    int   selectedSlot  = 0;
    Block selectedBlock = Block.AIR;
    float digParticleTimer = 0f;

    // Starting kit  -  basic. Slots 1-4 are RESERVED (empty) for the god-tier weapons
    // earned through the progression; each drops into its own fixed slot on unlock
    // (see grantWeaponForAbility). Block selectors fill in as the player mines.
    final Block[] hotbar = {
            Block.WPN_SNIPER, Block.AIR, Block.AIR, Block.AIR,
            Block.AIR, Block.TELESCOPE, Block.TORCH, Block.DIRT, Block.STONE
    };

    final List<DroppedItem> droppedItems = new ArrayList<>();
    private final Map<Block, Mesh>  itemMeshes   = new HashMap<>();

    // ── ENEMY SYSTEM ──────────────────────────────────────────────────────────
    EnemyManager enemyManager;

    // ── Animated enemy rendering ──────────────────────────────────────────────
    // Single shared model definition; each enemy has its own AnimPlayer instance
    // (independent animation time) stored by enemy ID.
    private com.leaf.game.anim.AnimModel enemyAnimModel = null;
    private com.leaf.game.anim.AnimModel slimeAnimModel = null;
    private com.leaf.game.anim.AnimModel golemAnimModel = null;
    private com.leaf.game.anim.AnimModel lavaSlimeAnimModel = null;
    private com.leaf.game.anim.AnimModel infernoTowerAnimModel = null;
    /** Throttle for the living-world ambience VFX (tower embers, sakura petals). */
    private float ambienceTimer = 0f;
    private final java.util.Map<Integer, com.leaf.game.anim.AnimPlayer> enemyAnimPlayers
            = new java.util.HashMap<>();
    /** Edge-detect for P key to spawn enemies. */
    private boolean lastP = false;
    private boolean lastZeroKey = false;   // debug: spawn a GUARDIAN with [0]

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
    /** True while V is held and the ability is ready  -  next hit will be negated. */
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
    private boolean lastF11 = false;
    private boolean lastTilde = false;     // Tracks Flappy Mode key (`)
    // ── FLAPPY RECOVERY STATE ──
    private float   flappyPrevX, flappyPrevY, flappyPrevZ;
    private float   flappyPrevYaw, flappyPrevPitch;
    private boolean flappyPrevWaves = true;
    private boolean lastBackspace   = false;
    /** Auto-dismiss welcome banner shown when the game first loads. */
    float   welcomeTimer   = 0f;
    private boolean welcomeStarted = false;

    // ── Onboarding tutorial ────────────────────────────────────────────────────
    // A designed, staged tutorial (see TutorialManager). Spawns enemies by hand at
    // controlled difficulty and only turns on infinite waves once the player
    // graduates. Created when the world finishes preloading.
    TutorialManager tutorial = null;
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
    private int  kamuiFboW       = 0;   // last known FBO size  -  recreate on resize
    private int  kamuiFboH       = 0;
    private com.leaf.game.render.Shader distortShader = null;
    /** All ImGui HUD rendering  -  see WindowHud.java */
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
    //   Hold R -> slow motion  (GameConfig.timeSlowScale ≈ 0.15)
    //   Hold Y -> fast time    (GameConfig.timeFastScale  ≈ 4.0)
    // (T is reserved for chat; R was chosen as the slow-time key.)

    // ── SCREEN SHAKE (Ground Smash landing effect) ────────────────────────────
    // A damped sinusoidal camera offset applied for smashShakeDuration seconds.
    // Window temporarily offsets camera.position before rendering, then restores
    // it so Player's position accounting is unaffected.
    private float smashShakeTimer = 0f;   // counts down from smashShakeDuration
    public final Random shakeRng = new Random();
    private float activeShakeAmplitude = GameConfig.smashShakeAmplitude; // Dynamic amplitude
    private float activeShakeDuration  = GameConfig.smashShakeDuration;  // Dynamic duration

    // ── METEOR EFFECT (Smash visual) ─────────────────────────────────────────
    // When a ground smash begins, a STAR_IRON DroppedItem is spawned high above
    // the player and falls at high speed  -  giving the descent a "meteor crashing
    // from the sky" visual without requiring a particle system.
    private boolean wasSmashing      = false;
    private boolean wasCannonballing = false;
    private boolean wasCharging      = false;   // edge-detect for preload trigger
    /** Last-frame mana, used to detect the moment mana hits zero. */
    private float   lastMana         = -1f;
    /** Last-frame camera position  -  lets us derive listener velocity for Doppler. */
    private final Vector3f lastListenerPos = new Vector3f();
    private boolean listenerPosInit = false;
    /** Last muffle amount sent to OpenAL; only re-sent when it moves meaningfully. */
    private float   lastMuffle       = 0f;
    /** Last reverb environment  -  only call setEnvironment when this changes. */
    private int     lastEnv          = AudioManager.ENV_NONE;

    // ── Water sound state ────────────────────────────────────────────────────
    private boolean lastFeetInWater  = false;
    private boolean lastCamSubmerged = false;

    // ── Wind stinger timers ──────────────────────────────────────────────────
    // wind_harsh: random gusts fired during fast air movement.
    // caveWindCooldown: wind_cemetery eerie stinger, fires slowly in cave env.
    private float windStingerCooldown = 0f;
    private float caveWindCooldown    = 5f;   // start at 5 s so it doesn't fire on room entry
    // windFade: 0->1 multiplier that smoothly fades wind beds in/out instead of
    // abruptly starting/stopping them (avoids pops and sudden silence on landing).
    private float windFade            = 0f;

    // ── Snow weather + ambient wind gusts ─────────────────────────────────────
    // Accumulated game-time drives snowflake animation (deterministic, no state).
    private float snowTimeAccum    = 0f;
    private float snowIntensity    = 0f;  // 0..1, updated each frame from altitude
    // Gust system: fires randomly, audible even when standing still, tilts snow.
    private float windGustStrength = 0f;   // smoothed 0..1 current strength
    private float windGustAngle    = 0f;   // radians  -  which way the gust blows
    private float windGustTimer    = 10f + (float)(Math.random() * 18f); // until next gust
    private float windGustDuration = 0f;   // remaining seconds of current gust

    // ── Footstep state ───────────────────────────────────────────────────────
    // Walking/running files are long loops  -  we keep one looping continuously
    // rather than re-triggering them as one-shots.
    private String  activeStepLoop    = null; // which step loop is currently running

    // ── Flight-stop swoosh ────────────────────────────────────────────────────
    private boolean lastFlightMode    = false; // player.debugMode last frame (before update)

    // ── Crystal dig sequence (plays clank1..4 in shuffled random order) ─────
    // cystal_clank2 has a typo in the filename on disk  -  keep it to match the file.
    static final String[] CRYSTAL_CLANKS  = {
            "crystal_clank1", "cystal_clank2", "crystal_clank3", "crystal_clank4"
    };
    private int[]   crystalClankOrder = {0, 1, 2, 3};
    private int     crystalClankIdx   = 4;    // force a shuffle on first use

    // ── Dig sound timer (fires while holding break key) ────────────────────
    float digSoundTimer = 0f;   // package-private  -  reset from WindowHud.updateBreaking()
    float digPreDelay   = 0f;   // how long break key has been held  -  sounds start after a brief delay
    float   pathReadiness    = 0f;      // 0..1 shown in HUD during charging
    private boolean clientSpawnedAtHost = false;

    // ── PHYSICAL FRAMEBUFFER SIZE ─────────────────────────────────────────────
    // Tracked as instance fields so portal/kamui FBO code can read them without
    // needing them passed as parameters.  Updated once per frame via
    // glfwGetFramebufferSize inside loop().  On Retina/HiDPI displays these are
    // 2× the logical window size  -  the portal viewportSize uniform MUST use
    // these values, not the hardcoded PORTAL_FBO_W/H, to avoid UV tiling.
    private final int[] fw = {1280};
    private final int[] fh = {720};

    // ═══════════════════════════════════════════════════════════════════════════
    //  NON-EUCLIDEAN "LAYERED ROOMS"  (toggle with F6)
    // ═══════════════════════════════════════════════════════════════════════════
    // A 2×2 quadrant complex with a solid central cross-pillar. Walk CLOCKWISE
    // around the pillar and you pass through an unbounded sequence of distinct
    // rooms (1->2->3->4->5->6…) instead of looping back after four. The trick: the
    // solid centre fully occludes the diagonal quadrant, so each time you round a
    // corner we silently re-skin the now-hidden diagonal room to be the NEXT room
    // in the sequence. You never see the swap; the space feels infinite.
    //
    // The whole structure is built once at a fixed faraway spot (one chunk) and
    // sealed, so it never collides with terrain. F6 teleports you in/out.
    private static final int NER_X0     = 2450;  // min-corner world X
    private static final int NER_Y0     = 400;   // floor Y (well above terrain)
    private static final int NER_Z0     = 2450;  // min-corner world Z
    private static final int NER_ROOM   = 5;     // interior size of each quadrant
    private static final int NER_HT     = 5;     // interior height
    private static final int NER_SPAN   = 2 * NER_ROOM + 3; // 13 = walls+quads+divider
    private static final int NER_CX     = NER_X0 + NER_SPAN / 2; // centre column (rel 6)
    private static final int NER_CZ     = NER_Z0 + NER_SPAN / 2; // centre row    (rel 6)
    // Distinct OPAQUE blocks (transparent ones would break the occlusion). One per
    // room, cycled  -  so even far-apart rooms read as clearly different spaces.
    private static final Block[] NER_PALETTE = {
        Block.RED_SAND, Block.CRYSTAL_BASE, Block.SNOW, Block.GRASS,
        Block.STAR_IRON, Block.SAND, Block.BONE, Block.SCORCHED_STONE,
    };
    private boolean nerBuilt  = false;
    private boolean nerActive = false;
    private int     nerRoom   = 1;     // current logical room number (climbs forever)
    private int     nerQuad   = 0;     // physical quadrant: 0=NW 1=NE 2=SE 3=SW (clockwise)
    private final Block[] nerQuadBlock = new Block[4]; // palette block currently applied per quad
    // Where the player was before entering, so F6 can drop them back.
    private float   nerPrevX, nerPrevY, nerPrevZ, nerPrevYaw, nerPrevPitch;
    private boolean nerPrevWaves = true;   // wave-spawner state to restore on exit
    private boolean lastF6 = false;

    // ── CANYON WARP (F5) ───────────────────────────────────────────────────────
    // F5 toggles a teleport to the fixed Shadertoy-style canyon region and back.
    // We drop the player in high above the centre, hover while its chunks stream
    // in, then snap them to the surface (no death-fall).
    private boolean lastF5 = false;
    private boolean atCanyon = false;            // currently warped to the canyon?
    private boolean canyonSettlePending = false; // waiting for canyon chunks to mesh
    private float   canyonReturnX, canyonReturnY, canyonReturnZ; // where to warp back to

    // ═══════════════════════════════════════════════════════════════════════════
    //  "ORBITAL ANNIHILATION"   -  fully-3D cinematic strike (fire with F7)
    // ═══════════════════════════════════════════════════════════════════════════
    // Everything is real 3D geometry + the terrain "lidar" scan shader; the only
    // remaining 2D is nothing (no decals/rain). Phases:
    //   CHARGE  (0.0-3.0): gyroscope torus rings spin up + pulsing core; the world
    //                      darkens; flat ground rings spawn and creep outward.
    //   IMPLODE (3.0-4.2): all rings whip inward (exp ease-in), collide at the core
    //                      -> detonation flash, and the lidar wavefront fires OUT.
    //   SCANOUT (4.2-6.6): wavefront sweeps the terrain (glowing voxel wireframe);
    //                      anti-gravity green embers float up filling the volume.
    //   CARVE   (6.6-7.2): wavefront implodes back -> the crater is carved -> a beat.
    //   LASER   (7.2-11.5): volumetric core beam + helix satellites + 3D shockwave
    //                      + voxel debris + environmental flash + violent shake.
    private boolean orbitalActive  = false;
    private float   orbitalT       = 0f;     // seconds since fire
    private float   orbEpiX, orbEpiY, orbEpiZ; // epicentre (block centre)
    private boolean orbCarved      = false;   // one-shot: crater carved
    private boolean orbStruck      = false;   // one-shot: laser flash + debris burst
    private boolean lastF7         = false;
    private final Matrix4f orbProjView = new Matrix4f(); // last frame's proj*view (unused by 3D path; kept for safety)
    // Live scan parameters driven by the phase timeline (read by the render path).
    private float   orbScanRadiusW  = 0f;   // wavefront radius in WORLD units
    private float   orbScanIntensity= 0f;   // emissive gain for the lidar scan
    private float   orbFlashAmt     = 0f;   // environmental white flash (0..~1.7)
    private boolean orbDark         = false;// true = real lighting blackout (sun/ambient/sky -> black)
    private com.leaf.game.render.Shader bloomShader = null; // searing-bloom post-process
    /** Directional sun/moon shadow map (see render/ShadowMap.java). */
    private com.leaf.game.render.ShadowMap shadowMap = null;
    /** Final full-screen pass: HDR bloom pyramid + colour grade (see render/PostFx.java). */
    private com.leaf.game.render.PostFx postFx = null;
    private com.leaf.game.render.Shader skyShader   = null; // procedural day/night sky
    private com.leaf.game.render.Shader moonShader  = null; // 3D moon sphere
    private com.leaf.game.render.Mesh   moonMesh    = null;
    private com.leaf.game.render.Shader constellShader = null;
    private int  constellVao = 0, constellVbo = 0;
    private int  constellLineCount = 0;
    boolean showConstellations = false;  // toggle with F2
    /** /hud toggles this: hides every HUD element for clean screenshots / trailer capture. */
    boolean hudHidden = false;
    boolean showSnowAtmos      = true;   // toggle with '='
    private boolean lastConstellKey    = false;
    private boolean lastSnowToggleKey  = false;
    String  constellName       = null;   // name to show in HUD
    // 3D effect meshes (unit-sized, built once, drawn emissive with per-object MVP).
    private com.leaf.game.render.Mesh orbTorus, orbSphere, orbCyl, orbCube;
    private com.leaf.game.render.Mesh radarFan;   // flat afterglow wedge (angular bright->0 fade)
    private Shader meteorShader = null;
    private MeteorSystem meteorSystem = null;
    /** Lightweight 3D particles for embers (float up) and debris (burst out). */
    private static final class OrbParticle {
        float x, y, z, vx, vy, vz, life, maxLife, size, r, g, b;
    }
    private final java.util.List<OrbParticle> orbParticles = new java.util.ArrayList<>();
    // Phase boundaries (seconds)
    private static final float ORB_CHARGE = 3.0f, ORB_IMPLODE = 4.2f, ORB_SCANOUT = 6.6f,
                               ORB_CARVE  = 7.2f, ORB_LASER   = 7.6f, ORB_END     = 11.5f;
    private static final float ORB_DARK_START = 0.6f;  // blackout begins shortly after fire
    private static final int   ORB_CRATER_R   = 12;    // crater radius (blocks)
    // Tinnitus post-effect (runs after ORB_END, independent of orbitalActive).
    private float  tinnitusTimer = 0f;
    private static final float TINNITUS_DUR = 7.0f;   // seconds

    // ═══════════════════════════════════════════════════════════════════════════
    //  "THE WORLD"  -  time-stop domain (F8)
    // ═══════════════════════════════════════════════════════════════════════════
    // An expanding sphere of photographic-negative reality bursts from the player's
    // feet; everything inside is inverted + shifted to electric blue, enemies and
    // their projectiles freeze, then the domain collapses back. Pure spectacle.
    private boolean timeStopActive = false;
    /** Seconds until THE WORLD can stop time again (HUD draws the slot wheel). */
    float timeStopCooldown = 0f;
    private float   timeStopT      = 0f;
    private float   tsCenterX, tsCenterY, tsCenterZ;   // anchored at activation
    private float   tsRadiusNow    = 0f;               // live radius (world units)
    private boolean lastF8         = false;
    private boolean lastF4         = false;            // meteor storm edge-detect
    // ── RADAR SWEEP (F10)  -  one-shot scan over the REAL world ────────────────────
    // A 3D radar scope projects onto the terrain (rings + spokes + rotating sweep
    // arm with an opaque->transparent afterglow), enemies light up in wireframe and
    // "ping" as the arm passes them, then it all fades back to normal.
    private boolean vlActive   = false;
    private float   vlT        = 0f;          // seconds into the scan
    private float   vlCx, vlCy, vlCz;         // scope origin (player position at trigger)
    private float   vlRadiusNow = 0f;         // scope radius (world units)
    private float   vlAmountNow = 0f;         // 0->1->0 fade envelope
    private float   vlSweepNow  = 0f;         // sweep-arm angle (radians)
    private boolean lastF10     = false;
    private boolean lastRMB     = false;  // tracks RMB edge for ability-weapon one-shots
    private static final float VL_MAXR   = 200f;   // scope reach (blocks)
    private static final float VL_RAMP   = 1.0f;   // fade-in (s)
    private static final float VL_HOLD   = 6.0f;   // active scanning (s)
    private static final float VL_RETURN = 1.5f;   // fade-out (s)
    private static final float VL_END    = VL_RAMP + VL_HOLD + VL_RETURN;
    private static final float VL_SWEEP_SPEED = 3.14159f;  // rad/s (~1 rotation / 2 s)

    // ── CHOCOLATE DISCO GRID ('.' key) ─────────────────────────────────────────
    static final int   CD_ROWS    = 9;
    static final int   CD_COLS    = 9;
    static final int   CD_CELL    = 3;     // blocks per cell side (27×27 footprint)
    static final int   CD_SPAN    = CD_ROWS * CD_CELL;   // 27  -  full footprint
    static final float CD_HALF    = CD_SPAN * 0.5f;      // 13.5
    static final float CD_LW      = 0.16f; // grid-line glow width
    static final float CD_WALL    = 5.0f;  // wireframe box height
    static final float CD_SPAWN   = 0.22f; // spawn-in animation seconds (snappy)
    static final float CD_DET_DUR = 0.32f; // detonation flash duration (s)  -  quick
    static final float CD_RING_DUR = 0.6f; // ground shockwave ring lifetime (s)
    static final int   CD_CRUSH_H = 22;    // blocks above grid crushed on detonation
    static final float CD_LIFT    = 0.08f; // glow lift above terrain (anti z-fight)

    boolean cdActive   = false;
    boolean showDiscoUI = false;
    float   cdSpawnT   = 0f;               // 0->1 spawn animation (then negative = despawn countdown)
    private boolean lastDisco  = false;
    float   cdGridX, cdGridY, cdGridZ;     // world-space grid centre (Y = aim point)
    final boolean[][] cdMarked  = new boolean[CD_ROWS][CD_COLS];
    final float[][]   cdDetT    = new float[CD_ROWS][CD_COLS];    // >0 = flashing
    final float[][]   cdRingT   = new float[CD_ROWS][CD_COLS];    // >0 = shockwave ring alive
    final boolean[][] cdBlasted = new boolean[CD_ROWS][CD_COLS];  // blocks already carved
    int   cdHoverR = -1, cdHoverC = -1;
    private com.leaf.game.render.Mesh cdMesh = null;    // rebuilt when state changes
    boolean cdMeshDirty = true;

    // ── DEPRIVATION DOMAIN (Water God Stance)  -  ['] key ─────────────────────────
    // Absolute stillness. The player locks in place. Every entity that moves inside
    // the golden hemisphere is instantly counter-struck with a lingering golden thread
    // and a dimensional-slash ring. Thread web builds over time. All configurable via
    // GameConfig (depRadius, depDuration, depDamage, …).
    boolean depActive    = false;   // (HUD reads it for the slot glow)
    private float   depT         = 0f;   // seconds since domain activated
    float   depCooldown  = 0f;   // seconds until usable again (HUD draws the slot wheel)
    private float   depStrike    = 0f;   // 0->1 strike flash (decays 6×/sec); fed to shader
    private boolean lastApostr   = false;
    private float   depX, depY, depZ;   // player position frozen at activation
    private float   depDetectTimer = 0f; // accumulates until DEP_TICK, then samples
    /** Previous positions of each enemy for velocity estimation. int = System.identityHashCode. */
    private final java.util.HashMap<Integer, org.joml.Vector3f> depPrevPos = new java.util.HashMap<>();
    /** Active slash crescents: {x,y,z, yaw,pitch,roll, age,life, startScale,endScale, sweep}. */
    private final java.util.ArrayList<float[]> depSlashFx = new java.util.ArrayList<>();
    /** Voxel gib debris from sliced enemies: {x,y,z, vx,vy,vz, size, age,life}. */
    private final java.util.ArrayList<float[]> depGibs = new java.util.ArrayList<>();
    /** Anime sword-sweep crescent mesh (unit, in local XY plane; built once). */
    private com.leaf.game.render.Mesh depCrescent = null;
    /** Phantom sword silhouette mesh (built once). */
    private com.leaf.game.render.Mesh depBlade = null;
    /** Glowing wireframe half-dome (meridians + parallels) so the field's shape reads. */
    private com.leaf.game.render.Mesh depDomeWire = null;
    /** Solid hemisphere for the hex force-field shader (domeMode). */
    private com.leaf.game.render.Mesh depDomeSolid = null;
    /** Real hexagon-cage geometry (solid glowing tubes)  -  the actual wireframe dome. */
    private com.leaf.game.render.Mesh depHexDome = null;
    /** Thin gold ground ring where the cage meets the floor. */
    private com.leaf.game.render.Mesh depBaseRing = null;
    private float   depAuraEmit  = 0f;   // emit timer for the vigorous slash aura
    private float   depSlashSnd  = 0f;   // timer for ambient slash swishes
    // ── Sliced corpses: the actual enemy model chopped into 8 octants that scatter ──
    private final java.util.ArrayList<DepCorpse> depCorpses = new java.util.ArrayList<>();
    private static final String[] SLASH_ALL = {
        "sword_slash", "sword_slash2", "sword_slash3",
        "sword_slash_short", "sword_slash_short2", "sword_slash_short3", "sword_slash_long" };
    private static final String[] SLASH_SHORT = {
        "sword_slash_short", "sword_slash_short2", "sword_slash_short3" };
    // ── Bezier head-turn: smoothly rotate the camera to face each slashed enemy ──
    private boolean depTurning = false;
    private float   depTurnT = 0f;
    private float   depTurnStartYaw, depTurnStartPitch, depTurnTargetYaw, depTurnTargetPitch;

    /** A frozen enemy model captured at the instant it was sliced, flying apart in 8 octants. */
    private static final class DepCorpse {
        final com.leaf.game.anim.AnimModel model;
        final java.util.Map<String, Matrix4f> pose;
        final float px, py, pz, faceY, sx, sy, sz;
        final org.joml.Vector3f center;   // body-frame centre the cuts pass through
        final float size;                 // body-frame extent (for scatter distance)
        final long  seed;
        float age = 0f;
        final float life;
        DepCorpse(com.leaf.game.anim.AnimModel model, java.util.Map<String, Matrix4f> pose,
                  float px, float py, float pz, float faceY, float sx, float sy, float sz,
                  org.joml.Vector3f center, float size, float life, long seed) {
            this.model = model; this.pose = pose;
            this.px = px; this.py = py; this.pz = pz; this.faceY = faceY;
            this.sx = sx; this.sy = sy; this.sz = sz;
            this.center = center; this.size = size; this.life = life; this.seed = seed;
        }
    }

    // ── GENERAL ABILITY-FX SYSTEM ───────────────────────────────────────────────
    // Transient additive 3D effects shared by many abilities (slash crescents,
    // shockwave rings, burst spheres, energy bolts). Rendered in the world pass
    // through the bloom FBO. Spawn via fxSlash / fxRing / fxBurst / fxBolt.
    private static final int FX_CRESCENT = 0, FX_RING = 1, FX_BURST = 2, FX_BOLT = 3,
                             FX_SPARK = 4;   // local-only impact spark (never broadcast)
    private static final class Fx {
        int   type;
        float x, y, z;          // world position
        float dx, dy, dz;       // crescent facing / ring axis / bolt direction
        float roll, sweep;      // crescent roll + arc sweep
        float age, life;
        float s0, s1;           // start/end scale (or radius)
        float r, g, b;          // emissive colour (HDR)
        float vx, vy, vz;       // FX_SPARK velocity (blocks/s)
        boolean ambient;        // true = decorative living-world particle (does NOT
                                // trigger the full-screen bloom FBO; see doFxBloom)
        Fx(int type, float x, float y, float z) { this.type = type; this.x = x; this.y = y; this.z = z; }
    }
    private final java.util.ArrayList<Fx> fxList = new java.util.ArrayList<>();

    // ── COMBAT FEEDBACK: sparks, floating damage numbers, hit-stop ────────────
    // Fed by core.CombatFeedback (published from Enemy.applyDamage), so every
    // ability gets the same impact language without knowing about it.
    private static final class DamageNumber {
        int enemyId; float x, y, z; float amount; float age; float pop; boolean killed;
    }
    private final java.util.ArrayList<DamageNumber> damageNumbers = new java.util.ArrayList<>();
    private final java.util.HashMap<Integer, Float> sparkCooldown = new java.util.HashMap<>();
    private float hitStopCooldown = 0f;
    private com.leaf.game.render.Mesh fxGlowSphere;   // sphere WITH normals, for soft glow orbs
    /** While true, FX spawned via fxBolt/fxRing/etc. are tagged decorative (ambient)
     *  so they don't trigger the full-screen bloom FBO. Set around the ambience loop. */
    private boolean fxSpawnAmbient = false;

    // ── METEOR STORM ───────────────────────────────────────────────────────────
    /** A falling meteorite: a glowing hot core + comet trail that carves a crater. */
    private static final class Meteor {
        final Vector3f pos = new Vector3f();
        final Vector3f vel = new Vector3f();
        float size;
        boolean alive = true;
        final java.util.ArrayDeque<float[]> trail = new java.util.ArrayDeque<>();
    }
    private final java.util.ArrayList<Meteor> meteors = new java.util.ArrayList<>();
    private float meteorStormTimer = 0f;   // >0 while a storm is actively raining
    private float meteorSpawnTimer = 0f;   // countdown between drops during a storm

    // ── GATLING GUN (held item, hold LMB to rip) ───────────────────────────────
    private float gatlingCooldown = 0f;    // fire-rate timer
    private float gatlingFlash    = 0f;    // muzzle-flash glow timer (viewmodel)
    private float barrelSpin      = 0f;    // spinning-barrel angle (rad)
    private int   gatlingShots    = 0;     // round counter (for sound throttling)
    private com.leaf.game.render.Mesh gunBodyMesh, gunBarrelMesh;   // viewmodel meshes

    // ── DAY / NIGHT CYCLE ──────────────────────────────────────────────────────
    final DayNight dayNight = new DayNight();
    public Telescope telescope = new Telescope();
    private int meteorVao = 0;
    private int meteorVbo = 0;
    // State to smoothly zoom the FOV when aiming the telescope
    private float currentFov = GameConfig.fov;
    public boolean holdingTelescope = false;
    private float mihRamp = 0f;   // 0..1 "Made in Heaven" time-acceleration ramp
    /** World positions of placed TORCH blocks (drive point lights). */
    private final java.util.List<Vector3f> torchPositions = new java.util.ArrayList<>();
    private com.leaf.game.render.Mesh fxCrescentMesh = null;  // gradient slash (white edge -> warm body)
    private com.leaf.game.render.Mesh fxConeMesh = null;      // fiery cone (stone canon charge)
    // Rising-edge detectors so a teleport (blink / seal / substitute / swap) spawns FX once.
    private float lastBlinkFlash = 0f, lastSealFlash = 0f;

    // ── QUANTUM BULLET (',' key)  -  phases through everything, rippling walls ─────
    private static final class QBullet {
        final Vector3f pos, vel;
        float age; final float life;
        boolean wasSolid;
        final java.util.HashSet<Integer> hit = new java.util.HashSet<>();
        QBullet(Vector3f pos, Vector3f vel, float life) {
            this.pos = pos; this.vel = vel; this.life = life;
        }
    }
    private final java.util.ArrayList<QBullet> qbBullets = new java.util.ArrayList<>();
    private boolean lastQuantum = false;
    private float   qbCooldown  = 0f;
    // Screen-space surface warp (distort pass) while a bullet is inside a wall.
    private float qbWarpStrength = 0f;
    private float qbWarpX, qbWarpY, qbWarpZ;          // warp-centre world pos (projected at blit)

    private static final float TS_MAXR   = 220f;   // domain reach (blocks)
    private static final float TS_EXPAND = 1.8f;   // expansion duration (s)
    private static final float TS_SHRINK = 0.9f;   // collapse duration (s)
    // TS_HOLD and TS_END are read from GameConfig at runtime so they can be tuned.
    private float tsHold() { return GameConfig.timeStopHoldSecs; }
    private float tsEnd()  { return TS_EXPAND + tsHold() + TS_SHRINK; }
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
        dayNight.init();
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

            // ── CUTSCENE swallows input: ENTER advances, ESC skips.
            // On the ENDING last slide: double-tap SPACE exits (matches the on-screen hint).
            if (cutscene.isActive()) {
                if (action == GLFW_PRESS) {
                    if (key == GLFW_KEY_ESCAPE) cutscene.skip();
                    else if (key == GLFW_KEY_ENTER || key == GLFW_KEY_KP_ENTER) cutscene.advance();
                    else if (key == GLFW_KEY_SPACE && cutscene.getKind() == CutsceneManager.Kind.ENDING) {
                        cutscene.tapSpaceForEnding();  // double-tap exits ending
                    }
                }
                return;
            }
            // ── EVENT-DRIVEN BACKPACK TOGGLE ──
            if (key == GLFW_KEY_LEFT_ALT && action == GLFW_PRESS && !showChat && !showHelp) {
                backpackUI.toggle();
            }
            
            if (holdingTelescope) {
                if (key == GLFW_KEY_L && action == GLFW_PRESS) telescope.showLabels = !telescope.showLabels;
                if (key == GLFW_KEY_C && action == GLFW_PRESS) telescope.showConstLines = !telescope.showConstLines;
                if (key == GLFW_KEY_A && action == GLFW_PRESS) telescope.showAstroData = !telescope.showAstroData;
                if (key == GLFW_KEY_EQUAL && action == GLFW_PRESS) telescope.fovDeg = Math.max(4f, telescope.fovDeg - 2f);
                if (key == GLFW_KEY_MINUS && action == GLFW_PRESS) telescope.fovDeg = Math.min(20f, telescope.fovDeg + 2f);
            }

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

            // Skip the onboarding tutorial -> jump straight to endless waves.
            if (key == GLFW_KEY_F2 && action == GLFW_RELEASE && !showChat
                    && tutorial != null && tutorial.isActive()) {
                tutorial.skip();
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

            // F6  -  toggle the non-Euclidean "Layered Rooms" anomaly.
            // (Edge is detected in the game loop via lastF6.)
            if (key == GLFW_KEY_F6 && action == GLFW_RELEASE && !showChat) {
                lastF6 = false;
            }

            // F9  -  DEV: instantly clear the current wave / force the next wave
            if (key == GLFW_KEY_F9 && action == GLFW_RELEASE) {
                // Kill all alive enemies so the wave-clear detector fires next tick.
                if (enemyManager != null) enemyManager.getEnemies().forEach(e -> e.alive = false);
                // If already between waves, fast-forward (dismiss card / practice).
                showUnlockCard  = false;
                practiceAbility = null; practiceSteps = null;
                if (enemyManager != null && enemyManager.awaitingNextWave) enemyManager.beginNextWave();
                System.out.println("[DEV] F9  -  skipped wave " + (enemyManager != null ? enemyManager.getWaveNumber() : "?"));
            }
            if (key == GLFW_KEY_F12 && action == GLFW_RELEASE && !showChat) {
                player.useTestMovement = !player.useTestMovement;
                if (player.useTestMovement) {
                    player.testMovement.setEnemyManager(enemyManager);
                    // Inherit current velocity to avoid sudden drops
                    player.testMovement.velocity.y = player.getVelocityY();
                    hintText = "TEST MOVEMENT ENABLED (Parkour Mode)";
                } else {
                    hintText = "TEST MOVEMENT DISABLED (Survival Mode)";
                }
                hintTimer = 3f;
            }

            // ── FLAPPY BIRD MODE (Tilde key ` - Dynamic Toggle) ──
            // (Locked during the finale's bird trial — the crystal insists.)
            if (key == GLFW_KEY_GRAVE_ACCENT && action == GLFW_PRESS && !showChat
                    && (finale == null || !finale.isFlappyTrial())) {
                if (player.useTestMovement && player.testMovement.state == TestMovementController.State.FLAPPY) {
                    // Already in Flappy Mode -> EXIT and return to standard world
                    player.useTestMovement = false;
                    player.testMovement.state = TestMovementController.State.AIRBORNE;
                    player.health = player.maxHealth; // Ensure health is full

                    // Restore your exact pre-flappy position and camera angles
                    player.position.set(flappyPrevX, flappyPrevY, flappyPrevZ);
                    player.setVelocityY(0f);

                    if (enemyManager != null) {
                        enemyManager.wavesEnabled = flappyPrevWaves;
                    }

                    // Clear the pipe chunks from memory so the normal terrain regenerates
                    world.clearAllChunks();

                    hintText = "Returned to standard world";
                    hintTimer = 3f;
                } else {
                    // Enter Flappy Mode -> Save current world position & parameters
                    flappyPrevX = player.position.x;
                    flappyPrevY = player.position.y;
                    flappyPrevZ = player.position.z;
                    flappyPrevWaves = enemyManager.wavesEnabled;

                    player.debugMode = false;   // no skim/soar inside the bird
                    player.useTestMovement = true;
                    player.testMovement.state = TestMovementController.State.FLAPPY;

                    // Heal to full on start
                    player.health = player.maxHealth;

                    // Initialize starting Flappy states
                    player.testMovement.flappyScore = 0;
                    player.testMovement.lastPassedPipeX = 5010;
                    player.testMovement.flappyWaitingToStart = true;

                    player.position.set(4980f, 230f, 5000f);
                    player.testMovement.velocity.set(0f, 0f, 0f);

                    // Clear survival chunks so the Flappy corridor loads
                    world.clearAllChunks();

                    hintText = "FLAPPY BIRD 3D - SPACE to flap, steer with WASD, don't touch the pipes!";
                    hintTimer = 5f;
                }
            }
            // ── DEVELOPER CHEAT KEY (Backspace) ──
            if (key == GLFW_KEY_BACKSPACE && action == GLFW_PRESS && !showChat) {
                // 1. Skip onboarding tutorial
                if (tutorial != null && tutorial.isActive()) {
                    tutorial.skip();
                }
                // 2. Clear any active tutorial practice sessions
                if (practiceAbility != null) {
                    endPractice();
                }
                // 3. Force wave spawning onto the normal loop
                enemyManager.wavesEnabled = true;
                if (enemyManager.awaitingNextWave) {
                    enemyManager.beginNextWave();
                }

                // 4. Grant absolute mastery (unlock every single ability)
                player.progression.unlockAll();

                // 5. Send a funny chat message
                chatHistory.add("[System]: UNLIMITED POWER! All tutorials obliterated, all abilities unlocked.");

                // 6. Play a satisfying deep teleport boom
                com.leaf.game.core.AudioManager.play("teleport", 1.5f, 0.7f);
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
            if (!networkInitialized || isPreloading || showChat || showNoiseViewer || isPaused || showHelp || showDiscoUI || backpackUI.isOpen) return;

            if (button == GLFW_MOUSE_BUTTON_LEFT) {
                // Chocolate Disco: LMB marks/unmarks the hovered grid cell.
                // Consumes the click so it doesn't break a block at the same time.
                if (cdActive && action == GLFW_PRESS && cdHoverR >= 0 && cdHoverC >= 0) {
                    cdMarked[cdHoverR][cdHoverC] = !cdMarked[cdHoverR][cdHoverC];
                    if (!cdMarked[cdHoverR][cdHoverC]) { cdDetT[cdHoverR][cdHoverC] = 0f; cdBlasted[cdHoverR][cdHoverC] = false; }
                    cdMeshDirty = true;
                    return; // consumed
                }

                // While piloting the stand drone OR auto-aiming at an enemy, LMB is
                // consumed by the stand  -  block normal block-breaking.
                // While Kamui is active, LMB drives the absorption system instead.
                boolean standConsumedLMB = player.stand.isInStandPerspective()
                        || player.stand.autoAimedThisFrame;
                boolean kamuiConsumedLMB = player != null && player.abilities.isKamui;
                // Any held weapon turns LMB into FIRE, never break  -  one button for
                // every tool keeps the game straightforward.
                Block heldLmb = hotbar[selectedSlot];
                boolean gunHeld   = heldLmb == Block.GATLING_GUN
                        || heldLmb == Block.WPN_SNIPER  || heldLmb == Block.WPN_ORBITAL
                        || heldLmb == Block.WPN_TIMESTOP|| heldLmb == Block.WPN_STONE_CANNON
                        || heldLmb == Block.WPN_DEPRIVATION;
                boolean scopeHeld = heldLmb == Block.TELESCOPE;
                if (!standConsumedLMB && !kamuiConsumedLMB && !gunHeld && !scopeHeld) {
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
                if (lastTarget != null && lastTarget.hit && selectedBlock != Block.AIR
                        && selectedBlock != Block.GATLING_GUN) {   // the gun is a weapon, not a block
                    if (!playerOccupies(lastTarget.placeX, lastTarget.placeY, lastTarget.placeZ) &&
                            !remotePlayerOccupies(lastTarget.placeX, lastTarget.placeY, lastTarget.placeZ)) {
                        // Torches are an infinite light tool  -  never consumed.
                        if (selectedBlock == Block.TORCH || inventory.useBlock(selectedBlock)) {
                            world.setBlock(lastTarget.placeX, lastTarget.placeY, lastTarget.placeZ, selectedBlock);
                            world.rebuildChunkAt(lastTarget.placeX, lastTarget.placeY, lastTarget.placeZ);
                            if (selectedBlock == Block.TORCH)
                                torchPositions.add(new Vector3f(lastTarget.placeX + 0.5f,
                                        lastTarget.placeY + 0.5f, lastTarget.placeZ + 0.5f));
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
        UiFonts.load();          // Rajdhani HUD + Cinzel display type (before the font atlas is built)
        imguiGlfw.init(window, true);
    }

    private void setupMouseLook(Camera camera) {
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);

        glfwSetCursorPosCallback(window, (win, xpos, ypos) -> {
            if (!networkInitialized || isPreloading || showDebug || showChat || showNoiseViewer || isPaused || showHelp || backpackUI.isOpen)
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

            // Lock camera mouse look during 2D Flappy side-scrolling!
            boolean isFlappySide = player.useTestMovement && player.testMovement.state == TestMovementController.State.FLAPPY && player.testMovement.flappySideView;

            if (!player.isSmashing() && !player.abilities.isCharging()
                    && !isChargingStoneCanon && !isFlappySide) {
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
        // Fresh multiplayer-combat state each run (stale proxy/troops never leak in).
        mpProxy = null;
        mySummons.clear();
    }

    /**
     * Debug "arm everything" helper (used by {@code /showcase}-style testing).
     * NOT wired to PLAY anymore  -  PLAY runs the natural teach-and-reward
     * progression so the god-tier weapons stay an earned surprise.
     */
    public void enterPlaytestMode() {
        playtestMode = true;
        showcaseMode = true;
        immunityTimer = 99999f;
        player.progression.unlockAll();

        hotbar[0] = Block.GATLING_GUN;
        hotbar[1] = Block.WPN_SNIPER;
        hotbar[2] = Block.WPN_ORBITAL;
        hotbar[3] = Block.WPN_TIMESTOP;
        hotbar[4] = Block.WPN_STONE_CANNON;
        hotbar[5] = Block.TORCH;
        hotbar[6] = Block.TELESCOPE;
        hotbar[7] = Block.GRAPPLING_HOOK;
        hotbar[8] = Block.GRASS;
        for (Block b : Block.values()) {
            if (b != Block.AIR) inventory.addBlockAmount(b, 999);
        }
        selectedSlot = 1;

        if (tutorial != null) tutorial.skip();
        practiceQueue.clear();
        practiceAbility = null;
        practiceSteps   = null;
        practiceStepDone = false;
        practiceCelebration = 0f;
        showUnlockCard = false;

        if (enemyManager != null) {
            enemyManager.wavesEnabled    = false;
            enemyManager.freeExploreMode = true;
        }
    }

    /**
     * Map a just-unlocked ability to the physical weapon block it grants, and drop
     * that weapon into its DEDICATED hotbar slot so the player SEES their new power
     * appear in a clean, predictable place (matching the /showcase layout). This is
     * what makes the god-tier reveals feel tangible and exciting.
     * Returns the granted block (or null if the ability has no hotbar weapon).
     */
    public Block grantWeaponForAbility(Progression.Ability a) {
        // slot -1 = no hotbar weapon for this ability (key-activated, not an item).
        int slot = -1;
        Block w = null;
        switch (a) {
            case SNIPE       -> { w = Block.WPN_SNIPER;       slot = 0; }
            case GATLING     -> { w = Block.GATLING_GUN;      slot = 1; }
            case ORBITAL     -> { w = Block.WPN_ORBITAL;      slot = 2; }
            case DOMAIN      -> { w = Block.WPN_TIMESTOP;     slot = 3; }
            case STONE_CANON -> { w = Block.WPN_STONE_CANNON; slot = 4; }
            case DEPRIVATION -> { w = Block.WPN_DEPRIVATION;  slot = 5; }  // ' key still works too
            default          -> { return null; }
        }
        if (slot >= 0 && w != null) {
            inventory.addBlockAmount(w, 1);
            hotbar[slot] = w;
        }
        // Queue the fancy reveal card. Back-to-back grants (Orbital -> The World at
        // the tower) each get their full 5.5 s on screen instead of overwriting.
        if (weaponRevealTimer > 0f) {
            weaponRevealQueue.add(a);
        } else {
            weaponRevealAbility = a;
            weaponRevealSlot    = slot;
            weaponRevealTimer   = 5.5f;
        }
        return w;
    }

    /** Hotbar slot a granted ability's weapon lands in (-1 = key ability, no item). */
    private static int weaponSlotFor(Progression.Ability a) {
        return switch (a) {
            case SNIPE -> 0; case GATLING -> 1; case ORBITAL -> 2;
            case DOMAIN -> 3; case STONE_CANON -> 4; case DEPRIVATION -> 5;
            default -> -1;
        };
    }

    /**
     * 0..1 "ready" fraction for the TRY IT detector (1 = ready, drops on use).
     * Abilities without a cooldown report 1 and rely on the boolean detector.
     */
    private float tryFracFor(Progression.Ability a) {
        return switch (a) {
            case SLASH      -> player.attacks.getMeleeCooldownFrac();
            case DASH       -> player.abilities.getDashCooldownFrac();
            case QUAGMIRE   -> quagmireCooldown <= 0f ? 1f
                    : 1f - quagmireCooldown / GameConfig.quagmireCooldown;
            case LIGHTNING  -> player.lightning.getCooldownFrac();
            case GRAB       -> player.grab.getCooldownFrac();
            case BLINK      -> player.abilities.getBlinkCooldownFrac();
            case PILLAR     -> player.abilities.getPillarCooldownFrac();
            case CANNONBALL -> player.abilities.getCannonCooldownFrac();
            case SNIPE      -> player.attacks.getSnipeIconFrac();
            default         -> 1f;
        };
    }

    /** Live "watched value" for a step (snapshot-dip detectors). */
    private float tryLiveFrac(TryStep s) {
        if (s.detect == TRY_SEAL_WARP) return player.seals.getTeleportCooldownFrac();
        return tryFracFor(s.ability);
    }

    /** True the moment the step's goal is achieved. */
    private boolean tryStepDone(TryStep s, float liveFrac) {
        return switch (s.detect) {
            case TRY_STAND_OUT   -> player.stand.isDeployed();
            case TRY_STAND_PILOT -> player.stand.isInStandPerspective();
            case TRY_SEAL_PLACED -> player.seals.getSealCount() > 0
                                 || !player.seals.inFlightSeals.isEmpty();
            case TRY_SUB_PRIMED  -> substitutePrimed;
            case TRY_FLYING      -> player.debugMode;   // debugMode == flight mode
            case TRY_INFO_ONLY   -> false;              // timeout advances it
            default              -> liveFrac < trySnapshot - 0.15f;  // cooldown dip
        };
    }

    /**
     * Build the guided TRY IT steps for a freshly unlocked ability.
     * SLASH and DASH are skipped  -  the opening tutorial already taught them.
     * Hard-to-explain powers get short multi-step walkthroughs.
     */
    private void queueTryStepsFor(Progression.Ability a) {
        switch (a) {
            case SLASH, DASH -> { /* already taught in the opening tutorial */ }
            case QUAGMIRE -> tryQueue.add(new TryStep(a, "M",
                    "MUD WAVE   -   press  M",
                    "Aim at an enemy on the ground   -   the mud TRAPS them.",
                    TRY_COOLDOWN_DIP, 9f));
            case LIGHTNING -> tryQueue.add(new TryStep(a, "U",
                    "LIGHTNING   -   press  U",
                    "A full-power bolt strikes the enemy in your crosshair.",
                    TRY_COOLDOWN_DIP, 9f));
            case GRAB -> tryQueue.add(new TryStep(a, "O",
                    "GRAB & SLAM   -   press  O",
                    "GET CLOSE to an enemy first   -   then grab and SLAM them!",
                    TRY_COOLDOWN_DIP, 12f));
            case BLINK -> tryQueue.add(new TryStep(a, "E",
                    "BLINK   -   press  E",
                    "You teleport to WHERE YOU ARE LOOKING. Aim far, then blink.",
                    TRY_COOLDOWN_DIP, 9f));
            case PILLAR -> tryQueue.add(new TryStep(a, "K",
                    "STONE PILLAR   -   press  K",
                    "Stand on ROCKY / STONE ground   -   a pillar launches you up!",
                    TRY_COOLDOWN_DIP, 12f));
            case CANNONBALL -> tryQueue.add(new TryStep(a, "HOLD  G",
                    "CANNONBALL   -   hold  G",
                    "Hold to charge... release to LAUNCH yourself like a cannonball!",
                    TRY_COOLDOWN_DIP, 12f));
            case STAND -> {
                tryQueue.add(new TryStep(a, "X",
                        "COMBAT DRONE   -   press  X",
                        "Your drone deploys beside you and fires at enemies on its own.",
                        TRY_STAND_OUT, 12f));
                tryQueue.add(new TryStep(a, "TAB",
                        "PILOT IT   -   press  TAB",
                        "See through the drone's eyes. Press TAB again to come back.",
                        TRY_STAND_PILOT, 9f));
            }
            case SEAL -> {
                tryQueue.add(new TryStep(a, "H",
                        "MINATO'S SEAL   -   press  H",
                        "Throw a glowing seal   -   it sticks wherever it lands.",
                        TRY_SEAL_PLACED, 12f));
                tryQueue.add(new TryStep(a, "B",
                        "NOW press  B",
                        "You TELEPORT straight to your seal. Place them anywhere!",
                        TRY_SEAL_WARP, 9f));
            }
            case SUBSTITUTE -> {
                tryQueue.add(new TryStep(a, "HOLD  V",
                        "SUBSTITUTE   -   hold  V",
                        "Keep holding   -   you are braced for the next enemy hit.",
                        TRY_SUB_PRIMED, 9f));
                tryQueue.add(new TryStep(a, "HOLD  V",
                        "NOW LET AN ENEMY HIT YOU",
                        "The hit is absorbed   -   you blink away and your decoy EXPLODES.",
                        TRY_INFO_ONLY, 6f));
            }
            default -> { /* weapons get the WEAPON FORGED card instead */ }
        }
    }

    /**
     * Central death handler  -  the SINGLE place a death is triggered, so every damage
     * source (enemy hits, fall damage, PvP/troop relay, the void) dies the same way and
     * respawns at the same consistent spawn point via the revival flow. Idempotent: a
     * no-op if a death is already in progress.
     */
    /**
     * Central death handler. Routes Flappy mode to the instant arcade menu,
     * and normal survival mode to the cinematic revival flow.
     */
    private void triggerDeath() {
        if (showDeathScreen || deathCutscenePending) return;   // already dying
        player.health = 0f;

        // Death VFX Broadcast
        float px = player.position.x, py = player.position.y, pz = player.position.z;
        fxBurst(px, py + 1.0f, pz, 0.5f, 4.6f, 0.50f, 3.2f, 0.9f, 0.55f);
        fxRing (px, py + 0.6f, pz, 0.5f, 7.5f, 0.62f, 2.9f, 0.45f, 0.35f);
        for (int i = 0; i < 8; i++) {
            double a = i / 8.0 * Math.PI * 2;
            fxBolt(px, py, pz, (float) Math.cos(a) * 0.4f, 1f, (float) Math.sin(a) * 0.4f,
                    4.5f, 0.12f, 0.55f, 3.0f, 0.8f, 0.5f);
        }
        if (network != null && network.connected) network.sendDeath(px, py, pz);

        // ── FINALE OVERRIDES — the trial handles its own deaths ──
        if (finale != null && finale.isFlappyTrial()) {
            // Crashed during THE CRYSTAL MOCKS YOU: instant retry, no menu.
            ScreenEffectManager.INSTANCE.flash(1f, 1f, 1f, 0.5f, 0.2f);
            finaleRestartFlappy();
            return;
        }
        if (finale != null && finale.active()) {
            // The finale owns every death while it runs: trial deaths become the
            // wake-up sequence; deaths during the ending cinematics are ignored
            // (you are past dying by then).
            finale.onPlayerDeath();
            return;
        }

        // ── FLAPPY BIRD ARCADE OVERRIDE ──
        if (player.useTestMovement && player.testMovement.state == TestMovementController.State.FLAPPY) {
            showDeathScreen = true; // Instantly show the GAME OVER menu. Skip the cinematic!
            return;
        }

        // ── NORMAL SURVIVAL CINEMATIC ──
        AudioManager.stopContinuous("kamui_duration");
        AudioManager.stopContinuous("kamui_distortion");
        deathScreenLines = RunRecords.INSTANCE.recordDeath(
                enemyManager.getWaveNumber(), (float) org.lwjgl.glfw.GLFW.glfwGetTime());

        deathCutscenePending = true;

        if (RunRecords.INSTANCE.wasKamuiAwakenDeath()) {
            player.progression.grantKamui();
            kamuiJustUnlockedThisRevival = true;
            cutscene.startKamuiAwaken();
        } else {
            deathCutscenePending = false;
            showDeathScreen = true;
        }
        ScreenEffectManager.INSTANCE.desaturate(0.7f, 1.5f);
    }

    /**
     * Multiplayer DUEL setup, run BEFORE abilities each frame so PvP hits land reliably:
     *  • keeps the arena clear of unsynced wave mobs (this mode is duel + summon-troops),
     *  • creates/positions the opponent hitbox proxy from the FRESHEST synced position,
     *    so any enemy-targeting ability that fires this frame hits the right spot.
     * The damage the proxy soaks up is read + relayed at the end of the frame.
     */
    private void updateMpProxyEarly() {
        if (network == null || !network.connected || enemyManager == null) return;
        // No wave mobs in PvP (they'd be unsynced); troops live in mySummons, not here.
        enemyManager.wavesEnabled = false;
        enemyManager.getEnemies().removeIf(e -> e != mpProxy);
        if (mpProxy == null) {
            mpProxy = enemyManager.spawnAt(network.remoteX, network.remoteY, network.remoteZ,
                    com.leaf.game.entity.Enemy.Type.DUMMY);
        }
        mpProxy.position.set(network.remoteX, network.remoteY, network.remoteZ);
        mpProxy.alive = true;
    }

    /** Begin the next queued practice session, or start the next wave if the queue is empty. */
    private void startNextPractice() {
        Progression.Ability next = practiceQueue.poll();
        if (next != null && player.progression.isUnlocked(next)) {
            java.util.List<AbilityPractice.Step> steps = AbilityPractice.forAbility(next);
            if (steps.isEmpty()) {
                // No tutorial for this ability  -  keep draining the queue.
                startNextPractice();
                return;
            }
            practiceAbility    = next;
            practiceSteps      = steps;
            practiceStepIndex  = 0;
            practiceStepAge    = 0f;
            practiceStepDone   = false;
            practiceCelebration = 0f;
            practiceCtx.win      = this;
            practiceCtx.flag     = false;
            practiceCtx.snapshot = 0f;
            practiceCtx.counter  = 0;
            // Kill live enemies so the player can focus, pause spawning.
            enemyManager.getEnemies().forEach(e -> { if (e.type != Enemy.Type.DUMMY) e.alive = false; });
            enemyManager.wavesEnabled = false;
            // Run the first step's onEnter.
            AbilityPractice.Step first = steps.get(0);
            practiceCtx.required = first.required;
            if (first.onEnter != null) {
                practiceCtx.stepAge = 0f;
                first.onEnter.accept(practiceCtx);
            }
        } else {
            endPractice();
        }
    }

    /** Advance to the next practice step, or finish if all done. */
    private void advancePracticeStep() {
        practiceStepIndex++;
        if (practiceStepIndex >= practiceSteps.size()) {
            endPractice();
            return;
        }
        practiceStepAge     = 0f;
        practiceStepDone    = false;
        practiceCelebration = 0f;
        practiceCtx.flag     = false;
        practiceCtx.snapshot = 0f;
        practiceCtx.counter  = 0;
        AbilityPractice.Step step = practiceSteps.get(practiceStepIndex);
        practiceCtx.required = step.required;
        if (step.onEnter != null) step.onEnter.accept(practiceCtx);
    }

    /** Finish all practice, remove dummies, resume the wave. */
    private void endPractice() {
        practiceAbility = null;
        practiceSteps   = null;
        // Remove practice dummies.
        enemyManager.getEnemies().forEach(e -> { if (e.type == Enemy.Type.DUMMY) e.alive = false; });
        enemyManager.beginNextWave();
        enemyManager.wavesEnabled = true;
        lastPracticeEnter = true;
    }

    private void loop() {
        // ── MAC OS CRASH FIX ──────────────────────────────────────────────
        glfwPollEvents();
        try { Thread.sleep(150); } catch (InterruptedException e) {}
        // ──────────────────────────────────────────────────────────────────
        int[] ww = new int[1], wh = new int[1];
        glfwGetWindowSize(window, ww, wh);
        glfwGetFramebufferSize(window, fw, fh);

        GL.createCapabilities();
        imguiGl3.init("#version 330");

        glEnable(org.lwjgl.opengl.GL32.GL_PROGRAM_POINT_SIZE);

        com.leaf.game.anim.ModelRenderer.init();
        com.leaf.game.anim.AnimModel enemyAnimModelLoaded = com.leaf.game.anim.AnimModel.loadFromClasspath("enemy_basic");
        if (enemyAnimModelLoaded != null) this.enemyAnimModel = enemyAnimModelLoaded;
        com.leaf.game.anim.AnimModel slimeAnimModelLoaded = com.leaf.game.anim.AnimModel.loadFromClasspath("slime");
        if (slimeAnimModelLoaded != null) this.slimeAnimModel = slimeAnimModelLoaded;
        com.leaf.game.anim.AnimModel golemAnimModelLoaded = com.leaf.game.anim.AnimModel.loadFromClasspath("golem");
        if (golemAnimModelLoaded != null) this.golemAnimModel = golemAnimModelLoaded;
        com.leaf.game.anim.AnimModel lavaSlimeLoaded = com.leaf.game.anim.AnimModel.loadFromClasspath("lava_slime");
        if (lavaSlimeLoaded != null) this.lavaSlimeAnimModel = lavaSlimeLoaded;
        com.leaf.game.anim.AnimModel infernoTowerLoaded = com.leaf.game.anim.AnimModel.loadFromClasspath("inferno_tower");
        if (infernoTowerLoaded != null) this.infernoTowerAnimModel = infernoTowerLoaded;

        glEnable(GL_DEPTH_TEST);
        glClearColor(0.5f, 0.7f, 0.9f, 1.0f);

        Shader shader = new Shader("src/main/resources/shaders/vertex.glsl", "src/main/resources/shaders/fragment.glsl");
        shadowMap = new com.leaf.game.render.ShadowMap(GameConfig.shadowMapSize, GameConfig.shadowRadius, 260f);
        shadowMap.init();
        shader.bind();
        shader.setUniform("shadowMap", com.leaf.game.render.ShadowMap.TEXTURE_UNIT);
        shader.unbind();
        KeyBindings.verify();

        distortShader = new com.leaf.game.render.Shader("src/main/resources/shaders/distort_vertex.glsl", "src/main/resources/shaders/distort_fragment.glsl");
        bloomShader = new com.leaf.game.render.Shader("src/main/resources/shaders/distort_vertex.glsl", "src/main/resources/shaders/bloom_fragment.glsl");
        skyShader = new com.leaf.game.render.Shader("src/main/resources/shaders/distort_vertex.glsl", "src/main/resources/shaders/sky_fragment.glsl");
        starShader = new Shader("src/main/resources/shaders/star_vertex.glsl", "src/main/resources/shaders/star_fragment.glsl");
        constellShader = new Shader("src/main/resources/shaders/constellation_vertex.glsl", "src/main/resources/shaders/constellation_fragment.glsl");
        moonShader = new Shader("src/main/resources/shaders/moon_vertex.glsl", "src/main/resources/shaders/moon_fragment.glsl");

        meteorShader = new Shader("src/main/resources/shaders/meteor_vertex.glsl", "src/main/resources/shaders/meteor_fragment.glsl");
        meteorSystem = new MeteorSystem();

        // ── Initialize Constellation VAO/VBO ──
        constellVao = glGenVertexArrays();
        constellVbo = glGenBuffers();
        glBindVertexArray(constellVao);
        glBindBuffer(GL_ARRAY_BUFFER, constellVbo);
        org.lwjgl.opengl.GL20.glVertexAttribPointer(0, 3, GL_FLOAT, false, 12, 0L);
        org.lwjgl.opengl.GL20.glEnableVertexAttribArray(0);
        glBindVertexArray(0);

        // ── Initialize Star VAO/VBO ──
        starVao = glGenVertexArrays();
        starVbo = glGenBuffers();
        glBindVertexArray(starVao);
        glBindBuffer(GL_ARRAY_BUFFER, starVbo);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 5 * Float.BYTES, 0);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, 5 * Float.BYTES, 3 * Float.BYTES);
        glEnableVertexAttribArray(1);
        glBindVertexArray(0);

        // ── Initialize Meteor VAO/VBO ──
        meteorVao = glGenVertexArrays();
        meteorVbo = glGenBuffers();
        glBindVertexArray(meteorVao);
        glBindBuffer(GL_ARRAY_BUFFER, meteorVbo);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 5 * Float.BYTES, 0);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 1, GL_FLOAT, false, 5 * Float.BYTES, 3 * Float.BYTES);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(2, 1, GL_FLOAT, false, 5 * Float.BYTES, 4 * Float.BYTES);
        glEnableVertexAttribArray(2);
        glBindVertexArray(0);

        // Full-screen quad
        float[] quadVerts = {
                -1.0f,  1.0f, 0.0f, 1.0f,  -1.0f, -1.0f, 0.0f, 0.0f,   1.0f, -1.0f, 1.0f, 0.0f,
                -1.0f,  1.0f, 0.0f, 1.0f,   1.0f, -1.0f, 1.0f, 0.0f,   1.0f,  1.0f, 1.0f, 1.0f
        };
        kamuiScreenQuad = org.lwjgl.opengl.GL30.glGenVertexArrays();
        int quadVbo = org.lwjgl.opengl.GL15.glGenBuffers();
        org.lwjgl.opengl.GL30.glBindVertexArray(kamuiScreenQuad);
        org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, quadVbo);
        org.lwjgl.opengl.GL15.glBufferData(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, quadVerts, org.lwjgl.opengl.GL15.GL_STATIC_DRAW);
        org.lwjgl.opengl.GL20.glVertexAttribPointer(0, 2, GL_FLOAT, false, 4*4, 0L);
        org.lwjgl.opengl.GL20.glEnableVertexAttribArray(0);
        org.lwjgl.opengl.GL20.glVertexAttribPointer(1, 2, GL_FLOAT, false, 4*4, 2L*4);
        org.lwjgl.opengl.GL20.glEnableVertexAttribArray(1);
        org.lwjgl.opengl.GL30.glBindVertexArray(0);
        postFx = new com.leaf.game.render.PostFx();
        postFx.init(kamuiScreenQuad);

        camera = new Camera();
        setupMouseLook(camera);
        hud = new WindowHud(this);

        // ── AUDIO PRELOAD ──────────────────────────────────────────────────────
        // Warm up the JVM audio mixer so the very first play() call has no delay,
        // then decode every sound file once into heap memory.  Playback from this
        // point on is pure memory copy  -  no classpath IO, no thread-creation cost.
        //AudioManager.warmup();
        com.leaf.game.render.BlockTextureAtlas.load();
        // Subtle Doppler  -  enough to feel projectiles/wind pass by without the
        // cartoonish over-pitch a factor of 1.0 produces.
        /**AudioManager.setDopplerFactor(0.7f);
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
                // (block sounds now live under "block_stone/soil/sand/crystal"  -  see preload above)
        }) { //AudioManager.preload(snd);
            }
        **/
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
        enemyManager.setWorldGen(worldGen);   // biome queries for tower sites + ambient mix
        this.finale = new FinaleManager(this);
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
            //   R -> slow  (0.15 scale)
            //   Y -> fast  (4.0 scale)
            //   Neither -> normal (1.0)
            // Chat box suppresses time dilation so Y/R text doesn't glitch.
            if (!showChat && !isPaused && networkInitialized && !isPreloading) {
                boolean timeUnlocked = player.can(Progression.Ability.TIME);
                boolean rHeld = glfwGetKey(window, GLFW_KEY_R) == GLFW_PRESS && timeUnlocked;
                boolean yHeld = glfwGetKey(window, GLFW_KEY_Y) == GLFW_PRESS && timeUnlocked;
                if (rHeld) {
                    tc.setTargetScale(GameConfig.timeSlowScale);
                } else if (yHeld) {
                    tc.setTargetScale(GameConfig.timeFastScale);
                } else {
                    tc.setTargetScale(1.0f);
                }
            } else {
                // Menu open or not yet in-game  -  return to normal
                tc.setTargetScale(1.0f);
            }
            tc.update(rawDeltaTime);

            // ── SCALED DELTA TIME for physics ─────────────────────────────────
            ScreenEffectManager.INSTANCE.tick(rawDeltaTime);
            // Cutscene advances on raw time even though the world below is frozen.
            boolean cutsceneWasActive = cutscene.isActive();
            if (cutscene.isActive()) {
                cutscene.update(rawDeltaTime);
            }
            // (The old wave-11 ENDING cutscene was replaced by the FinaleManager
            //  portal trial — Voyage completion now triggers the true ending.)
            if (!cutscene.isActive() && deathCutscenePending) {
                // Cutscene just ended  -  check which kind finished.
                CutsceneManager.Kind finishedKind = cutscene.getKind();
                if (finishedKind == CutsceneManager.Kind.KAMUI_AWAKEN) {
                    // Kamui awakening done  -  show death screen so player can hit ENTER to respawn.
                    deathCutscenePending = false;
                    showDeathScreen = true;
                }
            }
            // ── THE FINALE — endgame state machine (summons → arena → ascension) ──
            if (finale != null && !isPaused) {
                finale.update(camera, rawDeltaTime);
                // The normal updateFx call lives inside the player-input block,
                // which is gated during finale cinematics — tick it here instead
                // so warp/tear/ascension particles keep moving.
                if (finale.locksPlayer()) updateFx(rawDeltaTime);
            }

            // Tick immunity  -  block incoming damage while it's active.
            if (immunityTimer > 0f) immunityTimer -= rawDeltaTime;
            // Showcase/demo mode: never run out of mana, never die  -  so the user can
            // chain every ability for the teacher without interruption.
            if (showcaseMode && player != null) {
                player.mana   = player.maxMana;
                player.health = player.maxHealth;
                immunityTimer = Math.max(immunityTimer, 5f);
            }
            if (damageFlashTimer > 0f) damageFlashTimer -= rawDeltaTime;
            if (player.manaFlashTimer > 0f) player.manaFlashTimer -= rawDeltaTime;
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
                // (each buildChunkMeshes call is ~0.5-1 ms on average hardware).
                boolean cannonActive = player.abilities.isCannonballing
                        || player.abilities.isCharging();
                int maxMeshesPerFrame = isPreloading ? 24 : cannonActive ? 20 : 8;
                // Per-frame wall-clock budget for mesh building. Each build also
                // issues a blocking glBufferData upload, so a burst of them (fast
                // movement / cannonball, when many chunks arrive at once) blows
                // the frame and shows as a lag spike. Cap the time spent here and
                // let leftover chunks mesh next frame  -  terrain pops in over a few
                // frames instead of stalling one. While preloading the player is
                // frozen at spawn, so spikes are invisible; let it run uncapped.
                long meshBudgetNanos = 4_000_000L; // ~4 ms
                long meshStartNanos  = System.nanoTime();
                int meshedThisFrame = 0;
                Chunk readyChunk;
                while (meshedThisFrame < maxMeshesPerFrame
                        && (readyChunk = world.meshingQueue.poll()) != null) {
                    world.buildChunkMeshes(readyChunk);
                    readyChunk.state = Chunk.ChunkState.MESHED;
                    meshedThisFrame++;
                    if (!isPreloading
                            && (System.nanoTime() - meshStartNanos) > meshBudgetNanos) break;
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
                        // the surface and Chunk.HEIGHT)  -  this prevents spawning on
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

                        if (armPlaytestOnSpawn) {
                            // PLAY -> full-fun sandbox. No tutorial, no walls: everything
                            // unlocked, weapons in the hotbar, enemies roaming to fight.
                            armPlaytestOnSpawn = false;
                            enterPlaytestMode();
                            welcomeTimer   = 6.0f;
                            welcomeStarted = true;
                        } else {
                            // Natural progression run: the player starts with the Sniper,
                            // Telescope, and a small stack of building blocks.
                            inventory.addBlockAmount(Block.WPN_SNIPER, 1);
                            inventory.addBlockAmount(Block.TELESCOPE, 1);
                            inventory.addBlockAmount(Block.TORCH, 10);
                            inventory.addBlockAmount(Block.GRASS, 20);
                            inventory.addBlockAmount(Block.DIRT, 20);
                            inventory.addBlockAmount(Block.STONE, 20);
                            // Begin the designed onboarding tutorial. It controls all
                            // enemy spawning (waves stay off) until the player graduates.
                            if (tutorial == null) {
                                tutorial = new TutorialManager(player, enemyManager, this, camera, world);
                                tutorial.start();
                            }
                            if (!welcomeStarted) {
                                welcomeTimer   = 6.0f;
                                welcomeStarted = true;
                            }
                        }
                        // Roll the intro cutscene the first time a NEW game reaches spawn.
                        if (playIntroOnSpawn) {
                            playIntroOnSpawn = false;
                            cutscene.startIntro();
                        }
                    }

                    // ── DEATH SCREEN INPUT HANDLER ───────────────────────────
                    if (showDeathScreen) {
                        if (player.useTestMovement && player.testMovement.state == TestMovementController.State.FLAPPY) {
                            // Flappy Mode Keyboard Menu
                            boolean enterPressed = glfwGetKey(window, GLFW_KEY_ENTER) == GLFW_PRESS
                                    || glfwGetKey(window, GLFW_KEY_KP_ENTER) == GLFW_PRESS;
                            boolean exitPressed = glfwGetKey(window, GLFW_KEY_X) == GLFW_PRESS; // Changed to X!

                            if (enterPressed && !lastDeathEnter) {
                                // Option 1: Play Again
                                showDeathScreen = false;
                                player.health = player.maxHealth; // CRITICAL FIX: Prevent instant re-death

                                player.testMovement.flappyScore = 0;
                                player.testMovement.lastPassedPipeX = 0;
                                player.testMovement.flappyWaitingToStart = true;
                                player.position.set(4980f, 230f, 5000f); // Spawn moved back
                                player.testMovement.velocity.set(0f, 0f, 0f);
                                camera.yaw = 0f;
                                camera.pitch = 0f;
                                world.clearAllChunks();
                            } else if (exitPressed) {
                                // Option 2: Exit and return to standard world
                                showDeathScreen = false;
                                player.health = player.maxHealth; // Reset health here too

                                player.useTestMovement = false;
                                player.testMovement.state = TestMovementController.State.AIRBORNE;

                                // Restore pre-flappy position
                                player.position.set(flappyPrevX, flappyPrevY, flappyPrevZ);
                                player.setVelocityY(0f);
                                camera.yaw = flappyPrevYaw;
                                camera.pitch = flappyPrevPitch;

                                if (enemyManager != null) enemyManager.wavesEnabled = flappyPrevWaves;
                                world.clearAllChunks();
                            }
                            lastDeathEnter = enterPressed;
                        } else {
                            // Standard survival mode reset
                            boolean en = glfwGetKey(window, GLFW_KEY_ENTER) == GLFW_PRESS
                                    || glfwGetKey(window, GLFW_KEY_KP_ENTER) == GLFW_PRESS;
                            if (en && !lastDeathEnter) {
                                showDeathScreen = false;
                                player.position.set(SPAWN_X, spawnSurfaceY, SPAWN_Z);
                                player.setVelocityY(0f);
                                player.pendingGravityReset = true;
                                atCanyon = false; canyonSettlePending = false;
                                player.health = player.maxHealth;
                                player.mana   = player.maxMana;
                                player.abilities.isKamui          = false;
                                player.abilities.kamuiAutoExited  = false;
                                player.abilities.absorptionCharge = 0f;
                                player.abilities.isDashing        = false;
                                player.abilities.isCannonballing  = false;
                                immunityTimer = REVIVAL_IMMUNITY_SECS;
                                ScreenEffectManager.INSTANCE.desaturate(0f, 0.8f);
                                enemyManager.resetForNewRun();
                                practiceAbility = null; practiceSteps = null; practiceQueue.clear();
                                showUnlockCard  = false; lastCardSpace = false; lastPracticeEnter = false;
                                deathCutscenePending = false;
                                gameEnded       = false;
                                if (kamuiJustUnlockedThisRevival) {
                                    kamuiJustUnlockedThisRevival = false;
                                    practiceQueue.add(Progression.Ability.KAMUI);
                                    lastPracticeEnter = true;
                                    startNextPractice();
                                }
                            }
                            lastDeathEnter = en;
                        }

                        RunRecords.INSTANCE.newRun((float) org.lwjgl.glfw.GLFW.glfwGetTime());
                    }

                    if (!showChat && !showNoiseViewer && !isPaused && !showHelp && !cutscene.isActive() && !showDeathScreen && !deathCutscenePending && !finale.locksPlayer()) {
                        // ── MP DUEL SETUP  -  runs BEFORE abilities so PvP hits land ─
                        updateMpProxyEarly();

                        // ── PLAYER UPDATE (time-scaled) ────────────────────────
                        // Save state BEFORE update so we can detect transitions.
                        // player.update() resets highestY on landing and toggles debugMode.
                        float   savedHighestY    = player.highestY;
                        boolean wasOnGroundAudio = player.isOnGround();
                        boolean wasFlightMode    = player.debugMode;
                        player.heldBlock = hotbar[selectedSlot]; // Sync the held block to the player
                        // ── SPIDER RIDING INPUT ──
                        if (riddenSpider != null && riddenSpider.alive) {
                            Vector3f fwd = camera.getForward();
                            Vector3f right = camera.getRight();
                            Vector3f move = new Vector3f();
                            if (glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS) move.add(fwd);
                            if (glfwGetKey(window, GLFW_KEY_S) == GLFW_PRESS) move.sub(fwd);
                            if (glfwGetKey(window, GLFW_KEY_D) == GLFW_PRESS) move.add(right);
                            if (glfwGetKey(window, GLFW_KEY_A) == GLFW_PRESS) move.sub(right);
                            if (move.lengthSquared() > 0.001f) move.normalize();

                            riddenSpider.rideInput.set(move);

                            // Snap player to the spider's back
                            player.position.set(riddenSpider.position.x, riddenSpider.position.y + 2.0f, riddenSpider.position.z);
                            player.setVelocityY(0f);
                            player.highestY = player.position.y;
                        }

                        // ── LASER POINTER UPDATE ──
                        if (spiderLaserActive && lastTarget != null && lastTarget.hit) {
                            for (Enemy e : enemyManager.getEnemies()) {
                                if (e instanceof com.leaf.game.entity.spider.SpiderEnemy) {
                                    com.leaf.game.entity.spider.SpiderEnemy se = (com.leaf.game.entity.spider.SpiderEnemy) e;
                                    if (se.mode == com.leaf.game.entity.spider.SpiderEnemy.BehaviorMode.FOLLOW_TARGET) {
                                        se.laserTarget.set(lastTarget.hitX + 0.5f, lastTarget.hitY + 1.0f, lastTarget.hitZ + 0.5f);
                                    }
                                }
                            }
                        }
                        player.update(window, camera, world, deltaTime);

                        // ── SMASH HINT: show "SHIFT = GROUND SMASH" on first 3 big falls ──
                        if (smashHintCount < 3
                                && player.can(Progression.Ability.PILLAR)
                                && !player.isOnGround()
                                && !player.isSmashing()
                                && !player.debugMode
                                && (player.highestY - player.position.y) > 8f) {
                            if (!showSmashHint) {
                                showSmashHint = true;
                                smashHintCount++;
                            }
                        } else if (player.isOnGround() || player.isSmashing() || player.debugMode) {
                            showSmashHint = false;
                        }

                        // ── DEPRIVATION DOMAIN: enforce position lock every frame ─
                        // Override whatever movement player.update() computed so the
                        // player's feet are nailed to their activation position.
                        if (depActive) {
                            player.position.x = depX;
                            player.position.y = depY;
                            player.position.z = depZ;
                            player.setVelocityY(0f);
                        }

                        lastCameraYaw = camera.yaw;
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
                        // Surface type: sand/red-sand -> walking_sand; sprint -> running.
                        float horizSpeedStep = (float) Math.sqrt(
                                listenerVel.x * listenerVel.x + listenerVel.z * listenerVel.z);
                        // Walking/running files are long loops  -  decide which one
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

                        // ── METEOR SPAWN: smash start -> STAR_IRON falling from sky ──
                        // Detects the leading edge of isSmashing so the meteor only
                        // spawns once per smash, not every frame of the descent.
                        // Only spawns on rocky/hard ground  -  looks wrong on sand/beach.
                        boolean nowSmashing = player.isSmashing();
                        if (nowSmashing && !wasSmashing) {
                            // Scan downward  -  at smash start the player is still airborne,
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
                        holdingTelescope = (hotbar[selectedSlot] == Block.TELESCOPE) && (glfwGetMouseButton(window, GLFW_MOUSE_BUTTON_RIGHT) == GLFW_PRESS);
                        if (holdingTelescope) {
                            telescope.update(camera, dayNight);  // Always update compass and tracking math
                        }

                        // ── ABILITY WEAPONS  -  LEFT-CLICK fires the equipped ability
                        //    (RMB still works too, but every tool answers to LMB so
                        //     there is exactly ONE button to remember) ───────────
                        Block heldWpn = hotbar[selectedSlot];
                        boolean fireWpnHeld = heldWpn == Block.WPN_SNIPER || heldWpn == Block.WPN_ORBITAL
                                || heldWpn == Block.WPN_TIMESTOP || heldWpn == Block.WPN_STONE_CANNON
                                || heldWpn == Block.WPN_DEPRIVATION;
                        boolean rmbNow = glfwGetMouseButton(window, GLFW_MOUSE_BUTTON_RIGHT) == GLFW_PRESS
                                || (fireWpnHeld && glfwGetMouseButton(window, GLFW_MOUSE_BUTTON_LEFT) == GLFW_PRESS);
                        boolean rmbEdge = rmbNow && !lastRMB;

                        // Sniper (WPN_SNIPER): hold to charge, release fires
                        player.attacks.weaponCHeld = (heldWpn == Block.WPN_SNIPER) && rmbNow;

                        // Orbital Annihilation (WPN_ORBITAL): one-shot on press
                        if (heldWpn == Block.WPN_ORBITAL && rmbEdge && !orbitalActive
                                && player.can(Progression.Ability.ORBITAL)) {
                            startOrbitalStrike(camera);
                        }

                        // Time Domain (WPN_TIMESTOP): toggle on press; cooldown after it ends
                        if (heldWpn == Block.WPN_TIMESTOP && rmbEdge
                                && player.can(Progression.Ability.DOMAIN)) {
                            if (timeStopActive) stopTimeStop();
                            else if (timeStopCooldown <= 0f) startTimeStop();
                        }

                        // Deprivation Domain (WPN_DEPRIVATION): toggle on press (also the ' key)
                        if (heldWpn == Block.WPN_DEPRIVATION && rmbEdge
                                && player.can(Progression.Ability.DEPRIVATION)) {
                            if (depActive) stopDeprivationDomain();
                            else if (depCooldown <= 0f) startDeprivationDomain();
                        }

                        // Stone Cannon (WPN_STONE_CANNON): fire button acts as I key (handled below via iHeld)
                        lastRMB = rmbNow;

                        // Selected-weapon hint so the player always knows what Left-Click does
                        if (hintTimer <= 0.1f) {
                            String wpnHint = switch (heldWpn) {
                                case WPN_ORBITAL     -> "Orbital Annihilation   -   Left-Click to fire";
                                case WPN_SNIPER      -> "Sniper   -   hold Left-Click to charge, release to fire";
                                case WPN_TIMESTOP    -> "Time Domain   -   Left-Click to toggle";
                                case WPN_STONE_CANNON-> "Stone Cannon   -   hold Left-Click to absorb earth, release to fire";
                                case WPN_DEPRIVATION -> "Deprivation Domain   -   Left-Click to erupt the golden cage";
                                case GATLING_GUN     -> "Gatling Gun   -   hold Left-Click to fire";
                                case GRAPPLING_HOOK  -> "Grappling Hook   -   hold LMB to swing";
                                case TELESCOPE       -> "Telescope   -   hold RMB to look";
                                default -> null;
                            };
                            if (wpnHint != null) { hintText = wpnHint; hintTimer = 1.5f; }
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
                            // Brutal impact shake  -  bigger than standard smash
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
                            // Brief camera judder  -  feels like pulling a trigger
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
                            // ── RUNIC CLEAVE VFX: a big diagonal X-slash in the aim
                            //    direction + a ground shockwave + an impact flash. ──
                            float ox = ev[0], oy = ev[1], oz = ev[2];
                            float adx = ev[3], ady = ev[4], adz = ev[5];
                            float cx = ox + adx * 3.5f, cy = oy + ady * 3.5f, cz = oz + adz * 3.5f;
                            float roll = (float) (Math.random() * Math.PI * 2.0);
                            // Two crossing crescents (facing back at the player) -> a searing X.
                            // Warm amber-gold tint  -  the baked gradient gives a white-hot
                            // edge fading to deep amber (powerful 3D blade, not flat white).
                            fxSlash(cx, cy, cz, -adx, -ady, -adz, roll,        2.4f, 3.6f, 6.0f, 0.30f, 1.5f, 1.0f, 0.45f);
                            fxSlash(cx, cy, cz, -adx, -ady, -adz, roll + 1.5f, -2.4f, 3.2f, 5.4f, 0.30f, 1.6f, 1.1f, 0.5f);
                            fxBurst(cx, cy, cz, 0.4f, 1.8f, 0.16f, 1.8f, 1.2f, 0.5f);   // small hot flash
                        }
                        player.attacks.pendingMeleeArcs.clear();

                        // Update all enemies (gravity, AI, death fade, etc.).
                        // THE WORLD: time is frozen -> enemies and their projectiles
                        // stop dead while the player keeps moving (dt=0 freezes them).
                        enemyManager.update(timeStopActive ? 0f : deltaTime, world, player.position);
                        processCombatFeedback(rawDeltaTime);

                        // ── INFERNO TOWER: eruption VFX (queued by EnemyManager) ──
                        // A fireball launches from the tower mouth, streaks to the landing
                        // spot, and the lava slime arrives in a molten splash.
                        for (float[] ev : enemyManager.pendingTowerErupts) {
                            fxBurst(ev[0], ev[1], ev[2], 0.6f, 2.8f, 0.35f, 3.2f, 1.4f, 0.4f);
                            float ddx = ev[3] - ev[0], ddy = ev[4] - ev[1], ddz = ev[5] - ev[2];
                            float dl = (float) Math.sqrt(ddx*ddx + ddy*ddy + ddz*ddz);
                            if (dl > 0.01f) {
                                fxBolt(ev[0], ev[1], ev[2], ddx/dl, ddy/dl, ddz/dl,
                                        dl, 0.22f, 0.32f, 2.8f, 1.2f, 0.35f);
                            }
                            fxRing(ev[3], ev[4] + 0.15f, ev[5], 0.4f, 3.4f, 0.45f, 2.6f, 0.9f, 0.25f);
                            fxBurst(ev[3], ev[4] + 0.5f, ev[5], 0.3f, 1.7f, 0.30f, 2.8f, 1.1f, 0.3f);
                            AudioManager.playAt("summon_slime",
                                    new Vector3f(ev[3], ev[4], ev[5]), (Vector3f) null, 60f);
                        }
                        enemyManager.pendingTowerErupts.clear();

                        // ── INFERNO TOWER: death eruption (column + shockwaves + shake) ──
                        for (float[] ev : enemyManager.pendingTowerDeaths) {
                            for (int k = 0; k < 6; k++) {
                                fxBurst(ev[0], ev[1] + 1.5f + k * 2.2f, ev[2],
                                        0.8f, 3.6f - k * 0.35f, 0.50f + k * 0.09f,
                                        3.4f, 1.3f, 0.35f);
                            }
                            fxRing(ev[0], ev[1] + 0.3f, ev[2], 1.0f, 15f, 0.85f, 3.0f, 1.0f, 0.30f);
                            fxRing(ev[0], ev[1] + 0.3f, ev[2], 0.5f, 9f,  1.15f, 2.2f, 0.6f, 0.20f);
                            for (int k = 0; k < 14; k++) {
                                float a  = (float)(Math.PI * 2 * k / 14.0);
                                float ux = (float) Math.cos(a), uz = (float) Math.sin(a);
                                fxBolt(ev[0], ev[1] + 9f, ev[2], ux * 0.55f, 0.83f, uz * 0.55f,
                                        4.5f, 0.12f, 0.65f, 2.8f, 1.0f, 0.3f);
                            }
                            activeShakeAmplitude = 0.30f;
                            activeShakeDuration  = 0.9f;
                            smashShakeTimer      = 0.9f;
                            AudioManager.playAt("fall_smash",
                                    new Vector3f(ev[0], ev[1] + 4f, ev[2]), (Vector3f) null, 120f);
                            enemyManager.processSmashKnockback(
                                    (int) ev[0], (int) ev[1], (int) ev[2], 6);
                        }
                        enemyManager.pendingTowerDeaths.clear();

                        // ── LIVING-WORLD AMBIENCE: tower embers, slime sparks, biome motes ──
                        ambienceTimer -= deltaTime;
                        if (ambienceTimer <= 0f && !isPreloading) {
                            ambienceTimer = 0.28f;
                            fxSpawnAmbient = true;   // tag these as decorative (no bloom FBO)
                            for (Enemy e : enemyManager.getEnemies()) {
                                if (!e.alive) continue;
                                float adx = e.position.x - player.position.x;
                                float adz = e.position.z - player.position.z;
                                if (adx*adx + adz*adz > 80f * 80f) continue;
                                if (e.type == Enemy.Type.INFERNO_TOWER) {
                                    // Rising embers from the crown  -  subtle warm streaks, NOT
                                    // HDR-bright (colours ≤ 0.25 keep peak < 1.0 in the FBO so
                                    // bloom stays gentle rather than saturating the screen).
                                    float ang = (float)(Math.random() * Math.PI * 2);
                                    float rr  = 0.8f + (float) Math.random() * 1.6f;
                                    fxBolt(e.position.x + (float) Math.cos(ang) * rr,
                                           e.position.y + 9.5f + (float) Math.random() * 2f,
                                           e.position.z + (float) Math.sin(ang) * rr,
                                           0f, 1f, 0f,
                                           1.2f + (float) Math.random() * 1.5f, 0.06f, 0.8f,
                                           0.20f, 0.09f, 0.025f);
                                } else if (e.type == Enemy.Type.LAVA_SLIME && Math.random() < 0.5) {
                                    fxBolt(e.position.x, e.position.y + 0.6f, e.position.z,
                                           0f, 1f, 0f, 0.7f, 0.05f, 0.5f, 0.18f, 0.08f, 0.025f);
                                }
                            }
                            // Biome atmosphere around the player: drifting sakura petals /
                            // rising volcanic embers. Kept dim (colours < 0.25) so the
                            // additive FX pass never saturates the FBO to white.
                            com.leaf.game.world.gen.biome.Biome hereBiome =
                                    worldGen.biomeAt((int) player.position.x, (int) player.position.z);
                            if (hereBiome == com.leaf.game.world.gen.biome.Biome.SAKURA) {
                                for (int k = 0; k < 2; k++) {
                                    float ang = (float)(Math.random() * Math.PI * 2);
                                    float rr  = 3f + (float) Math.random() * 14f;
                                    fxBolt(player.position.x + (float) Math.cos(ang) * rr,
                                           player.position.y + 2.5f + (float) Math.random() * 5f,
                                           player.position.z + (float) Math.sin(ang) * rr,
                                           0.25f, -1f, 0.18f,
                                           0.8f, 0.05f, 1.6f, 0.16f, 0.09f, 0.12f);
                                }
                            } else if (hereBiome == com.leaf.game.world.gen.biome.Biome.VOLCANIC) {
                                float ang = (float)(Math.random() * Math.PI * 2);
                                float rr  = 4f + (float) Math.random() * 12f;
                                fxBolt(player.position.x + (float) Math.cos(ang) * rr,
                                       player.position.y + (float) Math.random() * 3f,
                                       player.position.z + (float) Math.sin(ang) * rr,
                                       0f, 1f, 0f, 1.0f, 0.05f, 1.2f, 0.18f, 0.07f, 0.022f);
                            }
                            fxSpawnAmbient = false;  // back to bloom-worthy FX
                        }

                        // ── PROCESS GOLEM BLOCK BREAKING ──
                        for (Enemy e : enemyManager.getEnemies()) {
                            if (e.pendingBlockBreak) {
                                e.pendingBlockBreak = false;

                                // Golems smash a 2-block high hole through the wall
                                for (int dy = 0; dy <= 1; dy++) {
                                    int bx = e.breakX;
                                    int by = e.breakY + dy;
                                    int bz = e.breakZ;
                                    Block b = world.getBlock(bx, by, bz);

                                    // Don't let them break indestructible terrain
                                    if (b.isSolid() && b != Block.STAR_IRON && b != Block.MEGALITH && b != Block.MEGALITH_CARVED) {
                                        world.setBlock(bx, by, bz, Block.AIR);
                                        world.rebuildChunkAt(bx, by, bz);

                                        // Spawn flying debris
                                        Vector3f ejectVel = new Vector3f(
                                                (float)(shakeRng.nextFloat() - 0.5f) * 6f,
                                                3f + shakeRng.nextFloat() * 4f,
                                                (float)(shakeRng.nextFloat() - 0.5f) * 6f);
                                        droppedItems.add(new DroppedItem(bx, by, bz, b, ejectVel));
                                    }
                                }
                                // Play massive sound and shake the screen!
                                //AudioManager.playAt("ground_smash", e.position, (Vector3f)null, 40f);
                                //activeShakeDuration = 0.25f;
                                //activeShakeAmplitude = 0.15f;
                                //smashShakeTimer = Math.max(smashShakeTimer, activeShakeDuration);
                            }
                        }
                        if (tutorial != null) tutorial.update(deltaTime);

                        // ── THE VOYAGE  -  opens after the player clears wave 6 ──
                        // Waves 1-6 are the "training ground" where abilities unlock.
                        // Once the wave-6 card is dismissed and practice is done the
                        // crystal shatters, FLIGHT is granted, and the journey begins.
                        if (voyage == null) voyage = new Voyage(this);
                        if (!voyageStarted && !showcaseMode && tutorial != null
                                && tutorial.isFinished() && !voyage.complete
                                && !showUnlockCard && practiceAbility == null
                                && enemyManager.lastClearedWave >= Progression.VOYAGE_START_WAVE) {
                            voyage.start();
                            enemyManager.wavesEnabled    = false;  // training done  -  free to explore
                            enemyManager.freeExploreMode = true;   // ambient enemies roam the world
                            voyageStarted = true;
                            // Drop stale ground-combat prompts; this round is ALL
                            // about flying. One huge unmissable prompt until the
                            // player actually takes off.
                            tryQueue.clear();
                            tryStep = null;
                            tryQueue.add(new TryStep(Progression.Ability.FLIGHT,
                                    "SPACE  x2",
                                    "DOUBLE-TAP  SPACE   -   FLY!",
                                    "Hold SPACE to climb. Then follow the BEAM OF LIGHT to the shard.",
                                    TRY_FLYING, 90f));
                        }
                        if (voyageStarted && voyage.active) voyage.update(deltaTime);
                        if (voyageStarted) renderVoyageBeacon(rawDeltaTime);
                        // Voyage directives hold while the FLIGHT lesson is on screen,
                        // so the "fly to X" message still plays AFTER takeoff.
                        if (voyageMsgTimer > 0f
                                && !(tryStep != null && tryStep.ability == Progression.Ability.FLIGHT))
                            voyageMsgTimer = Math.max(0f, voyageMsgTimer - rawDeltaTime);
                        // The reveal waits its turn while the wave unlock card is up,
                        // so the two full-screen cards never stack on each other.
                        if (weaponRevealTimer > 0f && !showUnlockCard) {
                            weaponRevealTimer = Math.max(0f, weaponRevealTimer - rawDeltaTime);
                            // Current card done -> show the next queued reveal (if any)
                            if (weaponRevealTimer <= 0f && !weaponRevealQueue.isEmpty()) {
                                Progression.Ability next = weaponRevealQueue.poll();
                                weaponRevealAbility = next;
                                weaponRevealSlot    = weaponSlotFor(next);
                                weaponRevealTimer   = 5.5f;
                            }
                        }

                        // Night-time hint: show once when it first gets dark
                        if (!shownNightHint && world != null && player != null
                                && dayNight.ambientStrength < 0.30f) {
                            shownNightHint = true;
                            hintText  = "It's getting dark!  Press [7] for the TORCH, then Right-Click the ground to light it up.";
                            hintTimer = 8f;
                        }

                        // ── WAVE CLEARED -> ending / unlock card ───────────────
                        if (enemyManager.awaitingNextWave && !showUnlockCard && practiceAbility == null) {
                            int waveJustCleared = enemyManager.lastClearedWave;

                            {
                                // Every wave is a normal wave now — the TRUE ending is the
                                // FinaleManager portal trial, triggered by Voyage completion.
                                // Unlock this tier. Card only when something is genuinely
                                // NEW  -  wave 1 (Slash/Dash, already taught by the
                                // tutorial) flows straight into the next wave.
                                java.util.List<Progression.Ability> gained =
                                        player.progression.unlockForWave(waveJustCleared);
                                gained.removeIf(g -> g == Progression.Ability.SLASH
                                                  || g == Progression.Ability.DASH);
                                if (gained.isEmpty()) {
                                    enemyManager.beginNextWave();   // no tier (shouldn't happen ≤ ending)
                                } else {
                                    // Drop any physical weapon this tier grants straight into the
                                    // hotbar so the player SEES their new power appear.
                                    boolean gotWeapon = false;
                                    for (Progression.Ability a : gained) {
                                        if (grantWeaponForAbility(a) != null) gotWeapon = true;
                                    }
                                    unlockCardWave      = waveJustCleared;
                                    unlockCardAbilities = gained;
                                    showUnlockCard      = true;
                                    // A weightier sound + screen flash for a god-tier weapon reveal.
                                    if (gotWeapon) {
                                        AudioManager.play("snipe_loadgun");
                                        ScreenEffectManager.INSTANCE.flash(1.0f, 0.85f, 0.4f, 0.55f, 0.4f);
                                    } else {
                                        AudioManager.play("seal_collect");
                                    }
                                }
                            }
                        }

                        // Dismiss the card with ENTER  -  no forced practice, straight to next wave.
                        if (showUnlockCard) {
                            boolean en = glfwGetKey(window, GLFW_KEY_ENTER) == GLFW_PRESS
                                    || glfwGetKey(window, GLFW_KEY_KP_ENTER) == GLFW_PRESS;
                            if (en && !lastCardSpace) {
                                showUnlockCard = false;
                                lastPracticeEnter = true;
                                // Queue guided TRY IT steps for each fresh power  - 
                                // the next wave is already coming, so they get to
                                // try it on live targets immediately.
                                tryQueue.clear();
                                tryStep = null;
                                for (Progression.Ability ab : unlockCardAbilities)
                                    queueTryStepsFor(ab);
                                enemyManager.beginNextWave();
                                enemyManager.wavesEnabled = true;
                            }
                            lastCardSpace = en;
                        }

                        // ── TRY IT step tick ─────────────────────────────────
                        if (tryDoneTimer > 0f) {
                            tryDoneTimer -= rawDeltaTime;
                            if (tryDoneTimer <= 0f) tryDoneAbility = null;
                        }
                        if (tryStep == null && tryDoneTimer <= 0f && !tryQueue.isEmpty()
                                && !showUnlockCard && practiceAbility == null) {
                            tryStep     = tryQueue.poll();
                            tryTimer    = tryStep.timeout;
                            trySnapInit = false;
                        }
                        if (tryStep != null) {
                            tryTimer -= rawDeltaTime;
                            float frac = tryLiveFrac(tryStep);
                            if (!trySnapInit) { trySnapshot = frac; trySnapInit = true; }
                            if (tryStepDone(tryStep, frac)) {
                                AudioManager.play("cystal_click", 0.9f);
                                ScreenEffectManager.INSTANCE.flash(0.45f, 1.0f, 0.6f, 0.30f, 0.35f);
                                // Celebrate only when this was the LAST step of its
                                // ability (multi-step walkthroughs flow straight on).
                                TryStep peek = tryQueue.peek();
                                if (peek == null || peek.ability != tryStep.ability) {
                                    tryDoneAbility = tryStep.ability;
                                    tryDoneTimer   = 1.4f;
                                }
                                tryStep = null;
                            } else if (tryTimer <= 0f) {
                                tryStep = null;   // timed out  -  move on quietly
                            }
                        }

                        // ── PRACTICE SESSION tick (multi-step) ───────────────
                        if (practiceWarnTimer > 0f) practiceWarnTimer -= deltaTime;
                        if (practiceWarnTimer <= 0f) practiceWarnText = null;

                        if (practiceAbility != null && practiceSteps != null) {
                            AbilityPractice.Step step = practiceSteps.get(practiceStepIndex);
                            practiceStepAge     += deltaTime;
                            practiceCtx.stepAge  = practiceStepAge;

                            // Run optional per-frame tick logic (e.g. move NPCs).
                            if (step.onTick != null) {
                                try { step.onTick.accept(practiceCtx); } catch (Exception ignored) {}
                            }

                            if (practiceStepDone) {
                                // Celebration pause before advancing.
                                practiceCelebration -= deltaTime;
                                if (practiceCelebration <= 0f) advancePracticeStep();
                            } else {
                                // Run the done() predicate  -  counts up to step.required.
                                boolean oneAction = false;
                                try { oneAction = step.done.test(practiceCtx); }
                                catch (Exception ignored) {}

                                if (oneAction) {
                                    practiceCtx.counter++;
                                    practiceCtx.flag = false; // reset latch for next count
                                    if (practiceCtx.counter >= step.required) {
                                        practiceStepDone    = true;
                                        practiceCelebration = step.doneText != null
                                                ? PRACTICE_CELEBRATE_SECS : 0.3f;
                                    }
                                } else if (practiceStepAge > step.timeout) {
                                    advancePracticeStep(); // safety timeout
                                }

                                // ENTER skip  -  only for steps that explicitly allow it.
                                boolean enterNow = glfwGetKey(window, GLFW_KEY_ENTER) == GLFW_PRESS
                                        || glfwGetKey(window, GLFW_KEY_KP_ENTER) == GLFW_PRESS;
                                boolean canSkip  = step.allowSkip
                                        && practiceStepAge > 1.2f
                                        && !lastPracticeEnter && enterNow;
                                lastPracticeEnter = enterNow;
                                if (canSkip) { practiceStepDone = true; practiceCelebration = 0.1f; }
                            }
                        } else {
                            lastPracticeEnter = glfwGetKey(window, GLFW_KEY_ENTER) == GLFW_PRESS
                                    || glfwGetKey(window, GLFW_KEY_KP_ENTER) == GLFW_PRESS;
                        }

                        // ── PAPER FIGURINE SUBSTITUTE (V hold) ────────────────
                        // Must run BEFORE the damage drain so it can intercept.
                        if (substituteCooldown > 0f) substituteCooldown -= deltaTime;
                        boolean vHeld = glfwGetKey(window, GLFW_KEY_V) == GLFW_PRESS
                                && player.can(Progression.Ability.SUBSTITUTE);
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

                        // ── VOID DEATH  -  player fell past y=0 into the abyss ─────
                        if (player.fellIntoVoid && !showDeathScreen && !deathCutscenePending) {
                            player.fellIntoVoid = false;
                            ScreenEffectManager.INSTANCE.flash(0f, 0f, 0f, 1.0f, 0.5f);
                            triggerDeath();
                        }
                        player.fellIntoVoid = false; // clear every frame

                        // ── DRAIN remaining enemy damage into player health ────
                        if (enemyManager.pendingPlayerDamage > 0f) {
                            if (player.abilities.isKamui || immunityTimer > 0f) {
                                // Kamui / revival immunity = invincible
                                enemyManager.pendingPlayerDamage = 0f;
                            } else {
                                // ── DAMAGE ALERT  -  so a hit never comes "from nowhere" ──
                                float dmg = enemyManager.pendingPlayerDamage;
                                // Red vignette flash; stronger the harder you're hit.
                                float fa = Math.min(0.6f, 0.18f + dmg * 0.05f);
                                ScreenEffectManager.INSTANCE.flash(0.75f, 0.02f, 0.02f, fa, 0.35f);
                                AudioManager.play("fall_hit", Math.min(1f, 0.5f + dmg * 0.05f));
                                damageFlashTimer = 0.5f;   // HUD pulses the health bar

                                player.health -= dmg;
                                enemyManager.pendingPlayerDamage = 0f;
                            }
                        }

                        // ── CENTRAL DEATH  -  any source (enemy, FALL, PvP relay) that
                        //    drained HP to 0 dies here, the same way, with the same spawn.
                        if (player.health <= 0f && !player.abilities.isKamui && immunityTimer <= 0f) {
                            triggerDeath();
                        }

                        // ── KAMUI MANA DRAIN (passive while active) ───────────────
                        if (player.abilities.isKamui) {
                            player.mana = Math.max(0f,
                                    player.mana - GameConfig.manaKamuiDrain * deltaTime);
                            // Force-exit Kamui when mana is fully exhausted  -  no cooldown
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
                                // No range limit  -  Kamui can reach anything the player looks at.
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
                                    // No valid target  -  drain charge back
                                    AudioManager.stopContinuous("kamui_distortion");
                                    player.abilities.absorptionCharge = Math.max(0f,
                                            player.abilities.absorptionCharge - deltaTime * 2f);
                                }
                            } else {
                                // LMB released  -  bleed charge away
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

                        // P key  -  spawn test enemy at crosshair hit point
                        boolean pHeld = glfwGetKey(window, GLFW_KEY_P) == GLFW_PRESS;
                        if (pHeld && !lastP) {
                            RaycastResult hit = player.getTargetBlock(camera, world);
                            if (hit != null && hit.hit) {
                                enemyManager.spawnAt(
                                        hit.placeX + 0.5f,
                                        hit.placeY,
                                        hit.placeZ + 0.5f,
                                        Enemy.Type.SLIME); // <--- Add SLIME here!
                            }
                        }
                        lastP = pHeld;

                        // [0] key  -  DEV: spawn a GUARDIAN (golem) for testing.
                        // Drops it at the block you're aiming at, or ~6 blocks in front.
                        boolean zeroHeld = glfwGetKey(window, GLFW_KEY_0) == GLFW_PRESS;
                        if (zeroHeld && !lastZeroKey) {
                            RaycastResult ghit = player.getTargetBlock(camera, world);
                            if (ghit != null && ghit.hit) {
                                enemyManager.spawnAt(ghit.placeX + 0.5f, ghit.placeY + 1f,
                                        ghit.placeZ + 0.5f, Enemy.Type.GUARDIAN);
                            } else {
                                Vector3f fwd = camera.getLookDirection();
                                enemyManager.spawnAt(
                                        player.position.x + fwd.x * 6f,
                                        player.position.y + 3f,
                                        player.position.z + fwd.z * 6f,
                                        Enemy.Type.GUARDIAN);
                            }
                            System.out.println("[DEV] Spawned GUARDIAN (golem)");
                        }
                        lastZeroKey = zeroHeld;
                        // ── TODO'S TECHNIQUE (J key) ──────────────────────────
                        if (todoSwapCooldown > 0f) todoSwapCooldown -= deltaTime;
                        boolean jHeld = glfwGetKey(window, GLFW_KEY_J) == GLFW_PRESS
                                && player.can(Progression.Ability.SWAP);
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
                        boolean mHeld = glfwGetKey(window, GLFW_KEY_M) == GLFW_PRESS
                                && player.can(Progression.Ability.QUAGMIRE);
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

                        // Advance mud waves  -  permanently paint MUD blocks on the ground
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

                            // Reached target  -  burst MUD chunks around enemy feet, remove wave
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
                        boolean iHeld = (glfwGetKey(window, GLFW_KEY_I) == GLFW_PRESS
                                || (hotbar[selectedSlot] == Block.WPN_STONE_CANNON && lastRMB))
                                && player.can(Progression.Ability.STONE_CANON);

                        if (!isChargingStoneCanon && iHeld && !lastI
                                && stoneCanonCooldownTimer <= 0f
                                && player.mana >= GameConfig.manaStoneCanonBase) {
                            // Start charging  -  lock position
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
                                                if (isStoneCanonFuel(world.getBlock(bx2, by2, bz2))) {
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

                            // Release I or exceed max charge -> fire.
                            // ALWAYS fires after a real charge — consumed earth only makes
                            // the boulder bigger. (It used to silently fizzle with no
                            // stone nearby, which read as "the weapon is broken".)
                            if (!iHeld || stoneCanonCharge >= GameConfig.stoneCanonMaxCharge) {
                                isChargingStoneCanon = false;
                                if (stoneCanonCharge >= 0.20f) {
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

                                    // ── Fire from eye in exact look direction ─────
                                    // (Previously spawned from the ground  -  user requested
                                    //  it fires wherever you aim, like a normal projectile)
                                    Vector3f firePos = new Vector3f(
                                            player.position.x + lookDir.x * 1.2f,
                                            player.position.y + 1.6f + lookDir.y * 1.2f,
                                            player.position.z + lookDir.z * 1.2f);
                                    Vector3f fireDir = new Vector3f(lookDir); // already normalised
                                    Vector3f fireVel = new Vector3f(fireDir).mul(speed);
                                    stoneShotList.add(new ActiveStoneShot(firePos, fireVel, scale, chargeF));
                                    AudioManager.stopContinuous("charging");
                                    AudioManager.play("stone_canon");
                                    stoneCanonCooldownTimer = GameConfig.stoneCanonCooldown;
                                } else {
                                    // No blocks consumed  -  fizzle, just stop the charge sound
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
                        // holds the peak Y  -  Player resets it to position.y on landing.
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
                                // Short drop / jump landing  -  light thud
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
                        // Exit: pick randomly from 3 exit sounds  -  avoids the
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
                        // Three beds that never actually stop  -  windFade smoothly
                        // brings them to 0 so there are no abrupt pops on landing.
                        //
                        // KEY FIXES vs previous version:
                        //  • inAir uses debugMode as override -> touching blocks while
                        //    skimming no longer briefly kills the wind.
                        //  • When camSubmerged, windFade snaps to 0 immediately (water
                        //    has its own sound design).
                        //  • totalAirSpeed includes vertical so gentle rising/descending
                        //    registers  -  not just horizontal glides.
                        //  • wind_soft is ducked when wind_blow is strong.
                        //  • Tilt pan: rolls the stereo image left/right with camera roll.
                        float vy      = player.getVelocityY();
                        // debugMode = flight mode: ALWAYS treat as in-air so brief block
                        // grazes during skimming don't cut the wind.
                        // Underground (cave env) also suppresses wind  -  windFade fades
                        // out naturally when lastEnv switches to ENV_CAVE.
                        boolean inAir = (player.debugMode || !player.isOnGround())
                                        && !camSubmerged
                                        && lastEnv != AudioManager.ENV_CAVE;

                        // Snap wind off immediately on water entry  -  no lingering wind underwater.
                        if (camSubmerged) windFade = 0f;

                        float horizSpeed = (float) Math.sqrt(
                                listenerVel.x * listenerVel.x + listenerVel.z * listenerVel.z);
                        // In flight mode use FlightController's velocity so terrain collisions
                        // (which zero the position delta) don't kill wind volume on uphill skim.
                        float totalAirSpeed = player.debugMode
                                ? player.flightController.getFlightSpeed()
                                : (float) Math.sqrt(horizSpeed * horizSpeed + vy * vy);

                        // ── TUNING KNOBS ───────────────────────────────────────
                        final float BLOW_START   = 3f;    // lower -> wind starts earlier/gentler
                        final float BIG_START    = 13f;   // lower -> deep roar kicks in sooner
                        final float BLOW_MAX_VOL = 0.70f;
                        final float BIG_MAX_VOL  = 0.85f;
                        final float SOFT_MAX_VOL = 0.20f;
                        final float FADE_IN_SEC  = 0.12f; // faster fade-in for responsive feel
                        final float FADE_OUT_SEC = 0.55f; // longer fade-out  -  wind lingers a beat

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

                        // Gust boost: Math.max so gusts NEVER reduce existing flight-wind.
                        float gB = windGustStrength * 0.55f;
                        AudioManager.setContinuousVolume("wind/wind_soft", Math.max(softVol * windFade, gB * 0.45f));
                        AudioManager.setContinuousVolume("wind/wind_blow", Math.max(blowVol * windFade, gB * 0.60f));
                        AudioManager.setContinuousVolume("wind/wind_big",  Math.max(bigVol  * windFade, gB * 0.28f));

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

                        // ── AMBIENT WIND GUSTS ────────────────────────────────────
                        // Fires randomly even when standing still  -  a sudden gust of
                        // cold mountain wind. Boosts all wind beds, plays wind_harsh
                        // as the gust arrives, and tilts snow particles sideways.
                        // Independent of flight speed so it surprises the player.
                        windGustTimer -= deltaTime;
                        if (windGustTimer <= 0f && !camSubmerged) {
                            windGustDuration = 3.5f + (float)Math.random() * 4.0f;
                            windGustAngle    = (float)(Math.random() * Math.PI * 2);
                            windGustTimer    = 18f + (float)Math.random() * 35f;
                            AudioManager.playVaried("wind/wind_harsh",
                                    0.45f + (float)Math.random() * 0.30f, 0.07f);
                        }
                        if (windGustDuration > 0f) {
                            windGustDuration -= deltaTime;
                            windGustStrength  = Math.min(1f, windGustStrength + deltaTime * 1.8f);
                        } else {
                            windGustStrength  = Math.max(0f, windGustStrength - deltaTime * 1.1f);
                        }

                        // ── MUFFLE (low-pass on the whole mix) ────────────────
                        // Priority 1  -  submerged: very heavy low-pass gives the
                        //   沉闷 underwater feel.  Combined with ENV_UNDERWATER
                        //   reverb, it sells the "thick water pressing on your ears"
                        //   sensation.  Value 0..1 where 1 = fully muffled.
                        // Priority 2  -  fast air movement: lighter, speed-scaled cut.
                        //
                        // UNDERWATER_MUFFLE - raise toward 1 for heavier/deafer feel
                        final float UNDERWATER_MUFFLE = 0.82f;
                        final float MUFFLE_MAX        = 0.55f;

                        float targetMuffle;
                        if (camSubmerged) {
                            // Hard cut on all high frequencies  -  deep, thick, pressured
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
                        network.sendHealth(player.health, player.maxHealth);   // so the teammate sees our HP
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
                    remotePlayer.health      = network.remoteHealth;
                    remotePlayer.maxHealth   = network.remoteMaxHealth;

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
                        Block placed = Block.values()[plc[3]];
                        world.setBlock(plc[0], plc[1], plc[2], placed);
                        world.rebuildChunkAt(plc[0], plc[1], plc[2]);
                        if (placed == Block.TORCH)
                            torchPositions.add(new Vector3f(plc[0] + 0.5f, plc[1] + 0.5f, plc[2] + 0.5f));
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
                    // Drain enemy hotdog drops into the world item list.
                    if (!enemyManager.pendingFoodDrops.isEmpty()) {
                        for (float[] fd : enemyManager.pendingFoodDrops) {
                            droppedItems.add(new DroppedItem(
                                    (int) fd[0], (int) fd[1], (int) fd[2], Block.HOTDOG));
                        }
                        enemyManager.pendingFoodDrops.clear();
                    }
                    if (!enemyManager.pendingDeathFX.isEmpty()) {
                        for (float[] df : enemyManager.pendingDeathFX) {
                            float x = df[0], y = df[1], z = df[2];
                            fxBurst(x, y, z, 0.35f, 2.8f, 0.28f, 2.8f, 0.5f, 0.15f);
                            fxRing(x, y - 0.2f, z, 0.6f, 4.5f, 0.40f, 2.2f, 0.4f, 0.1f);
                        }
                        enemyManager.pendingDeathFX.clear();
                    }
                    Vector3f chestPos = new Vector3f(player.position.x,
                            player.position.y + 0.9f, player.position.z);
                    for (int i = droppedItems.size() - 1; i >= 0; i--) {
                        DroppedItem item = droppedItems.get(i);
                        item.update(timeStopActive ? 0f : deltaTime, player.position);
                        if (chestPos.distance(item.position) < 0.5f) {
                            if (item.blockType == Block.HOTDOG) {
                                // Nom  -  hotdogs heal instead of entering the inventory.
                                player.health = Math.min(player.maxHealth, player.health + 25f);
                                player.mana = Math.min(player.maxMana, player.mana + 25f);
                                AudioManager.play("cystal_click", 0.9f, 0.7f);
                                ScreenEffectManager.INSTANCE.flash(0.4f, 1.0f, 0.5f, 0.18f, 0.25f);
                                hintText  = "HOTDOG!  +25 HP";
                                hintTimer = 1.2f;
                            } else {
                                inventory.addBlock(item.blockType);
                                addBlockToHotbar(item.blockType);
                            }
                            item.alive = false;
                            if (network != null && network.connected)
                                network.sendPickup(item.originX, item.originY, item.originZ);
                            droppedItems.remove(i);
                        }
                    }

                    // ── PvP PROXY relay ────────────────────────────────────────
                    // The proxy hitbox is created + positioned EARLY each frame (before
                    // abilities run  -  see updateMpProxyEarly) so hits land reliably.
                    // Here we just read the damage our abilities dealt it and relay it.
                    if (mpProxy != null) {
                        float dealt = mpProxy.maxHealth - mpProxy.health;
                        if (dealt > 0.01f) network.sendDamage(dealt);   // relay our hits
                        mpProxy.health = mpProxy.maxHealth;
                        mpProxy.alive  = true;
                    }

                    // ── SPAWN A TROOP (press ']') that charges the opponent ─────
                    boolean sk = glfwGetKey(window, KeyBindings.MP_SUMMON) == GLFW_PRESS;
                    if (sk && !mpLastSummonKey && mySummons.size() < 64) {
                        mySummons.add(new com.leaf.game.entity.Summon(
                                player.position.x, player.position.y + 1.2f, player.position.z, 18f));
                        AudioManager.play("teleport");
                    }
                    mpLastSummonKey = sk;

                    // ── SIMULATE our troops  -  home on the opponent, bite, relay ─
                    Vector3f foePos = new Vector3f(remotePlayer.x, remotePlayer.y, remotePlayer.z);
                    float troopDmg = 0f;
                    for (com.leaf.game.entity.Summon s : mySummons) troopDmg += s.update(rawDeltaTime, foePos);
                    if (troopDmg > 0f) network.sendDamage(troopDmg);
                    mySummons.removeIf(s -> !s.alive);

                    // ── STREAM our troop positions to the peer (~20 Hz) ─────────
                    mpSummonSendTimer += rawDeltaTime;
                    if (mpSummonSendTimer >= 0.05f) {
                        mpSummonSendTimer = 0f;
                        int n = Math.min(255, mySummons.size());
                        float[] packed = new float[n * 3];
                        for (int i = 0; i < n; i++) {
                            com.leaf.game.entity.Summon s = mySummons.get(i);
                            packed[i*3] = s.pos.x; packed[i*3+1] = s.pos.y; packed[i*3+2] = s.pos.z;
                        }
                        network.sendSummons(packed, n);
                    }

                    // ── RECEIVE PvP / troop damage dealt to US ──────────────────
                    Float incoming;
                    while ((incoming = network.pollDamage()) != null) {
                        if (!(player.abilities.isKamui || immunityTimer > 0f)) {
                            player.health -= incoming;
                            damageFlashTimer = 0.5f;
                            ScreenEffectManager.INSTANCE.flash(0.75f, 0.02f, 0.02f, 0.22f, 0.3f);
                        }
                    }
                    if (player.health <= 0f && !player.abilities.isKamui && immunityTimer <= 0f) {
                        triggerDeath();   // PvP/troop kill  -  die immediately, same flow + spawn
                    }

                    // ── RECEIVE the opponent's ability VFX and replay them locally ─
                    float[] rfx;
                    while ((rfx = network.pollFx()) != null) addRemoteFx(rfx);

                    // ── OPPONENT ELIMINATED  -  kill banner + confirm sound ──────────
                    while (network.pollDeath() != null) {
                        killBannerTimer = 3.0f;
                        AudioManager.play("teleport");
                    }
                    if (killBannerTimer > 0f) killBannerTimer -= rawDeltaTime;
                }

                if (!isPreloading) {
                    lastTarget = player.getTargetBlock(camera, world);
                    // tickLiquids uses scaled deltaTime  -  fast time makes water flow faster
                    world.tickLiquids(deltaTime);
                    world.updateChunks(world, worldGen, player);
                    if (dayNight.starsDirty) {
                        dayNight.starsDirty = false;
                        updateConstellationBuffer();   // reprojected when stars update

                        // 5 floats per star: dir.x, dir.y, dir.z, mag, bv
                        float[] starData = new float[dayNight.visibleStars.size() * 5];
                        int idx = 0;
                        for (Astronomy.StarGPU star : dayNight.visibleStars) {
                            starData[idx++] = star.dir.x;
                            starData[idx++] = star.dir.y;
                            starData[idx++] = star.dir.z;
                            starData[idx++] = star.mag;
                            starData[idx++] = star.bv;
                        }

                        glBindBuffer(GL_ARRAY_BUFFER, starVbo);
                        glBufferData(GL_ARRAY_BUFFER, starData, GL_DYNAMIC_DRAW);
                        glBindBuffer(GL_ARRAY_BUFFER, 0);
                    }

                    // ── NON-EUCLIDEAN "LAYERED ROOMS" (F6 toggles in/out) ────
                    // (single-player only  -  unsynced world swap would break MP)
                    boolean mpLiveDbg = network != null && network.connected;
                    boolean f6Now = glfwGetKey(window, GLFW_KEY_F6) == GLFW_PRESS;
                    if (f6Now && !lastF6 && !mpLiveDbg) {
                        if (nerActive) exitLayeredRooms(camera);
                        else           enterLayeredRooms(camera);
                    }
                    lastF6 = f6Now;

                    // While inside, watch for the player rounding the pillar and
                    // re-skin the hidden diagonal room to keep the sequence going.
                    if (nerActive) updateLayeredRooms();

                    // ── CANYON WARP (F5 toggles to the mesa region and back) ───
                    // (single-player only  -  teleporting away desyncs the duel)
                    boolean f5Now = glfwGetKey(window, GLFW_KEY_F5) == GLFW_PRESS;
                    if (f5Now && !lastF5 && !mpLiveDbg) {
                        if (!atCanyon) {
                            canyonReturnX = player.position.x;
                            canyonReturnY = player.position.y;
                            canyonReturnZ = player.position.z;
                            player.position.set(GameConfig.canyonCenterX + 0.5f,
                                                GameConfig.canyonCeilingY + 50f,
                                                GameConfig.canyonCenterZ + 0.5f);
                            player.setVelocityY(0f);
                            atCanyon = true;
                            canyonSettlePending = true;
                            hintText = "WARPED TO CANYON   -   press F5 to return";
                            hintTimer = 5f;
                        } else {
                            player.position.set(canyonReturnX, canyonReturnY, canyonReturnZ);
                            player.setVelocityY(0f);
                            atCanyon = false;
                            canyonSettlePending = false;
                            hintText = "Returned to where you were";
                            hintTimer = 2.5f;
                        }
                    }
                    lastF5 = f5Now;
                    // Hover high above the canyon until its chunk meshes, then snap
                    // to the surface  -  avoids a long fall onto ungenerated terrain.
                    if (canyonSettlePending) {
                        int ccx = Math.floorDiv((int) Math.floor(player.position.x), Chunk.SIZE);
                        int ccz = Math.floorDiv((int) Math.floor(player.position.z), Chunk.SIZE);
                        Chunk cch = world.getChunk(ccx, 0, ccz);
                        if (cch != null && cch.state == Chunk.ChunkState.MESHED) {
                            int bx = (int) Math.floor(player.position.x);
                            int bz = (int) Math.floor(player.position.z);
                            settle:
                            for (int ly = Chunk.HEIGHT - 2; ly >= 1; ly--) {
                                if (!world.getBlock(bx, ly, bz).isSolid()) continue;
                                for (int sy = ly + 1; sy < Chunk.HEIGHT; sy++)
                                    if (world.getBlock(bx, sy, bz).isSolid()) continue settle;
                                player.position.y = ly + 1.5f;
                                break;
                            }
                            player.setVelocityY(0f);
                            canyonSettlePending = false;
                        } else {
                            player.position.y = GameConfig.canyonCeilingY + 50f; // hover while loading
                            player.setVelocityY(0f);
                        }
                    }

                    // Debug/cinematic F-keys are single-player only: they black out,
                    // carve, teleport or time-warp the world UNSYNCED, which corrupts a
                    // live multiplayer session (this is what made F7 "break the game").
                    boolean mpLive = network != null && network.connected;

                    // ── ORBITAL ANNIHILATION (F7 fires the cinematic) ─────────
                    boolean f7Now = glfwGetKey(window, GLFW_KEY_F7) == GLFW_PRESS;
                    if (f7Now && !lastF7 && !orbitalActive && !mpLive
                            && player.can(Progression.Ability.ORBITAL)) startOrbitalStrike(camera);
                    lastF7 = f7Now;
                    if (orbitalActive) updateOrbitalStrike(rawDeltaTime);

                    // ── THE WORLD: time-stop domain (F8) ──────────────────────
                    boolean f8Now = glfwGetKey(window, GLFW_KEY_F8) == GLFW_PRESS;
                    if (f8Now && !lastF8 && !timeStopActive && !mpLive
                            && player.can(Progression.Ability.DOMAIN)) startTimeStop();
                    lastF8 = f8Now;
                    if (timeStopActive) updateTimeStop(rawDeltaTime);

                    // ── METEOR STORM (F4)  -  rains hot meteorites that carve craters ──
                    boolean f4Now = glfwGetKey(window, KeyBindings.METEOR_STORM) == GLFW_PRESS;
                    if (f4Now && !lastF4) startMeteorStorm();
                    lastF4 = f4Now;
                    // Mega meteor removed from the keyboard  -  it shared a key with the
                    // Deprivation Domain and erased mountains by accident.
                    updateMeteors(rawDeltaTime);

                    // ── GATLING GUN  -  hold LMB (with the gun selected) to rip ──────
                    updateGatling(rawDeltaTime, camera);

                    // ── STARGAZING TOGGLES ──────────────────────────────────────────
                    boolean ck = glfwGetKey(window, GLFW_KEY_F2) == GLFW_PRESS;
                    if (ck && !lastConstellKey) showConstellations = !showConstellations;
                    lastConstellKey = ck;
                    boolean sk2 = glfwGetKey(window, GLFW_KEY_EQUAL) == GLFW_PRESS;  // '='
                    if (sk2 && !lastSnowToggleKey) showSnowAtmos = !showSnowAtmos;
                    lastSnowToggleKey = sk2;

                    // ── DAY / NIGHT CYCLE  -  advance the sky + light mood ───────────
                    // "Made in Heaven": HOLD '[' to ramp the cycle up to ~400×, so the
                    // sun & moon race across the sky. Eases in/out so it accelerates.
                    boolean mih = glfwGetKey(window, KeyBindings.TIME_ACCEL) == GLFW_PRESS
                            || (finale != null && finale.wantsTimeRush());
                    mihRamp = mih ? Math.min(1f, mihRamp + rawDeltaTime * 3.5f)
                                  : Math.max(0f, mihRamp - rawDeltaTime * 2.0f);
                    GameConfig.dayNightSpeed = 1f + mihRamp * mihRamp * 400f;
                    dayNight.update(rawDeltaTime);
                    if (!isPaused && !isPreloading) {
                        meteorSystem.update(rawDeltaTime * tc.getScale(), dayNight);
                    }

                    // ── RADAR SWEEP (F10)  -  earned god-tier power, sees enemies through walls ──
                    boolean f10Now = glfwGetKey(window, GLFW_KEY_F10) == GLFW_PRESS;
                    if (f10Now && !lastF10 && !vlActive
                            && player.can(Progression.Ability.RADAR)) {
                        vlActive = true; vlT = 0f;
                        vlCx = player.position.x; vlCy = player.position.y + 1f; vlCz = player.position.z;
                        hintText = "RADAR SWEEP"; hintTimer = 3f;
                    }
                    lastF10 = f10Now;
                    if (vlActive) updateVoxelLines(rawDeltaTime);

                    // ── CHOCOLATE DISCO GRID ('.' key)  -  showcase only; a stray press
                    //    mid-fight summons a glowing grid nobody asked for ─────────────
                    boolean kNow = (showcaseMode || playtestMode)
                            && glfwGetKey(window, KeyBindings.DISCO) == GLFW_PRESS;
                    if (kNow && !lastDisco) {
                        if (!cdActive) {
                            spawnDiscoGrid(camera, world);
                        } else if (showDiscoUI) {
                            dismissDiscoGrid();
                        }
                    }
                    lastDisco = kNow;
                    if (cdActive) updateDiscoGrid(rawDeltaTime, camera, world);

                    // ── DEPRIVATION DOMAIN - Water God Stance (' key) ─────────
                    boolean apostrNow = glfwGetKey(window, KeyBindings.DEPRIVATION_DOMAIN) == GLFW_PRESS;
                    if (apostrNow && !lastApostr) {
                        if (!depActive && depCooldown <= 0f && player.can(Progression.Ability.DEPRIVATION)) {
                            startDeprivationDomain();
                        } else if (depActive) {
                            stopDeprivationDomain();
                        }
                    }
                    lastApostr = apostrNow;
                    // Tick cooldown + strike flash decay every frame (outside domain too)
                    depCooldown = Math.max(0f, depCooldown - rawDeltaTime);
                    timeStopCooldown = Math.max(0f, timeStopCooldown - rawDeltaTime);
                    depStrike   = Math.max(0f, depStrike   - rawDeltaTime * 6f);
                    if (depActive) updateDeprivationDomain(rawDeltaTime, world, camera);
                    updateFx(rawDeltaTime);   // age shared ability VFX

                    // ── QUANTUM BULLET (',' key)  -  fire a phasing bullet ─────────
                    qbCooldown     = Math.max(0f, qbCooldown - rawDeltaTime);
                    qbWarpStrength = Math.max(0f, qbWarpStrength - rawDeltaTime);  // warp lasts ~1 s on the block
                    boolean quantumNow = (showcaseMode || playtestMode)
                            && glfwGetKey(window, KeyBindings.QUANTUM_BULLET) == GLFW_PRESS;
                    if (quantumNow && !lastQuantum && qbCooldown <= 0f && !player.debugMode) {
                        Vector3f d = camera.getLookDirection();
                        Vector3f o = new Vector3f(camera.position.x + d.x * 0.6f,
                                                  camera.position.y + d.y * 0.6f,
                                                  camera.position.z + d.z * 0.6f);
                        qbBullets.add(new QBullet(o, new Vector3f(d).mul(60f), 2.2f));
                        qbCooldown = 0.35f;
                        AudioManager.play("swoosh", 0.6f, 1.5f);
                    }
                    lastQuantum = quantumNow;
                    updateQuantumBullets(rawDeltaTime, world);

                    // ── TELEPORT VFX (blink / substitute / swap / seal warp) ──────
                    // Fire once on the rising edge of each teleport flash.
                    float bfNow = player.abilities.blinkFlashTimer;
                    if (bfNow > 0f && lastBlinkFlash <= 0.0001f) {
                        Vector3f o = player.abilities.blinkOrigin, d = player.abilities.blinkDest;
                        if (o != null) fxTeleport(o.x, o.y, o.z);
                        if (d != null) fxTeleport(d.x, d.y, d.z);
                    }
                    lastBlinkFlash = bfNow;
                    float sfNow = player.seals.teleportFlash;
                    if (sfNow > 0f && lastSealFlash <= 0.0001f) {
                        fxTeleport(player.position.x, player.position.y, player.position.z);
                    }
                    lastSealFlash = sfNow;

                    // ── LIGHTNING STRIKE VFX: 3D drama once per fresh bolt ────────
                    for (com.leaf.game.entity.LightningController.LightningBolt lb
                            : player.lightning.activeBolts) {
                        if (!lb.fxSpawned) {
                            lb.fxSpawned = true;
                            fxLightningStrike(lb.worldTarget.x, lb.worldTarget.y, lb.worldTarget.z);
                        }
                    }

                    // Drain enemy hotdog drops into the world item list.
                    if (!enemyManager.pendingFoodDrops.isEmpty()) {
                        for (float[] fd : enemyManager.pendingFoodDrops) {
                            droppedItems.add(new DroppedItem(
                                    (int) fd[0], (int) fd[1], (int) fd[2], Block.HOTDOG));
                        }
                        enemyManager.pendingFoodDrops.clear();
                    }
                    if (!enemyManager.pendingDeathFX.isEmpty()) {
                        for (float[] df : enemyManager.pendingDeathFX) {
                            float x = df[0], y = df[1], z = df[2];
                            fxBurst(x, y, z, 0.35f, 2.8f, 0.28f, 2.8f, 0.5f, 0.15f);
                            fxRing(x, y - 0.2f, z, 0.6f, 4.5f, 0.40f, 2.2f, 0.4f, 0.1f);
                        }
                        enemyManager.pendingDeathFX.clear();
                    }
                    Vector3f chestPos = new Vector3f(player.position.x,
                            player.position.y + 0.9f, player.position.z);
                    for (int i = droppedItems.size() - 1; i >= 0; i--) {
                        DroppedItem item = droppedItems.get(i);
                        item.update(timeStopActive ? 0f : deltaTime, player.position);
                        if (chestPos.distance(item.position) < 0.5f) {
                            if (item.blockType == Block.HOTDOG) {
                                // Nom  -  hotdogs heal instead of entering the inventory.
                                player.health = Math.min(player.maxHealth, player.health + 25f);
                                player.mana = Math.min(player.maxMana, player.mana + 25f);
                                AudioManager.play("cystal_click", 0.9f, 0.7f);
                                ScreenEffectManager.INSTANCE.flash(0.4f, 1.0f, 0.5f, 0.18f, 0.25f);
                                hintText  = "HOTDOG!  +25 HP";
                                hintTimer = 1.2f;
                            } else {
                                inventory.addBlock(item.blockType);
                                addBlockToHotbar(item.blockType);
                            }
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

            // ── TINNITUS ARC ──────────────────────────────────────────────────
            // Runs for TINNITUS_DUR seconds completely independently of orbitalActive.
            //
            // KEY: we use ONLY setMasterGain here (no muffle). Muffle applies its
            // lowpass to every source in the pool via rerouteAll(), including the
            // tinnitus one-shot  -  at 2.2× pitch that kills 95% of its signal.
            // setMasterGain only re-applies to loop sources, so the tinnitus
            // one-shot (spawned at masterGain=1.0) keeps its full 6.0 gain.
            //
            // Shape (real tinnitus, per the user):
            //   0 - 1.0 s : world CUTS to near-silence instantly (masterGain->0.0)
            //   1.0 - 5.5 s: world bleeds back very slowly with cubic ease-out
            //   5.5 - 7.0 s: world returns to normal; faint distorted bleed becomes clear
            if (tinnitusTimer > 0f) {
                tinnitusTimer = Math.max(0f, tinnitusTimer - rawDeltaTime);
                float p = 1f - tinnitusTimer / TINNITUS_DUR; // 0=just started, 1=done

                // Cut to silence fast (first 14% ≈ 1 s), then recover slowly.
                float gain;
                if (p < 0.14f) {
                    gain = 1f - (p / 0.14f); // 1->0 over first second
                } else {
                    float q = (p - 0.14f) / 0.86f;  // 0->1 over remaining 6 s
                    gain = q * q * q;                 // cubic: very slow then accelerates
                }
                AudioManager.setMasterGain(gain);

                if (tinnitusTimer == 0f) {
                    AudioManager.setMasterGain(1f);   // fully restored
                }
            }

            // ── RENDER ────────────────────────────────────────────────────────

            // ── KAMUI DISTORTION FBO ──────────────────────────────────────────
            // When Kamui is active the 3-D scene is redirected into an off-screen
            // texture. After all 3-D rendering is done we apply a GLSL distortion
            // shader (swirl / vortex UV warp) to that texture and blit the result
            // to the default framebuffer. ImGui is then composited on top normally.
            boolean doKamuiDistort = player != null && !isPreloading
                    && player.abilities.isKamui && distortShader != null;
            // The orbital cinematic also redirects the scene into the FBO so we can
            // run the searing-bloom pass over the emissive scan + laser flash.
            boolean doOrbitalBloom = orbitalActive && !isPreloading && bloomShader != null
                    && orbitalT >= ORB_DARK_START && orbitalT < ORB_END;
            // The radar also blooms (bright green lines + searing enemy pings bleed).
            boolean doRadarBloom = vlActive && !isPreloading && bloomShader != null && vlAmountNow > 0.05f;
            // Disco grid also blooms  -  the wireframe boxes and detonation pillars are
            // HDR-bright and need the bloom pass to look correct.
            boolean doDiscoBloom = cdActive && !isPreloading && bloomShader != null && cdSpawnT > 0.05f;
            // Domain always blooms  -  the HDR gold hemisphere + threads + boundary ring need it.
            boolean doDepBloom   = depActive && !isPreloading && bloomShader != null;
            // Shared ability-FX (slashes, rings, bolts) bloom whenever any "real" one
            // is live. Ambient living-world particles (tower embers, sakura petals,
            // volcanic motes) are EXCLUDED  -  they fire continuously, so letting them
            // route the whole scene through the bloom FBO every frame washed the world
            // out to a bright, see-through haze (the "transparent shader" bug).
            boolean hasBloomFx = false;
            for (int fi = 0; fi < fxList.size(); fi++) {
                if (!fxList.get(fi).ambient) { hasBloomFx = true; break; }
            }
            boolean doFxBloom    = (hasBloomFx || !qbBullets.isEmpty() || !meteors.isEmpty())
                    && !isPreloading && bloomShader != null;
            // Stone Canon's charge gathers searing emissive energy -> bloom it.
            boolean doStoneBloom = isChargingStoneCanon && !isPreloading && bloomShader != null;
            boolean doBloom = doOrbitalBloom || doRadarBloom || doDiscoBloom || doDepBloom
                    || doFxBloom || doStoneBloom;
            // Quantum Bullet's surface warp routes the scene through the distort pass.
            boolean doQuantumWarp = qbWarpStrength > 0.01f && distortShader != null && !isPreloading;
            // With post-processing on, every frame renders into the HDR scene FBO and
            // goes through PostFx (bloom + grade). Kamui / quantum warp keep their
            // own distortion passes.
            boolean doPostFx = GameConfig.postFx && postFx != null && networkInitialized && !isPreloading;
            boolean useSceneFbo = doKamuiDistort || doBloom || doQuantumWarp || doPostFx;

            // ── SUN / MOON SHADOW MAP ─────────────────────────────────────────
            // Depth-only terrain pass from the light's point of view. Runs before
            // the scene FBO is bound; end(0) hands the default framebuffer back.
            boolean shadowsLive = false;
            if (networkInitialized && !isPreloading && GameConfig.shadows && shadowMap != null
                    && dayNight.lightStrength > 0.04f && dayNight.lightDir.y > 0.03f
                    && !(orbitalActive && orbDark)
                    && shadowMap.begin(dayNight.lightDir, player.position)) {
                int scx = Math.floorDiv((int) Math.floor(player.position.x), Chunk.SIZE);
                int scz = Math.floorDiv((int) Math.floor(player.position.z), Chunk.SIZE);
                int scy = Math.floorDiv((int) player.position.y, Chunk.HEIGHT);
                int sr  = (int) Math.ceil(GameConfig.shadowRadius / Chunk.SIZE) + 1;
                for (int dx = -sr; dx <= sr; dx++) {
                    for (int dz = -sr; dz <= sr; dz++) {
                        for (int cy = Math.max(0, scy + 1); cy >= Math.min(scy - 4, -4); cy--) {
                            Chunk sc = world.getChunk(scx + dx, cy, scz + dz);
                            if (sc == null) continue;
                            if (sc.opaqueMesh != null)      sc.opaqueMesh.render();
                            if (sc.transparentMesh != null) sc.transparentMesh.render();  // leaves shade too
                        }
                    }
                }
                shadowMap.end(0);
                shadowsLive = true;
            }
            if (useSceneFbo) {
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
                    // HDR (half-float) so emissive light can exceed 1.0 and bloom properly.
                    org.lwjgl.opengl.GL11.glTexImage2D(
                            org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0,
                            org.lwjgl.opengl.GL30.GL_RGBA16F, fw[0], fh[0], 0,
                            org.lwjgl.opengl.GL11.GL_RGBA,
                            org.lwjgl.opengl.GL11.GL_FLOAT,
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

            // Orbital blackout: the SKY (clear colour) goes pure black so the only
            // light left in the world is the emissive lidar scan + laser flash.
            if (orbitalActive && orbDark) glClearColor(0f, 0f, 0f, 1f);
            else                          glClearColor(dayNight.skyHorizon.x, dayNight.skyHorizon.y,
                                                       dayNight.skyHorizon.z, 1f);

            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

            if (networkInitialized) {
                shader.bind();
                // Day/night drives the active luminary (sun by day, moon by night) + colour.
                shader.setUniform("sunDirection", dayNight.lightDir);
                // During the blackout, kill the sun + ambient so terrain is truly black.
                boolean orbBlack = orbitalActive && orbDark;
                shader.setUniform("sunStrength",     orbBlack ? 0f : dayNight.lightStrength);
                shader.setUniform("ambientStrength", orbBlack ? 0f : dayNight.ambientStrength);
                shader.setUniform("sunColor",     dayNight.lightColor);
                // Send the live sky colors and fog distance to the terrain shader
                shader.setUniform("skyHorizonCol", dayNight.skyHorizon);
                shader.setUniform("skyZenithCol",  dayNight.skyZenith);
                shader.setUniform("sunsetFactor",  dayNight.sunsetFactor);
                // Fog completely hides the square chunk loading edges
                shader.setUniform("fogEnd",        GameConfig.renderDistance * 16.0f);
                shader.setUniform("ambientColor", dayNight.ambientColor);
                uploadTorchLights(shader, camera);   // placed torches + held hand-light
                shader.setUniform("emissiveMode", 0);   // reset each frame (effects toggle it locally)
                // Orbital "lidar" scan + environmental flash uniforms.
                shader.setUniform("orbActive",    (orbitalActive && orbScanIntensity > 0.001f) ? 1 : 0);
                shader.setUniform("orbEpicenter", new Vector3f(orbEpiX, orbEpiY, orbEpiZ));
                shader.setUniform("orbRadius",    orbScanRadiusW);
                shader.setUniform("orbWidth",     6.0f);
                shader.setUniform("orbIntensity", orbScanIntensity);
                shader.setUniform("orbFlash",     orbitalActive ? orbFlashAmt : 0f);
                // "The World" time-stop domain.
                shader.setUniform("tsActive", timeStopActive ? 1 : 0);
                shader.setUniform("tsCenter", new Vector3f(tsCenterX, tsCenterY, tsCenterZ));
                shader.setUniform("tsRadius", tsRadiusNow);
                shader.setUniform("tsEdge",   2.5f);
                // Radar scope projected on the terrain.
                shader.setUniform("vlActive", vlActive ? 1 : 0);
                shader.setUniform("vlCenter", new Vector3f(vlCx, vlCy, vlCz));
                shader.setUniform("vlRadius", vlRadiusNow);
                shader.setUniform("vlAmount", vlAmountNow);
                shader.setUniform("vlSweep",  vlSweepNow);

                // Deprivation Domain world-shader tinting + special domain lighting.
                // depSweep is a ring radius that grows ~14 blocks/s and resets  -  the
                // repeating "360° detection" pulse traced across the terrain.
                float depSweepR = depActive
                        ? (depT * 8f) % Math.max(1f, GameConfig.depRadius)
                        : 0f;
                shader.setUniform("depActive", depActive ? 1 : 0);
                shader.setUniform("depCenter", new Vector3f(depX, depY + 0.9f, depZ));
                shader.setUniform("depRadius", GameConfig.depRadius);
                shader.setUniform("depStrike", depStrike);
                shader.setUniform("depSweep",  depSweepR);
                shader.setUniform("desaturate", ScreenEffectManager.INSTANCE.getDesaturate());

                boolean isCameraUnderwater = world.getBlock(
                        (int) Math.floor(camera.position.x),
                        (int) Math.floor(camera.position.y),
                        (int) Math.floor(camera.position.z)).isLiquid();
                shader.setUniform("isUnderwater", isCameraUnderwater ? 1 : 0);
                shader.setUniform("camPos", new Vector3f(camera.position.x, camera.position.y, camera.position.z));
                shader.setUniform("domeMode", 0);   // off  -  the dome is now real tube geometry, not a shader fill
                shader.setUniform("uTime", (float) glfwGetTime());   // water ripples, lava flow, glints
                // Shadow map (always keep a depth texture on its unit so the sampler is valid).
                if (shadowMap != null) {
                    shadowMap.bindForSampling();
                    shader.setUniform("lightVP", shadowMap.lightVP());
                    shader.setUniform("shadowTexel", shadowMap.texelWorld());
                }
                shader.setUniform("shadowOn", shadowsLive ? 1 : 0);

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
                shader.setUniform("snowAtmosphereStrength", showSnowAtmos ? snowAtmStr : 0f);

                // ── SNOW WEATHER INTENSITY (for particle effect) ──────────────
                // Starts fading in 70 blocks below snow altitude, full by 60 above.
                snowIntensity = 0f;
                if (showSnowAtmos && !isCameraUnderwater && lastEnv != AudioManager.ENV_CAVE) {
                    float sLow  = GameConfig.snowAltitude - 70f;
                    float sHigh = GameConfig.snowAltitude + 60f;
                    snowIntensity = Math.max(0f, Math.min(1f,
                            (camera.position.y - sLow) / (sHigh - sLow)));
                }
                snowTimeAccum += deltaTime;

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
                // sole source of colour effects  -  zero the 3-D overlay so the
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

                // ── FLIGHT CAMERA EFFECTS & TELESCOPE ZOOM ────────────────────
                boolean inStandView = player.stand.isInStandPerspective();
                if (!inStandView) {
                    float fovBoost = player.getCameraFovBoost();
                    float targetFov = holdingTelescope ? telescope.fovDeg : (GameConfig.fov + fovBoost);

                    // Smoothly lerp the FOV so the telescope zoom feels mechanical
                    currentFov += (targetFov - currentFov) * 12f * rawDeltaTime;

                    if (!holdingTelescope && Math.abs(currentFov - GameConfig.fov) < 0.1f && Math.abs(fovBoost) <= 0.1f) {
                        camera.dynamicFov = -1f;
                        currentFov = GameConfig.fov;
                    } else {
                        camera.dynamicFov = currentFov;
                    }
                }

                // ── VIEW MATRIX + ROLL ────────────────────────────────────────
                // When piloting the drone, all cosmetic effects are suppressed:
                // no roll, no screen shake, no attack pitch  -  clean drone view only.
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
// ── PASS 1: OPAQUE ────────────────────────────────────────────
                // Frustum culling uses the ACTUAL view matrix (including roll/shake)
                Matrix4f renderMvp = new Matrix4f(projection).mul(view);
                orbProjView.set(renderMvp);   // captured for the F7 cinematic overlay
                shader.setUniform("invViewProj", new Matrix4f(renderMvp).invert());
                shader.setUniform("screenSize", (float) fw[0], (float) fh[0]);
                // Share this frame's light with the model/creature shaders.
                SceneLighting.sunDir.set(dayNight.lightDir);
                SceneLighting.sunColor.set(dayNight.lightColor);
                SceneLighting.sunStrength     = (orbitalActive && orbDark) ? 0f : dayNight.lightStrength;
                SceneLighting.ambientColor.set(dayNight.ambientColor);
                SceneLighting.ambientStrength = (orbitalActive && orbDark) ? 0f : dayNight.ambientStrength;
                SceneLighting.skyZenith.set(dayNight.skyZenith);
                SceneLighting.skyHorizon.set(dayNight.skyHorizon);
                SceneLighting.sunsetFactor = dayNight.sunsetFactor;
                SceneLighting.camPos.set(camera.position);
                SceneLighting.fogEnd = GameConfig.renderDistance * 16.0f;
                SceneLighting.invViewProj.set(renderMvp).invert();
                SceneLighting.screenW = fw[0]; SceneLighting.screenH = fh[0];
                SceneLighting.shadowOn = shadowsLive;
                if (shadowMap != null) {
                    SceneLighting.lightVP.set(shadowMap.lightVP());
                    SceneLighting.shadowTexel = shadowMap.texelWorld();
                }
                float[] frustumPlanes = extractFrustumPlanes(renderMvp);

                // ── DAY/NIGHT SKY  -  gradient + sun + stars, behind everything ──
                if (!isPreloading && !(orbitalActive && orbDark)) {
                    renderSky(projection, view, camera);
                    renderMoon3D(projection, view, camera);     // 3D sphere moon
                    renderConstellations(projection, view, camera);
                    if (meteorShader != null && meteorSystem != null && dayNight.nightFactor > 0.05f) {
                        meteorShader.bind();

                        glEnable(GL_BLEND);
                        glBlendFunc(GL_SRC_ALPHA, GL_ONE);
                        glDepthMask(false);
                        glDisable(GL_DEPTH_TEST);

                        Matrix4f vp = new Matrix4f(projection).mul(view);
                        meteorShader.setUniform("viewProj", vp);

                        // FIX: Upload the raw meteor vertices to the VBO and render them
                        if (meteorSystem.getVertexCount() > 0) {
                            glBindVertexArray(meteorVao);
                            glBindBuffer(GL_ARRAY_BUFFER, meteorVbo);

                            // Upload the flat float buffer [x, y, z, trailFrac, brightness]
                            glBufferData(GL_ARRAY_BUFFER, meteorSystem.getGPUBuffer(), GL_DYNAMIC_DRAW);

                            glEnable(org.lwjgl.opengl.GL32.GL_PROGRAM_POINT_SIZE);
                            glDrawArrays(GL_POINTS, 0, meteorSystem.getVertexCount());
                            glDisable(org.lwjgl.opengl.GL32.GL_PROGRAM_POINT_SIZE);

                            glBindVertexArray(0);
                        }

                        glEnable(GL_DEPTH_TEST);
                        glDepthMask(true);
                        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
                        glDisable(GL_BLEND);
                        meteorShader.unbind();
                    }
                    shader.bind();   // restore the world shader for terrain (uniforms persist)
                }

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


                // ── BIND BLOCK TEXTURE ATLAS (if the PNG has been placed) ────
                boolean atlasActive = com.leaf.game.render.BlockTextureAtlas.isLoaded();
                if (atlasActive) {
                    org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
                    org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
                            com.leaf.game.render.BlockTextureAtlas.getTextureId());
                    shader.setUniform("texSampler", 0);
                    shader.setUniform("useTexture", 1);
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

                // ── ORBITAL ANNIHILATION: 3D effect geometry (inside the FBO so
                //    it gets the searing bloom)  -  drawn over opaque terrain ──────
                if (orbitalActive && !isPreloading) {
                    renderOrbital3D(shader, projection, view, renderMvp);
                }

                // ── METEOR STORM: glowing hot bodies + comet trails ─────────────
                if (!isPreloading) renderMeteors(shader, projection, view);

                // ── RADAR: 3D sweep blade, afterglow wedge, enemy wireframes ────
                if (vlActive && !isPreloading) {
                    renderRadar3D(shader, projection, view, renderMvp);
                }

                // ── CHOCOLATE DISCO: 9×9 glowing geometry grid ───────────────
                if (cdActive && !isPreloading) {
                    renderDiscoGrid(shader, projection, view, renderMvp);
                }

                // ── DEPRIVATION DOMAIN: golden hemisphere + thread web ────────
                if (depActive && !isPreloading) {
                    renderDeprivationDomain(shader, projection, view, renderMvp);
                }

                // ── Shared ability FX (slashes, rings, bolts, bursts) + quantum bullets ──
                if ((!fxList.isEmpty() || !qbBullets.isEmpty()) && !isPreloading) {
                    renderFx(shader, projection, view, renderMvp);
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

                // ── UNBIND ATLAS so subsequent passes use vertex colour ────────
                if (atlasActive) {
                    shader.setUniform("useTexture", 0);
                    org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0);
                }

                // ── PASS 3: OVERLAYS, ENTITIES & PROJECTILES (Blend Enabled) ──
                glEnable(GL_BLEND);
                glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

                // 1. Render Grapple Cable & Laser Sight
                FlightController fc = player.flightController;
                boolean grappleActive = player.isGrappleHooked; // Cleaned to read tool state
                boolean voidAiming = player.attacks.getChargeFrac() > 0f;

                if (grappleActive || voidAiming) {
                    Vector3f playerHand = new Vector3f(player.position.x, player.position.y + 0.9f, player.position.z);
                    Vector3f targetPoint = null;
                    boolean isLaser = false;
                    Block renderBlock = Block.CRYSTAL_AMETHYST;

                    if (grappleActive) {
                        targetPoint = player.grappleHookPoint; // Pointed to tool hook pos
                        isLaser = false;
                        renderBlock = Block.CRYSTAL_AMETHYST;
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

                // 5. Placed Seals (Normal Pass & Ghost Pass)  -  always lit so they're visible at night.
                if (!player.seals.placedSeals.isEmpty()) {
                    shader.setUniform("ambientStrength", Math.max(dayNight.ambientStrength, 0.80f));
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
                    shader.setUniform("ambientStrength", dayNight.ambientStrength); // restore
                }

                // 6. Render Enemies
                if (!enemyManager.getEnemies().isEmpty()) {
                    // RADAR: draw enemies as their EXACT model in see-through wireframe
                    // (no solid fill = transparent body) and ignore depth so contacts
                    // show through terrain  -  the "detection" look.
                    boolean radarWire = vlActive && vlAmountNow > 0.05f;
                    if (radarWire) {
                        org.lwjgl.opengl.GL11.glPolygonMode(
                                org.lwjgl.opengl.GL11.GL_FRONT_AND_BACK, org.lwjgl.opengl.GL11.GL_LINE);
                        org.lwjgl.opengl.GL11.glLineWidth(2.5f);   // bold contact lines
                        glDisable(GL_DEPTH_TEST);
                    }
                    if (enemyAnimModel != null) {
                        // ── Animated path: enemy_basic.json with walk/attack/death ──
                        // ── Animated path ──
                        for (Enemy enemy : enemyManager.getEnemies()) {
                            if (enemy == mpProxy) continue;   // invisible PvP hitbox  -  never drawn
                            // ── SPIDER RENDER CALL ──
                            if (enemy instanceof com.leaf.game.entity.spider.SpiderEnemy) {
                                com.leaf.game.entity.spider.SpiderEnemy spider = (com.leaf.game.entity.spider.SpiderEnemy) enemy;
                                float flashF = spider.hitFlashTimer > 0f ? (spider.hitFlashTimer / 0.18f) : 0f;
                                float alpha  = spider.alive ? 1.0f : flashF;

                                if (alpha > 0.02f) {
                                    shader.setUniform("alphaMultiplier", alpha);

                                    // Apply damage flash overlay
                                    if (spider.hitFlashTimer > 0f) {
                                        shader.setUniform("overlayVignetteStrength", 0.6f * flashF);
                                        shader.setUniform("overlayVignetteColor", new Vector3f(1.0f, 0.25f, 0.25f));
                                    }

                                    // DELEGATE TO OUR NEW RENDERER
                                    com.leaf.game.entity.spider.SpiderRenderer.render(spider, shader, projection, view);

                                    // Reset shader uniforms
                                    shader.setUniform("overlayVignetteStrength", 0f);
                                    shader.setUniform("overlayVignetteColor", new Vector3f(0f, 0f, 0f));
                                    shader.setUniform("alphaMultiplier", 1.0f);
                                    shader.setUniform("alphaMultiplier", 1.0f);
                                }
                                continue; // Skip the humanoid animation rendering below!
                            }
                            if (enemy.type == Enemy.Type.TREANT) {
                                TreantRenderer.render((TreantEnemy) enemy, shader, projection, view);
                                continue;
                            }
                            
                            float flashF = enemy.hitFlashTimer > 0f ? (enemy.hitFlashTimer / 0.18f) : 0f;
                            float alpha  = enemy.alive ? 1.0f : flashF;
                            if (alpha < 0.02f) {
                                enemyAnimPlayers.remove(enemy.id); continue;
                            }

                            // Pick the right model for the enemy type
                            boolean isGuardian = enemy.type == Enemy.Type.GUARDIAN || enemy.type == Enemy.Type.GOLEM;

                            com.leaf.game.anim.AnimModel targetModel =
                                    (enemy.type == Enemy.Type.SLIME    && slimeAnimModel != null) ? slimeAnimModel
                                            : (enemy.type == Enemy.Type.LAVA_SLIME    && lavaSlimeAnimModel    != null) ? lavaSlimeAnimModel
                                              : (enemy.type == Enemy.Type.INFERNO_TOWER && infernoTowerAnimModel != null) ? infernoTowerAnimModel
                                                : (isGuardian                        && golemAnimModel != null) ? golemAnimModel
                                                  : enemyAnimModel; // <-- Restored standard enemy model

                            if (targetModel == null) continue; // Now this only triggers if the JSON file actually failed to load
                            com.leaf.game.anim.AnimPlayer ap = enemyAnimPlayers.computeIfAbsent(
                                    enemy.id, id -> {
                                        com.leaf.game.anim.AnimPlayer p = new com.leaf.game.anim.AnimPlayer(targetModel);
                                        p.play("idle");
                                        return p;
                                    });

                            // Decide target animation from AI state
                            String want;
                            if (enemy.type == Enemy.Type.INFERNO_TOWER) {
                                // Static structure  -  its only clip is the importer's bind pose.
                                want = "idle";
                            } else if (isGuardian) {
                                // The guardian drives its own clip (patrol walk/idle + 3-hit combo).
                                want = enemy.getAnimName();
                            } else if (!enemy.alive) {
                                want = "death";
                            } else if (enemy.state == Enemy.State.CHASE || enemy.state == Enemy.State.RETREATING) {
                                // ── USE "move" FOR SLIMES, "walk" FOR OTHERS ──
                                want = (enemy.type == Enemy.Type.SLIME
                                        || enemy.type == Enemy.Type.LAVA_SLIME) ? "move" : "walk";
                            } else if (enemy.state == Enemy.State.ATTACK || enemy.state == Enemy.State.SLAMMING) {
                                want = "attack";
                            } else {
                                want = "idle";
                            }

                            String cur = ap.getCurrentClip();
                            if (!want.equals(cur)) {
                                // Guardian attack clips are one-shot (1.3 s swing); everything else loops.
                                boolean once = isGuardian && want.startsWith("attack");
                                if ("death".equals(want)) {
                                    ap.play("death", false);
                                } else if (once) {
                                    ap.play(want, false);
                                } else if (cur == null || "idle".equals(cur) || "death".equals(cur)) {
                                    ap.play(want);
                                } else {
                                    ap.crossfade(want, 0.12f);
                                }
                            }
                            // Time stop: freeze the animation clock for every enemy
                            // so idle/walk/attack loops halt mid-frame.
                            ap.tick(timeStopActive ? 0f : rawDeltaTime);

                            // Face the player (Y-axis rotation only).
                            // The golem's front is on the +Z face, so it needs NO +PI flip
                            // (the slime/enemy_basic front is -Z and does). The guardian
                            // controls its own facing (patrol heading / toward player).
                            float faceY;
                            if (enemy.type == Enemy.Type.INFERNO_TOWER) {
                                faceY = 0f;   // a building does not turn to watch you
                            } else if (isGuardian) {
                                faceY = enemy.facingYaw;
                            } else {
                                float faceDx = player.position.x - enemy.position.x;
                                float faceDz = player.position.z - enemy.position.z;
                                faceY  = (float) Math.atan2(faceDx, faceDz) + (float) Math.PI;
                            }

                            float[] sv = enemy.renderScaleVec();
                            // Models' origins sit slightly below their feet, so bodies
                            // visually sink into the floor — worse the bigger the scale.
                            // Lift proportionally to render height.
                            float lift = 0.15f * sv[1];
                            Matrix4f worldMat = new Matrix4f()
                                    .translate(enemy.position.x, enemy.position.y + lift, enemy.position.z)
                                    .rotateY(faceY)
                                    .scale(sv[0], sv[1], sv[2]);

                            // RADAR: bright-green tint, depth off (through walls), and a
                            // searing glow spike the instant the sweep arm crosses this
                            // enemy's bearing  -  fading over the afterglow. Always at least
                            // base-bright so every contact (even far/hidden) reads clearly.
                            if (radarWire) {
                                float dxb = enemy.position.x - vlCx, dzb = enemy.position.z - vlCz;
                                float bearing = (float) Math.atan2(dzb, dxb);
                                float behind = ((vlSweepNow - bearing) % 6.2831853f + 6.2831853f) % 6.2831853f;
                                float ping = (float) Math.exp(-behind * 2.2f);   // sharp flare just behind the arm
                                // Resting = bright green; on contact the colour shifts white-hot
                                // (reads as "hotter" even through 8-bit clamping) + a glow boost
                                // so the bloom halo flares. Always ≥ base-bright so every contact
                                // (far or wall-occluded) is clearly visible.
                                float tr = 0.1f + 0.85f * ping;
                                float tb = 0.35f + 0.6f * ping;
                                com.leaf.game.anim.ModelRenderer.setOverride(
                                        tr, 1.0f, tb, 1.0f, 1.6f + 2.4f * ping, false);
                            }

                            // Molten enemies smoulder: a faint warm tint + brightness pulse,
                            // surging red-hot for a beat whenever they take a hit. (Radar
                            // wireframe owns the override while it is active.)
                            boolean molten = enemy.type == Enemy.Type.LAVA_SLIME
                                    || enemy.type == Enemy.Type.INFERNO_TOWER;
                            if (molten && !radarWire) {
                                float hurt  = enemy.hitFlashTimer > 0f ? (enemy.hitFlashTimer / 0.18f) : 0f;
                                float pulse = 0.5f + 0.5f * (float) Math.sin(glfwGetTime() * 2.4 + enemy.id);
                                com.leaf.game.anim.ModelRenderer.setOverride(
                                        1.0f, 0.42f + 0.3f * hurt, 0.16f,
                                        0.06f + 0.30f * hurt,
                                        1.10f + 0.10f * pulse + 0.85f * hurt,
                                        true);
                            }
                            com.leaf.game.anim.ModelRenderer.render(
                                    targetModel, ap.getPose(), worldMat, view, projection);
                            if (molten && !radarWire) com.leaf.game.anim.ModelRenderer.clearOverride();

                            shader.bind();
                        }
                    } else {
                        // ── Fallback: plain capsule (no model loaded) ──────────
                        com.leaf.game.render.ModelMesh capsule =
                                com.leaf.game.render.AssetManager.get().getModel("player");
                        for (Enemy enemy : enemyManager.getEnemies()) {
                            if (enemy == mpProxy) continue;   // invisible PvP hitbox  -  never drawn
                            float flashF = enemy.hitFlashTimer > 0f
                                    ? (enemy.hitFlashTimer / 0.18f) : 0f;
                            float alpha  = enemy.alive ? 1.0f : flashF;
                            if (alpha < 0.02f) continue;
                            float[] sv = enemy.renderScaleVec();
                            Matrix4f enemyMat = new Matrix4f()
                                    .translate(enemy.position.x, enemy.position.y + 0.15f * sv[1],
                                               enemy.position.z)
                                    .scale(sv[0], sv[1], sv[2]);
                            shader.setUniform("alphaMultiplier", alpha);
                            shader.setUniform("mvp", new Matrix4f(projection).mul(view).mul(enemyMat));
                            capsule.render();
                        }
                    }
                    // Reset overlay state
                    shader.setUniform("overlayVignetteStrength", 0f);
                    shader.setUniform("overlayVignetteColor", new Vector3f(0f, 0f, 0f));
                    shader.setUniform("alphaMultiplier", 1.0f);
                    if (radarWire) {   // restore solid fill + depth after the wireframe pass
                        com.leaf.game.anim.ModelRenderer.clearOverride();
                        glEnable(GL_DEPTH_TEST);
                        org.lwjgl.opengl.GL11.glPolygonMode(
                                org.lwjgl.opengl.GL11.GL_FRONT_AND_BACK, org.lwjgl.opengl.GL11.GL_FILL);
                        org.lwjgl.opengl.GL11.glLineWidth(1.0f);
                    }
                }

                // ── DEPRIVATION DOMAIN: sliced corpses (real model -> 8 flying octants) ──
                if (depActive && !isPreloading) {
                    renderDepCorpses(view, projection);
                    shader.bind();   // restore the world shader for what follows
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
                    // Scale grows from 0.08 -> max scale as charge builds
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

                    // ── CHARGE: a fiery triangular CONE forming, aimed where it fires.
                    //    White-hot tip -> deep-red base, growing + pulsing with charge. ──
                    Matrix4f pvSC = new Matrix4f(projection).mul(view);
                    Vector3f look = camera.getLookDirection();
                    float scCx = stoneCanonGroundPos.x, scCy = riseY, scCz = stoneCanonGroundPos.z;
                    float cp    = chargeProgress;
                    float pulse = 0.88f + 0.12f * (float) Math.sin(timeSecs2 * 16);
                    float coneLen = 0.8f + cp * 4.2f + chargeScale;     // grows forward
                    float coneRad = 0.28f + cp * 0.9f + chargeScale * 0.4f;
                    float br = (0.55f + 0.9f * cp) * pulse;
                    glEnable(GL_BLEND); glBlendFunc(GL_ONE, GL_ONE);
                    glDepthMask(false); glDepthFunc(GL_LEQUAL); glDisable(GL_CULL_FACE);
                    shader.setUniform("emissiveMode", 1);
                    // A gathering star of heat instead of a solid cone: a soft molten
                    // glow stretched along the aim, a white-hot core, and embers
                    // spiralling inward as the charge builds.
                    if (fxGlowSphere == null) fxGlowSphere = buildNormalSphere(12, 18);
                    Matrix4f aim = faceMatrix(scCx, scCy, scCz, look.x, look.y, look.z);
                    orbDrawSoft(shader, pvSC, fxGlowSphere,
                            new Matrix4f(aim).translate(0f, 0f, coneLen * 0.35f)
                                    .scale(coneRad * 1.5f, coneRad * 1.5f, coneLen * 0.55f),
                            br * 0.95f, br * 0.30f, br * 0.07f, 1);
                    orbDrawSoft(shader, pvSC, fxGlowSphere,
                            new Matrix4f(aim).scale(coneRad * 0.55f),
                            br * 1.6f, br * 1.3f, br * 0.8f, 1);
                    if (orbCyl == null) orbCyl = orbBuildCylinder(28);
                    for (int k = 0; k < 8; k++) {
                        float ph  = ((timeSecs2 * (0.7f + cp)) + k / 8f) % 1f;   // 0 far → 1 arrived
                        float ang = k * 0.785f + timeSecs2 * 3f;
                        float rad = coneRad * 3.2f * (1f - ph);
                        float ex = scCx + (float) Math.cos(ang) * rad, ez = scCz + (float) Math.sin(ang) * rad;
                        float ey = scCy + (float) Math.sin(ang * 1.7f) * rad * 0.5f;
                        float dx = scCx - ex, dy = scCy - ey, dz = scCz - ez;
                        float dl = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                        if (dl < 0.05f) continue;
                        float eb = (float) Math.sin(ph * Math.PI) * br * 1.4f;
                        orbDraw(shader, pvSC, orbCyl, cylAlong(ex, ey, ez, dx / dl, dy / dl, dz / dl,
                                Math.min(0.6f, dl), 0.025f), eb * 1.2f, eb * 0.6f, eb * 0.2f);
                    }
                    shader.setUniform("emissiveMode", 0);
                    shader.setUniform("emissiveTint", new Vector3f(1f, 1f, 1f));
                    glDepthFunc(GL_LESS); glDepthMask(true); glDisable(GL_CULL_FACE); glDisable(GL_BLEND);
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
                            // Scale expands slightly as timer runs out (0.9 -> 1.2)
                            float dscale = 0.9f + (1f - lifeFrac) * 0.3f;
                            float alpha  = Math.max(0.15f, lifeFrac * 0.90f);
                            shader.setUniform("alphaMultiplier", alpha);
                            // Dark silhouette  -  near-black with faint blue tinge
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

                // 7. Render Items  -  emissive so they're always bright and easy to spot.
                shader.setUniform("emissiveMode", 1);
                shader.setUniform("emissiveTint", new Vector3f(1.2f, 1.2f, 1.2f));
                for (DroppedItem item : droppedItems) {
                    float bob = (float) Math.sin(item.age * 3.0f) * 0.05f;
                    Matrix4f itemModel = new Matrix4f()
                            .translate(item.position.x, item.position.y + bob, item.position.z)
                            .rotateY(item.age * 1.5f);
                    if (item.blockType == Block.HOTDOG) {
                        // Sausage in a bun: two bread-coloured boxes + a longer red
                        // sausage nested between them. Pure block-colour meshes.
                        Matrix4f bun = new Matrix4f(itemModel).scale(1.0f, 0.55f, 0.85f);
                        shader.setUniform("mvp", new Matrix4f(projection).mul(view).mul(bun));
                        getItemMesh(Block.SAND).render();
                        Matrix4f sausage = new Matrix4f(itemModel)
                                .translate(0f, 0.07f, 0f)
                                .scale(1.35f, 0.38f, 0.38f);
                        shader.setUniform("mvp", new Matrix4f(projection).mul(view).mul(sausage));
                        getItemMesh(Block.HOTDOG).render();
                        continue;
                    }
                    Mesh itemMesh = getItemMesh(item.blockType);
                    Matrix4f itemMvp = new Matrix4f(projection).mul(view).mul(itemModel);
                    shader.setUniform("mvp", itemMvp);
                    itemMesh.render();
                }
                shader.setUniform("emissiveMode", 0);  // restore normal lighting

                // 8. Render Remote Player
                if (network != null && network.connected) {
                    remotePlayer.render(shader, projection, view);
                    renderSummons(shader, projection, view);   // cyan = yours, red = theirs
                }
                // 8b. Render 3D Flappy Bird Player (Only when in 2D Side-View!)
                if (player.useTestMovement && player.testMovement.state == TestMovementController.State.FLAPPY && player.testMovement.flappySideView) {
                    renderYellowBird(shader, projection, view);
                }

                // 9. Render Ability Ghost Trails
                renderAbilityGhosts(shader, projection, view);
                // ── GATLING GUN: first-person spinning-barrel viewmodel ─────────
                if (!isPreloading) renderGatlingViewModel(shader, projection, view, camera);
                // ── Render Laser Pointer Dot ──
                if (spiderLaserActive && lastTarget != null && lastTarget.hit) {
                    glDisable(GL_DEPTH_TEST);
                    shader.setUniform("emissiveMode", 1);
                    shader.setUniform("emissiveTint", new Vector3f(3.0f, 0.2f, 0.2f)); // Glowing red

                    Matrix4f dotMat = new Matrix4f()
                            .translate(lastTarget.hitX + 0.5f, lastTarget.hitY + 1.05f, lastTarget.hitZ + 0.5f)
                            .scale(0.15f);
                    shader.setUniform("mvp", new Matrix4f(projection).mul(view).mul(dotMat));
                    getItemMesh(Block.CRATER_BLOOM).render(); // Use any simple block mesh

                    shader.setUniform("emissiveMode", 0);
                    shader.setUniform("emissiveTint", new Vector3f(1f, 1f, 1f));
                    glEnable(GL_DEPTH_TEST);
                }
                // 10. Knife view model  -  disabled (re-enable when model is ready)
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
                    distortShader.setUniform("qbStrength", 0f);   // kamui, not a quantum warp

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
                } else if (doQuantumWarp) {
                    // ── QUANTUM SURFACE WARP  -  ripple the wall texture (the scene)
                    //    around the phasing bullet's screen position. ──────────────
                    org.lwjgl.opengl.GL30.glBindFramebuffer(
                            org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, 0);
                    glClear(GL_COLOR_BUFFER_BIT);

                    org.joml.Vector4f clip = new Matrix4f(projection).mul(view)
                            .transform(new org.joml.Vector4f(qbWarpX, qbWarpY, qbWarpZ, 1f));
                    // World-anchored: re-project every frame so the warp stays on the
                    // block when you turn. If it's behind you, push it off-screen so it
                    // fades cleanly (rather than snapping to screen centre).
                    float sx = -5f, sy = -5f;
                    if (clip.w > 1e-4f) {
                        sx = (clip.x / clip.w) * 0.5f + 0.5f;
                        sy = (clip.y / clip.w) * 0.5f + 0.5f;
                    }
                    distortShader.bind();
                    distortShader.setUniform("screenTexture", 0);
                    distortShader.setUniform("time", (float) glfwGetTime());
                    distortShader.setUniform("kamuiCharge", 0f);
                    distortShader.setUniform("aspectRatio", (float) ww[0] / Math.max(1, wh[0]));
                    distortShader.setUniform("qbStrength", Math.min(1f, qbWarpStrength));
                    distortShader.setUniform("qbCenter", sx, sy);

                    org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
                    org.lwjgl.opengl.GL11.glBindTexture(
                            org.lwjgl.opengl.GL11.GL_TEXTURE_2D, kamuiFboTex);
                    glDisable(GL_DEPTH_TEST);
                    org.lwjgl.opengl.GL30.glBindVertexArray(kamuiScreenQuad);
                    glDrawArrays(GL_TRIANGLES, 0, 6);
                    org.lwjgl.opengl.GL30.glBindVertexArray(0);
                    glEnable(GL_DEPTH_TEST);
                    distortShader.unbind();
                } else if (useSceneFbo) {
                    // ── BLOOM + GRADE (every frame) ────────────────────────────
                    // Ability cinematics (orbital, radar, domain, disco, big FX)
                    // lower the threshold and push the glow so their emissive energy
                    // sears; otherwise only true HDR emitters (lava, glints) bloom.
                    postFx.apply(kamuiFboTex, fw[0], fh[0],
                            1.0f,                          // only true HDR emitters glow
                            doBloom ? 0.45f : 0.30f,       // restrained: a halo, never a wash
                            depActive ? depStrike : 0f,
                            GameConfig.vignette,
                            (float) glfwGetTime());
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
                } else if (cutscene.isActive()) {
                    // Cutscene takes over the screen (world still renders behind, dimmed).
                    cutscene.render((float) ww[0], (float) wh[0]);
                } else if (hudHidden && !showChat) {
                    // Clean-frame mode (/hud): world + combat text only, for screenshots & trailers.
                    renderDamageNumbers(ww[0], wh[0]);
                } else {
                    renderDamageNumbers(ww[0], wh[0]);
                    hud.renderHUD(camera, ww[0], wh[0]);
                    hud.renderTargetCracks(camera, ww[0], wh[0]);
                    if (showDebug)       hud.renderDebugMenu();
                    if (showNoiseViewer) noiseVis.renderWindow(player);
                    if (showChat || !chatHistory.isEmpty()) hud.renderChatBox(wh[0]);
                    if (isPaused)         hud.renderPauseMenu(ww[0], wh[0]);
                    if (showHelp)         hud.renderHelpScreen((float)ww[0], (float)wh[0]);
                    if (showUnlockCard)         hud.renderUnlockCard((float)ww[0], (float)wh[0]);
                    if (practiceAbility != null) hud.renderPractice((float)ww[0], (float)wh[0]);
                    if (weaponRevealTimer > 0f && weaponRevealAbility != null)
                        hud.renderWeaponReveal((float)ww[0], (float)wh[0]);
                    if (showDeathScreen)         hud.renderDeathScreen((float)ww[0], (float)wh[0]);
                    if (finale != null && finale.active()) hud.renderFinale((float)ww[0], (float)wh[0]);
                    hud.renderChocolateDiscoConsole();
                    // (The Orbital Annihilation cinematic is now drawn as real 3D
                    //  geometry during the world pass  -  see renderOrbital3D.)
                    // Screen flash overlay (snipe, explosion, melee hit, etc.)
                    ScreenEffectManager.INSTANCE.renderFlash(ww[0], wh[0]);
                    // Snow particle overlay  -  drawn on top of world, under flash
                    hud.renderSnowEffect(ww[0], wh[0], snowTimeAccum,
                                         snowIntensity, windGustStrength, windGustAngle);
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
        com.leaf.game.entity.spider.SpiderRenderer.cleanup();
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
        // smashImpactY is the player's feet level (first AIR block)  -  sample one block down for the actual ground material.
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
     * Damped sinusoidal: amplitude decays linearly from smashShakeAmplitude -> 0
     * over smashShakeDuration seconds.
     *
     * The caller adds this to camera.position before building the view matrix,
     * then subtracts it afterward  -  player position accounting is unaffected.
     *
     * @param rawDt raw (unscaled) frame time, used to tick the shake timer
     * @return offset vector (zero when no shake active)
     */
    private Vector3f computeShakeOffset(float rawDt) {
        if (smashShakeTimer <= 0f) return new Vector3f(0f);

        smashShakeTimer = Math.max(0f, smashShakeTimer - rawDt);

        float progress    = smashShakeTimer / activeShakeDuration; // Dynamic duration
        // Comfort: half-strength shake  -  full amplitude made playtesters dizzy.
        float amplitude   = progress * activeShakeAmplitude * 0.5f;
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
        // Only visible while actively swinging  -  not a permanent view model
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

    /** Render MP troops as solid cubes  -  cyan for ours, red for the opponent's. */
    private void renderSummons(Shader shader, Matrix4f projection, Matrix4f view) {
        if (network == null || !network.connected) return;
        if (summonCubeMine == null) summonCubeMine = buildColorCube(0.20f, 0.95f, 1.00f); // cyan = ours
        if (summonCubeFoe  == null) summonCubeFoe  = buildColorCube(1.00f, 0.25f, 0.18f); // red  = theirs
        Matrix4f pv = new Matrix4f(projection).mul(view);
        for (com.leaf.game.entity.Summon s : mySummons) {
            shader.setUniform("mvp", new Matrix4f(pv).mul(
                    new Matrix4f().translate(s.pos.x, s.pos.y, s.pos.z).scale(0.45f)));
            summonCubeMine.render();
        }
        float[] rs = network.remoteSummons;
        for (int i = 0; i + 2 < rs.length; i += 3) {
            shader.setUniform("mvp", new Matrix4f(pv).mul(
                    new Matrix4f().translate(rs[i], rs[i + 1], rs[i + 2]).scale(0.45f)));
            summonCubeFoe.render();
        }
    }

    /** A unit cube centred at the origin, single colour, in the 10-float vertex layout. */
    private com.leaf.game.render.Mesh buildColorCube(float r, float g, float b) {
        float[][] c = {
            {-.5f,-.5f, .5f},{ .5f,-.5f, .5f},{ .5f, .5f, .5f},{-.5f, .5f, .5f}, // front
            { .5f,-.5f,-.5f},{-.5f,-.5f,-.5f},{-.5f, .5f,-.5f},{ .5f, .5f,-.5f}, // back
            {-.5f, .5f,-.5f},{ .5f, .5f,-.5f},{ .5f, .5f, .5f},{-.5f, .5f, .5f}, // top
            {-.5f,-.5f, .5f},{ .5f,-.5f, .5f},{ .5f,-.5f,-.5f},{-.5f,-.5f,-.5f}, // bottom
            { .5f,-.5f, .5f},{ .5f,-.5f,-.5f},{ .5f, .5f,-.5f},{ .5f, .5f, .5f}, // right
            {-.5f,-.5f,-.5f},{-.5f,-.5f, .5f},{-.5f, .5f, .5f},{-.5f, .5f,-.5f}, // left
        };
        float[] v = new float[24 * 10];
        int[]   idx = new int[36];
        int vo = 0, io = 0;
        for (int face = 0; face < 6; face++) {
            float shade = (face == 2) ? 1.0f : (face == 3 ? 0.6f : 0.82f);
            for (int i = 0; i < 4; i++) {
                float[] p = c[face * 4 + i];
                v[vo++] = p[0]; v[vo++] = p[1]; v[vo++] = p[2];
                v[vo++] = r * shade; v[vo++] = g * shade; v[vo++] = b * shade; v[vo++] = 1f;
                v[vo++] = 0f; v[vo++] = 1f; v[vo++] = 0f;
            }
            int base = face * 4;
            idx[io++] = base; idx[io++] = base + 1; idx[io++] = base + 2;
            idx[io++] = base + 2; idx[io++] = base + 3; idx[io++] = base;
        }
        return new com.leaf.game.render.Mesh(v, idx);
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

    public Mesh getItemMesh(Block block) {
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
            case DIRT, GRASS, MUD, ANCIENT_SOIL,
                 MESA_GRASS, MESA_DIRT,
                 MESA_BLUE_SNOW, MESA_BLUE_SOIL                  -> "block_soil";
            case SAND, RED_SAND, GRAVEL, SNOW, MESA_SAND         -> "block_sand";
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
            case DIRT, GRASS, MUD, ANCIENT_SOIL,
                 MESA_GRASS, MESA_DIRT,
                 MESA_BLUE_SNOW, MESA_BLUE_SOIL                  -> "block_soil";
            case SAND, RED_SAND, GRAVEL, SNOW, MESA_SAND         -> "block_sand";
            case CRYSTAL_AMETHYST, CRYSTAL_QUARTZ,
                 CRYSTAL_CITRINE, CRYSTAL_ROSE                   -> "block_crystal";
            default                                              -> "block_stone";
        };
    }

    /**
     * Returns the dig sound to play while the player is actively breaking a block.
     * Crystal returns the special sentinel {@code "crystal_clank_seq"}  -  the caller
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
            case DIRT, GRASS, MUD, ANCIENT_SOIL,
                 MESA_GRASS, MESA_DIRT,
                 MESA_BLUE_SNOW, MESA_BLUE_SOIL                  -> "soil_digging";
            case SAND, RED_SAND, GRAVEL, SNOW,
                 MESA_SAND, MESA_BLUE_LIGHT                      -> "sand_digging";
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

    // ═══════════════════════════════════════════════════════════════════════════
    //  NON-EUCLIDEAN "LAYERED ROOMS"  (F6)
    // ═══════════════════════════════════════════════════════════════════════════
    //
    // One physical 2×2 building with a solid central cross-pillar and four
    // doorways arranged as a clockwise ring around the centre:
    //
    //        NW ── door ── NE          quadrant indices (clockwise):
    //        │              │              0 = NW   1 = NE
    //       door   PILLAR  door             3 = SW   2 = SE
    //        │              │
    //        SW ── door ── SE
    //
    // Walking clockwise you go NW->NE->SE->SW->NW physically, but logically the room
    // number keeps climbing (1,2,3,4,5,6…). Each quadrant is skinned with a room
    // colour. The visible rooms (current + its two neighbours) always show three
    // consecutive numbers; the DIAGONAL quadrant is fully hidden behind the solid
    // pillar, so when you round a corner we silently repaint that hidden room to
    // be the next colour in the sequence. You never witness the swap.

    /** Relative interior bounds {rxLo,rxHi,rzLo,rzHi} of a quadrant (0=NW 1=NE 2=SE 3=SW). */
    private int[] nerQuadBounds(int quad) {
        int lo = 1, hi = NER_ROOM;                      // 1..5  (west / north interior)
        int eLo = NER_ROOM + 2, eHi = 2 * NER_ROOM + 1; // 7..11 (east / south interior)
        return switch (quad) {
            case 0 -> new int[]{ lo,  hi,  lo,  hi  };  // NW
            case 1 -> new int[]{ eLo, eHi, lo,  hi  };  // NE
            case 2 -> new int[]{ eLo, eHi, eLo, eHi };  // SE
            default-> new int[]{ lo,  hi,  eLo, eHi };  // SW
        };
    }

    /** Palette colour for a logical room number (cycles; handles negatives). */
    private Block nerPaletteFor(int room) {
        int n = NER_PALETTE.length;
        int i = ((room - 1) % n + n) % n;
        return NER_PALETTE[i];
    }

    /** Logical room number shown in the quadrant that is {@code offset} clockwise
     *  steps ahead of the player's current quadrant. The counter-clockwise
     *  neighbour (offset 3) shows N-1  -  the room you just came from. */
    private int nerLogicalRoom(int offset, int base) {
        return switch (offset) {
            case 1 -> base + 1;   // clockwise neighbour (ahead)
            case 2 -> base + 2;   // diagonal (hidden  -  becomes "ahead" next step)
            case 3 -> base - 1;   // counter-clockwise neighbour (behind)
            default-> base;       // current room
        };
    }

    /** Paint a quadrant's floor + ceiling with its room colour (no remesh here). */
    private void nerThemeQuadrant(int quad, Block block) {
        int[] q = nerQuadBounds(quad);
        int yFloor = NER_Y0;
        int yCeil  = NER_Y0 + NER_HT + 1;
        for (int rx = q[0]; rx <= q[1]; rx++) {
            for (int rz = q[2]; rz <= q[3]; rz++) {
                world.setBlockWithMeta(NER_X0 + rx, yFloor, NER_Z0 + rz, block, (byte) 0, false);
                world.setBlockWithMeta(NER_X0 + rx, yCeil,  NER_Z0 + rz, block, (byte) 0, false);
            }
        }
        nerQuadBlock[quad] = block;
    }

    /** Rebuild the chunk meshes the complex occupies (it fits in ~1 chunk). */
    private void nerRebuildChunks() {
        int cxLo = Math.floorDiv(NER_X0, Chunk.SIZE);
        int cxHi = Math.floorDiv(NER_X0 + NER_SPAN - 1, Chunk.SIZE);
        int czLo = Math.floorDiv(NER_Z0, Chunk.SIZE);
        int czHi = Math.floorDiv(NER_Z0 + NER_SPAN - 1, Chunk.SIZE);
        int cyLo = Math.floorDiv(NER_Y0, Chunk.HEIGHT);
        int cyHi = Math.floorDiv(NER_Y0 + NER_HT + 1, Chunk.HEIGHT);
        for (int cx = cxLo; cx <= cxHi; cx++)
            for (int cz = czLo; cz <= czHi; cz++)
                for (int cy = cyLo; cy <= cyHi; cy++) {
                    Chunk c = world.getOrCreateChunk(cx, cy, cz);
                    c.noEvict = true;
                    world.buildChunkMeshes(c);
                    c.state = Chunk.ChunkState.MESHED;
                }
    }

    /** Recompute every quadrant's target colour; repaint+remesh only those that
     *  changed (in practice just the hidden diagonal). This is the seamless swap. */
    private void nerApplyThemes() {
        boolean changed = false;
        for (int j = 0; j < 4; j++) {
            int offset = (j - nerQuad + 4) % 4;
            Block want = nerPaletteFor(nerLogicalRoom(offset, nerRoom));
            if (nerQuadBlock[j] != want) { nerThemeQuadrant(j, want); changed = true; }
        }
        if (changed) nerRebuildChunks();
    }

    /** Construct the sealed 2×2 complex once. */
    private void buildLayeredRooms() {
        // Raw geometry: outer shell, floor, ceiling, central cross with 4 door gaps.
        for (int rx = 0; rx < NER_SPAN; rx++) {
            for (int rz = 0; rz < NER_SPAN; rz++) {
                for (int ry = 0; ry <= NER_HT + 1; ry++) {
                    boolean floor   = (ry == 0);
                    boolean ceiling = (ry == NER_HT + 1);
                    boolean outer   = (rx == 0 || rx == NER_SPAN - 1 || rz == 0 || rz == NER_SPAN - 1);
                    boolean divider = (rx == NER_SPAN / 2 || rz == NER_SPAN / 2);
                    // Door gaps in the divider, one per clockwise arm (3 tall).
                    boolean door =
                           (rx == 6 && rz == 3)   // NW ↔ NE
                        || (rz == 6 && rx == 9)   // NE ↔ SE
                        || (rx == 6 && rz == 9)   // SE ↔ SW
                        || (rz == 6 && rx == 3);  // SW ↔ NW
                    boolean doorOpen = door && ry >= 1 && ry <= 3;

                    Block b;
                    if (floor || ceiling)      b = Block.STONE;          // re-skinned per room
                    else if (outer)            b = Block.STONE;          // sealed shell
                    else if (divider)          b = doorOpen ? Block.AIR : Block.STONE;
                    else                       b = Block.AIR;            // walkable interior

                    world.setBlockWithMeta(NER_X0 + rx, NER_Y0 + ry, NER_Z0 + rz, b, (byte) 0, false);
                }
            }
        }
        // Initial colours for room 1, player starting in NW.
        nerQuad = 0; nerRoom = 1;
        java.util.Arrays.fill(nerQuadBlock, null);
        nerApplyThemes();   // themes all four + meshes the chunk
        nerBuilt = true;
    }

    /** Which quadrant the player is well inside (0..3), or -1 while in a doorway. */
    private int nerQuadrantAt(float px, float pz) {
        float drx = px - NER_CX;
        float drz = pz - NER_CZ;
        if (Math.abs(drx) < 1.5f || Math.abs(drz) < 1.5f) return -1; // transition zone
        boolean east = drx > 0, south = drz > 0;
        if (!east && !south) return 0; // NW
        if ( east && !south) return 1; // NE
        if ( east &&  south) return 2; // SE
        return 3;                      // SW
    }

    /** F6 with the anomaly closed: build it (once), remember where we were, warp in. */
    private void enterLayeredRooms(Camera camera) {
        nerPrevX = player.position.x; nerPrevY = player.position.y; nerPrevZ = player.position.z;
        nerPrevYaw = camera.yaw; nerPrevPitch = camera.pitch;

        // Always (re)build: it's a single chunk and guarantees the geometry exists
        // even if the chunk was evicted between sessions. Resets to room 1 / NW.
        buildLayeredRooms();

        // Stand in the centre of the NW quadrant, facing +X toward the NE doorway.
        player.position.x = NER_X0 + 3 + 0.5f;
        player.position.y = NER_Y0 + 1.0f;
        player.position.z = NER_Z0 + 3 + 0.5f;
        player.setVelocityY(0f);
        player.highestY = player.position.y;   // no phantom fall damage on arrival
        camera.yaw   = 0f;   // +X (east)
        camera.pitch = 0f;
        nerActive = true;

        if (enemyManager != null) {
            nerPrevWaves = enemyManager.wavesEnabled;
            enemyManager.wavesEnabled = false;   // keep the anomaly calm
        }
        hintText  = "LAYERED ROOMS   -   walk CLOCKWISE around the pillar.   (F6 to exit)";
        hintTimer = 7f;
    }

    /** F6 while inside: drop the player back exactly where they came from. */
    private void exitLayeredRooms(Camera camera) {
        player.position.x = nerPrevX; player.position.y = nerPrevY; player.position.z = nerPrevZ;
        player.setVelocityY(0f);
        player.highestY = nerPrevY;
        camera.yaw = nerPrevYaw; camera.pitch = nerPrevPitch;
        nerActive = false;
        if (enemyManager != null) enemyManager.wavesEnabled = nerPrevWaves;
        hintText  = "Left the anomaly.";
        hintTimer = 2f;
    }

    /** Per-frame while inside: detect rounding the pillar and advance the sequence. */
    private void updateLayeredRooms() {
        int q = nerQuadrantAt(player.position.x, player.position.z);
        if (q < 0 || q == nerQuad) return;

        int diff = (q - nerQuad + 4) % 4;
        if      (diff == 1) nerRoom++;   // clockwise  -> next room
        else if (diff == 3) nerRoom--;   // counter-cw -> previous room
        // diff == 2 (straight across the solid centre) can't happen by walking  -  ignore.
        nerQuad = q;
        nerApplyThemes();                // repaints only the now-hidden diagonal room

        hintText  = "Room " + nerRoom;
        hintTimer = 2.5f;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  "ORBITAL ANNIHILATION"  (F7)
    // ═══════════════════════════════════════════════════════════════════════════

    /** F7: pick an epicentre and start the cinematic. */
    private void startOrbitalStrike(Camera camera) {
        if (lastTarget != null && lastTarget.hit) {
            orbEpiX = lastTarget.hitX + 0.5f;
            orbEpiY = lastTarget.hitY + 0.5f;
            orbEpiZ = lastTarget.hitZ + 0.5f;
        } else {
            // No block targeted  -  drop it ~14 blocks ahead, on the first surface below.
            float dirx = (float) Math.cos(camera.yaw), dirz = (float) Math.sin(camera.yaw);
            float tx = player.position.x + dirx * 14f;
            float tz = player.position.z + dirz * 14f;
            int bx = (int) Math.floor(tx), bz = (int) Math.floor(tz);
            float ey = player.position.y;
            for (int y = (int) player.position.y + 6; y >= 1; y--) {
                if (world.getBlock(bx, y, bz).isSolid()) { ey = y + 0.5f; break; }
            }
            orbEpiX = tx; orbEpiY = ey; orbEpiZ = tz;
        }
        orbitalT = 0f; orbCarved = false; orbStruck = false; orbitalActive = true;
        orbParticles.clear();
        AudioManager.play("laser_gun", 1.0f);   // starts charging immediately on F7
        hintText = "ORBITAL ANNIHILATION   -   stand back."; hintTimer = 3f;
    }

    /** Drive the scan/blackout parameters, shake, particles, one-shots; end it. */
    private void updateOrbitalStrike(float dt) {
        orbitalT += dt;
        float t = orbitalT;
        final float MAXR = 85f;   // world radius the lidar wavefront reaches

        orbScanRadiusW = 0f; orbScanIntensity = 0f; orbFlashAmt = 0f;
        // Blackout from a hair after fire, lifting partway through the laser.
        orbDark = (t >= ORB_DARK_START) && (t < ORB_LASER + 1.6f);

        if (t < ORB_CHARGE) {
            // CHARGE: gyroscope spins up; gentle, building rumble.
            orbShake(0.16f, 0.02f + 0.05f * (t / ORB_CHARGE));
        } else if (t < ORB_IMPLODE) {
            // IMPLODE: rings whip inward; rumble tightens toward the collision.
            float p = (t - ORB_CHARGE) / (ORB_IMPLODE - ORB_CHARGE);
            orbShake(0.14f, 0.05f + 0.18f * p);
        } else if (t < ORB_SCANOUT) {
            // SCANOUT: detonation has fired the wavefront; it sweeps the terrain.
            orbScanIntensity = 2.3f;
            float p = (t - ORB_IMPLODE) / (ORB_SCANOUT - ORB_IMPLODE);
            orbScanRadiusW = p * MAXR;
            spawnEmbers(dt);                         // anti-gravity embers fill the volume
        } else if (t < ORB_CARVE) {
            // The wavefront implodes back toward the centre, searing brighter.
            float p = (t - ORB_SCANOUT) / (ORB_CARVE - ORB_SCANOUT);
            orbScanIntensity = 2.3f + p * 3f;
            orbScanRadiusW = (1f - p) * MAXR;
        } else if (t < ORB_LASER) {
            // Carve the world, then a short, dark beat before the strike.
            if (!orbCarved) {
                carveOrbitalCrater();
                orbCarved = true;
                orbShake(0.6f, 0.55f);
                ScreenEffectManager.INSTANCE.flash(0.4f, 1f, 0.5f, 0.4f, 0.16f);
            }
        } else {
            // LASER: fire the 10-second laser_gun audio on the frame it begins,
            // then the environmental flash sears the dead world and the beam fades.
            float p = (t - ORB_LASER) / (ORB_END - ORB_LASER);
            orbFlashAmt = Math.max(0f, 1.7f * (1f - p * 2.4f));
            orbShake(0.22f, 0.36f);
            if (!orbStruck) {
                orbStruck = true;
                ScreenEffectManager.INSTANCE.flash(1f, 1f, 1f, 0.9f, 0.22f);
                spawnDebris();                                  // voxel debris bursts from impact
            }
        }

        // Detonation kick the instant the implosion rings collide.
        if (Math.abs(t - ORB_IMPLODE) < dt * 1.5f) {
            orbShake(0.45f, 0.42f);
            ScreenEffectManager.INSTANCE.flash(0.7f, 1f, 0.8f, 0.55f, 0.14f);
        }

        updateOrbParticles(dt);

        if (t >= ORB_END) {
            orbitalActive = false; orbDark = false; orbFlashAmt = 0f; orbParticles.clear();
            // Restore clean audio state BEFORE spawning the tinnitus source so:
            //   • masterGain = 1.0  -> tinnitus one-shot created at full gain (6.0×1.0=6.0)
            //   • muffle = 0        -> routeSource attaches NO lowpass to the tinnitus source
            // The arc then uses setMasterGain to suppress world loops without touching
            // the already-spawned tinnitus one-shot (setMasterGain only re-applies to loops).
            AudioManager.setMasterGain(1.0f);
            AudioManager.setListenerMuffle(0.0f);
            AudioManager.play("tinnitus", 6.0f, 2.2f);
            tinnitusTimer = TINNITUS_DUR;
        }
    }

    /** Continuously spawn slow-rising green embers within the crater radius. */
    private void spawnEmbers(float dt) {
        int n = Math.min(3, orbParticles.size() < 90 ? 3 : 0);   // cap the population
        for (int i = 0; i < n; i++) {
            OrbParticle p = new OrbParticle();
            double a = Math.random() * Math.PI * 2;
            float  rad = (float) Math.sqrt(Math.random()) * (ORB_CRATER_R + 4);
            p.x = orbEpiX + (float) Math.cos(a) * rad;
            p.z = orbEpiZ + (float) Math.sin(a) * rad;
            p.y = orbEpiY - 2f + (float) Math.random() * 3f;
            p.vx = (float) (Math.random() - 0.5) * 0.4f;
            p.vz = (float) (Math.random() - 0.5) * 0.4f;
            p.vy = 1.5f + (float) Math.random() * 2.5f;          // anti-gravity: float UP
            p.maxLife = p.life = 2.5f + (float) Math.random() * 2f;
            p.size = 0.12f + (float) Math.random() * 0.18f;
            p.r = 0.25f; p.g = 1f; p.b = 0.35f;
            orbParticles.add(p);
        }
    }

    /** Burst of glowing voxel debris flying outward+up from the impact. */
    private void spawnDebris() {
        for (int i = 0; i < 60; i++) {
            OrbParticle p = new OrbParticle();
            double a = Math.random() * Math.PI * 2;
            float  out = 6f + (float) Math.random() * 10f;
            p.x = orbEpiX; p.y = orbEpiY; p.z = orbEpiZ;
            p.vx = (float) Math.cos(a) * out;
            p.vz = (float) Math.sin(a) * out;
            p.vy = 8f + (float) Math.random() * 14f;
            p.maxLife = p.life = 1.2f + (float) Math.random() * 1.3f;
            p.size = 0.3f + (float) Math.random() * 0.5f;
            p.r = 0.4f; p.g = 1f; p.b = 0.5f;
            orbParticles.add(p);
        }
    }

    private void updateOrbParticles(float dt) {
        boolean debrisPhase = orbitalT >= ORB_LASER;
        for (int i = orbParticles.size() - 1; i >= 0; i--) {
            OrbParticle p = orbParticles.get(i);
            p.life -= dt;
            if (p.life <= 0f) { orbParticles.remove(i); continue; }
            if (debrisPhase) p.vy -= 22f * dt;   // debris falls under gravity
            p.x += p.vx * dt; p.y += p.vy * dt; p.z += p.vz * dt;
        }
    }

    private void orbShake(float dur, float amp) {
        activeShakeDuration  = dur;
        activeShakeAmplitude = amp;
        smashShakeTimer      = Math.max(smashShakeTimer, dur);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  "THE WORLD"  (F8)
    // ═══════════════════════════════════════════════════════════════════════════

    /** F8: anchor the domain, pause all world audio, start the ZA WARUDO sequence. */
    private void stopTimeStop() {
        timeStopActive = false; tsRadiusNow = 0f;
        timeStopCooldown = GameConfig.timeStopCooldownSecs;
        AudioManager.stopContinuous("clock_ticking");
        AudioManager.resumeAll();
    }

    private void startTimeStop() {
        tsCenterX = player.position.x;
        tsCenterY = player.position.y + 1.0f;
        tsCenterZ = player.position.z;
        timeStopT = 0f; tsRadiusNow = 0f; timeStopActive = true;

        // Pause every currently-playing sound before the time_stop sfx fires.
        // This silences ambient loops, enemy sounds, and ongoing one-shots so
        // only the signature audio plays in a clean silence.
        AudioManager.pauseAll();
        AudioManager.play("copyrighted/time_stop", 1.0f);
        AudioManager.playContinuous("clock_ticking", 0.65f);

        ScreenEffectManager.INSTANCE.flash(0.45f, 0.7f, 1.0f, 0.7f, 0.18f);
        orbShake(0.25f, 0.18f);
        hintText = "THE WORLD   -   time has stopped."; hintTimer = 3f;
    }

    /** Drive the expand -> hold -> collapse radius; deactivate and restore audio at the end. */
    private void updateTimeStop(float dt) {
        timeStopT += dt;
        float t = timeStopT;
        float hold = tsHold();

        if (t < TS_EXPAND) {
            float p = t / TS_EXPAND;
            tsRadiusNow = TS_MAXR * (1f - (1f - p) * (1f - p) * (1f - p));
        } else if (t < TS_EXPAND + hold) {
            tsRadiusNow = TS_MAXR;
        } else if (t < tsEnd()) {
            float p = (t - TS_EXPAND - hold) / TS_SHRINK;
            tsRadiusNow = TS_MAXR * (1f - p * p);

            // One-shot at the moment collapse begins: stop ticking, resume world.
            if (Math.abs(t - (TS_EXPAND + hold)) < dt * 1.5f) {
                AudioManager.stopContinuous("clock_ticking");
                AudioManager.resumeAll();
                AudioManager.play("copyrighted/resume_time", 1.0f);
                ScreenEffectManager.INSTANCE.flash(0.5f, 0.7f, 1.0f, 0.4f, 0.2f);
            }
        } else {
            timeStopActive = false; tsRadiusNow = 0f;
        }
    }

    /** Drive the one-shot radar scan: fade in -> scan (rotating arm) -> fade out. */
    private void updateVoxelLines(float dt) {
        vlT += dt;
        float t = vlT;
        vlRadiusNow = VL_MAXR;                                  // full scope immediately
        vlSweepNow  = (vlSweepNow + VL_SWEEP_SPEED * dt) % 6.2831853f;  // rotate the arm
        if (t < VL_RAMP) {
            vlAmountNow = t / VL_RAMP;
        } else if (t < VL_RAMP + VL_HOLD) {
            vlAmountNow = 1f;
        } else if (t < VL_END) {
            vlAmountNow = 1f - (t - VL_RAMP - VL_HOLD) / VL_RETURN;
        } else {
            vlActive = false; vlAmountNow = 0f; vlRadiusNow = 0f;
        }
    }

    /** Erase a vertical cylinder of voxels at the epicentre and remesh the hit chunks. */
    private void carveOrbitalCrater() {
        int cxB = (int) Math.floor(orbEpiX);
        int czB = (int) Math.floor(orbEpiZ);
        int R = ORB_CRATER_R;
        // Full column: sky limit -> void.  Every block in the cylinder is erased,
        // exposing the void below bedrock so falling in is instant death.
        int yBot = 0;
        int yTop = Chunk.HEIGHT - 1;

        java.util.Set<Long> cols = new java.util.HashSet<>();
        for (int dx = -R; dx <= R; dx++) {
            for (int dz = -R; dz <= R; dz++) {
                if (dx * dx + dz * dz > R * R) continue;
                int wx = cxB + dx, wz = czB + dz;
                for (int y = yBot; y <= yTop; y++) {
                    if (world.getBlock(wx, y, wz) == Block.AIR) continue;
                    world.setBlockWithMeta(wx, y, wz, Block.AIR, (byte) 0, false);
                }
                int ccx = Math.floorDiv(wx, Chunk.SIZE), ccz = Math.floorDiv(wz, Chunk.SIZE);
                for (int ax = -1; ax <= 1; ax++)
                    for (int az = -1; az <= 1; az++)
                        cols.add(((long) (ccx + ax + 32768) << 20) | (ccz + az + 32768));
            }
        }
        // Rebuild every vertical chunk slab in the affected columns.
        int cyLo = 0, cyHi = Math.floorDiv(yTop, Chunk.HEIGHT);
        for (long key : cols) {
            int kcx = (int) ((key >> 20) & 0xFFFFF) - 32768;
            int kcz = (int) (key & 0xFFFFF) - 32768;
            for (int cy = cyLo; cy <= cyHi; cy++) {
                Chunk c = world.getChunk(kcx, cy, kcz);
                if (c != null) world.buildChunkMeshes(c);
            }
        }
    }

    // ── 3D effect geometry ──────────────────────────────────────────────────────

    /** Draw one unit mesh with a model transform and an emissive colour×intensity. */
    private void orbDraw(com.leaf.game.render.Shader shader, Matrix4f pv,
                         com.leaf.game.render.Mesh m, Matrix4f model, float r, float g, float b) {
        shader.setUniform("mvp", new Matrix4f(pv).mul(model));
        shader.setUniform("emissiveTint", new Vector3f(r, g, b));
        m.render();
    }

    /**
     * Emissive draw with a view-dependent glow profile. The mesh must carry real
     * normals (see {@link #buildNormalSphere}). mode 1 = soft core (bright centre,
     * edges fade out — a glowing orb); mode 2 = fresnel rim (a shock shell).
     */
    private void orbDrawSoft(com.leaf.game.render.Shader shader, Matrix4f pv,
                             com.leaf.game.render.Mesh m, Matrix4f model,
                             float r, float g, float b, int mode) {
        shader.setUniform("fxModel", model);
        shader.setUniform("fxSoft", mode);
        orbDraw(shader, pv, m, model, r, g, b);
        shader.setUniform("fxSoft", 0);
    }

    /** Unit sphere whose normals point outward (orbBuildSphere's are all +Y). */
    private com.leaf.game.render.Mesh buildNormalSphere(int rings, int sectors) {
        float[] v = new float[(rings + 1) * (sectors + 1) * 10];
        int vi = 0;
        for (int i = 0; i <= rings; i++) {
            double lat = Math.PI * i / rings, y = Math.cos(lat), rr = Math.sin(lat);
            for (int j = 0; j <= sectors; j++) {
                double lon = 2 * Math.PI * j / sectors;
                float px = (float) (rr * Math.cos(lon)), py = (float) y, pz = (float) (rr * Math.sin(lon));
                int o = (vi++) * 10;
                v[o] = px; v[o+1] = py; v[o+2] = pz;
                v[o+3] = 1; v[o+4] = 1; v[o+5] = 1; v[o+6] = 1;
                v[o+7] = px; v[o+8] = py; v[o+9] = pz;
            }
        }
        int[] idx = new int[rings * sectors * 6];
        int ii = 0, stride = sectors + 1;
        for (int i = 0; i < rings; i++)
            for (int j = 0; j < sectors; j++) {
                int a = i * stride + j, b = a + stride;
                idx[ii++] = a; idx[ii++] = b; idx[ii++] = a + 1; idx[ii++] = a + 1; idx[ii++] = b; idx[ii++] = b + 1;
            }
        return new com.leaf.game.render.Mesh(v, idx);
    }

    /** Radar 3D layer: a soft vertical "arm" curtain sweeping around (opaque->
     *  transparent afterglow) + a small centre pylon. The flat ground sweep, rings
     *  and spokes live in the terrain shader; enemies are wireframed in the enemy
     *  render pass (see the radar gate there). Kept deliberately DIM  -  additive
     *  green that glows without blowing out to white. */
    private void renderRadar3D(com.leaf.game.render.Shader shader,
                               Matrix4f projection, Matrix4f view, Matrix4f renderMvp) {
        if (radarFan  == null) radarFan  = orbBuildCurtain(1.7f, 28);  // ~97° afterglow curtain
        if (orbSphere == null) orbSphere = orbBuildSphere(10, 14);

        float amt = vlAmountNow;
        if (amt < 0.01f) return;
        Matrix4f pv = new Matrix4f(projection).mul(view);
        float cx = vlCx, cy = vlCy, cz = vlCz;
        float sweep = vlSweepNow;
        float armLen = Math.min(vlRadiusNow, 40f);   // a modest local arm, not the full scope

        glEnable(GL_BLEND);
        glBlendFunc(GL_ONE, GL_ONE);     // additive
        glDepthMask(false);
        glDepthFunc(GL_ALWAYS);          // draw over terrain like the shader scope
        shader.setUniform("emissiveMode", 1);

        // Vertical afterglow CURTAIN: a curved wall (radius 1, height 1) spanning
        // fan-angle [-span,0]; bright leading edge -> transparent trailing.
        // rotateY(-sweep) maps local fan-angle a -> world bearing (a + sweep).
        // DIM  -  a soft sheet of light, not a searing wall.
        Matrix4f curtain = new Matrix4f().translate(cx, cy - 1.0f, cz)
                .rotateY(-sweep)
                .scale(armLen, 4.0f, armLen);
        orbDraw(shader, pv, radarFan, curtain, 0.03f * amt, 0.34f * amt, 0.14f * amt);

        // Small centre pylon (gentle).
        orbDraw(shader, pv, orbSphere,
                new Matrix4f().translate(cx, cy, cz).scale(0.5f),
                0.12f * amt, 0.55f * amt, 0.22f * amt);

        shader.setUniform("emissiveMode", 0);
        shader.setUniform("mvp", renderMvp);
        glDepthFunc(GL_LESS);
        glDepthMask(true);
        glDisable(GL_BLEND);
    }

    /** Vertical afterglow curtain: a curved wall of radius 1, y in [0,1], spanning
     *  fan-angle [-span, 0]. Vertex brightness 1 at the leading edge (angle 0) -> 0
     *  trailing, so additive blending fades it opaque->transparent behind the arm. */
    private com.leaf.game.render.Mesh orbBuildCurtain(float span, int segs) {
        float[] v = new float[(segs + 1) * 2 * 10];
        int vi = 0;
        for (int i = 0; i <= segs; i++) {
            float a = -span * (float) i / segs;       // 0 -> -span
            float b = 1f - (float) i / segs;           // 1 -> 0 brightness
            float cxr = (float) Math.cos(a), czr = (float) Math.sin(a);
            int o = (vi++) * 10;                       // bottom vertex
            v[o]=cxr; v[o+1]=0; v[o+2]=czr; v[o+3]=b; v[o+4]=b; v[o+5]=b; v[o+6]=1; v[o+7]=0; v[o+8]=1; v[o+9]=0;
            o = (vi++) * 10;                           // top vertex
            v[o]=cxr; v[o+1]=1; v[o+2]=czr; v[o+3]=b; v[o+4]=b; v[o+5]=b; v[o+6]=1; v[o+7]=0; v[o+8]=1; v[o+9]=0;
        }
        int[] idx = new int[segs * 6];
        int ii = 0;
        for (int i = 0; i < segs; i++) {
            int a = i*2, bt = i*2+1, c = i*2+2, dt = i*2+3;
            idx[ii++]=a; idx[ii++]=c; idx[ii++]=bt;  idx[ii++]=bt; idx[ii++]=c; idx[ii++]=dt;
        }
        return new com.leaf.game.render.Mesh(v, idx);
    }

    /** The whole 3D set-piece: gyroscope, implosion rings, core, embers, laser. */
    private void renderOrbital3D(com.leaf.game.render.Shader shader,
                                 Matrix4f projection, Matrix4f view, Matrix4f renderMvp) {
        // Each mesh is checked individually: other effects (beacon, radar,
        // domain) lazily build SOME of these first, and a combined null-check
        // on orbTorus alone used to skip orbCyl/orbCube -> NPE mid-laser.
        if (orbTorus  == null) orbTorus  = orbBuildTorus(0.05f, 72, 8);
        if (orbSphere == null) orbSphere = orbBuildSphere(14, 20);
        if (orbCyl    == null) orbCyl    = orbBuildCylinder(28);
        if (orbCube   == null) orbCube   = orbBuildCube();
        float t  = orbitalT;
        float ex = orbEpiX, ey = orbEpiY, ez = orbEpiZ;
        Matrix4f pv = new Matrix4f(projection).mul(view);

        glEnable(GL_BLEND);
        glBlendFunc(GL_ONE, GL_ONE);
        glDepthMask(false);
        glDepthFunc(GL_ALWAYS);
        shader.setUniform("emissiveMode", 1);

        if (t < ORB_LASER) {
            float pulse  = 0.55f + 0.18f * (float) Math.sin(t * 8.0);
            float coreR  = 0.7f * pulse + 0.2f;
            float bright = 1.4f;
            if (Math.abs(t - ORB_IMPLODE) < 0.3f) bright = 3.4f;
            orbDraw(shader, pv, orbSphere,
                    new Matrix4f().translate(ex, ey, ez).scale(coreR), bright, bright, bright * 0.9f);
        }

        if (t < ORB_IMPLODE) {
            float gs = 3.6f;
            if (t > ORB_CHARGE) {
                float p = (t - ORB_CHARGE) / (ORB_IMPLODE - ORB_CHARGE);
                gs *= (1f - p * p * p);
            }
            orbDraw(shader, pv, orbTorus,
                    new Matrix4f().translate(ex, ey, ez).rotateX(t * 2.0f).scale(gs),
                    0.6f, 1f, 0.7f);
            orbDraw(shader, pv, orbTorus,
                    new Matrix4f().translate(ex, ey, ez).rotateZ(t * 2.7f).scale(gs * 0.85f),
                    0.7f, 1f, 0.85f);
            orbDraw(shader, pv, orbTorus,
                    new Matrix4f().translate(ex, ey, ez).rotateY(t * 1.6f).rotateX(0.5f).scale(gs * 1.15f),
                    0.5f, 1f, 0.6f);
        }

        if (t < ORB_IMPLODE) {
            for (int k = 0; k < 6; k++) {
                float tk = k * 0.45f;
                if (t < tk) continue;
                float r;
                if (t < ORB_CHARGE) {
                    r = 6f * (t - tk);
                } else {
                    float rAt = 6f * (ORB_CHARGE - tk);
                    float p   = (t - ORB_CHARGE) / (ORB_IMPLODE - ORB_CHARGE);
                    r = rAt * (1f - p * p * p);
                }
                if (r < 0.4f) continue;
                float b = 0.8f + (t >= ORB_CHARGE ? 1.6f * (t - ORB_CHARGE) / (ORB_IMPLODE - ORB_CHARGE) : 0f);
                orbDraw(shader, pv, orbTorus,
                        new Matrix4f().translate(ex, ey + 0.3f, ez).scale(r, 1f, r),
                        b * 0.7f, b, b * 0.8f);
            }
        }

        for (OrbParticle p : orbParticles) {
            float lf = Math.max(0f, p.life / p.maxLife);
            orbDraw(shader, pv, orbCube,
                    new Matrix4f().translate(p.x, p.y, p.z).scale(p.size),
                    p.r * (0.4f + lf), p.g * (0.4f + lf), p.b * (0.4f + lf));
        }

        if (t >= ORB_LASER) {
            float p     = (t - ORB_LASER) / (ORB_END - ORB_LASER);
            float fade  = (p < 0.7f) ? 1f : Math.max(0f, 1f - (p - 0.7f) / 0.3f);
            float flick = 0.85f + 0.30f * (float) Math.sin(t * 50.0);
            float top   = ey + 150f, bot = ey - 50f, hgt = top - bot;

            orbDraw(shader, pv, orbCyl,
                    new Matrix4f().translate(ex, bot, ez).scale(3.5f * fade, hgt, 3.5f * fade),
                    1.2f * flick, 1.2f * flick, 1.2f * flick);
            orbDraw(shader, pv, orbCyl,
                    new Matrix4f().translate(ex, bot, ez).scale(1.0f * fade, hgt, 1.0f * fade),
                    3.0f * flick, 3.0f * flick, 3.0f * flick);
            orbDraw(shader, pv, orbCyl,
                    new Matrix4f().translate(ex, bot, ez).scale(0.25f * fade, hgt, 0.25f * fade),
                    6f, 6f, 6f);

            for (int s = 0; s < 4; s++) {
                float ang = t * 4.5f + s * (float) (Math.PI / 2);
                float ox  = (float) Math.cos(ang) * 4.8f, oz = (float) Math.sin(ang) * 4.8f;
                orbDraw(shader, pv, orbCyl,
                        new Matrix4f().translate(ex + ox, bot, ez + oz).scale(0.5f * fade, hgt, 0.5f * fade),
                        0.4f, 1.5f, 0.6f);
            }

            float swb = Math.max(0f, 1.6f * (1f - p * 3f));
            if (swb > 0.01f) {
                float swr = Math.min(1f, p * 3f) * (ORB_CRATER_R + 6);
                orbDraw(shader, pv, orbTorus,
                        new Matrix4f().translate(ex, ey + 0.4f, ez).scale(swr, 1f, swr),
                        0.4f * swb, 1.6f * swb, 0.6f * swb);
            }

            float fr = Math.max(0f, 1f - p * 4f) * 9f;
            if (fr > 0.3f) {
                orbDraw(shader, pv, orbSphere,
                        new Matrix4f().translate(ex, ey, ez).scale(fr), 3f, 3f, 2.8f);
            }
        }

        shader.setUniform("emissiveMode", 0);
        shader.setUniform("mvp", renderMvp);
        glDepthFunc(GL_LESS);
        glDepthMask(true);
        glDisable(GL_BLEND);
    }

    private com.leaf.game.render.Mesh orbBuildTorus(float mr, int nMaj, int nMin) {
        float[] v = new float[nMaj * nMin * 10];
        int[]   idx = new int[nMaj * nMin * 6];
        int vi = 0, ii = 0;
        for (int i = 0; i < nMaj; i++) {
            double th = 2 * Math.PI * i / nMaj, ct = Math.cos(th), st = Math.sin(th);
            for (int j = 0; j < nMin; j++) {
                double ph = 2 * Math.PI * j / nMin;
                float rr = (float) (1 + mr * Math.cos(ph));
                int o = (vi++) * 10;
                v[o]=(float)(ct*rr); v[o+1]=(float)(mr*Math.sin(ph)); v[o+2]=(float)(st*rr);
                v[o+3]=1; v[o+4]=1; v[o+5]=1; v[o+6]=1; v[o+7]=0; v[o+8]=1; v[o+9]=0;
            }
        }
        for (int i = 0; i < nMaj; i++)
            for (int j = 0; j < nMin; j++) {
                int a = i*nMin+j, b = ((i+1)%nMaj)*nMin+j,
                        c = ((i+1)%nMaj)*nMin+(j+1)%nMin, dd = i*nMin+(j+1)%nMin;
                idx[ii++]=a; idx[ii++]=b; idx[ii++]=c; idx[ii++]=a; idx[ii++]=c; idx[ii++]=dd;
            }
        return new com.leaf.game.render.Mesh(v, idx);
    }

    private com.leaf.game.render.Mesh orbBuildSphere(int rings, int sectors) {
        float[] v = new float[(rings + 1) * (sectors + 1) * 10];
        int vi = 0;
        for (int i = 0; i <= rings; i++) {
            double lat = Math.PI * i / rings, y = Math.cos(lat), rr = Math.sin(lat);
            for (int j = 0; j <= sectors; j++) {
                double lon = 2 * Math.PI * j / sectors;
                int o = (vi++) * 10;
                v[o]=(float)(rr*Math.cos(lon)); v[o+1]=(float)y; v[o+2]=(float)(rr*Math.sin(lon));
                v[o+3]=1; v[o+4]=1; v[o+5]=1; v[o+6]=1; v[o+7]=0; v[o+8]=1; v[o+9]=0;
            }
        }
        int[] idx = new int[rings * sectors * 6];
        int ii = 0, stride = sectors + 1;
        for (int i = 0; i < rings; i++)
            for (int j = 0; j < sectors; j++) {
                int a = i*stride+j, b = a+stride;
                idx[ii++]=a; idx[ii++]=b; idx[ii++]=a+1; idx[ii++]=a+1; idx[ii++]=b; idx[ii++]=b+1;
            }
        return new com.leaf.game.render.Mesh(v, idx);
    }

    private com.leaf.game.render.Mesh orbBuildCylinder(int seg) {
        float[] v = new float[(seg + 1) * 2 * 10];
        int vi = 0;
        for (int j = 0; j <= seg; j++) {
            double a = 2 * Math.PI * j / seg; float cx = (float) Math.cos(a), cz = (float) Math.sin(a);
            int o = (vi++) * 10;
            v[o]=cx; v[o+1]=0; v[o+2]=cz; v[o+3]=1; v[o+4]=1; v[o+5]=1; v[o+6]=1; v[o+7]=cx; v[o+8]=0; v[o+9]=cz;
            o = (vi++) * 10;
            v[o]=cx; v[o+1]=1; v[o+2]=cz; v[o+3]=1; v[o+4]=1; v[o+5]=1; v[o+6]=1; v[o+7]=cx; v[o+8]=0; v[o+9]=cz;
        }
        int[] idx = new int[seg * 6];
        int ii = 0;
        for (int j = 0; j < seg; j++) {
            int a = j*2, b = j*2+1, c = j*2+2, d = j*2+3;
            idx[ii++]=a; idx[ii++]=c; idx[ii++]=b; idx[ii++]=b; idx[ii++]=c; idx[ii++]=d;
        }
        return new com.leaf.game.render.Mesh(v, idx);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  DEPRIVATION DOMAIN  -  WATER GOD STANCE  (' key, KeyBindings.DEPRIVATION_DOMAIN)
    // ══════════════════════════════════════════════════════════════════════════
    //  The player locks in place. The world-shader tints the interior gold and the
    //  exterior cool/dark  -  an absolute domain, a silent standoff. Any entity that
    //  moves inside the radius is instantly cut apart: a chaotic "slash storm" of
    //  anime crescents erupts around the player (Soul-Knight-style omni-swing) and
    //  the victim bursts into white-hot voxel gibs that char as they fall.

    private void startDeprivationDomain() {
        depActive = true;
        depT      = 0f;
        depX = player.position.x;
        depY = player.position.y;
        depZ = player.position.z;
        depStrike = 0f;
        depDetectTimer = 0f;
        depAuraEmit = 0f; depSlashSnd = 0f;
        depTurning = false; depTurnT = 0f;
        depPrevPos.clear();
        depSlashFx.clear();
        depGibs.clear();
        depCorpses.clear();
        if (depCrescent  == null) depCrescent  = buildSlashCrescent(22);
        if (depDomeWire  == null) depDomeWire  = buildDomeWire(7, 24);
        hintText  = "DEPRIVATION DOMAIN  -  perfect stillness, absolute death · ['] to exit";
        hintTimer = 5f;
        AudioManager.play("field_enter", 0.9f);
        ScreenEffectManager.INSTANCE.flash(1f, 0.88f, 0.28f, 0.30f, 0.40f);
    }

    private void stopDeprivationDomain() {
        depActive = false;
        depCooldown = GameConfig.depCooldownSecs;
        depTurning = false;
        depPrevPos.clear();
        depSlashFx.clear();
        depGibs.clear();
        depCorpses.clear();
        // AUDIO HOOK: add your own domain-exit cue here (user-supplied).
    }

    private void updateDeprivationDomain(float dt, World world, Camera camera) {
        depT += dt;

        // ── Bezier head-turn: smoothly swing the camera to lock onto the victim ──
        if (depTurning) {
            depTurnT += dt / Math.max(0.05f, GameConfig.depTurnDuration);
            float e = depTurnT >= 1f ? 1f : smootherstep(depTurnT);   // bezier-like S-curve
            camera.yaw   = lerpAngle(depTurnStartYaw, depTurnTargetYaw, e);
            camera.pitch = depTurnStartPitch + (depTurnTargetPitch - depTurnStartPitch) * e;
            camera.clampPitch();
            if (depTurnT >= 1f) depTurning = false;
        }

        // ── VIGOROUS SWORD AURA: a constant whirlwind of big slashes swooping around
        //    you (instead of a calm orbiting ring)  -  the swords never rest. ─────────
        depAuraEmit += dt;
        float auraInterval = 0.045f;                 // ~22 slashes/sec whirling around
        while (depAuraEmit >= auraInterval) {
            depAuraEmit -= auraInterval;
            spawnAuraSlash();
        }
        // Ambient slash swishes so the field constantly *sounds* like many blades.
        depSlashSnd += dt;
        if (depSlashSnd >= 0.16f) {
            depSlashSnd = 0f;
            AudioManager.play(SLASH_SHORT[(int)(Math.random() * SLASH_SHORT.length)],
                              0.32f, 0.9f + (float) Math.random() * 0.3f);
        }

        // Age slash crescents (expand + sweep + fade)
        for (java.util.Iterator<float[]> it = depSlashFx.iterator(); it.hasNext(); ) {
            float[] s = it.next(); s[6] += dt; if (s[6] >= s[7]) it.remove();
        }
        // Age sliced corpses (the 8 octants scatter + dissolve fast)
        for (java.util.Iterator<DepCorpse> it = depCorpses.iterator(); it.hasNext(); ) {
            DepCorpse c = it.next(); c.age += dt; if (c.age >= c.life) it.remove();
        }
        // Age + integrate voxel gibs (gravity, drag, fade).
        // Gib layout: [0]x [1]y [2]z [3]vx [4]vy [5]vz [6]size [7]age [8]life
        for (java.util.Iterator<float[]> it = depGibs.iterator(); it.hasNext(); ) {
            float[] g = it.next();
            g[7] += dt;
            if (g[7] >= g[8]) { it.remove(); continue; }
            g[4] -= 26f * dt;                      // gravity on vy
            g[0] += g[3] * dt; g[1] += g[4] * dt; g[2] += g[5] * dt;
            g[3] *= 0.96f; g[5] *= 0.96f;          // horizontal air drag
        }

        if (depT >= GameConfig.depDuration) { stopDeprivationDomain(); return; }

        // Movement detection (sampled every depDetectTick for perf)
        depDetectTimer += dt;
        if (depDetectTimer < GameConfig.depDetectTick) return;
        float tick = depDetectTimer;
        depDetectTimer = 0f;

        float radSq  = GameConfig.depRadius * GameConfig.depRadius;
        float minMov = GameConfig.depDetectMinVel * tick;  // blocks moved this tick
        if (enemyManager == null) return;

        boolean struck = false;

        // ── Enemy movement detection -> SLICE ─────────────────────────────────
        for (com.leaf.game.entity.Enemy e : enemyManager.getEnemies()) {
            if (!e.alive) continue;
            float dx = e.position.x - depX, dz = e.position.z - depZ;
            if (dx * dx + dz * dz > radSq) {
                depPrevPos.remove(System.identityHashCode(e));
                continue;
            }
            org.joml.Vector3f prev = depPrevPos.get(System.identityHashCode(e));
            if (prev != null) {
                float moved = (float) Math.hypot(e.position.x - prev.x, e.position.z - prev.z);
                if (moved >= minMov) {
                    float ex = e.position.x, ey = e.position.y + 1.0f, ez = e.position.z;
                    spawnSlashBurst(ex, ey, ez, 4, 0.8f, 1.8f);  // a flurry of cuts on the body
                    spawnSliceGibs(ex, ey, ez);                  // blood-spark debris
                    captureCorpse(e);                            // chop the ACTUAL model into 8
                    // Kill SILENTLY  -  applyDamage() plays a death "smash" sound; we
                    // want only the sword-slash cues here. Set state directly instead.
                    e.health = 0f; e.alive = false;
                    e.hitFlashTimer = 0f;                        // suppress the normal corpse render
                    enemyAnimPlayers.remove(e.id);
                    // Lock the head onto the victim  -  but let the current turn settle
                    // past halfway before snapping to a new one (avoids whiplash).
                    if (!depTurning || depTurnT > 0.5f) aimHeadAt(camera, ex, ey, ez);
                    // Many slashes: 2 random sword cuts per kill (varied pitch) -> a flurry.
                    AudioManager.play(SLASH_ALL[(int)(Math.random() * SLASH_ALL.length)],
                                      0.85f, 0.9f + (float) Math.random() * 0.35f);
                    AudioManager.play(SLASH_SHORT[(int)(Math.random() * SLASH_SHORT.length)],
                                      0.7f, 1.0f + (float) Math.random() * 0.4f);
                    struck = true;
                }
            }
            depPrevPos.put(System.identityHashCode(e), new org.joml.Vector3f(e.position));
        }

        // ── Projectile nullification -> split & drop ──────────────────────────
        for (com.leaf.game.entity.EnemyManager.EnemyProjectile proj : enemyManager.projectiles) {
            if (!proj.alive) continue;
            float dx = proj.pos.x - depX, dz = proj.pos.z - depZ;
            if (dx * dx + dz * dz > radSq) continue;
            if (proj.vel.length() > 0.1f) {
                spawnSlashBurst(proj.pos.x, proj.pos.y, proj.pos.z, 1, 0.4f, 1.0f);
                spawnSliceGibs(proj.pos.x, proj.pos.y, proj.pos.z);
                proj.alive = false;
                struck = true;
            }
        }

        // ── THE SLASH STORM  -  omnidirectional dome of slashes on top of the player ──
        if (struck) {
            int n = 6 + (int) (Math.random() * 6);   // 6-11 crescents around the player
            spawnSlashBurst(depX, depY + 1.1f, depZ, n, 1.0f, 2.0f);
            depStrike = Math.min(1f, depStrike + 0.6f);
            activeShakeAmplitude = 0.09f;
            activeShakeDuration  = 0.14f;
            smashShakeTimer      = Math.max(smashShakeTimer, 0.14f);
            ScreenEffectManager.INSTANCE.flash(1f, 0.90f, 0.35f, 0.25f, 0.10f);
        }
    }

    /** Spawn one big slash crescent whirling around the player (the vigorous aura). */
    private void spawnAuraSlash() {
        if (depSlashFx.size() > 200) return;
        float yaw   = (float) (Math.random() * Math.PI * 2.0);
        float pitch = (float) (Math.random() * Math.PI * 2.0);
        float roll  = (float) (Math.random() * Math.PI * 2.0);
        float r     = GameConfig.depRadius;
        float s0    = 1.8f + (float) Math.random() * 1.4f;
        float s1    = s0 + Math.max(2.0f, r * 0.5f);                 // big arcs spanning the dome
        float life  = 0.22f + (float) Math.random() * 0.12f;        // fast
        float sweep = (Math.random() < 0.5 ? -1f : 1f) * (2.5f + (float) Math.random() * 2.0f);
        // Offset the centre a little off the player so the arcs swirl, not just nest.
        float ox = (float) (Math.random() - 0.5) * r * 0.5f;
        float oz = (float) (Math.random() - 0.5) * r * 0.5f;
        float oy = (float) (Math.random() - 0.2) * 1.5f;
        depSlashFx.add(new float[]{ depX + ox, depY + 1.1f + oy, depZ + oz,
                                    yaw, pitch, roll, 0f, life, s0, s1, sweep });
    }

    /** Snapshot the enemy's posed model so it can be chopped into 8 flying octants. */
    private void captureCorpse(com.leaf.game.entity.Enemy e) {
        com.leaf.game.anim.AnimPlayer ap = enemyAnimPlayers.get(e.id);
        if (ap == null) return;                       // never rendered -> no model to slice
        boolean isGuardian = e.type == Enemy.Type.GUARDIAN || e.type == Enemy.Type.GOLEM;
        com.leaf.game.anim.AnimModel model =
                (e.type == Enemy.Type.SLIME && slimeAnimModel != null) ? slimeAnimModel
              : (isGuardian && golemAnimModel != null) ? golemAnimModel
              : enemyAnimModel;
        if (model == null) return;
        java.util.Map<String, Matrix4f> pose = ap.getPose();   // fresh owned snapshot

        // Body-frame cut centre = average of the parts' positions; size = spread.
        org.joml.Vector3f c = new org.joml.Vector3f();
        int n = 0;
        for (Matrix4f m : pose.values()) { c.add(m.getTranslation(new org.joml.Vector3f())); n++; }
        if (n > 0) c.div(n);
        float size = 0.5f;
        for (Matrix4f m : pose.values())
            size = Math.max(size, c.distance(m.getTranslation(new org.joml.Vector3f())));
        size = Math.max(1.5f, size * 1.6f);

        float faceY = isGuardian ? e.facingYaw
                : (float) Math.atan2(player.position.x - e.position.x,
                                     player.position.z - e.position.z) + (float) Math.PI;
        float[] sv = e.renderScaleVec();
        depCorpses.add(new DepCorpse(model, pose, e.position.x, e.position.y, e.position.z,
                faceY, sv[0], sv[1], sv[2], c, size, 0.55f, System.nanoTime()));
    }

    /** Begin a smooth bezier camera turn to face a world point (the slashed victim). */
    private void aimHeadAt(Camera camera, float tx, float ty, float tz) {
        float dx = tx - camera.position.x;
        float dy = ty - camera.position.y;
        float dz = tz - camera.position.z;
        float horiz = (float) Math.sqrt(dx * dx + dz * dz);
        if (horiz < 1e-3f) return;
        depTurnStartYaw    = camera.yaw;
        depTurnStartPitch  = camera.pitch;
        depTurnTargetYaw   = (float) Math.atan2(dz, dx);
        depTurnTargetPitch = (float) Math.atan2(dy, horiz);
        depTurnT = 0f;
        depTurning = true;
    }

    /** Shortest-path angular lerp (handles the ±π wrap). */
    private static float lerpAngle(float a, float b, float t) {
        float d = b - a;
        while (d >  Math.PI) d -= (float) (2 * Math.PI);
        while (d < -Math.PI) d += (float) (2 * Math.PI);
        return a + d * t;
    }

    /** Ken-Perlin smootherstep  -  the S-curve for the bezier-feel head turn. */
    private static float smootherstep(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * t * (t * (t * 6f - 15f) + 10f);
    }

    /** Spawn a burst of randomly-oriented slash crescents centred at (cx,cy,cz).
     *  Each crescent sweeps through an arc (like a real swing), expands, and fades. */
    private void spawnSlashBurst(float cx, float cy, float cz, int count,
                                 float startMin, float startMax) {
        for (int i = 0; i < count; i++) {
            if (depSlashFx.size() > 160) break;
            float yaw   = (float) (Math.random() * Math.PI * 2.0);
            float pitch = (float) (Math.random() * Math.PI * 2.0);
            float roll  = (float) (Math.random() * Math.PI * 2.0);
            float s0    = startMin + (float) Math.random() * (startMax - startMin);
            float s1    = s0 + 2.0f + (float) Math.random() * 1.4f;   // expand ~2-3 blocks
            // Longer life so the swing is readable; randomised so they don't sync up.
            float life  = GameConfig.depSlashLife * (0.8f + (float) Math.random() * 0.5f);
            // Sweep: how far the blade rotates through its arc over its life (the swing).
            float sweep = (Math.random() < 0.5 ? -1f : 1f) * (1.8f + (float) Math.random() * 1.6f);
            depSlashFx.add(new float[]{cx, cy, cz, yaw, pitch, roll, 0f, life, s0, s1, sweep});
        }
    }

    /** Spawn voxel gib debris: two halves split across a random vertical plane,
     *  flung apart with an upward pop. They start white-hot gold and char as they fall. */
    private void spawnSliceGibs(float cx, float cy, float cz) {
        float ang = (float) (Math.random() * Math.PI);
        float nx = (float) Math.cos(ang), nz = (float) Math.sin(ang);   // cut-plane normal (XZ)
        int chunks = 16 + (int) (Math.random() * 8);                    // juicier: more chunks
        for (int i = 0; i < chunks; i++) {
            if (depGibs.size() > 320) break;
            float side = (i % 2 == 0) ? 1f : -1f;                       // alternate halves
            float px = cx + (float) (Math.random() - 0.5) * 0.5f;
            float py = cy + (float) (Math.random() - 0.5) * 1.3f;
            float pz = cz + (float) (Math.random() - 0.5) * 0.5f;
            float spd = 3.5f + (float) Math.random() * 4.5f;            // flung apart harder
            float vx = nx * side * spd + (float) (Math.random() - 0.5) * 1.8f;
            float vz = nz * side * spd + (float) (Math.random() - 0.5) * 1.8f;
            float vy = 3.5f + (float) Math.random() * 5.0f;             // bigger upward pop
            float size = 0.12f + (float) Math.random() * 0.20f;
            float life = 0.9f + (float) Math.random() * 0.7f;
            depGibs.add(new float[]{px, py, pz, vx, vy, vz, size, 0f, life});
        }
    }

    private void renderDeprivationDomain(com.leaf.game.render.Shader shader,
                                         Matrix4f projection, Matrix4f view,
                                         Matrix4f renderMvp) {
        if (!depActive) return;
        if (depCrescent  == null) depCrescent  = buildSlashCrescent(22);
        if (depDomeWire  == null) depDomeWire  = buildDomeWire(7, 24);
        if (orbCube == null)      orbCube      = orbBuildCube();

        Matrix4f pv = new Matrix4f(projection).mul(view);
        float tnow = (float) glfwGetTime();

        glEnable(GL_BLEND);
        glBlendFunc(GL_ONE, GL_ONE);   // additive  -  everything glows + blooms
        glDepthMask(false);
        glDepthFunc(GL_LEQUAL);        // terrain naturally occludes the slashes
        glDisable(GL_CULL_FACE);
        shader.setUniform("emissiveMode", 1);

        // ── THE CAGE: a SOLID hexagon-WIREFRAME SPHERE ─────────────────────────
        // A full sphere is used instead of a hemisphere. The lower half clips
        // into the terrain via the depth buffer, creating a perfect seal that
        // follows any uneven ground/canyons perfectly without floating.
        if (depHexDome == null) depHexDome = buildHexDomeWire(56, 0.010f);
        float domR = GameConfig.depRadius;

        // Fade in on cast, out on expiry; gentle breathing pulse + a flare on each strike.
        float fin   = Math.min(1f, depT / 0.40f);
        float fout  = Math.min(1f, (GameConfig.depDuration - depT) / 0.60f);
        float fade  = Math.max(0f, Math.min(fin, fout));
        fade        = fade * fade * (3f - 2f * fade); // smoothstep ease
        float pulse = 0.5f + 0.5f * (float) Math.sin(tnow * 2.6);
        float gb    = (0.42f + 0.16f * pulse + depStrike * 0.45f) * fade;

        float spin  = tnow * 0.8f;

        // Center the sphere on the player's torso.
        // It will plunge 8 blocks into the earth and rise 8 blocks above.
        float centerY = depY + 1.0f;

        orbDraw(shader, pv, depHexDome,
                new Matrix4f().translate(depX, centerY, depZ).rotateY(spin).scale(domR),
                1.10f * gb, 0.78f * gb, 0.24f * gb);

        // (We completely removed the 2D 'depBaseRing' because the sphere now
        // creates a natural, organic intersection line with the terrain!)

        // ── THE SLASH STORM ──────────────────────────────────────────────────

        // ── THE SLASH STORM: crescents that SNAP through their arc fast, then linger
        //    + fade. (Fast expansion/sweep front-loaded so the cut reads as instant.) ─
        for (float[] s : depSlashFx) {
            float f      = s[6] / s[7];                         // 0->1 over its life
            float snap   = Math.min(1f, f / 0.18f);             // the actual slash: done in ~18%
            float snapE  = 1f - (1f - snap) * (1f - snap);      // easeOut -> very fast then settle
            float scale  = s[8] + (s[9] - s[8]) * snapE;
            float rise   = (f < 0.06f) ? f / 0.06f : 1f;        // appear almost instantly
            float fall   = (f > 0.45f) ? (1f - (f - 0.45f) / 0.55f) : 1f;
            float br     = rise * fall * 4.6f;                  // HDR  -  feeds the bloom
            if (br <= 0.01f) continue;
            Matrix4f m = new Matrix4f().translate(s[0], s[1], s[2])
                    .rotateY(s[3]).rotateX(s[4]).rotateZ(s[5] + s[10] * snapE)  // snap through the arc
                    .scale(scale);
            // Crimson-white blade so it pops off the gold dome.
            orbDraw(shader, pv, depCrescent, m, br * 1.3f, br * 0.55f, br * 0.30f);
        }

        // ── BLOOD-SPARK GIBS (small, fast) ───────────────────────────────────
        for (float[] g : depGibs) {
            float f   = g[7] / g[8];
            float hot = 1f - f;
            orbDraw(shader, pv, orbCube,
                    new Matrix4f().translate(g[0], g[1], g[2]).scale(g[6]),
                    2.6f * hot + 0.12f, 0.7f * hot + 0.05f, 0.25f * hot + 0.04f);  // blood red->dark
        }

        // Restore GL state  -  IMPORTANT: leave GL_CULL_FACE DISABLED (terrain needs it off;
        // it is never enabled at init, so re-enabling here would hide terrain top faces).
        shader.setUniform("emissiveMode", 0);
        shader.setUniform("emissiveTint", new Vector3f(1f, 1f, 1f));
        shader.setUniform("mvp", renderMvp);
        glDepthFunc(GL_LESS);
        glDepthMask(true);
        glDisable(GL_CULL_FACE);
        glDisable(GL_BLEND);
    }

    // ── GENERAL ABILITY-FX: spawn / update / render ────────────────────────────
    private void fxSlash(float x, float y, float z, float fx, float fy, float fz,
                         float roll, float sweep, float s0, float s1, float life,
                         float r, float g, float b) {
        Fx e = new Fx(FX_CRESCENT, x, y, z);
        e.dx = fx; e.dy = fy; e.dz = fz; e.roll = roll; e.sweep = sweep;
        e.s0 = s0; e.s1 = s1; e.life = life; e.r = r; e.g = g; e.b = b;
        pushFx(e);
    }
    void fxRing(float x, float y, float z, float r0, float r1, float life,
                        float r, float g, float b) {
        fxRingN(x, y, z, 0f, 1f, 0f, r0, r1, life, r, g, b);   // default horizontal (axis +Y)
    }
    /** Expanding ring whose plane is perpendicular to axis (nx,ny,nz). */
    void fxRingN(float x, float y, float z, float nx, float ny, float nz,
                         float r0, float r1, float life, float r, float g, float b) {
        Fx e = new Fx(FX_RING, x, y, z);
        e.dx = nx; e.dy = ny; e.dz = nz;
        e.s0 = r0; e.s1 = r1; e.life = life; e.r = r; e.g = g; e.b = b;
        pushFx(e);
    }
    void fxBurst(float x, float y, float z, float r0, float r1, float life,
                         float r, float g, float b) {
        Fx e = new Fx(FX_BURST, x, y, z);
        e.s0 = r0; e.s1 = r1; e.life = life; e.r = r; e.g = g; e.b = b;
        pushFx(e);
    }
    void fxBolt(float x, float y, float z, float dx, float dy, float dz,
                        float len, float thick, float life, float r, float g, float b) {
        Fx e = new Fx(FX_BOLT, x, y, z);
        e.dx = dx; e.dy = dy; e.dz = dz; e.s0 = len; e.s1 = thick;
        e.life = life; e.r = r; e.g = g; e.b = b;
        pushFx(e);
    }

    /** Add an FX locally AND broadcast it to the MP peer so the opponent sees it too. */
    private void pushFx(Fx e) {
        e.ambient = fxSpawnAmbient;
        fxList.add(e);
        if (network != null && network.connected) {
            network.sendFx(e.type, new float[]{
                e.x, e.y, e.z, e.dx, e.dy, e.dz, e.roll, e.sweep,
                e.life, e.s0, e.s1, e.r, e.g, e.b });
        }
    }

    // ── METEOR STORM: spawn / update / impact / render ─────────────────────────

    /** Begin a meteor storm: meteors rain down around the player for a few seconds. */
    private void startMeteorStorm() {
        meteorStormTimer = 6.0f;     // ~6 seconds of falling rocks
        meteorSpawnTimer = 0f;       // first one drops immediately
        hintText = "METEOR STORM"; hintTimer = 3f;
        AudioManager.play("fall_light", 0.8f);
    }

    /** Finale hook: meteor storm with custom duration, no hint text. */
    void startMeteorStorm(float seconds) {
        meteorStormTimer = Math.max(meteorStormTimer, seconds);
        meteorSpawnTimer = 0f;
    }

    /** Finale hook: end the boss-fight meteor rain immediately. */
    void stopMeteorStorm() {
        meteorStormTimer = 0f;
    }

    /**
     * Blocks the Stone Cannon can absorb. It used to demand pure STONE, which
     * is buried under most biomes — standing on grass meant the weapon
     * appeared completely dead. Any common earth now feeds the boulder.
     */
    private static boolean isStoneCanonFuel(Block b) {
        return switch (b) {
            case STONE, GRAVEL, DIRT, GRASS, SAND, RED_SAND, CLAY, SNOW,
                 ISLAND_STONE, FOSSIL_STONE, MEGALITH, CRYSTAL_BASE,
                 AUTUMN_GRASS, ANCIENT_SOIL, MESA_SAND -> true;
            default -> false;
        };
    }

    /** Finale hook: violent camera shake on demand. */
    void requestShake(float amplitude, float duration) {
        activeShakeAmplitude = amplitude;
        activeShakeDuration  = duration;
        smashShakeTimer      = Math.max(smashShakeTimer, duration);
    }

    /** Finale hook: drop the player into Flappy Bird mode for the arena trial. */
    void finaleEnterFlappy() {
        player.debugMode = false;            // no skim/soar inside the bird
        player.useTestMovement = true;
        player.testMovement.state = TestMovementController.State.FLAPPY;
        player.health = player.maxHealth;
        player.testMovement.flappyScore = 0;
        player.testMovement.lastPassedPipeX = 5010;
        player.testMovement.flappyWaitingToStart = true;
        player.position.set(4980f, 230f, 5000f);
        player.testMovement.velocity.set(0f, 0f, 0f);
        world.clearAllChunks();
    }

    /** Finale hook: leave Flappy mode and land at the given arena position. */
    void finaleExitFlappy(float x, float y, float z) {
        player.useTestMovement = false;
        player.testMovement.state = TestMovementController.State.AIRBORNE;
        player.health = player.maxHealth;
        player.position.set(x, y, z);
        player.setVelocityY(0f);
        player.highestY = y;
        camera.pitch = 0f;
        world.clearAllChunks();
    }

    /** Finale hook: instantly restart the flappy run after crashing (no GAME OVER menu). */
    void finaleRestartFlappy() {
        player.health = player.maxHealth;
        player.testMovement.flappyScore = 0;
        player.testMovement.lastPassedPipeX = 5010;
        player.testMovement.flappyWaitingToStart = true;
        player.position.set(4980f, 230f, 5000f);
        player.testMovement.velocity.set(0f, 0f, 0f);
        camera.yaw = 0f; camera.pitch = 0f;
        world.clearAllChunks();
    }

    /** Launch one meteorite high above, streaking down toward near the player. */
    private void spawnMeteor() {
        if (meteors.size() > 60) return;
        Meteor m = new Meteor();
        double ang = Math.random() * Math.PI * 2;
        float  off = 10f + (float) Math.random() * 70f;
        float sx = player.position.x + (float) Math.cos(ang) * off;
        float sz = player.position.z + (float) Math.sin(ang) * off;
        float sy = player.position.y + 150f + (float) Math.random() * 60f;
        m.pos.set(sx, sy, sz);
        // Aim down at a point near the player, with a steep angle.
        float tx = player.position.x + (float) (Math.random() - 0.5) * 50f;
        float tz = player.position.z + (float) (Math.random() - 0.5) * 50f;
        m.vel.set(tx - sx, -150f, tz - sz).normalize().mul(58f + (float) Math.random() * 26f);
        m.size = 1.5f + (float) Math.random() * 2.0f;
        meteors.add(m);
    }

    /** Drop ONE colossal meteor straight onto the player  -  mountain-erasing impact. */
    private void spawnMegaMeteor() {
        Meteor m = new Meteor();
        float tx = player.position.x + (float)(Math.random() - 0.5) * 24f;
        float tz = player.position.z + (float)(Math.random() - 0.5) * 24f;
        m.pos.set(tx + (float)(Math.random() - 0.5) * 40f,
                  player.position.y + 280f,
                  tz + (float)(Math.random() - 0.5) * 40f);
        m.vel.set(tx - m.pos.x, -300f, tz - m.pos.z).normalize().mul(70f);
        m.size = 30f;                       // SUPER freaking big
        meteors.add(m);
        hintText = "INCOMING"; hintTimer = 4f;
        AudioManager.play("fall_light", 1.0f);
    }

    /** Advance meteors: trail puffs, descent, and crater-carving impacts. */
    private void updateMeteors(float dt) {
        // Storm scheduler  -  staggered drops so it reads as a "rain", not a clump.
        if (meteorStormTimer > 0f) {
            meteorStormTimer -= dt;
            meteorSpawnTimer -= dt;
            if (meteorSpawnTimer <= 0f) {
                meteorSpawnTimer = 0.16f + (float) Math.random() * 0.20f;
                spawnMeteor();
                if (Math.random() < 0.5) spawnMeteor();
            }
        }
        if (meteors.isEmpty()) return;

        for (Meteor m : meteors) {
            // record a short comet trail
            m.trail.addFirst(new float[]{ m.pos.x, m.pos.y, m.pos.z });
            while (m.trail.size() > 9) m.trail.removeLast();

            m.pos.fma(dt, m.vel);

            // sizzling embers shed along the path
            if (Math.random() < 0.55) {
                fxBurst(m.pos.x, m.pos.y, m.pos.z, 0.2f, m.size * 1.5f, 0.30f, 2.6f, 1.0f, 0.32f);
            }

            int bx = (int) Math.floor(m.pos.x), by = (int) Math.floor(m.pos.y), bz = (int) Math.floor(m.pos.z);
            boolean hit = by <= 1 || (by < Chunk.HEIGHT && world.getBlock(bx, by, bz).isSolid());
            if (hit) meteorImpact(m, bx, by, bz);
        }
        meteors.removeIf(m -> !m.alive);
    }

    /** Crater + magnificent impact burst + shake when a meteor strikes the ground. */
    private void meteorImpact(Meteor m, int bx, int by, int bz) {
        // Crater scales with size; capped at 50 so a colossal strike erases a mountain
        // without freezing on the (one-time) million-block carve + remesh.
        int r = Math.min(50, (int) (3 + m.size * 1.7f));
        world.createImpactCrater(bx, by, bz, r);
        if (network != null && network.connected) network.sendCrater(bx, by, bz, r);

        float ix = m.pos.x, iy = m.pos.y, iz = m.pos.z;
        // White-hot detonation flash, twin shockwave rings, and flung debris.
        fxBurst(ix, iy + 0.4f, iz, 0.6f, r * 1.7f, 0.55f, 3.6f, 1.5f, 0.6f);
        fxRing (ix, iy + 0.3f, iz, 1f, r * 2.4f, 0.62f, 3.0f, 0.85f, 0.30f);
        fxRing (ix, iy + 0.3f, iz, 1f, r * 1.5f, 0.46f, 3.4f, 1.4f, 0.7f);
        for (int i = 0; i < 11; i++) {
            double a = i / 11.0 * Math.PI * 2;
            fxBolt(ix, iy, iz, (float) Math.cos(a) * 0.5f, 1f, (float) Math.sin(a) * 0.5f,
                    r * 0.9f, 0.14f, 0.5f, 3.0f, 1.1f, 0.45f);
        }

        // Shake + flash + boom, scaled by how close the strike is to the player.
        float d   = player.position.distance(new Vector3f(ix, iy, iz));
        float amt = Math.max(0f, 1f - d / 130f);
        if (amt > 0.02f) {
            activeShakeAmplitude = 0.10f + 0.35f * amt;
            smashShakeTimer      = Math.max(smashShakeTimer, 0.30f * amt + 0.05f);
            if (d < 70f) ScreenEffectManager.INSTANCE.flash(1f, 0.55f, 0.18f, 0.45f * amt, 0.28f);
        }
        AudioManager.play("ground_smash", Math.min(1f, 0.35f + amt));
        m.alive = false;
    }

    // ── GATLING GUN: fire / update / viewmodel ─────────────────────────────────

    /** Hold-LMB rapid fire: hitscan rounds with tracers, muzzle flash, recoil, spread. */
    private void updateGatling(float dt, com.leaf.game.util.Camera camera) {
        if (gatlingFlash > 0f) gatlingFlash -= dt;

        boolean held = glfwGetMouseButton(window, GLFW_MOUSE_BUTTON_LEFT) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        boolean canFire = hotbar[selectedSlot] == Block.GATLING_GUN && held
                && player.can(Progression.Ability.GATLING)
                && networkInitialized && !isPreloading && !isPaused && !showChat
                && !showHelp && !showDiscoUI && !cutscene.isActive() && !player.debugMode;

        // Barrels always idle-spin a little; spin up hard while firing.
        barrelSpin += dt * (canFire ? 34f : 3f);

        if (!canFire) { gatlingCooldown = 0f; return; }

        gatlingCooldown -= dt;
        if (gatlingCooldown > 0f) return;
        gatlingCooldown = GameConfig.gatlingFireRate;

        fireGatlingRound(camera);
        gatlingShots++;
        if (gatlingShots % 2 == 0)
            AudioManager.play((gatlingShots % 4 == 0) ? "snipe1" : "snipe2", 0.35f);
    }

    /** One bullet: aim (with spread) at the most-aligned enemy or the wall; spawn VFX. */
    private void fireGatlingRound(com.leaf.game.util.Camera camera) {
        Vector3f eye   = camera.position;
        Vector3f look  = camera.getLookDirection();
        Vector3f right = camera.getRight();
        Vector3f up    = new Vector3f(right).cross(look).normalize();
        Vector3f muzzle = new Vector3f(eye).fma(0.30f, right).fma(-0.20f, up).fma(0.85f, look);

        // crazy little spread
        float spread = GameConfig.gatlingSpread;
        Vector3f dir = new Vector3f(look)
                .fma((float)(Math.random() - 0.5) * spread, right)
                .fma((float)(Math.random() - 0.5) * spread, up).normalize();

        // Dim the muzzle/tracer glow at night so additive bloom doesn't blind you.
        float fxDim = 0.38f + 0.62f * dayNight.dayFactor;

        float range = GameConfig.gatlingRange;
        Vector3f hit = null;

        // Gatling gun auto-aim removed! Pure raycast block-by-block.
        float step = 0.2f, d = 0f;
        float rx = eye.x, ry = eye.y, rz = eye.z;
        boolean hitBlock = false;

        while (d < range) {
            rx += dir.x * step; ry += dir.y * step; rz += dir.z * step; d += step;
            int bx = (int)Math.floor(rx), by = (int)Math.floor(ry), bz = (int)Math.floor(rz);

            if (by < 0 || by >= com.leaf.game.world.Chunk.HEIGHT) break;

            // ── NEW: ENEMY HITBOX CHECK DURING RAYCAST (No Auto-Aim) ──
            if (enemyManager != null) {
                boolean enemyHit = false;
                for (Enemy e : enemyManager.getEnemies()) {
                    if (e.alive && e.isHitByPlayer(new Vector3f(rx, ry, rz))) {
                        e.applyDamage(GameConfig.gatlingDamage);
                        e.applyKnockback(dir.x * 1.4f, 0.25f, dir.z * 1.4f);
                        e.hitFlashTimer = 0.12f;
                        hit = new Vector3f(rx, ry, rz);
                        fxBurst(hit.x, hit.y, hit.z, 0.1f, 0.9f, 0.16f, 2.8f*fxDim, 1.3f*fxDim, 0.45f*fxDim);
                        enemyHit = true;
                        hitBlock = true;
                        break;
                    }
                }
                if (enemyHit) break;
            }

            Block b = world.getBlock(bx, by, bz);
            if (b.isSolid()) {
                hit = new Vector3f(rx, ry, rz);
                fxBurst(hit.x, hit.y, hit.z, 0.1f, 0.55f, 0.12f, 2.4f*fxDim, 1.7f*fxDim, 0.8f*fxDim);

                // ── TUNABLE GATLING POWER ──
                int radius = 1; // 1 = tight punch, 2 = massive blast

                java.util.Set<com.leaf.game.world.Chunk> dirtyChunks = new java.util.HashSet<>();

                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dy = -radius; dy <= radius; dy++) {
                        for (int dz = -radius; dz <= radius; dz++) {
                            // Spherical mask constraint
                            if (dx*dx + dy*dy + dz*dz > radius*radius + 0.5f) continue;

                            int targetX = bx + dx;
                            int targetY = by + dy;
                            int targetZ = bz + dz;

                            if (targetY <= 1 || targetY >= com.leaf.game.world.Chunk.HEIGHT) continue;

                            Block targetBlock = world.getBlock(targetX, targetY, targetZ);
                            if (targetBlock.isSolid() && targetBlock != Block.STAR_IRON && targetBlock != Block.MEGALITH && targetBlock != Block.MEGALITH_CARVED) {
                                world.setBlock(targetX, targetY, targetZ, Block.AIR);

                                // Collect chunk coordinate for a single batched rebuild
                                int cx = Math.floorDiv(targetX, Chunk.SIZE);
                                int cy = Math.floorDiv(targetY, Chunk.HEIGHT);
                                int cz = Math.floorDiv(targetZ, Chunk.SIZE);
                                com.leaf.game.world.Chunk c = world.getChunk(cx, cy, cz);
                                if (c != null) dirtyChunks.add(c);

                                // Handle chunk boundary neighbor dirtying
                                int lx = Math.floorMod(targetX, Chunk.SIZE);
                                int lz = Math.floorMod(targetZ, Chunk.SIZE);
                                if (lx == 0)             { com.leaf.game.world.Chunk n = world.getChunk(cx - 1, cy, cz); if (n != null) dirtyChunks.add(n); }
                                if (lx == Chunk.SIZE - 1) { com.leaf.game.world.Chunk n = world.getChunk(cx + 1, cy, cz); if (n != null) dirtyChunks.add(n); }
                                if (lz == 0)             { com.leaf.game.world.Chunk n = world.getChunk(cx, cy, cz - 1); if (n != null) dirtyChunks.add(n); }
                                if (lz == Chunk.SIZE - 1) { com.leaf.game.world.Chunk n = world.getChunk(cx, cy, cz + 1); if (n != null) dirtyChunks.add(n); }
                            }
                        }
                    }
                }

                // Rebuild every modified chunk EXACTLY once
                for (com.leaf.game.world.Chunk c : dirtyChunks) {
                    world.buildChunkMeshes(c);
                }

                // Spawn exactly ONE clean dropped item at the center of the impact (if we destroyed a solid block)
                if (b != Block.AIR && b.isSolid()) {
                    Vector3f ejectVel = new Vector3f(
                            (float)(Math.random() - 0.5) * 2f,
                            3f,
                            (float)(Math.random() - 0.5) * 2f
                    );
                    droppedItems.add(new DroppedItem(bx, by, bz, b, ejectVel));
                }

                // Play break sound at impact center
                String breakSnd = blockBreakSound(b);
                if (breakSnd != null) AudioManager.playVaried(breakSnd, 0.6f, 0.1f);

                hitBlock = true;
                break;
            }
        }
        if (!hitBlock) {
            hit = new Vector3f(eye).fma(range, dir);
        }

        // tracer: a thin, bright streak from the muzzle to the hit point
        Vector3f td = new Vector3f(hit).sub(muzzle);
        float len = td.length();
        if (len > 0.05f) {
            td.div(len);
            fxBolt(muzzle.x, muzzle.y, muzzle.z, td.x, td.y, td.z, len, 0.03f, 0.06f,
                    3.2f*fxDim, 2.7f*fxDim, 1.0f*fxDim);
        }
        // muzzle flash + recoil kick
        fxBurst(muzzle.x, muzzle.y, muzzle.z, 0.1f, 0.7f, 0.06f, 3.6f*fxDim, 2.1f*fxDim, 0.7f*fxDim);
        gatlingFlash = 0.05f;
        activeShakeAmplitude = 0.028f;
        smashShakeTimer = Math.max(smashShakeTimer, 0.04f);
        camera.pitch += 0.004f;   // tiny upward climb
    }

    /** Step a ray to the first solid block (tracer endpoint), else the max-range point. */
    private Vector3f rayHitPoint(Vector3f origin, Vector3f dir, float maxDist) {
        float step = 0.4f, d = 0f;
        float x = origin.x, y = origin.y, z = origin.z;
        while (d < maxDist) {
            x += dir.x * step; y += dir.y * step; z += dir.z * step; d += step;
            int bx = (int)Math.floor(x), by = (int)Math.floor(y), bz = (int)Math.floor(z);
            if (by < 0 || by >= Chunk.HEIGHT) break;
            if (world.getBlock(bx, by, bz).isSolid()) return new Vector3f(x, y, z);
        }
        return new Vector3f(origin).fma(maxDist, dir);
    }

    /** First-person Gatling gun: a dark body + ring of spinning barrels, with muzzle glow. */
    private void renderGatlingViewModel(com.leaf.game.render.Shader shader, Matrix4f projection, Matrix4f view,
                                        com.leaf.game.util.Camera camera) {
        if (hotbar[selectedSlot] != Block.GATLING_GUN || player.debugMode
                || player.stand.isInStandPerspective()) return;
        if (gunBodyMesh   == null) gunBodyMesh   = buildColorCube(0.24f, 0.24f, 0.29f);  // gunmetal
        if (gunBarrelMesh == null) gunBarrelMesh = buildColorCube(0.52f, 0.52f, 0.58f);  // steel

        Vector3f look  = camera.getLookDirection();
        Vector3f right = camera.getRight();
        Vector3f up    = new Vector3f(right).cross(look).normalize();
        Vector3f gp = new Vector3f(camera.position)
                .fma(0.30f, right).fma(-0.26f, up).fma(0.55f, look);
        // Orientation basis: local +X->right, +Y->up, +Z-> −look (so −Z is forward/down-barrel).
        Matrix4f basis = new Matrix4f(
                right.x, right.y, right.z, 0f,
                up.x,    up.y,    up.z,    0f,
               -look.x, -look.y, -look.z, 0f,
                gp.x,    gp.y,    gp.z,    1f);
        Matrix4f pvb = new Matrix4f(projection).mul(view).mul(basis);

        glDisable(GL_DEPTH_TEST);   // weapon always drawn on top of the world

        // World-space model matrices so the gun is lit, shadowed and fogged like
        // the world around it (it used to fall into the "abyss fog" and render black).
        shader.setUniform("useModel", 1);
        // Body
        Matrix4f bodyM = new Matrix4f(basis).mul(
                new Matrix4f().translate(0f, 0f, -0.02f).scale(0.10f, 0.10f, 0.34f));
        shader.setUniform("fxModel", bodyM);
        shader.setUniform("mvp", new Matrix4f(projection).mul(view).mul(bodyM));
        gunBodyMesh.render();
        // 6 spinning barrels in a ring around the −Z axis
        for (int k = 0; k < 6; k++) {
            float a = barrelSpin + k * (float)(Math.PI / 3);
            float ox = (float)Math.cos(a) * 0.055f, oy = (float)Math.sin(a) * 0.055f;
            Matrix4f barrelM = new Matrix4f(basis).mul(
                    new Matrix4f().translate(ox, oy, -0.22f).scale(0.022f, 0.022f, 0.42f));
            shader.setUniform("fxModel", barrelM);
            shader.setUniform("mvp", new Matrix4f(projection).mul(view).mul(barrelM));
            gunBarrelMesh.render();
        }
        shader.setUniform("useModel", 0);
        // Muzzle flash (bright emissive burst at the barrel tip just after firing)
        if (gatlingFlash > 0f) {
            if (orbSphere == null) orbSphere = orbBuildSphere(14, 20);
            glEnable(GL_BLEND); glBlendFunc(GL_ONE, GL_ONE);
            shader.setUniform("emissiveMode", 1);
            float s = 0.08f + gatlingFlash * 1.6f;
            shader.setUniform("mvp", new Matrix4f(pvb).mul(
                    new Matrix4f().translate(0f, 0f, -0.46f).scale(s)));
            shader.setUniform("emissiveTint", new Vector3f(3.6f, 2.2f, 0.8f));
            orbSphere.render();
            shader.setUniform("emissiveMode", 0);
            glDisable(GL_BLEND);
        }

        glEnable(GL_DEPTH_TEST);
        shader.setUniform("mvp", new Matrix4f(projection).mul(view));
    }

    /** Upload the active torch point-lights (held hand-light + nearest placed torches). */
    private void uploadTorchLights(com.leaf.game.render.Shader shader, com.leaf.game.util.Camera camera) {
        final int MAX = 12;
        // Drop torches that have been broken/removed.
        torchPositions.removeIf(p -> world.getBlock((int)Math.floor(p.x), (int)Math.floor(p.y),
                (int)Math.floor(p.z)) != Block.TORCH);

        java.util.List<float[]> lights = new java.util.ArrayList<>();   // x,y,z, r,g,b, radius
        // Held hand-light: a torch in your hand lights the area around you.
        if (hotbar[selectedSlot] == Block.TORCH) {
            lights.add(new float[]{ camera.position.x, camera.position.y, camera.position.z,
                    1.7f, 1.0f, 0.5f, 11f });
        }
        // Molten enemies are light sources: Inferno Towers throw a broad furnace
        // glow from their crown; lava slimes carry a small warm halo.
        for (Enemy e : enemyManager.getEnemies()) {
            if (lights.size() >= MAX) break;
            if (!e.alive) continue;
            float edx = e.position.x - camera.position.x;
            float edz = e.position.z - camera.position.z;
            if (e.type == Enemy.Type.INFERNO_TOWER) {
                if (edx*edx + edz*edz > 130f * 130f) continue;
                lights.add(new float[]{ e.position.x, e.position.y + 9f, e.position.z,
                        2.2f, 0.9f, 0.30f, 26f });
            } else if (e.type == Enemy.Type.LAVA_SLIME) {
                if (edx*edx + edz*edz > 70f * 70f) continue;
                lights.add(new float[]{ e.position.x, e.position.y + 0.6f, e.position.z,
                        1.9f, 0.8f, 0.25f, 9f });
            }
        }
        // Nearest placed torches.
        Vector3f pp = player.position;
        torchPositions.sort((a, b) -> Float.compare(a.distanceSquared(pp), b.distanceSquared(pp)));
        for (Vector3f t : torchPositions) {
            if (lights.size() >= MAX) break;
            if (t.distanceSquared(pp) > 3600f) break;     // beyond 60 blocks: skip
            lights.add(new float[]{ t.x, t.y, t.z, 1.6f, 0.9f, 0.4f, 13f });
        }

        int n = Math.min(MAX, lights.size());
        shader.setUniform("torchCount", n);
        for (int i = 0; i < n; i++) {
            float[] L = lights.get(i);
            shader.setUniform("torchPos[" + i + "]", new Vector3f(L[0], L[1], L[2]));
            shader.setUniform("torchCol[" + i + "]", new Vector3f(L[3], L[4], L[5]));
            shader.setUniform("torchRad[" + i + "]", L[6]);
        }
    }

    // ── MOON (3-D sphere), CONSTELLATION LINES, SNOW TOGGLE ──────────────────

    /** Unit sphere with normals = position direction (correct for moon lighting). */
    private com.leaf.game.render.Mesh buildMoonMesh(int rings, int sectors) {
        float[] v = new float[(rings + 1) * (sectors + 1) * 10];
        int vi = 0;
        for (int i = 0; i <= rings; i++) {
            double lat = Math.PI * i / rings;
            float y = (float) Math.cos(lat), rr = (float) Math.sin(lat);
            for (int j = 0; j <= sectors; j++) {
                double lon = 2 * Math.PI * j / sectors;
                float x = rr * (float) Math.cos(lon), z = rr * (float) Math.sin(lon);
                int o = (vi++) * 10;
                v[o] = x; v[o+1] = y; v[o+2] = z;
                v[o+3] = 1; v[o+4] = 1; v[o+5] = 1; v[o+6] = 1;
                v[o+7] = x; v[o+8] = y; v[o+9] = z;   // normal = position
            }
        }
        int[] idx = new int[rings * sectors * 6];
        int ii = 0, stride = sectors + 1;
        for (int i = 0; i < rings; i++) for (int j = 0; j < sectors; j++) {
            int a = i*stride+j, b = a+stride;
            idx[ii++]=a; idx[ii++]=b; idx[ii++]=a+1;
            idx[ii++]=a+1; idx[ii++]=b; idx[ii++]=b+1;
        }
        return new com.leaf.game.render.Mesh(v, idx);
    }

    private void renderSky(Matrix4f projection, Matrix4f view, com.leaf.game.util.Camera camera) {
        if (skyShader == null || kamuiScreenQuad == 0) return;

        Matrix4f invVP = new Matrix4f(projection).mul(view).invert();
        skyShader.bind();
        skyShader.setUniform("milkyWayAxis",   dayNight.milkyWay.axis);
        skyShader.setUniform("milkyWayCentre", dayNight.milkyWay.centre);
        skyShader.setUniform("time",               (float) glfwGetTime());
        skyShader.setUniform("moonBrightness",     dayNight.moonBrightness);
        skyShader.setUniform("auroraStrength",     dayNight.auroraStrength);
        skyShader.setUniform("lunarEclipseFactor", dayNight.lunarEclipseFactor);
        skyShader.setUniform("nebulaDir_M42",      dayNight.nebulaDir_M42);
        skyShader.setUniform("nebulaDir_M31",      dayNight.nebulaDir_M31);
        skyShader.setUniform("nebulaDir_M8",       dayNight.nebulaDir_M8);
        skyShader.setUniform("nebulaDir_EtaCar",   dayNight.nebulaDir_EtaCar);

        // 1. Draw the Sky Gradient, Sun, and Moon
        skyShader.setUniform("invViewProj", invVP);
        skyShader.setUniform("sunDir",       dayNight.sunDir);
        skyShader.setUniform("moonDir",      dayNight.moonDir);
        skyShader.setUniform("skyZenith",    dayNight.skyZenith);
        skyShader.setUniform("skyHorizon",   dayNight.skyHorizon);
        skyShader.setUniform("dayFactor",    dayNight.dayFactor);
        skyShader.setUniform("nightFactor",  dayNight.nightFactor);
        skyShader.setUniform("sunsetFactor", dayNight.sunsetFactor);

        glDisable(GL_DEPTH_TEST);
        glDepthMask(false);
        org.lwjgl.opengl.GL30.glBindVertexArray(kamuiScreenQuad);
        glDrawArrays(GL_TRIANGLES, 0, 6);
        org.lwjgl.opengl.GL30.glBindVertexArray(0);

        // 2. Draw the Stars as Point Sprites
        if (starShader != null && dayNight.visibleStars != null && dayNight.nightFactor > 0.05f) {
            starShader.bind();
            Matrix4f vp = new Matrix4f(projection).mul(view);
            starShader.setUniform("viewProj", vp);
            starShader.setUniform("time", (float) glfwGetTime());
            starShader.setUniform("nightFactor", dayNight.nightFactor);

            glEnable(org.lwjgl.opengl.GL32.GL_PROGRAM_POINT_SIZE);
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE); // Additive blending for stars

            glBindVertexArray(starVao);
            glDrawArrays(GL_POINTS, 0, dayNight.visibleStars.size());
            glBindVertexArray(0);

            glDisable(org.lwjgl.opengl.GL32.GL_PROGRAM_POINT_SIZE);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            glDisable(GL_BLEND);
        }

        glDepthMask(true);
        glEnable(GL_DEPTH_TEST);
        skyShader.unbind();
    }
    private void renderMoon3D(Matrix4f projection, Matrix4f view, com.leaf.game.util.Camera camera) {
        if (moonShader == null || dayNight.moonDir.y < -0.06f || dayNight.nightFactor < 0.02f) return;
        if (moonMesh == null) moonMesh = buildMoonMesh(24, 36);
        float vis = dayNight.nightFactor * Math.min(1f, dayNight.moonDir.y * 10f + 0.4f);
        if (vis < 0.01f) return;

        Vector3f moonPos = new Vector3f(camera.position).fma(900f, dayNight.moonDir);
        Matrix4f mvp = new Matrix4f(projection).mul(view)
                .mul(new Matrix4f().translate(moonPos.x, moonPos.y, moonPos.z).scale((float) GameConfig.moonSizeScale));

        moonShader.bind();
        moonShader.setUniform("lunarEclipseFactor", dayNight.lunarEclipseFactor);
        moonShader.setUniform("mvp",            mvp);
        moonShader.setUniform("moonVisibility", vis);
        moonShader.setUniform("moonPhaseAngle", dayNight.moonPhaseAngle);

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(false);
        glDepthFunc(GL_LEQUAL);

        // FIX: CULL FACE ensures the dark back-side doesn't glitch through the bright front-side!
        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);

        moonMesh.render();

        glDisable(GL_CULL_FACE);
        glDepthMask(true);
        glDisable(GL_BLEND);
        moonShader.unbind();
    }
    /** Reproject constellation line segments to world directions and re-upload to GPU. */
    private void updateConstellationBuffer() {
        if (constellVbo == 0) return;
        double lon = Math.toRadians(GameConfig.observerLonDeg);
        double lat = Math.toRadians(GameConfig.observerLatDeg);
        double lst = Astronomy.localSiderealTime(dayNight.currentJD, lon);
        java.util.ArrayList<Float> pts = new java.util.ArrayList<>(4096);
        for (ConstellationData c : ConstellationData.ALL) {
            for (int i = 0; i + 3 < c.segs.length; i += 4) {
                double ra1 = Math.toRadians(c.segs[i]),   dec1 = Math.toRadians(c.segs[i+1]);
                double ra2 = Math.toRadians(c.segs[i+2]), dec2 = Math.toRadians(c.segs[i+3]);
                org.joml.Vector2d h1 = Astronomy.equatorialToHorizontal(ra1, dec1, lat, lst);
                org.joml.Vector2d h2 = Astronomy.equatorialToHorizontal(ra2, dec2, lat, lst);
                if (h1.y < -0.08 || h2.y < -0.08) continue;   // both must be above horizon
                Vector3f d1 = Astronomy.azAltToDirection(h1.x, h1.y);
                Vector3f d2 = Astronomy.azAltToDirection(h2.x, h2.y);
                pts.add(d1.x); pts.add(d1.y); pts.add(d1.z);
                pts.add(d2.x); pts.add(d2.y); pts.add(d2.z);
            }
        }
        constellLineCount = pts.size() / 3;
        float[] buf = new float[pts.size()];
        for (int i = 0; i < buf.length; i++) buf[i] = pts.get(i);
        glBindBuffer(GL_ARRAY_BUFFER, constellVbo);
        glBufferData(GL_ARRAY_BUFFER, buf, GL_DYNAMIC_DRAW);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }



    /** Set constellName to whichever constellation is closest to the look direction. */
    private void identifyConstellation(com.leaf.game.util.Camera camera) {
        if (camera.pitch < 0.1f) { constellName = null; return; }  // only when looking up
        double lon = Math.toRadians(GameConfig.observerLonDeg);
        double lat = Math.toRadians(GameConfig.observerLatDeg);
        double lst = Astronomy.localSiderealTime(dayNight.currentJD, lon);
        Vector3f look = camera.getLookDirection();
        float best = -1f;
        constellName = null;
        for (ConstellationData c : ConstellationData.ALL) {
            double ra = Math.toRadians(c.centerRa), dec = Math.toRadians(c.centerDec);
            org.joml.Vector2d h = Astronomy.equatorialToHorizontal(ra, dec, lat, lst);
            if (h.y < 0.05) continue;
            Vector3f d = Astronomy.azAltToDirection(h.x, h.y);
            float dot = look.dot(d);
            if (dot > best) { best = dot; constellName = c.name; }
        }
        if (best < 0.97f) constellName = null;  // only label when clearly aimed at it
    }

    /** Draw constellation lines and identify the one the player is looking at. */
    private void renderConstellations(Matrix4f projection, Matrix4f view,
                                      com.leaf.game.util.Camera camera) {
        if (!showConstellations) {
            constellName = null; // Clear the HUD label when constellations are toggled off
            return;
        }
        if (constellShader == null || constellLineCount == 0) return;
        if (dayNight.nightFactor < 0.1f) return;

        // FIX: Compute the FORWARD View-Projection matrix, not the inverse.
        Matrix4f vp = new Matrix4f(projection).mul(view);

        constellShader.bind();
        // FIX: Correct uniform name to match the shader ("viewProj")
        constellShader.setUniform("viewProj", vp);
        constellShader.setUniform("nightFactor", dayNight.nightFactor);
        constellShader.setUniform("lineAlpha", 0.45f);

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(false);
        glDisable(GL_DEPTH_TEST);
        glLineWidth(1.2f);

        glBindVertexArray(constellVao);
        glDrawArrays(GL_LINES, 0, constellLineCount);
        glBindVertexArray(0);

        glDepthMask(true);
        glEnable(GL_DEPTH_TEST);
        glDisable(GL_BLEND);
        constellShader.unbind();

        // Identify nearest constellation above horizon to where the player is looking.
        identifyConstellation(camera);
    }


    /** Render meteor bodies as glowing hot orbs with a fading comet trail. */
    private void renderMeteors(com.leaf.game.render.Shader shader, Matrix4f projection, Matrix4f view) {
        if (meteors.isEmpty()) return;
        if (orbSphere == null) orbSphere = orbBuildSphere(14, 20);
        Matrix4f pv = new Matrix4f(projection).mul(view);

        glEnable(GL_BLEND);
        glBlendFunc(GL_ONE, GL_ONE);     // additive glow (feeds the bloom)
        glDepthMask(false);
        glDepthFunc(GL_LEQUAL);
        glDisable(GL_CULL_FACE);
        shader.setUniform("emissiveMode", 1);

        for (Meteor m : meteors) {
            // soft outer corona
            orbDraw(shader, pv, orbSphere,
                    new Matrix4f().translate(m.pos.x, m.pos.y, m.pos.z).scale(m.size * 1.9f),
                    1.3f, 0.45f, 0.12f);
            // searing white-orange core
            orbDraw(shader, pv, orbSphere,
                    new Matrix4f().translate(m.pos.x, m.pos.y, m.pos.z).scale(m.size),
                    3.4f, 1.6f, 0.65f);
            // fading comet trail
            int i = 0, n = m.trail.size();
            for (float[] tp : m.trail) {
                float f = 1f - (float) i / Math.max(1, n);
                float s = m.size * (0.30f + 0.65f * f);
                orbDraw(shader, pv, orbSphere,
                        new Matrix4f().translate(tp[0], tp[1], tp[2]).scale(s),
                        2.6f * f, 1.0f * f, 0.30f * f);
                i++;
            }
        }

        shader.setUniform("emissiveMode", 0);
        shader.setUniform("mvp", new Matrix4f(pv));
        glDepthFunc(GL_LESS);
        glDepthMask(true);
        glDisable(GL_BLEND);
    }

    /** Spawn an FX received from the peer (no re-broadcast). */
    private void addRemoteFx(float[] f) {
        Fx e = new Fx((int) f[0], f[1], f[2], f[3]);
        e.dx = f[4]; e.dy = f[5]; e.dz = f[6]; e.roll = f[7]; e.sweep = f[8];
        e.life = f[9]; e.s0 = f[10]; e.s1 = f[11]; e.r = f[12]; e.g = f[13]; e.b = f[14];
        fxList.add(e);
    }

    private void updateFx(float dt) {
        dt *= GameConfig.fxTimeScale;   // dev: /fxslow slows only the VFX (for capture & tuning)
        for (java.util.Iterator<Fx> it = fxList.iterator(); it.hasNext(); ) {
            Fx e = it.next(); e.age += dt; if (e.age >= e.life) { it.remove(); continue; }
            if (e.type == FX_SPARK) {
                e.x += e.vx * dt; e.y += e.vy * dt; e.z += e.vz * dt;
                e.vy -= 24f * dt;                               // gravity arcs the streak
                float drag = Math.max(0f, 1f - 2.2f * dt);
                e.vx *= drag; e.vy *= drag; e.vz *= drag;
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  COMBAT FEEDBACK — turns every enemy hit into sparks, a damage number,
    //  and (for heavy hits / kills) a couple of frames of hit-stop.
    // ═════════════════════════════════════════════════════════════════════════
    private void processCombatFeedback(float rawDt) {
        hitStopCooldown -= rawDt;
        for (java.util.Iterator<DamageNumber> it = damageNumbers.iterator(); it.hasNext(); ) {
            DamageNumber d = it.next();
            d.age += rawDt;
            d.pop = Math.max(0f, d.pop - rawDt * 5f);
            if (d.age > 1.15f) it.remove();
        }
        if (!sparkCooldown.isEmpty()) {
            sparkCooldown.replaceAll((k, v) -> v - rawDt);
            sparkCooldown.values().removeIf(v -> v <= 0f);
        }

        for (CombatFeedback.Hit h : CombatFeedback.drain()) {
            // ── Damage number: DoT ticks on the same enemy merge into one rising number.
            DamageNumber live = null;
            for (DamageNumber d : damageNumbers) {
                if (d.enemyId == h.enemyId && d.age < 0.45f && !d.killed) { live = d; break; }
            }
            if (live == null) {
                live = new DamageNumber();
                live.enemyId = h.enemyId;
                live.x = h.x + (float) (Math.random() - 0.5) * 0.6f;
                live.y = h.y + h.halfHeight + 0.3f;
                live.z = h.z + (float) (Math.random() - 0.5) * 0.6f;
                damageNumbers.add(live);
            } else {
                live.age = Math.min(live.age, 0.12f);           // keep it alive while ticking
            }
            live.amount += h.amount;
            live.pop = 1f;
            if (h.killed) live.killed = true;

            // ── Sparks (throttled per enemy so a beam doesn't spray a fountain).
            if (h.killed || !sparkCooldown.containsKey(h.enemyId)) {
                spawnHitSparks(h);
                sparkCooldown.put(h.enemyId, 0.09f);
            }
            if (h.killed) spawnKillBurst(h);

            // ── Hit-stop: only for blows with weight, and never back-to-back.
            if (hitStopCooldown <= 0f && (h.killed || h.amount >= 40f)) {
                ScreenEffectManager.INSTANCE.hitStopSeconds(h.killed ? 0.07f : 0.04f);
                hitStopCooldown = 0.28f;
                if (h.killed && smashShakeTimer < 0.1f) {        // tiny kick on a kill
                    activeShakeDuration  = 0.12f;
                    activeShakeAmplitude = 0.07f;
                    smashShakeTimer = 0.12f;
                }
            }
        }
    }

    /** A spray of hot streaks leaving the enemy, away from the player. */
    private void spawnHitSparks(CombatFeedback.Hit h) {
        float ax = h.x - player.position.x, az = h.z - player.position.z;
        float al = (float) Math.sqrt(ax * ax + az * az);
        if (al < 1e-3f) { ax = 1f; az = 0f; al = 1f; }
        ax /= al; az /= al;
        int n = Math.min(14, 5 + (int) (h.amount / 12f)) + (h.killed ? 6 : 0);
        for (int i = 0; i < n; i++) {
            Fx e = new Fx(FX_SPARK, h.x, h.y, h.z);
            float spread = 1.1f;
            float dx = ax + (float) (Math.random() - 0.5) * 2f * spread;
            float dz = az + (float) (Math.random() - 0.5) * 2f * spread;
            float dy = 0.35f + (float) Math.random() * 0.9f;
            float dl = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            float sp = 9f + (float) Math.random() * 11f;
            e.vx = dx / dl * sp; e.vy = dy / dl * sp; e.vz = dz / dl * sp;
            e.life = 0.22f + (float) Math.random() * 0.25f;
            e.s0 = 0.8f + (float) Math.random() * 0.6f;       // thickness multiplier
            // white-hot core fading to amber
            float t = (float) Math.random();
            e.r = 2.0f; e.g = 1.1f + 0.6f * t; e.b = 0.4f + 0.5f * t;
            e.ambient = true;                                   // cosmetic; no bloom-mode switch
            fxList.add(e);
        }
    }

    /** Death: a soft flash orb + a fast shock ring on the ground plane. */
    private void spawnKillBurst(CombatFeedback.Hit h) {
        float size = Math.max(0.8f, h.halfHeight);
        Fx orb = new Fx(FX_BURST, h.x, h.y, h.z);
        orb.s0 = 0.3f * size; orb.s1 = 1.9f * size; orb.life = 0.22f;
        orb.r = 1.1f; orb.g = 0.8f; orb.b = 0.55f; orb.ambient = true;
        fxList.add(orb);
        Fx ring = new Fx(FX_RING, h.x, h.y - h.halfHeight + 0.15f, h.z);
        ring.dx = 0; ring.dy = 1; ring.dz = 0;
        ring.s0 = 0.4f * size; ring.s1 = 3.2f * size; ring.life = 0.32f;
        ring.r = 0.7f; ring.g = 0.5f; ring.b = 0.3f; ring.ambient = true;
        fxList.add(ring);
    }

    /** Floating damage numbers, drawn over the world in the HUD pass. */
    private void renderDamageNumbers(int w, int h) {
        if (damageNumbers.isEmpty()) return;
        imgui.ImDrawList draw = ImGui.getBackgroundDrawList();
        org.joml.Vector4f p = new org.joml.Vector4f();
        for (DamageNumber d : damageNumbers) {
            float rise = d.age * 1.4f;
            p.set(d.x, d.y + rise, d.z, 1f);
            orbProjView.transform(p);
            if (p.w <= 0.1f) continue;
            float sx = (p.x / p.w * 0.5f + 0.5f) * w;
            float sy = (1f - (p.y / p.w * 0.5f + 0.5f)) * h;
            float dist = p.w;
            float fade = d.age < 0.7f ? 1f : Math.max(0f, 1f - (d.age - 0.7f) / 0.45f);
            if (fade <= 0f) continue;

            boolean big = d.amount >= 60f || d.killed;
            float base = (big ? 34f : 25f) * Math.max(0.55f, Math.min(1.25f, 14f / Math.max(4f, dist)));
            float size = base * (1f + 0.45f * d.pop * d.pop);
            String txt = String.valueOf(Math.max(1, Math.round(d.amount)));
            float tw = UiFonts.width(UiFonts.bold, size, txt);
            float x = sx - tw / 2f, y = sy - size / 2f;

            float r, g, b;
            if (d.killed)   { r = 1.00f; g = 0.36f; b = 0.22f; }   // crimson finisher
            else if (big)   { r = 1.00f; g = 0.80f; b = 0.25f; }   // gold heavy hit
            else            { r = 1.00f; g = 0.97f; b = 0.90f; }   // warm white
            int shadow = ImGui.colorConvertFloat4ToU32(0.05f, 0.03f, 0.08f, 0.85f * fade);
            int col    = ImGui.colorConvertFloat4ToU32(r, g, b, fade);
            for (int ox = -2; ox <= 2; ox += 2)
                for (int oy = -2; oy <= 2; oy += 2)
                    if (ox != 0 || oy != 0) draw.addText(UiFonts.bold, size, x + ox, y + oy, shadow, txt);
            draw.addText(UiFonts.bold, size, x, y, col, txt);
        }
    }

    /** Advance quantum bullets: they phase through everything, ripple at each
     *  surface they enter/leave, and pierce enemies (even behind walls). */
    private void updateQuantumBullets(float dt, World world) {
        if (qbBullets.isEmpty()) return;
        for (java.util.Iterator<QBullet> it = qbBullets.iterator(); it.hasNext(); ) {
            QBullet b = it.next();
            b.age += dt;
            if (b.age >= b.life) { it.remove(); continue; }
            int steps = 4;                      // substep for accurate crossings at speed
            float sdt = dt / steps;
            for (int s = 0; s < steps; s++) {
                b.pos.fma(sdt, b.vel);          // pos += vel * sdt
                boolean solid = world.getBlock((int) Math.floor(b.pos.x),
                        (int) Math.floor(b.pos.y), (int) Math.floor(b.pos.z)).isSolid();
                if (solid != b.wasSolid) {
                    // Crossed a surface  -  pin a screen-space WARP to THIS world point
                    // (frozen, so it stays on the block when you turn your head) for ~1 s.
                    qbWarpStrength = 1f;
                    qbWarpX = b.pos.x; qbWarpY = b.pos.y; qbWarpZ = b.pos.z;
                    b.wasSolid = solid;
                }
            }
            if (enemyManager != null) {
                for (com.leaf.game.entity.Enemy e : enemyManager.getEnemies()) {
                    if (!e.alive || b.hit.contains(e.id)) continue;
                    float dx = e.position.x - b.pos.x, dy = (e.position.y + 0.9f) - b.pos.y,
                          dz = e.position.z - b.pos.z;
                    if (dx * dx + dy * dy + dz * dz < 1.7f) {
                        e.applyDamage(45f);
                        b.hit.add(e.id);
                        fxBurst(b.pos.x, b.pos.y, b.pos.z, 0.3f, 1.6f, 0.2f, 1.0f, 1.8f, 2.8f);
                    }
                }
            }
        }
    }

    /**
     * Vivid 3D lightning strike at (x,y,z): a thick neon shaft descending from a
     * dark sky-ring "portal", a blinding white-violet impact eruption, ground
     * shockwaves, and chaotic branching tendrils. Layered over the 2D bolt.
     */
    private void fxLightningStrike(float x, float y, float z) {
        // Main channel: a jagged, forking 3D bolt from the sky (midpoint displacement),
        // violet glow sheath around a white-hot core.
        java.util.Random rng = new java.util.Random();
        fxJaggedBolt(rng, x + (rng.nextFloat() - 0.5f) * 6f, y + 44f, z + (rng.nextFloat() - 0.5f) * 6f,
                x, y, z, 0.20f, 0.46f, 6, 5);
        // Sky-ring portal/lens high above, from which the bolt descends.
        fxRing(x, y + 40f, z, 5.0f, 7.5f, 0.55f, 1.4f, 0.5f, 2.8f);        // violet ring
        // Blinding impact eruption + ground shockwaves.
        fxBurst(x, y + 0.6f, z, 0.6f, 5.5f, 0.34f, 2.2f, 1.6f, 3.6f);      // white-violet flash
        fxRing(x, y + 0.1f, z, 1f, 10f, 0.42f, 1.3f, 1.7f, 3.5f);
        fxRing(x, y + 0.1f, z, 1f, 6f,  0.30f, 2.4f, 1.4f, 3.2f);
        // Chaotic branching tendrils lashing out from the impact.
        for (int i = 0; i < 6; i++) {
            float a = (float) (Math.random() * Math.PI * 2.0);
            float spread = 0.5f + (float) Math.random() * 0.5f;
            float dx = (float) Math.cos(a) * spread, dz = (float) Math.sin(a) * spread;
            float dy = 0.6f + (float) Math.random() * 0.7f;
            float len = 4f + (float) Math.random() * 5f;
            fxBolt(x, y + 0.5f + (float) Math.random() * 2f, z, dx, dy, dz, len, 0.12f, 0.28f,
                    2.2f, 1.2f, 3.4f);   // violet-white tendrils
        }
        smashShakeTimer = Math.max(smashShakeTimer, 0.2f);
        activeShakeAmplitude = 0.16f; activeShakeDuration = 0.22f;
        ScreenEffectManager.INSTANCE.flash(0.7f, 0.6f, 1.0f, 0.14f, 0.12f);
    }

    /**
     * A forking lightning channel between two points. Built by recursive midpoint
     * displacement (the classic fractal-lightning trick): each pass splits every
     * segment and kicks the midpoint sideways, giving the crooked, self-similar
     * path of a real bolt. Some vertices sprout thinner, shorter branches.
     *
     * @param generations subdivision passes (6 → 64 segments)
     * @param branches    how many forks to sprout along the main channel
     */
    private void fxJaggedBolt(java.util.Random rng, float x0, float y0, float z0,
                              float x1, float y1, float z1,
                              float core, float life, int generations, int branches) {
        java.util.ArrayList<float[]> pts = new java.util.ArrayList<>();
        pts.add(new float[]{x0, y0, z0});
        pts.add(new float[]{x1, y1, z1});
        float len = (float) Math.sqrt((x1-x0)*(x1-x0) + (y1-y0)*(y1-y0) + (z1-z0)*(z1-z0));
        float kick = len * 0.16f;
        for (int g = 0; g < generations; g++) {
            java.util.ArrayList<float[]> next = new java.util.ArrayList<>(pts.size() * 2);
            for (int i = 0; i < pts.size() - 1; i++) {
                float[] a = pts.get(i), b = pts.get(i + 1);
                next.add(a);
                next.add(new float[]{
                        (a[0] + b[0]) * 0.5f + (rng.nextFloat() - 0.5f) * 2f * kick,
                        (a[1] + b[1]) * 0.5f + (rng.nextFloat() - 0.5f) * 0.6f * kick,
                        (a[2] + b[2]) * 0.5f + (rng.nextFloat() - 0.5f) * 2f * kick});
            }
            next.add(pts.get(pts.size() - 1));
            pts = next;
            kick *= 0.52f;
        }
        for (int i = 0; i < pts.size() - 1; i++) {
            float[] a = pts.get(i), b = pts.get(i + 1);
            float dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
            float l = (float) Math.sqrt(dx*dx + dy*dy + dz*dz);
            if (l < 1e-3f) continue;
            float taper = 0.55f + 0.45f * (float) i / pts.size();     // thickens toward the strike
            fxBolt(a[0], a[1], a[2], dx/l, dy/l, dz/l, l, core * 2.6f * taper, life, 0.9f, 0.9f, 2.6f); // violet sheath
            fxBolt(a[0], a[1], a[2], dx/l, dy/l, dz/l, l, core * taper,        life, 2.6f, 2.7f, 3.4f); // white core
        }
        // Forks: shorter, thinner bolts peeling off the channel's upper two-thirds.
        for (int k = 0; k < branches && pts.size() > 4; k++) {
            int i = 1 + rng.nextInt(Math.max(1, pts.size() * 2 / 3));
            float[] a = pts.get(i);
            float bl = len * (0.12f + rng.nextFloat() * 0.18f);
            double ang = rng.nextDouble() * Math.PI * 2.0;
            float bx = a[0] + (float) Math.cos(ang) * bl * 0.8f;
            float bz = a[2] + (float) Math.sin(ang) * bl * 0.8f;
            float by = a[1] - bl * (0.4f + rng.nextFloat() * 0.5f);
            fxJaggedBolt(rng, a[0], a[1], a[2], bx, by, bz, core * 0.45f, life * 0.8f,
                    Math.max(3, generations - 2), 0);
        }
    }

    /** A restrained, cool teleport flourish  -  concentric rings + a slim shimmer
     *  (no blinding white flash). */
    private void fxTeleport(float x, float y, float z) {
        // Two soft blue rings rising up the body (a vertical "warp" wash, not a flash).
        fxRingN(x, y + 0.6f, z, 0f, 1f, 0f, 2.2f, 0.2f, 0.30f, 0.35f, 0.55f, 1.0f);   // contracting
        fxRingN(x, y + 1.4f, z, 0f, 1f, 0f, 0.3f, 2.0f, 0.32f, 0.30f, 0.55f, 1.1f);   // expanding
        fxBolt(x, y, z, 0f, 1f, 0f, 3.2f, 0.07f, 0.24f, 0.4f, 0.7f, 1.3f);            // slim shimmer column
        AudioManager.play("teleport", 0.6f, 0.95f + (float) Math.random() * 0.2f);
    }

    /** Crescent with a baked WIDTH gradient  -  white-hot leading edge fading to a
     *  warm, dim trailing body  -  so a slash reads as a powerful 3D blade, not a flat
     *  white shape. (Colour comes from this gradient × the per-slash emissive tint.) */
    private com.leaf.game.render.Mesh buildFxCrescent(int seg) {
        // A feathered energy blade, drawn additively. Four rows across the width:
        //   inner feather (black = invisible) → amber body → white-hot leading edge
        //   → outer feather. Both edges fade to nothing, so it reads as light, not
        //   as a solid banana-shaped plate.
        final float A = 1.22f, R = 1.0f, wMax = 0.22f;
        final float[] off = { -1.0f, -0.25f, 0.55f, 0.85f };   // across-width offsets (× w)
        final float[][] col = {
                { 0.00f, 0.00f, 0.00f },     // inner feather
                { 0.95f, 0.46f, 0.12f },     // amber body
                { 1.00f, 0.96f, 0.84f },     // white-hot edge
                { 0.00f, 0.00f, 0.00f } };   // outer feather
        final int rows = off.length;
        float[] v = new float[(seg + 1) * rows * 10];
        int vi = 0;
        for (int i = 0; i <= seg; i++) {
            float t = (float) i / seg;
            float a = -A + 2f * A * t;
            float dirX = (float) Math.sin(a), dirY = (float) Math.cos(a);
            float taper = (float) Math.pow(Math.max(0f, 1f - (a / A) * (a / A)), 0.6);
            float w = wMax * taper;
            // the blade is brightest a little past centre (the swing's "leading" half)
            float glow = (0.35f + 0.65f * taper) * (0.75f + 0.25f * t);
            for (int r = 0; r < rows; r++) {
                int o = (vi++) * 10;
                float rr = R + off[r] * w;
                v[o] = rr * dirX; v[o+1] = rr * dirY; v[o+2] = 0f;
                v[o+3] = col[r][0] * glow; v[o+4] = col[r][1] * glow; v[o+5] = col[r][2] * glow; v[o+6] = 1f;
                v[o+7] = 0f; v[o+8] = 0f; v[o+9] = 1f;
            }
        }
        int[] idx = new int[seg * (rows - 1) * 6];
        int ii = 0;
        for (int i = 0; i < seg; i++) {
            for (int r = 0; r < rows - 1; r++) {
                int a = i * rows + r, b = a + 1, c = a + rows, d = c + 1;
                idx[ii++] = a; idx[ii++] = c; idx[ii++] = b;
                idx[ii++] = b; idx[ii++] = c; idx[ii++] = d;
            }
        }
        return new com.leaf.game.render.Mesh(v, idx);
    }

    /** A solid cone, apex at +Z (tip), base ring at z=0 radius 1. Baked fiery gradient:
     *  white-hot tip -> deep red base. For the Stone Canon charge. */
    private com.leaf.game.render.Mesh buildCone(int seg) {
        // verts: apex + base ring; triangles apex->edge, plus a base fan.
        float[] v = new float[(seg + 2) * 10];
        int vi = 0;
        // apex (tip)  -  white-hot
        int o = (vi++) * 10;
        v[o]=0; v[o+1]=0; v[o+2]=1f; v[o+3]=1f; v[o+4]=0.95f; v[o+5]=0.7f; v[o+6]=1f; v[o+7]=0; v[o+8]=0; v[o+9]=1f;
        // base ring  -  deep red-orange
        for (int i = 0; i <= seg; i++) {
            double th = 2 * Math.PI * i / seg;
            o = (vi++) * 10;
            v[o]=(float)Math.cos(th); v[o+1]=(float)Math.sin(th); v[o+2]=0f;
            v[o+3]=1.0f; v[o+4]=0.30f; v[o+5]=0.08f; v[o+6]=1f;
            v[o+7]=(float)Math.cos(th); v[o+8]=(float)Math.sin(th); v[o+9]=0.4f;
        }
        int[] idx = new int[seg * 3];
        int ii = 0;
        for (int i = 0; i < seg; i++) { idx[ii++] = 0; idx[ii++] = 1 + i; idx[ii++] = 1 + (i + 1); }
        return new com.leaf.game.render.Mesh(v, idx);
    }

    private void renderFx(com.leaf.game.render.Shader shader,
                          Matrix4f projection, Matrix4f view, Matrix4f renderMvp) {
        if (fxList.isEmpty() && qbBullets.isEmpty()) return;
        if (fxCrescentMesh == null) fxCrescentMesh = buildFxCrescent(24);
        if (depCrescent == null) depCrescent = buildSlashCrescent(22);
        if (orbTorus  == null) orbTorus  = orbBuildTorus(0.05f, 72, 8);
        if (orbSphere == null) orbSphere = orbBuildSphere(14, 20);
        if (orbCyl    == null) orbCyl    = orbBuildCylinder(28);
        if (fxGlowSphere == null) fxGlowSphere = buildNormalSphere(12, 18);
        Matrix4f pv = new Matrix4f(projection).mul(view);

        glEnable(GL_BLEND);
        glBlendFunc(GL_ONE, GL_ONE);
        glDepthMask(false);
        glDepthFunc(GL_LEQUAL);
        glDisable(GL_CULL_FACE);
        shader.setUniform("emissiveMode", 1);

        for (Fx e : fxList) {
            float f = Math.min(1f, e.age / e.life);
            switch (e.type) {
                case FX_CRESCENT: {
                    float snap  = Math.min(1f, f / 0.2f);
                    float snapE = 1f - (1f - snap) * (1f - snap);
                    float scale = e.s0 + (e.s1 - e.s0) * snapE;
                    float rise  = f < 0.06f ? f / 0.06f : 1f;
                    float fall  = f > 0.5f ? (1f - (f - 0.5f) / 0.5f) : 1f;
                    float br    = rise * fall * 1.35f;  // keep under the white shoulder so amber reads
                    if (br <= 0.01f) break;
                    Matrix4f m = faceMatrix(e.x, e.y, e.z, e.dx, e.dy, e.dz)
                            .rotateZ(e.roll + e.sweep * snapE).scale(scale);
                    orbDraw(shader, pv, fxCrescentMesh, m, e.r * br, e.g * br, e.b * br);
                    break;
                }
                case FX_RING: {
                    float ease = 1f - (1f - f) * (1f - f);
                    float rad  = e.s0 + (e.s1 - e.s0) * ease;
                    float br   = (1f - f) * 3.2f;
                    orbDraw(shader, pv, orbTorus,
                            ringMatrix(e.x, e.y, e.z, e.dx, e.dy, e.dz, rad, Math.max(0.2f, rad * 0.05f)),
                            e.r * br, e.g * br, e.b * br);
                    break;
                }
                case FX_BURST: {
                    // Soft glowing orb: hot core, edges fade to nothing (was a flat disc).
                    float ease = 1f - (1f - f) * (1f - f);
                    float rad  = e.s0 + (e.s1 - e.s0) * ease;
                    float br   = (1f - f) * (1f - f) * 2.0f;
                    orbDrawSoft(shader, pv, fxGlowSphere,
                            new Matrix4f().translate(e.x, e.y, e.z).scale(rad),
                            e.r * br, e.g * br, e.b * br, 1);
                    break;
                }
                case FX_SPARK: {
                    float br = (1f - f);
                    br = br * br * 3.2f;
                    float sp = (float) Math.sqrt(e.vx * e.vx + e.vy * e.vy + e.vz * e.vz);
                    if (sp < 0.05f || br < 0.02f) break;
                    float len = Math.max(0.12f, Math.min(0.85f, sp * 0.045f));
                    float nx = e.vx / sp, ny = e.vy / sp, nz = e.vz / sp;
                    orbDraw(shader, pv, orbCyl,
                            cylAlong(e.x - nx * len, e.y - ny * len, e.z - nz * len, nx, ny, nz,
                                     len, 0.028f * e.s0),
                            e.r * br, e.g * br, e.b * br);
                    break;
                }
                case FX_BOLT: {
                    float br = (f < 0.3f ? 1f : 1f - (f - 0.3f) / 0.7f) * 4.0f;
                    if (br <= 0.01f) break;
                    float thick = e.s1 > 0f ? e.s1 : 0.10f;
                    float jitter = 1f + 0.30f * (float) Math.sin(glfwGetTime() * 55.0 + e.x * 3.1);
                    orbDraw(shader, pv, orbCyl,
                            cylAlong(e.x, e.y, e.z, e.dx, e.dy, e.dz, e.s0, thick * jitter),
                            e.r * br, e.g * br, e.b * br);
                    break;
                }
            }
        }

        // ── Quantum bullets: a searing cyan-violet orb with a streaking trail ──
        for (QBullet b : qbBullets) {
            org.joml.Vector3f vn = new org.joml.Vector3f(b.vel);
            if (vn.lengthSquared() > 1e-6f) vn.normalize();
            orbDraw(shader, pv, orbCyl,
                    cylAlong(b.pos.x - vn.x * 2.4f, b.pos.y - vn.y * 2.4f, b.pos.z - vn.z * 2.4f,
                             vn.x, vn.y, vn.z, 2.4f, 0.07f),
                    0.4f, 1.4f, 2.6f);          // trail
            orbDraw(shader, pv, orbSphere,
                    new Matrix4f().translate(b.pos.x, b.pos.y, b.pos.z).scale(0.22f),
                    1.0f, 2.6f, 3.6f);          // hot orb
        }

        shader.setUniform("emissiveMode", 0);
        shader.setUniform("emissiveTint", new Vector3f(1f, 1f, 1f));
        shader.setUniform("mvp", renderMvp);
        glDepthFunc(GL_LESS);
        glDepthMask(true);
        glDisable(GL_CULL_FACE);
        glDisable(GL_BLEND);
    }

    /** Basis matrix that orients a flat mesh's local +Z toward {@code fwd} (no scale/roll). */
    private static Matrix4f faceMatrix(float px, float py, float pz, float fx, float fy, float fz) {
        org.joml.Vector3f z = new org.joml.Vector3f(fx, fy, fz);
        if (z.lengthSquared() < 1e-6f) z.set(0, 0, 1);
        z.normalize();
        org.joml.Vector3f up = Math.abs(z.y) < 0.95f
                ? new org.joml.Vector3f(0, 1, 0) : new org.joml.Vector3f(1, 0, 0);
        org.joml.Vector3f x = new org.joml.Vector3f(up).cross(z).normalize();
        org.joml.Vector3f y = new org.joml.Vector3f(z).cross(x).normalize();
        return new Matrix4f(x.x, x.y, x.z, 0f, y.x, y.y, y.z, 0f, z.x, z.y, z.z, 0f, px, py, pz, 1f);
    }

    /** Matrix that orients the unit torus (donut in XZ, axis +Y) so its axis = (nx,ny,nz). */
    private static Matrix4f ringMatrix(float px, float py, float pz,
                                       float nx, float ny, float nz, float rad, float thick) {
        org.joml.Vector3f y = new org.joml.Vector3f(nx, ny, nz);
        if (y.lengthSquared() < 1e-6f) y.set(0, 1, 0);
        y.normalize();
        org.joml.Vector3f ref = Math.abs(y.y) < 0.95f
                ? new org.joml.Vector3f(0, 1, 0) : new org.joml.Vector3f(1, 0, 0);
        org.joml.Vector3f x = new org.joml.Vector3f(ref).cross(y).normalize();
        org.joml.Vector3f z = new org.joml.Vector3f(x).cross(y).normalize();
        return new Matrix4f(
                x.x * rad,   x.y * rad,   x.z * rad,   0f,
                y.x * thick, y.y * thick, y.z * thick, 0f,
                z.x * rad,   z.y * rad,   z.z * rad,   0f,
                px, py, pz, 1f);
    }

    /** Matrix that runs the unit cylinder (radius 1, Y∈[0,1]) from a point along a direction. */
    private static Matrix4f cylAlong(float px, float py, float pz,
                                     float dx, float dy, float dz, float len, float thick) {
        org.joml.Vector3f y = new org.joml.Vector3f(dx, dy, dz);
        if (y.lengthSquared() < 1e-6f) y.set(0, 1, 0);
        y.normalize();
        org.joml.Vector3f ref = Math.abs(y.y) < 0.95f
                ? new org.joml.Vector3f(0, 1, 0) : new org.joml.Vector3f(1, 0, 0);
        org.joml.Vector3f x = new org.joml.Vector3f(ref).cross(y).normalize();
        org.joml.Vector3f z = new org.joml.Vector3f(x).cross(y).normalize();
        return new Matrix4f(
                x.x * thick, x.y * thick, x.z * thick, 0f,
                y.x * len,   y.y * len,   y.z * len,   0f,
                z.x * thick, z.y * thick, z.z * thick, 0f,
                px, py, pz, 1f);
    }

    /**
     * Render every sliced corpse as 8 octants of the REAL enemy model, scattering
     * outward (大卸八块 -> 四散) and dissolving fast. Uses the model shader, so this is
     * called from the enemy render section (NOT inside renderDeprivationDomain).
     */
    private void renderDepCorpses(Matrix4f view, Matrix4f projection) {
        if (depCorpses.isEmpty()) return;
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);   // dissolve fade
        for (DepCorpse c : depCorpses) {
            float f = c.age / c.life;
            float sliceAlpha = 1f - f * f;                   // hang briefly, then dissolve fast
            if (sliceAlpha <= 0.02f) continue;
            float ease = 1f - (1f - f) * (1f - f);
            float sep  = c.size * 1.4f * ease;               // scatter distance (body units)
            float drop = c.size * 0.9f * f * f;              // gravity pull (body units)
            Matrix4f worldMat = new Matrix4f()
                    .translate(c.px, c.py, c.pz).rotateY(c.faceY).scale(c.sx, c.sy, c.sz);
            java.util.Random rng = new java.util.Random(c.seed);
            for (int sgx = -1; sgx <= 1; sgx += 2)
            for (int sgy = -1; sgy <= 1; sgy += 2)
            for (int sgz = -1; sgz <= 1; sgz += 2) {
                org.joml.Vector3f sign = new org.joml.Vector3f(sgx, sgy, sgz);
                org.joml.Vector3f dir  = new org.joml.Vector3f(sgx, sgy, sgz).normalize();
                float spin = (rng.nextFloat() * 2f - 1f) * 7f;
                float tumble = spin * c.age;
                Matrix4f bodyFly = new Matrix4f()
                        .translate(dir.x * sep, dir.y * sep - drop, dir.z * sep)
                        .translate(c.center.x, c.center.y, c.center.z)
                        .rotateY(tumble).rotateX(tumble * 0.6f)
                        .translate(-c.center.x, -c.center.y, -c.center.z);
                com.leaf.game.anim.ModelRenderer.renderSliced(
                        c.model, c.pose, worldMat, bodyFly, view, projection,
                        c.center, sign, sliceAlpha);
            }
        }
        glDisable(GL_BLEND);
    }

    /**
     * Build a glowing wireframe half-dome (unit radius, base at y=0, pole at y=1):
     * {@code meridians} vertical arcs + {@code rings} horizontal parallels, each a
     * thin gold ribbon lying ON the dome surface so the hemisphere shape reads from
     * inside. Brighter near the base. (10 floats/vertex; drawn additive + emissive.)
     */
    private com.leaf.game.render.Mesh buildDomeWire(int rings, int meridians) {
        java.util.ArrayList<Float>   vl = new java.util.ArrayList<>(16384);
        java.util.ArrayList<Integer> il = new java.util.ArrayList<>(16384);
        final float hw   = 0.018f;
        final float HALF = (float) (Math.PI * 0.5);
        final float wAmp = 0.17f, wFreq = 3.5f;   // meridian undulation  -  woven, not straight

        // Undulating meridians: each spirals/waves as it climbs (per-meridian phase).
        int segM = 30;
        for (int m = 0; m < meridians; m++) {
            float base = (float) (2 * Math.PI * m / meridians);
            float[] prev = null; float prevBr = 0f;
            for (int s = 0; s <= segM; s++) {
                float phi = HALF * s / segM;
                float th  = base + (float) Math.sin(phi * wFreq + m * 0.6f) * wAmp;
                float cp = (float) Math.cos(phi), sp = (float) Math.sin(phi);
                float[] P = { cp * (float) Math.cos(th), sp, cp * (float) Math.sin(th) };
                float br = 0.40f + 0.60f * (1f - phi / HALF);
                if (prev != null) addDomeTube(vl, il, prev, P, prevBr, br, hw);
                prev = P; prevBr = br;
            }
        }
        // Rippled parallels: latitude wobbles around the ring (flowing, not flat).
        int segR = meridians * 3;
        for (int k = 0; k < rings; k++) {
            float phi0 = HALF * (k + 0.4f) / rings;
            float br   = 0.40f + 0.60f * (1f - phi0 / HALF);
            float[] prev = null;
            for (int s = 0; s <= segR; s++) {
                float th  = (float) (2 * Math.PI * s / segR);
                float phi = phi0 + (float) Math.sin(th * 5f + k) * 0.045f;
                float cp = (float) Math.cos(phi), sp = (float) Math.sin(phi);
                float[] P = { cp * (float) Math.cos(th), sp, cp * (float) Math.sin(th) };
                if (prev != null) addDomeTube(vl, il, prev, P, br, br, hw);
                prev = P;
            }
        }
        float[] va = new float[vl.size()];
        for (int i = 0; i < va.length; i++) va[i] = vl.get(i);
        int[] ia = new int[il.size()];
        for (int i = 0; i < ia.length; i++) ia[i] = il.get(i);
        return new com.leaf.game.render.Mesh(va, ia);
    }

    /** Solid upper hemisphere (radius 1, base y=0 -> pole y=1), outward normals. */
    private com.leaf.game.render.Mesh buildSolidHemisphere(int rings, int sectors) {
        int vc = (rings + 1) * (sectors + 1);
        float[] v = new float[vc * 10];
        int vi = 0;
        for (int i = 0; i <= rings; i++) {
            double lat = (Math.PI * 0.5) * i / rings;     // 0 base -> π/2 pole
            float y = (float) Math.sin(lat), rr = (float) Math.cos(lat);
            for (int j = 0; j <= sectors; j++) {
                double lon = 2 * Math.PI * j / sectors;
                float x = rr * (float) Math.cos(lon), z = rr * (float) Math.sin(lon);
                int o = (vi++) * 10;
                v[o]=x; v[o+1]=y; v[o+2]=z;  v[o+3]=1; v[o+4]=1; v[o+5]=1; v[o+6]=1;  v[o+7]=x; v[o+8]=y; v[o+9]=z;
            }
        }
        int[] idx = new int[rings * sectors * 6];
        int ii = 0, stride = sectors + 1;
        for (int i = 0; i < rings; i++)
            for (int j = 0; j < sectors; j++) {
                int a = i*stride+j, b = a+stride;
                idx[ii++]=a; idx[ii++]=b; idx[ii++]=a+1; idx[ii++]=a+1; idx[ii++]=b; idx[ii++]=b+1;
            }
        return new com.leaf.game.render.Mesh(v, idx);
    }

    /**
     * Build a SOLID hexagon-cage hemisphere out of glowing tubes (real geometry, not a
     * shader pattern  -  so it stays crisp through bloom and reads as a clear cage).
     * Base sits on the ground (y=0), curving up to the pole (y=1).
     * {@code cols} = hex columns around (more ⇒ smaller, denser cells; azimuth wraps
     * seamlessly); {@code hw} = tube half-width in unit-sphere space (smaller ⇒ thinner
     * lines). Both are scaled by the dome radius at draw time.
     */
    /**
     * Build a SOLID hexagon-cage SPHERE out of glowing tubes.
     * The sphere naturally clips into the terrain using the depth buffer,
     * perfectly sealing against uneven ground.
     */
    private com.leaf.game.render.Mesh buildHexDomeWire(int cols, float hw) {
        java.util.ArrayList<Float>   vl = new java.util.ArrayList<>(1 << 19); // Doubled capacity
        java.util.ArrayList<Integer> il = new java.util.ArrayList<>(1 << 19);
        final float SQRT3 = 1.7320508f;
        final float PI    = (float) Math.PI;
        final float HALF  = PI * 0.5f;
        final float s  = (float) (2 * Math.PI) / (cols * SQRT3);   // hex size -> columns wrap

        // For a full sphere, elevation angle goes from -HALF (south pole) to HALF (north pole).
        int numRows = (int) (PI / (1.5f * s)) + 2;
        int startRow = -numRows / 2;
        int endRow   = numRows / 2;

        for (int r = startRow; r <= endRow; r++) {
            float cb = r * 1.5f * s; // elevation of cell center
            if (cb - s > HALF || cb + s < -HALF) continue; // cull outside poles

            for (int c = 0; c < cols; c++) {
                // Math.abs(r) % 2 ensures the brick-offset works safely for negative rows
                float ca = c * SQRT3 * s + (Math.abs(r) % 2) * (SQRT3 * s * 0.5f);
                float[][] cor = new float[6][];
                for (int k = 0; k < 6; k++) {
                    double ang = Math.PI / 6 + k * Math.PI / 3;
                    float ka = ca + s * (float) Math.cos(ang);
                    float kb = cb + s * (float) Math.sin(ang);

                    // Clamp to poles to seal the top and bottom of the sphere
                    kb = Math.max(-HALF, Math.min(HALF, kb));

                    float theta = HALF - kb; // 0 at north pole, PI at south pole
                    float st = (float) Math.sin(theta), ct = (float) Math.cos(theta);
                    cor[k] = new float[]{ st * (float) Math.cos(ka), ct, st * (float) Math.sin(ka) };
                }
                for (int k = 0; k < 6; k++)
                    addDomeTube(vl, il, cor[k], cor[(k + 1) % 6], 1f, 1f, hw);
            }
        }
        float[] va = new float[vl.size()];
        for (int i = 0; i < va.length; i++) va[i] = vl.get(i);
        int[] ia = new int[il.size()];
        for (int i = 0; i < ia.length; i++) ia[i] = il.get(i);
        return new com.leaf.game.render.Mesh(va, ia);
    }

    /** A crossed-ribbon tube between two unit-sphere points A,B (visible from any angle). */
    private static void addDomeTube(java.util.ArrayList<Float> vl, java.util.ArrayList<Integer> il,
                                    float[] A, float[] B, float brA, float brB, float hw) {
        float dx = B[0]-A[0], dy = B[1]-A[1], dz = B[2]-A[2];
        float mx = (A[0]+B[0])*0.5f, my = (A[1]+B[1])*0.5f, mz = (A[2]+B[2])*0.5f;
        float ml = (float) Math.sqrt(mx*mx+my*my+mz*mz); if (ml < 1e-5f) ml = 1f;
        float rx = mx/ml, ry = my/ml, rz = mz/ml;                    // radial
        float w1x = dy*rz-dz*ry, w1y = dz*rx-dx*rz, w1z = dx*ry-dy*rx; // perp to dir & radial
        float w1l = (float) Math.sqrt(w1x*w1x+w1y*w1y+w1z*w1z); if (w1l < 1e-5f) w1l = 1f;
        w1x = w1x/w1l*hw; w1y = w1y/w1l*hw; w1z = w1z/w1l*hw;
        domePush(vl, il,
            A[0]+w1x,A[1]+w1y,A[2]+w1z, B[0]+w1x,B[1]+w1y,B[2]+w1z,
            B[0]-w1x,B[1]-w1y,B[2]-w1z, A[0]-w1x,A[1]-w1y,A[2]-w1z, brA, brB);
        float w2x = rx*hw, w2y = ry*hw, w2z = rz*hw;                 // radial ribbon
        domePush(vl, il,
            A[0]+w2x,A[1]+w2y,A[2]+w2z, B[0]+w2x,B[1]+w2y,B[2]+w2z,
            B[0]-w2x,B[1]-w2y,B[2]-w2z, A[0]-w2x,A[1]-w2y,A[2]-w2z, brA, brB);
    }

    /** Push one dome ribbon quad (4 verts, white×brightness; normal = radial-ish). */
    private static void domePush(java.util.ArrayList<Float> vl, java.util.ArrayList<Integer> il,
                                 float ax,float ay,float az, float bx,float by,float bz,
                                 float cx,float cy,float cz, float dx,float dy,float dz,
                                 float br0, float br1) {
        int base = vl.size() / 10;
        float[][] P = {{ax,ay,az,br0},{bx,by,bz,br1},{cx,cy,cz,br1},{dx,dy,dz,br0}};
        for (float[] p : P) {
            vl.add(p[0]); vl.add(p[1]); vl.add(p[2]);
            vl.add(p[3]); vl.add(p[3]); vl.add(p[3]); vl.add(1f);  // white × brightness
            vl.add(p[0]); vl.add(p[1]); vl.add(p[2]);               // radial normal
        }
        il.add(base); il.add(base+1); il.add(base+2);
        il.add(base); il.add(base+2); il.add(base+3);
    }

    /**
     * Build a unit anime sword-sweep crescent in the local XY plane.
     * An arc spanning [-A, A] at centreline radius 1, with a half-thickness that
     * tapers to sharp points at both tips (the classic slash shape). Vertex colour
     * is white (×emissiveTint at draw), dimmer toward the tips for soft ends.
     */
    private com.leaf.game.render.Mesh buildSlashCrescent(int seg) {
        final float A = 1.15f;     // arc half-angle (radians)  -  ~66°
        final float R = 1.0f;      // centreline radius
        final float wMax = 0.20f;  // max half-thickness of the blade
        float[] v = new float[(seg + 1) * 2 * 10];
        int vi = 0;
        for (int i = 0; i <= seg; i++) {
            float t = (float) i / seg;            // 0..1
            float a = -A + 2f * A * t;            // -A..A
            float dirX = (float) Math.sin(a), dirY = (float) Math.cos(a);
            float taper = 1f - (a / A) * (a / A); // 1 at centre, 0 at tips
            taper = (float) Math.pow(Math.max(0f, taper), 0.6);
            float w = wMax * taper;
            float bright = 0.40f + 0.60f * taper; // soft tips, hot centre
            int o = (vi++) * 10;                   // inner edge vertex
            v[o]   = (R - w) * dirX; v[o+1] = (R - w) * dirY; v[o+2] = 0f;
            v[o+3] = bright; v[o+4] = bright; v[o+5] = bright; v[o+6] = 1f;
            v[o+7] = 0f; v[o+8] = 0f; v[o+9] = 1f;
            o = (vi++) * 10;                       // outer edge vertex
            v[o]   = (R + w) * dirX; v[o+1] = (R + w) * dirY; v[o+2] = 0f;
            v[o+3] = bright; v[o+4] = bright; v[o+5] = bright; v[o+6] = 1f;
            v[o+7] = 0f; v[o+8] = 0f; v[o+9] = 1f;
        }
        int[] idx = new int[seg * 6];
        int ii = 0;
        for (int i = 0; i < seg; i++) {
            int a = i*2, b = i*2+1, c = i*2+2, d = i*2+3;
            idx[ii++] = a; idx[ii++] = c; idx[ii++] = b;
            idx[ii++] = b; idx[ii++] = c; idx[ii++] = d;
        }
        return new com.leaf.game.render.Mesh(v, idx);
    }

    /**
     * Build a flat phantom-sword silhouette (tip at +Y, flat in the local XY plane):
     * a white-hot blade triangle, a gold crossguard, and a darker-gold hilt.
     * Used for the idle "ready-sword" aura ring. (10 floats/vertex.)
     */
    private com.leaf.game.render.Mesh buildPhantomBlade() {
        float[] v = {
            //  x       y      z    r     g     b     a    nx ny nz
            // blade (white-hot)  -  triangle
             0.000f, 1.50f, 0f,  1.0f, 0.95f, 0.70f, 1f,  0f,0f,1f,
            -0.060f, 0.30f, 0f,  1.0f, 0.95f, 0.70f, 1f,  0f,0f,1f,
             0.060f, 0.30f, 0f,  1.0f, 0.95f, 0.70f, 1f,  0f,0f,1f,
            // crossguard (gold)  -  quad
            -0.220f, 0.24f, 0f,  1.0f, 0.80f, 0.35f, 1f,  0f,0f,1f,
             0.220f, 0.24f, 0f,  1.0f, 0.80f, 0.35f, 1f,  0f,0f,1f,
             0.220f, 0.32f, 0f,  1.0f, 0.80f, 0.35f, 1f,  0f,0f,1f,
            -0.220f, 0.32f, 0f,  1.0f, 0.80f, 0.35f, 1f,  0f,0f,1f,
            // hilt (dark gold)  -  quad
            -0.045f, 0.00f, 0f,  0.8f, 0.60f, 0.25f, 1f,  0f,0f,1f,
             0.045f, 0.00f, 0f,  0.8f, 0.60f, 0.25f, 1f,  0f,0f,1f,
             0.045f, 0.26f, 0f,  0.8f, 0.60f, 0.25f, 1f,  0f,0f,1f,
            -0.045f, 0.26f, 0f,  0.8f, 0.60f, 0.25f, 1f,  0f,0f,1f,
        };
        int[] idx = {
            0, 1, 2,            // blade
            3, 4, 5,  3, 5, 6,  // guard
            7, 8, 9,  7, 9, 10, // hilt
        };
        return new com.leaf.game.render.Mesh(v, idx);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  CHOCOLATE DISCO  -  FLAT HOLOGRAM METHODS
    // ══════════════════════════════════════════════════════════════════════════

    private void spawnDiscoGrid(Camera cam, World world) {
        Vector3f ld = cam.getLookDirection();

        // Spawn ~15 blocks ahead of the player
        cdGridX = player.position.x + ld.x * 15f;
        cdGridZ = player.position.z + ld.z * 15f;
        // Flat, rigid waist-level hologram plane
        cdGridY = (int) Math.floor(player.position.y) + 0.1f;

        cdActive = true;
        showDiscoUI = true;
        cdSpawnT = 0f;
        cdMeshDirty = true;
        cdHoverR = -1; cdHoverC = -1;
        for (boolean[] row : cdMarked)  java.util.Arrays.fill(row, false);
        for (float[]   row : cdDetT)    java.util.Arrays.fill(row, 0f);
        for (float[]   row : cdRingT)   java.util.Arrays.fill(row, 0f);
        for (boolean[] row : cdBlasted) java.util.Arrays.fill(row, false);

        // Free mouse for the 2D ImGui console
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
        firstMouse[0] = true;
    }

    void dismissDiscoGrid() {
        showDiscoUI = false;
        cdSpawnT = -0.7f; // Start despawn countdown
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED); // Re-lock mouse
        firstMouse[0] = true;
    }

    private void updateDiscoGrid(float dt, Camera cam, World world) {
        if (cdSpawnT >= 0f && cdSpawnT < 1f) {
            cdSpawnT = Math.min(1f, cdSpawnT + dt / CD_SPAWN);
            cdMeshDirty = true;
        }

        // Flash + shockwave-ring timers
        boolean anyActive = false;
        for (int r = 0; r < CD_ROWS; r++) {
            for (int c = 0; c < CD_COLS; c++) {
                if (cdDetT[r][c] > 0f) {
                    anyActive = true; cdDetT[r][c] -= dt; cdMeshDirty = true;
                    if (cdDetT[r][c] <= 0f) { cdDetT[r][c] = 0f; cdMarked[r][c] = false; }
                }
                if (cdRingT[r][c] > 0f) { anyActive = true; cdRingT[r][c] = Math.max(0f, cdRingT[r][c] - dt); }
                if (cdMarked[r][c]) anyActive = true;
            }
        }

        // Auto-despawn when finished exploding
        if (!anyActive && cdSpawnT > 0f && !showDiscoUI) {
            boolean anyBlasted = false;
            for (boolean[] row : cdBlasted) for (boolean b : row) if (b) { anyBlasted = true; break; }
            if (anyBlasted) cdSpawnT = -0.7f;
        }

        if (cdSpawnT < 0f) {
            cdSpawnT += dt; cdMeshDirty = true;
            if (cdSpawnT >= 0f) {
                cdActive = false;
                if (cdMesh != null) { cdMesh.cleanup(); cdMesh = null; }
                for (boolean[] row : cdBlasted) java.util.Arrays.fill(row, false);
            }
        }
    }

    void detonateDiscoGrid(World world) {
        boolean any = false;
        for (int r = 0; r < CD_ROWS; r++) {
            for (int c = 0; c < CD_COLS; c++) {
                if (cdMarked[r][c] && cdDetT[r][c] <= 0f) {
                    cdDetT[r][c]   = CD_DET_DUR;
                    cdRingT[r][c]  = CD_RING_DUR;
                    cdBlasted[r][c] = true;
                    cdDetonateCell(r, c, world);
                    any = true;
                }
            }
        }
        if (any) {
            cdMeshDirty = true;
            activeShakeDuration  = 0.45f;
            activeShakeAmplitude = 0.24f;
            smashShakeTimer      = activeShakeDuration;
            com.leaf.game.core.ScreenEffectManager.INSTANCE.flash(1f, 0.82f, 0.35f, 0.45f, 0.22f);
            com.leaf.game.core.AudioManager.play("paper_explode", 1f);
        }

        // Hide UI, return camera control to watch the fireworks
        showDiscoUI = false;
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
        firstMouse[0] = true;
    }

    private void cdDetonateCell(int row, int col, World world) {
        float x0 = cdGridX - CD_HALF + col * CD_CELL;
        float z0 = cdGridZ - CD_HALF + row * CD_CELL;
        // Crush blocks below and above the grid plane
        int y0 = Math.max(2, (int)cdGridY - 2);
        int y1 = Math.min(Chunk.HEIGHT - 1, (int)cdGridY + CD_CRUSH_H);

        for (int bx = (int) x0; bx < (int) x0 + CD_CELL; bx++) {
            for (int bz = (int) z0; bz < (int) z0 + CD_CELL; bz++) {
                for (int by = y0; by <= y1; by++) {
                    Block b = world.getBlock(bx, by, bz);
                    if (b != Block.AIR && b.hardness < 9f) {
                        world.setBlock(bx, by, bz, Block.AIR);
                    }
                }
            }
        }

        if (enemyManager != null) {
            for (com.leaf.game.entity.Enemy e : enemyManager.getEnemies()) {
                if (!e.alive) continue;
                float ex = e.position.x, ez = e.position.z;
                if (ex >= x0 && ex <= x0 + CD_CELL && ez >= z0 && ez <= z0 + CD_CELL) {
                    e.health = 0; e.alive = false;
                }
            }
        }
    }

    private void renderDiscoGrid(com.leaf.game.render.Shader shader,
                                 Matrix4f projection, Matrix4f view,
                                 Matrix4f renderMvp) {
        float spawnFade = Math.max(0f, Math.min(1f, cdSpawnT < 0f ? 1f + cdSpawnT / 0.7f : cdSpawnT));
        if (spawnFade < 0.01f) return;

        if (cdMeshDirty || cdMesh == null) {
            if (cdMesh != null) cdMesh.cleanup();
            cdMesh = cdBuildMesh(spawnFade);
            cdMeshDirty = false;
        }

        Matrix4f pv = new Matrix4f(projection).mul(view);
        if (orbCyl   == null) orbCyl   = orbBuildCylinder(28);
        if (orbTorus == null) orbTorus = orbBuildTorus(0.05f, 72, 8);

        glEnable(GL_BLEND);
        glBlendFunc(GL_ONE, GL_ONE);
        glDepthMask(false);
        glDepthFunc(GL_ALWAYS); // <--- X-RAY EFFECT! Slices straight through mountains like a hologram.
        glDisable(GL_CULL_FACE);
        shader.setUniform("emissiveMode", 1);

        float pulse = 0.92f + 0.08f * (float) Math.sin(glfwGetTime() * 3.0);
        shader.setUniform("emissiveTint", new Vector3f(pulse, pulse, pulse));
        shader.setUniform("mvp", pv);
        cdMesh.render();

        for (int r = 0; r < CD_ROWS; r++) {
            for (int c = 0; c < CD_COLS; c++) {
                float cx = cdGridX - CD_HALF + (c + 0.5f) * CD_CELL;
                float cz = cdGridZ - CD_HALF + (r + 0.5f) * CD_CELL;
                float cy = cdGridY;

                float dt = cdDetT[r][c];
                if (dt > 0f) {
                    float frac = dt / CD_DET_DUR;
                    float peak = 1f - Math.abs(frac - 0.5f) * 2f;
                    float br   = 5f + peak * 14f;
                    float pR   = CD_CELL * 0.42f;
                    float pH   = 30f + peak * 55f;
                    orbDraw(shader, pv, orbCyl,
                            new Matrix4f().translate(cx, cy, cz).scale(pR * 0.28f, pH, pR * 0.28f),
                            br, br, br * 0.9f);
                    orbDraw(shader, pv, orbCyl,
                            new Matrix4f().translate(cx, cy, cz).scale(pR, pH * 0.7f, pR),
                            br * 0.30f, br * 0.26f, br * 0.12f);
                }
                float rt = cdRingT[r][c];
                if (rt > 0f) {
                    float rf = 1f - rt / CD_RING_DUR;
                    float ringR = CD_CELL * (0.4f + rf * 2.6f);
                    float ringB = (1f - rf) * 6f;
                    orbDraw(shader, pv, orbTorus,
                            new Matrix4f().translate(cx, cy + 0.2f, cz).scale(ringR, 0.6f, ringR),
                            ringB, ringB * 0.8f, ringB * 0.3f);
                }
            }
        }

        shader.setUniform("emissiveMode", 0);
        shader.setUniform("emissiveTint", new Vector3f(1f, 1f, 1f));
        shader.setUniform("mvp", renderMvp);
        glDepthFunc(GL_LESS);
        glDepthMask(true);
        glDisable(GL_CULL_FACE);  // leave culling OFF  -  global default; terrain needs it off
        glDisable(GL_BLEND);
    }

    private com.leaf.game.render.Mesh cdBuildMesh(float spawnFade) {
        java.util.ArrayList<Float>   vl = new java.util.ArrayList<>(4096);
        java.util.ArrayList<Integer> il = new java.util.ArrayList<>(4096);

        float baseX = cdGridX - CD_HALF, baseZ = cdGridZ - CD_HALF;
        float lw = CD_LW * spawnFade;
        final float GR = 1.30f, GG = 0.85f, GB = 0.22f;

        // Long flat ribbons spanning the whole 3D grid
        for (int i = 0; i <= CD_ROWS; i++) {
            int lz = i * CD_CELL;
            float k = (i == 0 || i == CD_ROWS) ? 1.0f : 0.5f;
            addCDGroundRibbon(vl, il,
                    baseX,           cdGridY, baseZ + lz,
                    baseX + CD_SPAN, cdGridY, baseZ + lz,
                    GR * k * spawnFade, GG * k * spawnFade, GB * k * spawnFade, lw);
        }
        for (int j = 0; j <= CD_COLS; j++) {
            int lx = j * CD_CELL;
            float k = (j == 0 || j == CD_COLS) ? 1.0f : 0.5f;
            addCDGroundRibbon(vl, il,
                    baseX + lx, cdGridY, baseZ,
                    baseX + lx, cdGridY, baseZ + CD_SPAN,
                    GR * k * spawnFade, GG * k * spawnFade, GB * k * spawnFade, lw);
        }

        float wh = CD_WALL * spawnFade;
        for (int r = 0; r < CD_ROWS; r++) {
            for (int c = 0; c < CD_COLS; c++) {
                boolean isHover  = (r == cdHoverR && c == cdHoverC && !cdMarked[r][c]);
                boolean isMarked = cdMarked[r][c] && cdDetT[r][c] <= 0f;
                boolean isDet    = cdDetT[r][c] > 0f;
                if (!isHover && !isMarked && !isDet) continue;

                float cellR, cellG, cellB, edgeBright, boxH = wh;
                if (isDet) {
                    float frac = cdDetT[r][c] / CD_DET_DUR;
                    float peak = 1f - Math.abs(frac - 0.5f) * 2f;
                    edgeBright = 3.0f + peak * 7.0f;
                    cellR = 1f; cellG = 1f; cellB = 0.9f;
                    boxH  = wh * (1f + peak * 2.5f);
                } else if (isMarked) {
                    edgeBright = 1.5f; cellR = 1.30f; cellG = 0.80f; cellB = 0.15f;
                } else {
                    edgeBright = 1.1f; cellR = 0.30f; cellG = 0.85f; cellB = 1.00f;
                }
                float er = cellR * edgeBright * spawnFade;
                float eg = cellG * edgeBright * spawnFade;
                float eb = cellB * edgeBright * spawnFade;

                float x0 = baseX + c * CD_CELL, x1 = x0 + CD_CELL;
                float z0 = baseZ + r * CD_CELL, z1 = z0 + CD_CELL;
                float y0 = cdGridY, y1 = y0 + boxH;

                addCDEdgeX(vl, il, x0, x1, y0, z0, er, eg, eb, lw);
                addCDEdgeX(vl, il, x0, x1, y0, z1, er, eg, eb, lw);
                addCDEdgeZ(vl, il, z0, z1, y0, x0, er, eg, eb, lw);
                addCDEdgeZ(vl, il, z0, z1, y0, x1, er, eg, eb, lw);
                addCDEdgeX(vl, il, x0, x1, y1, z0, er, eg, eb, lw);
                addCDEdgeX(vl, il, x0, x1, y1, z1, er, eg, eb, lw);
                addCDEdgeZ(vl, il, z0, z1, y1, x0, er, eg, eb, lw);
                addCDEdgeZ(vl, il, z0, z1, y1, x1, er, eg, eb, lw);
                addCDEdgeY(vl, il, y0, y1, x0, z0, er, eg, eb, lw);
                addCDEdgeY(vl, il, y0, y1, x1, z0, er, eg, eb, lw);
                addCDEdgeY(vl, il, y0, y1, x0, z1, er, eg, eb, lw);
                addCDEdgeY(vl, il, y0, y1, x1, z1, er, eg, eb, lw);

                float fa = isDet ? edgeBright * 0.05f : 0.10f;
                addCDQuad(vl, il,
                        x0, y0, z0,  x1, y0, z0,  x1, y0, z1,  x0, y0, z1,
                        er * fa, eg * fa, eb * fa, 1f, 0, 1, 0);
            }
        }

        // Float the text perfectly flat on the grid!
        float gw = 1.1f, gh = 1.7f;
        for (int c = 0; c < CD_COLS; c++) {
            float originX = baseX + (c + 0.5f) * CD_CELL - gw * 0.5f;
            float originZ = baseZ - 0.5f;
            addCDGlyph(vl, il, cdGlyph((char) ('1' + c)),
                    originX, cdGridY, originZ, gw, 0f, 0f, -gh,
                    GR * spawnFade, GG * spawnFade, GB * spawnFade, lw);
        }
        for (int r = 0; r < CD_ROWS; r++) {
            float originX = baseX - 0.5f - gw;
            float originZ = baseZ + (r + 0.5f) * CD_CELL + gh * 0.5f;
            addCDGlyph(vl, il, cdGlyph((char) ('A' + r)),
                    originX, cdGridY, originZ, gw, 0f, 0f, -gh,
                    GR * spawnFade, GG * spawnFade, GB * spawnFade, lw);
        }

        float[] va = new float[vl.size()];
        for (int i = 0; i < va.length; i++) va[i] = vl.get(i);
        int[] ia = new int[il.size()];
        for (int i = 0; i < ia.length; i++) ia[i] = il.get(i);
        return new com.leaf.game.render.Mesh(va, ia);
    }

    private void addCDGlyph(java.util.ArrayList<Float> vl, java.util.ArrayList<Integer> il,
                            float[] seg, float originX, float flatY, float originZ,
                            float uX, float uZ, float vX, float vZ,
                            float r, float g, float b, float lw) {
        for (int s = 0; s + 3 < seg.length; s += 4) {
            float u0 = seg[s], v0 = seg[s + 1], u1 = seg[s + 2], v1 = seg[s + 3];
            float wx0 = originX + u0 * uX + v0 * vX, wz0 = originZ + u0 * uZ + v0 * vZ;
            float wx1 = originX + u1 * uX + v1 * vX, wz1 = originZ + u1 * uZ + v1 * vZ;
            addCDGroundRibbon(vl, il, wx0, flatY, wz0, wx1, flatY, wz1, r, g, b, lw);
        }
    }

    /** Stroke font (segments u0,v0,u1,v1 in a unit cell) for digits 1-9 and letters A-I. */
    private static float[] cdGlyph(char ch) {
        switch (ch) {
            case '1': return new float[]{1,1, 1,0};
            case '2': return new float[]{0,1,1,1, 1,1,1,0.5f, 0,0.5f,1,0.5f, 0,0.5f,0,0, 0,0,1,0};
            case '3': return new float[]{0,1,1,1, 1,1,1,0.5f, 0,0.5f,1,0.5f, 1,0.5f,1,0, 0,0,1,0};
            case '4': return new float[]{0,1,0,0.5f, 1,1,1,0.5f, 0,0.5f,1,0.5f, 1,0.5f,1,0};
            case '5': return new float[]{0,1,1,1, 0,1,0,0.5f, 0,0.5f,1,0.5f, 1,0.5f,1,0, 0,0,1,0};
            case '6': return new float[]{0,1,1,1, 0,1,0,0, 0,0,1,0, 1,0,1,0.5f, 0,0.5f,1,0.5f};
            case '7': return new float[]{0,1,1,1, 1,1,1,0};
            case '8': return new float[]{0,1,1,1, 1,1,1,0, 0,1,0,0, 0,0,1,0, 0,0.5f,1,0.5f};
            case '9': return new float[]{0,1,1,1, 1,1,1,0, 0,0,1,0, 0,1,0,0.5f, 0,0.5f,1,0.5f};
            case 'A': return new float[]{0,0,0.5f,1, 0.5f,1,1,0, 0.25f,0.45f,0.75f,0.45f};
            case 'B': return new float[]{0,0,0,1, 0,1,1,1, 0,0.5f,1,0.5f, 0,0,1,0, 1,1,1,0.5f, 1,0.5f,1,0};
            case 'C': return new float[]{0,1,1,1, 0,1,0,0, 0,0,1,0};
            case 'D': return new float[]{0,0,0,1, 0,1,1,1, 1,1,1,0, 1,0,0,0};
            case 'E': return new float[]{0,0,0,1, 0,1,1,1, 0,0.5f,0.85f,0.5f, 0,0,1,0};
            case 'F': return new float[]{0,0,0,1, 0,1,1,1, 0,0.5f,0.8f,0.5f};
            case 'G': return new float[]{0,1,1,1, 0,1,0,0, 0,0,1,0, 1,0,1,0.5f, 1,0.5f,0.55f,0.5f};
            case 'H': return new float[]{0,0,0,1, 1,0,1,1, 0,0.5f,1,0.5f};
            case 'I': return new float[]{0.5f,0,0.5f,1, 0.2f,1,0.8f,1, 0.2f,0,0.8f,0};
            default:  return new float[0];
        }
    }

    // ── CHOCOLATE DISCO HELPERS ──────────────────────────────────────────────
    private static void addCDGroundRibbon(java.util.ArrayList<Float> vl, java.util.ArrayList<Integer> il,
                                          float ax, float ay, float az,
                                          float bx, float by, float bz,
                                          float r, float g, float b, float lw) {
        float dx = bx - ax, dz = bz - az;
        float len = (float) Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-5f) return;
        float px = -dz / len * lw * 0.5f, pz = dx / len * lw * 0.5f;
        addCDQuad(vl, il,
                ax + px, ay, az + pz,  bx + px, by, bz + pz,
                bx - px, by, bz - pz,  ax - px, ay, az - pz,
                r, g, b, 1f, 0, 1, 0);
    }

    private static void addCDQuad(java.util.ArrayList<Float> vl, java.util.ArrayList<Integer> il,
                                  float x0, float y0, float z0,
                                  float x1, float y1, float z1,
                                  float x2, float y2, float z2,
                                  float x3, float y3, float z3,
                                  float r, float g, float b, float a,
                                  float nx, float ny, float nz) {
        int base = vl.size() / 10;
        float[] pts = {x0,y0,z0, x1,y1,z1, x2,y2,z2, x3,y3,z3};
        for (int i = 0; i < 4; i++) {
            vl.add(pts[i*3]); vl.add(pts[i*3+1]); vl.add(pts[i*3+2]);
            vl.add(r); vl.add(g); vl.add(b); vl.add(a);
            vl.add(nx); vl.add(ny); vl.add(nz);
        }
        il.add(base); il.add(base+1); il.add(base+2);
        il.add(base); il.add(base+2); il.add(base+3);
    }

    private static void addCDEdgeX(java.util.ArrayList<Float> vl, java.util.ArrayList<Integer> il,
                                   float ax, float bx, float ey, float ez,
                                   float r, float g, float b, float lw) {
        float h = lw * 0.5f;
        addCDQuad(vl, il,
                ax, ey, ez-h,   bx, ey, ez-h,   bx, ey, ez+h,   ax, ey, ez+h,
                r, g, b, 1f, 0, 1, 0);
    }

    private static void addCDEdgeZ(java.util.ArrayList<Float> vl, java.util.ArrayList<Integer> il,
                                   float az, float bz, float ey, float ex,
                                   float r, float g, float b, float lw) {
        float h = lw * 0.5f;
        addCDQuad(vl, il,
                ex-h, ey, az,   ex+h, ey, az,   ex+h, ey, bz,   ex-h, ey, bz,
                r, g, b, 1f, 0, 1, 0);
    }

    private static void addCDEdgeY(java.util.ArrayList<Float> vl, java.util.ArrayList<Integer> il,
                                   float ay, float by_, float ex, float ez,
                                   float r, float g, float b, float lw) {
        float h = lw * 0.5f;
        addCDQuad(vl, il,
                ex-h, ay, ez,   ex+h, ay, ez,   ex+h, by_, ez,   ex-h, by_, ez,
                r, g, b, 1f, 0, 0, 1);
        addCDQuad(vl, il,
                ex, ay, ez-h,   ex, ay, ez+h,   ex, by_, ez+h,   ex, by_, ez-h,
                r, g, b, 1f, 1, 0, 0);
    }

    private com.leaf.game.render.Mesh orbBuildCube() {
        float[] c = { -0.5f,-0.5f,-0.5f,  0.5f,-0.5f,-0.5f,  0.5f,0.5f,-0.5f,  -0.5f,0.5f,-0.5f,
                -0.5f,-0.5f, 0.5f,  0.5f,-0.5f, 0.5f,  0.5f,0.5f, 0.5f,  -0.5f,0.5f, 0.5f };
        float[] v = new float[8 * 10];
        for (int i = 0; i < 8; i++) {
            int o = i * 10;
            v[o]=c[i*3]; v[o+1]=c[i*3+1]; v[o+2]=c[i*3+2];
            v[o+3]=1; v[o+4]=1; v[o+5]=1; v[o+6]=1; v[o+7]=0; v[o+8]=1; v[o+9]=0;
        }
        int[] idx = {
                0,1,2, 0,2,3,  4,6,5, 4,7,6,  0,4,5, 0,5,1,
                3,2,6, 3,6,7,  1,5,6, 1,6,2,  0,3,7, 0,7,4 };
        return new com.leaf.game.render.Mesh(v, idx);
    }
    /** Renders a large, full-bright 3D yellow bird with flapping wings. */
    private void renderYellowBird(Shader shader, Matrix4f projection, Matrix4f view) {
        Matrix4f pv = new Matrix4f(projection).mul(view);
        float t = (float) glfwGetTime();

        // Wings flap aggressively when flying up, and glide slower when falling down
        float flapSpeed = (player.testMovement.velocity.y > 0) ? 35.0f : 12.0f;
        float wingFlap  = (float) Math.sin(t * flapSpeed) * 0.45f;
        Vector3f pos    = player.position;

        // Enable unlit emissive rendering so the bird glows vividly in the dark
        shader.setUniform("emissiveMode", 1);
        shader.setUniform("emissiveTint", new Vector3f(1.6f, 1.6f, 1.6f));

        // 1. Massive Yellow Body (Scale boosted to clear the 0.24f mesh limit)
        Matrix4f body = new Matrix4f()
                .translate(pos.x, pos.y + 0.9f, pos.z)
                .scale(5.40f);
        shader.setUniform("mvp", new Matrix4f(pv).mul(body));
        getItemMesh(Block.CRYSTAL_CITRINE).render();

        // 2. Large Orange Beak (facing forward along +X)
        Matrix4f beak = new Matrix4f()
                .translate(pos.x + 0.72f, pos.y + 0.82f, pos.z)
                .scale(1.70f, 1.30f, 2.10f);
        shader.setUniform("mvp", new Matrix4f(pv).mul(beak));
        getItemMesh(Block.RED_SAND).render();

        // 3. Flapping White Wings
        // Left wing (Z = -0.75)
        Matrix4f wingL = new Matrix4f()
                .translate(pos.x - 0.10f, pos.y + 0.9f, pos.z - 0.75f)
                .rotateX(wingFlap)
                .scale(1.60f, 2.40f, 0.70f);
        shader.setUniform("mvp", new Matrix4f(pv).mul(wingL));
        getItemMesh(Block.SNOW).render();

        // Right wing (Z = +0.75)
        Matrix4f wingR = new Matrix4f()
                .translate(pos.x - 0.10f, pos.y + 0.9f, pos.z + 0.75f)
                .rotateX(-wingFlap)
                .scale(1.60f, 2.40f, 0.70f);
        shader.setUniform("mvp", new Matrix4f(pv).mul(wingR));
        getItemMesh(Block.SNOW).render();

        // 4. White/Black Eyes
        // Left eye
        Matrix4f eyeL = new Matrix4f()
                .translate(pos.x + 0.42f, pos.y + 1.25f, pos.z - 0.52f)
                .scale(0.70f, 0.70f, 0.70f);
        shader.setUniform("mvp", new Matrix4f(pv).mul(eyeL));
        getItemMesh(Block.CRYSTAL_QUARTZ).render();

        // Right eye
        Matrix4f eyeR = new Matrix4f()
                .translate(pos.x + 0.42f, pos.y + 1.25f, pos.z + 0.52f)
                .scale(0.70f, 0.70f, 0.70f);
        shader.setUniform("mvp", new Matrix4f(pv).mul(eyeR));
        getItemMesh(Block.CRYSTAL_QUARTZ).render();

        // Restore normal lighting for subsequent render passes
        shader.setUniform("emissiveMode", 0);
        shader.setUniform("emissiveTint", new Vector3f(1.0f, 1.0f, 1.0f));
    }
}
