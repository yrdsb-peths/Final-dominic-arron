#version 330 core

// Input coming from the vertex shader
in vec3 vertexColor;

// The final output color of the pixel
out vec4 FragColor;

void main()
{
    // Set the pixel color (RGB + Alpha for transparency)
    FragColor = vec4(vertexColor, 1.0);
}