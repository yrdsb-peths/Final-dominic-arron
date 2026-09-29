package com.leaf.game.core;

import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiWindowFlags;

/**
 * TitleScreen — the first thing a player sees.
 *
 * A full-screen painted scene drawn with ImGui's draw lists (no world is loaded
 * yet): a dusk-to-night sky, a twinkling starfield, the Chakra Crystal hanging
 * over layered voxel mountain ridges that drift in parallax, shards rising like
 * embers, and the DESCENT wordmark in carved Cinzel capitals.
 *
 * {@link #render} returns true on the frame the player presses PLAY.
 */
public final class TitleScreen {

    private float hover = 0f;          // eased 0..1 hover glow on the button
    private float appear = 0f;         // fade-in on first show

    private static final int STARS  = 170;
    private static final int SHARDS = 26;
    private final float[] starX = new float[STARS], starY = new float[STARS],
                          starS = new float[STARS], starP = new float[STARS];
    private final float[] shardX = new float[SHARDS], shardSpd = new float[SHARDS],
                          shardPh = new float[SHARDS], shardSz = new float[SHARDS];

    public TitleScreen() {
        java.util.Random r = new java.util.Random(7);
        for (int i = 0; i < STARS; i++) {
            starX[i] = r.nextFloat();
            starY[i] = (float) Math.pow(r.nextFloat(), 1.6) * 0.62f;   // denser near the top
            starS[i] = 0.6f + r.nextFloat() * (r.nextFloat() < 0.08f ? 2.0f : 0.9f);
            starP[i] = r.nextFloat() * 6.283f;
        }
        for (int i = 0; i < SHARDS; i++) {
            shardX[i] = r.nextFloat();
            shardSpd[i] = 0.018f + r.nextFloat() * 0.035f;
            shardPh[i] = r.nextFloat();
            shardSz[i] = 2.5f + r.nextFloat() * 4.5f;
        }
    }

    private static int col(float r, float g, float b, float a) {
        return ImGui.colorConvertFloat4ToU32(r, g, b, Math.max(0f, Math.min(1f, a)));
    }

