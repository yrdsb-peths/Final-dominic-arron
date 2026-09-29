#version 330 core
// Final pass: HDR scene + bloom → graded LDR screen image (see PostFx.java).
in  vec2 TexCoord;
out vec4 FragColor;

uniform sampler2D scene;
uniform sampler2D bloom;
uniform float bloomStrength;
uniform float flash;      // Deprivation Domain strike wash (0 = none)
uniform float vignette;   // corner darkening amount
uniform float time;

// Identity up to 0.85, then a smooth shoulder that approaches 1.0 — HDR energy
// (lava, laser cores, glints) rolls off gracefully instead of hard-clipping.
vec3 shoulder(vec3 x) {
    vec3 over = max(x - 0.85, 0.0);
    return min(x, 0.85) + 0.15 * (1.0 - exp(-over / 0.15));
}

float hash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }

void main() {
    vec3 col = texture(scene, TexCoord).rgb;
    // The pyramid sums 5 mip levels; normalise so bloomStrength is "fraction of
    // the over-threshold light that spills", independent of the level count.
    col += texture(bloom, TexCoord).rgb * (bloomStrength / 5.0);

    if (flash > 0.001) col += vec3(1.6, 1.2, 0.35) * flash * 0.55;

    col = shoulder(max(col, 0.0));

    // Split tone: a whisper of cool into the shadows, warmth into the highlights.
    float l = dot(col, vec3(0.2126, 0.7152, 0.0722));
    col += vec3(-0.010, 0.000, 0.018) * (1.0 - l) + vec3(0.016, 0.008, -0.012) * l;

    // Gentle S-curve for a bit more punch in the mids.
    col = mix(col, col * col * (3.0 - 2.0 * col), 0.18);

    // Vignette — frames the shot, pulls the eye to the crosshair.
    vec2  v  = TexCoord - 0.5;
    col *= 1.0 - vignette * smoothstep(0.18, 0.75, dot(v, v) * 1.6);

    // Dither: ±½ LSB of noise hides 8-bit banding in the sky gradient.
    col += (hash(gl_FragCoord.xy + fract(time) * 61.0) - 0.5) / 255.0;

    FragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
