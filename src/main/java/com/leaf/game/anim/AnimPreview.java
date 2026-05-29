package com.leaf.game.anim;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Standalone animation sandbox — a tiny window that loads ONE model and lets you
 * orbit around it and flip through its animations. Completely isolated from the
 * game: no world, no networking, no audio. Perfect for checking a Blockbench
 * export before wiring it into the game.
 *
 * ── Run ────────────────────────────────────────────────────────────────────────
 *   ./preview.sh                      # previews the default model (enemy_basic)
 *   ./preview.sh my_monster.bbmodel   # imports a Blockbench file and previews it
 *   ./preview.sh enemy_basic          # previews a model already in resources/models
 *   ./preview.sh path/to/model.json   # previews an AnimModel json on disk
 *
 * ── Controls ───────────────────────────────────────────────────────────────────
 *   Left-drag .... orbit the camera
 *   Scroll ....... zoom in / out
 *   ← / → ........ previous / next animation
 *   Space ........ pause / resume
 *   R ............ reload from disk (re-imports .bbmodel too) — live editing!
 *   F ............ re-frame the camera on the model
 *   Esc .......... quit
 */
public class AnimPreview {

    private long window;
    private int winW = 1100, winH = 760;

    // Source we (re)load from.
    private final String sourceArg;

    // Loaded data.
    private AnimModel model;
    private AnimPlayer player;
    private final List<String> animNames = new ArrayList<>();
    private int animIndex = 0;
    private boolean paused = false;

    // Orbit camera.
    private final Vector3f target = new Vector3f(0, 1, 0);
    private float yaw = 0.6f, pitch = 0.3f, distance = 6f;

    // Mouse drag state.
    private boolean dragging = false;
    private double lastX, lastY;

    public AnimPreview(String sourceArg) {
        this.sourceArg = (sourceArg == null || sourceArg.isBlank()) ? "enemy_basic" : sourceArg;
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    public void run() {
        printControls();
        initWindow();
        GL.createCapabilities();
        ModelRenderer.init();

        glClearColor(0.13f, 0.15f, 0.19f, 1f);
        glEnable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);

        loadModel();

        double last = glfwGetTime();
        while (!glfwWindowShouldClose(window)) {
            double now = glfwGetTime();
            float dt = (float) (now - last);
            last = now;

            if (player != null && !paused) player.tick(dt);
            renderFrame();

            glfwSwapBuffers(window);
            glfwPollEvents();
        }

        ModelRenderer.cleanup();
        glfwDestroyWindow(window);
        glfwTerminate();
    }

