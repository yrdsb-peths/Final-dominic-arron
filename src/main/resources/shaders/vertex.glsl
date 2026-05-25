#version 330 core
layout (location = 0) in vec3 aPos;
layout (location = 1) in vec4 aColor;
layout (location = 2) in vec3 aNormal;

uniform mat4 mvp;

out vec4 vertexColor;
out vec3 vertexNormal;
out float vWorldY;   // absolute world Y of this vertex, passed for abyss depth fog

void main() {
    gl_Position = mvp * vec4(aPos, 1.0);
    vertexColor  = aColor;
    vertexNormal = aNormal;
    vWorldY      = aPos.y;   // vertices are stored in world-space coords
}