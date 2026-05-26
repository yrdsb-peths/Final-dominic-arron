package com.leaf.game.render;

import java.util.HashMap;
import java.util.Map;

/**
 * Singleton asset cache for 3-D models ({@link ModelMesh}) and 2-D textures ({@link Texture}).
 *
 * <h2>Model resolution order</h2>
 * <ol>
 *   <li>Check the in-memory cache.</li>
 *   <li>Try to load {@code /models/{name}.obj} from the classpath.</li>
 *   <li>Fall back to a procedurally-generated mesh built into this class.</li>
 * </ol>
 *
 * <h2>Texture resolution order</h2>
 * <ol>
 *   <li>Check the in-memory cache.</li>
 *   <li>Try to load {@code /textures/{name}.png} from the classpath.</li>
 *   <li>Return {@code null} (callers set {@code useTexture=0} in that case).</li>
 * </ol>
 *
 * <h2>Adding your own models</h2>
 * Drop an OBJ file into {@code src/main/resources/models/} and a PNG texture into
 * {@code src/main/resources/textures/} — both named identically (e.g. {@code seal.obj} +
 * {@code seal.png}).  Call {@code AssetManager.get().getModel("seal")} and
 * {@code AssetManager.get().getTexture("seal")} from Window.java.
 *
 * <h2>Built-in procedural fallbacks</h2>
 * <ul>
 *   <li>{@code "seal"}   — amber-gold elongated octahedron (hexagonal bipyramid)</li>
 *   <li>{@code "stand"}  — steel-blue low-poly saucer / flying-disc shape</li>
 *   <li>{@code "player"} — warm-grey capsule (cylinder body + dome head)</li>
 * </ul>
 *
 * <h2>Lifecycle</h2>
 * Call {@link #cleanup()} once when the GL context is being destroyed.
 * After cleanup the singleton is reset and can be re-initialised if needed.
 */
public final class AssetManager {

    // ─────────────────────────────────────────────────────────────────────────
    //  Singleton
    // ─────────────────────────────────────────────────────────────────────────

    private static AssetManager INSTANCE;

    /** Return (or lazily create) the singleton instance. */
    public static AssetManager get() {
        if (INSTANCE == null) INSTANCE = new AssetManager();
        return INSTANCE;
    }

    private final Map<String, ModelMesh> meshCache    = new HashMap<>();
    private final Map<String, Texture>   textureCache = new HashMap<>();

    private AssetManager() {}

    // ─────────────────────────────────────────────────────────────────────────
    //  Model access
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Get (or lazily load) a model by name.
     * <p>Never returns {@code null} — falls back to a procedural mesh.
     *
     * @param name e.g. {@code "seal"}, {@code "stand"}, {@code "player"}
     */
    public ModelMesh getModel(String name) {
        return meshCache.computeIfAbsent(name, this::resolveModel);
    }

