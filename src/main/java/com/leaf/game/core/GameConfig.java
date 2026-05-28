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
    public static float smashMinHeight        = 2.0f;   // blocks fallen before smash triggers
    public static float smashTriggerVelocity  = -3.0f;  // must be falling at least this fast
    public static float smashDescentSpeed     = 65.0f;  // initial speed at smash start (legacy, kept for reference)
    public static float smashDescentAccel     = 200.0f; // acceleration during smash (blocks/s²)
    public static float smashDescentMaxSpeed  = 130.0f; // terminal smash speed (blocks/s)
    public static int   smashCraterRadius     = 4;      // sphere radius in blocks
    public static float smashShakeDuration  = 0.8f;    // seconds of screen shake
    public static float smashShakeAmplitude = 0.28f;   // max camera offset magnitude
    public static float smashShakeFrequency = 20.0f;   // oscillations per second

    // ── SMASH SPLASH / KNOCKBACK ──────────────────────────────────────────────
    /** Blast radius = craterRadius × this multiplier (enemies within are hit). */
    public static float smashSplashRadiusMult    = 2.5f;
    /** Max damage dealt to an enemy at the centre of the blast (linear falloff). */
    public static float smashSplashDamage        = 60f;
    /** Peak horizontal launch speed (blocks/sec) applied to closest enemies. */
    public static float smashKnockbackStrength   = 28f;
    /**
     * Per-second multiplier for knockback velocity decay.
     * A value of 0.05 means the velocity drops to 5% of its initial value each second
     * (pow(0.05, dt) ≈ exponential decay with half-life ~0.23 s).
     */
    public static float knockbackDecay           = 0.05f;

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
    public static float meleeDamage          = 35f;    // HP per hit (cone arc)
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
    public static float voidShardMinDamage    = 25f;    // HP at zero charge
    public static float voidShardMaxDamage    = 80f;    // HP at full charge
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
    // Three enemy types: GOLEM, THROWER, PREDATOR (see below for their stats).

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

    // ── QUAGMIRE (M key) ─────────────────────────────────────────────────────
    // A wave of mud erupts from slightly in front of the player and races
    // toward the targeted enemy, trapping it on contact.
    public static float quagmireRange        = 50f;  // max target range (blocks)
    public static float quagmireTrapDuration = 4.0f; // seconds the enemy is frozen
    public static float quagmireSpreadSpeed  = 14f;  // blocks/sec the mud wave travels
    public static float quagmireCooldown     = 8.0f;
    /** Radius (blocks) of the irregular mud pool stamped when the wave arrives. */
    public static int   quagmirePoolRadius   = 3;

    // ── STONE CANON (I key) ───────────────────────────────────────────────────
    // Hold I to charge — nearby stone blocks are consumed and shaped into a
    // projectile.  Release to fire.  Player position is locked while charging.
    public static float stoneCanonScanRadius  = 7f;    // radius to search for stone
    public static float stoneCanonMaxCharge   = 3.0f;  // seconds to full charge
    public static float stoneCanonConsumeRate = 0.55f; // seconds between block consumes
    public static float stoneCanonMinSpeed    = 35f;   // projectile speed at 0 charge
    public static float stoneCanonMaxSpeed    = 55f;   // projectile speed at full charge
    public static float stoneCanonMinRadius   = 3.0f;  // blast radius at 0 charge
    public static float stoneCanonMaxRadius   = 8.0f;  // blast radius at full charge
    public static float stoneCanonMinDamage   = 80f;
    public static float stoneCanonMaxDamage   = 300f;
    public static float stoneCanonMinScale    = 0.35f; // projectile visual scale
    public static float stoneCanonMaxScale    = 1.4f;
    public static float stoneCanonCooldown    = 4.0f;
    public static float stoneCanonLifetime    = 6.0f;  // projectile max travel time

    // ── TODO'S TECHNIQUE (J key) ──────────────────────────────────────────────
    // Tap J to instantly swap positions with the nearest visible enemy.
    public static float todoRange    = 80f;  // max swap range (blocks)
    public static float todoCooldown = 4.0f; // seconds between swaps

    // ── PAPER FIGURINE SUBSTITUTE (V hold) ────────────────────────────────────
    // Hold V while cooldown is ready to prime a paper-dummy guardian.
    // When damage is received while primed: negate it, teleport player back,
    // leave a paper dummy at the old position that detonates after a short delay.
    public static float substituteBackDist     = 5.5f;  // how far back the player teleports (blocks)
    public static float substituteDummyLifetime = 1.0f; // seconds before dummy explodes
    public static float substituteBlastRadius  = 3.5f;  // dummy explosion radius (blocks)
    public static float substituteBlastDamage  = 80f;   // dummy explosion damage (hp)
    public static float substituteCooldown     = 10.0f; // seconds between activations

    // ── HEALING (Hold L) ──────────────────────────────────────────────────────
    public static float healPerSecond   = 15.0f;  // HP restored per second while holding
    public static float healCooldown    = 4.0f;   // Seconds before you can heal again after stopping
    public static float healMaxDuration = 3.5f;   // Max continuous seconds before forced cooldown

    // ── KAMUI (Z toggle) ─────────────────────────────────────────────────────
    /** Seconds the player can stay phased before being forced out. */
    public static float kamuiMaxDuration      = 60.0f;
    /** Cooldown after exiting Kamui (whether voluntary or by timer). */
    public static float kamuiCooldown         = 14.0f;
    /** Seconds to hold look at a target to absorb it into the Kamui dimension. */
    public static float kamuiAbsorptionTime   = 2.0f;
    /** How long the player stays exposed (vulnerable, Kamui off) after attacking. */
    public static float kamuiExposureDuration = 1.5f;

    // ── LIGHTNING (U key) ────────────────────────────────────────────────────
    /** Seconds to reach full charge. */
    public static float lightningMaxCharge       = 2.0f;
    /** Aimed-strike damage at zero charge. */
    public static float lightningBaseDamage      = 75f;
    /** Aimed-strike damage at full charge (scales linearly between these). */
    public static float lightningMaxDamage       = 280f;
    /** Maximum range for aimed targeting (blocks). */
    public static float lightningRange           = 100f;
    /** Cooldown after a charged single-target strike. */
    public static float lightningCooldown        = 4.5f;
    /** AOE-burst damage per enemy (double-tap U). */
    public static float lightningAoeDamage       = 60f;
    /** AOE-burst radius (blocks). */
    public static float lightningAoeRadius       = 16f;
    /** Cooldown after an AOE burst (longer — it's powerful). */
    public static float lightningAoeCooldown     = 10.0f;
    /** Maximum seconds between two U presses to count as a double-tap. */
    public static float lightningDoubleTapWindow = 0.38f;
    /**
     * When the primary target is standing in water, electrocution chains to all
     * other enemies also standing in water within this horizontal radius (blocks).
     */
    public static float lightningWaterChainRadius = 22f;
    /** Chain-strike damage (fixed, independent of primary charge level). */
    public static float lightningWaterChainDamage = 90f;
    /** Seconds the lightning bolt visual remains on screen. */
    public static float lightningBoltLife         = 0.6f;

    // ── ENEMY: ZOMBIE ────────────────────────────────────────────────────────
    // Slow, shambling melee chaser.  No ranged attack.
    public static float zombieHealth         = 80f;
    public static float zombieSpeed          = 2.2f;
    /** Damage per second dealt while in contact with the player. */
    public static float zombieDamagePerSec   = 5f;   // ~7 dmg per bite — hurts but survivable
    public static float zombieAggroRange     = 30f;
    public static float zombieAttackRange    = 1.8f;
    public static float zombieAttackInterval = 1.4f;

    // ── ENEMY: GOLEM ─────────────────────────────────────────────────────────
    public static float golemHealth          = 420f;
    public static float golemSpeed           = 1.6f;
    /** Melee contact damage per second (close-in body slam). */
    public static float golemDamagePerSec    = 6f;   // 6×1.8 = ~11 dmg per hit
    public static float golemAggroRange      = 45f;
    public static float golemAttackRange     = 2.8f;
    public static float golemAttackInterval  = 1.8f;
    /** Radius of the shockwave slam attack. */
    public static float golemSlamRadius      = 5.0f;
    /** Damage dealt by the shockwave slam. */
    public static float golemSlamDamage      = 22f;  // still scary but not one-shot
    /** Cooldown between slam uses. */
    public static float golemSlamCooldown    = 5.0f;
    /** Distance at which the golem triggers a slam. */
    public static float golemSlamRange       = 3.5f;
    /** Distance at which the golem tries to throw a boulder. */
    public static float golemThrowRange      = 18.0f;
    public static float golemThrowCooldown   = 7.0f;
    public static float golemThrowDamage     = 14f;
    /** Horizontal launch speed of boulder projectiles (blocks/s). */
    public static float golemThrowSpeed      = 18f;
    /** Fraction of smash-splash damage golems absorb (1.0 = full, 0.5 = half). */
    public static float golemSmashResist     = 0.35f;

    // ── ENEMY: THROWER ───────────────────────────────────────────────────────
    public static float throwerHealth         = 95f;
    public static float throwerSpeed          = 4.8f;
    public static float throwerDamagePerSec   = 3f;   // weak melee if cornered
    public static float throwerAggroRange     = 40f;
    public static float throwerAttackRange    = 2.0f;
    public static float throwerAttackInterval = 2.5f;
    public static float throwerThrowCooldown  = 4.5f;
    public static float throwerThrowDamage    = 10f;
    public static float throwerThrowSpeed     = 22f;
    /** Thrower tries to maintain this distance from the player. */
    public static float throwerPreferredDist  = 12.0f;
    /** Max retreat speed when the player is too close. */
    public static float throwerRetreatSpeed   = 5.5f;

    // ── ENEMY: PREDATOR ──────────────────────────────────────────────────────
    public static float predatorHealth         = 70f;
    public static float predatorSpeed          = 9.5f;
    public static float predatorDamagePerSec   = 18f;
    public static float predatorAggroRange     = 35f;
    public static float predatorAttackRange    = 1.8f;
    public static float predatorAttackInterval = 0.75f;
    /** Distance at which the predator initiates a pounce. */
    public static float predatorPounceDist     = 7.0f;
    /** Horizontal burst speed of the pounce (blocks/s). */
    public static float predatorPounceSpeed    = 22f;
    /** Vertical leap during a pounce (blocks/s upward). */
    public static float predatorPounceLeap     = 9f;
    /** Extra damage dealt on landing a pounce hit. */
    public static float predatorPounceDamage   = 45f;
    /** Cooldown between pounces. */
    public static float predatorPounceCooldown = 6.0f;

    // ── ENEMY PROJECTILE ─────────────────────────────────────────────────────
    /** Gravity applied to arc-trajectory projectiles (blocks/s²). */
    public static float projectileGravity  = 18f;
    /** Max seconds an enemy projectile travels before despawning. */
    public static float projectileLifetime = 6.0f;

    // ── GRAB SLAM (O key) ─────────────────────────────────────────────────────
    // Tap O to grab the nearest enemy in your crosshair within grabRange blocks.
    //   Normal tap  → Wall Throw: launched in look direction; hits solid → crater.
    //   Shift + O   → Ground Slam: enemy lifted then smashed straight down.
    /** Max range to grab an enemy (blocks). */
    public static float grabRange            = 4.5f;
    /** Throw speed for wall-slam (blocks/sec). */
    public static float grabThrowSpeed       = 50f;
    /** Damage applied when thrown enemy hits a wall. */
    public static float grabWallDamage       = 90f;
    /** How high the enemy is lifted before a ground slam (blocks). */
    public static float grabGroundLiftHeight = 3.0f;
    /** Seconds it takes to complete the lift before slamming. */
    public static float grabGroundLiftTime   = 0.22f;
    /** Downward speed of the slam (blocks/sec). */
    public static float grabGroundSlamSpeed  = 80f;
    /** Damage applied when slammed into the floor. */
    public static float grabGroundDamage     = 115f;
    /** Crater radius (blocks) on any grab impact. */
    public static int   grabCraterRadius     = 3;
    /** Seconds between grab uses. */
    public static float grabCooldown         = 2.5f;
    /** Camera-shake duration on impact. */
    public static float grabShakeDuration    = 0.55f;
    /** Camera-shake amplitude on impact. */
    public static float grabShakeAmplitude   = 0.22f;

    // ── FAST ATTACK / KNIFE COMBO (;  key) ───────────────────────────────────
    // Three rapid knife slashes — deals damage but destroys no terrain.
    /** Damage per hit (3 hits per combo). */
    public static float knifeDamage        = 18f;
    /** Reach of each knife slash (blocks). */
    public static float knifeRange         = 2.8f;
    /** Seconds the combo window stays open between presses. */
    public static float knifeComboWindow   = 0.50f;
    /** Seconds of camera-punch animation per hit. */
    public static float knifeHitDuration   = 0.13f;
    /** Cooldown after completing a full 3-hit combo. */
    public static float knifeCooldown      = 0.85f;

    // ── MANA SYSTEM ───────────────────────────────────────────────────────────
    // Mana is a shared resource that limits how often abilities can be used.
    // All values are tunable; regeneration is passive and always active.
    /** Mana regenerated per second (passive). */
    public static float manaRegenRate        = 3f;

    // Lightning costs
    /** Mana cost for a zero-charge single lightning strike. */
    public static float manaLightningBase    = 12f;
    /** Mana cost for a full-charge single lightning strike (interpolated). */
    public static float manaLightningMax     = 35f;
    /** Mana cost for the AOE lightning burst (double-tap). */
    public static float manaLightningAoe     = 28f;

    // Kamui costs
    /** Mana drained per second while Kamui is active. */
    public static float manaKamuiDrain       = 1.5f;
    /** Mana cost on a successful Kamui absorption (kill enemy / erase blocks). */
    public static float manaKamuiAbsorption  = 18f;

    // Movement-ability costs
    /** Mana cost per dash use. */
    public static float manaDash             = 6f;
    /** Mana cost per blink use. */
    public static float manaBlink            = 10f;
    /** Mana cost to fire the cannonball. */
    public static float manaCannonball       = 18f;
    /** Mana cost per ground-smash activation. */
    public static float manaSmash            = 8f;

    // Attack costs
    /** Mana cost per Runic Cleave (F key). */
    public static float manaCleave           = 5f;
    /** Mana cost per Void Shard bolt (C key). */
    public static float manaVoidShard        = 7f;
    /** Mana cost when the earth pillar activates (one-time). */
    public static float manaPillar           = 14f;
    /** Mana drained per second while the pillar is actively rising (continuous). */
    public static float manaPillarPerSec     = 4f;

    // Utility costs
    /** Mana cost for the Todo position-swap (one-time). */
    public static float manaTodoSwap         = 10f;
    /** Mana cost per Quagmire mud wave (one-time). */
    public static float manaQuagmire         = 12f;
    /** Mana drained per second while Stone Canon is charging (continuous). */
    public static float manaStoneCanonBase   = 4f;
    /** Mana consumed when Stone Canon fires, scaled by charge (0..1 × this). */
    public static float manaStoneCanonMax    = 14f;
    /** Mana cost per grab (wall throw or ground slam). */
    public static float manaGrab             = 8f;
    /** Mana cost per 3-hit knife combo (zero = free, feels snappy). */
    public static float manaKnife            = 0f;

}