// --- FILE: src/main/java/com/leaf/game/render/BlockTextureAtlas.java ---
package com.leaf.game.render;

import com.leaf.game.world.Block;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.*;

import static org.lwjgl.opengl.GL33.*;

public class BlockTextureAtlas {

    public static final int TILE_SIZE  = 16;
    public static final int ATLAS_COLS = 16;  // tiles per atlas row

    // Cube-net layout for 48×64 textures  (col, row inside the 3×4 grid).
    //
    //        col 0   col 1   col 2
    //  row 0:         [side]              ← front/south side
    //  row 1: [side] [ TOP ] [side]       ← left / TOP VIEW / right
    //  row 2:        [bottom]             ← dirt underside
    //  row 3:         [side]              ← back/north side
    //
    // Face order:  0=top  1=bottom  2=front  3=back  4=right  5=left
    // Face order:  0=top  1=bottom  2=front  3=back  4=right  5=left
    private static final int[][] CROSS = {
            {1, 1}, // face 0 = top    → (1, 1) Pure green
            {1, 3}, // face 1 = bottom → (1, 3) Pure dirt
            {1, 2}, // face 2 = front  → (1, 2) Grass side
            {1, 0}, // face 3 = back   → (1, 0) Grass side
            {2, 1}, // face 4 = right  → (2, 1) Grass side
            {0, 1}  // face 5 = left   → (0, 1) Grass side
    };

    private static int     textureId = 0;
    private static boolean loaded    = false;

    private static final Map<String, float[][]> faceUVs = new HashMap<>();
    private static float[] WHITE_UV;