    private void initWindow() {
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) throw new IllegalStateException("Unable to initialize GLFW");

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE,               GLFW_TRUE);
        glfwWindowHint(GLFW_RESIZABLE,             GLFW_TRUE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE,        GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);

        window = glfwCreateWindow(winW, winH, "AnimPreview", NULL, NULL);
        if (window == NULL) throw new RuntimeException("Failed to create preview window");

        glfwSetFramebufferSizeCallback(window, (w, width, height) -> {
            winW = Math.max(1, width); winH = Math.max(1, height);
            glViewport(0, 0, winW, winH);
        });

        glfwSetKeyCallback(window, (w, key, sc, action, mods) -> {
            if (action != GLFW_PRESS && action != GLFW_REPEAT) return;
            switch (key) {
                case GLFW_KEY_ESCAPE -> glfwSetWindowShouldClose(window, true);
                case GLFW_KEY_RIGHT  -> cycleAnim(+1);
                case GLFW_KEY_LEFT   -> cycleAnim(-1);
                case GLFW_KEY_SPACE  -> { paused = !paused; updateTitle(); }
                case GLFW_KEY_R      -> { loadModel(); System.out.println("[reload] " + sourceArg); }
                case GLFW_KEY_F      -> frameCamera();
            }
        });

        glfwSetMouseButtonCallback(window, (w, button, action, mods) -> {
            if (button == GLFW_MOUSE_BUTTON_LEFT) {
                dragging = (action == GLFW_PRESS);
                double[] x = new double[1], y = new double[1];
                glfwGetCursorPos(window, x, y);
                lastX = x[0]; lastY = y[0];
            }
        });

        glfwSetCursorPosCallback(window, (w, x, y) -> {
            if (dragging) {
                yaw   += (float) (x - lastX) * 0.01f;
                pitch += (float) (y - lastY) * 0.01f;
                pitch = Math.max(-1.5f, Math.min(1.5f, pitch));
            }
            lastX = x; lastY = y;
        });

        glfwSetScrollCallback(window, (w, dx, dy) -> {
            distance *= (dy > 0) ? 0.9f : 1.1f;
            distance = Math.max(0.5f, Math.min(60f, distance));
        });

        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);
        int[] fw = new int[1], fh = new int[1];
        glfwGetFramebufferSize(window, fw, fh);
        glViewport(0, 0, fw[0], fh[0]);
    }

    // ── Model loading ────────────────────────────────────────────────────────

    private void loadModel() {
        if (model != null) ModelRenderer.invalidateModel(model);

        AnimModel loaded = resolveAndLoad(sourceArg);
        if (loaded == null) {
            System.err.println("[AnimPreview] Could not load: " + sourceArg);
            if (model == null) {
                // Fall back to an empty placeholder so the window still runs.
                loaded = new AnimModel("empty");
            } else {
                return; // keep the previously loaded model
            }
        }

        model = loaded;
        player = new AnimPlayer(model);

        animNames.clear();
        animNames.addAll(model.animations.keySet());
        animIndex = 0;

        frameCamera();
        if (!animNames.isEmpty()) {
            player.play(animNames.get(0));
        }
        updateTitle();
    }

    private AnimModel resolveAndLoad(String arg) {
        String lower = arg.toLowerCase();
        try {
            if (lower.endsWith(".bbmodel")) {
                BlockbenchImporter.Result r = BlockbenchImporter.importFile(arg);
                for (String s : r.info)     System.out.println("  • " + s);
                for (String w : r.warnings) System.out.println("  ! " + w);
                return r.model;
            }
            if (lower.endsWith(".json")) {
                return AnimModel.loadFromFile(arg);
            }
            // Bare name: try classpath, then disk.
            AnimModel m = AnimModel.loadFromClasspath(arg);
            if (m != null) return m;
            return AnimModel.loadFromFile("src/main/resources/models/" + arg + ".json");
        } catch (Exception e) {
            System.err.println("[AnimPreview] Load error: " + e.getMessage());
            return null;
        }
    }

    private void cycleAnim(int dir) {
        if (animNames.isEmpty() || player == null) return;
        animIndex = (animIndex + dir + animNames.size()) % animNames.size();
        player.play(animNames.get(animIndex));
        paused = false;
        updateTitle();
    }

    // ── Camera framing ───────────────────────────────────────────────────────

    /** Auto-fit the orbit camera to the model's rest-pose bounding box. */
    private void frameCamera() {
        if (model == null || model.parts.isEmpty()) {
            target.set(0, 1, 0); distance = 6f; return;
        }
        // Rest pose: a fresh player with no clip playing returns default transforms.
        Map<String, Matrix4f> pose = new AnimPlayer(model).getPose();

        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        boolean any = false;

        for (PartDef p : model.parts) {
            if (p.w <= 0 && p.h <= 0 && p.d <= 0) continue; // transform-only bone
            Matrix4f m = pose.getOrDefault(p.id, new Matrix4f());
            float hw = p.w * 0.5f, hh = p.h * 0.5f, hd = p.d * 0.5f;
            // BoxMesh centres geometry at (-pivot).
            float cx = -p.pivotX, cy = -p.pivotY, cz = -p.pivotZ;
            for (int sx = -1; sx <= 1; sx += 2)
                for (int sy = -1; sy <= 1; sy += 2)
                    for (int sz = -1; sz <= 1; sz += 2) {
                        Vector4f v = new Vector4f(cx + sx * hw, cy + sy * hh, cz + sz * hd, 1f);
                        v.mul(m);
                        minX = Math.min(minX, v.x); maxX = Math.max(maxX, v.x);
                        minY = Math.min(minY, v.y); maxY = Math.max(maxY, v.y);
                        minZ = Math.min(minZ, v.z); maxZ = Math.max(maxZ, v.z);
                        any = true;
                    }
        }
        if (!any) { target.set(0, 1, 0); distance = 6f; return; }

        target.set((minX + maxX) / 2f, (minY + maxY) / 2f, (minZ + maxZ) / 2f);
        float size = Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ));
        distance = Math.max(1.5f, size * 2.2f);
    }

    // ── Render ─────────────────────────────────────────────────────────────────

    private void renderFrame() {
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        if (model == null || player == null) return;

        Vector3f eye = new Vector3f(
                (float) (Math.cos(pitch) * Math.sin(yaw)),
                (float) Math.sin(pitch),
                (float) (Math.cos(pitch) * Math.cos(yaw)))
                .mul(distance).add(target);

        Matrix4f view = new Matrix4f().lookAt(eye, target, new Vector3f(0, 1, 0));
        float aspect = (float) winW / Math.max(1, winH);
        Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(50f), aspect, 0.05f, 200f);

        ModelRenderer.render(model, player.getPose(), new Matrix4f(), view, proj);
    }

    // ── UI text (window title — no font rendering needed) ──────────────────────

    private void updateTitle() {
        if (window == NULL) return;
        String anim = animNames.isEmpty() ? "(none)"
                : animNames.get(animIndex) + "  [" + (animIndex + 1) + "/" + animNames.size() + "]";
        String state = paused ? "  ⏸ PAUSED" : "";
        String name = model != null ? model.name : "?";
        glfwSetWindowTitle(window, "AnimPreview  —  " + name + "  —  anim: " + anim + state);
    }

    private void printControls() {
        System.out.println("""
            ┌──────────────────────────────────────────────┐
            │  AnimPreview                                   │
            ├──────────────────────────────────────────────┤
            │  Left-drag : orbit       Scroll : zoom         │
            │  ← / →      : switch animation                 │
            │  Space      : pause/resume                     │
            │  R          : reload from disk (live editing)  │
            │  F          : re-frame camera                  │
            │  Esc        : quit                             │
            └──────────────────────────────────────────────┘""");
    }

    // ── Entry point ──────────────────────────────────────────────────────────

    public static void main(String[] args) {
        new AnimPreview(args.length > 0 ? args[0] : null).run();
    }
}
