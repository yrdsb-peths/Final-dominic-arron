#version 330 core

// Input attributes from our Java code (Vertex positions and colors)
layout (location = 0) in vec3 aPos;
layout (location = 1) in vec3 aColor;

// The Model-View-Projection matrix we will pass from Java
uniform mat4 mvp;

// The color we are passing to the fragment shader
out vec3 vertexColor;

void main()
{
    // Apply the matrix math to the 3D position
    gl_Position = mvp * vec4(aPos, 1.0);

    // Pass the color straight through to the next step
    vertexColor = aColor;
}