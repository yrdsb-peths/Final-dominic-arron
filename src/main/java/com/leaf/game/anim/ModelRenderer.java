package com.leaf.game.anim;

import com.leaf.game.render.Mesh;
import com.leaf.game.render.Shader;
import com.leaf.game.render.Texture;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;   // glActiveTexture, GL_TEXTURE0

/**
 * Renders a posed AnimModel into whatever framebuffer is currently bound.
 *
 * Call order:
 *   ModelRenderer.init()               — once, after GL context exists
 *   ModelRenderer.render(model, pose, world, view, projection)   — each frame
 *   ModelRenderer.cleanup()            — on shutdown
 *
 * The renderer keeps a Mesh cache keyed by (modelName + partId).
 * Meshes are regenerated if the PartDef's colour changes.
 */
public class ModelRenderer {

    private static Shader shader;

    // Cache: "modelName:partId" -> Mesh
    private static final Map<String, Mesh> meshCache = new HashMap<>();

    // Cache: classpath texture path -> Texture (NONE sentinel marks a failed load)
    private static final Map<String, Texture> textureCache = new HashMap<>();
    private static final Texture NONE = null; // readability for "tried, missing"
    private static final java.util.Set<String> texMisses = new java.util.HashSet<>();

    public static void init() {
        shader = new Shader(
                "src/main/resources/shaders/model_vertex.glsl",
                "src/main/resources/shaders/model_fragment.glsl");
    }

    // ── Radar override (set by Window during the radar sweep) ──────────────────
    private static final Vector3f tintColor = new Vector3f(0.1f, 1f, 0.35f);
    private static float   tintAmt  = 0f;     // 0 = normal model, 1 = full radar tint
    private static float   glow     = 1f;     // output brightness (>1 = searing ping)
    private static boolean depthOn  = true;   // false = draw through terrain (detection)

    /** Configure the radar look for subsequent render() calls. amt=0,glow=1,depth=true = normal. */
    public static void setOverride(float r, float g, float b, float amt, float gl, boolean depth) {
        tintColor.set(r, g, b); tintAmt = amt; glow = gl; depthOn = depth;
    }
    public static void clearOverride() { tintAmt = 0f; glow = 1f; depthOn = true; }

    /**
     * Render a posed model.
     *
     * @param model      the AnimModel definition
     * @param pose       part-id → local-space transform matrix (from AnimPlayer.getPose())
     * @param worldMat   where the model sits in the game world
     * @param view       camera view matrix
     * @param projection camera projection matrix
     */
    public static void render(AnimModel model, Map<String, Matrix4f> pose,
                              Matrix4f worldMat, Matrix4f view, Matrix4f projection) {
        if (shader == null) return;

        shader.bind();
        shader.setUniform("lightDir", new Vector3f(0.6f, 1f, 0.4f).normalize());
        shader.setUniform("ambient", 0.35f);
        com.leaf.game.core.SceneLighting.apply(shader);
        // Radar override (defaults are a no-op: tintAmt 0, glow 1, depth on).
        shader.setUniform("tintColor", tintColor);
        shader.setUniform("tintAmt", tintAmt);
        shader.setUniform("glow", glow);
        // Slice override OFF for normal rendering (renderSliced turns it on).
        shader.setUniform("cutActive", 0);
        shader.setUniform("sliceAlpha", 1f);
        shader.setUniform("clipPose", new Matrix4f());

        if (depthOn) glEnable(GL_DEPTH_TEST); else glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE); // <── FORCE DISABLE CULLING FOR ANIMATED MODELS HERE!

        shader.setUniform("tex", 0);        // sampler reads texture unit 0
        shader.setUniform("useTexture", 0); // default: solid vertex colour