    /** Draw the title. @return true when PLAY was clicked this frame. */
    public boolean render(float w, float h, float dt, float t) {
        appear = Math.min(1f, appear + dt * 0.8f);
        float A = appear * appear * (3f - 2f * appear);

        ImDrawList bg = ImGui.getBackgroundDrawList();

        // ── Sky: deep indigo zenith → violet → ember horizon ──────────────────
        float hz = h * 0.70f;
        bg.addRectFilledMultiColor(0, 0, w, hz * 0.55f,
                col(0.02f, 0.02f, 0.07f, 1), col(0.02f, 0.02f, 0.07f, 1),
                col(0.09f, 0.05f, 0.18f, 1), col(0.09f, 0.05f, 0.18f, 1));
        bg.addRectFilledMultiColor(0, hz * 0.55f, w, hz,
                col(0.09f, 0.05f, 0.18f, 1), col(0.09f, 0.05f, 0.18f, 1),
                col(0.55f, 0.20f, 0.18f, 1), col(0.55f, 0.20f, 0.18f, 1));
        bg.addRectFilled(0, hz, w, h, col(0.03f, 0.02f, 0.05f, 1));

        // ── Stars ─────────────────────────────────────────────────────────────
        for (int i = 0; i < STARS; i++) {
            float tw = 0.55f + 0.45f * (float) Math.sin(t * (0.8f + starS[i]) + starP[i]);
            float a = tw * (1f - starY[i] / 0.7f) * A;
            bg.addCircleFilled(starX[i] * w, starY[i] * h, starS[i], col(0.85f, 0.88f, 1f, a), 6);
        }

        // ── The Chakra Crystal: a slow-breathing violet diamond with a halo ───
        float cx = w * 0.5f, cy = h * 0.34f;
        float pulse = 0.5f + 0.5f * (float) Math.sin(t * 1.3f);
        float cs = Math.min(w, h) * 0.075f;
        for (int k = 22; k >= 1; k--) {                      // soft halo (many faint rings = no banding)
            float rr = cs * (1.0f + k * 0.20f + pulse * 0.25f);
            bg.addCircleFilled(cx, cy, rr, col(0.55f, 0.35f, 1.0f, 0.016f * A), 64);
        }
        float bob = (float) Math.sin(t * 0.9f) * cs * 0.12f;
        float spin = (float) Math.sin(t * 0.45f) * 0.35f;   // fake 3D turn: squash the width
        float cw = cs * (0.62f + 0.18f * (float) Math.cos(spin * 3f));
        float top = cy - cs * 1.55f + bob, mid = cy - cs * 0.25f + bob, bot = cy + cs * 1.35f + bob;
        // two facets (left darker, right lighter) → reads as a cut gem
        bg.addTriangleFilled(cx, top, cx - cw, mid, cx, bot, col(0.42f, 0.24f, 0.85f, 0.95f * A));
        bg.addTriangleFilled(cx, top, cx + cw, mid, cx, bot, col(0.68f, 0.50f, 1.00f, 0.95f * A));
        bg.addTriangleFilled(cx, top, cx - cw, mid, cx + cw, mid, col(0.86f, 0.78f, 1.0f, (0.55f + 0.35f * pulse) * A));
        bg.addLine(cx, top, cx, bot, col(1f, 0.95f, 1f, 0.55f * A), 1.2f);
        bg.addLine(cx - cw, mid, cx + cw, mid, col(1f, 0.95f, 1f, 0.35f * A), 1f);

        // ── Rising shards (embers of the crystal) ─────────────────────────────
        for (int i = 0; i < SHARDS; i++) {
            float p = (shardPh[i] + t * shardSpd[i]) % 1f;
            float x = shardX[i] * w + (float) Math.sin(t * 0.7f + i) * 18f;
            float y = h * (1.02f - p * 0.95f);
            float a = (float) Math.sin(p * Math.PI) * 0.75f * A;
            float s = shardSz[i];
            bg.addTriangleFilled(x, y - s * 1.6f, x - s * 0.55f, y, x + s * 0.55f, y,
                    col(0.78f, 0.62f, 1.0f, a));
            bg.addTriangleFilled(x - s * 0.55f, y, x + s * 0.55f, y, x, y + s,
                    col(0.52f, 0.36f, 0.95f, a));
        }

        // ── Voxel mountain ridges: stepped silhouettes, three depths, parallax ─
        ridge(bg, w, h, hz - h * 0.10f, h * 0.16f, 34f, t * 6f, 11, col(0.20f, 0.10f, 0.22f, 1));
        ridge(bg, w, h, hz - h * 0.03f, h * 0.13f, 26f, t * 11f, 23, col(0.11f, 0.06f, 0.14f, 1));
        ridge(bg, w, h, hz + h * 0.05f, h * 0.10f, 20f, t * 18f, 41, col(0.045f, 0.03f, 0.07f, 1));
        // Valley mist rolling over the nearest ridge
        bg.addRectFilledMultiColor(0, hz, w, h,
                col(0.30f, 0.16f, 0.30f, 0.0f), col(0.30f, 0.16f, 0.30f, 0.0f),
                col(0.02f, 0.015f, 0.04f, 0.9f), col(0.02f, 0.015f, 0.04f, 0.9f));

        // Horizon glow bleeding over the ridges
        bg.addRectFilledMultiColor(0, hz - h * 0.2f, w, hz + h * 0.02f,
                col(0.9f, 0.35f, 0.25f, 0f), col(0.9f, 0.35f, 0.25f, 0f),
                col(0.9f, 0.35f, 0.25f, 0.10f * A), col(0.9f, 0.35f, 0.25f, 0.10f * A));

        // ── Wordmark ──────────────────────────────────────────────────────────
        ImDrawList fg = ImGui.getForegroundDrawList();
        String title = "D E S C E N T";
        float ts = Math.min(w * 0.085f, 96f);
        float tw = UiFonts.width(UiFonts.display, ts, title);
        float tx = cx - tw / 2f, ty = h * 0.53f;
        for (int k = 3; k >= 1; k--)                        // warm glow behind the letters
            fg.addText(UiFonts.display, ts, tx, ty + k * 1.5f, col(0.9f, 0.45f, 0.15f, 0.10f * A), title);
        fg.addText(UiFonts.display, ts, tx + 2, ty + 3, col(0.05f, 0.02f, 0.06f, 0.8f * A), title);
        fg.addText(UiFonts.display, ts, tx, ty, col(1.0f, 0.86f, 0.52f, A), title);

        String tag = "FIGHT  ·  FLY  ·  FORGE";
        float gs = Math.max(18f, ts * 0.26f);
        float gw = UiFonts.width(UiFonts.body, gs, tag);
        fg.addText(UiFonts.body, gs, cx - gw / 2f, ty + ts * 1.08f, col(0.80f, 0.78f, 0.95f, 0.85f * A), tag);

        // ── PLAY button (custom-drawn; an invisible ImGui button catches input) ─
        float bw = 280f, bh = 54f;
        float bx = cx - bw / 2f, by = ty + ts * 1.08f + gs + 34f;
        ImGui.setNextWindowPos(0, 0);
        ImGui.setNextWindowSize(w, h);
        ImGui.begin("##title", ImGuiWindowFlags.NoDecoration | ImGuiWindowFlags.NoBackground
                | ImGuiWindowFlags.NoMove | ImGuiWindowFlags.NoSavedSettings
                | ImGuiWindowFlags.NoBringToFrontOnFocus);
        ImGui.setCursorPos(bx, by);
        boolean clicked = ImGui.invisibleButton("##play", bw, bh);
        boolean hov = ImGui.isItemHovered();
        ImGui.end();

        hover += ((hov ? 1f : 0f) - hover) * Math.min(1f, dt * 10f);
        float glow = 0.35f + 0.65f * hover;
        for (int k = 4; k >= 1; k--) {
            float g = k * 3f * (0.4f + hover);
            fg.addRectFilled(bx - g, by - g, bx + bw + g, by + bh + g,
                    col(0.95f, 0.55f, 0.2f, 0.035f * glow * A), 10f + g);
        }
        fg.addRectFilled(bx, by, bx + bw, by + bh, col(0.10f, 0.06f, 0.12f, (0.72f + 0.2f * hover) * A), 8f);
        fg.addRect(bx, by, bx + bw, by + bh, col(1.0f, 0.78f, 0.42f, (0.55f + 0.45f * hover) * A), 8f, 0, 1.6f);
        String play = "BEGIN THE DESCENT";
        float ps = 24f;
        float pw = UiFonts.width(UiFonts.bold, ps, play);
        fg.addText(UiFonts.bold, ps, cx - pw / 2f, by + (bh - ps) / 2f - 1f,
                col(1.0f, 0.93f - 0.1f * hover, 0.78f - 0.2f * hover, A), play);

        String hint = "Survive six waves. Each one wakes a new power.";
        float hs = 17f;
        float hw = UiFonts.width(UiFonts.body, hs, hint);
        fg.addText(UiFonts.body, hs, cx - hw / 2f, by + bh + 18f, col(0.62f, 0.58f, 0.74f, 0.8f * A), hint);

        return clicked;
    }

    /**
     * A stepped, blocky ridge line — the voxel world in silhouette. Heights come
     * from a couple of summed sines (seeded per layer), quantised to "block" steps.
     */
    private static void ridge(ImDrawList dl, float w, float h, float baseY, float amp,
                              float block, float scroll, int seed, int colour) {
        float off = scroll % block;
        int n = (int) (w / block) + 3;
        for (int i = -1; i < n; i++) {
            float x0 = i * block - off;
            float u = (i + (float) Math.floor(scroll / block)) * 0.13f + seed;
            float v = (float) (Math.sin(u) * 0.55 + Math.sin(u * 2.3 + seed) * 0.30 + Math.sin(u * 5.1) * 0.15);
            float hh = amp * (0.55f + 0.45f * v);
            hh = Math.round(hh / (block * 0.5f)) * (block * 0.5f);   // snap to half-block steps
            dl.addRectFilled(x0, baseY - hh, x0 + block + 1f, h, colour);   // solid to the bottom: no sky gaps
        }
    }
}
