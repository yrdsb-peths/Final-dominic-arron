package com.leaf.game.anim;

import com.google.gson.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Converts a Blockbench project file (.bbmodel) into our {@link AnimModel} JSON.
 *
 * ── What it understands ────────────────────────────────────────────────────────
 *  • Bones (groups in the Blockbench "outliner")  → animatable transform nodes
 *  • Cubes (elements)                             → solid coloured boxes
 *  • Bone/cube rotation in the rest pose          → defaultR
 *  • Animations → rotation + position keyframes (degrees / blocks)
 *
 * ── Design ─────────────────────────────────────────────────────────────────────
 * Blockbench works in PIXELS where 16 px = 1 block. Our engine works in blocks,
 * so every length is divided by 16.
 *
 * Each Blockbench *group* becomes a zero-size "bone" PartDef whose origin is the
 * group's pivot — bones carry the animation. Each *cube* inside a group becomes a
 * separate geometry PartDef parented to that bone. This keeps the mapping simple
 * and correct no matter how many cubes a bone holds, and it means a bone's
 * rotation always pivots around the point you set in Blockbench.
 *
 * ── Coordinate maths (so future-me can follow it) ──────────────────────────────
 * Given our AnimPlayer transform  P(L) = origin + pivot + R·(L − pivot)  and our
 * BoxMesh which centres geometry at (−pivot):
 *   • a box's centre at rest sits at   origin − pivot
 *   • a part's rotation pivot sits at  origin + pivot
 * For a bone we want rotation about the group origin, so pivot = 0 and
 *   origin = (groupOrigin − parentGroupOrigin) / 16.
 * For a cube with no own rotation:  pivot = 0,  origin = (cubeCentre − groupOrigin)/16.
 * For a cube rotated about its own point C:
 *   pivot  = (C − cubeCentre) / 32
 *   origin = (cubeCentre + C) / 32  −  groupOrigin / 16
 *
 * ── CLI use ────────────────────────────────────────────────────────────────────
 *   java ...BlockbenchImporter  monster.bbmodel  [out.json]
 * If out.json is omitted it writes  src/main/resources/models/&lt;name&gt;.json
 */
public class BlockbenchImporter {

    /** 16 Blockbench pixels = 1 world block. */
    private static final float SCALE = 16f;

    /** The 8 Blockbench cube "marker" colours, approximated as solid RGB. */
    private static final float[][] MARKER = {
        {0.62f, 0.62f, 0.66f}, // 0 grey   (default)
        {0.86f, 0.36f, 0.36f}, // 1 red
        {0.90f, 0.62f, 0.32f}, // 2 orange
        {0.90f, 0.84f, 0.36f}, // 3 yellow
        {0.50f, 0.76f, 0.42f}, // 4 green
        {0.40f, 0.74f, 0.78f}, // 5 cyan
        {0.42f, 0.56f, 0.86f}, // 6 blue
        {0.72f, 0.46f, 0.82f}, // 7 purple
    };

    /** Conversion result + human-readable notes/warnings for the preview console. */
    public static class Result {
        public AnimModel model;
        public final List<String> warnings = new ArrayList<>();
        public final List<String> info     = new ArrayList<>();
    }

    // ── per-run state ────────────────────────────────────────────────────────
    private final Result res = new Result();
    private final AnimModel model = new AnimModel();
    private final Set<String> usedIds = new HashSet<>();
    private final Map<String, JsonObject> elements = new HashMap<>(); // cube uuid → cube
    private final Map<String, String> uuidToBone = new HashMap<>();   // group uuid → bone part id
    private boolean warnedCurve = false;

    private BlockbenchImporter(String name) { model.name = name; }

    // ── Public entry points ───────────────────────────────────────────────────

    public static Result importFile(String path) throws IOException {
        String json = Files.readString(Path.of(path));
        String fileName = Path.of(path).getFileName().toString();
        String base = fileName.toLowerCase().endsWith(".bbmodel")
                ? fileName.substring(0, fileName.length() - 8) : fileName;
        return importJson(json, sanitize(base));
    }

