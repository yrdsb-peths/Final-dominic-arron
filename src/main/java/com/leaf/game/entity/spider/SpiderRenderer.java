package com.leaf.game.entity.spider;

import com.leaf.game.render.Shader;
import com.leaf.game.render.AssetManager;
import com.leaf.game.render.ModelMesh;
import com.leaf.game.render.Texture;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import java.util.List;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;

public class SpiderRenderer {

    private static Shader spiderShader = null;

    public static void render(SpiderEnemy spider, Shader fallbackShader, Matrix4f projection, Matrix4f view) {
        if (spiderShader == null) {
            spiderShader = new Shader(
                    "src/main/resources/shaders/model_vertex.glsl",
                    "src/main/resources/shaders/model_fragment.glsl");
        }

        SpiderBody body = spider.getBody();
        if (body == null) return;
        Matrix4f pv = new Matrix4f(projection).mul(view);

        spiderShader.bind();
        spiderShader.setUniform("lightDir", new Vector3f(0.6f, 1f, 0.4f).normalize());
        spiderShader.setUniform("ambient", 0.45f);
        com.leaf.game.core.SceneLighting.apply(spiderShader);
        spiderShader.setUniform("tintColor", new Vector3f(0f, 0f, 0f));
        spiderShader.setUniform("tintAmt", 0f);
        spiderShader.setUniform("glow", 1.0f);
        spiderShader.setUniform("cutActive", 0);
        spiderShader.setUniform("clipPose", new Matrix4f());
        spiderShader.setUniform("tex", 0);

        // Keep culling disabled so mirrored right-side legs don't vanish
        glDisable(GL_CULL_FACE);

        // 1. Render Torso Structure
        Matrix4f torsoTransform = new Matrix4f()
                .translate(body.position)
                .rotate(body.orientation)
                .scale(body.bodyPlan.scale);
        renderModel(spiderShader, pv, body.bodyPlan.bodyModel, torsoTransform);

        // 2. Render Leg Segments
        Quaternionf pivot = body.gait().legChainPivotMode.get(body);
        for (int legIdx = 0; legIdx < body.legs.size(); legIdx++) {
            Leg leg = body.legs.get(legIdx);
            KinematicChain chain = leg.chain;
            List<Quaternionf> rotations = chain.getRotations(pivot);

            boolean isRightLeg = leg.legPlan.attachmentPosition.x < 0f;
            float mirrorX = isRightLeg ? -1.0f : 1.0f;

            for (int i = 0; i < chain.segments.size(); i++) {
                SegmentPlan segmentPlan = body.bodyPlan.legs.get(legIdx).segments.get(i);
                Vector3f startPos = (i == 0) ? chain.root : chain.segments.get(i - 1).position;
                Quaternionf rot = rotations.get(i);

                Matrix4f segmentTransform = new Matrix4f()
                        .translate(startPos)
                        .rotate(rot)
                        .scale(mirrorX * body.bodyPlan.scale, body.bodyPlan.scale, segmentPlan.length);

                renderModel(spiderShader, pv, segmentPlan.model, segmentTransform);
            }
        }

        spiderShader.unbind();

        // ── FIX FOR THE SHADER BREAKING BUG ──
        // Restore the terrain shader so the rest of the game renders properly!
        fallbackShader.bind();
    }

    private static void renderModel(Shader shader, Matrix4f pv, DisplayModel model, Matrix4f transformation) {
        for (BlockDisplayModelPiece piece : model.pieces) {

            // ── MATHEMATICALLY PERFECT ALIGNMENT ──
            // piece.transform from Minecraft expects a cube drawn from 0.0 to 1.0.
            // Our default engine cube draws from -0.5 to 0.5.
            // By pushing our cube +0.5 at the end, it maps perfectly into Minecraft's matrix.
            // This centers BOTH the body AND the legs seamlessly on their physical IK joints!
            Matrix4f pieceTransform = new Matrix4f(transformation)
                    .mul(piece.transform)
                    .translate(0.5f, 0.5f, 0.5f);

            Matrix4f mvp = new Matrix4f(pv).mul(pieceTransform);
            Matrix4f normalMat = new Matrix4f(pieceTransform).invert().transpose();

            shader.setUniform("mvp", mvp);
            shader.setUniform("normalMat", normalMat);

            if (piece.tags.contains("eye")) {
                shader.setUniform("tintColor", new Vector3f(0.0f, 2.5f, 2.5f));
                shader.setUniform("tintAmt", 1.0f);
                shader.setUniform("glow", 2.0f);
            } else if (piece.tags.contains("blinking_lights")) {
                shader.setUniform("tintColor", new Vector3f(0.4f, 2.0f, 0.6f));
                shader.setUniform("tintAmt", 1.0f);
                shader.setUniform("glow", 1.5f);
            } else {
                shader.setUniform("tintAmt", 0f);
                shader.setUniform("glow", 1.0f);
            }

            String texName = piece.blockName;
            if (texName.equals("cyan_shulker_box")) texName = "shulker_cyan";
            if (texName.equals("gray_shulker_box")) texName = "shulker_gray";
            if (texName.equals("black_shulker_box")) texName = "shulker_black";
            if (texName.equals("polished_deepslate_slab")) texName = "polished_deepslate";

            shader.setUniform("useTexture", 1);
            Texture tex = AssetManager.get().getTexture(texName);
            if (tex != null) {
                glActiveTexture(GL_TEXTURE0);
                tex.bind();
            } else {
                shader.setUniform("useTexture", 0);
            }

            ModelMesh mesh = AssetManager.get().getModel(piece.blockName);
            if (mesh != null) mesh.render();
        }
    }

    public static void cleanup() {
        if (spiderShader != null) {
            spiderShader.cleanup();
            spiderShader = null;
        }
    }
}