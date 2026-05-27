package com.leaf.game.entity;

import com.leaf.game.core.GameConfig;
import com.leaf.game.util.Camera;
import com.leaf.game.world.Chunk;
import com.leaf.game.world.World;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFW.*;

/**
 * SealController — "Minato's Seal" ability.
 *
 *   H : fire a seal projectile that embeds on the first solid surface it hits.
 *       Maximum GameConfig.sealMaxCount seals can be placed simultaneously.
 *   B : teleport instantly to the "targeted" seal (seal closest to crosshair).
 *   N : reclaim the targeted seal (remove from world, restore count).
 *
 * ── Integration contract ──────────────────────────────────────────────────
 *   • Player.update() calls seals.tick() after attacks.tick() each frame.
 *   • tick() never takes full positional control — teleport is instant.
 *   • Window renders placed seals in two passes:
 *       Pass 1 (normal depth): opaque spinning CRYSTAL_CITRINE cube.
 *       Pass 2 (depth-test disabled, smaller + alphaMultiplier=sealThroughWallAlpha):
 *              through-wall ghost so the player can always see seal positions.
 *       The targeted seal is drawn at scale sealTargetedScale so it stands out.
 *   • Window renders in-flight seal projectiles as small CRYSTAL_CITRINE cubes.
 *   • teleportFlash is exposed for Window's white-flash overlay on teleport.
 *
 * ── Targeting logic ───────────────────────────────────────────────────────
 *   Each frame, updateTargeting() computes the dot product between the
 *   camera look direction and each (eye→seal) vector.  The seal with the
 *   highest dot product (closest to crosshair centre) is marked `targeted`.
 *   This works through walls, so the player can always select a seal even
 *   without direct sight.
 */
public class SealController {

    // ─────────────────────────────────────────────────────────────────────────
    //  Placed seal
    // ─────────────────────────────────────────────────────────────────────────

    public static class SealEntry {
        /** World-space position of the embedded seal. */
        public final Vector3f position;
        /** True when this seal is the crosshair-targeted one this frame. */
        public       boolean  targeted   = false;
        /** Accumulated seconds — drives the idle pulse glow animation. */
        public       float    pulsePhase = 0f;
        /** Accumulated radians — drives cube spin animation in Window. */
        public       float    spinPhase  = 0f;