    public static Result importJson(String json, String modelName) {
        BlockbenchImporter imp = new BlockbenchImporter(modelName);
        imp.run(json);
        imp.res.model = imp.model;
        return imp.res;
    }

    // ── Conversion ─────────────────────────────────────────────────────────────

    private void run(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        // 1. Index every cube by uuid.
        if (root.has("elements")) {
            for (JsonElement el : root.getAsJsonArray("elements")) {
                JsonObject c = el.getAsJsonObject();
                if (c.has("uuid")) elements.put(c.get("uuid").getAsString(), c);
            }
        }
        res.info.add("Found " + elements.size() + " cube(s).");

        // 2. Walk the outliner (bone hierarchy). Top level parents = none.
        if (root.has("outliner")) {
            float[] worldOrigin = {0, 0, 0};
            for (JsonElement node : root.getAsJsonArray("outliner")) {
                processNode(node, null, worldOrigin);
            }
        } else {
            res.warnings.add("No 'outliner' found — model has no bones to convert.");
        }
        res.info.add("Created " + model.parts.size() + " part(s).");

        // 3. Animations.
        if (root.has("animations")) {
            for (JsonElement a : root.getAsJsonArray("animations")) {
                convertAnimation(a.getAsJsonObject());
            }
        }
        if (model.animations.isEmpty()) {
            res.info.add("No animations in file — added a static 'idle' pose.");
            AnimClip idle = new AnimClip("idle");
            idle.duration = 1f; idle.loop = true;
            model.animations.put("idle", idle);
        }
        res.info.add("Converted " + model.animations.size() + " animation(s).");
    }

    /** A node is either a group (JsonObject) or a bare cube uuid (JsonPrimitive). */
    private void processNode(JsonElement node, String parentBoneId, float[] parentOrigin) {
        if (node.isJsonObject()) {
            JsonObject g = node.getAsJsonObject();
            String name = g.has("name") ? g.get("name").getAsString() : "bone";
            String boneId = uniqueId(sanitize(name));
            if (g.has("uuid")) uuidToBone.put(g.get("uuid").getAsString(), boneId);

            float[] origin = arr3(g, "origin", new float[]{0, 0, 0});

            // Bone = zero-size transform node, rotates about the group origin.
            PartDef bone = new PartDef();
            bone.id = boneId;
            bone.parent = parentBoneId;
            bone.w = bone.h = bone.d = 0f;
            bone.ox = (origin[0] - parentOrigin[0]) / SCALE;
            bone.oy = (origin[1] - parentOrigin[1]) / SCALE;
            bone.oz = (origin[2] - parentOrigin[2]) / SCALE;
            if (g.has("rotation")) {
                float[] r = arr3el(g.get("rotation"), new float[]{0, 0, 0});
                bone.defaultRx = r[0]; bone.defaultRy = r[1]; bone.defaultRz = r[2];
            }
            model.parts.add(bone);

            // Children: nested groups recurse; cube uuids become geometry.
            if (g.has("children")) {
                int cubeIdx = 0;
                for (JsonElement child : g.getAsJsonArray("children")) {
                    if (child.isJsonPrimitive()) {
                        JsonObject cube = elements.get(child.getAsString());
                        if (cube != null) {
                            String cubeId = uniqueId(boneId + "_box" + (cubeIdx == 0 ? "" : cubeIdx));
                            addCubePart(cube, boneId, origin, cubeId);
                        }
                        cubeIdx++;
                    } else {
                        processNode(child, boneId, origin);
                    }
                }
            }
        } else if (node.isJsonPrimitive()) {
            // Loose cube not inside any group — attach to current parent bone.
            JsonObject cube = elements.get(node.getAsString());
            if (cube != null) {
                String nm = cube.has("name") ? cube.get("name").getAsString() : "cube";
                addCubePart(cube, parentBoneId, parentOrigin, uniqueId(sanitize(nm)));
            }
        }
    }

