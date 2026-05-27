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
    public static float skimHeightTarget = 4.5f;   // base hover height above terrain (dynamic; raised for better feel)
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
    public static float smashMinHeight      = 2.0f;   // blocks fallen before smash triggers
    public static float smashTriggerVelocity = -3.0f; // must be falling at least this fast
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

    // ── ABILITIES ─────────────────────────────────────────────────────────────
    // These only activate in survival mode (!debugMode). None conflict with
    // existing keys: Q/E/G/Z are unused everywhere else.

    // Dash (tap Q) — instant horizontal burst, wall-stopped, no vertical override
    public static float dashSpeed        = 55f;   // blocks/sec during dash
    public static float dashDuration     = 0.26f; // seconds per dash
    public static float dashCooldown     = 1.4f;  // seconds
    public static int   dashGhostCount   = 10;    // ghost trail positions kept

    // Cannonball (hold G to charge, release to fire)
    public static float cannonMinPower   = 40f;   // launch speed at zero charge
    public static float cannonMaxPower   = 140f;  // launch speed at full charge (~2x old)
    public static float cannonMaxCharge  = 2.5f;  // seconds to reach full power
    public static float cannonHorizDrag  = 0.998f; // slightly less drag → longer range
    public static float cannonCooldown   = 3.0f;
    public static int   cannonArcPoints  = 30;    // more dots = longer visible arc

    // State Rewind (hold Z to rewind 5 s of your own history)
    // World state is NOT rewound — only player position/velocity/camera.
    public static float rewindBufferSecs = 5.0f;
    public static float rewindSnapshotHz = 20f;   // snapshots/s recorded
    public static float rewindSpeed      = 3.0f;  // playback is 3× real-time
    public static float rewindCooldown   = 5.0f;

    // Blink (tap E) — teleport to crosshair target up to blinkRange blocks away
    public static float blinkRange       = 22f;   // blocks
    public static float blinkCooldown    = 2.0f;
    public static float blinkFlashDecay  = 0.30f; // seconds for white flash to fade

    // ── MELEE ATTACK (Runic Cleave — F key) ──────────────────────────────────
    // Windup: camera lifts, FOV tightens, gold vignette builds (0.14 s)
    // Strike: instant arc shatter, camera bucks, white flash, screen shake
    // Recoil: pitch + FOV decay back to neutral (0.22 s)
    public static float meleeWindupDuration  = 0.14f;  // seconds
    public static float meleeRecoilDuration  = 0.22f;  // seconds
    public static float meleeCooldown        = 0.80f;  // seconds between strikes
    public static float meleeShakeStrength   = 0.13f;  // shake timer length (s)
    public static boolean melee3DAiming = true;

    // ── RANGED ATTACK (Void Shard — C key) ───────────────────────────────────
    // Hold C to charge, release fires a straight crystal bolt (no gravity).
    // Impact triggers sphere explosion + crystal debris burst.
    public static float voidShardMaxCharge    = 1.2f;   // seconds to full charge
    public static float voidShardMinSpeed     = 45f;    // blocks/s at zero charge
    public static float voidShardMaxSpeed     = 82f;    // blocks/s at full charge
    public static float voidShardLifetime     = 4.0f;   // seconds before despawn
    public static float voidShardMinRadius    = 1.5f;   // explosion radius (blocks) at 0 charge
    public static float voidShardMaxRadius    = 3.2f;   // explosion radius at full charge
    public static float voidShardCooldown     = 1.6f;   // seconds
    public static float voidShardShakeStrength = 0.18f; // base shake factor (scaled by chargeF)
    // ── SNIPING UPDATE ────────────────────────────────────────────────────────
    public static float standAimDot   = 0.993f; // cos(~6°) — how close crosshair must be to stand circle
    public static float standAimRange = 12f;   // max distance to aim-redirect (blocks)

    // ── MANHATTAN TRANSFER (Stand / Drone — X key) ────────────────────────────
    // X          : deploy / recall stand
    // TAB        : enter / exit stand perspective
    // WASD+Space+Shift in stand perspective: fly the drone
    // LMB in stand perspective: fire redirect shot (dual LOS required)
    public static float standMaxHealth       = 50f;   // HP before stand is destroyed
    public static float standSpeed           = 22f;   // blocks/sec drone movement
    public static float standShotSpeed       = 90f;   // blocks/sec redirect bolt
    public static float standShotRange       = 80f;   // max bolt travel distance (blocks)
    public static float standDeployHeight    = 8f;    // blocks above player on deploy
    public static float standRecallCooldown  = 1.5f;  // seconds after recall before re-deploy
    public static float standDestroyCooldown = 5.0f;  // seconds after destruction before re-deploy
    public static float standBlockedFlashTime = 0.5f; // HUD flash duration when LOS blocked
    public static float standShotRadius      = 2.5f;  // explosion radius on bolt impact (blocks)
    public static float standHoverBob        = 0.1f;  // amplitude of idle bob (blocks)
    public static float standHoverBobSpeed   = 0.5f;  // Hz of idle bob

    // ── MINATO'S SEAL (H / B / N keys) ───────────────────────────────────────
    // H : fire seal projectile — embeds on first solid surface hit
    // B : teleport instantly to targeted seal (closest to crosshair)
    // N : reclaim targeted seal (retrieve it back)
    public static int   sealMaxCount          = 5;     // max simultaneously placed seals
    public static float sealProjectileSpeed   = 60f;   // blocks/sec
    public static float sealProjectileLifetime = 3.0f; // seconds before despawn if no hit
    public static float sealPlaceCooldown     = 0.4f;  // seconds between placements
    public static float sealTeleportCooldown  = 0.0f;  // seconds between teleports
    public static float sealTeleportFlash     = 0.25f; // seconds for teleport white flash
    public static float sealPulseSpeed        = 4.5f;  // Hz for idle pulse glow
    public static float sealThroughWallAlpha  = 0.7f; // opacity of through-wall ghost
    public static float sealTargetedScale     = 3.5f;  // size multiplier for targeted seal

    /**
     * Flying Raijin look-direction mode after teleporting to a seal.
     *   0 — keep current look direction (unchanged, default)
     *   1 — face the direction of travel (look toward where you came from, i.e. away from origin)
     *   2 — look toward the nearest remaining seal after landing
     */
    public static int sealLookMode = 1;

    // ── ENEMIES ───────────────────────────────────────────────────────────────
    // GRUNT — standard pursuer.  Medium speed, medium health.
    public static float gruntSpeed        = 3.5f;
    public static float gruntHealth       = 50f;
    public static float gruntDamagePerSec = 8f;
    public static float gruntAggroRange   = 24f;
    public static float gruntAttackRange  = 1.5f;

    // BRUTE — slow tank.  High health, heavy damage.
    public static float bruteSpeed        = 1.8f;
    public static float bruteHealth       = 200f;
    public static float bruteDamagePerSec = 20f;
    public static float bruteAggroRange   = 16f;
    public static float bruteAttackRange  = 2.0f;

    // STALKER — fast glass cannon.  Long aggro, fragile.
    public static float stalkerSpeed        = 6.5f;
    public static float stalkerHealth       = 25f;
    public static float stalkerDamagePerSec = 6f;
    public static float stalkerAggroRange   = 36f;
    public static float stalkerAttackRange  = 1.5f;

    // ── ENEMY WAVE SPAWNING ───────────────────────────────────────────────────
    /** Seconds between automatic wave spawns. */
    public static float spawnWaveInterval = 30f;
    /** Minimum horizontal spawn distance from player (blocks). */
    public static float spawnMinDist      = 22f;
    /** Maximum horizontal spawn distance from player (blocks). */
    public static float spawnMaxDist      = 38f;
    /** Maximum total live enemies before spawning is suppressed. */
    public static int   spawnMaxEnemies   = 24;
    /** Base enemies per wave (scales with wave number). */
    public static int   spawnWaveBase     = 3;

    public static float pillarRiseSpeed = 16.0f;   // Blocks/sec upward velocity
    public static float pillarMaxHeight = 40.0f;   // Max altitude gained per cast
    public static float pillarTaper     = 0.15f;   // REDUCED: How much it widens per block downwards (more subtle)
    public static float pillarCooldown  = 2.5f;    // Seconds before it can be cast again

}