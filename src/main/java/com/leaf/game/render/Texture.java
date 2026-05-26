package com.leaf.game.render;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL33.*;
import static org.lwjgl.stb.STBImage.*;

/**
 * A 2-D OpenGL texture loaded from a PNG on the classpath via STBImage.
 *
 * <p>Usage:
 * <pre>
 *   Texture t = Texture.load("/textures/seal.png");
 *   t.bind();             // binds to GL_TEXTURE_2D on unit 0
 *   // … render …
 *   t.cleanup();          // call once when the game shuts down
 * </pre>
 *
 * <p>Memory contract: the STB pixel buffer is freed immediately after uploading
 * to the GPU; only the GL texture object remains allocated.
 */
public final class Texture {

    private final int id;
    private final int width;
    private final int height;

    // ─────────────────────────────────────────────────────────────────────────
    //  Factory
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Load a PNG texture from the classpath.
     *
     * @param classpathPath e.g. {@code "/textures/seal.png"}  (must start with '/')
     * @throws RuntimeException if the resource is missing or STBImage fails
     */
    public static Texture load(String classpathPath) {
        // ── 1. Read raw bytes from classpath ─────────────────────────────────
        ByteBuffer rawBuffer;
        try (InputStream is = Texture.class.getResourceAsStream(classpathPath)) {
            if (is == null)
                throw new RuntimeException("Texture resource not found: " + classpathPath);
            byte[] bytes = is.readAllBytes();
            rawBuffer = MemoryUtil.memAlloc(bytes.length);
            rawBuffer.put(bytes).flip();
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new RuntimeException("Failed to read texture resource: " + classpathPath, e);
        }

        // ── 2. Decode with STBImage ───────────────────────────────────────────
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer wBuf = stack.mallocInt(1);
            IntBuffer hBuf = stack.mallocInt(1);
            IntBuffer chBuf = stack.mallocInt(1);

            stbi_set_flip_vertically_on_load(true); // OpenGL origin = bottom-left
            ByteBuffer pixels = stbi_load_from_memory(rawBuffer, wBuf, hBuf, chBuf, 4 /*RGBA*/);
            MemoryUtil.memFree(rawBuffer); // raw bytes no longer needed

            if (pixels == null)
                throw new RuntimeException("STBImage decode failed for '" + classpathPath
                        + "': " + stbi_failure_reason());

            // ── 3. Upload to GPU ─────────────────────────────────────────────
            int texId = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, texId);

            // Wrapping and filtering
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S,     GL_REPEAT);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T,     GL_REPEAT);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA,
                    wBuf.get(0), hBuf.get(0), 0,
                    GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            glGenerateMipmap(GL_TEXTURE_2D);

            stbi_image_free(pixels); // STB pixel buffer freed immediately

            glBindTexture(GL_TEXTURE_2D, 0);

            return new Texture(texId, wBuf.get(0), hBuf.get(0));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    private Texture(int id, int width, int height) {
        this.id     = id;
        this.width  = width;
        this.height = height;
    }

    // ── Public API ────────────────────────────────────────────────────────────
    /** Bind this texture to {@code GL_TEXTURE_2D} on the active texture unit. */
    public void bind()       { glBindTexture(GL_TEXTURE_2D, id); }
    public int  getId()      { return id; }
    public int  getWidth()   { return width; }
    public int  getHeight()  { return height; }

    /** Free the GPU texture object.  Must be called on the render thread. */
    public void cleanup()    { glDeleteTextures(id); }
}
