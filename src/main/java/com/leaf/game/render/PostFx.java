package com.leaf.game.render;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL30.*;

/**
 * PostFx — the final full-screen pass every frame goes through.
 *
 * <ul>
 *   <li><b>Bloom</b>: a proper mip-pyramid bloom (the "dual filter" technique used by
 *       most modern engines). The HDR scene is bright-passed and downsampled five
 *       times, then upsampled back with additive blending, so glows are wide, soft
 *       and cheap. Only light above {@code threshold} blooms: lava, glints, ability
 *       energy — not ordinary sunlit terrain.</li>
 *   <li><b>Grade</b>: a soft highlight shoulder (HDR values roll off instead of
 *       clipping), a gentle cool-shadow / warm-highlight split tone, a vignette
 *       and a tiny dither that removes 8-bit banding from sky gradients.</li>
 * </ul>
 *
 * The scene must already be rendered into an HDR (RGBA16F) texture.
 */
public final class PostFx {

    private static final int LEVELS = 5;

    private final int[] mipFbo = new int[LEVELS];
    private final int[] mipTex = new int[LEVELS];
    private final int[] mipW   = new int[LEVELS];
    private final int[] mipH   = new int[LEVELS];
    private int builtW = -1, builtH = -1;

    private Shader down, up, composite;
    private int quadVao;

    /** @param screenQuadVao VAO with a full-screen quad (pos at 0, uv at 1), 6 vertices */
    public void init(int screenQuadVao) {
        quadVao   = screenQuadVao;
        down      = new Shader("src/main/resources/shaders/distort_vertex.glsl", "src/main/resources/shaders/bloom_down.glsl");
        up        = new Shader("src/main/resources/shaders/distort_vertex.glsl", "src/main/resources/shaders/bloom_up.glsl");
        composite = new Shader("src/main/resources/shaders/distort_vertex.glsl", "src/main/resources/shaders/post_composite.glsl");
    }

    private void ensureSize(int w, int h) {
        if (w == builtW && h == builtH) return;
        for (int i = 0; i < LEVELS; i++) {
            if (mipFbo[i] != 0) { glDeleteFramebuffers(mipFbo[i]); glDeleteTextures(mipTex[i]); }
            mipW[i] = Math.max(1, w >> (i + 1));
            mipH[i] = Math.max(1, h >> (i + 1));
            mipTex[i] = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, mipTex[i]);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_R11F_G11F_B10F, mipW[i], mipH[i], 0,
                    GL_RGB, GL_FLOAT, (java.nio.ByteBuffer) null);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
            mipFbo[i] = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, mipFbo[i]);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, mipTex[i], 0);
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glBindTexture(GL_TEXTURE_2D, 0);
        builtW = w; builtH = h;
    }

    private void drawQuad() {
        glBindVertexArray(quadVao);
        glDrawArrays(GL_TRIANGLES, 0, 6);
        glBindVertexArray(0);
    }

    /**
     * Bloom + grade {@code sceneTex} onto the default framebuffer.
     *
     * @param threshold     HDR brightness where bloom starts (1.0 = only true emitters)
     * @param bloomStrength how much of the glow is added back
     * @param flash         extra white-gold wash (Deprivation Domain strike), 0 = none
     */
    public void apply(int sceneTex, int w, int h, float threshold, float bloomStrength,
                      float flash, float vignette, float time) {
        ensureSize(w, h);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_BLEND);
        glActiveTexture(GL_TEXTURE0);

        // ── Downsample chain (first step also bright-passes) ─────────────────
        down.bind();
        down.setUniform("src", 0);
        int srcTex = sceneTex, srcW = w, srcH = h;
        for (int i = 0; i < LEVELS; i++) {
            glBindFramebuffer(GL_FRAMEBUFFER, mipFbo[i]);
            glViewport(0, 0, mipW[i], mipH[i]);
            down.setUniform("texel", 1f / srcW, 1f / srcH);
            down.setUniform("prefilter", i == 0 ? 1 : 0);
            down.setUniform("threshold", threshold);
            glBindTexture(GL_TEXTURE_2D, srcTex);
            drawQuad();
            srcTex = mipTex[i]; srcW = mipW[i]; srcH = mipH[i];
        }
        down.unbind();

        // ── Upsample back up, accumulating each level additively ─────────────
        up.bind();
        up.setUniform("src", 0);
        glEnable(GL_BLEND);
        glBlendFunc(GL_ONE, GL_ONE);
        for (int i = LEVELS - 1; i > 0; i--) {
            glBindFramebuffer(GL_FRAMEBUFFER, mipFbo[i - 1]);
            glViewport(0, 0, mipW[i - 1], mipH[i - 1]);
            up.setUniform("texel", 1f / mipW[i], 1f / mipH[i]);
            glBindTexture(GL_TEXTURE_2D, mipTex[i]);
            drawQuad();
        }
        glDisable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        up.unbind();

        // ── Composite: scene + bloom, graded, onto the screen ─────────────────
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, w, h);
        composite.bind();
        composite.setUniform("scene", 0);
        composite.setUniform("bloom", 1);
        composite.setUniform("bloomStrength", bloomStrength);
        composite.setUniform("flash", flash);
        composite.setUniform("vignette", vignette);
        composite.setUniform("time", time);
        glActiveTexture(GL_TEXTURE0 + 1);
        glBindTexture(GL_TEXTURE_2D, mipTex[0]);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, sceneTex);
        drawQuad();
        composite.unbind();

        glActiveTexture(GL_TEXTURE0 + 1);
        glBindTexture(GL_TEXTURE_2D, 0);
        glActiveTexture(GL_TEXTURE0);
        glEnable(GL_DEPTH_TEST);
    }

    public void cleanup() {
        for (int i = 0; i < LEVELS; i++) {
            if (mipFbo[i] != 0) { glDeleteFramebuffers(mipFbo[i]); glDeleteTextures(mipTex[i]); }
        }
        if (down != null) down.cleanup();
        if (up != null) up.cleanup();
        if (composite != null) composite.cleanup();
    }
}
