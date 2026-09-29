package com.leaf.game.entity;

import com.leaf.game.core.Window;
import com.leaf.game.entity.spider.*;
import com.leaf.game.render.Shader;
import com.leaf.game.render.AssetManager;
import com.leaf.game.render.ModelMesh;
import com.leaf.game.render.Texture;
import com.leaf.game.world.Block;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;

public class TreantRenderer {

    // ── Own model shader, matching SpiderRenderer exactly ────────────────────────
    // The terrain/entity shader does not support normalMat lighting, which is why
    // everything was black.  This shader is identical to what SpiderRenderer uses.
    private static Shader treantShader = null;

    private static Shader getShader() {
        if (treantShader == null) {
            treantShader = new Shader(
                    "src/main/resources/shaders/model_vertex.glsl",
                    "src/main/resources/shaders/model_fragment.glsl");
        }
        return treantShader;
    }

    public static void cleanup() {
        if (treantShader != null) {
            treantShader.cleanup();
            treantShader = null;
        }
    }

    public static void render(TreantEnemy treant, Shader fallbackShader,
                              Matrix4f projection, Matrix4f view) {
        SpiderBody body = treant.getBody();
        if (body == null) return;

        Shader shader = getShader();
        shader.bind();

        Matrix4f pv = new Matrix4f(projection).mul(view);

        // Standard model-shader uniforms (same defaults as SpiderRenderer)
        shader.setUniform("lightDir", new Vector3f(0.6f, 1f, 0.4f).normalize());
        shader.setUniform("ambient",  0.45f);
        com.leaf.game.core.SceneLighting.apply(shader);
        shader.setUniform("tintColor", new Vector3f(0f, 0f, 0f));
        shader.setUniform("tintAmt",  0f);
        shader.setUniform("glow",     1.0f);
        shader.setUniform("cutActive", 0);
        shader.setUniform("clipPose", new Matrix4f());
        shader.setUniform("tex",      0);

        // Hit flash
        float flashF = treant.hitFlashTimer > 0f ? (treant.hitFlashTimer / 0.18f) : 0f;
        if (treant.hitFlashTimer > 0f) {
            shader.setUniform("tintColor", new Vector3f(1.0f, 0.2f, 0.2f));
            shader.setUniform("tintAmt",  0.6f * flashF);
        }
        if (!treant.alive && flashF < 0.01f) {
            shader.unbind();
            fallbackShader.bind();
            return; // Fully faded out
        }

        glDisable(GL_CULL_FACE);

        float sleep = treant.getSleepTransition();

        // ── Orientation: slerp toward pure-yaw when sleeping ──────────────────────
        // Never decompose to euler and scale — that blows up at ±90° pitch (gimbal).
        Vector3f currentEuler = body.orientation.getEulerAnglesYXZ(new Vector3f());
        Quaternionf uprightOrient = new Quaternionf().rotationYXZ(currentEuler.y, 0f, 0f);
        Quaternionf renderOrient  = new Quaternionf(body.orientation).slerp(uprightOrient, sleep);

        // ── Base matrix: world-space Y drop BEFORE rotation ──────────────────────
        // Doing it after .rotate() would apply the offset in local space, tilting
        // the trunk sideways whenever the body leans.
        Matrix4f baseMat = new Matrix4f()
                .translate(body.position.x, body.position.y - 0.2f, body.position.z)
                .rotate(renderOrient);

        // ── Render helper ─────────────────────────────────────────────────────────
        // Mirrors SpiderRenderer.renderModel exactly:
        //   pieceTransform = segmentTransform * piece.transform * translate(0.5,0.5,0.5)
        // The translate(0.5) maps our engine's centred cube (-0.5..+0.5) into the
        // Minecraft convention the piece.transform was authored for (0..1).

        // ── Trunk (4 oak_log blocks stacked) ─────────────────────────────────────
        final int trunkH = TreantEnemy.TRUNK_HEIGHT;
        for (int y = 0; y < trunkH; y++) {
            // One unit cube per block, centred at (0, y+0.5, 0) in baseMat space.
            // scale(1,1,1) — trunk blocks are exactly 1×1×1 world units.
            Matrix4f segTransform = new Matrix4f(baseMat)
                    .translate(0f, y + 0.5f, 0f)
                    .scale(1f, 1f, 1f);
            renderBlock(shader, pv, "oak_log", segTransform);
        }

        // ── Roots (IK chains) ─────────────────────────────────────────────────────
        // Follows SpiderRenderer's leg loop exactly.
        // segmentPlan.length is used directly as the Z scale because ModelMesh is a
        // unit cube (0..1) and bodyPlan.scale is 1.0, so no extra conversion needed.
        Quaternionf pivot = body.gait().legChainPivotMode.get(body);
        for (int legIdx = 0; legIdx < body.legs.size(); legIdx++) {
            Leg leg = body.legs.get(legIdx);
            KinematicChain chain = leg.chain;
            if (chain.segments.isEmpty()) continue;

            List<Quaternionf> rotations = chain.getRotations(pivot);

            for (int i = 0; i < chain.segments.size(); i++) {
                SegmentPlan segPlan  = body.bodyPlan.legs.get(legIdx).segments.get(i);
                Vector3f    startPos = (i == 0) ? chain.root : chain.segments.get(i - 1).position;
                Quaternionf rot      = rotations.get(i);

                // Roots taper: thick at base, thin at tip.
                // XY scale shrinks toward the tip; Z = segment length (world units).
                float girth = 0.55f - i * 0.18f;

                Matrix4f segTransform = new Matrix4f()
                        .translate(startPos)
                        .rotate(rot)
                        .scale(girth, girth, segPlan.length);

                // Render each piece in the segment's DisplayModel
                for (BlockDisplayModelPiece piece : segPlan.model.pieces) {
                    Matrix4f pieceTransform = new Matrix4f(segTransform)
                            .mul(piece.transform)
                            .translate(0.5f, 0.5f, 0.5f);

                    Matrix4f mvp       = new Matrix4f(pv).mul(pieceTransform);
                    Matrix4f normalMat = new Matrix4f(pieceTransform).invert().transpose();

                    shader.setUniform("mvp",       mvp);
                    shader.setUniform("normalMat", normalMat);
                    shader.setUniform("tintAmt",   0f);
                    shader.setUniform("glow",      1.0f);

                    renderMesh(shader, piece.blockName);
                }
            }
        }

        // ── Canopy (oak_leaves) ───────────────────────────────────────────────────
        // Matches buildOakTree layout: dy 2..5, radius 2 except top layer (radius 1),
        // corners clipped on top/bottom layers, centre column hollow through trunk.
        final int leafBottom = trunkH - 2; // 2
        final int leafTop    = trunkH + 1; // 5
        final int eyeRow     = trunkH - 2; // 2

        for (int dy = leafBottom; dy <= leafTop; dy++) {
            int rad = (dy == leafTop) ? 1 : 2;
            for (int dx = -rad; dx <= rad; dx++) {
                for (int dz = -rad; dz <= rad; dz++) {
                    // Clip corners on top and bottom leaf layers
                    if (Math.abs(dx) == rad && Math.abs(dz) == rad) {
                        if (dy == leafTop || dy == leafBottom) continue;
                    }
                    // Hollow centre column through trunk
                    if (dx == 0 && dz == 0 && dy <= trunkH) continue;
                    // Eye gap: cut two cells on +Z face of eye row when awake
                    if (sleep < 0.99f && dy == eyeRow && dz == rad && Math.abs(dx) <= 1) {
                        if (sleep < 0.5f) continue;
                    }

                    Matrix4f segTransform = new Matrix4f(baseMat)
                            .translate(dx, dy + 0.5f, dz)
                            .scale(1f, 1f, 1f);
                    renderBlock(shader, pv, "oak_leaves", segTransform);
                }
            }
        }

        // ── Glowing Eyes ──────────────────────────────────────────────────────────
        if (sleep < 0.9f) {
            float eyeBrightness = 2.0f * (1f - sleep);
            shader.setUniform("tintColor", new Vector3f(eyeBrightness, eyeBrightness * 0.05f, eyeBrightness * 0.05f));
            shader.setUniform("tintAmt",   1.0f);
            shader.setUniform("glow",      eyeBrightness);

            float eyeY = eyeRow + 0.5f;
            renderBlock(shader, pv, "glowstone",
                    new Matrix4f(baseMat).translate(-0.35f, eyeY, 2.05f).scale(0.2f, 0.2f, 0.2f));
            renderBlock(shader, pv, "glowstone",
                    new Matrix4f(baseMat).translate( 0.35f, eyeY, 2.05f).scale(0.2f, 0.2f, 0.2f));

            shader.setUniform("tintAmt", 0f);
            shader.setUniform("glow",    1.0f);
        }

        shader.unbind();

        // Restore terrain shader so the rest of the game renders correctly
        fallbackShader.bind();
    }

