package com.leaf.game.core;

import java.util.ArrayList;
import java.util.List;

/**
 * TimeController — global time-scale singleton.
 *
 * Every system that consumes deltaTime multiplies by TimeController.getScale()
 * to respect time dilation. This controller is updated once per frame with raw
 * (unscaled) deltaTime so it can advance its own smooth transition.
 *
 * Usage in game loop:
 *   TimeController tc = TimeController.getInstance();
 *   tc.update(rawDeltaTime);                  // advance transition
 *   float dt = rawDeltaTime * tc.getScale();  // scaled dt for physics
 *
 * Keybindings (set in Window.java):
 *   Hold R → slow motion  (scale → GameConfig.timeSlowScale ≈ 0.15)
 *   Hold Y → fast time    (scale → GameConfig.timeFastScale  ≈ 4.0)
 *   Release → scale returns to 1.0
 *
 * Terrain reshaping, creature AI, and future systems will hook in via the
 * TimeScaleListener interface rather than polling getScale() each frame.
 * That keeps those systems decoupled and allows fine-grained opt-in behaviour
 * (e.g. erosion accelerates at fast time, particle trails slow at slow time).
 */
public class TimeController {

    // ─────────────────────────────────────────────────────────────────────────
    //  Listener interface — register anything that cares about scale changes.
    //  Terrain systems, creature AI, etc. will implement this once they exist.
    // ─────────────────────────────────────────────────────────────────────────

    public interface TimeScaleListener {
        /**
         * Called whenever the time scale meaningfully changes value.
         * @param newScale the new (possibly mid-transition) scale value
         */
        void onTimeScaleChanged(float newScale);
    }

    // ── Singleton ─────────────────────────────────────────────────────────────
    private static final TimeController INSTANCE = new TimeController();

    public static TimeController getInstance() { return INSTANCE; }

    /** Private constructor — use getInstance(). */
    private TimeController() {}

    // ── State ─────────────────────────────────────────────────────────────────
    private float scale       = 1.0f;
    private float targetScale = 1.0f;
    private float prevScale   = 1.0f;   // for change detection

    private final List<TimeScaleListener> listeners = new ArrayList<>();

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Current time scale. Multiply raw deltaTime by this in all physics systems:
     *   Player.update(), World.tickLiquids(), FlightController.update(), etc.
     */
    public float getScale() { return scale; }

    /**
     * Desired target scale. The controller transitions linearly toward it at
     * GameConfig.timeTransitionSpeed units per second of real time.
     */
    public void setTargetScale(float target) {
        this.targetScale = Math.max(0.05f, Math.min(8.0f, target));
    }

    /**
     * Advance the transition. Call once per frame with RAW (unscaled) deltaTime,
     * BEFORE any other system reads getScale() for that frame.
     */
    public void update(float rawDeltaTime) {
        prevScale = scale;

        float diff = targetScale - scale;
        if (Math.abs(diff) < 0.001f) {
            scale = targetScale;
        } else {
            float step = GameConfig.timeTransitionSpeed * rawDeltaTime;
            if (Math.abs(diff) <= step) {
                scale = targetScale;
            } else {
                scale += Math.signum(diff) * step;
            }
        }

        if (scale != prevScale) notifyListeners();
    }

    /**
     * Normalised slowness: 1.0 when fully in slow-mo, 0.0 at normal or above.
     * Used by the fragment shader vignette blend.
     */
    public float getSlownessFactor() {
        if (scale >= 1.0f) return 0.0f;
        // Map [1.0 → timeSlowScale] onto [0 → 1]
        float slowRange = 1.0f - GameConfig.timeSlowScale;
        return Math.min(1.0f, (1.0f - scale) / slowRange);
    }

    /**
     * Normalised fastness: 1.0 when fully in fast-time, 0.0 at normal or below.
     * Used by the fragment shader warm-tint blend.
     */
    public float getFastnessFactor() {
        if (scale <= 1.0f) return 0.0f;
        float fastRange = GameConfig.timeFastScale - 1.0f;
        return Math.min(1.0f, (scale - 1.0f) / fastRange);
    }

    // ── Listener management ──────────────────────────────────────────────────

    public void addListener(TimeScaleListener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(TimeScaleListener l) {
        listeners.remove(l);
    }

    private void notifyListeners() {
        // Iterate over a snapshot to allow listeners to remove themselves safely
        TimeScaleListener[] snapshot = listeners.toArray(new TimeScaleListener[0]);
        for (TimeScaleListener l : snapshot) l.onTimeScaleChanged(scale);
    }

    // ── Convenience reset ────────────────────────────────────────────────────

    /** Instantly snap to normal time (use on game reset / load). */
    public void resetToNormal() {
        scale       = 1.0f;
        targetScale = 1.0f;
        notifyListeners();
    }
}