        SealEntry(Vector3f pos) {
            this.position = new Vector3f(pos);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  In-flight seal projectile
    // ─────────────────────────────────────────────────────────────────────────

    public static class SealProjectile {
        public final Vector3f pos;
        public final Vector3f vel;
        public       float    lifetime;   // seconds before despawn if no hit
        public       float    spinPhase = 0f;
        public       boolean  alive = true;

        SealProjectile(Vector3f pos, Vector3f vel) {
            this.pos      = new Vector3f(pos);
            this.vel      = new Vector3f(vel);
            this.lifetime = GameConfig.sealProjectileLifetime;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    private final Player player;

    /** All currently placed seals — Window reads this list to render them. */
    public final List<SealEntry>      placedSeals   = new ArrayList<>();
    /** Seal projectiles currently in flight — Window renders these too. */
    public final List<SealProjectile> inFlightSeals = new ArrayList<>();

    // ── Cooldowns ──────────────────────────────────────────────────────────────
    private float placeCooldown    = 0f;
    private float teleportCooldown = 0f;

    // ── White flash timer (set on teleport, read by Window for overlay) ────────
    /** Window reads this and applies a white vignette that fades to zero. */
    public float teleportFlash = 0f;

    // ── Key edge detectors ─────────────────────────────────────────────────────
    private boolean lastH = false;
    private boolean lastB = false;
    private boolean lastN = false;

    // ─────────────────────────────────────────────────────────────────────────
    public SealController(Player p) {
        this.player = p;
    }

    // ── Public accessors ──────────────────────────────────────────────────────
    public int   getSealCount()            { return placedSeals.size(); }
    /** 0 = on cooldown, 1 = fully ready. */
    public float getTeleportCooldownFrac() {
        return teleportCooldown <= 0f ? 1f : 1f - teleportCooldown / GameConfig.sealTeleportCooldown;
    }
    public float getPlaceCooldownFrac() {
        return placeCooldown <= 0f ? 1f : 1f - placeCooldown / GameConfig.sealPlaceCooldown;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Main tick — call from Player.update() after attacks.tick()
    // ─────────────────────────────────────────────────────────────────────────

    public void tick(long window, Camera camera, World world, float dt) {
        tickCooldowns(dt);
        tickInFlight(world, dt);
        tickPlacedSeals(dt);
        updateTargeting(camera);

        // ── H — Fire seal projectile ───────────────────────────────────────────
        boolean hHeld = glfwGetKey(window, GLFW_KEY_H) == GLFW_PRESS;
        if (hHeld && !lastH
                && placeCooldown <= 0f
                && placedSeals.size() < GameConfig.sealMaxCount) {
            fireSeal(camera);
            placeCooldown = GameConfig.sealPlaceCooldown;
        }
        lastH = hHeld;

        // ── B — Teleport to targeted seal ─────────────────────────────────────
        boolean bHeld = glfwGetKey(window, GLFW_KEY_B) == GLFW_PRESS;
        if (bHeld && !lastB && teleportCooldown <= 0f) {
            teleportToTargetedSeal(camera);
        }
        lastB = bHeld;

        // ── N — Reclaim targeted seal ──────────────────────────────────────────
        boolean nHeld = glfwGetKey(window, GLFW_KEY_N) == GLFW_PRESS;
        if (nHeld && !lastN) {
            reclaimTargetedSeal();
        }
        lastN = nHeld;

        // Decay teleport flash toward zero
        if (teleportFlash > 0f) {
            teleportFlash = Math.max(0f, teleportFlash - dt);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Fire seal projectile
    // ─────────────────────────────────────────────────────────────────────────

    private void fireSeal(Camera camera) {
        Vector3f dir      = camera.getLookDirection();
        // Spawn 1.2 blocks ahead of eye so it clears the player's face geometry
        Vector3f startPos = new Vector3f(camera.position).add(new Vector3f(dir).mul(1.2f));
        Vector3f vel      = new Vector3f(dir).mul(GameConfig.sealProjectileSpeed);
        inFlightSeals.add(new SealProjectile(startPos, vel));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Tick in-flight projectiles
    // ─────────────────────────────────────────────────────────────────────────

    private void tickInFlight(World world, float dt) {
        for (SealProjectile proj : inFlightSeals) {
            if (!proj.alive) continue;

            proj.lifetime  -= dt;
            proj.spinPhase += dt * 6f;

            if (proj.lifetime <= 0f) { proj.alive = false; continue; }

            // Sub-step to avoid tunnelling through thin surfaces
            float totalDist = proj.vel.length() * dt;
            int   substeps  = Math.max(1, (int)Math.ceil(totalDist / 0.4f));
            float subDt     = dt / substeps;
            boolean hit     = false;

            float rx = proj.pos.x, ry = proj.pos.y, rz = proj.pos.z;
            for (int s = 0; s < substeps && !hit; s++) {
                float prevRx = rx, prevRy = ry, prevRz = rz;
                rx += proj.vel.x * subDt;
                ry += proj.vel.y * subDt;
                rz += proj.vel.z * subDt;

                int bx = (int)Math.floor(rx), by = (int)Math.floor(ry), bz = (int)Math.floor(rz);
                if (by < 0 || by >= Chunk.HEIGHT) { proj.alive = false; hit = true; break; }

                if (world.getBlock(bx, by, bz).isSolid()) {
                    // Embed at the position just before impact
                    embedSeal(new Vector3f(prevRx, prevRy, prevRz));
                    proj.alive = false;
                    hit = true;
                }
            }
            if (!hit) {
                proj.pos.set(rx, ry, rz);
            }
        }
        inFlightSeals.removeIf(p -> !p.alive);
    }

    private void embedSeal(Vector3f pos) {
        // If at the cap, silently reject (player must reclaim one manually).
        // This is a deliberate design choice — seals are a scarce resource.
        if (placedSeals.size() >= GameConfig.sealMaxCount) return;
        placedSeals.add(new SealEntry(pos));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Animate placed seals (pulse + spin)
    // ─────────────────────────────────────────────────────────────────────────

    private void tickPlacedSeals(float dt) {
        for (SealEntry seal : placedSeals) {
            seal.pulsePhase += dt * GameConfig.sealPulseSpeed;
            seal.spinPhase  += dt * 1.5f;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Targeting — dot product to find seal closest to crosshair
    // ─────────────────────────────────────────────────────────────────────────

    private void updateTargeting(Camera camera) {
        if (placedSeals.isEmpty()) return;

        Vector3f eye = camera.position;
        Vector3f dir = camera.getLookDirection();

        float bestDot   = -2f;
        int   bestIndex = -1;

        for (int i = 0; i < placedSeals.size(); i++) {
            Vector3f toSeal = new Vector3f(placedSeals.get(i).position).sub(eye);
            float dist = toSeal.length();
            if (dist < 0.001f) continue;
            float dot = dir.dot(toSeal.div(dist));
            if (dot > bestDot) {
                bestDot   = dot;
                bestIndex = i;
            }
        }

        for (int i = 0; i < placedSeals.size(); i++) {
            placedSeals.get(i).targeted = (i == bestIndex);
        }
    }

    /** Returns the seal currently marked as targeted, or null if the list is empty. */
    public SealEntry getTargetedSeal() {
        for (SealEntry seal : placedSeals) {
            if (seal.targeted) return seal;
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Teleport
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Teleport the player to the targeted seal and orient the camera according
     * to {@link GameConfig#sealLookMode}:
     * <ul>
     *   <li>0 — keep the camera look direction unchanged (default)</li>
     *   <li>1 — face the direction of travel (look away from the origin of the jump)</li>
     *   <li>2 — look toward the nearest remaining seal after landing</li>
     * </ul>
     */
    private void teleportToTargetedSeal(Camera camera) {
        SealEntry target = getTargetedSeal();
        if (target == null) return;

        Vector3f origin = new Vector3f(player.position); // capture before move

        player.position.set(target.position);
        player.setVelocityY(0f);
        player.highestY  = target.position.y;  // suppress fall-damage at destination
        teleportCooldown = GameConfig.sealTeleportCooldown;
        teleportFlash    = GameConfig.sealTeleportFlash;

        switch (GameConfig.sealLookMode) {

            case 1: {
                // Look toward the nearest remaining seal that is NOT the target AND NOT at the origin
                float bestDist = Float.MAX_VALUE;
                Vector3f bestPos = null;
                for (SealEntry s : placedSeals) {
                    if (s == target) continue; // skip the seal we landed on
                    // skip the seal (if any) near the origin we just teleported from
                    if (new Vector3f(s.position).sub(origin).lengthSquared() < 4.0f) continue;

                    float d = new Vector3f(s.position).sub(target.position).lengthSquared();
                    if (d < bestDist) {
                        bestDist = d;
                        bestPos  = s.position;
                    }
                }
                if (bestPos != null) {
                    Vector3f toNext = new Vector3f(bestPos).sub(target.position);
                    float hDist = (float)Math.sqrt(toNext.x * toNext.x + toNext.z * toNext.z);
                    if (hDist > 0.01f) {
                        camera.yaw   = (float)Math.atan2(toNext.z, toNext.x);
                        camera.pitch = (float)Math.atan2(toNext.y, hDist);
                        camera.clampPitch();
                    }
                }
                break;
            }

            case 2: {
                // Look toward the nearest REMAINING seal (excluding the one we just used,
                // which has not been removed — so skip the one with the smallest distance
                // to our new position if we want to ignore the landing seal itself).
                float bestDist = Float.MAX_VALUE;
                Vector3f bestPos = null;
                for (SealEntry s : placedSeals) {
                    if (s == target) continue; // skip the seal we landed on
                    float d = new Vector3f(s.position).sub(target.position).length();
                    if (d < bestDist) {
                        bestDist = d;
                        bestPos  = s.position;
                    }
                }
                if (bestPos != null) {
                    Vector3f toNext = new Vector3f(bestPos).sub(target.position);
                    float hDist = (float)Math.sqrt(toNext.x * toNext.x + toNext.z * toNext.z);
                    if (hDist > 0.01f) {
                        camera.yaw   = (float)Math.atan2(toNext.z, toNext.x);
                        // pitch > 0 means looking UP; if toNext.y > 0 seal is above → positive pitch
                        camera.pitch = (float)Math.atan2(toNext.y, hDist);
                        camera.clampPitch();
                    }
                }
                break;
            }

            // case 0: fall through — keep camera unchanged
            default:
                break;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Reclaim
    // ─────────────────────────────────────────────────────────────────────────

    private void reclaimTargetedSeal() {
        SealEntry target = getTargetedSeal();
        if (target != null) {
            float dist = new Vector3f(target.position).sub(player.position).length();
            if (dist <= 8.0f) { // Must be within 8 blocks to reclaim
                placedSeals.remove(target);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Cooldown helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void tickCooldowns(float dt) {
        if (placeCooldown    > 0f) placeCooldown    -= dt;
        if (teleportCooldown > 0f) teleportCooldown -= dt;
    }
}
