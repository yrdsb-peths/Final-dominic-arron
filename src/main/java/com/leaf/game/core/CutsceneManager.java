package com.leaf.game.core;

import imgui.ImFont;
import imgui.ImGui;

/**
 * CutsceneManager  -  in-engine letterbox text cutscenes for DESCENT.
 *
 * Cinematic-on-a-budget: black letterbox bars, a dimmed view of the world behind,
 * text that types in character-by-character, and a wind bed howling underneath.
 * No video files, no extra dependencies  -  and the ending can show live run stats.
 *
 * ── HOW TO EDIT THE CUTSCENES ────────────────────────────────────────────────
 *  Everything you'd want to change is right here at the top:
 *    INTRO / ENDING : the slides. Each inner array is ONE slide (advanced with
 *                     SPACE); each String in it is a line on that slide.
 *    CPS            : typewriter speed (characters per second).
 *    WIND_SOUND     : which /audios file plays underneath (wind_cemetery, wind_big…).
 *  A line that is exactly "DESCENT" is rendered as the large glowing title.
 *
 * ── CONTROLS ─────────────────────────────────────────────────────────────────
 *  SPACE / ENTER : finish the current line instantly, or advance to the next slide.
 *  ESC           : skip the whole cutscene.
 */
public class CutsceneManager {

    // ═══════════════════════════════════════════════════════════════════════
    //  EDIT YOUR CUTSCENES HERE
    // ═══════════════════════════════════════════════════════════════════════
    public static final String[][] INTRO = {
        { "The Chakra Crystal is yours now.",
          "And everything on this mountain wants it back." },
        { "FIGHT the waves.",
          "Every wave you survive, the crystal grants a NEW POWER." },
        { "Survive six  -  and it will give you the sky." },
        { "DESCENT",
          "Fight.   Fly.   Forge." },
    };

    /**
     * Played on every death before the player is revived.
     * Auto-advances: no ENTER needed, the last slide fades out and the
     * caller restores the player automatically when isActive() returns false.
     */
    public static final String[][] REVIVAL = {
        { "The crystal catches you.",
          "It is not done with you yet." },
        { "You feel cold stone  -  and then warmth.",
          "You are back." },
    };

    /**
     * Played when the 3rd death triggers the Kamui awakening.
     * Comes BEFORE the normal revival cutscene.
     */
    public static final String[][] KAMUI_AWAKEN = {
        { "Three times the darkness claimed you.",
          "Three times the crystal refused to let go." },
        { "But something else happened at the threshold.",
          "A part of you stopped coming back." },
        { "It drifted  -  sideways.",
          "Into a space that has no name." },
        { "You feel it now.",
          "A dimension folded inside your own body." },
        { "KAMUI",
          "Press [ Z ] to phase into the void.",
          "While inside: nothing can touch you." },
    };

    public static final String[][] ENDING = {
        { "You survived.",
          "Every wave. Every hunter. Everything it sent." },
        { "And now  -  the sky opens.",
          "FLIGHT. The last thing the crystal ever gives." },
        { "The mountain has nothing left to hold you." },
        { "Double-tap  [ SPACE ]  to take flight.",
          "The world is yours." },
        { "DESCENT",
          "You are free.  Go anywhere." },
    };

    /** Typewriter speed (characters revealed per second). */
    private static final float CPS = 30f;
    /** Wind bed that plays under the cutscene (path under /audios, no extension). */
    private static final String WIND_SOUND = "wind/wind_cemetery";
    private static final float  WIND_VOLUME = 0.6f;

    // ═══════════════════════════════════════════════════════════════════════

    /** Identifies which script is currently running — read by Window to know what to do on end. */
    public enum Kind { INTRO, REVIVAL, KAMUI_AWAKEN, ENDING }

    private String[][] script = null;
    private int     slide      = 0;
    private float   typed      = 0f;
    private int     slideChars = 0;
    private boolean active     = false;
    private Kind    kind       = Kind.INTRO;
    /**
     * When true, the cutscene auto-advances each slide after a fixed pause
     * (used for revival — player shouldn't need to press ENTER just to respawn).
     */
    private boolean autoAdvance = false;
    private static final float AUTO_SLIDE_PAUSE = 2.2f;  // seconds per slide in auto mode

    private static final float SLIDE_MIN_SHOW = 0.9f;
    private float   slideAge   = 0f;
    private float   windFade   = 0f;
    private float   age        = 0f;
    private float   promptT    = 0f;

    /** Optional stat line shown on the ENDING. */
    public String endingStat = null;

