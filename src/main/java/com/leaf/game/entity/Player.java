package com.leaf.game.entity;

import com.leaf.game.util.Camera;
import com.leaf.game.util.RaycastResult;
import com.leaf.game.core.GameConfig;
import com.leaf.game.core.Progression;
import com.leaf.game.world.Block;
import com.leaf.game.world.World;
import com.leaf.game.world.Chunk;
import com.leaf.game.core.AudioManager;
import org.joml.Vector3f;

import static org.lwjgl.glfw.GLFW.*;

public class Player {

    public boolean debugMode = false;
    public Vector3f position;

    /** Ability unlock state (persists across deaths). Checked at every ability trigger. */
    public final Progression progression = new Progression();
    /** Shorthand gate used at ability triggers. */
    public boolean can(Progression.Ability a) { return progression.isUnlocked(a); }
    // ── NEW TESTING MOVEMENT CONTROLLER ───────────────────────────────────────
    public boolean useTestMovement = false;
    public final TestMovementController testMovement = new TestMovementController(this);
    public float cameraYOffset = 0f; // Smoothes out instant coordinate teleports (Stairs/Step-Up)
    private boolean wasInWaterTest = false; // Manages seamless land/water handoffs
    public Block heldBlock = Block.AIR; // Synchronized held block from Window.java
    // ── CENTRAL GRAPPLE HOOK TOOL STATE ───────────────────────────────────────
    public boolean isGrappleHooked = false;
    public Vector3f grappleHookPoint = new Vector3f();
    public float grappleHookTime = 0f;
    private boolean lastRMBGrapple = false;
    // ── HEALTH & FALL DAMAGE ──────────────────────────────────────────────────
    public float   health      = GameConfig.playerMaxHealth;
    public float   maxHealth   = GameConfig.playerMaxHealth;
    public float   highestY    = -1000f;
    /** Set true the frame the player falls below y=0 (the void). Window kills them. */
    public boolean fellIntoVoid = false;

    // ── MANA ──────────────────────────────────────────────────────────────────
    // Consumed by abilities; regenerates passively over time.
    // All costs are defined in GameConfig so designers can tweak them.
    public float mana    = 100f;
    public float maxMana = 100f;
    public float manaFlashTimer = 0f;  // pulses mana bar red when set; ticks down each frame

    private float   velocityY  = 0.0f;        // velocity ALONG the up axis (not always world-Y)
    private boolean onGround   = false;
    private boolean wasInWater     = false;
    private boolean cameraSubmerged = false;  // true when the camera (eye level) is inside water

    // ── GRAVITY DIRECTION (6-axis) ────────────────────────────────────────────
    // Gravity can point along any world axis. gravAxis ∈ {0:X,1:Y,2:Z}; gravSign is
    // the sign of "down" on that axis (default down = −Y). upDir = −gravity, cached.
    // Flipping gravity lets the player stand and walk on walls / the ceiling.
    public int      gravAxis = 1;
    public float    gravSign = -1f;
    public final Vector3f upDir = new Vector3f(0f, 1f, 0f);
    private boolean lastGravityKey = false;
    /** Set by death/respawn code so gravity snaps back to normal on the next update. */
    public boolean  pendingGravityReset = false;

    private static final float WIDTH      = 0.6f;
    private static final float HEIGHT     = 1.8f;
    private static final float EYE_HEIGHT = 1.6f;

    // ── MOVEMENT STATE ────────────────────────────────────────────────────────
    private boolean lastW     = false;
    private double  lastWTime = 0;
    private boolean isSprinting = false;

    private boolean lastSpace    = false;
    private double  lastSpaceTime = 0;
    private int     airJumpsUsed = 0;   // mid-air jumps consumed since last touching ground

    /** Authoritative smoothed horizontal velocity (blocks/sec). Single source of truth —
     *  never reconstructed from position deltas, so it can't fling the player. */
    private final Vector3f horizVel = new Vector3f();

    // ── FLIGHT ENGINE ─────────────────────────────────────────────────────────
    // Public so Window.java can read roll/fov each frame.
    public final FlightController flightController = new FlightController(this);

    // Track last debugMode state to detect the transition and pick up launch vel
    private boolean wasFlying = false;

    // ── ABILITIES ─────────────────────────────────────────────────────────────
    // Q=Dash  G=Cannonball  Z=Rewind  E=Blink
    // All abilities disabled while debugMode (flight) is active.
    public final AbilityController abilities = new AbilityController(this);

    // ── ATTACKS ───────────────────────────────────────────────────────────────
    // F=Runic Cleave (melee)   C=Void Shard (ranged bolt)
    // All attacks disabled while debugMode (flight) is active.
    public final AttackController attacks = new AttackController(this);

