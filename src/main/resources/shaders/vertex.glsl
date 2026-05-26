#version 330 core
layout (location = 0) in vec3 aPos;
layout (location = 1) in vec4 aColor;
layout (location = 2) in vec3 aNormal;
layout (location = 3) in vec2 aUV;     // UV coords — used by ModelMesh; zero-filled for regular Mesh

uniform mat4 mvp;

out vec4  vertexColor;
out vec3  vertexNormal;
out float vWorldY;   // absolute world Y of this vertex, passed for abyss depth fog
out vec2  vertexUV;  // passed through to fragment for optional texture sampling

void main() {
    gl_Position = mvp * vec4(aPos, 1.0);
    vertexColor  = aColor;
    vertexNormal = aNormal;
    vWorldY      = aPos.y;   // vertices are stored in world-space coords
    vertexUV     = aUV;
}
