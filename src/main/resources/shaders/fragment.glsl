#version 330 core
in vec4  vertexColor;
in vec3  vertexNormal;
in float vWorldY;        // world-space Y of this fragment (from vertex shader)
in vec2  vertexUV;       // texture coordinates (zero when not a ModelMesh)

uniform vec3  sunDirection;
uniform float sunStrength;
uniform float ambientStrength;
uniform int   isUnderwater;
uniform float cameraY;        // camera eye world-Y, set each frame from Java

// ── TEXTURE SAMPLING ──────────────────────────────────────────────────────────
// Set useTexture = 1 and bind a texture to unit 0 to enable texture sampling.
// Set useTexture = 0 (default) to use pure vertex colour — all existing Mesh
// rendering is unaffected because this uniform defaults to 0.
uniform sampler2D texSampler;
uniform int       useTexture;   // 0 = vertex colour only, 1 = texture × vertex colour

// ── TIME DILATION VIGNETTE ────────────────────────────────────────────────────
uniform float timeVignetteStrength;
uniform vec3  timeVignetteColor;

// ── ABILITY OVERLAY VIGNETTE ──────────────────────────────────────────────────
// Used by dash (cyan), cannonball (orange), rewind (blue), blink (white flash).
// Set by Window.java from player.abilities.getOverlayStrength/Color().
uniform float overlayVignetteStrength;
uniform vec3  overlayVignetteColor;

// ── SNOW BIOME ATMOSPHERE ─────────────────────────────────────────────────────
uniform float snowAtmosphereStrength;  // 0=none; driven by altitude in Window.java

// ── SCREEN DESATURATION (ScreenEffectManager) ─────────────────────────────────
uniform float desaturate;   // 0=colour, 1=greyscale

// ── GHOST TRAIL TRANSPARENCY ──────────────────────────────────────────────────
// Multiplied into vertexColor.a when rendering ghost/trail meshes.
// Window.java sets this per-draw-call (0.05–0.40) then resets to 1.0.
uniform float alphaMultiplier;

// ── PORTAL RENDERING ─────────────────────────────────────────────────────────
// When portalMode == 1 the fragment samples texSampler using screen-space UVs
// (gl_FragCoord / viewportSize) and returns immediately.  This displays the
// FBO colour texture on a portal quad with perfect alignment.
// portalMode == 0 (default) → normal lit rendering, no change.
uniform int   portalMode;
uniform vec2  viewportSize;   // FBO dimensions (set to PORTAL_FBO_W/H)

out vec4 FragColor;

void main() {
    // ── FBO PORTAL PASSTHROUGH ────────────────────────────────────────────────
    if (portalMode == 1) {
        vec2 screenUV = gl_FragCoord.xy / viewportSize;
        FragColor = texture(texSampler, screenUV);
        return;
    }

    float diffuse = max(0.0, dot(normalize(vertexNormal), normalize(sunDirection)));
    float light   = ambientStrength + sunStrength * diffuse;

    // ── Base colour: vertex colour or texture × vertex colour ─────────────────
    vec4 baseColor;
    if (useTexture == 1) {
        vec4 texColor = texture(texSampler, vertexUV);
        baseColor = texColor * vertexColor;
    } else {
        baseColor = vertexColor;
    }

    vec3 color          = baseColor.rgb * light;
    vec3 gammaCorrected = pow(clamp(color, 0.0, 1.0), vec3(1.0 / 1.2));

    // ── ATMOSPHERIC DISTANCE HAZE (aerial perspective) ────────────────────────
    // Distant geometry fades into a blue-grey haze, selling the colossal scale
    // of the snow peaks: a far summit dissolves into cold sky. The haze turns
    // bluer and brighter with altitude (thin alpine air), and a little warmth
    // bleeds into low terrain when you look down on it from a peak.
    // Uses only existing uniforms — no CPU-side wiring needed.
    if (isUnderwater == 0) {
        float viewDist = gl_FragCoord.z / gl_FragCoord.w;
        float hazeT    = 1.0 - exp(-max(0.0, viewDist - 150.0) * 0.0016);
        hazeT          = clamp(hazeT, 0.0, 0.85);

        float altT = clamp((cameraY - 300.0) / 600.0, 0.0, 1.0);
        vec3  haze = mix(vec3(0.60, 0.69, 0.80), vec3(0.78, 0.86, 0.98), altT);

        // Valleys/low terrain seen from high up read a touch warmer.
        float warmT = clamp((cameraY - vWorldY - 300.0) / 600.0, 0.0, 1.0) * altT;
        haze = mix(haze, vec3(0.88, 0.80, 0.68), warmT * 0.45);

        gammaCorrected = mix(gammaCorrected, haze, hazeT);
    }

    // ── ABYSS DEPTH DARKNESS ──────────────────────────────────────────────────
    float distBelow = cameraY - vWorldY;
    if (distBelow > 80.0) {
        float t         = (distBelow - 80.0) * 0.0055;
        float abyssFog  = clamp(1.0 - exp(-t), 0.0, 0.92);
        vec3  abyssColor = vec3(0.012, 0.006, 0.022);
        gammaCorrected  = mix(gammaCorrected, abyssColor, abyssFog);
    }

    // ── UNDERWATER FOG ────────────────────────────────────────────────────────
    if (isUnderwater == 1) {
        float depthDist    = gl_FragCoord.z / gl_FragCoord.w;
        float fogFactor    = 1.0 - exp(-depthDist * 0.15);
        vec3  waterFogColor = vec3(0.05, 0.20, 0.55);
        gammaCorrected     = mix(gammaCorrected, waterFogColor,
                                 clamp(fogFactor + 0.3, 0.0, 1.0));
    }

    // ── SNOW BIOME ATMOSPHERE ─────────────────────────────────────────────────
    if (snowAtmosphereStrength > 0.001) {
        vec3 snowAtmColor = vec3(0.68, 0.82, 0.96);
        gammaCorrected = mix(gammaCorrected, snowAtmColor, snowAtmosphereStrength);
    }

    // ── TIME SCALE VIGNETTE ───────────────────────────────────────────────────
    if (timeVignetteStrength > 0.001) {
        gammaCorrected = mix(gammaCorrected, timeVignetteColor, timeVignetteStrength);
    }

    // ── ABILITY OVERLAY VIGNETTE ──────────────────────────────────────────────
    // Applied after time vignette so abilities visually "win" during activation.
    if (overlayVignetteStrength > 0.001) {
        gammaCorrected = mix(gammaCorrected, overlayVignetteColor, overlayVignetteStrength);
    }

    // ── IMPACT DESATURATION (explosions, slow-mo, near-death) ─────────────────
    if (desaturate > 0.001) {
        float lum = dot(gammaCorrected, vec3(0.299, 0.587, 0.114));
        gammaCorrected = mix(gammaCorrected, vec3(lum), desaturate);
    }

    FragColor = vec4(gammaCorrected, baseColor.a * alphaMultiplier);
}
