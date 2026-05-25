#version 330 core
in vec4  vertexColor;
in vec3  vertexNormal;
in float vWorldY;        // world-space Y of this fragment (from vertex shader)

uniform vec3  sunDirection;
uniform float sunStrength;
uniform float ambientStrength;
uniform int   isUnderwater;
uniform float cameraY;        // camera eye world-Y, set each frame from Java

// ── TIME DILATION VIGNETTE ────────────────────────────────────────────────────
// timeVignetteStrength : 0 = no tint, ~0.30 at peak slow/fast
// timeVignetteColor    : blue-grey (slow) or warm orange (fast)
// These are cheap full-screen colour blends, not post-process passes.
// Both are set by Window.java each frame from TimeController.*Factor().
uniform float timeVignetteStrength;
uniform vec3  timeVignetteColor;

out vec4 FragColor;

void main() {
    float diffuse = max(0.0, dot(normalize(vertexNormal), normalize(sunDirection)));
    float light   = ambientStrength + sunStrength * diffuse;

    vec3 color          = vertexColor.rgb * light;
    vec3 gammaCorrected = pow(clamp(color, 0.0, 1.0), vec3(1.0 / 1.2));

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

    // ── TIME SCALE VIGNETTE ───────────────────────────────────────────────────
    // Applied last so it overlays everything including abyss/underwater effects.
    // Slow motion → subtle blue-grey wash.  Fast time → warm orange tint.
    if (timeVignetteStrength > 0.001) {
        gammaCorrected = mix(gammaCorrected, timeVignetteColor, timeVignetteStrength);
    }

    FragColor = vec4(gammaCorrected, vertexColor.a);
}