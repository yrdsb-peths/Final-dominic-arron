#version 330 core

in  vec4 vColor;
in  vec3 vNormal;
in  vec2 vUV;
in  vec3 vClipPos;
out vec4 FragColor;

uniform vec3  lightDir;   // normalized world-space light direction
uniform float ambient;    // 0..1

// ── SLICE: chop the model into one OCTANT (Deprivation Domain "大卸八块") ───────
// cutActive=1 keeps only the body-local octant selected by cutSign (each comp ±1)
// relative to cutCenter — 3 axis planes → 8 pieces. Freshly-exposed inner faces
// sear molten white-gold; sliceAlpha fades the piece as it dissolves.
uniform int   cutActive;
uniform vec3  cutCenter;   // body-frame centre the 3 cut planes pass through
uniform vec3  cutSign;     // (±1,±1,±1) → which of the 8 octants this draw keeps
uniform float sliceAlpha;

// Texture sampling: set useTexture = 1 and bind a texture to unit 0 to draw the
// Blockbench texture; useTexture = 0 (default) keeps the solid vertex-colour path.
uniform sampler2D tex;
uniform int       useTexture;

// Radar override: tint the model toward a colour and boost brightness so it can
// glow far past white (the sweep "ping"). Defaults (tintAmt 0, glow 1) = normal.
uniform vec3  tintColor;
uniform float tintAmt;
uniform float glow;

// ── Shared scene lighting (core/SceneLighting.java) — same sun, sky, shadows
//    and haze as the terrain, so creatures sit IN the world at every hour. ────
uniform vec3  uSunDir, uSunColor, uAmbientColor, uSkyZenith, uSkyHorizon, uCamPos;
uniform float uSunStrength, uAmbientStrength, uSunset, uFogEnd, uShadowTexel;
uniform mat4  uInvViewProj, uLightVP;
uniform vec2  uScreenSize;
uniform int   uShadowOn;
uniform sampler2DShadow uShadowMap;

vec3 worldPos() {
    vec3 ndc = vec3(gl_FragCoord.xy / uScreenSize, gl_FragCoord.z) * 2.0 - 1.0;
    vec4 p = uInvViewProj * vec4(ndc, 1.0);
    return p.xyz / p.w;
}
float shadowAt(vec3 wp, vec3 N) {
    if (uShadowOn == 0) return 1.0;
    vec4 lp = uLightVP * vec4(wp + N * uShadowTexel * 2.0, 1.0);
    vec3 sc = lp.xyz / lp.w * 0.5 + 0.5;
    if (sc.z >= 1.0 || any(lessThan(sc.xy, vec2(0.0))) || any(greaterThan(sc.xy, vec2(1.0)))) return 1.0;
    vec2 ts = 1.0 / vec2(textureSize(uShadowMap, 0));
    float s = 0.0;
    for (int i = -1; i <= 1; i++)
        for (int j = -1; j <= 1; j++)
            s += texture(uShadowMap, vec3(sc.xy + vec2(i, j) * ts, sc.z - 0.0006));
    return s / 9.0;
}
vec3 filmicL(vec3 c) {
    c = max(c, 0.0);
    float L = dot(c, vec3(0.2126, 0.7152, 0.0722));
    vec3 byLum = c * ((1.0 - exp(-L * 1.8)) / max(L, 1e-4));
    vec3 perCh = 1.0 - exp(-c * 1.8);
    return mix(byLum, perCh, smoothstep(0.75, 1.25, max(byLum.r, max(byLum.g, byLum.b))));
}

void main() {
    vec3  N     = normalize(vNormal);
    vec3  WP    = worldPos();
    float diff  = max(dot(N, normalize(uSunDir)), 0.0);
    float skyW  = 0.5 + 0.5 * N.y;
    vec3  amb   = uAmbientStrength * mix(uAmbientColor, uSkyZenith * 1.1, 0.30) * mix(0.9, 1.4, skyW);
    vec3  bounce= uSunColor * uSunStrength * 0.30 * (1.0 - skyW);
    float sh    = diff > 0.0 ? shadowAt(WP, N) : 0.0;
    vec3  lightC = amb + bounce + uSunColor * uSunStrength * diff * sh;
    // Keep a readable floor so silhouettes never vanish at night.
    lightC = max(lightC, vec3(0.06, 0.07, 0.10));

    vec4 base = vColor;
    if (useTexture == 1) {
        base = texture(tex, vUV);
        if (base.a < 0.5) discard;   // cutout: don't let transparent texels occlude
    }
    vec3 rgb = filmicL(base.rgb * lightC);

    // Same aerial haze as the terrain.
    float dist = length(WP - uCamPos);
    vec3  ray  = normalize(WP - uCamPos);
    vec3  sky  = mix(uSkyHorizon, uSkyZenith, smoothstep(0.0, 0.55, ray.y));
    float haze = (1.0 - exp(-dist * 0.0085)) * 0.80;
    rgb = mix(rgb, sky, haze);
    rgb = mix(rgb, sky, smoothstep(uFogEnd * 0.70, uFogEnd, dist));
    rgb = mix(rgb, tintColor, tintAmt);   // recolour for the radar
    rgb *= glow;                          // ping brightness (can exceed 1 → searing)

    float outA = base.a;
    if (cutActive == 1) {
        vec3 rel = (vClipPos - cutCenter) * cutSign;        // >0 on the kept side of each plane
        if (rel.x < 0.0 || rel.y < 0.0 || rel.z < 0.0) discard;  // outside this octant
        // Molten cut surfaces: glow where this fragment hugs any of the 3 cut planes.
        vec3 dd  = abs(vClipPos - cutCenter);
        float face = max(max(1.0 - smoothstep(0.0, 1.2, dd.x),
                             1.0 - smoothstep(0.0, 1.2, dd.y)),
                             1.0 - smoothstep(0.0, 1.2, dd.z));
        rgb = mix(rgb, vec3(3.6, 2.5, 0.85), face * sliceAlpha);
        outA *= sliceAlpha;
    }
    FragColor = vec4(rgb, outA);
}