    // ── Render a single named block as a 1×1×1 cube ──────────────────────────────
    private static void renderBlock(Shader shader, Matrix4f pv,
                                    String blockName, Matrix4f segTransform) {
        Matrix4f pieceTransform = new Matrix4f(segTransform)
                .translate(0.5f, 0.5f, 0.5f);

        Matrix4f mvp       = new Matrix4f(pv).mul(pieceTransform);
        Matrix4f normalMat = new Matrix4f(pieceTransform).invert().transpose();

        shader.setUniform("mvp",       mvp);
        shader.setUniform("normalMat", normalMat);

        renderMesh(shader, blockName);

        // Reset tint so colours don't bleed into the next piece
        shader.setUniform("tintAmt", 0f);
    }

    // ── Fetch ModelMesh and bind texture, matching SpiderRenderer.renderModel ─────
    // When no texture exists the default cube has white vertices, so we apply a
    // block-appropriate tint (90%) to give the treant correct colours while still
    // letting the remaining 10% vertex-colour carry the directional-light gradient.
    private static void renderMesh(Shader shader, String blockName) {
        shader.setUniform("useTexture", 1);
        Texture tex = AssetManager.get().getTexture(blockName);
        if (tex != null) {
            glActiveTexture(GL_TEXTURE0);
            tex.bind();
        } else {
            shader.setUniform("useTexture", 0);
            shader.setUniform("tintColor", blockFallbackColor(blockName));
            shader.setUniform("tintAmt",   0.88f);
        }

        ModelMesh mesh = AssetManager.get().getModel(blockName);
        if (mesh != null) mesh.render();
    }

    private static Vector3f blockFallbackColor(String name) {
        return switch (name) {
            case "oak_log"    -> new Vector3f(0.35f, 0.23f, 0.12f);
            case "oak_leaves" -> new Vector3f(0.18f, 0.48f, 0.15f);
            case "glowstone"  -> new Vector3f(1.0f,  0.90f, 0.50f);
            default           -> new Vector3f(0.65f, 0.65f, 0.65f);
        };
    }
}