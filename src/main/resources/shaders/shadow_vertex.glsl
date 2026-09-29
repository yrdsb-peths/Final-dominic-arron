#version 330 core
// Depth-only pass for the sun/moon shadow map (see ShadowMap.java).
layout (location = 0) in vec3 aPos;
layout (location = 3) in vec2 aUV;

uniform mat4 lightVP;

out float vStyleU;   // procedural style packed by the mesher (negative = untextured block)

void main() {
    vStyleU     = aUV.x;
    gl_Position = lightVP * vec4(aPos, 1.0);
}
