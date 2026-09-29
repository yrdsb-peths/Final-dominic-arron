package com.leaf.game.core;

import java.util.ArrayList;
import java.util.List;

/**
 * CombatFeedback — one place every enemy hit is reported, so "hit feel" is
 * consistent no matter which of the 20+ abilities landed it.
 *
 * <p>{@code Enemy.applyDamage} publishes a {@link Hit}; the render loop drains
 * the queue once per frame and turns each hit into sparks, a floating damage
 * number, a kill burst and (for heavy hits) a few frames of hit-stop. Abilities
 * don't need to know any of this exists.
 */
public final class CombatFeedback {

    /** A single damage event, in world space. */
    public static final class Hit {
        public final int     enemyId;
        public final float   x, y, z;      // centre of the enemy's body
        public final float   halfHeight;   // body size (bigger enemies → bigger bursts)
        public final float   amount;
        public final boolean killed;

        Hit(int enemyId, float x, float y, float z, float halfHeight, float amount, boolean killed) {
            this.enemyId = enemyId; this.x = x; this.y = y; this.z = z;
            this.halfHeight = halfHeight; this.amount = amount; this.killed = killed;
        }
    }

    private static final List<Hit> pending = new ArrayList<>();

    private CombatFeedback() {}

    public static synchronized void publish(int enemyId, float x, float y, float z,
                                            float halfHeight, float amount, boolean killed) {
        if (amount <= 0f && !killed) return;
        if (pending.size() > 512) return;   // safety valve (e.g. a runaway DoT)
        pending.add(new Hit(enemyId, x, y, z, halfHeight, amount, killed));
    }

    /** Everything published since the last drain, oldest first. */
    public static synchronized List<Hit> drain() {
        if (pending.isEmpty()) return List.of();
        List<Hit> out = new ArrayList<>(pending);
        pending.clear();
        return out;
    }
}