    // ── MANHATTAN TRANSFER (Stand / Drone — X / TAB / LMB) ───────────────────
    // Ticked BEFORE abilities so it can take full positional control first.
    public final StandController stand = new StandController(this);

    // ── MINATO'S SEAL (H / B / N) ────────────────────────────────────────────
    // Ticked after attacks; never takes full positional control.
    public final SealController seals = new SealController(this);

    // ── LIGHTNING (U key) ─────────────────────────────────────────────────────
    public final LightningController lightning = new LightningController(this);

    // ── GRAB SLAM (O key) ─────────────────────────────────────────────────────
    public final GrabController grab = new GrabController(this);

    // ── GROUND SMASH ─────────────────────────────────────────────────────────
    private boolean isSmashing = false;
    private boolean lastShift  = false;   // edge detector for smash trigger

    public int  smashImpactX = Integer.MIN_VALUE;
    public int  smashImpactY, smashImpactZ;
    public int  currentSmashRadius = GameConfig.smashCraterRadius; // Tracks the height-scaled radius

    // ─────────────────────────────────────────────────────────────────────────
    //  Constructor
    // ─────────────────────────────────────────────────────────────────────────

    public Player(float x, float y, float z) {
        position = new Vector3f(x, y, z);
        highestY = y;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Main update (called with time-scaled deltaTime from Window.java)
    // ─────────────────────────────────────────────────────────────────────────

    public void update(long window, Camera camera, World world, float deltaTime) {
        double now = glfwGetTime();

        // ── GRAVITY CHANGE (look + press key) ─────────────────────────────────
        // A pending reset (from death/respawn) snaps gravity back to normal first.
        if (pendingGravityReset) {
            pendingGravityReset = false;
            gravAxis = 1; gravSign = -1f; upDir.set(0f, 1f, 0f);
            velocityY = 0f; onGround = false;
            camera.snapGravityUp(upDir);
            highestY = upHeight();
        }
        // Always animate the view toward the current gravity orientation, and on a
        // fresh key press snap "down" to whatever axis the camera is looking along.
        camera.tickGravity(deltaTime);
        // Gravity flip is disabled in normal play — a stray '/' press mid-fight flips the
        // whole world upside-down, which reads as a bug to anyone who didn't ask for it.

        // ── Clear per-frame smash signal ──────────────────────────────────────
        smashImpactX = Integer.MIN_VALUE;

        // ── Landing dip spring-back (runs every frame; harmless when viewDip == 0) ─
        viewDip += (0f - viewDip) * Math.min(1f, GameConfig.landingDipRecover * deltaTime);

        // ── DOUBLE-TAP W → SPRINT ─────────────────────────────────────────────
        boolean currentW = glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS;
        if (currentW && !lastW) {
            if (now - lastWTime < 0.3) isSprinting = true;
            lastWTime = now;
        }
        if (!currentW) isSprinting = false;
        lastW = currentW;

        // ── DOUBLE-TAP SPACE → TOGGLE FLIGHT ─────────────────────────────────
        boolean currentSpace = glfwGetKey(window, GLFW_KEY_SPACE) == GLFW_PRESS;
        if (currentSpace && !lastSpace) {
            // Flight is a late unlock; allow exiting if already flying, but block entering until learned.
            // Never toggle in test-movement modes (flappy spams SPACE = accidental skim/soar).
            boolean flightAllowed = (debugMode || can(Progression.Ability.FLIGHT)) && !useTestMovement;
            boolean flightToggled = false;
            if (now - lastSpaceTime < 0.3 && !stand.isInStandPerspective() && flightAllowed) {
                debugMode = !debugMode;
                velocityY = 0f;
                isSmashing = false;
                flightToggled = true;
                if (!debugMode) {
                    flightController.onFlightDeactivated();
                    Vector3f lv = flightController.getLaunchVelocity();
                    velocityY = lv.y;
                    position.x += lv.x * deltaTime;
                    position.z += lv.z * deltaTime;
                }
            }
            // ── AIR-JUMP (double jump) ────────────────────────────────────────
            // A second hop in mid-air — the basis of agile, vertical fights. Doesn't
            // fire if this same press just toggled flight, and never in water/flight/
            // smash/drone. Resets when you touch the ground.
            if (!flightToggled && !debugMode && !useTestMovement && !onGround
                    && !wasInWater && !isSmashing
                    && !stand.isInStandPerspective()
                    && airJumpsUsed < GameConfig.airJumpCount) {
                velocityY = GameConfig.JUMP_FORCE * GameConfig.airJumpFactor;
                airJumpsUsed++;
                AudioManager.play("swoosh", 0.55f);
            }
            lastSpaceTime = now;
        }
        lastSpace = currentSpace;

        boolean shiftHeld = glfwGetKey(window, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS;

        float speed = debugMode ? GameConfig.FLY_SPEED
                : isSprinting ? GameConfig.SPRINT_SPEED
                  : GameConfig.WALK_SPEED;

        boolean isCameraInWater = isBlockLiquid(world,
                camera.position.x, camera.position.y, camera.position.z);
        cameraSubmerged = isCameraInWater;   // publish for audio reverb each update
        Vector3f forward = isCameraInWater ? camera.getLookDirection() : camera.getForward();
        Vector3f right   = camera.getRight();

        // ── FLIGHT MODE — delegate to FlightController ───────────────────────
        if (debugMode) {
            horizVel.zero();   // no stale ground momentum carried out of flight
            flightController.update(window, camera, world, deltaTime);
            wasFlying = true;
            syncEye(camera);
            highestY = upHeight();
            return;
        }

        if (wasFlying) {
            wasFlying = false;
        }

        flightController.decayEffects(deltaTime);

        // ── STAND TICK (Manhattan Transfer) ───────────────────────────────────
        // Must run before abilities so that drone-perspective takes priority.
        // Returns true when the player is piloting the drone — body is frozen.
        if (stand.tick(window, camera, world, deltaTime)) {
            horizVel.zero();   // body is frozen while piloting the drone
            attacks.tick(window, stand.standCamera, world, deltaTime);
            // Gravity still applies to the player body while piloting the drone
            boolean inWaterD = isBlockLiquid(world, position.x + upDir.x * 0.1f,
                    position.y + upDir.y * 0.1f,
                    position.z + upDir.z * 0.1f);
            float hD = upHeight();
            if (inWaterD && !wasInWater) highestY = hD;
            wasInWater = inWaterD;
            if (inWaterD) {
                velocityY *= (float) Math.pow(0.85f, deltaTime * 60f);
                velocityY  = Math.max(-4.0f, Math.min(4.0f, velocityY));
            } else {
                velocityY -= GameConfig.GRAVITY * deltaTime;
            }
            float dyD = velocityY * deltaTime;
            if (dyD != 0f) {
                onGround = false;
                position.x += upDir.x * dyD; position.y += upDir.y * dyD; position.z += upDir.z * dyD;
                resolveAxis(world, gravAxis, upDir.get(gravAxis) * dyD);  // sets onGround / zeroes velocityY
            }
            hD = upHeight();
            if (onGround || hD > highestY) highestY = hD;
            if (position.y < 0f) { fellIntoVoid = true; }   // void death — Window handles it
            return;
        }

        // ── ABILITY TICK ───────────────────────────────────────────────────────
        // Runs before physics. Returns true when ability has full positional
        // control (Rewind) — caller skips the physics block entirely.
        if (abilities.tick(window, camera, world, deltaTime)) {
            horizVel.zero();   // ability took full positional control (e.g. Rewind)
            syncEye(camera);
            return;
        }

        // ── ATTACK TICK ────────────────────────────────────────────────────────
        // Attacks always tick — AbilityController auto-exits Kamui when an attack key
        // is pressed and re-enters once the attack completes.
        attacks.tick(window, camera, world, deltaTime);

        // ── GRAB SLAM TICK (O key) ─────────────────────────────────────────────
        grab.tick(window, camera, world, deltaTime);

        // ── SEAL TICK (Minato's Seal) ──────────────────────────────────────────
        seals.tick(window, camera, world, deltaTime);

        // ── MANA REGENERATION ──────────────────────────────────────────────────
        // No regen while in Kamui — the alternate dimension has no ambient mana.
        if (!abilities.isKamui) {
            mana = Math.min(maxMana, mana + GameConfig.manaRegenRate * deltaTime);
        }

        // ── LIGHTNING TICK ─────────────────────────────────────────────────────
        lightning.tick(window, camera, world, deltaTime, (float) glfwGetTime());

        // ── LAVA TICK DAMAGE (Applies normally in all modes) ──
        boolean inLava = isBlockLiquid(world, position.x, position.y + 0.1f, position.z)
                && world.getBlock((int)Math.floor(position.x), (int)Math.floor(position.y), (int)Math.floor(position.z)) == Block.LAVA;

        // Safe, clean lava damage that applies normally in all modes
        if (inLava && !abilities.isKamui) {
            health = Math.max(0f, health - 120f * deltaTime); // Rapidly burns down health
        }
        // ── PHYSICAL MOVEMENT & COLLISION BRANCH ───────────────────────────────
        boolean inWater = isBlockLiquid(world, position.x + upDir.x * 0.1f,
                position.y + upDir.y * 0.1f,
                position.z + upDir.z * 0.1f);

        // ── UNIFIED GRAPPLE HOOK TOOL PHYSICS ──
        boolean holdingGrapple = (heldBlock == Block.GRAPPLING_HOOK);
        boolean rmbHeld = glfwGetMouseButton(window, GLFW_MOUSE_BUTTON_RIGHT) == GLFW_PRESS;

        float zipX = 0f, zipY = 0f, zipZ = 0f;

        if (holdingGrapple && rmbHeld && !debugMode && !stand.isInStandPerspective()) {
            if (!isGrappleHooked && !lastRMBGrapple) {
                // Fire Hook
                Vector3f target = getGrappleAimTarget(camera, world, 50.0f);
                if (target != null) {
                    grappleHookPoint.set(target);
                    isGrappleHooked = true;
                    grappleHookTime = 0f;
                    velocityY = Math.max(velocityY, 14.0f); // Launch pop
                    if (useTestMovement) testMovement.velocity.y = Math.max(testMovement.velocity.y, 14.0f);
                    AudioManager.play("swoosh", 0.8f);
                }
            }

            if (isGrappleHooked) {
                grappleHookTime += deltaTime;
                Vector3f toHook = new Vector3f(grappleHookPoint).sub(position);
                float dist = toHook.length();

                if (dist < 1.8f) {
                    isGrappleHooked = false; // Reached target, auto-sever
                } else {
                    Vector3f pullDir = new Vector3f(toHook).normalize();

                    // ── Original arcade zip-line logic ──
                    if (grappleHookTime < 0.3f) {
                        zipX = pullDir.x * 4.0f;
                        zipZ = pullDir.z * 4.0f;
                        zipY = Math.max(2.0f, velocityY - (GameConfig.GRAVITY * 0.4f) * deltaTime);
                        setDirectVelocity(zipX, zipY, zipZ);
                    } else {
                        float t = Math.min(1.0f, (grappleHookTime - 0.3f) / 0.35f);
                        float currentSpeed = 4.0f + (t * t * 75.0f); // Slingshot!
                        zipX = pullDir.x * currentSpeed;
                        zipY = pullDir.y * currentSpeed;
                        zipZ = pullDir.z * currentSpeed;
                        setDirectVelocity(zipX, zipY, zipZ);
                    }
                }
            }
        } else {
            isGrappleHooked = false;
        }
        lastRMBGrapple = rmbHeld;

        // Run movement physics
        if (isGrappleHooked) {
            horizVel.zero();   // grapple drives position directly; don't carry stale momentum out
            // Apply collision-safe zip displacement directly
            float sx = zipX * deltaTime;
            float sy = zipY * deltaTime;
            float sz = zipZ * deltaTime;
            if (useTestMovement) {
                if (sx != 0f) { position.x += sx; if (testMovement.resolveAxisFluid(world, 0, sx)) sx = 0f; }
                if (sy != 0f) { position.y += sy; if (testMovement.resolveAxisFluid(world, 1, sy)) sy = 0f; }
                if (sz != 0f) { position.z += sz; if (testMovement.resolveAxisFluid(world, 2, sz)) sz = 0f; }
            } else {
                if (sx != 0f) { position.x += sx; if (resolveAxis(world, 0, sx)) sx = 0f; }
                if (sy != 0f) { position.y += sy; if (resolveAxis(world, 1, sy)) sy = 0f; }
                if (sz != 0f) { position.z += sz; if (resolveAxis(world, 2, sz)) sz = 0f; }
            }
            if (position.y < 0f) { fellIntoVoid = true; }
            syncEye(camera);
        } else if (useTestMovement && !inWater) {
            // Capture Y coordinate before the movement tick
            float prevY = position.y;

            // Tick the custom physics engine
            testMovement.setEnemyManager(attacks.getEnemyManager());
            testMovement.tick(window, camera, world, deltaTime);

            // Camera smoothing
            float diffY = position.y - prevY;
            float expectedMaxY = Math.max(0f, testMovement.velocity.y * deltaTime) + 0.01f;
            if (diffY > expectedMaxY) {
                cameraYOffset -= (diffY - expectedMaxY);
            }
            cameraYOffset += (0f - cameraYOffset) * Math.min(1f, 16f * deltaTime);

            // Sync normal velocityY so if we hit water next frame, we carry our momentum
            velocityY = testMovement.velocity.y;

            if (position.y < 0f) { fellIntoVoid = true; }
            syncEye(camera);
            wasInWaterTest = false;
        } else {
            // Standard Minecraft-style survival movement, gravity, and collision
            Vector3f wish = new Vector3f();
            cameraYOffset = 0f; // Reset offset when outside test mode

            // ── GROUND SMASH — pre-empt normal input while smashing ───────────
            if (isSmashing) {
                // Accelerate "downward" (toward gravity) like real free-fall.
                horizVel.zero();
                velocityY = Math.max(-GameConfig.smashDescentMaxSpeed,
                        velocityY - GameConfig.smashDescentAccel * deltaTime);
                float targetPitch = -(float)(Math.PI * 0.305);
                camera.pitch += (targetPitch - camera.pitch) * Math.min(1f, 4f * deltaTime);

            } else if (abilities.isDashing) {
                // ── DASH — override WASD with dash velocity ────────────────────
                wish.x = abilities.dashDirX * GameConfig.dashSpeed * deltaTime;
                wish.z = abilities.dashDirZ * GameConfig.dashSpeed * deltaTime;
                // Seed smoothed velocity so the dash's momentum glides out cleanly.
                horizVel.set(abilities.dashDirX * GameConfig.dashSpeed, 0f,
                             abilities.dashDirZ * GameConfig.dashSpeed);

            } else if (abilities.isCannonballing) {
                // ── CANNONBALL — override horizontal movement ──────────────────
                wish.x = abilities.cannonVelX * deltaTime;
                wish.z = abilities.cannonVelZ * deltaTime;
                horizVel.set(abilities.cannonVelX, 0f, abilities.cannonVelZ);

            } else if (abilities.isPillaring || abilities.isHealing) {
                // Lock horizontal movement while performing stone pillar rise or channeling heal
                horizVel.zero();

            } else {
                // Split forward into a horizontal (⊥ up) part for walking and an up part for
                // swimming, so moving while looking up/down in water doesn't double-count the
                // vertical (matches the old forward.x/z-walk + forward.y-swim behaviour).
                float    fUp    = forward.dot(upDir);
                Vector3f fHoriz = new Vector3f(forward).fma(-fUp, upDir);

                // Build the desired horizontal direction from WASD (camera-relative). The
                // swim-vertical impulses stay exactly as before; only the horizontal part
                // is velocity-smoothed.
                Vector3f wishDir = new Vector3f();
                if (glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS) {
                    wishDir.add(fHoriz);
                    if (isCameraInWater) velocityY += fUp * speed * 3.5f * deltaTime;
                }
                if (glfwGetKey(window, GLFW_KEY_S) == GLFW_PRESS) {
                    wishDir.sub(fHoriz);
                    if (isCameraInWater) velocityY -= fUp * speed * 3.5f * deltaTime;
                }
                if (glfwGetKey(window, GLFW_KEY_D) == GLFW_PRESS) wishDir.add(right);
                if (glfwGetKey(window, GLFW_KEY_A) == GLFW_PRESS) wishDir.sub(right);
                wishDir.y = 0f;   // locomotion stays on the ground plane; vertical is handled separately

                // Target velocity = normalized input direction × current speed.
                float tvx = 0f, tvz = 0f;
                if (wishDir.lengthSquared() > 1e-6f) {
                    wishDir.normalize();
                    tvx = wishDir.x * speed;
                    tvz = wishDir.z * speed;
                }

                if (GameConfig.smoothMovement) {
                    // Ease horizVel toward the target. Asymmetric & tight: accel ramps the
                    // start (no jerk), decel snaps the stop (no slide). Frame-rate-independent.
                    boolean hasInput = (tvx != 0f || tvz != 0f);
                    float rate = onGround
                            ? (hasInput ? GameConfig.groundAccel : GameConfig.groundDecel)
                            : (hasInput ? GameConfig.airAccel    : GameConfig.airDecel);
                    float k = 1f - (float) Math.exp(-rate * deltaTime);
                    horizVel.x += (tvx - horizVel.x) * k;
                    horizVel.z += (tvz - horizVel.z) * k;
                    // Safety clamp — horizontal speed can never run away and fling the player.
                    float cap = GameConfig.SPRINT_SPEED * 1.35f;
                    float sp2 = horizVel.x * horizVel.x + horizVel.z * horizVel.z;
                    if (sp2 > cap * cap) {
                        float s = cap / (float) Math.sqrt(sp2);
                        horizVel.x *= s; horizVel.z *= s;
                    }
                } else {
                    horizVel.set(tvx, 0f, tvz);   // instant (legacy feel)
                }

                wish.x = horizVel.x * deltaTime;
                wish.z = horizVel.z * deltaTime;
            }

            // ── SURVIVAL PHYSICS ──────────────────────────────────────────────
            // Water probes are taken at the feet and eye ALONG the up axis.
            boolean submerged = isBlockLiquid(world, position.x + upDir.x * EYE_HEIGHT,
                    position.y + upDir.y * EYE_HEIGHT,
                    position.z + upDir.z * EYE_HEIGHT);

            float h = upHeight();
            if (inWater && !wasInWater) highestY = h;

            if (inWater) {
                velocityY -= 2.5f * deltaTime;
                if (submerged) velocityY += 8.0f * deltaTime;

                if (currentSpace) velocityY += 35f * deltaTime;
                if (shiftHeld)    velocityY -= 35f * deltaTime;

                velocityY *= (float)Math.pow(0.85f, deltaTime * 60f);
                velocityY  = Math.max(-4.0f, Math.min(4.0f, velocityY));

                wish.mul(isSprinting ? 0.90f : 0.55f);
                horizVel.mul(isSprinting ? 0.90f : 0.55f);   // keep smoothed velocity in sync with water drag
                highestY = h;
                isSmashing = false;
                abilities.cancelCannonball(); // water takes over, cannonball ends cleanly

            } else if (!isSmashing) {
                if (GameConfig.dashFliesFlat && abilities.isDashing) {
                    // Flat air-dash: suspend gravity for the burst so it flies straight forward
                    // instead of sagging into an arc. Gravity resumes the instant the dash ends.
                    velocityY = 0f;
                } else {
                    velocityY -= GameConfig.GRAVITY * deltaTime;

                    if (wasInWater && currentSpace) {
                        velocityY = GameConfig.JUMP_FORCE * 0.85f;
                    } else if (currentSpace && onGround) {
                        velocityY = GameConfig.JUMP_FORCE;
                        onGround  = false;
                    }

                    boolean shiftJustPressed = shiftHeld && !lastShift;
                    if (!onGround
                            && shiftJustPressed
                            && velocityY < GameConfig.smashTriggerVelocity
                            && (highestY - h) > GameConfig.smashMinHeight) {
                        isSmashing = true;
                        mana = Math.max(0f, mana - GameConfig.manaSmash);  // drain; never cancel mid-air
                    }
                }
            }

            lastShift  = shiftHeld;
            wasInWater = inWater;

            // Total displacement: horizontal wish + vertical (velocity along up axis).
            Vector3f delta = new Vector3f(wish).fma(velocityY * deltaTime, upDir);

            int substeps = (int)Math.ceil(
                    Math.max(Math.abs(delta.x), Math.max(Math.abs(delta.y), Math.abs(delta.z))) * 10f);
            substeps = Math.max(1, substeps);

            float stepX = delta.x / substeps, stepY = delta.y / substeps, stepZ = delta.z / substeps;

            boolean wasOnGround = onGround;
            onGround = false;

            // Resolve each world axis independently
            for (int i = 0; i < substeps; i++) {
                if (stepX != 0f) { position.x += stepX; if (resolveAxis(world, 0, stepX)) { stepX = 0f; if (gravAxis != 0) isSprinting = false; } }
                if (stepY != 0f) { position.y += stepY; if (resolveAxis(world, 1, stepY)) { stepY = 0f; if (gravAxis != 1) isSprinting = false; } }
                if (stepZ != 0f) { position.z += stepZ; if (resolveAxis(world, 2, stepZ)) { stepZ = 0f; if (gravAxis != 2) isSprinting = false; } }
            }

            // Fall damage calculation
            float hNow = upHeight();
            if (!wasOnGround && onGround) {
                if (isSmashing) {
                    smashImpactX = (int)Math.floor(position.x);
                    smashImpactY = (int)Math.floor(position.y);
                    smashImpactZ = (int)Math.floor(position.z);

                    currentSmashRadius = GameConfig.smashCraterRadius
                            + (int) Math.floor((fallDist(hNow) - GameConfig.smashMinHeight) * 0.08f);
                    currentSmashRadius = Math.max(GameConfig.smashCraterRadius, Math.min(12, currentSmashRadius));

                    isSmashing   = false;
                    velocityY    = 0f;
                    camera.pitch = (float)Math.toRadians(-30.0);
                } else if (abilities.isCannonballing) {
                    abilities.isCannonballing = false;
                    abilities.cannonVelX      = 0f;
                    abilities.cannonVelZ      = 0f;
                    velocityY = 0f;
                } else {
                    float fallDist = highestY - hNow;
                    if (fallDist > 4.0f && !abilities.isKamui) {
                        health -= (fallDist * 0.5f - 2.0f);
                        if (health < 0f) health = 0f;
                    }
                    // Camera dip on landing, scaled to how far we fell (a small hop barely dips,
                    // a big drop bends the knees). Camera-only — no gameplay effect.
                    if (fallDist > 1.2f) {
                        viewDip = Math.max(viewDip,
                                Math.min(GameConfig.landingDipMax, fallDist * 0.025f));
                    }
                }
                highestY = hNow;
            } else if (onGround) {
                highestY = hNow;
                isSmashing = false;
            } else if (hNow > highestY) {
                highestY = hNow;
            }
            // Touching ground refreshes the air-jump budget.
            if (onGround) airJumpsUsed = 0;

            // Reconstruct velocity vector on water-exit so the handoff is perfect
            if (useTestMovement && deltaTime > 0f) {
                testMovement.velocity.set(delta.x / deltaTime, velocityY, delta.z / deltaTime);
            }

            // Void check
            if (position.y < 0f) { fellIntoVoid = true; }
            syncEye(camera);
            if (useTestMovement) wasInWaterTest = true;
        }
    }

    private float fallDist(float hNow) {
        return highestY - hNow;
    }

    /** Comfort damping: full-strength roll/FOV pumping made playtesters dizzy. */
    private static final float CAMERA_COMFORT = 0.4f;

    /** Extra roll forced by cinematic sequences (finale wake-up). Not comfort-damped. */
    public float externalRoll = 0f;

    /** Camera "knees-bend" dip on landing (blocks). Set on a hard landing, springs back to 0. Pure juice. */
    public float viewDip = 0f;

    public float getCameraRoll() {
        return (flightController.getCameraRoll() + abilities.getCameraRoll()) * CAMERA_COMFORT
                + externalRoll;
    }
    public float getCameraFovBoost() {
        if (isSmashing) return -8f * CAMERA_COMFORT;
        float boost = (flightController.getFovBoost() + abilities.getCameraFovBoost() + attacks.getFovBoost())
                * CAMERA_COMFORT;
        // Sprinting on the ground widens the view a touch — the classic "speed feel" cue.
        // Window's FOV lerp eases this in/out, so it ramps smoothly as you start/stop sprinting.
        if (isSprinting && onGround && !debugMode) boost += GameConfig.sprintFovKick;
        return boost;
    }
    public boolean isSmashing() { return isSmashing; }

    // ── GRAVITY (6-axis) HELPERS ──────────────────────────────────────────────

    /** Signed "height" measured along the current up axis (generalises position.y). */
    private float upHeight() {
        return position.x * upDir.x + position.y * upDir.y + position.z * upDir.z;
    }

    /** Place the camera at eye height along the current up axis. */
    private void syncEye(Camera camera) {
        // If 2D side-view is active in Flappy Mode, let the movement controller position the camera
        if (useTestMovement && testMovement.state == TestMovementController.State.FLAPPY && testMovement.flappySideView) {
            return;
        }
        camera.position.set(position.x + upDir.x * EYE_HEIGHT,
                position.y + upDir.y * EYE_HEIGHT + cameraYOffset - viewDip,
                position.z + upDir.z * EYE_HEIGHT);
    }

    /**
     * Snap gravity to the world axis the camera most points along — "down" becomes the
     * direction you're looking (look at a wall → fall to it and stand on it; look up →
     * fall to the ceiling). The camera then smoothly reorients to the new orientation.
     */
    public void setGravityFromLook(Camera camera) {
        Vector3f look = camera.getLookDirection();
        float ax = Math.abs(look.x), ay = Math.abs(look.y), az = Math.abs(look.z);
        int axis; float sign;
        if (ax >= ay && ax >= az) { axis = 0; sign = Math.signum(look.x); }
        else if (ay >= az)        { axis = 1; sign = Math.signum(look.y); }
        else                      { axis = 2; sign = Math.signum(look.z); }
        if (sign == 0f) sign = -1f;
        if (axis == gravAxis && sign == gravSign) return;     // already that way

        gravAxis = axis;
        gravSign = sign;
        upDir.set(0f, 0f, 0f);
        upDir.setComponent(axis, -sign);                      // up = −gravity
        velocityY = 0f;                                       // don't carry old vertical momentum
        onGround  = false;                                    // let them fall into the new frame
        highestY  = upHeight();                               // reset fall reference for the new axis
        camera.setGravityUp(upDir);                           // animate the view flip

        // Ensure pitch is clamped during the rotation to prevent glitches
        camera.clampPitch();
    }

    // ── Package-private accessors for AbilityController ───────────────────────
    // AbilityController is in the same package so these stay package-visible.
    public float getVelocityY()   { return velocityY; }
    public void  setVelocityY(float v) { this.velocityY = v; }
    public boolean isOnGround()        { return onGround; }
    /** True when the camera / eye position is inside a liquid block — used to trigger underwater reverb. */
    public boolean isCameraSubmerged() { return cameraSubmerged; }
    /** True while the player is sprinting (double-tap W). Used for footstep timing. */
    public boolean isSprinting()       { return isSprinting; }

    private boolean isBlockLiquid(World world, float x, float y, float z) {
        return world.getBlock(
                (int)Math.floor(x), (int)Math.floor(y), (int)Math.floor(z)).isLiquid();
    }

    private static final float EPSILON = 0.01f;

    /**
     * Generalised swept-AABB collision after the player has moved by {@code delta}
     * along world {@code axis} (0=X,1=Y,2=Z). The player box is WIDTH×WIDTH in the two
     * non-gravity axes and HEIGHT along the gravity axis (feet at {@code position},
     * extending toward {@code upDir}). Returns true if blocked; when {@code axis} is the
     * gravity axis it zeroes velocityY and, on a floor hit, sets onGround.
     *
     * For default gravity (down = −Y) this reduces exactly to the old per-axis resolvers.
     */
    private boolean resolveAxis(World world, int axis, float delta) {
        if (delta == 0f) return false;
        float halfW = WIDTH / 2f;
        float us    = -gravSign;                         // up sign on the gravity axis

        // Box extent as an offset from position, per world axis.
        float[] lo = { -halfW, -halfW, -halfW };
        float[] hi = {  halfW,  halfW,  halfW };
        if (us > 0f) { lo[gravAxis] = 0f;      hi[gravAxis] = HEIGHT; }
        else         { lo[gravAxis] = -HEIGHT; hi[gravAxis] = 0f;     }

        float[] p = { position.x, position.y, position.z };
        int a1 = (axis + 1) % 3, a2 = (axis + 2) % 3;    // the two cross-section axes
        int lo1 = (int)Math.floor(p[a1] + lo[a1] + EPSILON), hi1 = (int)Math.floor(p[a1] + hi[a1] - EPSILON);
        int lo2 = (int)Math.floor(p[a2] + lo[a2] + EPSILON), hi2 = (int)Math.floor(p[a2] + hi[a2] - EPSILON);

        boolean positive = delta > 0f;
        int     block    = (int)Math.floor(positive ? p[axis] + hi[axis] : p[axis] + lo[axis]);

        int[] c = new int[3];
        c[axis] = block;
        for (int i1 = lo1; i1 <= hi1; i1++) {
            c[a1] = i1;
            for (int i2 = lo2; i2 <= hi2; i2++) {
                c[a2] = i2;
                if (world.getBlock(c[0], c[1], c[2]).isSolid()) {
                    position.setComponent(axis, positive ? (block - hi[axis]) : (block + 1f - lo[axis]));
                    if (axis == gravAxis) {
                        velocityY = 0f;
                        if (Math.signum(delta) == Math.signum(gravSign)) onGround = true;   // floor hit
                    }
                    return true;
                }
            }
        }
        return false;
    }

    public RaycastResult getTargetBlock(Camera camera, World world) {
        final float STEP = 0.05f, MAX_REACH = 5.0f;
        float rx = camera.position.x, ry = camera.position.y, rz = camera.position.z;
        org.joml.Vector3f dir = camera.getLookDirection();
        float ddx = dir.x * STEP, ddy = dir.y * STEP, ddz = dir.z * STEP;

        int lastBX = (int)Math.floor(rx), lastBY = (int)Math.floor(ry), lastBZ = (int)Math.floor(rz);
        float dist = 0;

        while (dist < MAX_REACH) {
            rx += ddx; ry += ddy; rz += ddz; dist += STEP;
            int bx = (int)Math.floor(rx), by = (int)Math.floor(ry), bz = (int)Math.floor(rz);

            if (world.getBlock(bx, by, bz).isSolid()) {
                RaycastResult res = new RaycastResult();
                res.hit = true; res.hitX = bx; res.hitY = by; res.hitZ = bz;
                res.placeX = lastBX; res.placeY = lastBY; res.placeZ = lastBZ;
                return res;
            }
            lastBX = bx; lastBY = by; lastBZ = bz;
        }
        return null;
    }
    /**
     * Set direct velocity during a grapple zip, supporting both the standard physics
     * engine and the custom test movement sandbox cleanly.
     */
    private void setDirectVelocity(float x, float y, float z) {
        if (useTestMovement) {
            testMovement.velocity.set(x, y, z);
        } else {
            velocityY = y;
        }
    }

    /**
     * Sweeps a long-range raycast to detect an anchor block for the grappling hook.
     */
    public Vector3f getGrappleAimTarget(Camera camera, World world, float maxRange) {
        Vector3f dir  = camera.getLookDirection();
        float    step = 0.4f;
        float    rx   = camera.position.x, ry = camera.position.y, rz = camera.position.z;

        for (float dist = 0f; dist < maxRange; dist += step) {
            rx += dir.x * step; ry += dir.y * step; rz += dir.z * step;
            int bx = (int)Math.floor(rx), by = (int)Math.floor(ry), bz = (int)Math.floor(rz);
            if (by >= 0 && by < Chunk.HEIGHT && world.getBlock(bx, by, bz).isSolid()) {
                return new Vector3f(rx, ry, rz);
            }
        }
        return null;
    }
}