        for (PartDef part : model.parts) {
            Mesh mesh = getMesh(model, part);
            if (mesh == null) continue; // transform-only bone (no geometry)

            // MVP = projection × view × world × partPose
            Matrix4f partPose = pose.getOrDefault(part.id, new Matrix4f());
            Matrix4f mvp = new Matrix4f(projection).mul(view).mul(worldMat).mul(partPose);
            Matrix4f normalMat = new Matrix4f(worldMat).mul(partPose).invert().transpose();

            // Bind this part's texture (if any) and toggle the sampler path.
            Texture tx = (part.geo != null && part.tex != null) ? getTexture(part.tex) : NONE;
            if (tx != null) {
                glActiveTexture(GL_TEXTURE0);
                tx.bind();
                shader.setUniform("useTexture", 1);
            } else {
                shader.setUniform("useTexture", 0);
            }

            shader.setUniform("mvp", mvp);
            shader.setUniform("normalMat", normalMat);
            mesh.render();
        }

        shader.setUniform("useTexture", 0);
        shader.unbind();
    }

    /**
     * Render ONE HALF of a sliced corpse: the model is clipped along a body-local
     * plane (cutNormal·p = cutOffset), keeping the {@code cutSide} half, and the cut
     * surface glows molten gold. {@code bodyFly} is applied in body space (before the
     * world matrix) so the half can separate + tumble while the clip stays put.
     * Call twice (cutSide = +1 and −1) for the two halves. Caller manages blending.
     */
    public static void renderSliced(AnimModel model, Map<String, Matrix4f> pose,
                                    Matrix4f worldMat, Matrix4f bodyFly,
                                    Matrix4f view, Matrix4f projection,
                                    Vector3f cutCenter, Vector3f cutSign,
                                    float sliceAlpha) {
        if (shader == null) return;
        shader.bind();
        shader.setUniform("lightDir", new Vector3f(0.6f, 1f, 0.4f).normalize());
        shader.setUniform("ambient", 0.35f);
        com.leaf.game.core.SceneLighting.apply(shader);
        shader.setUniform("tintColor", tintColor);
        shader.setUniform("tintAmt", 0f);
        shader.setUniform("glow", 1f);
        shader.setUniform("cutActive", 1);
        shader.setUniform("cutCenter", cutCenter);
        shader.setUniform("cutSign", cutSign);
        shader.setUniform("sliceAlpha", sliceAlpha);

        glEnable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        shader.setUniform("tex", 0);
        shader.setUniform("useTexture", 0);

        for (PartDef part : model.parts) {
            Mesh mesh = getMesh(model, part);
            if (mesh == null) continue;
            Matrix4f partPose = pose.getOrDefault(part.id, new Matrix4f());
            // gl_Position uses bodyFly (the half flies apart); clip uses partPose only.
            Matrix4f mvp = new Matrix4f(projection).mul(view).mul(worldMat).mul(bodyFly).mul(partPose);
            Matrix4f normalMat = new Matrix4f(worldMat).mul(bodyFly).mul(partPose).invert().transpose();

            Texture tx = (part.geo != null && part.tex != null) ? getTexture(part.tex) : NONE;
            if (tx != null) {
                glActiveTexture(GL_TEXTURE0);
                tx.bind();
                shader.setUniform("useTexture", 1);
            } else {
                shader.setUniform("useTexture", 0);
            }

            shader.setUniform("mvp", mvp);
            shader.setUniform("normalMat", normalMat);
            shader.setUniform("clipPose", partPose);   // body-frame clip
            mesh.render();
        }

        shader.setUniform("cutActive", 0);
        shader.setUniform("useTexture", 0);
        shader.unbind();
    }

    private static Mesh getMesh(AnimModel model, PartDef part) {
        // Transform-only bone: no geometry to draw.
        if (part.geo == null && part.w <= 0 && part.h <= 0 && part.d <= 0) return null;
        String key = model.name + ":" + part.id;
        return meshCache.computeIfAbsent(key,
                k -> part.geo != null ? buildTexturedMesh(part) : BoxMesh.build(part));
    }

    /** Build a UV-aware Mesh from a part's baked triangle list (15 floats / triangle). */
    private static Mesh buildTexturedMesh(PartDef part) {
        float[] geo = part.geo;
        int triCount = geo.length / 15;
        int vCount   = triCount * 3;
        float[] verts = new float[vCount * 12]; // pos3 + colour4 + normal3 + uv2
        int[]   idx   = new int[vCount];
        int vi = 0, ii = 0;

        // Untextured geo groups fall back to the part's solid colour; textured → white.
        float cr = part.tex != null ? 1f : part.cr;
        float cg = part.tex != null ? 1f : part.cg;
        float cb = part.tex != null ? 1f : part.cb;

        for (int t = 0; t < triCount; t++) {
            int o = t * 15;
            float ax = geo[o],    ay = geo[o+1],  az = geo[o+2];
            float bx = geo[o+5],  by = geo[o+6],  bz = geo[o+7];
            float cx = geo[o+10], cy = geo[o+11], cz = geo[o+12];
            // Flat face normal = normalize((b-a) × (c-a)).
            float ux = bx-ax, uy = by-ay, uz = bz-az;
            float wx = cx-ax, wy = cy-ay, wz = cz-az;
            float nx = uy*wz - uz*wy, ny = uz*wx - ux*wz, nz = ux*wy - uy*wx;
            float len = (float) Math.sqrt(nx*nx + ny*ny + nz*nz);
            if (len < 1e-6f) len = 1f;
            nx /= len; ny /= len; nz /= len;

            for (int k = 0; k < 3; k++) {
                int g = o + k * 5;
                verts[vi++] = geo[g];   verts[vi++] = geo[g+1]; verts[vi++] = geo[g+2]; // position
                verts[vi++] = cr; verts[vi++] = cg; verts[vi++] = cb; verts[vi++] = 1f; // colour
                verts[vi++] = nx; verts[vi++] = ny; verts[vi++] = nz;                    // normal
                verts[vi++] = geo[g+3]; verts[vi++] = geo[g+4];                          // uv
                idx[ii] = ii; ii++;
            }
        }
        return new Mesh(verts, idx, true);
    }

    /** Lazily load+cache a model texture from the classpath. Returns null if missing.
     *  Model textures are pixel-art (32–64 px) so we override to GL_NEAREST to keep
     *  them crisp — Texture.load() defaults to GL_LINEAR which blurs them. */
    private static Texture getTexture(String classpathPath) {
        Texture t = textureCache.get(classpathPath);
        if (t != null) return t;
        if (texMisses.contains(classpathPath)) return null;
        try {
            t = Texture.load(classpathPath);
            // Override the bilinear filtering that Texture.load() sets by default.
            // These are tiny pixel-art textures — GL_LINEAR blurs them noticeably.
            glBindTexture(GL_TEXTURE_2D, t.getId());
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glBindTexture(GL_TEXTURE_2D, 0);
            textureCache.put(classpathPath, t);
            return t;
        } catch (Exception e) {
            System.err.println("[ModelRenderer] Texture not found: " + classpathPath + " (" + e.getMessage() + ")");
            texMisses.add(classpathPath);
            return null;
        }
    }

    /** Call when a part's colour or geometry changes (e.g. after editor edit). */
    public static void invalidateMesh(AnimModel model, String partId) {
        String key = model.name + ":" + partId;
        Mesh old = meshCache.remove(key);
        if (old != null) old.cleanup();
    }

    /** Invalidate all meshes for this model. */
    public static void invalidateModel(AnimModel model) {
        for (PartDef p : model.parts) invalidateMesh(model, p.id);
    }

    public static void cleanup() {
        for (Mesh m : meshCache.values()) m.cleanup();
        meshCache.clear();
        for (Texture t : textureCache.values()) if (t != null) t.cleanup();
        textureCache.clear();
        texMisses.clear();
        if (shader != null) { shader.cleanup(); shader = null; }
    }

    public static boolean isInitialised() { return shader != null; }
}
