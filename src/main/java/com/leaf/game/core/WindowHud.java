package com.leaf.game.core;

import com.leaf.game.entity.*;
import com.leaf.game.net.NetworkSession;
import com.leaf.game.render.Shader;
import com.leaf.game.util.Camera;
import com.leaf.game.util.RaycastResult;
import com.leaf.game.world.Block;
import com.leaf.game.world.Chunk;
import imgui.ImFont;
import imgui.ImGui;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

/**
 * WindowHud  -  all ImGui HUD / menu rendering extracted from Window.java.
 *
 * Every method reads game state via the {@code win} back-reference.
 * Fields accessed as {@code win.fieldName} are package-private in Window.java
 * (no access modifier  -  same-package visibility is sufficient).
 */
class WindowHud {

    final Window win;

    WindowHud(Window win) {
        this.win = win;
    }

    /**
     * Flip to true to expose the developer options (load game, multiplayer,
     * pre-gen radius). The shipping start screen shows only "Play Game".
     * The code below is kept intact so nothing is lost  -  just hidden.
     */
    private static final boolean DEV_MENU = false;

    void renderConnectionMenu(float w, float h) {
        ImGui.setNextWindowPos(w / 2.0f - 170.0f, h / 2.0f - 130.0f);
        ImGui.setNextWindowSize(340.0f, DEV_MENU ? 400.0f : 240.0f);
        ImGui.begin("Start Screen",
                imgui.flag.ImGuiWindowFlags.NoDecoration | imgui.flag.ImGuiWindowFlags.NoMove);

        ImGui.spacing();
        ImGui.textColored(1.0f, 0.85f, 0.4f, 1.0f, "        D E S C E N T");
        ImGui.spacing();
        ImGui.textDisabled("        Survive. Learn. Escape.");
        ImGui.spacing(); ImGui.separator(); ImGui.spacing(); ImGui.spacing();

        // ── The one shipping button  -  starts the guided, story-paced run ─────────
        if (ImGui.button("PLAY", 320, 44)) {
            win.network = null;
            win.playIntroOnSpawn   = true;
            win.armPlaytestOnSpawn = false;  // natural progression: earn powers wave by wave
            win.gameEnded = false;
            win.player.progression.reset();
            RunRecords.INSTANCE.newRun((float) org.lwjgl.glfw.GLFW.glfwGetTime());
            win.startPreload();
        }
        ImGui.spacing();
        ImGui.textDisabled("   Survive. The crystal grants a new power every wave.");
        ImGui.spacing();

        // ── Developer options (hidden unless DEV_MENU)  -  kept in code ─────────
        if (DEV_MENU) {
            ImGui.separator(); ImGui.spacing();
            ImGui.text("Pre-generate Radius:");
            int[] rad = {win.preloadRadius};
            if (ImGui.sliderInt("##rad", rad, 0, 100)) win.preloadRadius = rad[0];
            ImGui.spacing();

            if (ImGui.button("Single Player (no intro)", 320, 28)) {
                win.network = null;
                win.startPreload();
            }
            ImGui.spacing();
            if (SaveManager.saveExists() && ImGui.button("Load Saved Game", 320, 28)) {
                java.util.Arrays.fill(win.hotbar, Block.AIR);
                SaveManager.loadGame(win.world, win.player, win.inventory);
                for (Block b : Block.values()) {
                    if (win.inventory.getCount(b) > 0) win.addBlockToHotbar(b);
                }
                win.worldGen.resetSeed(GameConfig.seed);
                win.world.clearAllChunks();
                win.network = null;
                win.startPreload();
            }
            ImGui.spacing();
            if (ImGui.button("Host Multiplayer Game", 320, 28)) {
                win.network = new NetworkSession(true, null);
                win.network.start();
                win.remotePlayer = new RemotePlayer();
                win.startPreload();
            }
            ImGui.spacing();
            ImGui.inputText("Host IP", win.ipInput);
            if (ImGui.button("Join Multiplayer Game", 320, 28)) {
                win.network = new NetworkSession(false, win.ipInput.get().trim());
                win.network.start();
                win.remotePlayer = new RemotePlayer();
                win.startPreload();
            }
        }
        ImGui.end();
    }

    /** Shared host/join setup: connect, unlock everything, and start the world. */
    private void startMultiplayer(boolean host) {
        win.network = host ? new NetworkSession(true, null)
                           : new NetworkSession(false, win.ipInput.get().trim());
        win.network.start();
        win.remotePlayer     = new RemotePlayer();
        win.playIntroOnSpawn = false;            // skip the intro cutscene in MP
        win.gameEnded        = false;
        win.player.progression.unlockAll();      // PvP: both players fully armed
        RunRecords.INSTANCE.newRun((float) org.lwjgl.glfw.GLFW.glfwGetTime());
        win.startPreload();
    }

    void renderPreloadProgress(float w, float h) {
        ImGui.setNextWindowPos(w / 2.0f - 160.0f, h / 2.0f - 65.0f);
        ImGui.setNextWindowSize(320.0f, 130.0f);
        ImGui.begin("Pre-generating Terrain",
                imgui.flag.ImGuiWindowFlags.NoDecoration | imgui.flag.ImGuiWindowFlags.NoMove);
        ImGui.text("Generating win.world in background...");
        ImGui.text("Please wait a moment while the spawn");
        ImGui.text("area finishes compiling...");
        ImGui.spacing();
        float progress = (float) (glfwGetTime() % 2.0) / 2.0f;
        ImGui.progressBar(progress, 300, 24);
        ImGui.end();
    }

    // Slider value holders (imgui-java sliderFloat needs a float[1]); synced from AudioManager.
    private final float[] volMaster = {1f}, volSfx = {1f}, volMob = {1f}, volMusic = {1f};

