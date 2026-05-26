package com.leaf.game.render;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Minimal but robust Wavefront OBJ loader.
 *
 * <p><b>Supported features:</b>
 * <ul>
 *   <li>{@code v}  — geometric vertices</li>
 *   <li>{@code vt} — texture coordinates (optional; (0,0) used when absent)</li>
 *   <li>{@code vn} — per-vertex normals (optional; face normals auto-computed otherwise)</li>
 *   <li>{@code f}  — faces in {@code v}, {@code v/vt}, {@code v/vt/vn}, {@code v//vn} formats</li>
 *   <li>Polygons with more than 3 vertices (fan-triangulated)</li>
 * </ul>
 *
 * <p><b>Silently ignored:</b> {@code o}, {@code g}, {@code s}, {@code usemtl}, {@code mtllib},
 *  {@code l} (lines), {@code p} (points), and comment lines.
 *
 * <p>Output uses the {@link ModelMesh} vertex format:
 * {@code pos(3) + color(4) + normal(3) + uv(2) = 12 floats/vertex}.
 * Vertex colour is set to the {@code r,g,b,a} tint supplied to the load call
 * (defaults to white (1,1,1,1) — use the shader's {@code alphaMultiplier} or a
 * colour-override uniform to tint at render time instead of baking the colour in).
 *
 * <p>All OBJ indices are 1-based; negative (relative) indices are not currently supported.
 */
public final class ObjLoader {

    private ObjLoader() {}

    // ─────────────────────────────────────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Load an OBJ from the classpath with white (1,1,1,1) vertex colour.
     *
     * @param classpathPath e.g. {@code "/models/seal.obj"}
     */
    public static ModelMesh load(String classpathPath) {
        return load(classpathPath, 1f, 1f, 1f, 1f);
    }

    /**
     * Load an OBJ from the classpath, baking a tint colour into every vertex.
     *
     * @param classpathPath e.g. {@code "/models/seal.obj"}
     * @param r red   [0..1]
     * @param g green [0..1]
     * @param b blue  [0..1]
     * @param a alpha [0..1]
     */
    public static ModelMesh load(String classpathPath, float r, float g, float b, float a) {

        // ── Parse ─────────────────────────────────────────────────────────────
        List<float[]> positions = new ArrayList<>();  // [x, y, z]
        List<float[]> uvCoords  = new ArrayList<>();  // [u, v]
        List<float[]> normals   = new ArrayList<>();  // [nx, ny, nz]

        // Each face element is int[][] where each row = [posIdx, uvIdx, nrmIdx]
        // uvIdx / nrmIdx == -1 when absent.
        List<int[][]> triangles = new ArrayList<>();  // fan-triangulated

        try (InputStream is = ObjLoader.class.getResourceAsStream(classpathPath);
             BufferedReader br = new BufferedReader(
                     new InputStreamReader(
                             Objects.requireNonNull(is, "OBJ not found: " + classpathPath)))) {

            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.charAt(0) == '#') continue;

                if (line.startsWith("v ")) {
                    String[] t = splitSpace(line, 2);
                    positions.add(new float[]{
                            Float.parseFloat(t[0]),
                            Float.parseFloat(t[1]),
                            Float.parseFloat(t[2])
                    });

                } else if (line.startsWith("vt ")) {
                    String[] t = splitSpace(line, 3);
                    uvCoords.add(new float[]{
                            Float.parseFloat(t[0]),
                            t.length > 1 ? Float.parseFloat(t[1]) : 0f
                    });

                } else if (line.startsWith("vn ")) {
                    String[] t = splitSpace(line, 3);
                    normals.add(new float[]{
                            Float.parseFloat(t[0]),
                            Float.parseFloat(t[1]),
                            Float.parseFloat(t[2])
                    });

                } else if (line.startsWith("f ")) {
                    String[] tokens = splitSpace(line, 2);
                    int[][] verts = new int[tokens.length][3];
                    for (int i = 0; i < tokens.length; i++) {
                        verts[i] = parseFaceToken(tokens[i]);
                    }
                    // Fan triangulation: (v0, v1, v2), (v0, v2, v3), …
                    for (int i = 1; i + 1 < verts.length; i++) {
                        triangles.add(new int[][]{ verts[0], verts[i], verts[i + 1] });
                    }
                }
                // All other tokens (o, g, s, usemtl, mtllib, …) silently ignored.
            }

        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load OBJ: " + classpathPath, e);
        }

        if (positions.isEmpty())
            throw new RuntimeException("OBJ has no vertices: " + classpathPath);

        // ── Assemble ModelMesh data ───────────────────────────────────────────
        boolean hasNormals = !normals.isEmpty();
        boolean hasUVs     = !uvCoords.isEmpty();

        // Deduplication map: "posIdx/uvIdx/nrmIdx" → output index
        Map<String, Integer> indexMap = new LinkedHashMap<>();
        List<Float>   vertData = new ArrayList<>();
        List<Integer> idxData  = new ArrayList<>();

        for (int[][] tri : triangles) {
            // Compute face normal once per triangle when vn is absent
            float[] faceNormal = null;
            if (!hasNormals) {
                faceNormal = computeFaceNormal(
                        positions.get(tri[0][0]),
                        positions.get(tri[1][0]),
                        positions.get(tri[2][0]));
            }

            for (int[] v : tri) {
                // Build dedup key
                String key = v[0] + "/" + v[1] + "/" + v[2];
                Integer existing = indexMap.get(key);

                if (existing != null) {
                    idxData.add(existing);
                } else {
                    int newIdx = indexMap.size();
                    indexMap.put(key, newIdx);
                    idxData.add(newIdx);

                    float[] pos = positions.get(v[0]);
                    float[] uv  = (hasUVs && v[1] >= 0)      ? uvCoords.get(v[1])  : ZERO_UV;
                    float[] nrm = (hasNormals && v[2] >= 0)   ? normals.get(v[2])   : faceNormal;

                    // pos(3) + color(4) + normal(3) + uv(2) = 12 floats
                    vertData.add(pos[0]); vertData.add(pos[1]); vertData.add(pos[2]);
                    vertData.add(r);      vertData.add(g);       vertData.add(b); vertData.add(a);
                    vertData.add(nrm[0]); vertData.add(nrm[1]); vertData.add(nrm[2]);
                    vertData.add(uv[0]);  vertData.add(uv[1]);
                }
            }
        }

        float[] vertices = new float[vertData.size()];
        int[]   indices  = new int[idxData.size()];
        for (int i = 0; i < vertices.length; i++) vertices[i] = vertData.get(i);
        for (int i = 0; i < indices.length;  i++) indices[i]  = idxData.get(i);

        return new ModelMesh(vertices, indices);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static final float[] ZERO_UV = {0f, 0f};

    /**
     * Split {@code line} on whitespace, skipping the first {@code skip} characters (the keyword).
     */
    private static String[] splitSpace(String line, int skip) {
        return line.substring(skip).trim().split("\\s+");
    }

    /**
     * Parse a single face token.  Supported formats:
     * {@code v}, {@code v/vt}, {@code v/vt/vn}, {@code v//vn}.
     *
     * @return [posIdx0, uvIdx, nrmIdx] where absent indices are −1; all are 0-based.
     */
    private static int[] parseFaceToken(String token) {
        String[] parts = token.split("/", -1);
        int posIdx = Integer.parseInt(parts[0]) - 1;
        int uvIdx  = (parts.length > 1 && !parts[1].isEmpty()) ? Integer.parseInt(parts[1]) - 1 : -1;
        int nrmIdx = (parts.length > 2 && !parts[2].isEmpty()) ? Integer.parseInt(parts[2]) - 1 : -1;
        return new int[]{posIdx, uvIdx, nrmIdx};
    }

    /** Compute a unit face normal from three CCW positions. */
    private static float[] computeFaceNormal(float[] p0, float[] p1, float[] p2) {
        float ax = p1[0] - p0[0], ay = p1[1] - p0[1], az = p1[2] - p0[2];
        float bx = p2[0] - p0[0], by = p2[1] - p0[1], bz = p2[2] - p0[2];
        float nx = ay * bz - az * by;
        float ny = az * bx - ax * bz;
        float nz = ax * by - ay * bx;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len > 1e-6f) { nx /= len; ny /= len; nz /= len; }
        return new float[]{nx, ny, nz};
    }
}