    public void startIntro()       { begin(INTRO,        Kind.INTRO,       false); }
    public void startRevival()     { begin(REVIVAL,      Kind.REVIVAL,     true);  }
    public void startKamuiAwaken() { begin(KAMUI_AWAKEN, Kind.KAMUI_AWAKEN,false); }
    public void startEnding()      { begin(ENDING,       Kind.ENDING,      false); }

    private void begin(String[][] s, Kind k, boolean auto) {
        script = s; slide = 0; typed = 0f; active = true; kind = k; autoAdvance = auto;
        windFade = 0f; age = 0f; promptT = 0f; slideAge = 0f;
        slideChars = charCount(0);
        AudioManager.playContinuous(WIND_SOUND, 0f);
    }

    public boolean isActive()   { return active; }
    public Kind    getKind()    { return kind;   }
    @Deprecated
    public boolean wasEnding()  { return kind == Kind.ENDING; }

    // ── Per-frame update ──────────────────────────────────────────────────────
    public void update(float dt) {
        if (!active) return;
        age      += dt;
        promptT  += dt;
        slideAge += dt;
        windFade  = Math.min(1f, windFade + dt * 0.6f);
        AudioManager.setContinuousVolume(WIND_SOUND, WIND_VOLUME * windFade);
        if (typed < slideChars) typed = Math.min(slideChars, typed + CPS * dt);

        // Auto-advance mode: scroll through slides automatically (revival cutscene).
        if (autoAdvance && typed >= slideChars && slideAge >= AUTO_SLIDE_PAUSE) {
            slide++;
            if (slide >= script.length) { end(); return; }
            typed = 0f; slideAge = 0f;
            slideChars = charCount(slide);
        }
    }

    /**
     * ENTER: complete the current typewriter, or move to the next slide.
     * A per-slide minimum display time prevents accidental skipping  -  the
     * player often has keys held from gameplay when a cutscene opens.
     */
    public void advance() {
        if (!active) return;
        // First press finishes typing; only then (and after min-show time) advance.
        if (typed < slideChars) {
            if (slideAge < SLIDE_MIN_SHOW * 0.4f) return; // too soon  -  ignore
            typed = slideChars;
            return;
        }
        if (slideAge < SLIDE_MIN_SHOW) return;  // must have been visible long enough
        slide++;
        if (slide >= script.length) { end(); return; }
        typed = 0f; slideAge = 0f;
        slideChars = charCount(slide);
        promptT = 0f;
        AudioManager.play("cystal_click", 0.4f);
    }

    /** ESC: skip the whole thing. */
    public void skip() { end(); }

    // Space double-tap tracking for ending exit
    private float lastSpaceTapTime = -99f;
    /**
     * Called when SPACE is pressed during the ENDING cutscene.
     * Two taps within 0.6 s on the final slide exits the scene — matching the
     * on-screen hint "Double-tap [SPACE] to take flight."
     */
    public void tapSpaceForEnding() {
        if (kind != Kind.ENDING) return;
        boolean onLastSlide = slide >= script.length - 1;
        if (!onLastSlide) { advance(); return; }   // on earlier slides, advance normally
        if (age - lastSpaceTapTime <= 0.6f) {
            end();   // second tap within window — exit and let the player fly
        } else {
            lastSpaceTapTime = age;  // first tap — wait for the second
        }
    }

    private void end() {
        active = false;
        AudioManager.stopContinuous(WIND_SOUND);
    }

    private int charCount(int s) {
        int n = 0;
        for (String line : script[s]) n += line.length();
        return n;
    }

