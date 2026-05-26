package com.leaf.game.entity;

import com.leaf.game.core.GameConfig;
import com.leaf.game.util.Camera;
import com.leaf.game.world.Block;
import com.leaf.game.world.World;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFW.*;

/**
 * AttackController — two combat abilities wired to F and C.
 *
 *   F  — Runic Cleave   a sweeping melee strike that shatters blocks in a
 *                        wide arc and sends them flying forward.
 *                        Three-phase: WINDUP → STRIKE (instant) → RECOIL.
 *
 *   C  — Void Shard     hold to charge, release fires a glowing crystal bolt
 *                        that explodes on impact with a sphere of destruction
 *                        and a burst of crystal debris.
 *
 * ── Integration contract ────────────────────────────────────────────────────
 *   • Player.update() calls attacks.tick() after abilities.tick().
 *   • Window applies getPitchOffset() non-destructively around getViewMatrix().
 *   • Window composites getOverlayStrength/Color with abilities overlay.
 *   • Window drains pendingDebris into DroppedItems each frame.
 *   • Window reads shakeRequest, triggers shake, then zeros it.
 *   • Window renders activeBolts as spinning CRYSTAL_AMETHYST cubes.
 */
public class AttackController {

    // ── Bolt ──────────────────────────────────────────────────────────────────

    /** A single Void Shard bolt in flight. */
    public static class ActiveBolt {
        public final Vector3f pos;
        public final Vector3f vel;
        public       float    lifetime;   // counts down to 0 then removed
        public final float    chargeF;    // 0–1 — scales explosion radius & shake
        public       float    spinPhase;  // accumulates for visual spin
        public       boolean  alive = true;

        ActiveBolt(Vector3f pos, Vector3f vel, float chargeF) {
            this.pos      = new Vector3f(pos);
            this.vel      = new Vector3f(vel);
            this.lifetime = GameConfig.voidShardLifetime;
            this.chargeF  = chargeF;
            this.spinPhase = 0f;
        }
    }

    // ── Debris spawn request ──────────────────────────────────────────────────

