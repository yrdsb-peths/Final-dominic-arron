package com.leaf.game.core;

import imgui.ImGui;

/**
 * Central manager for short-lived screen effects.
 *
 * ── Effects ──────────────────────────────────────────────────────────────────
 *
 *  HIT-STOP
 *    Call hitStop(frames) on a powerful impact.  The game loop multiplies
 *    deltaTime by getHitStopScale() — which returns 0 for the freeze, 1 normally.
 *    Typically 2-4 frames (0.03-0.07 s at 60 fps) for melee; 1 frame for ranged.
 *
 *  SCREEN FLASH
 *    Call flash(r, g, b, alpha, durationSeconds) for an impact overlay:
 *      flash(1, 0, 0, 0.6, 0.08)  →  red flash   (snipe kill)
 *      flash(0, 0, 0, 0.9, 0.05)  →  blackout     (explosion)
 *      flash(1, 1, 1, 0.8, 0.04)  →  white flash  (melee hit)
 *    The alpha fades linearly over the duration.
 *    Predefined shortcuts: flashSnipe(), flashExplosion(), flashMeleeHit().
 *
 *  DESATURATE
 *    Call desaturate(amount, durationSeconds) to drain color from the scene.
 *    amount = 1.0 is full black-and-white; 0.0 is no change.
 *    The main fragment.glsl reads getDesaturate() as the "desaturate" uniform.
 *    Decays linearly.  Good for big explosions, near-death, slow-motion.
 *
 * ── Integration ──────────────────────────────────────────────────────────────
 *
 *  In Window.java game loop (tick each frame):
 *    ScreenEffectManager sfx = ScreenEffectManager.INSTANCE;
 *    sfx.tick(rawDeltaTime);                          // always raw time
 *    float dt = rawDeltaTime * sfx.getHitStopScale(); // modified deltaTime
 *
 *  In fragment.glsl render pass:
 *    shader.setUniform("desaturate", sfx.getDesaturate());
 *
 *  In WindowHud.renderHUD (after ImGui.newFrame):
 *    sfx.renderFlash(screenW, screenH);
 */
public class ScreenEffectManager {

    public static final ScreenEffectManager INSTANCE = new ScreenEffectManager();
    private ScreenEffectManager() {}

    // ── Hit-stop ─────────────────────────────────────────────────────────────

    private float hitStopRemaining = 0f;  // in seconds

    /** Freeze the game for the given number of frames (at 60fps). */
    public void hitStop(int frames) {
        hitStopRemaining = Math.max(hitStopRemaining, frames / 60f);
    }

    /** Freeze for an explicit duration in seconds. */
    public void hitStopSeconds(float seconds) {
        hitStopRemaining = Math.max(hitStopRemaining, seconds);
    }

    /** Returns 0 while frozen, 1 otherwise.  Multiply your deltaTime by this. */
    public float getHitStopScale() {
        return hitStopRemaining > 0f ? 0f : 1f;
    }

    // ── Screen flash ──────────────────────────────────────────────────────────

    private float flashR, flashG, flashB;
    private float flashAlpha    = 0f;
    private float flashDuration = 0f;
    private float flashElapsed  = 0f;

    public void flash(float r, float g, float b, float alpha, float durationSeconds) {
        flashR        = r;
        flashG        = g;
        flashB        = b;
        flashAlpha    = alpha;
        flashDuration = durationSeconds;
        flashElapsed  = 0f;
    }

    // Predefined presets
    /** Red tint — use for snipe kills / headshots */
    public void flashSnipe()     { flash(0.9f, 0.05f, 0.05f, 0.55f, 0.10f); }
    /** Deep black — use for large explosions */
    public void flashExplosion() { flash(0f,   0f,    0f,    0.45f, 0.06f); }
    /** Bright white — use for melee heavy hits */
    public void flashMeleeHit()  { flash(1f,   0.95f, 0.85f, 0.22f, 0.05f); }
    /** Orange — use for fire / grab slam */
    public void flashGrabSlam()  { flash(1f,   0.5f,  0f,    0.50f, 0.12f); }
    /** Healing green */
    public void flashHeal()      { flash(0.2f, 1f,    0.4f,  0.30f, 0.20f); }

    // ── Desaturate ────────────────────────────────────────────────────────────

    private float desaturate         = 0f;
    private float desaturateDuration = 0f;
    private float desaturateElapsed  = 0f;
    private float desaturateStart    = 0f;

    /** 0=full colour, 1=greyscale, decays linearly over durationSeconds */
    public void desaturate(float amount, float durationSeconds) {
        desaturateStart    = amount;
        desaturate         = amount;
        desaturateDuration = durationSeconds;
        desaturateElapsed  = 0f;
    }

    /** Returns the current desaturation value 0..1 for the shader. */
    public float getDesaturate() { return desaturate; }

    // ── Tick (call every frame with RAW deltaTime) ─────────────────────────────

    public void tick(float rawDt) {
        // Hit-stop
        if (hitStopRemaining > 0f) hitStopRemaining = Math.max(0f, hitStopRemaining - rawDt);

        // Flash decay
        if (flashDuration > 0f) {
            flashElapsed += rawDt;
            float t = Math.min(1f, flashElapsed / flashDuration);
            flashAlpha = flashAlpha * (1f - t);   // fade linearly
            if (flashElapsed >= flashDuration) flashDuration = 0f;
        }

        // Desaturate decay
        if (desaturateDuration > 0f) {
            desaturateElapsed += rawDt;
            float t = Math.min(1f, desaturateElapsed / desaturateDuration);
            desaturate = desaturateStart * (1f - t);
            if (desaturateElapsed >= desaturateDuration) { desaturateDuration = 0f; desaturate = 0f; }
        }
    }

    // ── HUD render (call inside ImGui frame, after ImGui.newFrame()) ──────────

    public void renderFlash(float screenW, float screenH) {
        if (flashAlpha < 0.005f) return;
        var draw = ImGui.getForegroundDrawList();
        int col = ImGui.colorConvertFloat4ToU32(flashR, flashG, flashB, flashAlpha);
        draw.addRectFilled(0, 0, screenW, screenH, col);
    }
}