    private void addCubePart(JsonObject cube, String parentBoneId, float[] boneOrigin, String id) {
        float[] from = arr3(cube, "from", new float[]{0, 0, 0});
        float[] to   = arr3(cube, "to",   new float[]{0, 0, 0});
        float[] center = {(from[0] + to[0]) / 2f, (from[1] + to[1]) / 2f, (from[2] + to[2]) / 2f};
        float[] size   = {Math.abs(to[0] - from[0]), Math.abs(to[1] - from[1]), Math.abs(to[2] - from[2])};

        PartDef p = new PartDef();
        p.id = id;
        p.parent = parentBoneId;
        p.w = size[0] / SCALE;
        p.h = size[1] / SCALE;
        p.d = size[2] / SCALE;

        boolean rotated = cube.has("rotation") && !isZero(arr3el(cube.get("rotation"), new float[]{0, 0, 0}));
        if (rotated) {
            float[] r = arr3el(cube.get("rotation"), new float[]{0, 0, 0});
            float[] c = arr3(cube, "origin", center); // cube rotation point
            p.defaultRx = r[0]; p.defaultRy = r[1]; p.defaultRz = r[2];
            p.pivotX = (c[0] - center[0]) / (2f * SCALE);
            p.pivotY = (c[1] - center[1]) / (2f * SCALE);
            p.pivotZ = (c[2] - center[2]) / (2f * SCALE);
            p.ox = (center[0] + c[0]) / (2f * SCALE) - boneOrigin[0] / SCALE;
            p.oy = (center[1] + c[1]) / (2f * SCALE) - boneOrigin[1] / SCALE;
            p.oz = (center[2] + c[2]) / (2f * SCALE) - boneOrigin[2] / SCALE;
        } else {
            p.ox = (center[0] - boneOrigin[0]) / SCALE;
            p.oy = (center[1] - boneOrigin[1]) / SCALE;
            p.oz = (center[2] - boneOrigin[2]) / SCALE;
        }

        int ci = cube.has("color") ? cube.get("color").getAsInt() : 0;
        float[] col = MARKER[((ci % MARKER.length) + MARKER.length) % MARKER.length];
        p.cr = col[0]; p.cg = col[1]; p.cb = col[2]; p.ca = 1f;

        model.parts.add(p);
    }

    private void convertAnimation(JsonObject anim) {
        String name = anim.has("name") ? sanitize(anim.get("name").getAsString()) : "anim";
        name = uniqueAnimName(name);

        AnimClip clip = new AnimClip(name);
        clip.duration = anim.has("length") ? anim.get("length").getAsFloat() : 0f;
        String loopMode = anim.has("loop") ? anim.get("loop").getAsString() : "once";
        clip.loop = "loop".equalsIgnoreCase(loopMode);

        float maxT = 0f;

        if (anim.has("animators")) {
            for (Map.Entry<String, JsonElement> e : anim.getAsJsonObject("animators").entrySet()) {
                String boneId = uuidToBone.get(e.getKey());
                if (boneId == null) continue; // e.g. an "effects" animator — skip silently
                JsonObject animator = e.getValue().getAsJsonObject();
                if (!animator.has("keyframes")) continue;

                List<Keyframe> track = clip.getOrCreateTrack(boneId);
                for (JsonElement kfe : animator.getAsJsonArray("keyframes")) {
                    JsonObject kfo = kfe.getAsJsonObject();
                    String channel = kfo.has("channel") ? kfo.get("channel").getAsString() : "";
                    float t = kfo.has("time") ? kfo.get("time").getAsFloat() : 0f;
                    maxT = Math.max(maxT, t);
                    String easing = mapEasing(kfo);

                    float[] v = firstDataPoint(kfo);
                    Keyframe kf = new Keyframe(t);
                    kf.easing = easing;
                    if ("rotation".equals(channel)) {
                        kf.rx = v[0]; kf.ry = v[1]; kf.rz = v[2];
                    } else if ("position".equals(channel)) {
                        kf.tx = v[0] / SCALE; kf.ty = v[1] / SCALE; kf.tz = v[2] / SCALE;
                    } else if ("scale".equals(channel)) {
                        res.warnings.add("Animation '" + name + "': scale keyframes are not supported and were skipped.");
                        continue;
                    } else {
                        continue;
                    }
                    track.add(kf);
                }
            }
        }
        if (clip.duration <= 0f) clip.duration = Math.max(0.001f, maxT);
        clip.sortTracks();
        model.animations.put(name, clip);
    }

