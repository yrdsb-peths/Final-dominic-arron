package com.leaf.game.entity;

import com.leaf.game.world.Block;
import org.joml.Vector3f;

public class DroppedItem {
    public Vector3f position;
    public Block    blockType;
    public float    age;
    public boolean  alive = true;

    /**
     * Optional launch velocity for ejected crater debris.
     * Non-zero → physics are applied (gravity + air drag) until velocity is
     * negligible, after which normal magnet behaviour takes over.
     * Zero-length (default) → original behaviour, no change to existing items.
     */
    public Vector3f velocity = new Vector3f(0f, 0f, 0f);

    // ── Lock-on state ────────────────────────────────────────────────────────
    private boolean isLockedOn = false;

    // Unique origin coordinates for network deduplication
    public final int originX, originY, originZ;

    // ─────────────────────────────────────────────────────────────────────────
    //  Standard constructor — spawns at block position with random jitter.
    //  Used for broken blocks; velocity defaults to zero.
    // ─────────────────────────────────────────────────────────────────────────
    public DroppedItem(int bx, int by, int bz, Block blockType) {
        this.originX   = bx;
        this.originY   = by;
        this.originZ   = bz;
        float rx = (float)(Math.random() * 0.4f - 0.2f);
        float rz = (float)(Math.random() * 0.4f - 0.2f);
        this.position  = new Vector3f(bx + 0.5f + rx, by + 0.2f, bz + 0.5f + rz);
        this.blockType = blockType;
        this.age       = (float)(Math.random() * 100.0f);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Ejection constructor — for crater debris with an outward launch velocity.
    // ─────────────────────────────────────────────────────────────────────────
    public DroppedItem(int bx, int by, int bz, Block blockType, Vector3f launchVelocity) {
        this(bx, by, bz, blockType);
        this.velocity.set(launchVelocity);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Update
    // ─────────────────────────────────────────────────────────────────────────
    public void update(float deltaTime, Vector3f playerPos) {
        this.age += deltaTime;

        // ── Physics for ejected debris ────────────────────────────────────────
        // Only applies when an initial launch velocity was given.
        // Once velocity is negligible, the normal lock-on logic takes over.
        float velSq = velocity.lengthSquared();
        if (velSq > 0.01f && !isLockedOn) {
            // Gravity
            velocity.y -= 18f * deltaTime;
            // Integrate
            position.add(new Vector3f(velocity).mul(deltaTime));
            // Air resistance — exponential drag
            float drag = (float)Math.pow(0.80f, deltaTime * 60f);
            velocity.mul(drag);
            // Do NOT do the magnet logic while in ballistic flight
            return;
        }

        // ── Lock-on magnet (original behaviour) ──────────────────────────────
        float dist = position.distance(playerPos);

        if (dist < 3.5f) isLockedOn = true;

        if (isLockedOn) {
            Vector3f target    = new Vector3f(playerPos.x, playerPos.y + 0.9f, playerPos.z);
            Vector3f direction = new Vector3f(target).sub(position).normalize();
            float    pullSpeed = 6.0f / Math.max(0.1f, dist);
            position.add(direction.mul(pullSpeed * deltaTime));
        }
    }
}