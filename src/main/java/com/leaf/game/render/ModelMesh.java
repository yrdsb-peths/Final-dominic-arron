package com.leaf.game.render;

import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL33.*;

/**
 * GPU mesh for 3-D models loaded from OBJ files (or built procedurally).
 *
 * <p>Vertex format — 12 floats per vertex, stride = 48 bytes:
 * <pre>
 *   [0-2]  position  (vec3)   — location 0
 *   [3-6]  color     (vec4)   — location 1  (tint; (1,1,1,1) for fully textured)
 *   [7-9]  normal    (vec3)   — location 2
 *  [10-11] uv        (vec2)   — location 3
 * </pre>
 *
 * <p>Rendering with texture:
 * <pre>
 *   shader.setUniform("useTexture", 1);
 *   texture.bind();
 *   model.render();
 *   shader.setUniform("useTexture", 0); // restore default
 * </pre>
 *
 * <p>Rendering without texture (color-only):
 * <pre>
 *   // useTexture is 0 by default — nothing to set
 *   model.render();
 * </pre>
 *
 * <p>The {@code Mesh} class is unmodified; both classes share the same shader.
 * Attribute location 3 (UV) is simply unused/zero-filled when rendering a
 * regular {@link Mesh} — OpenGL treats a disabled generic attribute as (0,0,0,1).
 */
public final class ModelMesh {

    /** Number of floats per vertex: pos(3) + color(4) + normal(3) + uv(2). */
    public static final int FLOATS_PER_VERTEX = 12;

    /** Stride in bytes = 12 × 4. */
    private static final int STRIDE = FLOATS_PER_VERTEX * Float.BYTES;

    private final int vaoId;
    private final int vboId;
    private final int eboId;
    private final int indexCount;

    // ─────────────────────────────────────────────────────────────────────────
    //  Constructor
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Upload vertex and index data to the GPU.
     *
     * @param vertices flat array of 12-float vertex records
     * @param indices  triangle index list (three per triangle)
     */
    public ModelMesh(float[] vertices, int[] indices) {
        this.indexCount = indices.length;

        vaoId = glGenVertexArrays();
        glBindVertexArray(vaoId);

        // ── Vertex buffer ─────────────────────────────────────────────────────
        vboId = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vboId);
        FloatBuffer vb = MemoryUtil.memAllocFloat(vertices.length);
        vb.put(vertices).flip();
        glBufferData(GL_ARRAY_BUFFER, vb, GL_STATIC_DRAW);
        MemoryUtil.memFree(vb);

        // ── Index buffer ──────────────────────────────────────────────────────
        eboId = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, eboId);
        IntBuffer ib = MemoryUtil.memAllocInt(indices.length);
        ib.put(indices).flip();
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, ib, GL_STATIC_DRAW);
        MemoryUtil.memFree(ib);

        // ── Attribute pointers ────────────────────────────────────────────────
        // location 0 — position (vec3)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, STRIDE, 0);
        glEnableVertexAttribArray(0);

        // location 1 — color (vec4)
        glVertexAttribPointer(1, 4, GL_FLOAT, false, STRIDE, 3 * Float.BYTES);
        glEnableVertexAttribArray(1);

        // location 2 — normal (vec3)
        glVertexAttribPointer(2, 3, GL_FLOAT, false, STRIDE, 7 * Float.BYTES);
        glEnableVertexAttribArray(2);

        // location 3 — uv (vec2)
        glVertexAttribPointer(3, 2, GL_FLOAT, false, STRIDE, 10 * Float.BYTES);
        glEnableVertexAttribArray(3);

        glBindVertexArray(0);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Rendering
    // ─────────────────────────────────────────────────────────────────────────

    /** Draw this mesh using GL_TRIANGLES.  Bind a shader and set uniforms before calling. */
    public void render() {
        glBindVertexArray(vaoId);
        glDrawElements(GL_TRIANGLES, indexCount, GL_UNSIGNED_INT, 0);
        glBindVertexArray(0);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Cleanup
    // ─────────────────────────────────────────────────────────────────────────

    /** Free GPU buffers.  Must be called on the render thread before the GL context is destroyed. */
    public void cleanup() {
        glDeleteBuffers(vboId);
        glDeleteBuffers(eboId);
        glDeleteVertexArrays(vaoId);
    }
}
