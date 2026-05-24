#version 330 core
in vec4 vertexColor;
in vec3 vertexNormal;

uniform vec3  sunDirection;
uniform float sunStrength;
uniform float ambientStrength;
uniform int   isUnderwater; // New flag!

out vec4 FragColor;

void main() {
    float diffuse = max(0.0, dot(normalize(vertexNormal), normalize(sunDirection)));
    float light = ambientStrength + sunStrength * diffuse;

    // Apply light to the RGB, but keep Alpha untouched
    vec3 color = vertexColor.rgb * light;
    vec3 gammaCorrected = pow(clamp(color, 0.0, 1.0), vec3(1.0 / 1.2));

    // ── UNDERWATER FOG / BLUR EFFECT ──────────────────────────────────
    if (isUnderwater == 1) {
        // Calculate distance from camera using depth buffer
        float depthDist = gl_FragCoord.z / gl_FragCoord.w;

        // Deep blue fog that gets denser the further you look
        float fogFactor = 1.0 - exp(-depthDist * 0.15);
        vec3 waterFogColor = vec3(0.05, 0.20, 0.55); // Deep ocean blue

        // Always apply a 30% tint, blending to 100% fog in the distance
        gammaCorrected = mix(gammaCorrected, waterFogColor, clamp(fogFactor + 0.3, 0.0, 1.0));
    }

    FragColor = vec4(gammaCorrected, vertexColor.a);
}