    public static void load() {
        System.out.println("[Atlas] Starting atlas compilation...");
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (Block b : Block.values()) {
            if (b.texName != null) names.add(b.texName);
        }

        int totalSlots = 1 + names.size() * 6;
        int atlasRows  = (totalSlots + ATLAS_COLS - 1) / ATLAS_COLS;
        int atlasW     = ATLAS_COLS * TILE_SIZE;
        int atlasH     = atlasRows  * TILE_SIZE;
        byte[] atlas   = new byte[atlasW * atlasH * 4];

        System.out.println("[Atlas] Allocated RAM buffer: " + atlasW + "x" + atlasH);

        blitTile(atlas, atlasW, fillWhite(), 0, 0);
        float tileW = (float) TILE_SIZE / atlasW;
        float tileH = (float) TILE_SIZE / atlasH;
        WHITE_UV = uvFor(0, tileW, tileH);

        int slot = 1;
        List<String> missing = new ArrayList<>();
        for (String name : names) {
            byte[][] faces = loadFaces(name, missing);
            float[][] uvTable = new float[6][];
            for (int f = 0; f < 6; f++) {
                int col = slot % ATLAS_COLS;
                int row = slot / ATLAS_COLS;
                blitTile(atlas, atlasW, faces[f], col, row);
                uvTable[f] = uvFor(slot, tileW, tileH);
                slot++;
            }
            faceUVs.put(name, uvTable);
        }

        if (!missing.isEmpty()) {
            System.out.println("[Atlas] Missing (flat colour fallback): " + missing);
        }

        System.out.println("[Atlas] Uploading to OpenGL...");
        ByteBuffer buf = MemoryUtil.memAlloc(atlas.length);
        try {
            buf.put(atlas).flip();

            textureId = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, textureId);

            // Use proper unpacking alignment just in case
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1);

            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, atlasW, atlasH,
                    0, GL_RGBA, GL_UNSIGNED_BYTE, buf);
            glBindTexture(GL_TEXTURE_2D, 0);
        } finally {
            MemoryUtil.memFree(buf);
        }

        loaded = true;
        System.out.println("[Atlas] Built " + atlasW + "x" + atlasH
                + " with " + names.size() + " block(s), " + slot + " tiles total.");
    }

    public static float[] getUV(String texName, int faceIndex) {
        if (texName == null) return WHITE_UV;
        float[][] table = faceUVs.get(texName);
        if (table == null) return WHITE_UV;
        return table[faceIndex];
    }

    public static boolean isLoaded()     { return loaded;    }
    public static int     getTextureId() { return textureId; }

    public static void cleanup() {
        if (loaded) { glDeleteTextures(textureId); loaded = false; }
        faceUVs.clear();
    }

    private static float[] uvFor(int slot, float tileW, float tileH) {
        float uMin = (slot % ATLAS_COLS) * tileW;
        float vMin = (slot / ATLAS_COLS) * tileH;
        return new float[]{ uMin, vMin, uMin + tileW, vMin + tileH };
    }

    private static byte[] fillWhite() {
        byte[] w = new byte[TILE_SIZE * TILE_SIZE * 4];
        Arrays.fill(w, (byte) 0xFF);
        return w;
    }

    private static byte[][] loadFaces(String name, List<String> missing) {
        System.out.println("[Atlas] Loading PNG: " + name + ".png");
        InputStream is = BlockTextureAtlas.class.getResourceAsStream("/textures/blocks/" + name + ".png");
        if (is == null) {
            missing.add(name);
            return sixWhite();
        }
        try {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(is);
            if (img == null) {
                missing.add(name);
                return sixWhite();
            }

            int iw = img.getWidth(), ih = img.getHeight();
            System.out.println("[Atlas] Decoded " + name + " (" + iw + "x" + ih + ")");

            byte[] src = toRGBA(img);

            if (iw == TILE_SIZE && ih == TILE_SIZE) {
                byte[][] faces = new byte[6][];
                Arrays.fill(faces, src);
                return faces;
            } else if (iw == TILE_SIZE * 3 && ih == TILE_SIZE * 4) {
                byte[][] faces = new byte[6][];
                for (int f = 0; f < 6; f++) {
                    boolean flip = (f >= 2); // side faces (2=front,3=back,4=right,5=left) need flipping
                    faces[f] = extractTile(src, iw, CROSS[f][0], CROSS[f][1], flip);
                }
                return faces;
            } else {
                missing.add(name);
                return sixWhite();
            }
        } catch (Exception e) {
            missing.add(name);
            return sixWhite();
        }
    }

    private static byte[] toRGBA(java.awt.image.BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int[] pixels = img.getRGB(0, 0, w, h, null, 0, w); // returns ARGB
        byte[] out = new byte[w * h * 4];
        for (int i = 0; i < pixels.length; i++) {
            int argb  = pixels[i];
            out[i*4]     = (byte)((argb >> 16) & 0xFF); // R
            out[i*4 + 1] = (byte)((argb >> 8)  & 0xFF); // G
            out[i*4 + 2] = (byte)(argb         & 0xFF); // B
            out[i*4 + 3] = (byte)((argb >> 24) & 0xFF); // A
        }
        return out;
    }

    private static byte[] extractTile(byte[] src, int srcW, int col, int row, boolean flipV) {
        byte[] tile = new byte[TILE_SIZE * TILE_SIZE * 4];
        for (int ty = 0; ty < TILE_SIZE; ty++) {
            // When flipV is true, read image rows in reverse so that what you drew
            // at the TOP of the tile (image row 0) ends up at the TOP of the rendered
            // face — compensating for glTexImage2D's bottom-up byte interpretation.
            int srcTy = flipV ? (TILE_SIZE - 1 - ty) : ty;
            for (int tx = 0; tx < TILE_SIZE; tx++) {
                int sx  = col * TILE_SIZE + tx;
                int sy  = row * TILE_SIZE + srcTy;
                int si  = (sy * srcW + sx) * 4;
                int di  = (ty * TILE_SIZE + tx) * 4;
                tile[di]     = src[si];
                tile[di + 1] = src[si + 1];
                tile[di + 2] = src[si + 2];
                tile[di + 3] = src[si + 3];
            }
        }
        return tile;
    }

    private static void blitTile(byte[] atlas, int atlasW,
                                 byte[] tile, int col, int row) {
        for (int ty = 0; ty < TILE_SIZE; ty++) {
            for (int tx = 0; tx < TILE_SIZE; tx++) {
                int si  = (ty * TILE_SIZE + tx) * 4;
                int di  = ((row * TILE_SIZE + ty) * atlasW + col * TILE_SIZE + tx) * 4;
                atlas[di]     = tile[si];
                atlas[di + 1] = tile[si + 1];
                atlas[di + 2] = tile[si + 2];
                atlas[di + 3] = tile[si + 3];
            }
        }
    }

    private static byte[][] sixWhite() {
        byte[][] w = new byte[6][];
        Arrays.fill(w, fillWhite());
        return w;
    }
}