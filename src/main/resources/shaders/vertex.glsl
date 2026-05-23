#version 330 core

layout (location = 0) in vec3 aPos;
layout (location = 1) in vec3 aColor;
layout (location = 2) in vec3 aNormal;   // face normal — which way this face points

uniform mat4 mvp;

out vec3 vertexColor;
out vec3 vertexNormal;

void main()
{
    gl_Position = mvp * vec4(aPos, 1.0);
    vertexColor = aColor;
    vertexNormal = aNormal;
}