    // ── Render (call inside the ImGui frame) ─────────────────────────────────
    public void render(float w, float h) {
        if (!active) return;
        imgui.ImDrawList draw = ImGui.getForegroundDrawList();

        // Dim the world, then lay cinematic letterbox bars over the top/bottom.
        draw.addRectFilled(0, 0, w, h, ImGui.colorConvertFloat4ToU32(0f, 0f, 0f, 0.80f));
        float bar = h * 0.155f;
        int black = ImGui.colorConvertFloat4ToU32(0f, 0f, 0f, 1f);
        draw.addRectFilled(0, 0, w, bar, black);
        draw.addRectFilled(0, h - bar, w, h, black);

        ImFont font = ImGui.getFont();
        float base = ImGui.getFontSize();
        ImFont story = UiFonts.display;   // Cinzel: carved, mythic small-caps for the narration

        String[] lines = script[slide];
        boolean titleSlide = lines.length > 0 && lines[0].equals("DESCENT");

        // Pre-measure block height so the text sits centred between the bars.
        float bodySize  = Math.max(24f, h * 0.040f);
        float titleSize = Math.max(56f, h * 0.10f);
        float gap = 18f;
        float blockH = 0f;
        for (String line : lines) {
            boolean t = line.equals("DESCENT");
            blockH += (t ? titleSize : bodySize) + gap;
        }
        float y = h * 0.5f - blockH * 0.5f;

        int budget = (int) typed;   // characters allowed to show this frame
        for (String line : lines) {
            boolean t = line.equals("DESCENT");
            float size = t ? titleSize : bodySize;
            int take = Math.max(0, Math.min(line.length(), budget));
            budget -= line.length();
            String shown = line.substring(0, take);

            if (!shown.isEmpty()) {
                float tw = UiFonts.width(story, size, shown);
                float x = (w - tw) * 0.5f;
                if (t) {
                    // Glowing title: a soft pulsing halo drawn behind the crisp text.
                    float glow = 0.45f + 0.25f * (float) Math.sin(age * 2.2f);
                    int halo = ImGui.colorConvertFloat4ToU32(0.85f, 0.55f, 1.0f, glow);
                    for (int o = 1; o <= 3; o++) {
                        draw.addText(story, size, x - o, y,     halo, shown);
                        draw.addText(story, size, x + o, y,     halo, shown);
                        draw.addText(story, size, x,     y - o, halo, shown);
                        draw.addText(story, size, x,     y + o, halo, shown);
                    }
                    draw.addText(story, size, x, y, ImGui.colorConvertFloat4ToU32(1f, 0.90f, 0.62f, 1f), shown);
                } else {
                    // Body: drop shadow + soft white for legibility over the world.
                    draw.addText(story, size, x + 2, y + 2, ImGui.colorConvertFloat4ToU32(0f, 0f, 0f, 0.9f), shown);
                    draw.addText(story, size, x, y, ImGui.colorConvertFloat4ToU32(0.94f, 0.90f, 0.84f, 1f), shown);
                }
            }
            y += size + gap;
        }

        // Optional stat line on the ending.
        if (kind == Kind.ENDING && endingStat != null && typed >= slideChars) {
            float tw = ImGui.calcTextSize(endingStat).x;
            draw.addText(font, base, (w - tw) * 0.5f, h - bar - 40f,
                    ImGui.colorConvertFloat4ToU32(0.8f, 0.6f, 0.55f, 0.95f), endingStat);
        }

        // Crystal glow effect: a pulsing radial bloom in the centre of the screen.
        // Shown on REVIVAL (warm amber) and KAMUI_AWAKEN (cold purple/void).
        if (kind == Kind.REVIVAL || kind == Kind.KAMUI_AWAKEN) {
            float pulse = 0.18f + 0.12f * (float) Math.sin(age * 4.5f);
            boolean revival = (kind == Kind.REVIVAL);
            int glowCol = revival
                ? ImGui.colorConvertFloat4ToU32(1.0f, 0.85f, 0.35f, pulse)   // warm crystal amber
                : ImGui.colorConvertFloat4ToU32(0.55f, 0.0f, 0.9f,  pulse);  // void purple
            // Layered bloom — bigger & softer each pass
            for (int r = 220; r >= 40; r -= 30) {
                draw.addCircleFilled(w * 0.5f, h * 0.5f, r, glowCol, 48);
            }
        }

        // ENTER/Space prompt — drawn ABOVE the bottom letterbox bar so it's actually visible.
        // Hidden on auto-advance (revival cutscene doesn't need a prompt).
        if (!autoAdvance && typed >= slideChars && slideAge >= SLIDE_MIN_SHOW) {
            float a = 0.5f + 0.4f * (float) Math.sin(promptT * 3.5f);   // brighter flash
            boolean last = slide >= script.length - 1;
            String prompt = (last && kind == Kind.ENDING)
                    ? "double-tap  [ SPACE ]  to fly"
                    : "[ ENTER ]";
            float tw = ImGui.calcTextSize(prompt).x * 1.2f;   // scaled up
            float px = (w - tw) * 0.5f;
            float py = h - bar - 36f;  // above the bar, not inside it
            // Shadow
            draw.addText(font, base * 1.2f, px + 2, py + 2,
                    ImGui.colorConvertFloat4ToU32(0f, 0f, 0f, a * 0.9f), prompt);
            // Bright text
            draw.addText(font, base * 1.2f, px, py,
                    ImGui.colorConvertFloat4ToU32(0.9f, 0.93f, 1.0f, a), prompt);
        }

        // Skip hint — inside the bar (small, unobtrusive), not on revival.
        if (!autoAdvance) {
            draw.addText(font, base, 14f, h - bar + 10f,
                    ImGui.colorConvertFloat4ToU32(0.55f, 0.57f, 0.65f, 0.55f), "[ESC] skip");
        }
    }
}
