# Art & Animation Guide

A practical walkthrough for adding models, textures, animations, and screen design to the game. Covers the current asset pipeline and the planned extension points for animation and UI polish.

---

## 1. Adding a 3D Model (OBJ)

### How the pipeline works

`AssetManager.get().getModel("name")` does the following in order:

1. Looks for `/models/name.obj` on the classpath (inside `src/main/resources/models/`)
2. If not found, falls back to a procedural mesh built in Java

So to replace the procedural saucer/seal/capsule with a real model, you just drop an OBJ file in the right place and it auto-loads.

### Step-by-step

1. **Export from Blender** (or any modeller)
   - Select your mesh → File → Export → Wavefront (.obj)
   - Keep geometry simple: 200–2 000 triangles is a healthy budget per model
   - **UV-unwrap before export** if you want textures (see section 2)
   - Triangulate: Mesh → Faces → Triangulate Faces before export, or tick "Triangulate Mesh" in the export dialog
   - Export with normals (`vn`) — the loader uses them for lighting

2. **Place the file**
   ```
   src/main/resources/models/seal.obj     ← replaces procedural seal
   src/main/resources/models/stand.obj    ← replaces procedural saucer
   src/main/resources/models/player.obj   ← replaces procedural capsule (also used for enemies)
   src/main/resources/models/enemy.obj    ← add a dedicated enemy model (then change AssetManager call)
   ```

3. **Add a new named model** — pick any key string and add a fallback in `AssetManager.buildProcedural()`:
   ```java
   case "pillar": return buildProceduralPillar(); // your new procedural or just return buildDefault()
   ```
   Then call `AssetManager.get().getModel("pillar")` from Window.

4. **Tinting** — `ObjLoader.load(path, r, g, b, a)` bakes a solid colour into all vertex colours. This is how enemies get red-tinted versions of the player model. To load a white (untinted) model that relies fully on texture colour use `r=g=b=a=1f`.

### OBJ limitations (current loader)

The loader handles `v / vt / vn / f` and fan-triangulates polygons. It does **not** handle:
- Multi-material OBJ files (`.mtl` is ignored — textures are done separately, see section 2)
- Smooth shading groups (`s`)
- Per-face normals calculated from groups rather than averaged per vertex

If your model looks faceted, export with "per-face normals" turned off and smooth-shade it in Blender first.

---

## 2. Adding a Texture (PNG)

### How the pipeline works

`AssetManager.get().getTexture("name")` tries `/textures/name.png`. Returns `null` on miss (rendering falls back to vertex colour).

The shader already supports textures: set `useTexture = 1` and bind the texture to unit 0 before calling `model.render()`. The colour is `texColor * vertexColor`, so a white vertex colour lets the texture show at full fidelity.

### Step-by-step

1. **Paint/export your texture** — PNG, any power-of-two resolution (128×128, 256×256, 512×512 etc). The game runs on 3.3 core so non-power-of-two works but is slightly less compatible across older hardware.

2. **Drop it in**
   ```
   src/main/resources/textures/seal.png
   src/main/resources/textures/stand.png
   src/main/resources/textures/player.png
   src/main/resources/textures/enemy.png
   ```

3. **UV map your model** — In Blender, UV-unwrap before export. The OBJ loader reads `vt` UV coordinates and passes them to `ModelMesh` attribute location 3. The vertex shader already passes `aUV → vertexUV` to the fragment shader.

4. **Texture tips for the current aesthetic**
   - Keep textures hand-painted and slightly stylised — smooth gradients don't read well on low-poly geometry
   - Use a dark outline baked into the UV map to fake toon shading without extra shader work
   - The `density3DAmplitude`, cave fog, and abyss darkness in the shader all darken deep areas — avoid very dark texture bases or they'll disappear underground

### Rendering the texture at runtime (custom code)

```java
Texture myTex = AssetManager.get().getTexture("mymodel");
if (myTex != null) {
    shader.setUniform("useTexture", 1);
    myTex.bind();            // binds to GL_TEXTURE0 by default in Texture.java
}
AssetManager.get().getModel("mymodel").render();
if (myTex != null) {
    shader.setUniform("useTexture", 0);
}
```

Always reset `useTexture` to 0 after — every other Mesh render call assumes vertex colour only.

---

## 3. Animation

There is no animation system yet. Here is the planned architecture, starting from simplest to most powerful.

### Option A — Transform animation (no rigging, good for abilities)

This is what's already happening for the stand bob and seal spin. You drive a float value each frame and build the `Matrix4f` from it.

```java
// In Window.java rendering block:
float t = (float)glfwGetTime();
Matrix4f model = new Matrix4f()
    .translate(pos.x, pos.y + 0.3f * Math.sin(t * 3f), pos.z)  // bob
    .rotateY(t * 2f)                                             // spin
    .scale(0.5f);
```

Good for: idle animations, floating objects, projectiles, particle-like effects.

### Option B — Keyframe animation (bone-free, for rigid models)

Store a list of `AnimFrame` — each frame is a position/rotation/scale and a time. Lerp between adjacent frames each tick.