    // ── small helpers ────────────────────────────────────────────────────────

    private String mapEasing(JsonObject kf) {
        String interp = kf.has("interpolation") ? kf.get("interpolation").getAsString() : "linear";
        switch (interp) {
            case "step":   return "step";
            case "linear": return "linear";
            case "smooth": return "ease_in_out";
            case "catmullrom":
            case "bezier":
                if (!warnedCurve) {
                    res.warnings.add("Curved interpolation ('" + interp + "') was approximated as linear.");
                    warnedCurve = true;
                }
                return "linear";
            default: return "linear";
        }
    }

    private static float[] firstDataPoint(JsonObject kf) {
        float[] out = {0, 0, 0};
        if (kf.has("data_points")) {
            JsonArray dp = kf.getAsJsonArray("data_points");
            if (dp.size() > 0) {
                JsonObject p = dp.get(0).getAsJsonObject();
                out[0] = parseNum(p.get("x"));
                out[1] = parseNum(p.get("y"));
                out[2] = parseNum(p.get("z"));
            }
        }
        return out;
    }

    /** Data points may be numbers OR strings ("0", "1.5", or even math expressions). */
    private static float parseNum(JsonElement el) {
        if (el == null || el.isJsonNull()) return 0f;
        try {
            return el.getAsFloat();
        } catch (Exception ex) {
            try { return Float.parseFloat(el.getAsString().trim()); }
            catch (Exception ex2) { return 0f; } // expression we can't evaluate → 0
        }
    }

    private static float[] arr3(JsonObject o, String key, float[] def) {
        if (!o.has(key)) return def;
        return arr3el(o.get(key), def);
    }

    private static float[] arr3el(JsonElement el, float[] def) {
        if (el == null || !el.isJsonArray()) return def;
        JsonArray a = el.getAsJsonArray();
        if (a.size() < 3) return def;
        return new float[]{a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat()};
    }

    private static boolean isZero(float[] v) {
        return v[0] == 0f && v[1] == 0f && v[2] == 0f;
    }

    private static String sanitize(String s) {
        String out = s.toLowerCase().trim().replaceAll("[^a-z0-9_]+", "_").replaceAll("_+", "_");
        out = out.replaceAll("^_|_$", "");
        return out.isEmpty() ? "part" : out;
    }

    private String uniqueId(String base) {
        String id = base;
        int n = 2;
        while (usedIds.contains(id)) id = base + "_" + (n++);
        usedIds.add(id);
        return id;
    }

    private String uniqueAnimName(String base) {
        String id = base;
        int n = 2;
        while (model.animations.containsKey(id)) id = base + "_" + (n++);
        return id;
    }

    // ── CLI ────────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: BlockbenchImporter <input.bbmodel> [output.json]");
            return;
        }
        Result r = importFile(args[0]);
        String out = args.length >= 2 ? args[1]
                : "src/main/resources/models/" + r.model.name + ".json";
        r.model.saveToFile(out);

        for (String s : r.info)     System.out.println("  • " + s);
        for (String w : r.warnings) System.out.println("  ! " + w);
        System.out.println("Wrote " + out);
    }
}
