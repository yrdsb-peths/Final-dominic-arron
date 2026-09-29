#version 330 core
layout (location = 0) in vec3 aPos;
layout (location = 1) in vec4 aColor;
layout (location = 2) in vec3 aNormal;
layout (location = 3) in vec2 aUV;     // UV coords — used by ModelMesh; zero-filled for regular Mesh

uniform mat4 mvp;
// Soft-glow FX (Window.orbDrawSoft): the model matrix, so the fragment shader can
// see the effect's real world position + normal for its view-dependent falloff.
uniform int  fxSoft;
uniform mat4 fxModel;
// Local-space meshes (viewmodels, props) set useModel=1 + fxModel so lighting,
// shadows and fog see their true world position instead of the origin.
uniform int  useModel;
uniform float uTime;   // wind sway for ground-cover plants

out vec4  vertexColor;
out vec3  vertexNormal;
out float vWorldY;   // absolute world Y of this vertex, passed for abyss depth fog
out vec2  vertexUV;  // passed through to fragment for optional texture sampling
out vec3  vWorldPos; // full world-space position (for the orbital "lidar" scan)

void main() {
    vec3 pos = aPos;
    // Ground-cover plants (surface styles 9–11, packed by ChunkMesher.addPlant):
    // sway the top of each quad in a gentle travelling breeze.
    if (aUV.x < 0.0) {
        int st = int(floor(-aUV.x)) - 1;
        if (st >= 9 && st <= 11 && aUV.y > 0.0) {
            float ph = dot(floor(aPos.xz), vec2(0.73, 1.37));
            float w  = sin(uTime * 1.9 + aPos.x * 0.35 + aPos.z * 0.22 + ph) * 0.10
                     + sin(uTime * 3.7 + ph * 2.1) * 0.03;
            pos.x += w * aUV.y;
            pos.z += w * 0.6 * aUV.y;
        }
    }
    gl_Position = mvp * vec4(pos, 1.0);
    vertexColor  = aColor;
    vertexNormal = aNormal;
    vWorldY      = aPos.y;   // vertices are stored in world-space coords
    vertexUV     = aUV;
    vWorldPos    = pos;      // world-space coords (regular Mesh stores world coords)
    if (fxSoft != 0 || useModel != 0) {
        vWorldPos    = (fxModel * vec4(aPos, 1.0)).xyz;
        vertexNormal = mat3(fxModel) * aNormal;
    }
}
