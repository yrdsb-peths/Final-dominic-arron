package com.leaf.game.core;

public class GameConfig {
    public static long  seed           = 1L;
    public static int   renderDistance = 6;

    // ── HEIGHT MAPPING ────────────────────────────────────────────────────────
    public static int heightBase  = 200;
    public static int heightRange = 100;
    public static int seaLevel    = 220;

    // ── BIOME THRESHOLDS ─────────────────────────────────────────────────────
    public static int   beachMaxAltitude     = 2;
    public static int   snowAltitude         = 310;
    public static float coldTempThreshold    = -0.25f;
    public static int   mountainSnowAltitude = 120;

    public static float contFreq    = 0.001f;
    public static int   contOctaves = 2;
    public static float contPersist = 0.35f;

    public static float erosFreq         = 0.0015f;
    public static int   erosOctaves      = 3;
    public static float erosPersist      = 0.45f;
    public static float erosWarpFreq     = 0.001f;
    public static float erosWarpStrength = 180f;

    public static float pvFreq    = 0.008f;
    public static int   pvOctaves = 5;
    public static float pvPersist = 0.50f;

    public static float tempFreq    = 0.0008f;
    public static int   tempOctaves = 2;
    public static float tempPersist = 0.3f;

    public static float humFreq    = 0.002f;
    public static int   humOctaves = 3;
    public static float humPersist = 0.4f;

    public static float riverFreq        = 0.003f;
    public static int   riverOctaves     = 2;
    public static float riverPersist     = 0.50f;
    public static float riverThreshold   = 0.050f;
    public static float riverCarveDepth  = 0.045f;
    public static float riverFloorMargin = 0.025f;

    public static float density3DFreq             = 0.05f;
    public static float density3DVerticalCompress = 0.35f;
    public static int   density3DOctaves          = 3;
    public static float density3DPersist          = 0.5f;
    public static float density3DAmplitude        = 8f;
    public static float densityVerticalScale      = 0.12f;

    public static float cheeseFreq         = 0.040f;
    public static float cheeseVertCompress = 0.50f;
    public static int   cheeseOctaves      = 3;
    public static float cheesePersist      = 0.50f;
    public static float cheeseThreshold    = 0.58f;
    public static float cheeseDepthBoost   = 0.12f;

    public static float spagFreq         = 0.022f;
    public static float spagVertCompress = 0.65f;
    public static int   spagOctaves      = 2;
    public static float spagPersist      = 0.50f;
    public static float spagThreshold    = 0.68f;

    public static int caveSurfaceBuffer = 6;
    public static int caveBedrockFloor  = 4;

    public static float sunDirX         = 0.6f;
    public static float sunDirY         = 1.0f;
    public static float sunDirZ         = 0.4f;
    public static float sunStrength     = 0.75f;
    public static float ambientStrength = 0.25f;

    public static float mouseSensitivity = 0.001f;
    public static float fov              = 70.0f;

    public static float GRAVITY      = 35.0f;
    public static float JUMP_FORCE   = 10.0f;
    public static float WALK_SPEED   = 5.0f;
    public static float SPRINT_SPEED = 8.5f;
    public static float FLY_SPEED    = 100.0f;

    // ── FLIGHT ENGINE ─────────────────────────────────────────────────────────
    // SKIM: low-altitude terrain-hugging burst mode
    public static float skimHeightTarget = 3.0f;   // base hover height above terrain (dynamic; was 5.0)
    public static float skimSpeed        = 32.0f;  // horizontal blocks/sec

    // SOAR: full-3D free flight
    public static float soarSpeed   = 40.0f;        // blocks/sec
    public static float soarAccel   = 7.0f;         // velocity lerp speed
    public static float soarGravity = 4.0f;         // passive downward pull (m/s²) — aerodynamic feel
    public static float soarAirDrag = 0.88f;        // per-frame drag base (applied when no keys held)

    // GRAPPLE: hook-and-swing
    public static float grappleRange        = 50.0f;  // max cast distance (blocks)
    public static float grappleSwingStrength = 1.25f; // gravity multiplier in swing
    public static float grappleCooldown     = 0.15f;  // Reduced from 1.2f for rapid AOT-style response

    // Camera effects (apply to all flight modes)
    public static float flightFovMax       = 25.0f;  // max FOV boost above base fov
    public static float rollMaxAngle       = 18.0f;  // degrees of camera roll for GRAPPLE/SKIM
    public static float soarRollMaxAngle   = 8.0f;   // degrees of camera roll for SOAR (softer)
    public static float cameraLerpSpeed    = 8.0f;   // FOV / pitch effect lerp rate
    public static float rollLerpSpeed      = 2.5f;   // roll lerp rate (reduced from 6.0 — was too fast/disorienting)

    // ── GROUND SMASH ─────────────────────────────────────────────────────────
    public static float smashMinHeight      = 15.0f;   // blocks fallen before smash triggers
    public static float smashTriggerVelocity = -8.0f; // must be falling at least this fast
    public static float smashDescentSpeed   = 65.0f;   // locked downward speed during smash
    public static int   smashCraterRadius   = 4;       // sphere radius in blocks
    public static float smashShakeDuration  = 0.8f;    // seconds of screen shake
    public static float smashShakeAmplitude = 0.28f;   // max camera offset magnitude
    public static float smashShakeFrequency = 20.0f;   // oscillations per second

    // ── TIME DILATION ─────────────────────────────────────────────────────────
    // Hold R = slow motion, hold Y = fast time.
    // (T is reserved for chat; R was chosen to avoid conflict.)
    public static float timeSlowScale       = 0.15f;  // scale while R held
    public static float timeFastScale       = 4.0f;   // scale while Y held
    // Linear ramp rate: covers full slow range (1.0→0.15 = 0.85) in ~0.3 s
    public static float timeTransitionSpeed = 2.83f;
}