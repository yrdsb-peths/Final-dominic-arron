#version 330 core
in vec4  vertexColor;
in vec3  vertexNormal;
in float vWorldY;        // world-space Y of this fragment (from vertex shader)

uniform vec3  sunDirection;
uniform float sunStrength;
uniform float ambientStrength;
uniform int   isUnderwater;
uniform float cameraY;        // camera eye world-Y, set each frame from Java

out vec4 FragColor;

void main() {
    float diffuse = max(0.0, dot(normalize(vertexNormal), normalize(sunDirection)));
    float light   = ambientStrength + sunStrength * diffuse;

    vec3 color         = vertexColor.rgb * light;
    vec3 gammaCorrected = pow(clamp(color, 0.0, 1.0), vec3(1.0 / 1.2));

    // ── ABYSS DEPTH DARKNESS ──────────────────────────────────────────────────
    // Fragments far below the camera eye fade to near-black.
    // Onset at 80 blocks below camera; fully opaque dark at ~450 blocks.
    // This hides the void at ungenerated chunk boundaries and makes the abyss
    // feel genuinely bottomless — lore-accurate darkness you cannot see through.
    float distBelow = cameraY - vWorldY;          // positive = fragment is below camera
    if (distBelow > 80.0) {
        float t = (distBelow - 80.0) * 0.0055;   // scale: ~1.0 at 260 blocks below onset
        float abyssFog = clamp(1.0 - exp(-t), 0.0, 0.92);
        // Deep purple-black: matches the Made in Abyss colour palette
        vec3 abyssColor = vec3(0.012, 0.006, 0.022);
        gammaCorrected = mix(gammaCorrected, abyssColor, abyssFog);
    }

    // ── UNDERWATER FOG ────────────────────────────────────────────────────────
    if (isUnderwater == 1) {
        float depthDist = gl_FragCoord.z / gl_FragCoord.w;
        float fogFactor = 1.0 - exp(-depthDist * 0.15);
        vec3  waterFogColor = vec3(0.05, 0.20, 0.55);
        gammaCorrected = mix(gammaCorrected, waterFogColor, clamp(fogFactor + 0.3, 0.0, 1.0));
    }

    FragColor = vec4(gammaCorrected, vertexColor.a);
}