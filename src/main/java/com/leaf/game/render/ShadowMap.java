package com.leaf.game.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL13.GL_CLAMP_TO_BORDER;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT24;
import static org.lwjgl.opengl.GL14.GL_TEXTURE_COMPARE_FUNC;
import static org.lwjgl.opengl.GL14.GL_TEXTURE_COMPARE_MODE;
import static org.lwjgl.opengl.GL30.*;

/**
 * ShadowMap — directional sun/moon shadows for the voxel world.
 *
 * <p>Each frame the terrain is drawn once more, depth-only, from the light's point
 * of view into a square depth texture that follows the player. The world shader
 * then asks "is this pixel behind something, as seen from the sun?" and darkens
 * the direct-light term if so. Trees, cliffs and overhangs finally cast shadows.
 *
 * <p>Stability: the light-space origin is snapped to whole shadow texels, so the
 * shadow edges don't crawl/shimmer as the camera moves.
 *
 * <p>Usage from the render loop:
 * <pre>
 *   if (shadows.begin(lightDir, playerPos)) {
 *       ... mesh.render() for every chunk in range ...
 *       shadows.end(fbWidth, fbHeight);
 *   }
 *   shadows.bindForSampling(); worldShader.setUniform("lightVP", shadows.lightVP());
 * </pre>
 */
public final class ShadowMap {

    /** Texture unit the world shader samples the shadow map from. Unit 0 is the block atlas. */
    public static final int TEXTURE_UNIT = 3;

    private final int   size;        // depth texture resolution (square)
    private final float radius;      // half-width of the covered area, in blocks
    private final float depthRange;  // how far along the light ray casters are captured

    private int fbo, depthTex;
    private Shader depthShader;
    private final Matrix4f lightView = new Matrix4f();
    private final Matrix4f lightProj = new Matrix4f();
    private final Matrix4f lightVP   = new Matrix4f();
    private final int[] prevViewport = new int[4];

    public ShadowMap(int size, float radius, float depthRange) {
        this.size = size;
        this.radius = radius;
        this.depthRange = depthRange;
    }

    public void init() {
        depthTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, depthTex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, size, size, 0,
                GL_DEPTH_COMPONENT, GL_FLOAT, (java.nio.ByteBuffer) null);
        // Linear + compare mode = free 2×2 hardware PCF via sampler2DShadow.
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_BORDER);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_BORDER);
        glTexParameterfv(GL_TEXTURE_2D, GL_TEXTURE_BORDER_COLOR, new float[]{1f, 1f, 1f, 1f}); // outside = lit
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_REF_TO_TEXTURE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_FUNC, GL_LEQUAL);
        glBindTexture(GL_TEXTURE_2D, 0);

        fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depthTex, 0);
        glDrawBuffer(GL_NONE);
        glReadBuffer(GL_NONE);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            System.err.println("[Shadows] Shadow framebuffer incomplete — shadows disabled.");
            glDeleteFramebuffers(fbo);
            fbo = 0;
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);

        depthShader = new Shader("src/main/resources/shaders/shadow_vertex.glsl",
                                 "src/main/resources/shaders/shadow_fragment.glsl");
        System.out.println("[Shadows] " + size + "² shadow map, radius " + radius + " blocks.");
    }

    public boolean isReady() { return fbo != 0; }

    /**
     * Point the light camera at {@code focus} and start the depth pass.
     * @param toLight unit vector pointing TOWARD the sun/moon
     * @return false if shadows are unavailable (caller skips the pass)
     */
    public boolean begin(Vector3f toLight, Vector3f focus) {
        if (fbo == 0) return false;

        // Rotation-only light view; pick an up vector that isn't parallel to the light.
        Vector3f dir = new Vector3f(toLight).normalize();
        Vector3f up  = Math.abs(dir.y) > 0.95f ? new Vector3f(0, 0, 1) : new Vector3f(0, 1, 0);
        lightView.setLookAt(dir.x, dir.y, dir.z, 0, 0, 0, up.x, up.y, up.z);

        // Snap the focus point to whole texels in light space → no edge shimmer.
        Vector4f c = new Vector4f(focus, 1f).mul(lightView);
        float texel = (2f * radius) / size;
        float cx = (float) Math.floor(c.x / texel) * texel;
        float cy = (float) Math.floor(c.y / texel) * texel;
        float cz = c.z;
        lightProj.setOrtho(cx - radius, cx + radius, cy - radius, cy + radius,
                -cz - depthRange, -cz + depthRange);
        lightProj.mul(lightView, lightVP);

        glGetIntegerv(GL_VIEWPORT, prevViewport);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glViewport(0, 0, size, size);
        glClear(GL_DEPTH_BUFFER_BIT);
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(1.6f, 3.0f);                        // slope-scaled bias vs. acne

        depthShader.bind();
        depthShader.setUniform("lightVP", lightVP);
        return true;
    }

    /** Finish the depth pass and restore the caller's framebuffer + viewport. */
    public void end(int restoreFbo) {
        depthShader.unbind();
        glDisable(GL_POLYGON_OFFSET_FILL);
        glBindFramebuffer(GL_FRAMEBUFFER, restoreFbo);
        glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
    }

    /** Bind the depth texture on {@link #TEXTURE_UNIT} for the world shader. */
    public void bindForSampling() {
        glActiveTexture(GL_TEXTURE0 + TEXTURE_UNIT);
        glBindTexture(GL_TEXTURE_2D, depthTex);
        glActiveTexture(GL_TEXTURE0);
    }

    public Matrix4f lightVP()     { return lightVP; }
    public float    texelWorld()  { return (2f * radius) / size; }
    public float    radius()      { return radius; }

    public void cleanup() {
        if (fbo != 0) glDeleteFramebuffers(fbo);
        if (depthTex != 0) glDeleteTextures(depthTex);
        if (depthShader != null) depthShader.cleanup();
    }

    @SuppressWarnings("unused")
    private static final int UNUSED_CLAMP = GL_CLAMP_TO_EDGE; // keep import tidy across LWJGL versions
}