```java
public class AnimFrame {
    public float time;
    public Vector3f pos, scale;
    public org.joml.Quaternionf rotation;
}

// Lerp between frames:
float t = (currentTime % totalDuration);
int i = findFrameIndex(t);
float blend = (t - frames[i].time) / (frames[i+1].time - frames[i].time);
Vector3f interPos = new Vector3f(frames[i].pos).lerp(frames[i+1].pos, blend);
```

Good for: cutscenes, triggered events, scripted story moments, simple enemy behaviours.

### Option C — Skeletal / bone animation (full character animation)

The longer-term approach for enemies and the player. Needs:

1. **Bone weights baked into vertices** — a new vertex attribute `boneIndex` (int) and `boneWeight` (float). ModelMesh gets two new attribute locations (4 and 5).
2. **Bone transform palette** — an array of `Matrix4f` uploaded as a uniform array (`mat4 boneTransforms[MAX_BONES]`).
3. **Vertex shader** — `gl_Position = boneTransforms[boneIndex] * vec4(aPos, 1.0)` (simplified single-weight; full skeletal uses up to 4 bone influences per vertex blended by weight).
4. **Animation clips** — per-clip arrays of bone transforms sampled at e.g. 24 fps, loaded from a custom binary format or GLTF.

This is a meaningful addition — budget 1–2 weeks if you go this route. The good news is everything else (textures, lighting, HUD) stays the same.

**Recommended path**: Start with Option A for all abilities and effects. Add Option B for story cutscenes when you need them. Add Option C when you want the player and enemies to have walk/run/attack cycles.

---

## 4. Screen Design & UI Art Style

### Current rendering layer

All 2D HUD elements are drawn via ImGui's `ImDrawList` (`getForegroundDrawList()` / `getBackgroundDrawList()`). This gives access to:
- `addLine`, `addRect`, `addRectFilled`, `addCircle`, `addCircleFilled`
- `addTriangle`, `addTriangleFilled`, `addQuad`, `addQuadFilled`
- `addText` (uses the default ImGui font — see below for custom fonts)
- Per-call RGBA colour as a packed `int` from `colorConvertFloat4ToU32`

### Custom fonts

ImGui supports loading TTF/OTF fonts at init time. Drop a font in `src/main/resources/fonts/` and load it in `init()`:

```java
// Inside init(), before ImGui.createContext():
ImGui.createContext();
ImFontAtlas fonts = ImGui.getIO().getFonts();
fonts.addFontFromFileTTF("src/main/resources/fonts/MyFont.ttf", 18.0f);
fonts.build();
// Then push/pop the font around draw calls:
ImGui.pushFont(myFont);
draw.addText(...);
ImGui.popFont();
```

For a strong art style: pick one display font (bold, geometric, slightly runic) for labels and ability names, and one readable font at small sizes for distance indicators and HP numbers.

### Texture-based HUD sprites

For icons, portraits, or decorative frames, load PNGs as `Texture` objects and draw them via ImGui's image API:

```java
Texture icon = AssetManager.get().getTexture("seal_icon");
if (icon != null) {
    ImGui.image(icon.getId(), iconSize, iconSize);
    // Or on a DrawList (screen-space positioned):
    draw.addImage(icon.getId(), x, y, x + iconSize, y + iconSize);
}
```

This lets you replace the current rectangle-block ability icons with hand-drawn sprites while keeping all the logic the same.

### Vignette and post-process style

The fragment shader already has named uniform slots for:
- `timeVignetteStrength / timeVignetteColor` — slow/fast time
- `overlayVignetteStrength / overlayVignetteColor` — ability activations
- `alphaMultiplier` — ghost/transparency rendering

To add a new persistent ambient vignette (e.g. slightly darkened edges at all times for a cinematic look), add a new uniform `vignetteEdge` and in the fragment shader compute it from the fragment's NDC coordinate — this costs essentially nothing.

### Recommended direction for your open-world / story art style

Given what you described (infinite world, story locked behind progress, strong combat feel):

- **Dark, high-contrast palette** — the world already has the abyss darkness and cave fog; lean into it. Use bright ability colours (cyan seals, gold stand, purple void shard) against a muted world.
- **Minimal HUD by default** — only show ability pips, HP, and seal count. Everything else fades unless relevant. The "less is more on screen, more happens in world" principle.
- **Story UI as world overlays** — for unlocks and story beats, consider projecting text and markers into 3D space (using the same NDC projection technique already used for the stand master marker and enemy HP bars) rather than flat menus. Feels more immersive.
- **Ability-specific screen distortion** — the overlay vignette already does colour wash; a future step is adding a `distortionStrength` uniform that offsets UV sampling in the fragment shader for a brief screen warp on big hits.

---

## 5. Quick Reference: Asset Checklist

| Asset | File location | Format | Notes |
|-------|--------------|--------|-------|
| 3D model | `resources/models/name.obj` | OBJ | Triangulated, with `vn` normals |
| Texture | `resources/textures/name.png` | PNG | Power-of-two preferred; UV-mapped |
| Font | `resources/fonts/name.ttf` | TTF/OTF | Load in `init()` via ImFontAtlas |
| HUD icon | `resources/textures/name_icon.png` | PNG | Any size; drawn with `draw.addImage` |
| Sound (future) | `resources/sounds/name.ogg` | OGG/WAV | No audio system yet; hook in OpenAL |