    void renderPauseMenu(float w, float h) {
        ImGui.setNextWindowPos(w / 2.0f - 130.0f, h / 2.0f - 175.0f);
        ImGui.setNextWindowSize(260.0f, 350.0f);
        ImGui.begin("Paused",
                imgui.flag.ImGuiWindowFlags.NoDecoration | imgui.flag.ImGuiWindowFlags.NoMove);
        ImGui.text("Game Paused");
        ImGui.separator();
        ImGui.spacing();
        if (ImGui.button("Resume", 240, 28)) {
            win.isPaused = false;
            glfwSetInputMode(win.window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
        }
        ImGui.spacing();
        if (ImGui.button("Controls  [F1]", 240, 28)) {
            // Open help without closing pause  -  win.player can read then ESC back
            win.showHelp = true;
        }
        ImGui.spacing();
        if (ImGui.button("Save Game", 240, 28)) SaveManager.saveGame(win.world, win.player, win.inventory);
        ImGui.spacing();
        if (ImGui.button("Save & Quit", 240, 28)) {
            SaveManager.saveGame(win.world, win.player, win.inventory);
            glfwSetWindowShouldClose(win.window, true);
        }

        // ── VOLUME MIXER ──────────────────────────────────────────────────────
        ImGui.spacing();
        ImGui.separator();
        ImGui.text("Volume");
        ImGui.pushItemWidth(150f);
        volMaster[0] = AudioManager.getMasterVolume();
        if (ImGui.sliderFloat("Master", volMaster, 0f, 1f)) AudioManager.setMasterVolume(volMaster[0]);
        volSfx[0] = AudioManager.getSfxVolume();
        if (ImGui.sliderFloat("Effects", volSfx, 0f, 1f)) AudioManager.setSfxVolume(volSfx[0]);
        volMob[0] = AudioManager.getMobVolume();
        if (ImGui.sliderFloat("Mobs", volMob, 0f, 1f)) AudioManager.setMobVolume(volMob[0]);
        volMusic[0] = AudioManager.getMusicVolume();
        if (ImGui.sliderFloat("Music", volMusic, 0f, 1f)) AudioManager.setMusicVolume(volMusic[0]);
        ImGui.popItemWidth();

        ImGui.end();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  ABILITY UNLOCK CARD  (shown between waves)
    // ─────────────────────────────────────────────────────────────────────────
    void renderUnlockCard(float w, float h) {
        int n = win.unlockCardAbilities.size();
        float cardW = 540f;
        float cardH = 210f + n * 92f;
        ImGui.setNextWindowPos(w / 2f - cardW / 2f, h / 2f - cardH / 2f);
        ImGui.setNextWindowSize(cardW, cardH);
        ImGui.setNextWindowBgAlpha(0.93f);
        ImGui.begin("AbilityUnlock",
                imgui.flag.ImGuiWindowFlags.NoDecoration
              | imgui.flag.ImGuiWindowFlags.NoMove
              | imgui.flag.ImGuiWindowFlags.NoResize);

        ImGui.spacing(); ImGui.spacing();
        cardCenterBig("NEW POWER UNLOCKED", 1.3f, 1.0f, 0.85f, 0.35f);
        ImGui.spacing();
        cardCenter(win.player.progression.flavorFor(win.unlockCardWave), 0.85f, 0.9f, 1.0f);
        ImGui.spacing(); ImGui.separator(); ImGui.spacing();

        for (Progression.Ability a : win.unlockCardAbilities) {
            // The key IS the lesson  -  render the key + name line big.
            cardCenterBig(a.key + "   " + a.label, 1.35f, 0.45f, 0.95f, 1.0f);
            ImGui.spacing();
            ImGui.pushTextWrapPos(ImGui.getWindowWidth() - 24f);
            ImGui.setCursorPosX(22f);
            ImGui.text(a.desc);
            ImGui.popTextWrapPos();
            ImGui.spacing();
        }

        ImGui.spacing();
        ImGui.pushTextWrapPos(ImGui.getWindowWidth() - 24f);
        ImGui.setCursorPosX(22f);
        ImGui.textColored(0.55f, 0.75f, 1.0f, 0.95f, Progression.MANA_NOTE);
        ImGui.popTextWrapPos();
        ImGui.spacing(); ImGui.separator(); ImGui.spacing();

        cardCenter("[ ENTER ]   begin wave " + (win.unlockCardWave + 1), 1.0f, 0.9f, 0.4f);
        ImGui.end();
    }

    /** Centre a single line of text horizontally in the current ImGui window. */
    private void cardCenter(String text, float r, float g, float b) {
        float tw = ImGui.calcTextSize(text).x;
        ImGui.setCursorPosX(Math.max(8f, (ImGui.getWindowWidth() - tw) * 0.5f));
        ImGui.textColored(r, g, b, 1.0f, text);
    }

    /**
     * Centre a line at {@code scale}× the base font size in the current ImGui
     * window (drawn via the window draw list  -  the binding has no static
     * setWindowFontScale). Advances the layout cursor past the text.
     */
    private void cardCenterBig(String text, float scale, float r, float g, float b) {
        ImFont font = ImGui.getFont();
        float  base = ImGui.getFontSize();
        float  size = base * scale;
        float  tw   = ImGui.calcTextSize(text).x * scale;
        float  x = ImGui.getWindowPosX() + Math.max(8f, (ImGui.getWindowWidth() - tw) * 0.5f);
        float  y = ImGui.getCursorScreenPosY();
        ImGui.getWindowDrawList().addText(font, size, x, y,
                ImGui.colorConvertFloat4ToU32(r, g, b, 1.0f), text);
        ImGui.dummy(0f, size + 2f);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  WEAPON FORGED REVEAL   -  big dramatic overlay when a weapon is earned
    // ─────────────────────────────────────────────────────────────────────────

    void renderWeaponReveal(float screenW, float screenH) {
        Progression.Ability a = win.weaponRevealAbility;
        if (a == null) return;
        // Wait until the wave unlock card is dismissed  -  never stack the two.
        if (win.showUnlockCard) return;
        float t = win.weaponRevealTimer;   // 5.5 -> 0
        // fade-in 0.4 s, full 4.5 s, fade-out 0.6 s
        float alpha = t > 5.1f ? (5.5f - t) / 0.4f
                    : t < 0.6f ? t / 0.6f
                    : 1.0f;
        alpha = Math.max(0f, Math.min(1f, alpha));

        imgui.ImDrawList draw = ImGui.getForegroundDrawList();
        int sh  = ImGui.colorConvertFloat4ToU32(0f, 0f, 0f, 0.85f * alpha);
        int bg  = ImGui.colorConvertFloat4ToU32(0.04f, 0.03f, 0.10f, 0.88f * alpha);
        int bdr = ImGui.colorConvertFloat4ToU32(1.0f, 0.82f, 0.25f, 0.85f * alpha);
        int gld = ImGui.colorConvertFloat4ToU32(1.0f, 0.88f, 0.25f, alpha);
        int wht = ImGui.colorConvertFloat4ToU32(1.0f, 0.97f, 0.92f, alpha);
        int dim = ImGui.colorConvertFloat4ToU32(0.75f, 0.82f, 0.95f, 0.9f * alpha);
        int grn = ImGui.colorConvertFloat4ToU32(0.4f, 1.0f, 0.65f, alpha);
        int acc = ImGui.colorConvertFloat4ToU32(0.55f, 0.85f, 1.0f, alpha);

        float cardW = 460f, cardH = 220f;
        float cx = screenW / 2f, cy = screenH / 2f;
        float x0 = cx - cardW / 2f, y0 = cy - cardH / 2f;
        float x1 = x0 + cardW,      y1 = y0 + cardH;

        // Dark vignette
        draw.addRectFilled(0, 0, screenW, screenH,
                ImGui.colorConvertFloat4ToU32(0f, 0f, 0f, 0.45f * alpha));
        // Card background + double border for drama
        draw.addRectFilled(x0, y0, x1, y1, bg, 12f);
        draw.addRect(x0, y0, x1, y1, bdr, 12f, 0, 2.5f);
        draw.addRect(x0 + 4, y0 + 4, x1 - 4, y1 - 4,
                ImGui.colorConvertFloat4ToU32(1.0f, 0.95f, 0.6f, 0.25f * alpha), 9f, 0, 1f);

        int slot = win.weaponRevealSlot;
        float iy = y0 + 20f;

        // WEAPON FORGED / ABILITY FORGED header
        String header = slot >= 0 ? "  **  WEAPON FORGED  **  " : "  **  ABILITY FORGED  **  ";
        float hw = ImGui.calcTextSize(header).x;
        draw.addText(cx - hw / 2 + 1, iy + 1, sh, header);
        draw.addText(cx - hw / 2, iy, gld, header);
        iy += 28f;

        // Thin separator
        draw.addLine(x0 + 24, iy, x1 - 24, iy,
                ImGui.colorConvertFloat4ToU32(1.0f, 0.82f, 0.25f, 0.4f * alpha), 1f);
        iy += 10f;

        // Weapon icon  -  48×48 centred
        float iconSz = 48f;
        float ix0 = cx - iconSz / 2f, iy0i = iy, ix1 = ix0 + iconSz, iy1i = iy0i + iconSz;
        switch (a) {
            case GATLING     -> drawGunIcon    (draw, ix0, iy0i, ix1, iy1i);
            case SNIPE       -> drawSniperIcon (draw, ix0, iy0i, ix1, iy1i);
            case ORBITAL     -> drawOrbitalIcon(draw, ix0, iy0i, ix1, iy1i);
            case DOMAIN      -> drawTimeStopIcon(draw, ix0, iy0i, ix1, iy1i);
            case STONE_CANON -> drawStoneCannonIcon(draw, ix0, iy0i, ix1, iy1i);
            case DEPRIVATION -> {
                // Golden hemisphere icon
                draw.addCircleFilled(cx, iy0i + iconSz / 2, iconSz / 2.2f,
                        ImGui.colorConvertFloat4ToU32(1.0f, 0.82f, 0.15f, 0.55f * alpha));
                draw.addCircle(cx, iy0i + iconSz / 2, iconSz / 2.2f,
                        ImGui.colorConvertFloat4ToU32(1.0f, 0.95f, 0.4f, alpha), 32, 2f);
            }
            default -> {
                draw.addCircleFilled(cx, iy0i + iconSz / 2, iconSz / 2.2f,
                        ImGui.colorConvertFloat4ToU32(0.5f, 0.7f, 1f, 0.7f * alpha));
            }
        }
        iy += iconSz + 10f;

        // Weapon / ability name
        String name = a.label;
        float nw = ImGui.calcTextSize(name).x;
        draw.addText(cx - nw / 2 + 1, iy + 1, sh, name);
        draw.addText(cx - nw / 2, iy, wht, name);
        iy += 22f;

        // Key hint (a.key already contains the bracket, e.g. "[C] / RMB" or "[ ' ]")
        String key = a.key + "  to activate";
        float kw = ImGui.calcTextSize(key).x;
        draw.addText(cx - kw / 2 + 1, iy + 1, sh, key);
        draw.addText(cx - kw / 2, iy, acc, key);
        iy += 20f;

        // Hotbar slot arrow
        if (slot >= 0) {
            String slotHint = "->  Added to hotbar slot  " + (slot + 1);
            float sw = ImGui.calcTextSize(slotHint).x;
            draw.addText(cx - sw / 2 + 1, iy + 1, sh, slotHint);
            draw.addText(cx - sw / 2, iy, grn, slotHint);
        }

        // Draw a pulsing up-arrow above the hotbar slot so the eye finds it
        if (slot >= 0) {
            float slotSize = 40f, spacing = 5f, numSlots = 9;
            float startX = screenW / 2f - (numSlots * slotSize + (numSlots - 1) * spacing) / 2f;
            float slotX  = startX + slot * (slotSize + spacing) + slotSize / 2f;
            float slotTop = screenH - slotSize - 24f;
            float bounce = (float) Math.sin(win.weaponRevealTimer * 6.0) * 4f;
            float ax = slotX, ay = slotTop - 14f + bounce;
            int arrowCol = ImGui.colorConvertFloat4ToU32(1.0f, 0.88f, 0.2f, alpha);
            draw.addTriangleFilled(ax, ay + 10f, ax - 8f, ay - 6f, ax + 8f, ay - 6f, arrowCol);
            draw.addRect(ax - 3f, ay + 10f, ax + 3f, ay + 20f, arrowCol, 0f, 0, 2f);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  PRACTICE OVERLAY   -  multi-step hands-on ability tutorial
    // ─────────────────────────────────────────────────────────────────────────
    void renderPractice(float w, float h) {
        if (win.practiceAbility == null || win.practiceSteps == null) return;
        AbilityPractice.Step step = win.practiceSteps.get(win.practiceStepIndex);
        int totalSteps = win.practiceSteps.size();
        boolean celebrating = win.practiceStepDone;

        imgui.ImDrawList draw = ImGui.getForegroundDrawList();
        ImFont font = ImGui.getFont();
        float base = ImGui.getFontSize();

        // Dim background  -  world stays visible so the player can act
        draw.addRectFilled(0, 0, w, h, ImGui.colorConvertFloat4ToU32(0f, 0f, 0f, 0.50f));

        // Card sits at the bottom quarter of the screen
        float cardW = Math.min(640f, w - 40f);
        float cx = w * 0.5f;
        float lineH = base * 1.45f;
        // Count instruction lines (split on \n)
        String[] instrLines = step.instruction.split("\n");
        float cardH = 18f + lineH              // title row
                    + instrLines.length * lineH + 8f // instructions
                    + lineH + 12f;             // goal/skip row
        float cardY = h - cardH - 28f;

        draw.addRectFilled(cx - cardW/2, cardY, cx + cardW/2, cardY + cardH,
                ImGui.colorConvertFloat4ToU32(0.03f, 0.04f, 0.10f, 0.93f), 8f);
        int borderCol = celebrating
                ? ImGui.colorConvertFloat4ToU32(0.35f, 1.0f, 0.5f, 0.9f)   // green when done
                : ImGui.colorConvertFloat4ToU32(0.4f,  0.55f, 0.9f, 0.6f);
        draw.addRect(cx - cardW/2, cardY, cx + cardW/2, cardY + cardH, borderCol, 8f, 0, 1.8f);

        float ty = cardY + 10f;

        // Title + step counter
        String abilName = win.practiceAbility.label;
        String progress = totalSteps > 1
                ? "  (" + (win.practiceStepIndex + 1) + " / " + totalSteps + ")"
                : "";
        String header = "PRACTICE  -  " + abilName + progress;
        if (step.keyHint != null) header += "    [ " + step.keyHint + " ]";
        float hw = ImGui.calcTextSize(header).x;
        draw.addText(cx - hw/2, ty, ImGui.colorConvertFloat4ToU32(0.45f, 0.95f, 1.0f, 1f), header);
        ty += lineH + 2f;

        // Instruction lines
        for (String line : instrLines) {
            float lw = ImGui.calcTextSize(line).x;
            draw.addText(font, base, cx - lw/2, ty,
                    ImGui.colorConvertFloat4ToU32(0.88f, 0.90f, 0.95f, 0.95f), line);
            ty += lineH;
        }
        ty += 8f;

        // Progress counter (e.g. "2 / 3") if required > 1
        if (step.required > 1) {
            int done = Math.min(win.practiceCtx.counter, step.required);
            String prog = done + " / " + step.required;
            float progW = ImGui.calcTextSize(prog).x;
            int progCol = done >= step.required
                    ? ImGui.colorConvertFloat4ToU32(0.3f, 1.0f, 0.45f, 1f)
                    : ImGui.colorConvertFloat4ToU32(1.0f, 0.85f, 0.3f,  1f);
            draw.addText(cx - progW/2, ty, progCol, prog);
            ty += lineH;
        }

        // Warning text (e.g. "HOLD it! Don't click!")  -  shown in red above skip row
        if (win.practiceWarnText != null && win.practiceWarnTimer > 0f) {
            float pulse = 0.75f + 0.25f * (float) Math.sin(glfwGetTime() * 10f);
            float ww = ImGui.calcTextSize(win.practiceWarnText).x;
            draw.addText(font, base * 1.05f, cx - ww/2, ty,
                    ImGui.colorConvertFloat4ToU32(1.0f, 0.3f, 0.25f, pulse), win.practiceWarnText);
            ty += lineH;
        }

        // Celebration OR skip/prompt row
        if (celebrating && step.doneText != null) {
            float pulse = 0.7f + 0.3f * (float) Math.sin(glfwGetTime() * 8f);
            float dw = ImGui.calcTextSize(step.doneText).x;
            draw.addText(cx - dw/2, ty,
                    ImGui.colorConvertFloat4ToU32(0.3f, 1.0f, 0.45f, pulse), step.doneText);
        } else if (!celebrating) {
            String skipTxt = step.allowSkip && win.practiceStepAge > 2f
                    ? "[ ENTER ]  skip" : "";
            if (!skipTxt.isEmpty()) {
                float sw = ImGui.calcTextSize(skipTxt).x;
                draw.addText(cx - sw/2, ty,
                        ImGui.colorConvertFloat4ToU32(0.5f, 0.52f, 0.6f, 0.6f), skipTxt);
            }
        }

        // Dummy HP bar above the card
        com.leaf.game.entity.Enemy dummy = win.practiceCtx.dummy();
        if (dummy != null && dummy.alive) {
            float barW = 180f, barH = 12f;
            float bx = cx - barW/2, by = cardY - barH - 22f;
            float frac = Math.max(0f, dummy.health / dummy.maxHealth);
            draw.addRectFilled(bx, by, bx + barW, by + barH,
                    ImGui.colorConvertFloat4ToU32(0.15f, 0.05f, 0.05f, 0.85f), 4f);
            draw.addRectFilled(bx, by, bx + barW * frac, by + barH,
                    ImGui.colorConvertFloat4ToU32(0.9f, 0.25f, 0.25f, 0.9f), 4f);
            draw.addRect(bx, by, bx + barW, by + barH,
                    ImGui.colorConvertFloat4ToU32(0.5f, 0.2f, 0.2f, 0.6f), 4f, 0, 1.2f);
            String dLabel = "TARGET  HP";
            draw.addText(cx - ImGui.calcTextSize(dLabel).x/2, by - 16f,
                    ImGui.colorConvertFloat4ToU32(0.75f, 0.55f, 0.55f, 0.7f), dLabel);
        }

        // ── HUD arrows: draw a pointer to the mana bar and/or cooldown icon ──
        if (step.showManaArrow || step.showCooldownArrow) {
            renderHudAnnotations(draw, font, base, w, h, step.showManaArrow, step.showCooldownArrow);
        }
    }

    /** Draw animated arrows pointing at the mana bar and/or the cooldown icon strip. */
    private void renderHudAnnotations(imgui.ImDrawList draw, ImFont font, float base,
                                      float w, float h,
                                      boolean showMana, boolean showCooldown) {
        float pulse = 0.6f + 0.4f * (float)Math.abs(Math.sin(glfwGetTime() * 4f));
        int arrowCol = ImGui.colorConvertFloat4ToU32(1.0f, 0.92f, 0.3f, pulse);
        int labelCol = ImGui.colorConvertFloat4ToU32(1.0f, 0.95f, 0.5f, pulse);

        if (showMana) {
            // Mana bar sits at h-80 + 14+3 = h-63  (hpHeight=14, gap=3)
            float mpY   = h - 63f;
            float mpCx  = w * 0.5f;
            // Arrow pointing right  (from left of bar)
            float ax = mpCx - 120f, ay = mpY + 5f;
            draw.addLine(ax - 28f, ay, ax, ay, arrowCol, 2.5f);
            draw.addTriangleFilled(ax, ay - 5f, ax, ay + 5f, ax + 8f, ay, arrowCol);
            draw.addText(font, base * 0.9f, ax - 80f, ay - 8f, labelCol, "MANA");
        }

        if (showCooldown) {
            // Cooldown icon row 1 is bottom-right.  Point at that area.
            float iconY = h - 90f;   // approximate top of the icon strip
            float iconX = w - 180f;
            float ax = iconX - 10f, ay = iconY + 15f;
            draw.addLine(ax - 32f, ay, ax, ay, arrowCol, 2.5f);
            draw.addTriangleFilled(ax, ay - 5f, ax, ay + 5f, ax + 8f, ay, arrowCol);
            draw.addText(font, base * 0.9f, ax - 105f, ay - 8f, labelCol, "COOLDOWN");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  DEATH SCREEN
    // ─────────────────────────────────────────────────────────────────────────
    void renderDeathScreen(float w, float h) {
        imgui.ImDrawList draw = ImGui.getForegroundDrawList();

        // ── FLAPPY MODE ARCADE DEATH OVERLAY ──
        if (win.player.useTestMovement && win.player.testMovement.state == TestMovementController.State.FLAPPY) {
            // Dark retro violet backdrop
            draw.addRectFilled(0, 0, w, h, ImGui.colorConvertFloat4ToU32(0.02f, 0.02f, 0.04f, 0.85f));

            float cy = h * 0.5f - 110f;
            ImFont font = ImGui.getFont();

            // Big Red "GAME OVER"
            String title = "GAME OVER";
            float titleScale = 2.6f;
            float sz = font.getFontSize() * titleScale;
            float tw = ImGui.calcTextSize(title).x * titleScale;
            draw.addText(font, sz, (w - tw) / 2f + 2f, cy + 2f, ImGui.colorConvertFloat4ToU32(0f, 0f, 0f, 1f), title);
            draw.addText(font, sz, (w - tw) / 2f,      cy,      ImGui.colorConvertFloat4ToU32(0.95f, 0.2f, 0.15f, 1f), title);

            cy += 70f;

            // Score summary
            String scoreLine = "FINAL SCORE: " + win.player.testMovement.flappyScore;
            float sw = ImGui.calcTextSize(scoreLine).x * 1.5f;
            draw.addText(font, font.getFontSize() * 1.5f, (w - sw) / 2f, cy, ImGui.colorConvertFloat4ToU32(1f, 1f, 0.9f, 1f), scoreLine);

            cy += 50f;

            // Arcade Prompt Options (Play Again or Exit)
            String opt1 = "[ ENTER ]  Play Again";
            String opt2 = "[ X ]  Exit to Normal World";
            float pw1 = ImGui.calcTextSize(opt1).x * 1.2f;
            float pw2 = ImGui.calcTextSize(opt2).x * 1.2f;

            float pa = 0.5f + 0.5f * (float) Math.abs(Math.sin(glfwGetTime() * 3.5f));
            draw.addText(font, font.getFontSize() * 1.2f, (w - pw1) / 2f, cy, ImGui.colorConvertFloat4ToU32(0.2f, 0.85f, 0.4f, pa), opt1);
            draw.addText(font, font.getFontSize() * 1.2f, (w - pw2) / 2f, cy + 28f, ImGui.colorConvertFloat4ToU32(0.85f, 0.85f, 0.9f, 0.75f), opt2);
            return; // Completely bypass the standard survival death screen
        }

        // Heavy dark overlay
        draw.addRectFilled(0, 0, w, h, ImGui.colorConvertFloat4ToU32(0f, 0f, 0f, 0.82f));

        float cy = h * 0.5f - 60f;

        // Title  -  big, red
        String title = "YOU DIED";
        ImFont font = ImGui.getFont();
        float titleSz = ImGui.getFontSize() * 2.8f;
        float tw = ImGui.calcTextSize(title).x * (titleSz / ImGui.getFontSize());
        float tx = (w - tw) * 0.5f;
        draw.addText(font, titleSz, tx + 2, cy + 2, ImGui.colorConvertFloat4ToU32(0f, 0f, 0f, 1f), title);
        draw.addText(font, titleSz, tx, cy, ImGui.colorConvertFloat4ToU32(0.95f, 0.18f, 0.12f, 1f), title);
        cy += titleSz + 30f;

        // Single blinking respawn prompt
        String prompt = "[ ENTER ]   RESPAWN";
        float pa = 0.55f + 0.45f * (float) Math.abs(Math.sin(glfwGetTime() * 2.5f));
        float promptSz = ImGui.getFontSize() * 1.6f;
        float pw = ImGui.calcTextSize(prompt).x * (promptSz / ImGui.getFontSize());
        draw.addText(font, promptSz, (w - pw) * 0.5f + 1, cy + 1,
                ImGui.colorConvertFloat4ToU32(0f, 0f, 0f, pa * 0.9f), prompt);
        draw.addText(font, promptSz, (w - pw) * 0.5f, cy,
                ImGui.colorConvertFloat4ToU32(0.85f, 0.88f, 1.0f, pa), prompt);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  THE FINALE — every overlay of the endgame, switched on FinaleManager state
    // ─────────────────────────────────────────────────────────────────────────

    /** Centered text with shadow at a given scale. Returns the height drawn. */
    private float finText(imgui.ImDrawList draw, float w, float y, float scale,
                          float r, float g, float b, float a, String text) {
        ImFont font = ImGui.getFont();
        float sz = font.getFontSize() * scale;
        float tw = ImGui.calcTextSize(text).x * scale;
        float x = (w - tw) * 0.5f;
        draw.addText(font, sz, x + 2, y + 2, ImGui.colorConvertFloat4ToU32(0f, 0f, 0f, a), text);
        draw.addText(font, sz, x, y, ImGui.colorConvertFloat4ToU32(r, g, b, a), text);
        return sz;
    }

    void renderFinale(float w, float h) {
        FinaleManager f = win.finale;
        imgui.ImDrawList draw = ImGui.getForegroundDrawList();
        float t = f.t;

        switch (f.state) {
            case SUMMONS -> {
                // Giant pulsing banner: impossible to miss what's going on.
                float pulse = 0.8f + 0.2f * (float) Math.abs(Math.sin(ImGui.getTime() * 2.2));
                finText(draw, w, 50f, 2.2f, 1.0f, 0.85f * pulse, 0.3f * pulse, 1f,
                        "ALL POWERS CLAIMED  -  THE CRYSTAL CALLS YOU HOME");
                float dx = win.player.position.x - f.portalX();
                float dz = win.player.position.z - f.portalZ();
                float dist = (float) Math.sqrt(dx * dx + dz * dz);
                if (dist > 12f) {
                    finText(draw, w, 96f, 1.3f, 0.85f, 0.9f, 1.0f, 0.95f,
                            String.format("RETURN TO SPAWN  -  enter the portal    %.0f m", dist));
                    drawPortalArrow(draw, w, h, f);
                } else {
                    float pa = 0.55f + 0.45f * (float) Math.abs(Math.sin(ImGui.getTime() * 3.0));
                    finText(draw, w, h * 0.62f, 1.8f, 0.85f, 0.55f, 1.0f, pa,
                            "STEP INTO THE PORTAL");
                }
                drawFinaleSubtitle(draw, w, h, f);
            }
            case PORTAL_WARP -> {
                // Purple vortex vignette closing in, then white-out.
                float closeT = Math.min(1f, t / 1.6f);
                int purple = ImGui.colorConvertFloat4ToU32(0.45f, 0.1f, 0.8f, 0.55f * closeT);
                float edge = h * 0.45f * closeT;
                draw.addRectFilledMultiColor(0, 0, w, edge, purple, purple, 0, 0);
                draw.addRectFilledMultiColor(0, h - edge, w, h, 0, 0, purple, purple);
                float white = Math.max(0f, (t - 1.2f) / 0.8f);
                white = Math.min(1f, white) * (t < 2.4f ? 1f : Math.max(0f, 1f - (t - 2.4f) / 0.6f));
                if (white > 0f)
                    draw.addRectFilled(0, 0, w, h, ImGui.colorConvertFloat4ToU32(1f, 1f, 1f, white));
            }
            case ARENA_INTRO -> {
                float a = Math.min(1f, t / 0.8f) * (t > 3.7f ? Math.max(0f, 1f - (t - 3.7f) / 0.8f) : 1f);
                finText(draw, w, h * 0.34f, 3.2f, 1.0f, 0.3f, 0.25f, a, "THE FINAL TRIAL");
                finText(draw, w, h * 0.34f + 64f, 1.2f, 0.9f, 0.85f, 0.8f, a,
                        "Six trials stand between you and freedom.  Survive them all.");
                finText(draw, w, h * 0.34f + 96f, 1.0f, 0.7f, 0.7f, 0.75f, a * 0.9f,
                        "Fall, and wake at the portal.  The trial will wait.");
            }
            case PHASE_BANNER -> {
                float a = Math.min(1f, t / 0.5f) * (t > 2.5f ? Math.max(0f, 1f - (t - 2.5f) / 0.7f) : 1f);
                finText(draw, w, h * 0.30f, 1.2f, 0.8f, 0.75f, 0.7f, a,
                        "TRIAL  " + f.phase + "  OF  " + FinaleManager.PHASES);
                finText(draw, w, h * 0.30f + 34f, 2.8f, 1.0f, 0.35f, 0.3f, a, f.phaseName());
                finText(draw, w, h * 0.30f + 96f, 1.15f, 0.95f, 0.9f, 0.8f, a, f.phaseSub());
            }
            case FIGHT -> {
                finText(draw, w, 40f, 1.1f, 1.0f, 0.5f, 0.4f, 0.9f,
                        "TRIAL " + f.phase + " / " + FinaleManager.PHASES + "   " + f.phaseName());
                float infoY = 66f;
                String swl = f.subWaveLabel();
                if (swl != null) {
                    finText(draw, w, infoY, 1.0f, 0.85f, 0.7f, 1.0f, 0.95f, swl);
                    infoY += 24f;
                }
                finText(draw, w, infoY, 1.0f, 0.95f, 0.9f, 0.85f, 0.85f,
                        "ENEMIES LEFT:  " + f.enemiesLeft());
                infoY += 26f;

                // Boss bar — the Avatar's health, big and unmistakable.
                float bf = f.bossFrac();
                if (bf >= 0f) {
                    finText(draw, w, infoY, 1.0f, 1.0f, 0.6f, 1.0f, 0.95f, "AVATAR  OF  THE  CRYSTAL");
                    infoY += 22f;
                    float bw = w * 0.42f, bh = 14f;
                    float bx = (w - bw) / 2f;
                    draw.addRectFilled(bx - 2, infoY - 2, bx + bw + 2, infoY + bh + 2,
                            ImGui.colorConvertFloat4ToU32(0f, 0f, 0f, 0.75f));
                    int fillCol = f.isEnraged()
                            ? ImGui.colorConvertFloat4ToU32(1.0f, 0.2f, 0.15f, 0.95f)
                            : ImGui.colorConvertFloat4ToU32(0.75f, 0.25f, 0.95f, 0.95f);
                    draw.addRectFilled(bx, infoY, bx + bw * bf, infoY + bh, fillCol);
                    draw.addRect(bx - 2, infoY - 2, bx + bw + 2, infoY + bh + 2,
                            ImGui.colorConvertFloat4ToU32(1.0f, 0.8f, 0.25f, 0.85f), 3f, 0, 1.5f);
                    infoY += bh + 10f;
                }
                if (f.phase >= 5) {
                    float pa = 0.4f + 0.6f * (float) Math.abs(Math.sin(ImGui.getTime() * 4.0));
                    finText(draw, w, infoY, 1.05f, 1.0f, 0.25f, 0.15f, pa,
                            "! THE ARENA IS COLLAPSING  -  STAY OFF THE EDGE !");
                }
                // Enrage: the whole screen breathes red.
                if (f.isEnraged()) {
                    float ra = 0.10f + 0.08f * (float) Math.abs(Math.sin(ImGui.getTime() * 3.2));
                    drawRadialVignette(draw, w, h, 0.9f, 0.05f, 0.05f, ra * 2.4f);
                }
                drawFinaleSubtitle(draw, w, h, f);   // giant names, avatar callouts
            }
            case FLAPPY_TRIAL -> {
                finText(draw, w, h * 0.035f, 1.9f, 1.0f, 0.55f, 0.9f, 1f, "THE CRYSTAL MOCKS YOU");
                finText(draw, w, h * 0.035f + 44f, 1.15f, 0.95f, 0.92f, 0.85f, 0.95f,
                        "BECOME THE BIRD.   [SPACE] to flap.   Crash = instant retry.");
                int score = win.player.testMovement.flappyScore;
                float pulse = score >= 10 ? 0.7f + 0.3f * (float) Math.abs(Math.sin(ImGui.getTime() * 5.0)) : 1f;
                finText(draw, w, h * 0.16f, 2.4f, 1.0f, 0.85f * pulse, 0.25f, 1f,
                        "SCORE   " + score + "  /  15");
            }
            case DEFEAT_FADE -> {
                float a = Math.min(1f, f.wakeEyelid());
                draw.addRectFilled(0, 0, w, h, ImGui.colorConvertFloat4ToU32(0f, 0f, 0f, a));
            }
            case DEFEAT_WAKE -> {
                // Curved eyelids: two enormous black discs whose arcs sweep open
                // like real lids, with layered translucent rings for a soft,
                // blurred edge instead of a hard line.
                float e = f.wakeEyelid();
                if (e > 0.001f) {
                    float R = w * 1.35f;
                    float cover = (h * 0.62f) * e;
                    int black = ImGui.colorConvertFloat4ToU32(0f, 0f, 0f, 1f);
                    draw.addCircleFilled(w / 2f, cover - R, R, black, 80);
                    draw.addCircleFilled(w / 2f, h - cover + R, R, black, 80);
                    for (int i = 1; i <= 4; i++) {
                        int soft = ImGui.colorConvertFloat4ToU32(0f, 0f, 0f, 0.45f - i * 0.10f);
                        draw.addCircleFilled(w / 2f, cover - R + i * 12f, R, soft, 80);
                        draw.addCircleFilled(w / 2f, h - cover + R - i * 12f, R, soft, 80);
                    }
                }
                // Groggy radial vignette that breathes, fading as you stand.
                float groggy = Math.max(0f, 1f - t / 8f);
                float vig = groggy * (0.55f + 0.10f * (float) Math.sin(ImGui.getTime() * 1.7));
                drawRadialVignette(draw, w, h, 0f, 0f, 0f, vig);
                // Brief desaturated blue haze right after the eyes open
                if (t > 3.0f && t < 6.5f) {
                    float haze = (1f - (t - 3.0f) / 3.5f) * 0.18f;
                    draw.addRectFilled(0, 0, w, h, ImGui.colorConvertFloat4ToU32(0.4f, 0.5f, 0.8f, haze));
                }
                drawFinaleSubtitle(draw, w, h, f);
            }
            case VICTORY_TEAR -> {
                finText(draw, w, h * 0.25f, 2.6f, 1.0f, 0.95f, 0.7f, Math.min(1f, t / 0.5f),
                        "THE TRIAL IS COMPLETE");
                finText(draw, w, h * 0.25f + 58f, 1.2f, 0.95f, 0.9f, 0.85f, Math.min(1f, t / 0.8f),
                        "The dimension cannot bear your power.  It is tearing apart.");
                // White tear: flickering bright gash growing from the centre.
                float tear = Math.max(0f, (t - 1.0f) / 3.0f);
                float flicker = 0.85f + 0.15f * (float) Math.sin(ImGui.getTime() * 40.0);
                if (tear > 0f) {
                    float tw2 = w * tear * flicker, th2 = h * tear * tear * flicker;
                    int whiteC = ImGui.colorConvertFloat4ToU32(1f, 1f, 1f, Math.min(1f, tear * 1.3f));
                    draw.addTriangleFilled(w / 2f - tw2 / 2f, h / 2f, w / 2f + tw2 / 2f, h / 2f,
                            w / 2f, h / 2f - th2 / 2f, whiteC);
                    draw.addTriangleFilled(w / 2f - tw2 / 2f, h / 2f, w / 2f + tw2 / 2f, h / 2f,
                            w / 2f, h / 2f + th2 / 2f, whiteC);
                }
                if (t > 3.4f)
                    draw.addRectFilled(0, 0, w, h, ImGui.colorConvertFloat4ToU32(1f, 1f, 1f,
                            Math.min(1f, (t - 3.4f) / 0.6f)));
            }
            case MADE_IN_HEAVEN -> {
                // Fade back in from the white tear
                if (t < 1.2f)
                    draw.addRectFilled(0, 0, w, h, ImGui.colorConvertFloat4ToU32(1f, 1f, 1f, 1f - t / 1.2f));
                drawLetterbox(draw, w, h, Math.min(1f, t / 1.5f));
                // Faint golden grade over everything — the golden wind
                float gold = 0.10f + 0.05f * (float) Math.sin(ImGui.getTime() * 1.3);
                draw.addRectFilled(0, 0, w, h, ImGui.colorConvertFloat4ToU32(1.0f, 0.85f, 0.3f, gold));
                drawFinaleSubtitle(draw, w, h, f);
            }
            case UPHEAVAL -> {
                drawLetterbox(draw, w, h, 1f);
                // SEASONS STROBE PAST: spring green → summer gold → autumn fire →
                // winter pale, blending continuously as years race by.
                float[][] seasons = {
                        {0.45f, 0.95f, 0.45f}, {1.0f, 0.85f, 0.30f},
                        {0.95f, 0.45f, 0.18f}, {0.80f, 0.90f, 1.0f}};
                float cyc = (t * 0.55f) % 4f;
                int i0 = (int) cyc, i1 = (i0 + 1) % 4;
                float ft = cyc - i0;
                float sr2 = seasons[i0][0] + (seasons[i1][0] - seasons[i0][0]) * ft;
                float sg = seasons[i0][1] + (seasons[i1][1] - seasons[i0][1]) * ft;
                float sb = seasons[i0][2] + (seasons[i1][2] - seasons[i0][2]) * ft;
                draw.addRectFilled(0, 0, w, h, ImGui.colorConvertFloat4ToU32(sr2, sg, sb, 0.15f));
                drawRadialVignette(draw, w, h, 0f, 0f, 0f, 0.30f);
                drawFinaleSubtitle(draw, w, h, f);
            }
            case ASCENSION -> {
                drawLetterbox(draw, w, h, 1f);
                // The sky deepens into space as you climb
                float space = Math.min(0.55f, t / 18f * 0.7f);
                draw.addRectFilled(0, 0, w, h, ImGui.colorConvertFloat4ToU32(0.02f, 0.02f, 0.09f, space));
                // Star streaks racing downward — speed builds with the climb
                java.util.Random srng = new java.util.Random(42);
                int stars = (int) Math.min(80, t * 7);
                double now = ImGui.getTime();
                for (int i = 0; i < stars; i++) {
                    float sx = srng.nextFloat() * w;
                    float spd = 180f + srng.nextFloat() * 520f;
                    float len = 24f + srng.nextFloat() * 70f * Math.min(3f, t / 5f);
                    float sy = (float) ((srng.nextFloat() * h + now * spd * (0.4f + t * 0.10f)) % (h + len)) - len;
                    draw.addLine(sx, sy, sx, sy + len,
                            ImGui.colorConvertFloat4ToU32(1f, 1f, 1f, 0.20f + srng.nextFloat() * 0.30f), 1.5f);
                }
                // Golden aura intensifying around the edges
                float gold = 0.10f + 0.06f * (float) Math.sin(now * 2.2);
                drawRadialVignette(draw, w, h, 1.0f, 0.82f, 0.30f, gold * 2.2f);
                drawFinaleSubtitle(draw, w, h, f);
                float wo = f.whiteout();
                if (wo > 0f)
                    draw.addRectFilled(0, 0, w, h, ImGui.colorConvertFloat4ToU32(1f, 1f, 1f, wo));
            }
            case WHITEOUT -> {
                draw.addRectFilled(0, 0, w, h, ImGui.colorConvertFloat4ToU32(1f, 1f, 1f, 1f));
                String line = f.whiteoutLine();
                if (line != null) {
                    if (f.whiteoutClimax()) {
                        // The last truth, burned in gold.
                        float ca = Math.min(1f, (t - 13.5f) / 0.8f);
                        finText(draw, w, h * 0.44f, 2.6f, 0.85f, 0.62f, 0.05f, ca, line);
                    } else {
                        finText(draw, w, h * 0.46f, 1.6f, 0.25f, 0.22f, 0.28f, 1f, line);
                    }
                }
                // ease to black at the very end
                if (t > 16.0f)
                    draw.addRectFilled(0, 0, w, h, ImGui.colorConvertFloat4ToU32(0f, 0f, 0f,
                            Math.min(1f, (t - 16.0f) / 1.5f)));
            }
            case THE_END -> {
                draw.addRectFilled(0, 0, w, h, ImGui.colorConvertFloat4ToU32(0f, 0f, 0f, 1f));
                // A sparse field of slowly breathing stars — eternity.
                java.util.Random erng = new java.util.Random(7);
                double now = ImGui.getTime();
                for (int i = 0; i < 60; i++) {
                    float sx = erng.nextFloat() * w, sy = erng.nextFloat() * h;
                    float tw2 = 0.25f + 0.20f * (float) Math.sin(now * (0.4 + erng.nextFloat()) + i);
                    draw.addCircleFilled(sx, sy, 1.2f + erng.nextFloat(),
                            ImGui.colorConvertFloat4ToU32(1f, 1f, 0.95f, Math.max(0.05f, tw2)));
                }
                float a1 = Math.min(1f, t / 2.0f);
                finText(draw, w, h * 0.34f, 3.4f, 1.0f, 0.85f, 0.35f, a1, "D E S C E N T");
                float a2 = Math.min(1f, Math.max(0f, (t - 1.6f) / 1.6f));
                finText(draw, w, h * 0.34f + 78f, 1.3f, 0.8f, 0.78f, 0.72f, a2,
                        "A S C E N S I O N    C O M P L E T E");
                if (t > 3.4f) {
                    int em = (int) (f.endTime / 60f), es = (int) (f.endTime % 60f);
                    float a3 = Math.min(1f, (t - 3.4f) / 1f);
                    finText(draw, w, h * 0.34f + 128f, 1.0f, 0.55f, 0.53f, 0.5f, a3,
                            String.format("Enemies defeated: %d      Time: %d:%02d", f.endKills, em, es));
                }
                if (t > 5.5f) {
                    float a4 = Math.min(1f, (t - 5.5f) / 1.5f);
                    finText(draw, w, h * 0.66f, 1.1f, 0.85f, 0.83f, 0.78f, a4,
                            "Thank you for playing.");
                }
                if (t > 8f) {
                    float pa = 0.20f + 0.20f * (float) Math.abs(Math.sin(now * 1.5));
                    finText(draw, w, h * 0.92f, 0.9f, 0.5f, 0.5f, 0.48f, pa, "[ ESC ]   rest");
                }
            }
            default -> { }
        }
    }

    /**
     * Soft radial vignette: gradient bands from each screen edge fading toward
     * the centre — reads as a darkened ring, not "black boxes".
     */
    private void drawRadialVignette(imgui.ImDrawList draw, float w, float h,
                                    float r, float g, float b, float a) {
        if (a <= 0.002f) return;
        int edge = ImGui.colorConvertFloat4ToU32(r, g, b, Math.min(1f, a));
        int mid  = ImGui.colorConvertFloat4ToU32(r, g, b, 0f);
        float bx = w * 0.30f, by = h * 0.34f;
        draw.addRectFilledMultiColor(0, 0, w, by, edge, edge, mid, mid);       // top
        draw.addRectFilledMultiColor(0, h - by, w, h, mid, mid, edge, edge);   // bottom
        draw.addRectFilledMultiColor(0, 0, bx, h, edge, mid, mid, edge);       // left
        draw.addRectFilledMultiColor(w - bx, 0, w, h, mid, edge, edge, mid);   // right
    }

    /** Cinematic letterbox bars (alpha 0..1). */
    private void drawLetterbox(imgui.ImDrawList draw, float w, float h, float a) {
        float bar = h * 0.11f;
        int black = ImGui.colorConvertFloat4ToU32(0f, 0f, 0f, 0.92f * a);
        draw.addRectFilled(0, 0, w, bar, black);
        draw.addRectFilled(0, h - bar, w, h, black);
    }

    /** Lower-third subtitle with typewriter reveal, shared by several states. */
    private void drawFinaleSubtitle(imgui.ImDrawList draw, float w, float h, FinaleManager f) {
        if (f.subtitle == null) return;
        int visible = (int) (f.subtitleAge * 32);
        String shown = visible >= f.subtitle.length() ? f.subtitle : f.subtitle.substring(0, Math.max(0, visible));
        if (shown.isEmpty()) return;
        finText(draw, w, h * 0.80f, 1.35f, 0.95f, 0.92f, 0.82f, 0.96f, shown);
    }

    /** Edge arrow pointing toward the portal during the SUMMONS. */
    private void drawPortalArrow(imgui.ImDrawList draw, float w, float h, FinaleManager f) {
        Camera cam = win.camera;
        if (cam == null) return;
        Vector3f target = new Vector3f(f.portalX(), f.portalCY(), f.portalZ());

        Matrix4f viewProj = new Matrix4f(cam.getProjectionMatrix()).mul(cam.getViewMatrix());
        org.joml.Vector4f clip = new org.joml.Vector4f(target.x, target.y, target.z, 1.0f).mul(viewProj);
        boolean inFront = clip.w > 0.0f;
        float ndcX = clip.x / Math.abs(clip.w);
        float ndcY = clip.y / Math.abs(clip.w);
        boolean onScreen = inFront && Math.abs(ndcX) <= 1.0f && Math.abs(ndcY) <= 1.0f;

        int gold = ImGui.colorConvertFloat4ToU32(1.0f, 0.8f, 0.25f, 0.95f);
        if (onScreen) {
            // Diamond marker hovering on the portal itself
            float sx = (ndcX * 0.5f + 0.5f) * w;
            float sy = (1f - (ndcY * 0.5f + 0.5f)) * h;
            float d = 10f + 4f * (float) Math.abs(Math.sin(ImGui.getTime() * 3.0));
            draw.addLine(sx, sy - d, sx + d, sy, gold, 3f);
            draw.addLine(sx + d, sy, sx, sy + d, gold, 3f);
            draw.addLine(sx, sy + d, sx - d, sy, gold, 3f);
            draw.addLine(sx - d, sy, sx, sy - d, gold, 3f);
            String lbl = "THE PORTAL";
            float lw = ImGui.calcTextSize(lbl).x;
            draw.addText(sx - lw / 2, sy - d - 22f, gold, lbl);
        } else {
            // Edge triangle pointing the way (same dot-product trick as the drone arrow)
            Vector3f to = new Vector3f(target).sub(cam.position).normalize();
            Vector3f right = cam.getRight();
            Vector3f up = new Vector3f(right).cross(cam.getLookDirection()).normalize();
            float dirX = to.dot(right);
            float dirY = -to.dot(up);
            float ang = (float) Math.atan2(dirY, dirX);
            float cx = w / 2f, cy = h / 2f;
            float radius = Math.min(cx, cy) * 0.82f;
            float ax = cx + (float) Math.cos(ang) * radius;
            float ay = cy + (float) Math.sin(ang) * radius;
            float ca = (float) Math.cos(ang), sa = (float) Math.sin(ang);
            // triangle: tip + two base corners, rotated by ang
            float[][] p = {{26f, 0f}, {-12f, 16f}, {-12f, -16f}};
            float[] xs = new float[3], ys = new float[3];
            for (int i = 0; i < 3; i++) {
                xs[i] = ax + p[i][0] * ca - p[i][1] * sa;
                ys[i] = ay + p[i][0] * sa + p[i][1] * ca;
            }
            draw.addTriangleFilled(xs[0], ys[0], xs[1], ys[1], xs[2], ys[2], gold);
        }
    }

    void renderChatBox(int screenHeight) {
        ImGui.setNextWindowPos(10, screenHeight - 280);
        ImGui.setNextWindowSize(400, 200);
        int flags = imgui.flag.ImGuiWindowFlags.NoDecoration | imgui.flag.ImGuiWindowFlags.NoMove;
        if (!win.showChat) flags |= imgui.flag.ImGuiWindowFlags.NoBackground;
        ImGui.begin("Chat", flags);
        for (int i = Math.max(0, win.chatHistory.size() - 10); i < win.chatHistory.size(); i++)
            ImGui.text(win.chatHistory.get(i));
        if (win.showChat) {
            ImGui.setKeyboardFocusHere();
            if (ImGui.inputText("##chat", win.chatInput,
                    imgui.flag.ImGuiInputTextFlags.EnterReturnsTrue)) {
                String msg = win.chatInput.get().trim();
                if (!msg.isEmpty()) {

                    // Route commands to the new decoupled handler
                    if (msg.startsWith("/")) {
                        win.commandHandler.execute(msg);
                    } else {
                        win.chatHistory.add("[You]: " + msg);
                        if (win.network != null && win.network.connected) win.network.sendChat(msg);
                    }

                    win.chatInput.set("");
                }
                win.showChat = false;
                glfwSetInputMode(win.window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
            }
        }
        ImGui.end();
    }

    void renderTargetCracks(Camera camera, float w, float h) {
        if (win.lastTarget == null || !win.lastTarget.hit || !win.breakingActive || win.breakProgress <= 0) return;
        Matrix4f viewProj = new Matrix4f(camera.getProjectionMatrix()).mul(camera.getViewMatrix());
        int bx = win.lastTarget.hitX, by = win.lastTarget.hitY, bz = win.lastTarget.hitZ;
        float e = 0.005f;
        Vector3f[] corners = {
                new Vector3f(bx - e, by - e, bz - e), new Vector3f(bx + 1 + e, by - e, bz - e),
                new Vector3f(bx + 1 + e, by + 1 + e, bz - e), new Vector3f(bx - e, by + 1 + e, bz - e),
                new Vector3f(bx - e, by - e, bz + 1 + e), new Vector3f(bx + 1 + e, by - e, bz + 1 + e),
                new Vector3f(bx + 1 + e, by + 1 + e, bz + 1 + e), new Vector3f(bx - e, by + 1 + e, bz + 1 + e)
        };
        org.joml.Vector4f[] proj = new org.joml.Vector4f[8];
        for (int i = 0; i < 8; i++) {
            proj[i] = new org.joml.Vector4f(corners[i].x, corners[i].y, corners[i].z, 1.0f).mul(viewProj);
            if (proj[i].w > 0) {
                proj[i].x /= proj[i].w;
                proj[i].y /= proj[i].w;
            }
        }
        int finalColor = ImGui.colorConvertFloat4ToU32(0.1f, 0.1f, 0.1f, 0.5f + win.breakProgress * 0.4f);
        float finalThickness = 2.0f + win.breakProgress * 3.0f;
        var draw = ImGui.getBackgroundDrawList();
        java.util.function.BiConsumer<Integer, Integer> drawCrack = (i, j) -> {
            if (proj[i].w > 0 && proj[j].w > 0) {
                float x1 = (proj[i].x + 1) * 0.5f * w, y1 = (1 - proj[i].y) * 0.5f * h;
                float x2 = (proj[j].x + 1) * 0.5f * w, y2 = (1 - proj[j].y) * 0.5f * h;
                draw.addLine(x1, y1, x2, y2, finalColor, finalThickness);
            }
        };
        if (win.breakProgress > 0.1f) {
            drawCrack.accept(0, 6);
            drawCrack.accept(3, 5);
        }
        if (win.breakProgress > 0.4f) {
            drawCrack.accept(1, 7);
            drawCrack.accept(2, 4);
        }
        if (win.breakProgress > 0.7f) {
            drawCrack.accept(0, 2);
            drawCrack.accept(5, 7);
        }
    }

    /** Simple gatling-gun glyph for the hotbar (body + barrel cluster + grip). */
    private void drawGunIcon(imgui.ImDrawList draw, float x0, float y0, float x1, float y1) {
        float w = x1 - x0, h = y1 - y0;
        int metal = ImGui.colorConvertFloat4ToU32(0.34f, 0.34f, 0.40f, 1f);
        int dark  = ImGui.colorConvertFloat4ToU32(0.12f, 0.12f, 0.16f, 1f);
        draw.addRectFilled(x0 + w*0.04f, y0 + h*0.40f, x0 + w*0.62f, y0 + h*0.72f, metal, 2f);  // body
        draw.addRectFilled(x0 + w*0.14f, y0 + h*0.66f, x0 + w*0.30f, y1,            dark, 1.5f); // grip
        for (int k = 0; k < 3; k++) {                                                            // barrels
            float yy = y0 + h * (0.46f + 0.10f * k);
            draw.addLine(x0 + w*0.55f, yy, x1, yy, dark, 2.4f);
        }
    }

    /** Simple torch glyph for the hotbar (wooden stick + glowing flame). */
    private void drawTorchIcon(imgui.ImDrawList draw, float x0, float y0, float x1, float y1) {
        float w = x1 - x0, h = y1 - y0, cx = (x0 + x1) * 0.5f;
        int wood    = ImGui.colorConvertFloat4ToU32(0.45f, 0.30f, 0.14f, 1f);
        int flameO  = ImGui.colorConvertFloat4ToU32(1.0f, 0.50f, 0.10f, 1f);
        int flameY  = ImGui.colorConvertFloat4ToU32(1.0f, 0.90f, 0.45f, 1f);
        draw.addRectFilled(cx - w*0.08f, y0 + h*0.42f, cx + w*0.08f, y1, wood, 1.5f);  // stick
        draw.addCircleFilled(cx, y0 + h*0.36f, w*0.24f, flameO);                       // flame
        draw.addCircleFilled(cx, y0 + h*0.32f, w*0.13f, flameY);                       // hot core
    }
    private void drawTelescopeIcon(imgui.ImDrawList draw, float x0, float y0, float x1, float y1) {
        float w = x1 - x0, h = y1 - y0;
        int brass = ImGui.colorConvertFloat4ToU32(0.8f, 0.6f, 0.2f, 1f);
        int glass = ImGui.colorConvertFloat4ToU32(0.4f, 0.8f, 1.0f, 1f);
        draw.addLine(x0 + w * 0.2f, y1 - h * 0.2f, x1 - w * 0.2f, y0 + h * 0.2f, brass, 4f);
        draw.addCircleFilled(x1 - w * 0.2f, y0 + h * 0.2f, 4f, glass, 8);
    }

    /** Sniper glyph  -  a scope reticle with crosshairs over a faint purple charge glow. */
    private void drawSniperIcon(imgui.ImDrawList draw, float x0, float y0, float x1, float y1) {
        float cx = (x0 + x1) * 0.5f, cy = (y0 + y1) * 0.5f;
        float r = Math.min(x1 - x0, y1 - y0) * 0.40f;
        int glow = ImGui.colorConvertFloat4ToU32(0.55f, 0.30f, 1.0f, 0.30f);
        int rim  = ImGui.colorConvertFloat4ToU32(0.80f, 0.70f, 1.0f, 1f);
        int line = ImGui.colorConvertFloat4ToU32(0.95f, 0.92f, 1.0f, 0.95f);
        int dot  = ImGui.colorConvertFloat4ToU32(1.0f, 0.45f, 0.45f, 1f);
        draw.addCircleFilled(cx, cy, r * 0.9f, glow);          // charge aura
        draw.addCircle(cx, cy, r, rim, 20, 2.0f);              // scope ring
        draw.addLine(cx - r, cy, cx + r, cy, line, 1.3f);      // horizontal crosshair
        draw.addLine(cx, cy - r, cx, cy + r, line, 1.3f);      // vertical crosshair
        draw.addCircleFilled(cx, cy, r * 0.16f, dot);          // centre pip
    }

    /** Orbital Annihilation glyph  -  satellite dot raining a beam onto a target ring. */
    private void drawOrbitalIcon(imgui.ImDrawList draw, float x0, float y0, float x1, float y1) {
        float w = x1 - x0, h = y1 - y0, cx = (x0 + x1) * 0.5f;
        int sat   = ImGui.colorConvertFloat4ToU32(1.0f, 0.92f, 0.55f, 1f);
        int beam  = ImGui.colorConvertFloat4ToU32(1.0f, 0.65f, 0.18f, 0.9f);
        int ring  = ImGui.colorConvertFloat4ToU32(1.0f, 0.45f, 0.12f, 1f);
        draw.addCircleFilled(cx, y0 + h * 0.16f, w * 0.10f, sat);           // satellite
        draw.addTriangleFilled(cx - w*0.10f, y0 + h*0.18f,                  // widening beam
                cx + w*0.10f, y0 + h*0.18f, cx, y1 - h*0.22f, beam);
        draw.addCircle(cx, y1 - h * 0.18f, w * 0.26f, ring, 16, 2.2f);      // target ring
    }

    /** Time Domain glyph  -  a clock face with hands. */
    private void drawTimeStopIcon(imgui.ImDrawList draw, float x0, float y0, float x1, float y1) {
        float cx = (x0 + x1) * 0.5f, cy = (y0 + y1) * 0.5f;
        float r = Math.min(x1 - x0, y1 - y0) * 0.40f;
        int face = ImGui.colorConvertFloat4ToU32(0.10f, 0.55f, 0.70f, 0.85f);
        int rim  = ImGui.colorConvertFloat4ToU32(0.45f, 0.95f, 1.0f, 1f);
        int hand = ImGui.colorConvertFloat4ToU32(1.0f, 1.0f, 1.0f, 1f);
        draw.addCircleFilled(cx, cy, r, face, 20);
        draw.addCircle(cx, cy, r, rim, 20, 2.0f);
        draw.addLine(cx, cy, cx, cy - r * 0.7f, hand, 2.0f);               // minute hand (12)
        draw.addLine(cx, cy, cx + r * 0.5f, cy, hand, 2.0f);              // hour hand (3)
    }

    /** Stone Cannon glyph  -  a stone barrel firing a boulder. */
    private void drawStoneCannonIcon(imgui.ImDrawList draw, float x0, float y0, float x1, float y1) {
        float w = x1 - x0, h = y1 - y0;
        int stone = ImGui.colorConvertFloat4ToU32(0.45f, 0.42f, 0.38f, 1f);
        int dark  = ImGui.colorConvertFloat4ToU32(0.20f, 0.18f, 0.16f, 1f);
        int rock  = ImGui.colorConvertFloat4ToU32(0.62f, 0.58f, 0.50f, 1f);
        draw.addRectFilled(x0 + w*0.06f, y0 + h*0.52f, x0 + w*0.62f, y0 + h*0.86f, stone, 3f); // barrel
        draw.addRectFilled(x0 + w*0.50f, y0 + h*0.48f, x0 + w*0.66f, y0 + h*0.90f, dark, 2f);  // muzzle
        draw.addCircleFilled(x1 - w*0.18f, y0 + h*0.40f, w*0.16f, rock);  // boulder
    }

    /**
     * Simple faux-3D block icon: an isometric top + two side faces.
     * Renders using the block's actual textures from the atlas when available,
     * and falls back to flat shaded colors otherwise.
     */
    private void drawBlockIcon(imgui.ImDrawList draw, Block b, float x0, float y0, float x1, float y1) {
        float w = x1 - x0, h = y1 - y0;
        float cx = (x0 + x1) * 0.5f;
        float topY = y0 + h * 0.04f;
        float midY = y0 + h * 0.36f;
        float botY = y1 - h * 0.04f;

        // Calculate the correct bottom-left and bottom-right height for equal vertical edges
        float sideBotY = y0 + h * 0.66f;

        // Slightly translucent blocks (leaves, water) read better fully opaque as an icon.
        float r = b.r, g = b.g, bl = b.b;
        int topCol  = ImGui.colorConvertFloat4ToU32(Math.min(1f, r * 1.15f), Math.min(1f, g * 1.15f), Math.min(1f, bl * 1.15f), 1f);
        int leftCol = ImGui.colorConvertFloat4ToU32(r * 0.80f, g * 0.80f, bl * 0.80f, 1f);
        int rightCol= ImGui.colorConvertFloat4ToU32(r * 0.55f, g * 0.55f, bl * 0.55f, 1f);
        int edge    = ImGui.colorConvertFloat4ToU32(0f, 0f, 0f, 0.55f);

        // If the block has a texture and the atlas is loaded, draw the textured faces
        if (b.texName != null && com.leaf.game.render.BlockTextureAtlas.isLoaded()) {
            int texId = com.leaf.game.render.BlockTextureAtlas.getTextureId();

            // Retrieve the UV coordinates for Top (0), Left/West (5), and Right/East (4)
            float[] topUV   = com.leaf.game.render.BlockTextureAtlas.getUV(b.texName, 0);
            float[] leftUV  = com.leaf.game.render.BlockTextureAtlas.getUV(b.texName, 5);
            float[] rightUV = com.leaf.game.render.BlockTextureAtlas.getUV(b.texName, 4);

            // 1. Draw Top Face (Diamond)
            draw.addImageQuad(texId,
                    cx, topY,               // p1 (Back corner)
                    x1, midY,               // p2 (Right corner)
                    cx, y0 + h * 0.66f,     // p3 (Front corner)
                    x0, midY,               // p4 (Left corner)
                    topUV[2], topUV[1],     // uv1 (uMax, vMin) -> Back corner
                    topUV[2], topUV[3],     // uv2 (uMax, vMax) -> Right corner
                    topUV[0], topUV[3],     // uv3 (uMin, vMax) -> Front corner
                    topUV[0], topUV[1],     // uv4 (uMin, vMin) -> Left corner
                    topCol
            );

            // 2. Draw Left Face (Sheared side)
            draw.addImageQuad(texId,
                    x0, midY,               // p1 (Top-Left)
                    cx, y0 + h * 0.66f,     // p2 (Top-Right)
                    cx, botY,               // p3 (Bottom-Right / Bottom-Center)
                    x0, sideBotY,           // p4 (Bottom-Left / Bottom-Outer)
                    leftUV[0], leftUV[1],   // uv1 (uMin, vMin)
                    leftUV[2], leftUV[1],   // uv2 (uMax, vMin)
                    leftUV[2], leftUV[3],   // uv3 (uMax, vMax)
                    leftUV[0], leftUV[3],   // uv4 (uMin, vMax)
                    leftCol
            );

            // 3. Draw Right Face (Sheared side)
            draw.addImageQuad(texId,
                    cx, y0 + h * 0.66f,     // p1 (Top-Left)
                    x1, midY,               // p2 (Top-Right)
                    x1, sideBotY,           // p3 (Bottom-Right / Bottom-Outer)
                    cx, botY,               // p4 (Bottom-Left / Bottom-Center)
                    rightUV[0], rightUV[1],  // uv1 (uMin, vMin)
                    rightUV[2], rightUV[1],  // uv2 (uMax, vMin)
                    rightUV[2], rightUV[3],  // uv3 (uMax, vMax)
                    rightUV[0], rightUV[3],  // uv4 (uMin, vMax)
                    rightCol
            );
        } else {
            // Fallback for solid-colored blocks / untextured meshes
            // Top face (diamond)
            draw.addQuadFilled(cx, topY, x1, midY, cx, y0 + h * 0.66f, x0, midY, topCol);
            // Left face
            draw.addQuadFilled(x0, midY, cx, y0 + h * 0.66f, cx, botY, x0, sideBotY, leftCol);
            // Right face
            draw.addQuadFilled(cx, y0 + h * 0.66f, x1, midY, x1, sideBotY, cx, botY, rightCol);
        }

        // Draw outer wireframe lines with the new pointed bottom
        draw.addLine(cx, topY, x0, midY, edge, 1.0f);
        draw.addLine(cx, topY, x1, midY, edge, 1.0f);
        draw.addLine(cx, y0 + h * 0.66f, cx, botY, edge, 1.0f);
        draw.addLine(x0, midY, cx, y0 + h * 0.66f, edge, 1.0f);
        draw.addLine(x1, midY, cx, y0 + h * 0.66f, edge, 1.0f);

        draw.addLine(x0, midY, x0, sideBotY, edge, 1.0f);       // Corrected left vertical edge
        draw.addLine(x1, midY, x1, sideBotY, edge, 1.0f);       // Corrected right vertical edge

        draw.addLine(x0, sideBotY, cx, botY, edge, 1.0f);       // Left slanted bottom edge
        draw.addLine(x1, sideBotY, cx, botY, edge, 1.0f);       // Right slanted bottom edge
    }
    void renderHUD(Camera camera, float screenW, float screenH) {
        var draw = ImGui.getForegroundDrawList();
        float cx = screenW / 2.0f, cy = screenH / 2.0f;

        // ── MULTIPLAYER STATUS  -  confirm sync at a glance ─────────────────────
        // Shows the live link + the peer's synced HP (drops when you damage them) +
        // troop counts, so it's obvious whether syncing actually works.
        if (win.network != null) {
            boolean up = win.network.connected;
            int foeTroops = win.network.remoteSummons.length / 3;
            String line = up
                ? String.format("MP LINK OK   Foe HP %.0f/%.0f   You troops %d  Foe troops %d  ([ ] ] spawn)",
                        win.remotePlayer != null ? win.remotePlayer.health : 0f,
                        win.remotePlayer != null ? win.remotePlayer.maxHealth : 0f,
                        win.mySummons.size(), foeTroops)
                : "MP LINK DOWN  -  check Host IP / firewall, or a desync dropped it (see console)";
            int col = up ? ImGui.colorConvertFloat4ToU32(0.4f, 1f, 0.5f, 0.95f)
                         : ImGui.colorConvertFloat4ToU32(1f, 0.4f, 0.3f, 0.95f);
            draw.addText(14f, 40f, col, line);

            // ── KILL BANNER  -  big and clear when you eliminate the opponent ──
            if (win.killBannerTimer > 0f) {
                float a = Math.min(1f, win.killBannerTimer / 1.5f);
                String txt = "ENEMY ELIMINATED";
                ImFont font = ImGui.getFont();
                float scale = 2.6f;
                float sz = font.getFontSize() * scale;
                float tw = ImGui.calcTextSize(txt).x * scale;
                int gold = ImGui.colorConvertFloat4ToU32(1f, 0.85f, 0.25f, a);
                int shad = ImGui.colorConvertFloat4ToU32(0f, 0f, 0f, a * 0.7f);
                draw.addText(font, sz, cx - tw / 2f + 2f, screenH * 0.26f + 2f, shad, txt);
                draw.addText(font, sz, cx - tw / 2f,      screenH * 0.26f,      gold, txt);
            }
        }

        // ── STARGAZING ─────────────────────────────────────────────────────────
        if (win.showConstellations) {
            int starC = ImGui.colorConvertFloat4ToU32(0.7f, 0.85f, 1.0f, 0.85f);
            draw.addText(14f, screenH - 120f, starC,
                    "Constellations ON  [F2] toggle   [=] snow toggle");
        }
        if (win.constellName != null) {
            String txt = win.constellName;
            int gold  = ImGui.colorConvertFloat4ToU32(1.0f, 0.92f, 0.60f, 0.95f);
            int shad  = ImGui.colorConvertFloat4ToU32(0f, 0f, 0f, 0.7f);
            float tw  = ImGui.calcTextSize(txt).x;
            draw.addText(cx - tw/2 + 1, screenH * 0.18f + 1, shad, txt);
            draw.addText(cx - tw/2,     screenH * 0.18f,      gold, txt);
        }

        // (Lightning bolts are real 3D geometry now — see Window.fxJaggedBolt.)

        // ── SCREEN FLASH on fresh strike ──────────────────────────────────────
        // When a new bolt is at peak brightness, flash the whole screen white briefly
        float peakBright = 0f;
        for (com.leaf.game.entity.LightningController.LightningBolt b : win.player.lightning.activeBolts) {
            if (b.brightness() > 0.85f) peakBright = Math.max(peakBright, b.brightness());
        }
        if (peakBright > 0.85f) {
            float flashA = (peakBright - 0.85f) / 0.15f * 0.15f;
            draw.addRectFilled(0, 0, screenW, screenH,
                    ImGui.colorConvertFloat4ToU32(0.85f, 0.90f, 1.0f, flashA));
        }

        // ── GRAB IMPACT / THROW FLASH ────────────────────────────────────────
        // throwFlash > 0 on the frame an enemy is thrown/slammed, and on impact.
        // Gives the hit a bright orange punch flash across the screen.
        float grabFlash = win.player.grab.throwFlash;
        if (grabFlash > 0.01f) {
            // Edge burst: bright orange, very brief
            float edgeA = grabFlash * 0.65f;
            float innerA = grabFlash * 0.20f;
            // Full-screen orange tint (very subtle  -  just a wash of colour)
            draw.addRectFilled(0, 0, screenW, screenH,
                    ImGui.colorConvertFloat4ToU32(1.0f, 0.45f, 0.05f, innerA));
            // Strong orange flash on all four edges (like a punch frame)
            float edgeW = 28f * grabFlash;
            int edgeCol = ImGui.colorConvertFloat4ToU32(1.0f, 0.35f, 0.0f, edgeA);
            draw.addRectFilled(0, 0, screenW, edgeW, edgeCol);
            draw.addRectFilled(0, screenH - edgeW, screenW, screenH, edgeCol);
            draw.addRectFilled(0, edgeW, edgeW, screenH - edgeW, edgeCol);
            draw.addRectFilled(screenW - edgeW, edgeW, screenW, screenH - edgeW, edgeCol);
        }

        // ── KAMUI PHASE BORDER EFFECT ─────────────────────────────────────────
        // Shimmering purple frame around the screen edges while Kamui is active.
        if (win.player.abilities.isKamui) {
            float urgency = 1f - win.player.abilities.kamuiTimer / GameConfig.kamuiMaxDuration;
            float pulse = 0.5f + 0.5f * (float) Math.sin(glfwGetTime() * 6.0 + urgency * 4.0);
            float edgeAlpha = 0.4f + urgency * 0.25f + pulse * 0.15f;
            int edgeColor = ImGui.colorConvertFloat4ToU32(0.6f, 0.0f, 1.0f, edgeAlpha);
            float edgeW = 18f + pulse * 8f;
            draw.addRectFilled(0, 0, screenW, edgeW, edgeColor);
            draw.addRectFilled(0, screenH - edgeW, screenW, screenH, edgeColor);
            draw.addRectFilled(0, edgeW, edgeW, screenH - edgeW, edgeColor);
            draw.addRectFilled(screenW - edgeW, edgeW, screenW, screenH - edgeW, edgeColor);

            // ── KAMUI ABSORPTION VORTEX ──────────────────────────────────────
            // When the win.player holds LMB while in Kamui, a spiraling distortion
            // vortex builds up around the targeted position, mimicking Obito's
            // Kamui eye technique from Naruto.
            if (win.player.abilities.isAbsorbing || win.player.abilities.absorptionCharge > 0f) {
                float charge = win.player.abilities.absorptionCharge;
                float vx = win.player.abilities.absorptionScrX;
                float vy = win.player.abilities.absorptionScrY;
                double t = glfwGetTime();

                // Outer pulsing glow around the vortex origin
                float glowR = 30f + charge * 90f;
                draw.addCircleFilled(vx, vy, glowR,
                        ImGui.colorConvertFloat4ToU32(0.55f, 0.0f, 1.0f, 0.20f * charge));

                // Spinning concentric rings that tighten as charge builds
                int numRings = 4;
                for (int ri = 0; ri < numRings; ri++) {
                    float ringPhase = (float) (t * (2.5f + ri * 0.8f)) + ri * (float) (Math.PI / numRings);
                    float ringRadius = glowR * (0.3f + ri * 0.22f) * (1f - charge * 0.4f);
                    float ringAlpha = 0.5f * charge;
                    int ringCol = ImGui.colorConvertFloat4ToU32(0.7f, 0.1f, 1.0f, ringAlpha);
                    draw.addCircle(vx, vy, ringRadius, ringCol, 48, 2.5f);
                }

                // Spiral arms  -  lines drawn from vortex outward along rotating angles
                int numArms = 8;
                for (int ai = 0; ai < numArms; ai++) {
                    float baseAngle = (float) (t * 3.5 + ai * Math.PI * 2.0 / numArms);
                    float armLen = (50f + charge * 130f);
                    // Each arm has segments that taper inward (spiral toward centre)
                    int armSegs = 10;
                    for (int seg = 0; seg < armSegs; seg++) {
                        float tSeg = (float) seg / armSegs;
                        float tSeg1 = (float) (seg + 1) / armSegs;
                        float angle0 = baseAngle - tSeg * 1.8f * charge;
                        float angle1 = baseAngle - tSeg1 * 1.8f * charge;
                        float r0 = armLen * (1f - tSeg);
                        float r1a = armLen * (1f - tSeg1);
                        float sx0 = vx + (float) Math.cos(angle0) * r0;
                        float sy0 = vy + (float) Math.sin(angle0) * r0;
                        float sx1 = vx + (float) Math.cos(angle1) * r1a;
                        float sy1 = vy + (float) Math.sin(angle1) * r1a;
                        float aa = (1f - tSeg) * charge * 0.80f;
                        int armCol = ImGui.colorConvertFloat4ToU32(0.65f, 0.0f, 1.0f, aa);
                        draw.addLine(sx0, sy0, sx1, sy1, armCol, 2.5f + charge * 2.5f);
                    }
                }

                // Bright core that pulses faster as it approaches full charge
                float corePulse = 0.5f + 0.5f * (float) Math.sin(t * (8.0 + charge * 14.0));
                float coreR = 6f + corePulse * 10f * charge;
                draw.addCircleFilled(vx, vy, coreR,
                        ImGui.colorConvertFloat4ToU32(0.9f, 0.5f, 1.0f, 0.85f * charge));

                // Near-complete: charge bar flashes bright white
                if (charge > 0.85f) {
                    float flashA = (charge - 0.85f) / 0.15f * 0.55f;
                    draw.addCircleFilled(vx, vy, 28f * charge,
                            ImGui.colorConvertFloat4ToU32(1f, 1f, 1f, flashA));
                }
            }
        }

        // ── MANHATTAN TRANSFER 2D OFF-SCREEN INDICATOR ────────────────────────
        if (win.player.stand.isDeployed() && !win.player.stand.isInStandPerspective()) {

            // 1. Use the Matrix only to check if the drone is visible on-screen
            Matrix4f viewProj = new Matrix4f(camera.getProjectionMatrix()).mul(camera.getViewMatrix());
            Vector3f dronePos = win.player.stand.standPos;

            org.joml.Vector4f clipPos = new org.joml.Vector4f(dronePos.x, dronePos.y, dronePos.z, 1.0f).mul(viewProj);
            boolean inFront = clipPos.w > 0.0f;
            float ndcX = clipPos.x / Math.abs(clipPos.w);
            float ndcY = clipPos.y / Math.abs(clipPos.w);
            boolean onScreen = inFront && Math.abs(ndcX) <= 1.0f && Math.abs(ndcY) <= 1.0f;

            // 2. Draw the edge arrow ONLY if off-screen and not during Voyage (it would
            //    overlap the voyage objective banner at the top of the screen).
            if (!onScreen && !win.voyageStarted) {
                // To avoid matrix flipping bugs, we use simple Dot Products with
                // the camera's local axes to find the true, stable direction.
                Vector3f toDrone = new Vector3f(dronePos).sub(camera.position).normalize();
                Vector3f right = camera.getRight();
                // Cross product: Right x Forward = Local Up Vector
                Vector3f up = new Vector3f(right).cross(camera.getLookDirection()).normalize();

                // Screen X grows right. Screen Y grows DOWN (so we invert Y).
                float dirX = toDrone.dot(right);
                float dirY = -toDrone.dot(up);

                float angle = (float) Math.atan2(dirY, dirX);
                float radius = Math.min(cx, cy) * 0.85f;

                float indicatorX = cx + (float) Math.cos(angle) * radius;
                float indicatorY = cy + (float) Math.sin(angle) * radius;

                // 3. Draw Custom "Tag" Shape (Made slightly larger)
                float L = 46f;      // Total length of the shape
                float W = 32f;      // Total width
                float tipL = 16f;   // Length of the pointy triangle tip

                float[][] pts = {
                        {L / 2, 0},               // 0: Tip
                        {L / 2 - tipL, W / 2},      // 1: Top Right corner
                        {-L / 2, W / 2},            // 2: Top Left corner
                        {-L / 2, -W / 2},           // 3: Bottom Left corner
                        {L / 2 - tipL, -W / 2}      // 4: Bottom Right corner
                };

                // Rotate and translate vertices to the edge of the screen
                float cosA = (float) Math.cos(angle);
                float sinA = (float) Math.sin(angle);
                for (int i = 0; i < 5; i++) {
                    float px = pts[i][0];
                    float py = pts[i][1];
                    float rotX = px * cosA - py * sinA;
                    float rotY = px * sinA + py * cosA;
                    pts[i][0] = indicatorX + rotX;
                    pts[i][1] = indicatorY + rotY;
                }

                // Colors
                int goldFill = ImGui.colorConvertFloat4ToU32(1.0f, 0.85f, 0.15f, 0.95f);
                int outline = ImGui.colorConvertFloat4ToU32(0.1f, 0.1f, 0.1f, 1.0f);
                int redAlert = ImGui.colorConvertFloat4ToU32(1.0f, 0.15f, 0.15f, 1.0f);

                // Fill the shape
                draw.addQuadFilled(pts[1][0], pts[1][1], pts[2][0], pts[2][1], pts[3][0], pts[3][1], pts[4][0], pts[4][1], goldFill);
                draw.addTriangleFilled(pts[0][0], pts[0][1], pts[1][0], pts[1][1], pts[4][0], pts[4][1], goldFill);

                // Draw thick outline
                float thick = 2.5f;
                draw.addLine(pts[0][0], pts[0][1], pts[1][0], pts[1][1], outline, thick);
                draw.addLine(pts[1][0], pts[1][1], pts[2][0], pts[2][1], outline, thick);
                draw.addLine(pts[2][0], pts[2][1], pts[3][0], pts[3][1], outline, thick);
                draw.addLine(pts[3][0], pts[3][1], pts[4][0], pts[4][1], outline, thick);
                draw.addLine(pts[4][0], pts[4][1], pts[0][0], pts[0][1], outline, thick);

                // 4. Draw large, bright RED "!"
                float textOffsetX = -tipL / 2.0f;
                float centerIconX = indicatorX + textOffsetX * cosA;
                float centerIconY = indicatorY + textOffsetX * sinA;

                // Draw exclamation mark using filled rectangles (bypassing text limits so it's massive)
                // Top bar
                draw.addRectFilled(centerIconX - 2.5f, centerIconY - 9f, centerIconX + 2.5f, centerIconY + 3f, redAlert);
                // Bottom dot
                draw.addRectFilled(centerIconX - 2.5f, centerIconY + 6f, centerIconX + 2.5f, centerIconY + 11f, redAlert);

                // Outline the exclamation mark so it pops beautifully inside the gold box
                draw.addRect(centerIconX - 2.5f, centerIconY - 9f, centerIconX + 2.5f, centerIconY + 3f, outline, 0f, 0, 1.5f);
                draw.addRect(centerIconX - 2.5f, centerIconY + 6f, centerIconX + 2.5f, centerIconY + 11f, outline, 0f, 0, 1.5f);
                // Outline the exclamation mark so it pops beautifully inside the gold box
                draw.addRect(centerIconX - 2.5f, centerIconY - 9f, centerIconX + 2.5f, centerIconY + 3f, outline, 0f, 0, 1.5f);
                draw.addRect(centerIconX - 2.5f, centerIconY + 6f, centerIconX + 2.5f, centerIconY + 11f, outline, 0f, 0, 1.5f);
            } else {
                // ── ON-SCREEN 2D DRONE OUTLINE (Always bright, always visible!) ──
                float dScrX = (ndcX * 0.5f + 0.5f) * screenW;
                float dScrY = (1f - (ndcY * 0.5f + 0.5f)) * screenH;

                float dist = new Vector3f(dronePos).sub(camera.position).length();
                // Scale the 2D UI box so it shrinks slightly at a distance, but never gets too tiny
                float s = Math.max(14f, Math.min(45f, 400f / dist));

                boolean aimingAtDrone = win.player.attacks.isAimingAtStand(camera);

                int yellow = ImGui.colorConvertFloat4ToU32(1.0f, 0.85f, 0.10f, 1.0f);
                int dimYellow = ImGui.colorConvertFloat4ToU32(1.0f, 0.85f, 0.10f, 0.45f); // 45% opacity when idle

                int brightRed = ImGui.colorConvertFloat4ToU32(1.0f, 0.0f, 0.0f, 1.0f); // 100% opacity bright red
                int dimRed = ImGui.colorConvertFloat4ToU32(1.0f, 0.0f, 0.0f, 0.45f); // 45% opacity dim red

                int color = aimingAtDrone ? brightRed : dimRed;
                float thick = aimingAtDrone ? 3.0f : 1.5f;

                // Draw a 2D Diamond Outline exactly over the drone
                draw.addQuad(
                        dScrX, dScrY - s,
                        dScrX + s, dScrY,
                        dScrX, dScrY + s,
                        dScrX - s, dScrY, color, thick);

                if (aimingAtDrone) {
                    // Draw a striking inner crosshair when locked on
                    float in = s * 0.5f;
                    draw.addLine(dScrX - in, dScrY, dScrX + in, dScrY, yellow, 2.0f);
                    draw.addLine(dScrX, dScrY - in, dScrX, dScrY + in, yellow, 2.0f);
                }
            }
        }
        // Crosshair
        int black = ImGui.colorConvertFloat4ToU32(0, 0, 0, 0.6f);
        int white = ImGui.colorConvertFloat4ToU32(1.0f, 1.0f, 1.0f, 1.0f);

        float chargeF = win.player.attacks.getChargeFrac();

        if (chargeF > 0f) {
            // ── SNIPER / CHARGING MODE  -  red shrinking circle crosshair ──────────
            int red = ImGui.colorConvertFloat4ToU32(1.0f, 0.12f, 0.12f, 0.95f);
            float ring = Math.max(4f, 18f - 13f * chargeF); // shrinks from 18 -> 5 px as charge builds

            draw.addCircle(cx, cy, ring, red, 32, 1.8f);
            draw.addCircleFilled(cx, cy, 2.2f, red, 8);

            // Four radiating lines  -  sniper style
            float gap = ring + 2f, ext = gap + 9f;
            draw.addLine(cx - ext, cy, cx - gap, cy, red, 1.5f);
            draw.addLine(cx + gap, cy, cx + ext, cy, red, 1.5f);
            draw.addLine(cx, cy - ext, cx, cy - gap, red, 1.5f);
            draw.addLine(cx, cy + gap, cx, cy + ext, red, 1.5f);

            // Charge % below crosshair
            String pct = String.format("%.0f%%", chargeF * 100f);
            draw.addText(cx - 10f, cy + ring + 8f, black, pct);
            draw.addText(cx - 11f, cy + ring + 7f, red, pct);

        } else {
            // ── NORMAL  -  plain white crosshair ────────────────────────────────────
            draw.addLine(cx - 11, cy, cx + 11, cy, black, 3.0f);
            draw.addLine(cx, cy - 11, cx, cy + 11, black, 3.0f);
            draw.addLine(cx - 10, cy, cx + 10, cy, white, 1.5f);
            draw.addLine(cx, cy - 10, cx, cy + 10, white, 1.5f);

            // ── KAMUI ABSORPTION RING  -  shows charge building around crosshair ──
            if (win.player.abilities.isKamui && win.player.abilities.absorptionCharge > 0f) {
                float ch = win.player.abilities.absorptionCharge;
                double now = glfwGetTime();
                float pulse = 0.7f + 0.3f * (float) Math.sin(now * 12.0);
                int ringCol = ImGui.colorConvertFloat4ToU32(0.8f, 0.2f, 1.0f, 0.85f * ch * pulse);
                // Clockwise fill arc  -  approximate with many small lines around the ring
                int absRing = ImGui.colorConvertFloat4ToU32(0.7f, 0.0f, 1.0f, 0.9f * ch);
                draw.addCircle(cx, cy, 22f, absRing, 48, 3.0f * ch);
                // Filling arc (simplified: full circle but alpha scaled by charge)
                if (ch < 1.0f) {
                    draw.addCircle(cx, cy, 26f,
                            ImGui.colorConvertFloat4ToU32(0.55f, 0.0f, 1.0f, 0.35f * ch), 32, 1.5f);
                }
                // Label below crosshair
                if (ch > 0.05f) {
                    String absPct = String.format("%.0f%%", ch * 100f);
                    draw.addText(cx - 12f, cy + 32f, black, absPct);
                    draw.addText(cx - 13f, cy + 31f,
                            ImGui.colorConvertFloat4ToU32(0.85f, 0.4f, 1.0f, 1f), absPct);
                }
            }
        }

        // ── REDIRECT INDICATOR ──
        boolean aimingAtDrone = !win.player.stand.isInStandPerspective() && win.player.attacks.isAimingAtStand(camera);
        boolean redirectReady = aimingAtDrone && win.player.attacks.isRedirectAvailable(win.world);

        if (aimingAtDrone) {
            // Both states are now high-contrast Yellow/Gold
            int yellow = ImGui.colorConvertFloat4ToU32(1.0f, 0.85f, 0.10f, 1.0f);
            int grey = ImGui.colorConvertFloat4ToU32(0.5f, 0.5f, 0.5f, 1.0f);

            int blackBg = ImGui.colorConvertFloat4ToU32(0.0f, 0.0f, 0.0f, 0.85f);

            float circleRadius = 12.0f; // Shorter, tighter radius (down from 18)

            if (redirectReady) {
                draw.addRectFilled(cx + 12f, cy - 20f, cx + 115f, cy + 4f, blackBg, 4f);
                draw.addText(cx + 18f, cy - 12f, yellow, "REDIRECT [C]");
                draw.addCircle(cx, cy, circleRadius, yellow, 32, 2.5f);
            } else {
                draw.addRectFilled(cx + 12f, cy - 20f, cx + 110f, cy + 4f, blackBg, 4f);
                draw.addText(cx + 18f, cy - 12f, grey, "REFLECT [C]"); // Yellow indicator
                draw.addCircle(cx, cy, circleRadius, grey, 32, 2.0f); // Yellow circle
            }
        }
        // Health bar and win.hotbar  -  hidden while piloting the drone
        if (!win.player.stand.isInStandPerspective()) {

            // Revival immunity indicator  -  pulsing golden border around the whole screen.
            if (win.immunityTimer > 0f) {
                float t = win.immunityTimer / win.REVIVAL_IMMUNITY_SECS;
                float flicker = 0.3f + 0.55f * (float) Math.abs(Math.sin(glfwGetTime() * 10f));
                float alpha = t * flicker;  // fades out as immunity expires
                int immCol = ImGui.colorConvertFloat4ToU32(1f, 0.88f, 0.3f, alpha);
                float bw = 8f;
                draw.addRect(bw, bw, screenW - bw, screenH - bw, immCol, 0f, 0, bw);
                // Small label
                String immLabel = "PROTECTED  " + (int)Math.ceil(win.immunityTimer) + "s";
                float lw = ImGui.calcTextSize(immLabel).x;
                draw.addText(cx - lw/2, screenH - 140f,
                        ImGui.colorConvertFloat4ToU32(1f, 0.95f, 0.5f, alpha * 1.5f), immLabel);
            }

            // Health bar
            float hpWidth = 200f, hpHeight = 14f;
            float hpX = cx - hpWidth / 2.0f, hpY = screenH - 80f;
            // Recent-damage pulse: bright red wash + flashing outline behind the bar.
            float dmgT = Math.max(0f, win.damageFlashTimer);
            draw.addRectFilled(hpX, hpY, hpX + hpWidth, hpY + hpHeight,
                    ImGui.colorConvertFloat4ToU32(0.2f, 0, 0, 0.8f));
            float fillW = hpWidth * (Math.max(0, win.player.health) / win.player.maxHealth);
            float flashLift = dmgT > 0f ? (0.4f * (float) Math.abs(Math.sin(glfwGetTime() * 18f))) : 0f;
            draw.addRectFilled(hpX, hpY, hpX + fillW, hpY + hpHeight,
                    ImGui.colorConvertFloat4ToU32(0.8f + flashLift, 0.1f + flashLift, 0.1f + flashLift, 1.0f));
            // Low-health warning: the whole bar gently pulses when under 30%.
            float hpFracP = Math.max(0f, win.player.health) / win.player.maxHealth;
            if (hpFracP < 0.30f) {
                float warn = 0.3f + 0.4f * (float) Math.abs(Math.sin(glfwGetTime() * 5f));
                draw.addRect(hpX - 2, hpY - 2, hpX + hpWidth + 2, hpY + hpHeight + 2,
                        ImGui.colorConvertFloat4ToU32(1f, 0.2f, 0.2f, warn), 0f, 0, 2.5f);
            }
            int hpBorder = dmgT > 0f
                    ? ImGui.colorConvertFloat4ToU32(1f, 0.9f, 0.9f, Math.min(1f, dmgT * 2f))
                    : black;
            draw.addRect(hpX, hpY, hpX + hpWidth, hpY + hpHeight, hpBorder, 0f, 0, dmgT > 0f ? 3.0f : 2.0f);
            draw.addText(hpX - 26f, hpY, ImGui.colorConvertFloat4ToU32(1f, 0.7f, 0.7f, 0.9f), "HP");

            // Mana bar (blue, below health bar)
            float mpHeight = 9f;
            float mpY = hpY + hpHeight + 3f;
            draw.addRectFilled(hpX, mpY, hpX + hpWidth, mpY + mpHeight,
                    ImGui.colorConvertFloat4ToU32(0.03f, 0.04f, 0.22f, 0.8f));
            float mpFill = hpWidth * Math.max(0f, win.player.mana / win.player.maxMana);
            draw.addRectFilled(hpX, mpY, hpX + mpFill, mpY + mpHeight,
                    ImGui.colorConvertFloat4ToU32(0.22f, 0.45f, 1.0f, 1.0f));
            draw.addRect(hpX, mpY, hpX + hpWidth, mpY + mpHeight, black, 0f, 0, 1.5f);
            if (win.player.manaFlashTimer > 0f) {
                float pulse = (float) Math.abs(Math.sin(win.player.manaFlashTimer * 18f));
                float flashAlpha = Math.min(win.player.manaFlashTimer * 2f, 1f) * 0.7f * pulse;
                draw.addRectFilled(hpX, mpY, hpX + hpWidth, mpY + mpHeight,
                        ImGui.colorConvertFloat4ToU32(1.0f, 0.1f, 0.1f, flashAlpha));
            }
            draw.addText(hpX - 26f, mpY - 2f, ImGui.colorConvertFloat4ToU32(0.55f, 0.7f, 1f, 0.9f), "MP");
            // Mana text label (compact, right-aligned inside bar)
            String mpLabel = String.format("%.0f / %.0f  MP", win.player.mana, win.player.maxMana);
            draw.addText(hpX + hpWidth - 78f, mpY - 0.5f, black, mpLabel);
            draw.addText(hpX + hpWidth - 79f, mpY - 1.5f,
                    ImGui.colorConvertFloat4ToU32(0.55f, 0.75f, 1.0f, 0.9f), mpLabel);


            // Hotbar
            float slotSize = 40.0f, spacing = 5.0f;
            int numSlots = 9;
            float startX = cx - ((numSlots * slotSize + (numSlots - 1) * spacing) / 2.0f);
            float startY = screenH - slotSize - 15.0f;
            win.selectedBlock = win.hotbar[win.selectedSlot];

            for (int i = 0; i < numSlots; i++) {
                float x = startX + i * (slotSize + spacing);
                int bgCol = (i == win.selectedSlot)
                        ? ImGui.colorConvertFloat4ToU32(0.6f, 0.6f, 0.6f, 0.8f)
                        : ImGui.colorConvertFloat4ToU32(0.2f, 0.2f, 0.2f, 0.8f);
                draw.addRectFilled(x, startY, x + slotSize, startY + slotSize, bgCol, 4.0f);
                int outCol = (i == win.selectedSlot)
                        ? ImGui.colorConvertFloat4ToU32(1.0f, 1.0f, 1.0f, 1.0f)
                        : ImGui.colorConvertFloat4ToU32(0.0f, 0.0f, 0.0f, 0.8f);
                draw.addRect(x, startY, x + slotSize, startY + slotSize, outCol, 4.0f, 0,
                        (i == win.selectedSlot) ? 3.0f : 1.5f);

                // Slot number (1-9) in the top-left corner so the keybind is obvious.
                String slotNum = String.valueOf(i + 1);
                draw.addText(x + 4, startY + 2, black, slotNum);
                draw.addText(x + 3, startY + 1,
                        ImGui.colorConvertFloat4ToU32(1f, 0.9f, 0.5f, 0.9f), slotNum);

                Block b = win.hotbar[i];
                int count = win.inventory.getCount(b);

                // Tools and ability weapons render without needing a block count.
                boolean tool = (b == Block.GATLING_GUN || b == Block.TORCH || b == Block.TELESCOPE
                        || b == Block.GRAPPLING_HOOK
                        || b == Block.WPN_ORBITAL || b == Block.WPN_SNIPER
                        || b == Block.WPN_TIMESTOP || b == Block.WPN_STONE_CANNON
                        || b == Block.WPN_DEPRIVATION);
                if (b != Block.AIR && (tool || count > 0)) {
                    float s = 8.0f;
                    float ix0 = x + s, iy0 = startY + s, ix1 = x + slotSize - s, iy1 = startY + slotSize - s;
                    switch (b) {
                        case GATLING_GUN     -> drawGunIcon(draw, ix0, iy0, ix1, iy1);
                        case TORCH           -> drawTorchIcon(draw, ix0, iy0, ix1, iy1);
                        case TELESCOPE       -> drawTelescopeIcon(draw, ix0, iy0, ix1, iy1);
                        case GRAPPLING_HOOK  -> drawGrappleIcon(draw, ix0, iy0, ix1, iy1);
                        case WPN_SNIPER      -> drawSniperIcon(draw, ix0, iy0, ix1, iy1);
                        case WPN_ORBITAL     -> drawOrbitalIcon(draw, ix0, iy0, ix1, iy1);
                        case WPN_TIMESTOP    -> drawTimeStopIcon(draw, ix0, iy0, ix1, iy1);
                        case WPN_STONE_CANNON-> drawStoneCannonIcon(draw, ix0, iy0, ix1, iy1);
                        case WPN_DEPRIVATION -> drawBlockIcon(draw, b, ix0, iy0, ix1, iy1);
                        default -> {
                            drawBlockIcon(draw, b, ix0, iy0, ix1, iy1);
                            String countStr = String.valueOf(count);
                            draw.addText(x + slotSize - 14, startY + slotSize - 18, black, countStr);
                            draw.addText(x + slotSize - 15, startY + slotSize - 19, white, countStr);
                        }
                    }
                }

                // ── COOLDOWN WHEEL: a golden time-circle sweeps closed while the
                //    weapon recharges (THE WORLD / Deprivation / Stone Cannon). ──
                float cdLeft = 0f, cdMax = 0f;
                if (b == Block.WPN_TIMESTOP && win.timeStopCooldown > 0f) {
                    cdLeft = win.timeStopCooldown;  cdMax = GameConfig.timeStopCooldownSecs;
                } else if (b == Block.WPN_DEPRIVATION && win.depCooldown > 0f) {
                    cdLeft = win.depCooldown;       cdMax = GameConfig.depCooldownSecs;
                } else if (b == Block.WPN_STONE_CANNON && win.stoneCanonCooldownTimer > 0f) {
                    cdLeft = win.stoneCanonCooldownTimer; cdMax = GameConfig.stoneCanonCooldown;
                }
                if (cdLeft > 0f && cdMax > 0f) {
                    float frac = Math.min(1f, cdLeft / cdMax);     // 1 = just used
                    float ccx = x + slotSize / 2f, ccy = startY + slotSize / 2f;
                    // Dim the slot while recharging
                    draw.addRectFilled(x, startY, x + slotSize, startY + slotSize,
                            ImGui.colorConvertFloat4ToU32(0f, 0f, 0f, 0.55f), 4.0f);
                    // The time circle: golden arc shrinking clockwise as it recharges
                    float a0 = -(float) Math.PI / 2f;
                    draw.pathClear();
                    draw.pathArcTo(ccx, ccy, slotSize * 0.32f, a0, a0 + (float) Math.PI * 2f * frac, 32);
                    draw.pathStroke(ImGui.colorConvertFloat4ToU32(1.0f, 0.82f, 0.25f, 0.95f), 0, 3.5f);
                    // Seconds remaining, centred
                    String secs = String.valueOf((int) Math.ceil(cdLeft));
                    float tw2 = ImGui.calcTextSize(secs).x;
                    draw.addText(ccx - tw2 / 2f + 1, ccy - 7f + 1, black, secs);
                    draw.addText(ccx - tw2 / 2f, ccy - 7f,
                            ImGui.colorConvertFloat4ToU32(1f, 0.95f, 0.7f, 1f), secs);
                }
                // Active-state glow: golden pulse while the Deprivation cage is up
                if (b == Block.WPN_DEPRIVATION && win.depActive) {
                    float pa = 0.5f + 0.4f * (float) Math.abs(Math.sin(glfwGetTime() * 4.0));
                    draw.addRect(x - 1, startY - 1, x + slotSize + 1, startY + slotSize + 1,
                            ImGui.colorConvertFloat4ToU32(1.0f, 0.85f, 0.2f, pa), 5f, 0, 2.5f);
                }
            }
        } // end !isInStandPerspective (health bar + win.hotbar guard)

        // ── FLIGHT MODE INDICATOR ─────────────────────────────────────────────
        if (win.player.debugMode) {
            FlightController.FlightMode mode = win.player.flightController.getMode();
            String modeLabel = switch (mode) {
                case SKIM -> "* SKIM";
                case SOAR -> "* SOAR";
                case GRAPPLE -> "* GRAPPLE";
            };
            int modeColor = switch (mode) {
                case SKIM -> ImGui.colorConvertFloat4ToU32(0.4f, 0.9f, 0.4f, 0.9f);
                case SOAR -> ImGui.colorConvertFloat4ToU32(0.4f, 0.7f, 1.0f, 0.9f);
                case GRAPPLE -> ImGui.colorConvertFloat4ToU32(1.0f, 0.75f, 0.2f, 0.9f);
            };
            draw.addText(cx - 30, cy - 60, black, modeLabel);
            draw.addText(cx - 31, cy - 61, modeColor, modeLabel);
            draw.addText(cx - 55, cy - 44, black, "[V] cycle mode");
            draw.addText(cx - 56, cy - 45,
                    ImGui.colorConvertFloat4ToU32(0.8f, 0.8f, 0.8f, 0.7f), "[V] cycle mode");

            // ── GRAPPLE FEEDBACK ─────────────────────────────────────────────
            // Gives the win.player clear state feedback so they always know whether
            // they are hooked, where the anchor is, and how to use it.
            if (mode == FlightController.FlightMode.GRAPPLE) {
                FlightController fc = win.player.flightController;
                int grappleGold = ImGui.colorConvertFloat4ToU32(1.0f, 0.78f, 0.1f, 1.0f);
                int grappleRed = ImGui.colorConvertFloat4ToU32(1.0f, 0.35f, 0.1f, 0.9f);
                int grappleGrey = ImGui.colorConvertFloat4ToU32(0.75f, 0.75f, 0.75f, 0.7f);

                if (fc.isHooked()) {
                    // Show hook distance
                    float hookDist = new Vector3f(fc.getHookPoint()).sub(win.player.position).length();
                    String hookedStr = String.format("[O] ZIPPING  %.0fm  Release to launch", hookDist);
                    draw.addText(cx - 72, cy + 28, black, hookedStr);
                    draw.addText(cx - 73, cy + 27, grappleGold, hookedStr);

                    // Draw a small "anchor" diamond at crosshair center to distinguish state
                    float d = 7f;
                    draw.addLine(cx, cy - d, cx + d, cy, grappleGold, 2.0f);
                    draw.addLine(cx + d, cy, cx, cy + d, grappleGold, 2.0f);
                    draw.addLine(cx, cy + d, cx - d, cy, grappleGold, 2.0f);
                    draw.addLine(cx - d, cy, cx, cy - d, grappleGold, 2.0f);
                } else {
                    // Unhooked: show targeting guide
                    String aimStr = "[ ] AIM + Hold [RIGHT-CLICK] or [F]";
                    draw.addText(cx - 88, cy + 28, black, aimStr);
                    draw.addText(cx - 89, cy + 27, grappleGrey, aimStr);

                    // Draw a square targeting reticle around the crosshair
                    float s = 12f;
                    // Four corner brackets
                    draw.addLine(cx - s, cy - s, cx - s + 5, cy - s, grappleRed, 1.5f);
                    draw.addLine(cx - s, cy - s, cx - s, cy - s + 5, grappleRed, 1.5f);
                    draw.addLine(cx + s, cy - s, cx + s - 5, cy - s, grappleRed, 1.5f);
                    draw.addLine(cx + s, cy - s, cx + s, cy - s + 5, grappleRed, 1.5f);
                    draw.addLine(cx - s, cy + s, cx - s + 5, cy + s, grappleRed, 1.5f);
                    draw.addLine(cx - s, cy + s, cx - s, cy + s - 5, grappleRed, 1.5f);
                    draw.addLine(cx + s, cy + s, cx + s - 5, cy + s, grappleRed, 1.5f);
                    draw.addLine(cx + s, cy + s, cx + s, cy + s - 5, grappleRed, 1.5f);
                }
            }
        }

        // ── CANNONBALL CHARGE BAR + READINESS ────────────────────────────────
        if (win.player.abilities.isCharging()) {
            float barW = 180f, barH = 12f;
            float barX = cx - barW / 2f, barY = cy + 42f;

            // Power bar (gold -> red-orange)
            draw.addRectFilled(barX, barY, barX + barW, barY + barH,
                    ImGui.colorConvertFloat4ToU32(0.1f, 0.1f, 0.1f, 0.7f), 4f);
            float fill = win.player.abilities.chargePower;
            int barColor = fill >= 0.99f
                    ? ImGui.colorConvertFloat4ToU32(1.0f, 0.3f, 0.05f, 1.0f)
                    : ImGui.colorConvertFloat4ToU32(1.0f, 0.75f, 0.1f, 1.0f);
            draw.addRectFilled(barX, barY, barX + barW * fill, barY + barH, barColor, 4f);
            draw.addRect(barX, barY, barX + barW, barY + barH, black, 4f, 0, 1.5f);

            String powerLabel = fill >= 0.99f ? "FULL POWER" : String.format("CHARGING %.0f%%", fill * 100f);
            draw.addText(cx - 43, barY + 16f, black, powerLabel);
            draw.addText(cx - 44, barY + 15f, barColor, powerLabel);

            // Readiness bar (blue  -  shows chunk generation progress)
            float rdyBarY = barY + 30f;
            draw.addRectFilled(barX, rdyBarY, barX + barW, rdyBarY + barH,
                    ImGui.colorConvertFloat4ToU32(0.05f, 0.05f, 0.15f, 0.7f), 4f);
            int rdyColor = win.pathReadiness >= 0.95f
                    ? ImGui.colorConvertFloat4ToU32(0.2f, 1.0f, 0.4f, 1.0f)   // ready = green
                    : ImGui.colorConvertFloat4ToU32(0.3f, 0.6f, 1.0f, 1.0f);  // loading = blue
            draw.addRectFilled(barX, rdyBarY, barX + barW * win.pathReadiness, rdyBarY + barH, rdyColor, 4f);
            draw.addRect(barX, rdyBarY, barX + barW, rdyBarY + barH, black, 4f, 0, 1.5f);

            String rdyLabel = win.pathReadiness >= 0.95f
                    ? "PATH CLEAR  -  release [G] to fire!"
                    : String.format("Loading terrain... %.0f%%", win.pathReadiness * 100f);
            int rdyTextColor = win.pathReadiness >= 0.95f
                    ? ImGui.colorConvertFloat4ToU32(0.2f, 1.0f, 0.4f, 0.95f)
                    : ImGui.colorConvertFloat4ToU32(0.5f, 0.75f, 1.0f, 0.85f);
            draw.addText(cx - 70, rdyBarY + 16f, black, rdyLabel);
            draw.addText(cx - 71, rdyBarY + 15f, rdyTextColor, rdyLabel);

            // Crosshair lock indicator (camera is frozen to aim direction)
            draw.addText(cx - 55, cy - 22, black, "[ AIM LOCKED ]");
            draw.addText(cx - 56, cy - 23,
                    ImGui.colorConvertFloat4ToU32(1.0f, 0.85f, 0.3f, 0.85f), "[ AIM LOCKED ]");
        }

        // ── REWIND TRAIL INDICATOR ────────────────────────────────────────────
        if (win.player.abilities.isRewinding) {
            draw.addText(cx - 42, cy - 90, black, "<< REWINDING >>");
            draw.addText(cx - 43, cy - 91,
                    ImGui.colorConvertFloat4ToU32(0.3f, 0.6f, 1.0f, 1.0f), "<< REWINDING >>");
        }

        // ── SMASH INDICATOR ───────────────────────────────────────────────────
        if (win.player.isSmashing()) {
            draw.addText(cx - 35, cy - 90, black, "\\/ SMASHING \\/");
            draw.addText(cx - 36, cy - 91,
                    ImGui.colorConvertFloat4ToU32(1.0f, 0.3f, 0.1f, 1.0f), "\\/ SMASHING \\/");
        } else if (win.showSmashHint) {
            float pulse = 0.7f + 0.3f * (float) Math.abs(Math.sin(ImGui.getTime() * 3.0));
            int hintCol = ImGui.colorConvertFloat4ToU32(1.0f, 0.85f, 0.2f, pulse);
            String hint = "[SHIFT]  GROUND SMASH";
            float hw = ImGui.calcTextSize(hint).x;
            draw.addText(cx - hw / 2 + 1, cy - 92, black, hint);
            draw.addText(cx - hw / 2, cy - 93, hintCol, hint);
        }

        // ── STAND PERSPECTIVE OVERLAY ────────────────────────────────────────
        // When piloting the drone: replace most HUD with a clean drone cockpit view.
        if (win.player.stand.isInStandPerspective()) {
            // Dim border vignette to indicate we are in drone mode
            int vigCol = ImGui.colorConvertFloat4ToU32(0.05f, 0.1f, 0.35f, 0.55f);
            float vEdge = 80f;
            draw.addRectFilled(0f, screenH - vEdge, screenW, screenH, vigCol);
            draw.addRectFilled(0f, 0f, screenW, vEdge, vigCol);
            draw.addRectFilled(0f, 0f, vEdge, screenH, vigCol);
            draw.addRectFilled(screenW - vEdge, 0f, screenW, screenH, vigCol);

            // ── Stand health bar ──────────────────────────────────────────────
            float shW = 160f, shH = 12f;
            float shX = cx - shW / 2f, shY = 18f;
            int shBg = ImGui.colorConvertFloat4ToU32(0.05f, 0.05f, 0.2f, 0.85f);
            int shFg = ImGui.colorConvertFloat4ToU32(0.25f, 0.65f, 1.0f, 1.0f);
            draw.addRectFilled(shX, shY, shX + shW, shY + shH, shBg, 4f);
            float shFill = (win.player.stand.standHealth / GameConfig.standMaxHealth) * shW;
            draw.addRectFilled(shX, shY, shX + shFill, shY + shH, shFg, 4f);
            draw.addRect(shX, shY, shX + shW, shY + shH, black, 4f, 0, 1.5f);
            String shLabel = String.format("STAND HP  %.0f / %.0f",
                    win.player.stand.standHealth, GameConfig.standMaxHealth);
            draw.addText(cx - 50f, shY + shH + 4f, black, shLabel);
            draw.addText(cx - 51f, shY + shH + 3f, ImGui.colorConvertFloat4ToU32(0.6f, 0.85f, 1.0f, 0.9f), shLabel);

            // ── LOS status indicators ─────────────────────────────────────────
            // Two dots: owner->stand (left), stand->target (right).
            // Green = clear, red = blocked.
            float dotY = shY + shH + 26f;
            float dotR = 5f;
            int losGreen = ImGui.colorConvertFloat4ToU32(0.2f, 1.0f, 0.35f, 1.0f);
            int losRed = ImGui.colorConvertFloat4ToU32(1.0f, 0.2f, 0.15f, 1.0f);
            int losDim = ImGui.colorConvertFloat4ToU32(0.4f, 0.4f, 0.4f, 0.6f);

            // Owner->Stand
            float d1X = cx - 28f;
            draw.addCircleFilled(d1X, dotY, dotR,
                    win.player.stand.losOwnerToStand ? losGreen : losRed, 16);
            draw.addCircle(d1X, dotY, dotR, black, 16, 1.2f);
            draw.addText(d1X - 14f, dotY + 8f,
                    ImGui.colorConvertFloat4ToU32(0.75f, 0.75f, 0.75f, 0.8f), "OWNER");

            // Connecting line
            draw.addLine(cx - 22f, dotY, cx + 22f, dotY, losDim, 1.2f);

            // Stand->Target
            float d2X = cx + 28f;
            draw.addCircleFilled(d2X, dotY, dotR,
                    win.player.stand.losStandToTarget ? losGreen : losRed, 16);
            draw.addCircle(d2X, dotY, dotR, black, 16, 1.2f);
            draw.addText(d2X - 12f, dotY + 8f,
                    ImGui.colorConvertFloat4ToU32(0.75f, 0.75f, 0.75f, 0.8f), "TGT");

            // Fire-ready composite state
            boolean readyToFire = win.player.stand.losOwnerToStand && win.player.stand.losStandToTarget;
            String fireHint = readyToFire ? "[LMB] Fire redirect shot" : "No clear line of fire";
            int fireColor = readyToFire
                    ? ImGui.colorConvertFloat4ToU32(0.2f, 1.0f, 0.4f, 0.95f)
                    : ImGui.colorConvertFloat4ToU32(1.0f, 0.4f, 0.2f, 0.75f);
            draw.addText(cx - 65f, dotY + 20f, black, fireHint);
            draw.addText(cx - 66f, dotY + 19f, fireColor, fireHint);

            // ── Master position marker (glowing dot + distance) ───────────────
            // Project win.player body position to screen so pilot can see where master is.
            Camera sc = win.player.stand.standCamera;
            Matrix4f svp = new Matrix4f(sc.getProjectionMatrix()).mul(sc.getViewMatrix());
            org.joml.Vector4f masterClip = new org.joml.Vector4f(
                    win.player.position.x, win.player.position.y + 0.9f, win.player.position.z, 1f).mul(svp);
            boolean masterInFront = masterClip.w > 0f;
            float masterDist = new Vector3f(win.player.stand.standPos).sub(win.player.position).length();
            String distLabel = String.format("%.0fm", masterDist);

            if (masterInFront) {
                float mNdcX = masterClip.x / masterClip.w;
                float mNdcY = masterClip.y / masterClip.w;
                boolean mOnScreen = Math.abs(mNdcX) <= 0.92f && Math.abs(mNdcY) <= 0.92f;
                float mScrX = (mNdcX * 0.5f + 0.5f) * screenW;
                float mScrY = (1f - (mNdcY * 0.5f + 0.5f)) * screenH;

                if (mOnScreen) {
                    // Pulsing gold ring  -  master is visible from drone camera
                    int masterGold = ImGui.colorConvertFloat4ToU32(1.0f, 0.85f, 0.15f, 0.9f);
                    draw.addCircle(mScrX, mScrY, 11f, masterGold, 24, 2.5f);
                    draw.addCircle(mScrX, mScrY, 7f, masterGold, 24, 1.5f);
                    draw.addText(mScrX - 12f, mScrY + 14f, black, distLabel);
                    draw.addText(mScrX - 13f, mScrY + 13f, masterGold, distLabel);
                }
            }

            // ── Return hint ───────────────────────────────────────────────────
            String returnHint = "[TAB]  Return to body";
            draw.addText(cx - 52f, screenH - 30f, black, returnHint);
            draw.addText(cx - 53f, screenH - 31f,
                    ImGui.colorConvertFloat4ToU32(0.7f, 0.82f, 1.0f, 0.85f), returnHint);
        }

        // ── SEAL COUNT DISPLAY ────────────────────────────────────────────────
        // Only once the SEAL ability is unlocked AND at least one seal is out  - 
        // it used to render "[H] Place [B] Teleport…" from the very first frame
        // of the game, overlapping everything (playtest complaint).
        if (!win.player.stand.isInStandPerspective() && !win.player.debugMode
                && win.player.progression.isUnlocked(Progression.Ability.SEAL)
                && (win.player.seals.getSealCount() > 0
                    || !win.player.seals.inFlightSeals.isEmpty())) {
            int placed = win.player.seals.getSealCount();
            int maxSeals = GameConfig.sealMaxCount;
            // Pip row: filled diamond = placed, hollow = available slot
            float pipY = screenH - 120f;
            float pipGap = 14f;
            float pipStartX = cx - (maxSeals - 1) * pipGap / 2f;
            int sealCyan = ImGui.colorConvertFloat4ToU32(0.2f, 0.95f, 0.95f, 1.0f);
            int sealDim = ImGui.colorConvertFloat4ToU32(0.3f, 0.3f, 0.3f, 0.55f);

            for (int i = 0; i < maxSeals; i++) {
                float px = pipStartX + i * pipGap;
                float half = 5f;
                if (i < placed) {
                    // Filled diamond
                    draw.addQuadFilled(px, pipY - half,
                            px + half, pipY,
                            px, pipY + half,
                            px - half, pipY, sealCyan);
                    draw.addQuad(px, pipY - half,
                            px + half, pipY,
                            px, pipY + half,
                            px - half, pipY, black, 1.2f);
                } else {
                    // Empty diamond outline
                    draw.addQuad(px, pipY - half,
                            px + half, pipY,
                            px, pipY + half,
                            px - half, pipY, sealDim, 1.2f);
                }
            }

            // Label removed  -  the pip row alone is enough visual feedback
        }

        // ── SEAL HUD MARKERS ─────────────────────────────────────────────────
        // Always-visible indicators so seals are spottable at any distance:
        //   • On-screen seal  -> pulsing cyan ring + distance label
        //   • Off-screen seal -> small cyan arrow on screen edge
        if (!win.player.stand.isInStandPerspective()
                && !win.player.seals.placedSeals.isEmpty()) {

            Matrix4f sealVP = new Matrix4f(camera.getProjectionMatrix())
                    .mul(camera.getViewMatrix());

            for (SealController.SealEntry seal : win.player.seals.placedSeals) {
                org.joml.Vector4f clip = new org.joml.Vector4f(
                        seal.position.x, seal.position.y, seal.position.z, 1f)
                        .mul(sealVP);

                boolean inFront = clip.w > 0f;
                float absW = Math.abs(clip.w);
                float ndcX = clip.x / absW;
                float ndcY = clip.y / absW;
                boolean onScreen = inFront
                        && Math.abs(ndcX) <= 1.0f && Math.abs(ndcY) <= 1.0f;

                float dist = new Vector3f(seal.position).sub(camera.position).length();
                String distLabel = String.format("%.0fm", dist);

                int sealRingColor = seal.targeted
                        ? ImGui.colorConvertFloat4ToU32(0.2f, 1.0f, 1.0f, 1.0f)
                        : ImGui.colorConvertFloat4ToU32(0.1f, 0.8f, 0.8f, 0.85f);
                int sealDimText = ImGui.colorConvertFloat4ToU32(0.2f, 0.9f, 0.9f, 0.75f);

                if (onScreen) {
                    // Project to pixel coords
                    float sx = (ndcX * 0.5f + 0.5f) * screenW;
                    float sy = (1f - (ndcY * 0.5f + 0.5f)) * screenH;

                    // Pulse: ring radius grows/shrinks slightly
                    float pulse = 1f + 0.12f * (float) Math.sin(seal.pulsePhase * 2f);
                    float outerR = (seal.targeted ? 18f : 11f) * pulse;
                    float innerR = outerR * 0.55f;

                    // Outer ring
                    draw.addCircle(sx, sy, outerR, sealRingColor, 20, seal.targeted ? 2.5f : 1.8f);
                    // Inner dot for targeted seal
                    if (seal.targeted) {
                        draw.addCircleFilled(sx, sy, innerR,
                                ImGui.colorConvertFloat4ToU32(0.2f, 1.0f, 1.0f, 0.35f), 16);
                    }
                    // Distance label just below the ring
                    float labelY = sy + outerR + 3f;
                    draw.addText(sx - 12f, labelY + 1f, black, distLabel);
                    draw.addText(sx - 13f, labelY, sealDimText, distLabel);

                } else {
                    // Off-screen arrow  -  same dot-product technique as the stand indicator
                    Vector3f toSeal = new Vector3f(seal.position)
                            .sub(camera.position).normalize();
                    Vector3f right = camera.getRight();
                    Vector3f up = new Vector3f(right)
                            .cross(camera.getLookDirection()).normalize();

                    float dirX = toSeal.dot(right);
                    float dirY = -toSeal.dot(up);
                    float angle = (float) Math.atan2(dirY, dirX);
                    float edgeR = Math.min(cx, cy) * 0.80f;

                    float ax = cx + (float) Math.cos(angle) * edgeR;
                    float ay = cy + (float) Math.sin(angle) * edgeR;

                    // Small triangle arrow
                    float cosA = (float) Math.cos(angle);
                    float sinA = (float) Math.sin(angle);
                    float tl = 12f, tw = 7f;
                    // tip, left-base, right-base
                    float t1x = ax + cosA * tl * 0.5f, t1y = ay + sinA * tl * 0.5f;
                    float t2x = ax - cosA * tl * 0.5f + sinA * tw * 0.5f;
                    float t2y = ay - sinA * tl * 0.5f - cosA * tw * 0.5f;
                    float t3x = ax - cosA * tl * 0.5f - sinA * tw * 0.5f;
                    float t3y = ay - sinA * tl * 0.5f + cosA * tw * 0.5f;
                    draw.addTriangleFilled(t1x, t1y, t2x, t2y, t3x, t3y, sealRingColor);
                    draw.addTriangle(t1x, t1y, t2x, t2y, t3x, t3y, black, 1.2f);

                    // Distance label beside arrow
                    draw.addText(ax + cosA * (tl + 3f) - 10f,
                            ay + sinA * (tl + 3f) - 6f, black, distLabel);
                    draw.addText(ax + cosA * (tl + 3f) - 11f,
                            ay + sinA * (tl + 3f) - 7f, sealDimText, distLabel);
                }
            }
        }

        // ── ENEMY HP BARS ─────────────────────────────────────────────────────
        // World-space health bar projected above each enemy's head.
        // Use the active camera: stand camera in drone mode, win.player camera otherwise.
        if (!win.enemyManager.getEnemies().isEmpty() && !win.player.stand.isInStandPerspective()) {
            // Build VP from the actual win.player eye position/orientation
            float aspect = screenW / screenH;
            float fovRad = (float) Math.toRadians(
                    (camera.dynamicFov >= 0f) ? camera.dynamicFov : GameConfig.fov);
            Matrix4f hpProj = new Matrix4f().perspective(fovRad, aspect, 0.1f, 1000f);
            Matrix4f hpView = camera.getViewMatrix();
            Matrix4f enemyVP = new Matrix4f(hpProj).mul(hpView);

            // Camera eye position and look direction for in-front culling
            Vector3f eyePos = new Vector3f(camera.position);
            Vector3f lookDir = camera.getLookDirection();
            Vector3f right = camera.getRight();

            // Off-screen enemies within this range get an edge arrow so the player
            // can always locate threats (fixes "enemies are hard to find / hit me
            // from nowhere"). Each entry: {screenDirX, screenDirY, distance}.
            java.util.List<float[]> offscreen = new java.util.ArrayList<>();
            float ecx = screenW * 0.5f, ecy = screenH * 0.5f;
            float marker = (float) glfwGetTime();

            for (Enemy enemy : win.enemyManager.getEnemies()) {
                if (!enemy.alive) continue;
                if (enemy == win.mpProxy) continue;   // invisible PvP hitbox  -  never show its 999 HP bar

                Vector3f headPos = new Vector3f(
                        enemy.position.x,
                        enemy.position.y + 2.3f,    // just above model top
                        enemy.position.z);
                Vector3f toEnemy = new Vector3f(headPos).sub(eyePos);
                float dist = toEnemy.length();
                if (dist > 70f) continue;           // too far to matter

                org.joml.Vector4f clip = new org.joml.Vector4f(
                        headPos.x, headPos.y, headPos.z, 1f).mul(enemyVP);
                float ndcX = clip.w != 0f ? clip.x / clip.w : 2f;
                float ndcY = clip.w != 0f ? clip.y / clip.w : 2f;
                boolean onScreen = clip.w > 0.001f
                        && ndcX >= -1.0f && ndcX <= 1.0f && ndcY >= -1.0f && ndcY <= 1.0f;

                if (!onScreen) {
                    // Collect nearby off-screen threats for an edge arrow.
                    if (dist <= 50f && dist > 0.001f) {
                        Vector3f dir = new Vector3f(headPos).sub(eyePos).div(dist);
                        Vector3f up  = new Vector3f(right).cross(lookDir).normalize();
                        offscreen.add(new float[]{ dir.dot(right), -dir.dot(up), dist });
                    }
                    continue;
                }

                float sx = (ndcX * 0.5f + 0.5f) * screenW;
                float sy = (1f - (ndcY * 0.5f + 0.5f)) * screenH;

                float hpFrac = Math.max(0f, enemy.health / (enemy.maxHealth * enemy.healthScale));
                float barW = 44f, barH = 6f;
                float barX = sx - barW / 2f, barY = sy - barH;

                // Downward chevron above the bar  -  makes enemies pop against terrain.
                float hurt = enemy.hitFlashTimer > 0f ? 1f : 0f;
                int chevCol = ImGui.colorConvertFloat4ToU32(1f, 0.25f + 0.6f * hurt, 0.2f, 0.95f);
                float chTop = barY - 16f, chMid = barY - 7f;
                draw.addTriangleFilled(sx - 7f, chTop, sx + 7f, chTop, sx, chMid, chevCol);

                // Background
                draw.addRectFilled(barX, barY, barX + barW, barY + barH,
                        ImGui.colorConvertFloat4ToU32(0.1f, 0f, 0f, 0.8f), 2f);
                // HP fill  -  green -> red as health drops
                float r = 0.15f + 0.85f * (1f - hpFrac);
                float g = 0.9f * hpFrac;
                draw.addRectFilled(barX, barY, barX + barW * hpFrac, barY + barH,
                        ImGui.colorConvertFloat4ToU32(r, g, 0.05f, 1.0f), 2f);
                draw.addRect(barX, barY, barX + barW, barY + barH,
                        ImGui.colorConvertFloat4ToU32(0f, 0f, 0f, 0.9f), 2f, 0, 1.2f);

                // HP numbers
                String hpStr = String.format("%.0f", enemy.health);
                draw.addText(sx - 7f, barY - 30f, black, hpStr);
                draw.addText(sx - 8f, barY - 31f,
                        ImGui.colorConvertFloat4ToU32(1f, 0.85f, 0.85f, 0.85f), hpStr);
            }

            // ── OFF-SCREEN ENEMY ARROWS (nearest few, pulsing red) ────────────
            offscreen.sort((a, b) -> Float.compare(a[2], b[2]));
            int arrows = Math.min(6, offscreen.size());
            float pulse = 0.55f + 0.45f * (float) Math.sin(marker * 4f);
            for (int i = 0; i < arrows; i++) {
                float[] o = offscreen.get(i);
                float angle  = (float) Math.atan2(o[1], o[0]);
                float radius = Math.min(ecx, ecy) * 0.80f;
                float ix = ecx + (float) Math.cos(angle) * radius;
                float iy = ecy + (float) Math.sin(angle) * radius;
                float closeness = 1f - Math.min(1f, o[2] / 50f);   // bigger when closer
                float s = 11f + closeness * 10f;
                float cosA = (float) Math.cos(angle), sinA = (float) Math.sin(angle);
                // Triangle pointing outward toward the threat.
                float[][] tri = {{ s, 0 }, { -s * 0.7f, s * 0.7f }, { -s * 0.7f, -s * 0.7f }};
                for (float[] p : tri) {
                    float rx = p[0] * cosA - p[1] * sinA, ry = p[0] * sinA + p[1] * cosA;
                    p[0] = ix + rx; p[1] = iy + ry;
                }
                int fill = ImGui.colorConvertFloat4ToU32(1f, 0.2f, 0.15f, 0.55f + 0.4f * pulse * closeness);
                draw.addTriangleFilled(tri[0][0], tri[0][1], tri[1][0], tri[1][1], tri[2][0], tri[2][1], fill);
                draw.addTriangle(tri[0][0], tri[0][1], tri[1][0], tri[1][1], tri[2][0], tri[2][1],
                        ImGui.colorConvertFloat4ToU32(0f, 0f, 0f, 0.8f), 1.5f);
            }
        }

        // ── ABILITY COOLDOWN ICONS ────────────────────────────────────────────
        if (!win.player.debugMode) {
            renderAbilityHUD(draw, screenW, screenH);
        }

        // ── WELCOME BANNER ───────────────────────────────────────────────────
        // Shown for ~6 s after the win.world first loads. Guides new players to F1.
        if (win.welcomeTimer > 0f && !win.showHelp) {
            renderWelcomeBanner(draw, screenW, screenH, win.welcomeTimer);
        }

        // Onboarding objective banner (top-centre)  -  only during the tutorial.
        // After the tutorial the Voyage HUD takes over the top banner.
        if (!win.showHelp && win.welcomeTimer <= 0f && !win.playtestMode
                && win.tutorial != null && win.tutorial.isActive()) {
            renderObjectiveBanner(draw, screenW, screenH);
        }

        // ── COMBAT KEY STRIP  -  always-on key reminders above the hotbar.
        // Playtest feedback: nobody remembers keybinds after one tutorial pass.
        if (!win.showHelp && !win.playtestMode && !win.isPaused
                && win.tutorial != null && win.tutorial.isFinished()
                && !win.player.stand.isInStandPerspective()) {
            renderCombatKeyStrip(draw, screenW, screenH);
        }

        // ── TRY IT prompt  -  big key chip for the ability just unlocked ───────
        if (!win.showHelp && !win.isPaused && !win.showUnlockCard
                && win.weaponRevealTimer <= 0f
                && (win.tryStep != null || win.tryDoneAbility != null)) {
            renderTryPrompt(draw, screenW, screenH);
        }

        // ── PLAYTEST CONTROLS LEGEND ──────────────────────────────────────────
        // Always-on compact legend so the player discovers every system unaided.
        if (win.playtestMode && !win.showHelp && !win.isPaused
                && !win.player.stand.isInStandPerspective()) {
            renderPlaytestLegend(draw, screenW, screenH);
        }

        // ── THE VOYAGE  -  objective banner, world waypoint + forge reveals ─────
        if (!win.showHelp && !win.player.stand.isInStandPerspective()) {
            renderVoyageHUD(draw, camera, screenW, screenH);
        }

        // ── CONTEXTUAL HINT BANNER ────────────────────────────────────────────
        // One-liners that fire on first stand deploy / first seal placement.
        if (win.hintTimer > 0f && win.hintText != null && !win.showHelp) {
            renderHintBanner(draw, screenW, screenH, win.hintTimer, win.hintText);
        }

        // ── WAVE COUNTER ──────────────────────────────────────────────────────
        // Top-left: "WAVE N  -  next in Xs" or "MAX ENEMIES REACHED".
        // Hidden in playtest mode  -  waves are off, so a wave timer would mislead.
        if (!win.player.stand.isInStandPerspective() && !win.player.debugMode && !win.playtestMode
                && !win.voyageStarted) {
            int waveNum = win.enemyManager.getWaveNumber();
            float waveTimer = win.enemyManager.getWaveTimer();
            int liveCount = (int) win.enemyManager.getEnemies().stream().filter(e -> e.alive).count();
            int maxCount = GameConfig.spawnMaxEnemies;

            String waveLabel;
            int waveColor;
            if (liveCount >= maxCount) {
                waveLabel = String.format("WAVE %d  [!] MAX ENEMIES (%d/%d)", waveNum, liveCount, maxCount);
                waveColor = ImGui.colorConvertFloat4ToU32(1.0f, 0.4f, 0.15f, 0.95f);
            } else if (waveNum == 0) {
                waveLabel = String.format("Next wave in %.0fs", waveTimer);
                waveColor = ImGui.colorConvertFloat4ToU32(0.8f, 0.8f, 0.8f, 0.65f);
            } else {
                waveLabel = String.format("WAVE %d   -  next in %.0fs  [%d alive]",
                        waveNum, waveTimer, liveCount);
                // Pulse red as the next wave approaches (< 8 s)
                float urgency = Math.max(0f, 1f - waveTimer / 8f);
                waveColor = ImGui.colorConvertFloat4ToU32(
                        0.75f + 0.25f * urgency,
                        0.85f - 0.50f * urgency,
                        0.85f - 0.75f * urgency,
                        0.85f);
            }
            draw.addText(12f, 13f, black, waveLabel);
            draw.addText(11f, 12f, waveColor, waveLabel);
        }

        // ── TIME SCALE INDICATOR ──────────────────────────────────────────────
        TimeController tc = TimeController.getInstance();
        if (tc.getSlownessFactor() > 0.05f || tc.getFastnessFactor() > 0.05f) {
            String timeLabel = tc.getSlownessFactor() > 0.05f
                    ? String.format("⧗ %.2fx", tc.getScale())
                    : String.format("⚡ %.1fx", tc.getScale());
            int timeColor = tc.getSlownessFactor() > 0.05f
                    ? ImGui.colorConvertFloat4ToU32(0.6f, 0.7f, 1.0f, 0.9f)
                    : ImGui.colorConvertFloat4ToU32(1.0f, 0.65f, 0.2f, 0.9f);
            draw.addText(screenW - 80f, 12f, black, timeLabel);
            draw.addText(screenW - 81f, 11f, timeColor, timeLabel);
        }
        if (win.holdingTelescope) {
            win.telescope.render2D((int)screenW, (int)screenH, draw);

            // Generate and draw contextual navigation hint
            String navHint = CelestialNav.generateHint(win.dayNight);
            float nw = ImGui.calcTextSize(navHint).x;
            draw.addText(cx - nw / 2f, screenH - 60f, ImGui.colorConvertFloat4ToU32(0.8f, 0.9f, 1f, 0.8f), navHint);
        }
        // ── FLAPPY BIRD ARCADE SCORE ──
        // (Hidden during the finale trial — renderFinale draws its own n/15 tracker.)
        if (win.player.useTestMovement && win.player.testMovement.state == TestMovementController.State.FLAPPY
                && (win.finale == null || !win.finale.isFlappyTrial())) {
            String scoreStr = "SCORE: " + win.player.testMovement.flappyScore;
            ImFont font = ImGui.getFont();
            float scale = 2.4f;
            float sz = font.getFontSize() * scale;
            float tw = ImGui.calcTextSize(scoreStr).x * scale;

            // Render centered drop-shadow score
            draw.addText(font, sz, cx - tw / 2f + 2f, screenH * 0.16f + 2f, ImGui.colorConvertFloat4ToU32(0f, 0f, 0f, 1f), scoreStr);
            draw.addText(font, sz, cx - tw / 2f,      screenH * 0.16f,      ImGui.colorConvertFloat4ToU32(1.0f, 0.85f, 0.25f, 1f), scoreStr);

            // Flashing start instruction
            if (win.player.testMovement.flappyWaitingToStart) {
                String startPrompt = "Press [ SPACE ] to Flap & Start";
                float sw = ImGui.calcTextSize(startPrompt).x * 1.5f;
                float pa = 0.5f + 0.5f * (float) Math.abs(Math.sin(glfwGetTime() * 4.5f));
                draw.addText(font, font.getFontSize() * 1.5f, cx - sw / 2f, screenH * 0.28f, ImGui.colorConvertFloat4ToU32(1f, 1.0f, 1.0f, pa), startPrompt);
            }
        }
        // ── RENDER BACKPACK OVERLAY ──
        win.backpackUI.render(screenW, screenH);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  ABILITY VISUAL RENDERING
    // ─────────────────────────────────────────────────────────────────────────

    void renderAbilityHUD(imgui.ImDrawList draw, float screenW, float screenH) {
        // ── Ability icon layout: up to three rows, bottom-right ──────────────
        // Bigger icons with full label clearance per row  -  the old 28px grid
        // had row labels colliding with the icons below (playtest complaint).
        float iconSize = 38f;
        float spacing  = 10f;
        float rowPitch = iconSize + 24f;   // icon + centred label + gap
        int black = ImGui.colorConvertFloat4ToU32(0f, 0f, 0f, 0.8f);
        int grey = ImGui.colorConvertFloat4ToU32(0.2f, 0.2f, 0.2f, 0.8f);

        // Row 1  -  only show abilities the player has unlocked
        {
            Progression prog = win.player.progression;
            String[] labels   = {"Q",    "F",     "M",    "O",    "L"   };
            String[] tooltips = {"Dash", "Slash", "Mud",  "Grab", "Heal"};
            Progression.Ability[] gates = {
                Progression.Ability.DASH, Progression.Ability.SLASH,
                Progression.Ability.QUAGMIRE, Progression.Ability.GRAB,
                Progression.Ability.HEAL,
            };
            float quagFrac = (GameConfig.quagmireCooldown > 0f)
                    ? Math.max(0f, Math.min(1f, 1f - win.quagmireCooldown / GameConfig.quagmireCooldown))
                    : 1f;
            float[] fracs = {
                    win.player.abilities.getDashCooldownFrac(),
                    win.player.attacks.getMeleeCooldownFrac(),
                    quagFrac,
                    win.player.grab.getCooldownFrac(),
                    win.player.abilities.getHealCooldownFrac(),
            };
            int[] colors = {
                    ImGui.colorConvertFloat4ToU32(0.45f, 0.88f, 1.0f, 1.0f),
                    ImGui.colorConvertFloat4ToU32(1.0f,  0.55f, 0.06f, 1.0f),
                    ImGui.colorConvertFloat4ToU32(0.55f, 0.82f, 0.20f, 1.0f),
                    ImGui.colorConvertFloat4ToU32(1.0f,  0.20f, 0.15f, 1.0f),
                    ImGui.colorConvertFloat4ToU32(0.15f, 0.85f, 0.35f, 1.0f),
            };

            // Build the visible subset
            java.util.List<Integer> vis = new java.util.ArrayList<>();
            for (int i = 0; i < labels.length; i++) if (prog.isUnlocked(gates[i])) vis.add(i);
            if (!vis.isEmpty()) {
                float totalW = vis.size() * iconSize + (vis.size() - 1) * spacing;
                float startX = screenW - totalW - 16f;
                float startY = screenH - rowPitch * 3f - 10f;
                for (int slot = 0; slot < vis.size(); slot++) {
                    int i = vis.get(slot);
                    float x = startX + slot * (iconSize + spacing);
                    drawAbilityIcon(draw, x, startY, iconSize, labels[i], tooltips[i],
                            fracs[i], colors[i], false, black, grey);
                }
            }
        }

        // ── ROW 2 (middle): RANGED ATTACKS  -  only unlocked ───────────────────
        {
            Progression prog = win.player.progression;
            boolean standDeployed = win.player.stand.isDeployed();
            float stoneFrac = win.isChargingStoneCanon
                    ? Math.min(1f, win.stoneCanonCharge / GameConfig.stoneCanonMaxCharge)
                    : ((GameConfig.stoneCanonCooldown > 0f)
                       ? Math.max(0f, Math.min(1f, 1f - win.stoneCanonCooldownTimer / GameConfig.stoneCanonCooldown))
                       : 1f);
            float lightFrac = win.player.lightning.isCharging()
                    ? win.player.lightning.getChargeFrac()
                    : win.player.lightning.getCooldownFrac();
            float lightA = win.player.lightning.isCharging()
                    ? 0.6f + (float) Math.sin(glfwGetTime() * 12) * 0.4f : 1.0f;

            String[] labelsR   = {"C",     "I",       "U",     "X"     };
            String[] tooltipsR = {"Snipe", "Cannon",  "Bolt",  "Drone" };
            Progression.Ability[] gatesR = {
                Progression.Ability.SNIPE, Progression.Ability.STONE_CANON,
                Progression.Ability.LIGHTNING, Progression.Ability.STAND,
            };
            float[]  fracsR    = {
                    win.player.attacks.getSnipeIconFrac(), stoneFrac, lightFrac,
                    standDeployed ? 1f : win.player.stand.getRedeployCooldownFrac(),
            };
            int[] colorsR = {
                    ImGui.colorConvertFloat4ToU32(0.75f, 0.45f, 1.0f, 1.0f),
                    ImGui.colorConvertFloat4ToU32(0.70f, 0.55f, 0.35f, 1.0f),
                    ImGui.colorConvertFloat4ToU32(0.85f, 0.92f, 1.0f, lightA),
                    ImGui.colorConvertFloat4ToU32(1.0f,  0.82f, 0.15f, 1.0f),
            };
            boolean[] glowR = { false, win.isChargingStoneCanon, win.player.lightning.isCharging(), standDeployed };

            java.util.List<Integer> visR = new java.util.ArrayList<>();
            for (int i = 0; i < labelsR.length; i++) if (prog.isUnlocked(gatesR[i])) visR.add(i);
            if (!visR.isEmpty()) {
                float totalW = visR.size() * iconSize + (visR.size() - 1) * spacing;
                float startX = screenW - totalW - 16f;
                float startY = screenH - rowPitch * 2f - 10f;
                for (int slot = 0; slot < visR.size(); slot++) {
                    int i = visR.get(slot);
                    float x = startX + slot * (iconSize + spacing);
                    drawAbilityIcon(draw, x, startY, iconSize, labelsR[i], tooltipsR[i],
                            fracsR[i], colorsR[i], glowR[i], black, grey);
                }
            }
        }

        // ── ROW 3 (bottom): SPATIAL / SPECIAL  -  only unlocked ────────────────
        {
            Progression prog = win.player.progression;
            float kamuiFrac = win.player.abilities.isKamui
                    ? win.player.abilities.kamuiTimer / GameConfig.kamuiMaxDuration
                    : win.player.abilities.getKamuiCooldownFrac();
            float subFrac = (GameConfig.substituteCooldown > 0f)
                    ? Math.max(0f, Math.min(1f, win.substituteCooldown / GameConfig.substituteCooldown)) : 0f;
            float swapFrac = (GameConfig.todoCooldown > 0f)
                    ? Math.max(0f, Math.min(1f, 1f - win.todoSwapCooldown / GameConfig.todoCooldown)) : 1f;

            String[] labelsS   = {"Z",     "K",      "V",    "B",      "J"   };
            String[] tooltipsS = {"Kamui", "Pillar", "Sub",  "Seal",   "Swap" };
            Progression.Ability[] gatesS = {
                Progression.Ability.KAMUI, Progression.Ability.PILLAR,
                Progression.Ability.SUBSTITUTE, Progression.Ability.SEAL,
                Progression.Ability.SWAP,
            };
            float[]  fracsS    = {
                    kamuiFrac, win.player.abilities.getPillarCooldownFrac(),
                    win.substitutePrimed ? 1f : (1f - subFrac),
                    win.player.seals.getTeleportCooldownFrac(), swapFrac,
            };
            int[] colorsS = {
                    ImGui.colorConvertFloat4ToU32(0.85f, 0.0f,  0.9f,  1.0f),
                    ImGui.colorConvertFloat4ToU32(0.6f,  0.6f,  0.65f, 1.0f),
                    ImGui.colorConvertFloat4ToU32(0.9f,  0.92f, 1.0f,  1.0f),
                    ImGui.colorConvertFloat4ToU32(0.1f,  0.75f, 0.65f, 1.0f),
                    ImGui.colorConvertFloat4ToU32(0.95f, 0.4f,  1.0f,  1.0f),
            };
            boolean[] glowS = { win.player.abilities.isKamui, false, win.substitutePrimed, false, false };

            java.util.List<Integer> visS = new java.util.ArrayList<>();
            for (int i = 0; i < labelsS.length; i++) if (prog.isUnlocked(gatesS[i])) visS.add(i);
            if (!visS.isEmpty()) {
                float totalW = visS.size() * iconSize + (visS.size() - 1) * spacing;
                float startX = screenW - totalW - 16f;
                float startY = screenH - rowPitch - 10f;
                for (int slot = 0; slot < visS.size(); slot++) {
                    int i = visS.get(slot);
                    float x = startX + slot * (iconSize + spacing);
                    drawAbilityIcon(draw, x, startY, iconSize, labelsS[i], tooltipsS[i],
                            fracsS[i], colorsS[i], glowS[i], black, grey);
                }
            }
        }
    }

    /**
     * One ability icon: rounded slot with cooldown fill, the key letter LARGE
     * and centred inside, and the ability name centred underneath with full
     * clearance (nothing overlaps the row below).
     */
    private void drawAbilityIcon(imgui.ImDrawList draw, float x, float y, float size,
                                 String key, String name, float frac, int color,
                                 boolean glow, int black, int grey) {
        ImFont font = ImGui.getFont();
        float base = ImGui.getFontSize();
        draw.addRectFilled(x, y, x + size, y + size, grey, 6f);
        float fillH = size * Math.max(0f, Math.min(1f, frac));
        if (fillH > 0.5f)
            draw.addRectFilled(x, y + size - fillH, x + size, y + size, color, 6f);
        draw.addRect(x, y, x + size, y + size, glow ? color : black, 6f, 0, glow ? 3f : 1.5f);
        // Key letter  -  large, centred
        float keySize = base * 1.45f;
        float kw = ImGui.calcTextSize(key).x * (keySize / base);
        float kx = x + (size - kw) / 2f, ky = y + (size - keySize) / 2f;
        draw.addText(font, keySize, kx + 1, ky + 1, black, key);
        draw.addText(font, keySize, kx, ky,
                ImGui.colorConvertFloat4ToU32(1f, 1f, 1f, 0.95f), key);
        // Name  -  centred below, white with shadow for readability
        float nw = ImGui.calcTextSize(name).x;
        float nx = x + (size - nw) / 2f, ny = y + size + 4f;
        draw.addText(nx + 1, ny + 1, black, name);
        draw.addText(nx, ny, ImGui.colorConvertFloat4ToU32(0.95f, 0.95f, 0.95f, 0.95f), name);
    }

    /** Draws one horizontal row of ability cooldown icons. glow[i] = bright outline (active state). */
    private void drawIconRow(imgui.ImDrawList draw, String[] labels, String[] tooltips,
                             float[] fracs, int[] colors, boolean[] glow,
                             float startX, float startY, float iconSize, float spacing,
                             int black, int grey) {
        for (int i = 0; i < labels.length; i++) {
            float x = startX + i * (iconSize + spacing);
            draw.addRectFilled(x, startY, x + iconSize, startY + iconSize, grey, 5f);
            float fillH = iconSize * Math.max(0f, Math.min(1f, fracs[i]));
            if (fillH > 0.5f) {
                draw.addRectFilled(x, startY + iconSize - fillH,
                        x + iconSize, startY + iconSize, colors[i], 5f);
            }
            boolean g = glow != null && i < glow.length && glow[i];
            draw.addRect(x, startY, x + iconSize, startY + iconSize,
                    g ? colors[i] : black, 5f, 0, g ? 2.5f : 1.5f);
            draw.addText(x + 9f, startY + 7f, black, labels[i]);
            draw.addText(x + 9f, startY + 7f,
                    ImGui.colorConvertFloat4ToU32(1f, 1f, 1f, 0.9f), labels[i]);
            draw.addText(x, startY + iconSize + 2f,
                    ImGui.colorConvertFloat4ToU32(0.8f, 0.8f, 0.8f, 0.7f), tooltips[i]);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  HELP SCREEN  (F1)
    // ─────────────────────────────────────────────────────────────────────────

    // --- FILE: src/main/java/com/leaf/game/core/WindowHud.java ---
// Replace the renderHelpScreen method with this updated version:

    void renderHelpScreen(float screenW, float screenH) {
        float winW = Math.min(850f, screenW - 40f);
        float winH = Math.min(screenH - 40f, screenH - 40f);
        ImGui.setNextWindowPos(screenW / 2f - winW / 2f, screenH / 2f - winH / 2f);
        ImGui.setNextWindowSize(winW, winH);
        ImGui.setNextWindowBgAlpha(0.94f);
        ImGui.begin("Controls & Abilities",
                imgui.flag.ImGuiWindowFlags.NoResize |
                        imgui.flag.ImGuiWindowFlags.NoMove);

        ImGui.textDisabled("  [F1] or [ESC] to close.   Scroll down for all sections.");
        ImGui.separator();
        ImGui.spacing();

        // ── EASTER EGGS ───────────────────────────────────────────────────────
        ImGui.textColored(1.0f, 0.55f, 0.9f, 1.0f, "EASTER EGGS  **  (hidden secrets)");
        ImGui.separator();
        helpRow("[ ` ] (Tilde)", "FLAPPY BIRD MODE  -  press ` at any time to instantly transform DESCENT into a full 2D side-scrolling Flappy Bird arcade! Press [TAB] to toggle 3D/2D view. How high can you score?");
        helpRow("[ F4 ]", "METEOR STORM  -  rains flaming meteorites from the sky. Each one dynamically carves a crater into the terrain on impact. The mountain will never be the same.");
        helpRow("[ F6 ]", "NON-EUCLIDEAN SPACE  -  teleports you into an impossible, infinitely looping 4D layered room. Find your way out.");
        ImGui.spacing();

        // ── MARKER / SHOWCASE FEATURES ────────────────────────────────────────
        ImGui.textColored(1.0f, 0.4f, 0.4f, 1.0f, "SHOWCASE / SANDBOX (For Marking & Testing)");
        ImGui.separator();
        helpRow("[ F7 ] / Slot 3", "Orbital Annihilation (RMB): cinematic 3D orbital strike  -  earn it in the Ashlands.");
        helpRow("[ F8 ] / Slot 4", "The World / Time Stop (RMB): freeze all enemies in place  -  earned by completing the Voyage.");
        helpRow("[ F10 ]", "Radar Sweep: 3D ping  -  enemies glow through walls with wireframe highlights. Yours from the very start!");
        helpRow("[ . ] (Period)", "Chocolate Disco (showcase mode only): Spawns a 9×9 tactical grid. Mark cells with LMB, press [.] again to detonate.");
        helpRow("[ ' ] (Quote)", "Deprivation Domain: Golden absolute-defence hemisphere  -  any enemy entering is instantly sliced. Forged in the Glowing Groves.");
        helpRow("[ , ] (Comma)", "Quantum Bullet (showcase mode only): Phases through walls, leaving ripple distortions on every surface hit.");
        helpRow("[ F12 ]", "Parkour Mode: Toggles Quake-style momentum physics (frictionless, high-speed).");
        helpRow("[ T ] -> /skip", "Skip the tutorial and jump straight to wave 1.");
        helpRow("[ T ] -> /showcase", "Instantly unlock every ability and arm all 5 weapons  -  for grading/testing.");
        ImGui.spacing();

        // ── YOUR ABILITIES (unlocked vs. still locked) ────────────────────────
        ImGui.textColored(1.0f, 0.85f, 0.25f, 1.0f, "YOUR ABILITIES");
        ImGui.separator();
        Progression prog = win.player.progression;
        for (Progression.Ability a : prog.allAbilities()) {
            // TIME, HEAL and SWAP are showcase-only now  -  don't advertise locked rows.
            if ((a == Progression.Ability.TIME || a == Progression.Ability.HEAL
                    || a == Progression.Ability.SWAP)
                    && !prog.isUnlocked(a)) continue;
            if (prog.isUnlocked(a)) {
                helpRow(a.key, a.label + "  -  " + a.desc);
            } else {
                int wv = prog.unlockWaveOf(a);
                ImGui.textDisabled("  [ ? ]");
                ImGui.sameLine(230f);
                ImGui.pushTextWrapPos(0f);
                String lockMsg = (wv >= 1 && wv <= Progression.VOYAGE_START_WAVE)
                        ? "Locked  -  defeat wave " + wv + " at spawn to unlock."
                        : (a == Progression.Ability.KAMUI)
                        ? "Locked  -  a secret. Keep playing."
                        : "Locked  -  forge on the Voyage (follow the beam after wave 4).";
                ImGui.textDisabled(lockMsg);
                ImGui.popTextWrapPos();
            }
        }
        ImGui.spacing();

        // ── MOVEMENT ──────────────────────────────────────────────────────────
        ImGui.textColored(0.5f, 0.9f, 1.0f, 1.0f, "MOVEMENT");
        ImGui.separator();
        helpRow("WASD", "Move. Double-tap W to sprint.");
        helpRow("Space", "Jump. Double-tap Space to toggle flight mode (must unlock FLIGHT first).");
        helpRow("Shift (in air)", "While falling from height: slam into the ground. Craters the terrain and damages nearby enemies. Bigger fall = bigger crater.");
        ImGui.spacing();

        // ── FLIGHT ────────────────────────────────────────────────────────────
        ImGui.textColored(0.4f, 0.75f, 1.0f, 1.0f, "FLIGHT  (double-tap Space to enter / exit)");
        ImGui.separator();
        helpRow("[V]  Cycle mode", "Switch between SKIM -> SOAR -> GRAPPLE flight modes.");
        helpRow("SKIM", "Glide forward with gravity. Pitch your camera to angle up or down. Good for fast horizontal travel.");
        helpRow("SOAR", "Full 3D free-flight. WASD moves relative to your look direction. Space = up, Shift = down. No gravity.");
        helpRow("GRAPPLE", "Swing on a grapple line attached to the block you're looking at.");
        ImGui.spacing();


        // ── COMBAT ────────────────────────────────────────────────────────────
        ImGui.textColored(1.0f, 0.35f, 0.2f, 1.0f, "COMBAT");
        ImGui.separator();
        helpRow("[F]  Slash", "Wide melee swing. Hits every enemy in a cone in front of you.");
        helpRow("[C] / RMB  Sniper", "Hold to charge a crystal bolt, release to fire. Longer charge = bigger explosion on impact.");
        helpRow("[G]  Cannonball", "Hold to charge, release to launch yourself as a cannonball. Explodes on landing. A dotted arc previews your trajectory while charging.");
        helpRow("[U]  Lightning", "Strike the enemy you're aiming at with a lightning bolt. Double-tap [U] quickly for an area burst that hits all nearby enemies.");
        helpRow("[O]  Grab & Slam", "Grab the enemy in your crosshair (up to ~4.5 blocks away), hoist them overhead, then smash them into the ground. Creates a crater. Big damage.");
        ImGui.spacing();

        // ── MOVEMENT ABILITIES ────────────────────────────────────────────────
        ImGui.textColored(1.0f, 0.78f, 0.15f, 1.0f, "MOVEMENT ABILITIES");
        ImGui.separator();
        helpRow("[Q]  Dash", "Instant burst in the direction you're moving. Very short cooldown. Leaves a ghost trail behind you.");
        helpRow("[E]  Blink", "Teleport to the point you're looking at (up to ~22 blocks). Short cooldown.");
        helpRow("[K]  Pillar", "A stone spire erupts beneath your feet and launches you into the air.");
        ImGui.spacing();

        // ── SPECIAL ABILITIES ─────────────────────────────────────────────────
        ImGui.textColored(0.85f, 0.35f, 1.0f, 1.0f, "SPECIAL ABILITIES");
        ImGui.separator();
        helpRow("[Z]  Kamui", "Activate a space-time vortex around yourself. Distorts the whole screen. While active: hold LMB to charge an absorption ring that sucks in nearby enemies. Drains mana continuously.");
        helpRow("[J]  Position Swap", "Instantly teleport-swap with the nearest enemy in range. Great for escaping a bad spot or dropping enemies off cliffs.");
        helpRow("[V]  Substitute", "(On foot, not in flight) Hold [V] to prime. The next hit you take is completely absorbed  -  you blink backward, a paper dummy appears at your old position, then explodes.");
        helpRow("[M]  Quagmire", "Fire a mud wave at the enemy you're aiming at. It travels along the ground and traps them on contact.");
        helpRow("[I]  Stone Canon", "Stand near stone blocks and hold [I] to charge. Nearby stone is absorbed into a massive projectile. Release to fire.");
        helpRow("[L]  Heal", "Hold [L] to channel healing energy. Restores health over time while held. You cannot move while channeling.");
        ImGui.spacing();

        // ── MANHATTAN TRANSFER (Stand / Drone) ────────────────────────────────
        ImGui.textColored(1.0f, 0.85f, 0.15f, 1.0f, "MANHATTAN TRANSFER  (Stand / Drone)");
        ImGui.separator();
        helpRow("[X]", "Deploy your drone directly above you. Press [X] again to recall it.");
        helpRow("[TAB]", "Enter the drone's perspective to pilot it manually. Press [TAB] again to return to your body.");
        helpRow("Piloting  -  WASD", "Fly the drone. Space = up, Shift = down.");
        helpRow("Not piloting  -  click", "The drone auto-targets and fires at the nearest visible enemy.");
        helpRow("Two dots (top-right)", "Indicate line-of-sight: yours (left dot) and the drone's (right dot). Both green = clear shot.");
        ImGui.spacing();

        // ── MINATO'S SEAL ─────────────────────────────────────────────────────
        ImGui.textColored(0.2f, 0.95f, 0.95f, 1.0f, "MINATO'S SEAL  (Teleport Anchors)");
        ImGui.separator();
        helpRow("[H]", "Throw a seal marker. It sticks to the first surface it touches.");
        helpRow("[B]", "Instantly teleport to the seal nearest your crosshair. The targeted seal glows to indicate the destination.");
        helpRow("[N]", "Pull the targeted seal back to your hand without teleporting.");
        ImGui.spacing();

        // ── WORLD & UI ────────────────────────────────────────────────────────
        ImGui.textColored(0.75f, 0.75f, 0.75f, 1.0f, "WORLD & UI");
        ImGui.separator();
        helpRow("LMB / RMB", "Break block (hold) / Place selected block.");
        helpRow("1 - 9", "Select hotbar slot.");
        helpRow("Left ALT", "Open Backpack menu to swap out blocks/tools.");
        helpRow("[P]", "Debug: Spawn a test slime at your crosshair.");
        helpRow("[F3]", "Debug overlay (position, FPS, time scale, render distance).");
        helpRow("[F5]", "Warp to the Mesa/Canyon region (teleports you to a warm mesa biome).");
        helpRow("[F6]", "Non-Euclidean 'Layered Rooms'. Teleports you into an impossible, infinitely looping 4D space.");
        ImGui.spacing();

        ImGui.end();
    }

    /**
     * Single row in the help screen: yellow key label on the left,
     * wrapped description on the right. Fixed column split at 230px.
     */
    private void helpRow(String key, String desc) {
        ImGui.textColored(1.0f, 0.88f, 0.25f, 1.0f, "  " + key);
        ImGui.sameLine(230f);
        ImGui.pushTextWrapPos(0f);   // wrap at window right edge
        ImGui.text(desc);
        ImGui.popTextWrapPos();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  TUTORIAL BANNERS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * "Press F1 for controls" banner shown for the first few seconds of play.
     * Fades in over 0.4 s and out over 1 s.
     */
    // ── Onboarding objective banner (top-centre) ──────────────────────────────
    void renderObjectiveBanner(imgui.ImDrawList draw, float screenW, float screenH) {
        com.leaf.game.core.TutorialManager t = win.tutorial;

        float cx = screenW * 0.5f;
        float y  = 50f;
        int shadow   = ImGui.colorConvertFloat4ToU32(0f, 0f, 0f, 0.85f);
        int titleCol = ImGui.colorConvertFloat4ToU32(1.0f, 0.85f, 0.4f, 1.0f);
        int bodyCol  = ImGui.colorConvertFloat4ToU32(0.92f, 0.95f, 1.0f, 0.97f);
        int dimCol   = ImGui.colorConvertFloat4ToU32(0.7f, 0.75f, 0.85f, 0.8f);
        int keyCol   = ImGui.colorConvertFloat4ToU32(0.45f, 0.9f, 1.0f, 1.0f);
        int panelBg  = ImGui.colorConvertFloat4ToU32(0.05f, 0.07f, 0.12f, 0.62f);

        // ── TUTORIAL ACTIVE: BIG centred lesson card ─────────────────────────
        // Playtest feedback: small top-bar text was read once and forgotten.
        // Now: huge gold title, a giant pulsing keyboard-key chip, short line.
        if (t != null && t.isActive()) {
            ImFont font = ImGui.getFont();
            float  base = ImGui.getFontSize();
            float  pulse = 0.70f + 0.30f * (float) Math.sin(glfwGetTime() * 4.5);

            String title = t.title();
            String instr = t.instruction();
            String key   = t.keyHint();

            float titleSize = base * 2.4f;
            float instrSize = base * 1.35f;
            float keySize   = base * 2.0f;

            float titleW = ImGui.calcTextSize(title).x * (titleSize / base);
            float instrW = ImGui.calcTextSize(instr).x * (instrSize / base);
            float keyTxtW = (key != null) ? ImGui.calcTextSize(key).x * (keySize / base) : 0f;
            float chipW   = keyTxtW + 44f;
            float maxW  = Math.max(Math.max(titleW, instrW), chipW);
            float pad   = 30f;

            float top   = screenH * 0.13f;
            float yy    = top + 16f;
            float chipH = base * 2.0f + 22f;
            float panelH = 16f                    // top pad
                    + 12f                          // progress pips
                    + titleSize + 12f              // title
                    + (key != null ? chipH + 14f : 0f)
                    + instrSize + 18f;             // instruction + bottom pad
            draw.addRectFilled(cx - maxW / 2 - pad, top, cx + maxW / 2 + pad, top + panelH,
                    ImGui.colorConvertFloat4ToU32(0.03f, 0.04f, 0.10f, 0.78f), 14f);
            draw.addRect(cx - maxW / 2 - pad, top, cx + maxW / 2 + pad, top + panelH,
                    ImGui.colorConvertFloat4ToU32(1.0f, 0.85f, 0.35f, 0.35f), 14f, 0, 1.5f);

            // Progress pips  -  one dot per step, current one glows
            int   n = t.stepCount();
            float pipR = 4f, pipGap = 16f;
            float pipX = cx - (n - 1) * pipGap / 2f;
            for (int i = 1; i <= n; i++) {
                boolean done = i < t.stepNumber();
                boolean curr = i == t.stepNumber();
                int col = curr ? ImGui.colorConvertFloat4ToU32(1.0f, 0.88f, 0.3f, pulse)
                        : done ? ImGui.colorConvertFloat4ToU32(0.5f, 0.95f, 0.6f, 0.9f)
                               : ImGui.colorConvertFloat4ToU32(0.45f, 0.5f, 0.6f, 0.6f);
                draw.addCircleFilled(pipX + (i - 1) * pipGap, yy, curr ? pipR + 1.5f : pipR, col);
            }
            yy += 12f;

            // Title  -  huge gold
            draw.addText(font, titleSize, cx - titleW / 2 + 2, yy + 2, shadow, title);
            draw.addText(font, titleSize, cx - titleW / 2, yy, titleCol, title);
            yy += titleSize + 12f;

            // Giant key chip  -  looks like a physical keyboard key, pulsing border
            if (key != null) {
                float x0 = cx - chipW / 2f;
                int chipEdge = ImGui.colorConvertFloat4ToU32(0.02f, 0.02f, 0.06f, 0.95f);
                int chipBg   = ImGui.colorConvertFloat4ToU32(0.12f, 0.14f, 0.24f, 0.97f);
                int chipBdr  = ImGui.colorConvertFloat4ToU32(1.0f, 0.85f, 0.30f, pulse);
                int chipTxt  = ImGui.colorConvertFloat4ToU32(1.0f, 0.96f, 0.80f, 1.0f);
                draw.addRectFilled(x0, yy + 5f, x0 + chipW, yy + chipH + 5f, chipEdge, 10f);
                draw.addRectFilled(x0, yy, x0 + chipW, yy + chipH, chipBg, 10f);
                draw.addRect(x0, yy, x0 + chipW, yy + chipH, chipBdr, 10f, 0, 3f);
                draw.addText(font, keySize, cx - keyTxtW / 2, yy + (chipH - keySize) / 2f, chipTxt, key);
                yy += chipH + 14f;
            }

            // Instruction  -  short, readable
            draw.addText(font, instrSize, cx - instrW / 2 + 1, yy + 1, shadow, instr);
            draw.addText(font, instrSize, cx - instrW / 2, yy, bodyCol, instr);

            // skip hint (bottom-centre, unobtrusive)
            String skip = "[F2] skip tutorial";
            imgui.ImVec2 ss = ImGui.calcTextSize(skip);
            draw.addText(cx - ss.x / 2 + 1, top + panelH + 7f, shadow, skip);
            draw.addText(cx - ss.x / 2,     top + panelH + 6f,
                    ImGui.colorConvertFloat4ToU32(0.6f, 0.62f, 0.7f, 0.6f), skip);
            return;
        }

        // ── TUTORIAL DONE: minimal survival banner ───────────────────────────
        int km = win.enemyManager != null ? win.enemyManager.totalKills : 0;
        String hint = "Survive the waves    -    [F1] for all controls";
        imgui.ImVec2 hs = ImGui.calcTextSize(hint);
        draw.addText(cx - hs.x / 2 + 1, y + 1, shadow, hint);
        draw.addText(cx - hs.x / 2,     y,     bodyCol, hint);
        String kills = "Defeated: " + km;
        imgui.ImVec2 ks = ImGui.calcTextSize(kills);
        draw.addText(cx - ks.x / 2 + 1, y + hs.y + 7f, shadow, kills);
        draw.addText(cx - ks.x / 2,     y + hs.y + 6f,
                ImGui.colorConvertFloat4ToU32(1.0f, 0.6f, 0.5f, 0.9f), kills);
    }

    /**
     * Big TRY IT prompt for a freshly unlocked ability: gold header, giant
     * pulsing key chip, ability name  -  then a green NICE! burst on success.
     * Non-blocking: the wave keeps running underneath.
     */
    void renderTryPrompt(imgui.ImDrawList draw, float screenW, float screenH) {
        ImFont font = ImGui.getFont();
        float  base = ImGui.getFontSize();
        float  cx   = screenW * 0.5f;
        float  top  = screenH * 0.14f;
        int shadow = ImGui.colorConvertFloat4ToU32(0f, 0f, 0f, 0.85f);

        // ── Celebration phase ─────────────────────────────────────────────────
        if (win.tryDoneAbility != null) {
            float t = win.tryDoneTimer;                 // 1.4 -> 0
            float a = Math.min(1f, t / 0.4f);           // fade out at the end
            float size = base * (2.6f + 0.25f * (float) Math.sin(t * 18f));  // excited jitter
            String nice = "NICE!";
            float nw = ImGui.calcTextSize(nice).x * (size / base);
            draw.addText(font, size, cx - nw / 2 + 2, top + 2,
                    ImGui.colorConvertFloat4ToU32(0f, 0f, 0f, 0.85f * a), nice);
            draw.addText(font, size, cx - nw / 2, top,
                    ImGui.colorConvertFloat4ToU32(0.45f, 1.0f, 0.55f, a), nice);
            String got = win.tryDoneAbility.label + "  learned!";
            float gw = ImGui.calcTextSize(got).x * 1.2f;
            draw.addText(font, base * 1.2f, cx - gw / 2 + 1, top + size + 7f,
                    ImGui.colorConvertFloat4ToU32(0f, 0f, 0f, 0.85f * a), got);
            draw.addText(font, base * 1.2f, cx - gw / 2, top + size + 6f,
                    ImGui.colorConvertFloat4ToU32(0.9f, 1.0f, 0.92f, a), got);
            return;
        }

        // ── Prompt phase ──────────────────────────────────────────────────────
        Window.TryStep st = win.tryStep;
        if (st == null) return;
        float pulse = 0.70f + 0.30f * (float) Math.sin(glfwGetTime() * 4.5);

        String header = "NEW POWER  -  TRY IT!";
        float headerSize = base * 1.35f;
        float hw = ImGui.calcTextSize(header).x * (headerSize / base);

        String key = st.chip;
        float keySize = base * 2.4f;
        float keyTxtW = ImGui.calcTextSize(key).x * (keySize / base);
        float chipW = keyTxtW + 52f, chipH = keySize + 22f;

        String name = st.headline;
        float nameSize = base * 1.7f;
        float nw = ImGui.calcTextSize(name).x * (nameSize / base);

        String det = st.detail;
        float detSize = base * 1.15f;
        float dw = ImGui.calcTextSize(det).x * (detSize / base);

        float maxW = Math.max(Math.max(hw, dw), Math.max(chipW, nw));
        float pad = 30f;
        float panelH = 14f + headerSize + 10f + chipH + 12f + nameSize + 8f + detSize + 16f;
        draw.addRectFilled(cx - maxW / 2 - pad, top, cx + maxW / 2 + pad, top + panelH,
                ImGui.colorConvertFloat4ToU32(0.03f, 0.05f, 0.10f, 0.80f), 12f);
        draw.addRect(cx - maxW / 2 - pad, top, cx + maxW / 2 + pad, top + panelH,
                ImGui.colorConvertFloat4ToU32(1.0f, 0.85f, 0.35f, 0.4f * pulse + 0.2f), 12f, 0, 2.5f);

        float yy = top + 14f;
        draw.addText(font, headerSize, cx - hw / 2 + 1, yy + 1, shadow, header);
        draw.addText(font, headerSize, cx - hw / 2, yy,
                ImGui.colorConvertFloat4ToU32(1.0f, 0.85f, 0.35f, 1f), header);
        yy += headerSize + 10f;

        float x0 = cx - chipW / 2f;
        draw.addRectFilled(x0, yy + 5f, x0 + chipW, yy + chipH + 5f,
                ImGui.colorConvertFloat4ToU32(0.02f, 0.02f, 0.06f, 0.95f), 10f);
        draw.addRectFilled(x0, yy, x0 + chipW, yy + chipH,
                ImGui.colorConvertFloat4ToU32(0.12f, 0.14f, 0.24f, 0.97f), 10f);
        draw.addRect(x0, yy, x0 + chipW, yy + chipH,
                ImGui.colorConvertFloat4ToU32(1.0f, 0.85f, 0.30f, pulse), 10f, 0, 3f);
        draw.addText(font, keySize, cx - keyTxtW / 2, yy + (chipH - keySize) / 2f,
                ImGui.colorConvertFloat4ToU32(1.0f, 0.96f, 0.80f, 1f), key);
        yy += chipH + 12f;

        draw.addText(font, nameSize, cx - nw / 2 + 1, yy + 1, shadow, name);
        draw.addText(font, nameSize, cx - nw / 2, yy,
                ImGui.colorConvertFloat4ToU32(0.92f, 0.95f, 1.0f, 1f), name);
        yy += nameSize + 8f;

        draw.addText(font, detSize, cx - dw / 2 + 1, yy + 1, shadow, det);
        draw.addText(font, detSize, cx - dw / 2, yy,
                ImGui.colorConvertFloat4ToU32(0.75f, 0.88f, 1.0f, 1f), det);
    }

    /**
     * Always-on key reminders, centred just above the hotbar.
     * Shows the three core combat keys during the wave phase, and the flight
     * keys once the Voyage opens  -  because nobody remembers a keybind they
     * read once in a tutorial.
     */
    void renderCombatKeyStrip(imgui.ImDrawList draw, float screenW, float screenH) {
        Progression prog = win.player.progression;
        String[][] entries;
        if (win.voyageStarted) {
            entries = new String[][] {
                { "SPACE x2", "FLY" },
                { "V", "FLIGHT MODE" },
                { "LMB", "FIRE WEAPON" },
            };
        } else {
            java.util.List<String[]> list = new java.util.ArrayList<>();
            list.add(new String[]{ "LMB hold", "SNIPE" });
            if (prog.isUnlocked(Progression.Ability.SLASH)) list.add(new String[]{ "F", "SLASH" });
            if (prog.isUnlocked(Progression.Ability.DASH))  list.add(new String[]{ "Q", "DASH" });
            list.add(new String[]{ "F10", "RADAR" });
            list.add(new String[]{ "F9", "SKIP WAVE" });
            entries = list.toArray(new String[0][]);
        }

        ImFont font = ImGui.getFont();
        float  base = ImGui.getFontSize();
        float  keySize = base * 1.1f;
        float  padX = 8f, gap = 26f, chipGapLabel = 7f;

        // Measure total width
        float total = 0f;
        float[] keyWs = new float[entries.length], lblWs = new float[entries.length];
        for (int i = 0; i < entries.length; i++) {
            keyWs[i] = ImGui.calcTextSize(entries[i][0]).x * (keySize / base) + padX * 2;
            lblWs[i] = ImGui.calcTextSize(entries[i][1]).x;
            total += keyWs[i] + chipGapLabel + lblWs[i];
            if (i < entries.length - 1) total += gap;
        }

        float chipH = keySize + 10f;
        float x = screenW / 2f - total / 2f;
        // Sits ABOVE the health bar (HP bar top edge = screenH - 80) so the
        // strip never overlaps HP/MP  -  that overlap was a playtest complaint.
        float yTop = screenH - 80f - 14f - chipH;

        int chipBg  = ImGui.colorConvertFloat4ToU32(0.07f, 0.09f, 0.16f, 0.82f);
        int chipBdr = ImGui.colorConvertFloat4ToU32(0.85f, 0.75f, 0.4f, 0.75f);
        int keyTxt  = ImGui.colorConvertFloat4ToU32(1.0f, 0.95f, 0.8f, 0.95f);
        int lblTxt  = ImGui.colorConvertFloat4ToU32(0.85f, 0.9f, 1.0f, 0.85f);
        int shadow  = ImGui.colorConvertFloat4ToU32(0f, 0f, 0f, 0.8f);

        for (int i = 0; i < entries.length; i++) {
            draw.addRectFilled(x, yTop, x + keyWs[i], yTop + chipH, chipBg, 6f);
            draw.addRect(x, yTop, x + keyWs[i], yTop + chipH, chipBdr, 6f, 0, 1.5f);
            draw.addText(font, keySize, x + padX, yTop + 5f, keyTxt, entries[i][0]);
            float lx = x + keyWs[i] + chipGapLabel;
            float ly = yTop + (chipH - base) / 2f;
            draw.addText(lx + 1, ly + 1, shadow, entries[i][1]);
            draw.addText(lx, ly, lblTxt, entries[i][1]);
            x = lx + lblWs[i] + gap;
        }
    }

    /**
     * The Voyage HUD: a persistent top objective banner + a world-space waypoint
     * marker pointing at the beam of light, plus the big forge-reveal banner. This
     * is what makes the voyage impossible to get lost on.
     */
    void renderVoyageHUD(imgui.ImDrawList draw, Camera camera, float screenW, float screenH) {
        // The FLIGHT lesson owns the screen  -  all voyage messaging waits until
        // the player is actually airborne (focused "learn to fly" round).
        if (win.tryStep != null && win.tryStep.ability == Progression.Ability.FLIGHT) return;
        int shadow = ImGui.colorConvertFloat4ToU32(0f, 0f, 0f, 0.85f);

        // ── Forge / directive reveal (big, centred, fades over its last second) ──
        if (win.voyageMsg != null && win.voyageMsgTimer > 0f) {
            float a = Math.min(1f, win.voyageMsgTimer / 0.8f);   // fade-out last 0.8 s
            a = Math.min(a, (7.5f - win.voyageMsgTimer) / 0.4f + 0.001f); // fade-in first 0.4 s
            a = Math.max(0f, Math.min(1f, a));
            String msg = win.voyageMsg;
            // Word-wrap to ~58 chars.
            java.util.List<String> lines = wrap(msg, 58);
            float lh = 20f, padX = 22f, padY = 14f;
            float boxW = 0f;
            for (String ln : lines) boxW = Math.max(boxW, ImGui.calcTextSize(ln).x);
            boxW += padX * 2;
            float boxH = lines.size() * lh + padY * 2;
            float bx = screenW / 2f - boxW / 2f;
            float by = screenH * 0.30f;
            int bg     = ImGui.colorConvertFloat4ToU32(0.05f, 0.04f, 0.10f, 0.86f * a);
            int border = ImGui.colorConvertFloat4ToU32(1.0f, 0.82f, 0.35f, 0.7f * a);
            int txt    = ImGui.colorConvertFloat4ToU32(1.0f, 0.95f, 0.8f, a);
            int sh     = ImGui.colorConvertFloat4ToU32(0f, 0f, 0f, 0.8f * a);
            draw.addRectFilled(bx, by, bx + boxW, by + boxH, bg, 10f);
            draw.addRect(bx, by, bx + boxW, by + boxH, border, 10f, 0, 2.0f);
            float ty = by + padY;
            for (String ln : lines) {
                float tw = ImGui.calcTextSize(ln).x;
                draw.addText(screenW / 2f - tw / 2f + 1, ty + 1, sh, ln);
                draw.addText(screenW / 2f - tw / 2f,     ty,     txt, ln);
                ty += lh;
            }
        }

        if (win.voyage == null || !win.voyageStarted || !win.voyage.active || !win.voyage.beaconReady()) return;
        // Suppress the objective banner while the forge/directive message is dominating the screen
        if (win.voyageMsgTimer > 3.5f) return;

        org.joml.Vector3f beacon = win.voyage.beaconPos();
        float[] col = win.voyage.beaconColor();
        if (beacon == null || col == null) return;
        int beamCol = ImGui.colorConvertFloat4ToU32(col[0], col[1], col[2], 1f);
        int beamDim = ImGui.colorConvertFloat4ToU32(col[0], col[1], col[2], 0.55f);

        // ── Persistent objective banner (top-centre) ─────────────────────────
        float cx = screenW * 0.5f;
        float y  = 96f;  // lower than 44  -  leaves room for MT off-screen arrow at ~54
        String dir = win.voyage.directive();
        String rew = win.voyage.rewardLine();
        org.joml.Vector3f pp = win.player.position;
        float d = (float) Math.sqrt(
                (pp.x - beacon.x) * (pp.x - beacon.x) +
                (pp.y - beacon.y) * (pp.y - beacon.y) +
                (pp.z - beacon.z) * (pp.z - beacon.z));
        String distStr = (d >= 1000f) ? String.format("%.1f km", d / 1000f) : String.format("%.0f m", d);
        String line1 = ">  " + dir;
        String line2 = rew + "        " + distStr + " away";

        imgui.ImVec2 s1 = ImGui.calcTextSize(line1);
        imgui.ImVec2 s2 = ImGui.calcTextSize(line2);
        float maxW = Math.max(s1.x, s2.x) + 30f;
        int panelBg = ImGui.colorConvertFloat4ToU32(0.05f, 0.07f, 0.12f, 0.66f);
        draw.addRectFilled(cx - maxW / 2, y - 7f, cx + maxW / 2, y + 38f, panelBg, 8f);
        draw.addRect(cx - maxW / 2, y - 7f, cx + maxW / 2, y + 38f, beamDim, 8f, 0, 1.6f);
        draw.addText(cx - s1.x / 2 + 1, y + 1, shadow, line1);
        draw.addText(cx - s1.x / 2,     y,     beamCol, line1);
        draw.addText(cx - s2.x / 2 + 1, y + 21f, shadow, line2);
        draw.addText(cx - s2.x / 2,     y + 20f,
                ImGui.colorConvertFloat4ToU32(0.9f, 0.95f, 1.0f, 0.95f), line2);

        // ── World-space waypoint marker (points to the beam) ─────────────────
        Matrix4f vp = new Matrix4f(camera.getProjectionMatrix()).mul(camera.getViewMatrix());
        org.joml.Vector3f aim = new org.joml.Vector3f(beacon.x, beacon.y + 25f, beacon.z);
        org.joml.Vector4f clip = new org.joml.Vector4f(aim.x, aim.y, aim.z, 1f).mul(vp);
        float ecx = screenW * 0.5f, ecy = screenH * 0.5f;
        boolean behind = clip.w <= 0.001f;
        float ndcX = behind ? 0 : clip.x / clip.w;
        float ndcY = behind ? 0 : clip.y / clip.w;
        boolean onScreen = !behind && ndcX >= -1f && ndcX <= 1f && ndcY >= -1f && ndcY <= 1f;

        if (onScreen) {
            float sx = (ndcX * 0.5f + 0.5f) * screenW;
            float sy = (1f - (ndcY * 0.5f + 0.5f)) * screenH;
            // Diamond marker + distance label hovering over the beam.
            float r = 9f;
            draw.addTriangleFilled(sx, sy - r, sx + r, sy, sx, sy + r, beamCol);
            draw.addTriangleFilled(sx, sy - r, sx - r, sy, sx, sy + r, beamDim);
            draw.addCircle(sx, sy, r + 3f, beamCol, 16, 1.6f);
            String lbl = distStr;
            float tw = ImGui.calcTextSize(lbl).x;
            draw.addText(sx - tw / 2 + 1, sy + r + 4f + 1, shadow, lbl);
            draw.addText(sx - tw / 2,     sy + r + 4f,     beamCol, lbl);
        } else {
            // Off-screen / behind: edge arrow pointing toward the beacon.
            float dirx = ndcX, diry = ndcY;
            if (behind) { dirx = -dirx; diry = -diry; }
            float ang = (float) Math.atan2(-diry, dirx);
            float radius = Math.min(ecx, ecy) * 0.78f;
            float ix = ecx + (float) Math.cos(ang) * radius;
            float iy = ecy + (float) Math.sin(ang) * radius;
            float sz = 16f;
            float ca = (float) Math.cos(ang), sa = (float) Math.sin(ang);
            float[][] tri = {{ sz, 0 }, { -sz * 0.7f, sz * 0.7f }, { -sz * 0.7f, -sz * 0.7f }};
            for (float[] p : tri) {
                float rx = p[0] * ca - p[1] * sa, ry = p[0] * sa + p[1] * ca;
                p[0] = ix + rx; p[1] = iy + ry;
            }
            draw.addTriangleFilled(tri[0][0], tri[0][1], tri[1][0], tri[1][1], tri[2][0], tri[2][1], beamCol);
            draw.addTriangle(tri[0][0], tri[0][1], tri[1][0], tri[1][1], tri[2][0], tri[2][1], shadow, 1.6f);
        }
    }

    /** Greedy word-wrap to a rough character width. */
    private static java.util.List<String> wrap(String text, int width) {
        java.util.List<String> out = new java.util.ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.length() > 0 && line.length() + word.length() + 1 > width) {
                out.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    void renderWelcomeBanner(imgui.ImDrawList draw,
                             float screenW, float screenH, float timer) {
        float alpha;
        if (timer > 5.6f) alpha = (6f - timer) / 0.4f;   // fade-in 0->0.4 s
        else if (timer < 1.0f) alpha = timer;                   // fade-out last 1 s
        else alpha = 1f;
        alpha = Math.max(0f, Math.min(1f, alpha));

        int bg = ImGui.colorConvertFloat4ToU32(0.04f, 0.04f, 0.14f, 0.90f * alpha);
        int border = ImGui.colorConvertFloat4ToU32(0.4f, 0.85f, 1.0f, 0.55f * alpha);
        int white = ImGui.colorConvertFloat4ToU32(1.0f, 1.0f, 1.0f, alpha);
        int cyan = ImGui.colorConvertFloat4ToU32(0.4f, 0.9f, 1.0f, alpha);
        int black = ImGui.colorConvertFloat4ToU32(0f, 0f, 0f, alpha);

        float bW = 440f, bH = 56f;
        float bX = screenW / 2f - bW / 2f;
        float bY = screenH * 0.19f;

        draw.addRectFilled(bX, bY, bX + bW, bY + bH, bg, 8f);
        draw.addRect(bX, bY, bX + bW, bY + bH, border, 8f, 0, 1.8f);

        String line1 = win.playtestMode
                ? "Everything's unlocked  -  just explore and have fun!"
                : "A crystal has bonded to you.";
        String line2 = win.playtestMode
                ? "Slots 1-5 = weapons (Right-Click to fire).   [F1] = full guide."
                : "Survive each wave to unlock a new power.   [F1] = guide anytime.";

        // Approximate centering (default font ~7 px/char)
        draw.addText(bX + bW / 2f - line1.length() * 3.6f, bY + 8f, black, line1);
        draw.addText(bX + bW / 2f - line1.length() * 3.6f - 1, bY + 7f, white, line1);
        draw.addText(bX + bW / 2f - line2.length() * 3.6f, bY + 29f, black, line2);
        draw.addText(bX + bW / 2f - line2.length() * 3.6f - 1, bY + 28f, cyan, line2);
    }

    /**
     * Always-on compact controls legend shown during a playtest run, so the
     * player can discover every system without anyone explaining a thing.
     * Sits bottom-left, low-opacity, out of the way of the action.
     */
    void renderPlaytestLegend(imgui.ImDrawList draw, float screenW, float screenH) {
        String[] lines = {
            "MOVE  WASD    JUMP  Space   FLY  Space x2 (V cycles)",
            "SLOTS 1-5  weapons -> Right-Click to fire",
            "F  slash    Q  dash    E  blink    Z  phase    G  cannonball",
            "X  drone    Left-Alt  backpack    T  chat",
            "F1  full guide          /biome <name>  to teleport",
        };
        float pad = 8f, lh = 15f;
        float boxW = 360f;
        float boxH = lines.length * lh + pad * 2f;
        float x = 12f;
        float y = screenH - boxH - 12f;

        int bg     = ImGui.colorConvertFloat4ToU32(0.03f, 0.04f, 0.08f, 0.55f);
        int border = ImGui.colorConvertFloat4ToU32(0.4f, 0.8f, 1.0f, 0.30f);
        int head   = ImGui.colorConvertFloat4ToU32(1.0f, 0.85f, 0.35f, 0.95f);
        int body   = ImGui.colorConvertFloat4ToU32(0.85f, 0.92f, 1.0f, 0.85f);
        int shadow = ImGui.colorConvertFloat4ToU32(0f, 0f, 0f, 0.7f);

        draw.addRectFilled(x, y, x + boxW, y + boxH, bg, 6f);
        draw.addRect(x, y, x + boxW, y + boxH, border, 6f, 0, 1.4f);

        for (int i = 0; i < lines.length; i++) {
            int col = (i == 1) ? head : body;
            float ly = y + pad + i * lh;
            draw.addText(x + pad + 1, ly + 1, shadow, lines[i]);
            draw.addText(x + pad,     ly,     col,    lines[i]);
        }
    }

    /**
     * One-liner contextual hint (e.g. first stand deploy or first seal placed).
     * Fades out over the last 0.6 s of its duration.
     */
    void renderHintBanner(imgui.ImDrawList draw,
                          float screenW, float screenH,
                          float timer, String text) {
        float alpha = (timer < 0.6f) ? timer / 0.6f : 1f;
        alpha = Math.max(0f, Math.min(1f, alpha));

        int bg = ImGui.colorConvertFloat4ToU32(0.0f, 0.0f, 0.0f, 0.72f * alpha);
        int fg = ImGui.colorConvertFloat4ToU32(1.0f, 0.88f, 0.3f, alpha);
        int black = ImGui.colorConvertFloat4ToU32(0f, 0f, 0f, alpha);

        // Rough text width: 7 px/char
        float tw = text.length() * 7.0f;
        float bX = screenW / 2f - tw / 2f - 12f;
        float bY = 36f; // just below the wave counter line

        draw.addRectFilled(bX, bY, bX + tw + 24f, bY + 22f, bg, 4f);
        draw.addText(bX + 13f, bY + 5f, black, text);
        draw.addText(bX + 12f, bY + 4f, fg, text);
    }


    void renderDebugMenu() {
        ImGui.begin("Debug");
        ImGui.text(String.format("XYZ: %.3f / %.3f / %.3f",
                win.player.position.x, win.player.position.y, win.player.position.z));
        ImGui.text(String.format("FPS: %.1f", ImGui.getIO().getFramerate()));
        ImGui.text(String.format("Seed: %d", GameConfig.seed));
        ImGui.text(String.format("DeltaTime: %.4fs", ImGui.getIO().getDeltaTime()));
        ImGui.text(String.format("TimeScale: %.3f", TimeController.getInstance().getScale()));
        if (win.player.debugMode) {
            ImGui.text("Flight mode: " + win.player.flightController.getMode().name());
        }
        ImGui.separator();
        float[] fov = {GameConfig.fov};
        if (ImGui.sliderFloat("FOV", fov, 30f, 120f)) GameConfig.fov = fov[0];
        int[] rd = {GameConfig.renderDistance};
        if (ImGui.sliderInt("Render Distance", rd, 2, 16)) GameConfig.renderDistance = rd[0];
        ImGui.separator();
        // ── Seed editor ───────────────────────────────────────────────────────
        ImGui.text("World Seed:");
        ImGui.setNextItemWidth(140);
        // Pre-fill with current seed on first open
        if (win.seedInput.get().isEmpty()) win.seedInput.set(String.valueOf(GameConfig.seed));
        ImGui.inputText("##seedEdit", win.seedInput);
        ImGui.sameLine();
        if (ImGui.button("New World")) {
            try {
                long newSeed = Long.parseLong(win.seedInput.get().trim());
                GameConfig.seed = newSeed;
                win.worldGen.resetSeed(newSeed);
                win.world.clearAllChunks();
                win.world.meshingQueue.clear();
                win.player.position.set(2000f, 250f, 2000f);
                win.isPreloading = true;
                // Close debug menu so the preload screen shows
                win.showDebug = false;
                glfwSetInputMode(win.window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
            } catch (NumberFormatException ignored) {
                // Bad input  -  do nothing
            }
        }
        ImGui.end();
    }

    // --- FILE: ./main/java/com/leaf/game/core/WindowHud.java ---
// (Replace the updateBreaking method with this updated version)

    void updateBreaking(float deltaTime) {
        if (!win.breakingActive || win.lastTarget == null || !win.lastTarget.hit) {
            win.breakProgress = 0.0f;
            win.digParticleTimer = 0.0f; // Reset particle timer when not digging
            return;
        }
        int tx = win.lastTarget.hitX, ty = win.lastTarget.hitY, tz = win.lastTarget.hitZ;
        if (tx != win.breakX || ty != win.breakY || tz != win.breakZ) {
            win.breakProgress = 0.0f;
            win.breakX = tx;
            win.breakY = ty;
            win.breakZ = tz;
            win.digSoundTimer = 0f;
            win.digPreDelay = 0f;
            win.digParticleTimer = 0.0f; // Reset particle timer on target change
        }
        Block target = win.world.getBlock(tx, ty, tz);
        if (!target.isSolid()) {
            win.breakProgress = 0.0f;
            return;
        }

        win.breakProgress += deltaTime / target.hardness;

        // ── CONTINUOUS DIG PARTICLES (Debris Spray) ──────────────────────────
        // Spawns small block fragments flying out from the digging location
        // to provide solid, responsive visual feedback without drawing textures.
        final float DIG_START_DELAY = 0.18f;
        win.digPreDelay += deltaTime;
        if (win.digPreDelay >= DIG_START_DELAY) {
            win.digParticleTimer -= deltaTime;
            if (win.digParticleTimer <= 0f) {
                // Spawn a particle at a random offset on the block's face
                float px = tx + 0.5f + (win.shakeRng.nextFloat() - 0.5f) * 0.4f;
                float py = ty + 0.5f + (win.shakeRng.nextFloat() - 0.5f) * 0.4f;
                float pz = tz + 0.5f + (win.shakeRng.nextFloat() - 0.5f) * 0.4f;

                // Launch velocity: fly upward and slightly outward
                Vector3f vel = new Vector3f(
                        (win.shakeRng.nextFloat() - 0.5f) * 2.5f,
                        1.5f + win.shakeRng.nextFloat() * 2.5f,
                        (win.shakeRng.nextFloat() - 0.5f) * 2.5f
                );

                win.droppedItems.add(new DroppedItem(tx, ty, tz, target, vel));
                // Fire a new particle every 0.15 seconds
                win.digParticleTimer = 0.15f;
            }

            // ── DIG SOUND ────────────────────────────────────────────────────
            win.digSoundTimer -= deltaTime;
            if (win.digSoundTimer <= 0f) {
                String digSnd = Window.blockDigSound(target);
                if ("crystal_clank_seq".equals(digSnd)) {
                    digSnd = win.nextCrystalClank();
                }
                if (digSnd != null) AudioManager.playVaried(digSnd, 0.65f, 0.08f);
                win.digSoundTimer = 0.30f + Math.min(0.20f, target.hardness * 0.08f);
            }
        }

        if (win.breakProgress >= 1.0f) {
            win.droppedItems.add(new DroppedItem(tx, ty, tz, target));
            win.world.setBlock(tx, ty, tz, Block.AIR);
            win.world.rebuildChunkAt(tx, ty, tz);
            String breakSnd = Window.blockBreakSound(target);
            if (breakSnd != null) AudioManager.playVaried(breakSnd, 0.85f, 0.07f);
            win.breakProgress = 0.0f;
            win.digSoundTimer = 0f;
            win.digPreDelay = 0f;
            win.digParticleTimer = 0.0f;
        }
    }

    // ── SNOW PARTICLE OVERLAY ─────────────────────────────────────────────────
    // Screen-space procedural snowfall drawn with ImGui foreground draw list.
    // Uses a deterministic hash (no per-flake Java state)  -  N seeds produce N
    // stable screen-position trajectories that drift downward + windward over time.
    //
    //  intensity  0..1  driven by altitude above snowAltitude
    //  gustStrength 0..1  tilts snow sideways when wind gusts
    //  gustAngle  radians  direction of horizontal tilt
    // ─────────────────────────────────────────────────────────────────────────
    public void renderSnowEffect(float screenW, float screenH,
                                  float timeAccum, float intensity,
                                  float gustStrength, float gustAngle) {
        if (intensity < 0.02f) return;

        var draw = ImGui.getForegroundDrawList();

        // 3 depth layers: {sizeMin, sizeMax, fallSpeed px/s, timeScale, count}
        // Far = tiny/slow (background haze), near = large/fast (immersive)
        float[][] layers = {
            { 0.8f, 1.4f,  22f, 0.60f, 60 },
            { 1.3f, 2.2f,  42f, 1.00f, 55 },
            { 2.0f, 3.5f,  72f, 1.55f, 35 }
        };

        // Wind drift: how far horizontally per second of accumulated time
        float driftX = (float)Math.cos(gustAngle) * gustStrength * 55f;
        float driftY = (float)Math.sin(gustAngle) * gustStrength * 18f;

        for (int li = 0; li < layers.length; li++) {
            float sMin  = layers[li][0];
            float sMax  = layers[li][1];
            float speed = layers[li][2];
            float ts    = layers[li][3];
            int   count = (int)(layers[li][4] * intensity);

            float t = timeAccum * ts;

            for (int i = 0; i < count; i++) {
                int seed = i + li * 1000;

                // Deterministic base position + motion parameters from seed
                float bx    = fhash(seed * 7)     * screenW;
                float by    = fhash(seed * 7 + 1) * screenH;
                float spd   = speed  + fhash(seed * 7 + 2) * speed * 0.35f;
                float phase = fhash(seed * 7 + 3) * 6.2832f;  // twinkle phase
                float size  = sMin   + fhash(seed * 7 + 4) * (sMax - sMin);
                float sway  = fhash(seed * 7 + 5) * 28f - 14f; // subtle horizontal sway

                // Current screen position (wraps around both axes)
                float cy = (by + spd * t) % (screenH + size * 4);
                if (cy < 0) cy += screenH + size * 4;
                float cx = bx + (driftX + sway) * t * 0.055f;
                cx = ((cx % screenW) + screenW) % screenW;

                // Slight shimmer
                float twinkle = 0.72f + 0.28f * (float)Math.sin(timeAccum * 2.4f + phase);
                // Near-edge fade so flakes don't pop in/out at screen border
                float edgeFade = edgeFade(cx, cy, screenW, screenH, size * 6);
                float a = intensity * twinkle * edgeFade;

                int col = ImGui.colorConvertFloat4ToU32(1f, 1f, 1f, a);
                draw.addCircleFilled(cx, cy, size, col);
            }
        }
    }

    /** Deterministic float hash in [0,1] from an integer seed. */
    private float fhash(int n) {
        n = (n ^ 0xB5297A4D) ^ (n >> 14);
        n *= 0x68E31DA4;
        n ^= n >> 14;
        n *= 0x1B56C4E9;
        n ^= n >> 16;
        return ((n >>> 8) & 0xFFFF) / 65535f;
    }

    /** Returns 0..1 fade that is 1 in the interior and 0 near the screen edge. */
    private float edgeFade(float cx, float cy, float w, float h, float margin) {
        float ex = Math.min(cx, w - cx) / margin;
        float ey = Math.min(cy, h - cy) / margin;
        float t  = Math.min(1f, Math.min(ex, ey));
        return t * t * (3f - 2f * t); // smoothstep
    }

    void renderChocolateDiscoConsole() {
        if (!win.showDiscoUI) return;

        // Position on the left side of the screen
        ImGui.setNextWindowPos(30, 100, imgui.flag.ImGuiCond.FirstUseEver);
        ImGui.begin("Chocolate Disco Datapad", imgui.flag.ImGuiWindowFlags.AlwaysAutoResize | imgui.flag.ImGuiWindowFlags.NoCollapse);

        ImGui.textColored(1.0f, 0.8f, 0.1f, 1.0f, "MODE: ANNIHILATE");
        ImGui.separator();
        ImGui.spacing();

        // X-Axis Headers
        ImGui.text("   "); ImGui.sameLine();
        for (int c = 0; c < 9; c++) {
            ImGui.text(" " + (c + 1) + " "); ImGui.sameLine();
        }
        ImGui.newLine();

        // Reset hover state (updated only if hovered this frame)
        win.cdHoverR = -1;
        win.cdHoverC = -1;

        // 9x9 Grid of ImGui Buttons
        for (int r = 0; r < 9; r++) {
            // Y-Axis Headers
            ImGui.text("" + (char)('A' + r) + " "); ImGui.sameLine();

            for (int c = 0; c < 9; c++) {
                if (c > 0) ImGui.sameLine();
                ImGui.pushID(r * 9 + c);

                boolean marked = win.cdMarked[r][c];
                boolean det    = win.cdDetT[r][c] > 0;

                // Color the buttons based on their state
                if (det) {
                    ImGui.pushStyleColor(imgui.flag.ImGuiCol.Button, 1.0f, 0.2f, 0.2f, 1.0f);
                    ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonHovered, 1.0f, 0.4f, 0.4f, 1.0f);
                } else if (marked) {
                    ImGui.pushStyleColor(imgui.flag.ImGuiCol.Button, 1.0f, 0.7f, 0.1f, 1.0f);
                    ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonHovered, 1.0f, 0.85f, 0.3f, 1.0f);
                } else {
                    ImGui.pushStyleColor(imgui.flag.ImGuiCol.Button, 0.15f, 0.15f, 0.2f, 1.0f);
                    ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonHovered, 0.2f, 0.8f, 0.9f, 1.0f);
                }

                if (ImGui.button("##cell", 28f, 28f)) {
                    if (!det) {
                        win.cdMarked[r][c] = !marked;
                        win.cdMeshDirty = true;
                    }
                }

                if (ImGui.isItemHovered()) {
                    win.cdHoverR = r;
                    win.cdHoverC = c;
                    win.cdMeshDirty = true;
                }

                ImGui.popStyleColor(2);
                ImGui.popID();
            }
            ImGui.newLine();
        }

        ImGui.spacing(); ImGui.separator(); ImGui.spacing();

        if (ImGui.button("EXECUTE", 280f, 40f)) {
            win.detonateDiscoGrid(win.world);
        }
        ImGui.spacing();
        if (ImGui.button("DISMISS", 280f, 30f)) {
            win.dismissDiscoGrid();
        }

        ImGui.end();
    }
    // ═══════════════════════════════════════════════════════════════════════════
    //  CHOCOLATE DISCO: IMGUI WRIST-CONSOLE
    // ═══════════════════════════════════════════════════════════════════════════

    enum DiscoMode { ANNIHILATE, DROP, REDIRECT }
    DiscoMode currentDiscoMode = DiscoMode.ANNIHILATE;

    private void handleDiscoClick(int r, int c) {
        if (currentDiscoMode == DiscoMode.ANNIHILATE) {
            // Mode 1: Toggle the Mark for Detonation
            if (win.cdDetT[r][c] <= 0f) {
                win.cdMarked[r][c] = !win.cdMarked[r][c];
                win.cdMeshDirty = true;
            }
        }
        else if (currentDiscoMode == DiscoMode.DROP) {
            // Mode 2: Call down an orbital strike of your currently selected block!
            float wx = win.cdGridX - Window.CD_HALF + c * Window.CD_CELL + Window.CD_CELL * 0.5f;
            float wz = win.cdGridZ - Window.CD_HALF + r * Window.CD_CELL + Window.CD_CELL * 0.5f;
            float wy = win.cdGridY + 40f; // Drop from the sky

            com.leaf.game.entity.DroppedItem item = new com.leaf.game.entity.DroppedItem(
                    (int)wx, (int)wy, (int)wz, win.selectedBlock, new org.joml.Vector3f(0f, -40f, 0f) // Heavy downward slam velocity
            );
            win.droppedItems.add(item);
            com.leaf.game.core.AudioManager.play("fall_hit", 0.8f);
        }
        else if (currentDiscoMode == DiscoMode.REDIRECT) {
            // Mode 3: Instantly warp all active projectiles to this cell
            float wx = win.cdGridX - Window.CD_HALF + c * Window.CD_CELL + Window.CD_CELL * 0.5f;
            float wz = win.cdGridZ - Window.CD_HALF + r * Window.CD_CELL + Window.CD_CELL * 0.5f;

            boolean redirected = false;

            // Redirect player Void Shards
            for (com.leaf.game.entity.AttackController.ActiveBolt bolt : win.player.attacks.activeBolts) {
                bolt.pos.set(wx, win.cdGridY + 1.5f, wz);
                redirected = true;
            }
            // Redirect enemy boulders/projectiles
            for (com.leaf.game.entity.EnemyManager.EnemyProjectile proj : win.enemyManager.projectiles) {
                proj.pos.set(wx, win.cdGridY + 1.5f, wz);
                redirected = true;
            }

            if (redirected) {
                com.leaf.game.core.AudioManager.play("snipe_redirect", 1.0f);
                com.leaf.game.core.ScreenEffectManager.INSTANCE.flash(0f, 0.8f, 1.0f, 0.4f, 0.1f);
            }
        }
    }
    /** Simple hook glyph for the hotbar (diagonal line + three-prong grappling tip). */
    private void drawGrappleIcon(imgui.ImDrawList draw, float x0, float y0, float x1, float y1) {
        float w = x1 - x0, h = y1 - y0;
        float cx = (x0 + x1) * 0.5f;
        int ropeCol = ImGui.colorConvertFloat4ToU32(0.85f, 0.85f, 0.85f, 0.9f);
        int hookCol = ImGui.colorConvertFloat4ToU32(0.55f, 0.55f, 0.60f, 1.0f);

        // Draw cable
        draw.addLine(x0 + w*0.22f, y1 - h*0.22f, cx, y0 + h*0.42f, ropeCol, 2.2f);
        // Draw hook head
        draw.addTriangleFilled(cx, y0 + h*0.42f, cx - w*0.14f, y0 + h*0.24f, cx + w*0.14f, y0 + h*0.24f, hookCol);
    }


}
