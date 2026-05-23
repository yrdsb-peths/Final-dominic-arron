#version 330 core

in vec3 vertexColor;
in vec3 vertexNormal;

// Sun light
uniform vec3  sunDirection;    // direction the light is coming FROM (normalised in Java)
uniform float sunStrength;     // how bright the sun is (0.0 – 1.0)
uniform float ambientStrength; // minimum brightness even in full shadow (0.0 – 1.0)

out vec4 FragColor;

void main()
{
    // How much does this face point toward the sun?
    // dot() = 1.0 if face looks straight at sun, 0.0 if perpendicular, negative if facing away.
    // max(0) clamps the "facing away" case to zero — no negative light.
    float diffuse = max(0.0, dot(normalize(vertexNormal), normalize(sunDirection)));

    // Final light = constant ambient + sun contribution
    float light = ambientStrength + sunStrength * diffuse;

    // Apply light to the baked vertex color (which already includes AO)
    vec3 color = vertexColor * light;

    // Gamma correction — makes dark areas feel natural instead of muddy
    vec3 gammaCorrected = pow(clamp(color, 0.0, 1.0), vec3(1.0 / 1.2));

    FragColor = vec4(gammaCorrected, 1.0);
}
