package com.leaf.game;

import org.lwjgl.system.MemoryUtil;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL33.*;

public class Mesh {
    private final int vaoId;
    private final int vboId;
    private final int eboId;
    private final int vertexCount;

    public Mesh(float[] vertices, int[] indices) {
        this.vertexCount = indices.length;

        // 1. Create and bind the VAO
        vaoId = glGenVertexArrays();
        glBindVertexArray(vaoId);

        // 2. Upload Vertices to VBO
        vboId = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vboId);
        FloatBuffer verticesBuffer = MemoryUtil.memAllocFloat(vertices.length);
        verticesBuffer.put(vertices).flip();
        glBufferData(GL_ARRAY_BUFFER, verticesBuffer, GL_STATIC_DRAW);
        MemoryUtil.memFree(verticesBuffer);

        // 3. Upload Indices to EBO
        eboId = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, eboId);
        IntBuffer indicesBuffer = MemoryUtil.memAllocInt(indices.length);
        indicesBuffer.put(indices).flip();
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indicesBuffer, GL_STATIC_DRAW);
        MemoryUtil.memFree(indicesBuffer);

        // 4. Define Vertex Attributes (Telling OpenGL how to read the VBO)
        // Format:[x, y, z, r, g, b] -> 6 floats total per vertex
        int stride = 6 * Float.BYTES;

        // Position attribute (index 0 in shader)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0);
        glEnableVertexAttribArray(0);

        // Color attribute (index 1 in shader)
        glVertexAttribPointer(1, 3, GL_FLOAT, false, stride, 3 * Float.BYTES);
        glEnableVertexAttribArray(1);

        // Unbind the VAO to prevent accidental changes
        glBindVertexArray(0);
    }

    public void render() {
        // Bind the VAO to draw
        glBindVertexArray(vaoId);
        // Draw the triangles
        glDrawElements(GL_TRIANGLES, vertexCount, GL_UNSIGNED_INT, 0);
        // Unbind
        glBindVertexArray(0);
    }

    public void cleanup() {
        glDeleteBuffers(vboId);
        glDeleteBuffers(eboId);
        glDeleteVertexArrays(vaoId);
    }
}