    /**
     * Queued by tick(); drained by Window into DroppedItems next frame.
     * Avoids giving this controller a direct reference to the dropped-item list.
     */
    public static class DebrisSpawn {
        public final int bx, by, bz;
        public final Block block;
        public final Vector3f vel;
        DebrisSpawn(int bx, int by, int bz, Block block, Vector3f vel) {
            this.bx = bx; this.by = by; this.bz = bz;
            this.block = block;
            this.vel   = new Vector3f(vel);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    private final Player player;

    // ── Melee state (F — Runic Cleave) ───────────────────────────────────────
    private enum MeleePhase { IDLE, WINDUP, RECOIL }
    private MeleePhase meleePhase     = MeleePhase.IDLE;
    private float      meleeTimer     = 0f;
    private float      meleeCooldown  = 0f;
    private boolean    lastF          = false;

    // ── Ranged state (C — Void Shard) ────────────────────────────────────────
    private boolean isCharging      = false;
    private float   chargeTime      = 0f;
    private float   rangedCooldown  = 0f;
    private boolean lastC           = false;

    /** Live bolts in flight — Window renders these. */
    public final List<ActiveBolt> activeBolts = new ArrayList<>();

    /** Debris waiting to be spawned — drained by Window each frame. */
    public final List<DebrisSpawn> pendingDebris = new ArrayList<>();

    // ── Camera effects ────────────────────────────────────────────────────────
    // pitchOffset:  target value set per-phase, smoothed into smoothPitch.
    // Window applies smoothPitch non-destructively around getViewMatrix().
    private float     pitchTarget    = 0f;
    private float     smoothPitch    = 0f;
    private float     fovBoost       = 0f;
    private float     overlayStrength = 0f;
    private final Vector3f overlayColor = new Vector3f();

    /**
     * Set by tick() when a strike or bolt impacts.
     * Window reads this once, triggers shake, then zeroes it.
     */
    public float shakeRequest = 0f;

    // ─────────────────────────────────────────────────────────────────────────
    public AttackController(Player p) { this.player = p; }

    // ── Public accessors (Window + Player) ───────────────────────────────────
    /** Smooth pitch offset (radians) — apply around getViewMatrix() in Window. */
    public float    getPitchOffset()     { return smoothPitch; }
    /** Add to FlightController + abilities FOV boosts. */
    public float    getFovBoost()        { return fovBoost; }
    public float    getOverlayStrength() { return overlayStrength; }
    public Vector3f getOverlayColor()    { return overlayColor; }
    /** 0–1 charge progress for optional HUD display. */
    public float    getChargeFrac()      { return isCharging ? Math.min(1f, chargeTime / GameConfig.voidShardMaxCharge) : 0f; }

    // ─────────────────────────────────────────────────────────────────────────
    //  Main tick — called from Player.update() every survival-mode frame
    // ─────────────────────────────────────────────────────────────────────────

    public void tick(long window, Camera camera, World world, float dt) {
        if (player.debugMode) {
            decayAll(dt);
            return;
        }

        tickCooldowns(dt);
        tickBolts(world, dt);
        tickMelee(window, camera, world, dt);
        tickRanged(window, camera, world, dt);

        // Smooth pitch toward target
        float pitchLerp = (meleePhase == MeleePhase.IDLE && !isCharging) ? 10f : 20f;
        smoothPitch += (pitchTarget - smoothPitch) * Math.min(1f, pitchLerp * dt);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  MELEE — Runic Cleave  (F)
    // ─────────────────────────────────────────────────────────────────────────

    private void tickMelee(long window, Camera camera, World world, float dt) {
        boolean fHeld = glfwGetKey(window, GLFW_KEY_F) == GLFW_PRESS;

        switch (meleePhase) {

            case IDLE -> {
                // Leading edge of F, not on cooldown, no ability stealing control
                if (fHeld && !lastF && meleeCooldown <= 0f
                        && !player.abilities.isAnyAbilityActive()) {
                    meleePhase = MeleePhase.WINDUP;
                    meleeTimer = GameConfig.meleeWindupDuration;
                }
            }

            case WINDUP -> {
                meleeTimer -= dt;
                // windup: 0 (start) → 1 (end)
                float prog = 1f - Math.max(0f, meleeTimer / GameConfig.meleeWindupDuration);

                // Camera floats upward slightly — coiling energy
                pitchTarget = -0.35f * prog;
                fovBoost    = lerp(fovBoost, -6f * prog, 14f * dt);

                // Amber-gold vignette intensifies
                blendOverlay(new Vector3f(1.0f, 0.60f, 0.06f), 0.22f * prog, dt);

                if (meleeTimer <= 0f) {
                    // ── INSTANT STRIKE ────────────────────────────────────────
                    executeStrike(camera, world);

                    pitchTarget  = +0.55f;          // snap camera downward — recoil kick
                    fovBoost     = +24f;
                    // White-hot flash
                    overlayColor.set(1.0f, 0.96f, 0.88f);
                    overlayStrength = 0.58f;

                    shakeRequest = GameConfig.meleeShakeStrength;

                    meleePhase    = MeleePhase.RECOIL;
                    meleeTimer    = GameConfig.meleeRecoilDuration;
                    meleeCooldown = GameConfig.meleeCooldown;
                }
            }

            case RECOIL -> {
                meleeTimer -= dt;
                float prog = Math.max(0f, meleeTimer / GameConfig.meleeRecoilDuration);

                // Camera and FOV decay smoothly back to normal
                pitchTarget = +0.55f * prog;
                fovBoost    = lerp(fovBoost, +24f * prog, 12f * dt);

                // Orange afterglow fades
                blendOverlay(new Vector3f(0.95f, 0.32f, 0.04f), 0.18f * prog, dt);

                if (meleeTimer <= 0f) {
                    meleePhase  = MeleePhase.IDLE;
                    pitchTarget = 0f;
                }
            }
        }

        lastF = fHeld;
    }

    /**
     * Destroys solid blocks in a 3×3×3 arc ahead of the player,
     * launching each shattered block as a DebrisSpawn.
     * Skips ultra-hard blocks (STONE and STAR_IRON) so the player
     * can't trivially strip-mine mountains.
     */
    private void executeStrike(Camera camera, World world) {
        Vector3f fwd   = camera.getForward();   // yaw-only (no pitch component)
        Vector3f right = camera.getRight();

        // Centre the sweep at mid-torso height
        int eyeY = (int) Math.floor(player.position.y + 1.1f);

        for (int depth = 1; depth <= 3; depth++) {
            for (int side = -1; side <= 1; side++) {
                for (int vert = -1; vert <= 1; vert++) {
                    int bx = (int) Math.floor(player.position.x + fwd.x * depth + right.x * side);
                    int by = eyeY + vert;
                    int bz = (int) Math.floor(player.position.z + fwd.z * depth + right.z * side);

                    Block b = world.getBlock(bx, by, bz);
                    if (!b.isSolid()) continue;
                    // Leave hard stone intact — feels more meaningful to break soft terrain
                    if (b == Block.STONE || b == Block.STAR_IRON || b == Block.MEGALITH
                            || b == Block.MEGALITH_CARVED || b == Block.MOSSY_MEGALITH) continue;

                    world.setBlock(bx, by, bz, Block.AIR);

                    // Eject debris forward + slight upward arc
                    float speed = 8f + (float) Math.random() * 7f;
                    float vx = fwd.x * speed + (float) (Math.random() - 0.5) * 3.5f;
                    float vy = 2.5f + (float) Math.random() * 4.5f;
                    float vz = fwd.z * speed + (float) (Math.random() - 0.5) * 3.5f;
                    pendingDebris.add(new DebrisSpawn(bx, by, bz, b, new Vector3f(vx, vy, vz)));
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  RANGED — Void Shard  (C)
    // ─────────────────────────────────────────────────────────────────────────

    private void tickRanged(long window, Camera camera, World world, float dt) {
        boolean cHeld = glfwGetKey(window, GLFW_KEY_C) == GLFW_PRESS;

        // Block firing while melee is active or on cooldown
        if (rangedCooldown > 0f || meleePhase != MeleePhase.IDLE) {
            isCharging = false;
            lastC = cHeld;
            return;
        }

        if (cHeld) {
            // Start charging on leading edge (only if nothing else is running)
            if (!isCharging && !player.abilities.isAnyAbilityActive()) {
                isCharging = true;
                chargeTime = 0f;
            }
            if (isCharging) {
                chargeTime = Math.min(chargeTime + dt, GameConfig.voidShardMaxCharge);
                float cf = chargeTime / GameConfig.voidShardMaxCharge;
                // Deep-purple vignette swells with charge
                blendOverlay(new Vector3f(0.50f + cf * 0.25f, 0.10f, 0.95f), 0.20f * cf, dt);
                fovBoost = lerp(fovBoost, -4f * cf, 10f * dt);
            }
        } else if (isCharging) {
            // C released — fire
            float cf = Math.min(1f, chargeTime / GameConfig.voidShardMaxCharge);
            fireBolt(camera, cf);
            isCharging    = false;
            rangedCooldown = GameConfig.voidShardCooldown;
            // Brief purple flash on release
            overlayColor.set(0.75f, 0.40f, 1.0f);
            overlayStrength = 0.32f;
        }

        // When nothing is active, let overlay and FOV decay naturally
        if (!isCharging && meleePhase == MeleePhase.IDLE) {
            blendOverlay(new Vector3f(0f, 0f, 0f), 0f, dt);
            fovBoost = lerp(fovBoost, 0f, 8f * dt);
        }

        lastC = cHeld;
    }

    private void fireBolt(Camera camera, float chargeF) {
        float speed = GameConfig.voidShardMinSpeed
                + chargeF * (GameConfig.voidShardMaxSpeed - GameConfig.voidShardMinSpeed);
        Vector3f dir = camera.getLookDirection();
        // Spawn 1.4 blocks in front so it clears the player's own hitbox
        Vector3f startPos = new Vector3f(camera.position).add(new Vector3f(dir).mul(1.4f));
        activeBolts.add(new ActiveBolt(startPos, new Vector3f(dir).mul(speed), chargeF));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  BOLT TICK
    // ─────────────────────────────────────────────────────────────────────────

    private void tickBolts(World world, float dt) {
        for (ActiveBolt bolt : activeBolts) {
            if (!bolt.alive) continue;

            bolt.lifetime  -= dt;
            bolt.spinPhase += dt * 9f;

            if (bolt.lifetime <= 0f) {
                bolt.alive = false;
                continue;
            }

            // Sub-step to avoid tunnelling through thin walls at high speed
            float totalDist = bolt.vel.length() * dt;
            int   substeps  = Math.max(1, (int) Math.ceil(totalDist / 0.4f));
            float subDt     = dt / substeps;
            boolean hit     = false;

            for (int s = 0; s < substeps && !hit; s++) {
                bolt.pos.add(new Vector3f(bolt.vel).mul(subDt));
                int bx = (int) Math.floor(bolt.pos.x);
                int by = (int) Math.floor(bolt.pos.y);
                int bz = (int) Math.floor(bolt.pos.z);
                if (world.getBlock(bx, by, bz).isSolid()) {
                    boltImpact(bolt, world, bx, by, bz);
                    bolt.alive = false;
                    hit = true;
                }
            }
        }
        activeBolts.removeIf(b -> !b.alive);
    }

    /**
     * Sphere explosion centred on impact block — shatters terrain and
     * launches a burst of crystal + block-type debris outward.
     */
    private void boltImpact(ActiveBolt bolt, World world, int cx, int cy, int cz) {
        float radius = GameConfig.voidShardMinRadius
                + bolt.chargeF * (GameConfig.voidShardMaxRadius - GameConfig.voidShardMinRadius);
        int   ri     = (int) Math.ceil(radius);

        Vector3f boltDir   = new Vector3f(bolt.vel).normalize();
        int      maxDebris = 8 + (int) (bolt.chargeF * 7f);
        int      spawned   = 0;

        for (int dx = -ri; dx <= ri; dx++) {
            for (int dy = -ri; dy <= ri; dy++) {
                for (int dz = -ri; dz <= ri; dz++) {
                    if ((float)(dx*dx + dy*dy + dz*dz) > radius * radius) continue;
                    int bx = cx + dx, by = cy + dy, bz = cz + dz;
                    Block b = world.getBlock(bx, by, bz);
                    if (!b.isSolid()) continue;

                    world.setBlock(bx, by, bz, Block.AIR);

                    if (spawned < maxDebris) {
                        float speed = 4f + (float) Math.random() * 9f * (0.5f + bolt.chargeF * 0.5f);
                        // Random radial direction + forward momentum from bolt
                        Vector3f ejVel = new Vector3f(
                                (float) (Math.random() - 0.5),
                                0.2f + (float) Math.random() * 0.8f,
                                (float) (Math.random() - 0.5)
                        ).normalize().mul(speed);
                        ejVel.add(new Vector3f(boltDir).mul(speed * 0.4f));
                        ejVel.y += 2f + (float) Math.random() * 3f;

                        Block debrisType = (spawned % 3 == 0) ? b : pickCrystalDebris();
                        pendingDebris.add(new DebrisSpawn(bx, by, bz, debrisType, ejVel));
                        spawned++;
                    }
                }
            }
        }

        // Scale shake with charge
        shakeRequest = Math.max(shakeRequest,
                GameConfig.voidShardShakeStrength * (0.5f + bolt.chargeF * 0.5f));

        // Purple impact flash
        overlayColor.set(0.65f, 0.25f, 1.0f);
        overlayStrength = 0.40f;
    }

    private Block pickCrystalDebris() {
        Block[] options = { Block.CRYSTAL_AMETHYST, Block.CRYSTAL_QUARTZ,
                            Block.CRYSTAL_CITRINE,  Block.CRYSTAL_ROSE };
        return options[(int) (Math.random() * options.length)];
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void tickCooldowns(float dt) {
        if (meleeCooldown  > 0f) meleeCooldown  -= dt;
        if (rangedCooldown > 0f) rangedCooldown -= dt;
    }

    private void blendOverlay(Vector3f targetColor, float targetStrength, float dt) {
        float t = Math.min(1f, 13f * dt);
        overlayColor.lerp(targetColor, t);
        overlayStrength += (targetStrength - overlayStrength) * t;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * Math.min(1f, t);
    }

    private void decayAll(float dt) {
        pitchTarget = 0f;
        smoothPitch += (0f - smoothPitch)    * Math.min(1f, 10f * dt);
        fovBoost    += (0f - fovBoost)       * Math.min(1f, 8f  * dt);
        blendOverlay(new Vector3f(0f, 0f, 0f), 0f, dt);
    }
}
