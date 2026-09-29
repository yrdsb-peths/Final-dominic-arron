package com.leaf.game.core;

import imgui.ImFont;
import imgui.ImFontConfig;
import imgui.ImFontAtlas;
import imgui.ImGui;

import java.io.InputStream;

/**
 * UiFonts — the game's typography.
 *
 * <ul>
 *   <li>{@link #body}    Rajdhani SemiBold — clean, slightly technical HUD text (the default font).</li>
 *   <li>{@link #bold}    Rajdhani Bold — numbers, labels that need punch (damage numbers, keys).</li>
 *   <li>{@link #display} Cinzel Bold — carved, mythic capitals for titles and big reveals.</li>
 * </ul>
 * Both families are SIL Open Font License (see resources/fonts/OFL-*.txt).
 *
 * Fonts are rasterised at a generous size and drawn scaled down with
 * {@code ImDrawList.addText(font, size, ...)} so big titles stay crisp.
 * Must be called after {@code ImGui.createContext()} and before the ImGui GL
 * renderer builds its font texture. If a file is missing, ImGui's built-in
 * font is used and nothing else changes.
 */
public final class UiFonts {

    public static ImFont body, bold, display;

    private UiFonts() {}

    public static void load() {
        ImFontAtlas atlas = ImGui.getIO().getFonts();
        body    = add(atlas, "/fonts/Rajdhani-SemiBold.ttf", 19f);
        bold    = add(atlas, "/fonts/Rajdhani-Bold.ttf",     40f);
        display = add(atlas, "/fonts/Cinzel-Bold.ttf",       64f);
        ImFont fallback = null;
        if (body != null) ImGui.getIO().setFontDefault(body);
        else              fallback = atlas.addFontDefault();
        if (body == null)    body    = fallback;
        if (bold == null)    bold    = body;
        if (display == null) display = bold;
    }

    private static ImFont add(ImFontAtlas atlas, String res, float px) {
        try (InputStream in = UiFonts.class.getResourceAsStream(res)) {
            if (in == null) return null;
            byte[] data = in.readAllBytes();
            ImFontConfig cfg = new ImFontConfig();
            cfg.setOversampleH(2);
            cfg.setOversampleV(2);
            cfg.setPixelSnapH(false);
            ImFont f = atlas.addFontFromMemoryTTF(data, px, cfg);
            cfg.destroy();
            return f;
        } catch (Exception e) {
            System.err.println("[UiFonts] Could not load " + res + ": " + e.getMessage());
            return null;
        }
    }

    /** Font-aware text width helper. */
    public static float width(ImFont f, float size, String text) {
        return f.calcTextSizeAX(size, Float.MAX_VALUE, 0f, text);
    }
}