    private ModelMesh resolveModel(String name) {
        // Try OBJ on classpath first
        String path = "/models/" + name + ".obj";
        if (resourceExists(path)) {
            try {
                return ObjLoader.load(path);
            } catch (Exception e) {
                System.err.println("[AssetManager] Failed to load " + path + ": " + e.getMessage());
                // Fall through to procedural
            }
        }
        // Procedural fallback
        return switch (name) {
            case "seal"   -> buildSealOctahedron();
            case "stand"  -> buildStandSaucer();
            case "player" -> buildPlayerCapsule();
            default       -> buildDefaultCube();
        };
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Texture access
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Get (or lazily load) a texture by name.
     * <p>Returns {@code null} if no PNG is found — callers should set
     * {@code useTexture=0} when this returns {@code null}.
     *
     * @param name e.g. {@code "seal"}, {@code "stand"}
     */
    public Texture getTexture(String name) {
        if (textureCache.containsKey(name)) return textureCache.get(name);
        String path = "/textures/" + name + ".png";
        if (resourceExists(path)) {
            try {
                Texture t = Texture.load(path);
                textureCache.put(name, t);
                return t;
            } catch (Exception e) {
                System.err.println("[AssetManager] Failed to load texture " + path + ": " + e.getMessage());
            }
        }
        textureCache.put(name, null); // cache the miss so we don't retry every frame
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Cleanup
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Free all GPU resources.  Must be called on the render thread before the GL
     * context is destroyed.  The singleton is reset so {@link #get()} will re-create
     * it if needed (e.g. in tests).
     */
    public void cleanup() {
        meshCache.values().forEach(ModelMesh::cleanup);
        meshCache.clear();
        textureCache.values().stream().filter(t -> t != null).forEach(Texture::cleanup);
        textureCache.clear();
        INSTANCE = null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Helper
    // ─────────────────────────────────────────────────────────────────────────

    private static boolean resourceExists(String classpathPath) {
        return AssetManager.class.getResourceAsStream(classpathPath) != null;
    }

    // =========================================================================
    //  Procedural mesh builders
    // =========================================================================

    // ── Shared vertex builder ─────────────────────────────────────────────────

    /**
     * Append one 12-float vertex to {@code out}.
     * Format: pos(3) + color(4) + normal(3) + uv(2).
     */
    private static void addVertex(java.util.List<Float> out,
                                  float x, float y, float z,
                                  float r, float g, float b, float a,
                                  float nx, float ny, float nz,
                                  float u, float v) {
        out.add(x);  out.add(y);  out.add(z);
        out.add(r);  out.add(g);  out.add(b);  out.add(a);
        out.add(nx); out.add(ny); out.add(nz);
        out.add(u);  out.add(v);
    }

    private static float[] toFloatArray(java.util.List<Float> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = list.get(i);
        return arr;
    }

    private static int[] toIntArray(java.util.List<Integer> list) {
        int[] arr = new int[list.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = list.get(i);
        return arr;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  SEAL — elongated hexagonal bipyramid (fancy diamond / seal marker)
    //
    //  Topology: 6-sided equatorial ring + top apex + bottom apex = 8 tris (16 per side)
    //  Color: amber-gold  (1.0, 0.82, 0.18, 1.0)
    // ─────────────────────────────────────────────────────────────────────────
    private static ModelMesh buildSealOctahedron() {
        final float R = 0.40f;  // equatorial radius
        final float H = 0.60f;  // half-height (top and bottom apex)
        final int   N = 8;      // sides
        final float r = 1.00f, g = 0.82f, b = 0.18f, a = 1.0f; // amber-gold

        java.util.List<Float>   verts   = new java.util.ArrayList<>();
        java.util.List<Integer> indices = new java.util.ArrayList<>();

        // One triangle per upper face and one per lower face.
        // Build flat-shaded: each triangle has its own 3 vertices so normals are unique.
        float twoPi = (float)(2 * Math.PI);

        for (int i = 0; i < N; i++) {
            float a0 = twoPi * i       / N;
            float a1 = twoPi * (i + 1) / N;
            float x0 = R * (float)Math.cos(a0), z0 = R * (float)Math.sin(a0);
            float x1 = R * (float)Math.cos(a1), z1 = R * (float)Math.sin(a1);

            // Upper triangle: equator-edge0, equator-edge1, apex-top
            float[] nu = normal3(x0, 0f, z0,  x1, 0f, z1,  0f, H, 0f);
            int base = verts.size() / ModelMesh.FLOATS_PER_VERTEX;
            addVertex(verts, x0, 0f, z0, r,g,b,a, nu[0],nu[1],nu[2], 0f,0f);
            addVertex(verts, x1, 0f, z1, r,g,b,a, nu[0],nu[1],nu[2], 1f,0f);
            addVertex(verts, 0f, H,  0f, r,g,b,a, nu[0],nu[1],nu[2], 0.5f,1f);
            indices.add(base); indices.add(base+1); indices.add(base+2);

            // Lower triangle: equator-edge1, equator-edge0, apex-bottom
            float[] nd = normal3(x1, 0f, z1,  x0, 0f, z0,  0f, -H, 0f);
            base = verts.size() / ModelMesh.FLOATS_PER_VERTEX;
            addVertex(verts, x1,  0f, z1, r,g,b,a, nd[0],nd[1],nd[2], 0f,0f);
            addVertex(verts, x0,  0f, z0, r,g,b,a, nd[0],nd[1],nd[2], 1f,0f);
            addVertex(verts, 0f, -H,  0f, r,g,b,a, nd[0],nd[1],nd[2], 0.5f,1f);
            indices.add(base); indices.add(base+1); indices.add(base+2);
        }

        return new ModelMesh(toFloatArray(verts), toIntArray(indices));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  STAND — low-poly flying saucer (disc + dome)
    //
    //  Topology: outer rim ring → inner rim ring → dome apex
    //  Color: steel-blue  (0.35, 0.70, 0.95, 1.0)
    // ─────────────────────────────────────────────────────────────────────────
    private static ModelMesh buildStandSaucer() {
        final int   N    = 12;     // sides
        final float Ro   = 0.55f;  // outer radius
        final float Ri   = 0.30f;  // inner radius
        final float Yt   = 0.12f;  // top of dome
        final float Yb   = -0.07f; // bottom of disc
        final float r = 0.35f, g = 0.70f, b = 0.95f, a = 1.0f;

        java.util.List<Float>   verts   = new java.util.ArrayList<>();
        java.util.List<Integer> indices = new java.util.ArrayList<>();

        float twoPi = (float)(2 * Math.PI);

        // Build flat-shaded: each quad/tri uses its own vertices
        for (int i = 0; i < N; i++) {
            float a0 = twoPi * i       / N;
            float a1 = twoPi * (i + 1) / N;
            float ox0 = Ro * (float)Math.cos(a0), oz0 = Ro * (float)Math.sin(a0);
            float ox1 = Ro * (float)Math.cos(a1), oz1 = Ro * (float)Math.sin(a1);
            float ix0 = Ri * (float)Math.cos(a0), iz0 = Ri * (float)Math.sin(a0);
            float ix1 = Ri * (float)Math.cos(a1), iz1 = Ri * (float)Math.sin(a1);

            // ── Top ring face (outer-bottom to inner-top slanted) ─────────────
            float[] nt = normal3(ox0,Yb,oz0, ox1,Yb,oz1, ix0,Yt,iz0);
            int base = verts.size() / ModelMesh.FLOATS_PER_VERTEX;
            addVertex(verts, ox0,Yb,oz0, r,g,b,a, nt[0],nt[1],nt[2], 0f,0f);
            addVertex(verts, ox1,Yb,oz1, r,g,b,a, nt[0],nt[1],nt[2], 1f,0f);
            addVertex(verts, ix1,Yt,iz1, r,g,b,a, nt[0],nt[1],nt[2], 1f,1f);
            addVertex(verts, ix0,Yt,iz0, r,g,b,a, nt[0],nt[1],nt[2], 0f,1f);
            indices.add(base); indices.add(base+1); indices.add(base+2);
            indices.add(base); indices.add(base+2); indices.add(base+3);

            // ── Dome cap triangle: inner-ring to dome apex ────────────────────
            float domeY  = Yt + 0.18f;
            float[] nd   = normal3(ix0,Yt,iz0, ix1,Yt,iz1, 0f,domeY,0f);
            base = verts.size() / ModelMesh.FLOATS_PER_VERTEX;
            addVertex(verts, ix0,Yt,iz0, r,g,b,a, nd[0],nd[1],nd[2], 0f,0f);
            addVertex(verts, ix1,Yt,iz1, r,g,b,a, nd[0],nd[1],nd[2], 1f,0f);
            addVertex(verts, 0f,domeY,0f, r,g,b,a, nd[0],nd[1],nd[2], 0.5f,1f);
            indices.add(base); indices.add(base+1); indices.add(base+2);

            // ── Bottom disc face (flat, pointing down) ────────────────────────
            float[] nb = {0f, -1f, 0f};
            base = verts.size() / ModelMesh.FLOATS_PER_VERTEX;
            addVertex(verts, 0f, Yb,0f,  r,g,b,a, nb[0],nb[1],nb[2], 0.5f,0.5f);
            addVertex(verts, ox1,Yb,oz1, r,g,b,a, nb[0],nb[1],nb[2], 0f,0f);
            addVertex(verts, ox0,Yb,oz0, r,g,b,a, nb[0],nb[1],nb[2], 1f,0f);
            indices.add(base); indices.add(base+1); indices.add(base+2);
        }

        return new ModelMesh(toFloatArray(verts), toIntArray(indices));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  PLAYER — simple capsule (cylinder body + dome head + flat feet)
    //
    //  Color: warm grey  (0.78, 0.72, 0.65, 1.0)
    // ─────────────────────────────────────────────────────────────────────────
    private static ModelMesh buildPlayerCapsule() {
        final int   N    = 8;     // polygon sides
        final float Rb   = 0.30f; // body radius
        final float Rh   = 0.22f; // head radius
        final float Ybot = 0.0f;  // feet
        final float Ymid = 1.5f;  // shoulders
        final float Ytop = 2.0f;  // top of head dome
        final float r = 0.78f, g = 0.72f, b = 0.65f, a = 1.0f;

        java.util.List<Float>   verts   = new java.util.ArrayList<>();
        java.util.List<Integer> indices = new java.util.ArrayList<>();

        float twoPi = (float)(2 * Math.PI);

        for (int i = 0; i < N; i++) {
            float a0 = twoPi * i       / N;
            float a1 = twoPi * (i + 1) / N;
            float bx0 = Rb * (float)Math.cos(a0), bz0 = Rb * (float)Math.sin(a0);
            float bx1 = Rb * (float)Math.cos(a1), bz1 = Rb * (float)Math.sin(a1);
            float hx0 = Rh * (float)Math.cos(a0), hz0 = Rh * (float)Math.sin(a0);
            float hx1 = Rh * (float)Math.cos(a1), hz1 = Rh * (float)Math.sin(a1);

            // ── Body quad (cylinder side) ─────────────────────────────────────
            float[] ns = normal3(bx0,Ybot,bz0, bx1,Ybot,bz1, bx0,Ymid,bz0);
            int base = verts.size() / ModelMesh.FLOATS_PER_VERTEX;
            addVertex(verts, bx0,Ybot,bz0, r,g,b,a, ns[0],ns[1],ns[2], 0f,0f);
            addVertex(verts, bx1,Ybot,bz1, r,g,b,a, ns[0],ns[1],ns[2], 1f,0f);
            addVertex(verts, bx1,Ymid,bz1, r,g,b,a, ns[0],ns[1],ns[2], 1f,1f);
            addVertex(verts, bx0,Ymid,bz0, r,g,b,a, ns[0],ns[1],ns[2], 0f,1f);
            indices.add(base); indices.add(base+1); indices.add(base+2);
            indices.add(base); indices.add(base+2); indices.add(base+3);

            // ── Bottom cap ────────────────────────────────────────────────────
            float[] nb = {0f,-1f,0f};
            base = verts.size() / ModelMesh.FLOATS_PER_VERTEX;
            addVertex(verts, 0f,Ybot,0f,  r,g,b,a, nb[0],nb[1],nb[2], 0.5f,0.5f);
            addVertex(verts, bx0,Ybot,bz0,r,g,b,a, nb[0],nb[1],nb[2], 0f,0f);
            addVertex(verts, bx1,Ybot,bz1,r,g,b,a, nb[0],nb[1],nb[2], 1f,0f);
            indices.add(base); indices.add(base+1); indices.add(base+2);

            // ── Head side quad ────────────────────────────────────────────────
            float Yneck = Ymid + 0.05f;
            float[] nh = normal3(hx0,Yneck,hz0, hx1,Yneck,hz1, hx0,Ytop - Rh,hz0);
            base = verts.size() / ModelMesh.FLOATS_PER_VERTEX;
            addVertex(verts, hx0,Yneck,hz0, r,g,b,a, nh[0],nh[1],nh[2], 0f,0f);
            addVertex(verts, hx1,Yneck,hz1, r,g,b,a, nh[0],nh[1],nh[2], 1f,0f);
            addVertex(verts, hx1,Ytop - Rh,hz1, r,g,b,a, nh[0],nh[1],nh[2], 1f,1f);
            addVertex(verts, hx0,Ytop - Rh,hz0, r,g,b,a, nh[0],nh[1],nh[2], 0f,1f);
            indices.add(base); indices.add(base+1); indices.add(base+2);
            indices.add(base); indices.add(base+2); indices.add(base+3);

            // ── Head dome cap ─────────────────────────────────────────────────
            float[] nd = normal3(hx0,Ytop-Rh,hz0, hx1,Ytop-Rh,hz1, 0f,Ytop,0f);
            base = verts.size() / ModelMesh.FLOATS_PER_VERTEX;
            addVertex(verts, hx0,Ytop-Rh,hz0, r,g,b,a, nd[0],nd[1],nd[2], 0f,0f);
            addVertex(verts, hx1,Ytop-Rh,hz1, r,g,b,a, nd[0],nd[1],nd[2], 1f,0f);
            addVertex(verts, 0f, Ytop,0f,    r,g,b,a, nd[0],nd[1],nd[2], 0.5f,1f);
            indices.add(base); indices.add(base+1); indices.add(base+2);
        }

        return new ModelMesh(toFloatArray(verts), toIntArray(indices));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  DEFAULT — 1×1×1 white cube (fallback for unknown names)
    // ─────────────────────────────────────────────────────────────────────────
    private static ModelMesh buildDefaultCube() {
        // 6 faces × 4 vertices × 12 floats
        float r = 1f, g = 0.4f, b = 0.8f, a = 1f; // hot-pink so it's obvious
        float H = 0.5f;
        // Positions for a unit cube centred at origin
        float[][] pos = {
            {-H,-H, H}, { H,-H, H}, { H, H, H}, {-H, H, H}, // front
            { H,-H,-H}, {-H,-H,-H}, {-H, H,-H}, { H, H,-H}, // back
            {-H,-H,-H}, {-H,-H, H}, {-H, H, H}, {-H, H,-H}, // left
            { H,-H, H}, { H,-H,-H}, { H, H,-H}, { H, H, H}, // right
            {-H, H, H}, { H, H, H}, { H, H,-H}, {-H, H,-H}, // top
            {-H,-H,-H}, { H,-H,-H}, { H,-H, H}, {-H,-H, H}, // bottom
        };
        float[][] nrms = {
            {0,0,1},{0,0,1},{0,0,1},{0,0,1},
            {0,0,-1},{0,0,-1},{0,0,-1},{0,0,-1},
            {-1,0,0},{-1,0,0},{-1,0,0},{-1,0,0},
            {1,0,0},{1,0,0},{1,0,0},{1,0,0},
            {0,1,0},{0,1,0},{0,1,0},{0,1,0},
            {0,-1,0},{0,-1,0},{0,-1,0},{0,-1,0},
        };
        float[][] uvs = {{0,0},{1,0},{1,1},{0,1}};

        java.util.List<Float>   verts   = new java.util.ArrayList<>();
        java.util.List<Integer> indices = new java.util.ArrayList<>();

        for (int f = 0; f < 6; f++) {
            int base = f * 4;
            for (int v = 0; v < 4; v++) {
                float[] p = pos[base + v];
                float[] n = nrms[base + v];
                float[] uv = uvs[v];
                addVertex(verts, p[0],p[1],p[2], r,g,b,a, n[0],n[1],n[2], uv[0],uv[1]);
            }
            int b2 = base;
            indices.add(b2);   indices.add(b2+1); indices.add(b2+2);
            indices.add(b2);   indices.add(b2+2); indices.add(b2+3);
        }
        return new ModelMesh(toFloatArray(verts), toIntArray(indices));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Normal helper (computes unit face normal from 3 CCW points)
    // ─────────────────────────────────────────────────────────────────────────
    private static float[] normal3(float x0, float y0, float z0,
                                    float x1, float y1, float z1,
                                    float x2, float y2, float z2) {
        float ax = x1-x0, ay = y1-y0, az = z1-z0;
        float bx = x2-x0, by = y2-y0, bz = z2-z0;
        float nx = ay*bz - az*by;
        float ny = az*bx - ax*bz;
        float nz = ax*by - ay*bx;
        float len = (float)Math.sqrt(nx*nx + ny*ny + nz*nz);
        if (len > 1e-6f) { nx/=len; ny/=len; nz/=len; }
        return new float[]{nx, ny, nz};
    }
}
