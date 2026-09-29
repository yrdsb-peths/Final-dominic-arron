package com.leaf.game.core;

import com.leaf.game.render.Shader;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * SceneLighting — this frame's light, shared by every shader that draws into
 * the world. The world shader has always known the time of day; enemy / model
 * shaders used a fixed noon light, so creatures glowed at midnight. Window
 * fills this once per frame; model renderers call {@link #apply}.
 */
public final class SceneLighting {

    public static final Vector3f sunDir       = new Vector3f(0.6f, 1f, 0.4f).normalize();
    public static final Vector3f sunColor     = new Vector3f(1f);
    public static final Vector3f ambientColor = new Vector3f(0.6f, 0.7f, 0.9f);
    public static final Vector3f skyZenith    = new Vector3f(0.3f, 0.55f, 0.95f);
    public static final Vector3f skyHorizon   = new Vector3f(0.66f, 0.8f, 0.96f);
    public static final Vector3f camPos       = new Vector3f();
    public static float sunStrength = 0.8f, ambientStrength = 0.3f, sunsetFactor = 0f, fogEnd = 96f;
    public static final Matrix4f invViewProj  = new Matrix4f();
    public static final Matrix4f lightVP      = new Matrix4f();
    public static float screenW = 1, screenH = 1, shadowTexel = 0.07f;
    public static boolean shadowOn = false;

    private SceneLighting() {}

    /** Upload the shared lighting uniforms (names match model_fragment.glsl). */
    public static void apply(Shader s) {
        s.setUniform("uSunDir", sunDir);
        s.setUniform("uSunColor", sunColor);
        s.setUniform("uSunStrength", sunStrength);
        s.setUniform("uAmbientColor", ambientColor);
        s.setUniform("uAmbientStrength", ambientStrength);
        s.setUniform("uSkyZenith", skyZenith);
        s.setUniform("uSkyHorizon", skyHorizon);
        s.setUniform("uSunset", sunsetFactor);
        s.setUniform("uCamPos", camPos);
        s.setUniform("uFogEnd", fogEnd);
        s.setUniform("uInvViewProj", invViewProj);
        s.setUniform("uScreenSize", screenW, screenH);
        s.setUniform("uLightVP", lightVP);
        s.setUniform("uShadowTexel", shadowTexel);
        s.setUniform("uShadowOn", shadowOn ? 1 : 0);
        s.setUniform("uShadowMap", com.leaf.game.render.ShadowMap.TEXTURE_UNIT);
    }
}
