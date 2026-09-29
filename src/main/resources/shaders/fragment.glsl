#version 330 core
in vec4  vertexColor;
in vec3  vertexNormal;
in float vWorldY;        // world-space Y (unused after abyss fog removed; kept for ABI compat)
in vec2  vertexUV;       // texture coordinates (zero when not a ModelMesh)
in vec3  vWorldPos;      // full world-space position (orbital scan)
uniform vec3  skyHorizonCol;
uniform vec3  skyZenithCol;
uniform float sunsetFactor;
uniform float fogEnd;

uniform vec3  sunDirection;
uniform float sunStrength;
uniform float ambientStrength;
uniform vec3  sunColor;       // day/night light tint (warm noon → orange sunset → blue moon)
uniform vec3  ambientColor;   // ambient sky-bounce tint

// ── TORCH POINT LIGHTS (placed torches + held hand-light) ─────────────────────
#define MAX_TORCH 12
uniform int   torchCount;
uniform vec3  torchPos[MAX_TORCH];   // world-space positions
uniform vec3  torchCol[MAX_TORCH];   // colour × intensity
uniform float torchRad[MAX_TORCH];   // reach in blocks
uniform int   isUnderwater;

// ── ORBITAL ANNIHILATION: lidar/topographic scan + environmental flash ────────
// An expanding wavefront that traces the 90° voxel edges in pitch darkness, plus
// a global white flash when the orbital laser strikes. All additive emission.
uniform int   orbActive;      // 1 = scan live
uniform vec3  orbEpicenter;   // world-space epicentre
uniform float orbRadius;      // current wavefront radius (world units, XZ)
uniform float orbWidth;       // wavefront band thickness
uniform float orbIntensity;   // emissive gain
uniform float orbFlash;       // environmental white flash (laser impact)

// ── TIME STOP: "negative film" domain (DIO's The World) ───────────────────────
uniform int   tsActive;       // 1 = domain live
uniform vec3  tsCenter;       // world-space domain centre (player's feet)
uniform float tsRadius;       // current domain radius (world units)
uniform float tsEdge;         // boundary fade / ring thickness

// ── RADAR SCOPE: range rings + bearing spokes + rotating sweep on the terrain ──
uniform int   vlActive;       // 1 = radar live
uniform vec3  vlCenter;       // scope origin (player position)
uniform float vlRadius;       // scope radius (world units)
uniform float vlAmount;       // 0→1→0 fade-in/out envelope
uniform float vlSweep;        // current sweep-arm angle (radians)

// ── DEPRIVATION DOMAIN (Water God Stance) ─────────────────────────────────────
// Golden absolute-defence hemisphere. Inside: hyper-real desaturated gold tint.
// Outside: cooler, darker — the world beyond the domain is less real.
// Domain boundary glows bright HDR gold (bloom picks this up as a searing ring).
uniform int   depActive;      // 1 = domain live
uniform vec3  depCenter;      // player's locked world position (feet)
uniform float depRadius;      // domain detection + visual radius (blocks)
uniform float depStrike;      // 0→1 strike flash intensity (decays ~6×/sec)
uniform float depSweep;       // current radius of the repeating detection sweep ring

// ── DEPRIVATION DOME force-field: a hex energy shield rendered on a solid
//    hemisphere. domeMode==1 ⇒ ignore lighting and output the field pattern. ──
uniform int   domeMode;       // 1 = drawing the force-field hemisphere
uniform vec3  domeCenter;     // hemisphere centre (player feet)
uniform float domeTime;       // animation clock (seconds)
uniform vec3  camPos;         // camera world position (for the fresnel rim)

// ── TEXTURE SAMPLING ──────────────────────────────────────────────────────────
// Set useTexture = 1 and bind a texture to unit 0 to enable texture sampling.
// Set useTexture = 0 (default) to use pure vertex colour — all existing Mesh
// rendering is unaffected because this uniform defaults to 0.
uniform sampler2D texSampler;
uniform int       useTexture;   // 0 = vertex colour only, 1 = texture × vertex colour

// ── TIME DILATION VIGNETTE ────────────────────────────────────────────────────
uniform float timeVignetteStrength;
uniform vec3  timeVignetteColor;

// ── ABILITY OVERLAY VIGNETTE ──────────────────────────────────────────────────
// Used by dash (cyan), cannonball (orange), rewind (blue), blink (white flash).
// Set by Window.java from player.abilities.getOverlayStrength/Color().
uniform float overlayVignetteStrength;
uniform vec3  overlayVignetteColor;

// ── SNOW BIOME ATMOSPHERE ─────────────────────────────────────────────────────
uniform float snowAtmosphereStrength;  // 0=none; driven by altitude in Window.java

// ── SCREEN DESATURATION (ScreenEffectManager) ─────────────────────────────────
uniform float desaturate;   // 0=colour, 1=greyscale

// ── GHOST TRAIL TRANSPARENCY ──────────────────────────────────────────────────
// Multiplied into vertexColor.a when rendering ghost/trail meshes.
// Window.java sets this per-draw-call (0.05–0.40) then resets to 1.0.
uniform float alphaMultiplier;

// ── PORTAL RENDERING ─────────────────────────────────────────────────────────
// When portalMode == 1 the fragment samples texSampler using screen-space UVs
// (gl_FragCoord / viewportSize) and returns immediately.  This displays the
// FBO colour texture on a portal quad with perfect alignment.
// portalMode == 0 (default) → normal lit rendering, no change.
uniform int   portalMode;
uniform vec2  viewportSize;   // FBO dimensions (set to PORTAL_FBO_W/H)

// ── EMISSIVE (unlit) PASS ─────────────────────────────────────────────────────
// Used by the Orbital Annihilation 3D effect meshes (rings, core, beams, embers).
// When emissiveMode == 1 the fragment ignores ALL lighting/fog/scan and outputs
// vertexColor × emissiveTint directly, so the geometry glows at full intensity in
// the blackout and the bloom pass picks it up.
uniform int  emissiveMode;
uniform int  fxSoft;         // 0 flat, 1 soft core orb, 2 fresnel shell (see orbDrawSoft)
uniform vec3 emissiveTint;   // per-object colour × intensity (additive-bloomed)

// ── SUN / MOON SHADOWS (ShadowMap.java) ────────────────────────────────────────
uniform sampler2DShadow shadowMap;
uniform mat4  lightVP;
uniform int   shadowOn;
uniform float shadowTexel;   // world size of one shadow texel (for the normal-offset bias)

// ── TRUE WORLD POSITION (from depth) ──────────────────────────────────────────
// Many meshes (viewmodels, markers, tracers) are drawn in LOCAL space, so
// vWorldPos for them is near the origin — which used to drop them into the
// underground "abyss fog" and render them black. Reconstructing the position
// from the depth buffer is exact for every mesh, whatever space it was built in.
uniform mat4 invViewProj;
uniform vec2 screenSize;
vec3 fragWorldPos() {
    vec3 ndc = vec3(gl_FragCoord.xy / screenSize, gl_FragCoord.z) * 2.0 - 1.0;
    vec4 p = invViewProj * vec4(ndc, 1.0);
    return p.xyz / p.w;
}

// ── WORLD CLOCK (seconds) — animates water ripples, lava flow, crystal glints ──
uniform float uTime;

out vec4 FragColor;

// ═════════════════════════════════════════════════════════════════════════════
//  PROCEDURAL SURFACES — pixel-art detail for blocks that have no PNG texture.
//  The chunk mesher packs Block.surfaceStyle() into a NEGATIVE vertexUV.x
//  (u = -(style + 1.5)), so textured blocks and every non-terrain mesh (u >= 0)
//  are untouched. Keep these ids in sync with Block.STYLE_*.
// ═════════════════════════════════════════════════════════════════════════════
const int STYLE_ROCK    = 0;
const int STYLE_SOFT    = 1;
const int STYLE_FOLIAGE = 2;
const int STYLE_WOOD    = 3;
const int STYLE_WATER   = 4;
const int STYLE_GLOW    = 5;
const int STYLE_CRYSTAL = 6;
const int STYLE_PLAIN   = 7;
const int STYLE_BIOLUM  = 8;
const int STYLE_PLANT   = 9;
const int STYLE_FLOWER  = 10;
const int STYLE_GLOWPLANT = 11;

float hash13(vec3 p) {
    p  = fract(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return fract((p.x + p.y) * p.z);
}
float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}
float vnoise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash12(i),               hash12(i + vec2(1, 0)), f.x),
               mix(hash12(i + vec2(0, 1)),  hash12(i + vec2(1, 1)), f.x), f.y);
}
// In-plane 2D coordinates of a voxel face (so patterns run along the face).
vec2 facePlane(vec3 wp, vec3 n) {
    vec3 a = abs(n);
    if (a.y > 0.5) return wp.xz;
    if (a.x > 0.5) return vec2(wp.z, wp.y);
    return vec2(wp.x, wp.y);
}

// Albedo multiplier for a procedural style. Pixels are 1/16 block, snapped in
// 3D *inside* the block so neighbouring faces of one block agree at the edges.
vec3 surfaceDetail(int style, vec3 wp, vec3 n) {
    vec3  px    = floor((wp - n * 0.01) * 16.0);           // 16×16 texels per face
    float g     = hash13(px);                              // per-texel grain
    vec2  fp    = facePlane(wp, n) * 16.0;
    float blot  = vnoise(floor(fp / 3.0) + hash13(floor(wp - n * 0.01)) * 17.0);

    if (style == STYLE_ROCK) {
        float m = 0.84 + 0.16 * g + 0.16 * (blot - 0.5);
        if (g > 0.955) m *= 0.78;                          // dark mineral fleck
        if (g < 0.030) m *= 1.12;                          // bright speck
        return vec3(m);
    }
    if (style == STYLE_SOFT) {
        float m = 0.90 + 0.12 * g + 0.06 * (blot - 0.5);
        if (n.y > 0.5 && g > 0.94) m *= 1.10;             // sun-catching grains on top faces
        return vec3(m);
    }
    if (style == STYLE_FOLIAGE) {
        // Pixel-snapped leaf clumps: bright tips, darker gaps, per-texel grain.
        float c = vnoise(floor(fp) / 2.5 + hash13(floor(wp - n * 0.01)) * 11.0);
        float m = 0.80 + 0.28 * c + 0.10 * g;
        if (c < 0.28) m *= 0.82;                           // shadowed gaps between leaf clumps
        return vec3(m) * mix(vec3(1.0), vec3(1.03, 1.05, 0.95), step(0.72, c)); // sunlit clump tips
    }
    if (style == STYLE_WOOD) {
        if (abs(n.y) > 0.5) {                              // end grain: growth rings
            vec2  c = fract(wp.xz) - 0.5;
            float r = floor(length(c) * 16.0);
            float ring = step(0.5, fract(r * 0.5));
            return vec3(0.80 + 0.14 * ring + 0.06 * g) * (length(c) > 0.42 ? 0.80 : 1.0);
        }
        vec2  fpl  = facePlane(wp, n);
        float col  = floor(fpl.x * 16.0);                  // vertical bark grooves
        float grv  = hash12(vec2(col, floor(wp.x + wp.z)));
        float m    = 0.78 + 0.24 * grv + 0.06 * g;
        if (grv < 0.18) m *= 0.78;
        return vec3(m);
    }
    if (style == STYLE_CRYSTAL) {
        vec2  fpl = facePlane(wp, n);
        float diag = fract((fpl.x + fpl.y) * 1.5);         // facet bands
        return vec3(0.88 + 0.16 * smoothstep(0.35, 0.5, diag) * smoothstep(0.65, 0.5, diag) + 0.05 * g);
    }
    if (style == STYLE_GLOW) {
        return vec3(0.85 + 0.15 * g);
    }
    if (style == STYLE_BIOLUM) {                           // mushroom cap: pale spots on the cap
        float spot = step(0.80, vnoise(floor(fp) / 3.0 + hash13(floor(wp - n * 0.01)) * 7.0));
        return vec3(0.86 + 0.10 * g) + spot * 0.45;
    }
    return vec3(1.0);                                      // WATER / PLAIN
}

// 0 = fully shadowed, 1 = fully lit. Normal-offset + 3×3 PCF on top of the
// hardware 2×2 compare gives soft, acne-free edges. Fades out at the map border.
float sunShadow(vec3 N, vec3 L) {
    if (shadowOn == 0) return 1.0;
    float ndl = max(dot(N, L), 0.0);
    vec3  p   = fragWorldPos() + N * shadowTexel * (1.2 + 1.8 * (1.0 - ndl));
    vec4  lp  = lightVP * vec4(p, 1.0);
    vec3  sc  = lp.xyz / lp.w * 0.5 + 0.5;
    if (sc.z >= 1.0) return 1.0;
    vec2  ts  = 1.0 / vec2(textureSize(shadowMap, 0));
    float s   = 0.0;
    for (int i = -1; i <= 1; i++)
        for (int j = -1; j <= 1; j++)
            s += texture(shadowMap, vec3(sc.xy + vec2(i, j) * ts * 1.25, sc.z - 0.0004));
    s /= 9.0;
    float edge = max(abs(sc.x - 0.5), abs(sc.y - 0.5));
    return mix(s, 1.0, smoothstep(0.40, 0.49, edge));
}

// Filmic shoulder that matches the old pow(1/1.2) curve in darks + mids but
// rolls highlights off softly instead of clipping snow & sand to flat white.
vec3 filmic(vec3 c) {
    c = max(c, 0.0);
    // Tone-map LUMINANCE (keeps hue + saturation: pink blossoms stay pink) ...
    float L  = dot(c, vec3(0.2126, 0.7152, 0.0722));
    float Lt = 1.0 - exp(-L * 1.8);
    vec3  byLum = c * (Lt / max(L, 1e-4));
    // ... but fall back to per-channel as a channel nears white, so very bright
    // saturated light still desaturates toward white like real film.
    vec3  perCh = 1.0 - exp(-c * 1.8);
    float hot   = smoothstep(0.75, 1.25, max(byLum.r, max(byLum.g, byLum.b)));
    return mix(byLum, perCh, hot);
}

void main() {
    // ── DEPRIVATION DOME force-field (hex energy shield) ──────────────────────
    if (domeMode == 1) {
        vec3 nrm     = normalize(vWorldPos - domeCenter);
        vec3 viewDir = normalize(camPos - vWorldPos);
        float fres   = pow(1.0 - abs(dot(nrm, viewDir)), 3.0);   // bright at the silhouette rim

        // Hex grid in (azimuth, latitude) space, scrolling slowly upward.
        float u = atan(nrm.z, nrm.x);
        float v = acos(clamp(nrm.y, -1.0, 1.0));                 // 0 pole → ~1.57 base (ground)
        vec2  huv = vec2(u * 2.6, (v - domeTime * 0.10) * 5.2);
        vec2  rr = vec2(1.0, 1.7320508);
        vec2  hh = rr * 0.5;
        vec2  pa = mod(huv, rr) - hh;
        vec2  pb = mod(huv - hh, rr) - hh;
        vec2  gv = dot(pa, pa) < dot(pb, pb) ? pa : pb;
        vec2  ag = abs(gv);
        float hd = max(dot(ag, normalize(vec2(1.0, 1.7320508))), ag.x);  // 0 centre → ~0.5 edge
        // THICK, solid hexagon outline (a wide bright band along each cell edge).
        float line = smoothstep(0.32, 0.46, hd);
        // Bright ring where the dome meets the ground (so it clearly "ends at the ground").
        float baseRing = smoothstep(1.40, 1.54, v);

        float pulse = 0.75 + 0.25 * sin(v * 6.0 - domeTime * 2.0);
        vec3  gold  = vec3(1.30, 0.92, 0.30);
        // A SOLID hex wireframe cage: thick glowing cell edges + a ground ring, fully
        // transparent between the lines (no fill → not a screen-filter you sit inside).
        float bright = line * 1.5 * pulse        // the solid hexagon wireframe
                     + baseRing * 2.2            // ground ring
                     + fres * 0.5                // faint silhouette so the dome reads
                     + depStrike * 1.0;
        if (bright <= 0.04) discard;                     // truly see-through between the lines
        FragColor = vec4(gold * bright, 1.0);
        return;
    }

    // ── FBO PORTAL PASSTHROUGH ────────────────────────────────────────────────
    if (portalMode == 1) {
        vec2 screenUV = gl_FragCoord.xy / viewportSize;
        FragColor = texture(texSampler, screenUV);
        return;
    }

    // ── EMISSIVE (unlit) ──────────────────────────────────────────────────────
    if (emissiveMode == 1) {
        vec3 em = vertexColor.rgb * emissiveTint;
        if (fxSoft == 1) {            // soft glowing orb: hot core, edges dissolve
            float facing = abs(dot(normalize(vertexNormal), normalize(camPos - vWorldPos)));
            em *= facing * facing * (0.35 + 0.65 * facing);
        } else if (fxSoft == 2) {     // fresnel shell: bright silhouette, clear centre
            float facing = abs(dot(normalize(vertexNormal), normalize(camPos - vWorldPos)));
            em *= pow(1.0 - facing, 2.0) * 1.6 + 0.05;
        }
        FragColor = vec4(em, 1.0);
        return;
    }

    vec3  N       = normalize(vertexNormal);
    vec3  L       = normalize(sunDirection);
    vec3  WP      = fragWorldPos();          // true world position (see fragWorldPos)
    vec3  V       = normalize(camPos - WP);
    float diffuse = max(0.0, dot(N, L));

    // ── HEMISPHERE AMBIENT + GROUND BOUNCE ────────────────────────────────────
    // Up-facing surfaces see the whole sky dome; walls see half of it plus light
    // bounced off the sunlit ground; undersides see mostly bounce. This is what
    // keeps shadowed faces readable (they used to fall to near-black navy).
    float skyW    = 0.5 + 0.5 * N.y;
    vec3  skyAmb  = mix(ambientColor, skyZenithCol * 1.1, 0.30);
    vec3  ambient = ambientStrength * skyAmb * mix(0.90, 1.40, skyW);
    vec3  bounce  = sunColor * vec3(1.0, 0.94, 0.84) * (sunStrength * 0.34) * (1.0 - skyW);
    float shadow  = diffuse > 0.0 ? sunShadow(N, L) : 0.0;
    // Bounce light comes off *sunlit* ground, so it dims a little inside big shadows too.
    vec3  lit     = ambient + bounce * (0.55 + 0.45 * shadow) + sunColor * (sunStrength * diffuse * shadow);

    // ── Torch point lights — warm pools of light that pop at night ─────────────
    for (int i = 0; i < torchCount; i++) {
        vec3  d    = torchPos[i] - vWorldPos;
        float dist = length(d);
        float att  = clamp(1.0 - dist / torchRad[i], 0.0, 1.0);
        att = att * att;                                   // soft quadratic falloff
        float ndl  = max(0.0, dot(N, d / max(dist, 0.001)));
        lit += torchCol[i] * att * (0.35 + 0.65 * ndl);
    }

    // ── Base colour: atlas texture, procedural surface, or plain vertex colour ─
    int   style    = -1;                                   // -1 = not a procedural surface
    vec4  baseColor;
    if (useTexture == 1 && vertexUV.x < 0.0) {
        style     = int(floor(-vertexUV.x)) - 1;
        if (style >= STYLE_PLANT && style <= STYLE_GLOWPLANT) {
            // ── Cut-out pixel blades on a crossed quad ────────────────────────
            float s01 = clamp((fract(-vertexUV.x) - 0.05) / 0.9, 0.0, 1.0);
            float v01 = vertexUV.y;
            float px  = floor(s01 * 12.0), py = floor(v01 * 12.0);
            // Per-plant constant (the mesher's per-block shade lives in the colour),
            // so the blade pattern never shifts as the quad sways across cells.
            float plantId = dot(vertexColor.rgb, vec3(911.0, 523.0, 347.0));
            float seed = hash12(vec2(px * 1.37 + plantId, plantId * 0.13));
            float bladeTop = 0.30 + 0.70 * seed;                   // per-column blade height
            bool  gap = hash12(vec2(px + 7.0, plantId)) < 0.28;
            vec3  green = vec3(0.30, 0.58, 0.20);
            vec3  c;
            if (style == STYLE_FLOWER) {
                // single stem in the middle two columns, a 4×3 head on top
                // diamond-shaped bloom (Manhattan distance from the head centre)
                float hd   = abs(px - 5.5) + abs(py - 9.5);
                bool  head = hd <= 2.6;
                bool  eye  = hd <= 0.6;                               // darker centre
                bool  stem = (px == 5.0 || px == 6.0) && py < 9.0;
                bool  leaf = (px == 3.0 || px == 4.0 || px == 7.0 || px == 8.0)
                             && py >= 3.0 + abs(px - 5.5) * 0.5 && py < 4.5 + abs(px - 5.5) * 0.5;
                if (!(stem || head || leaf)) discard;
                c = head ? (eye ? vertexColor.rgb * 0.45 + vec3(0.12, 0.08, 0.0)
                                : vertexColor.rgb * (1.02 - 0.08 * hd / 2.6))
                         : green * (0.75 + 0.25 * v01);
            } else {
                if (gap || v01 > bladeTop) discard;
                float tip = smoothstep(bladeTop - 0.25, bladeTop, v01);
                c = vertexColor.rgb * (0.55 + 0.45 * v01) * (0.9 + 0.2 * seed) + vec3(0.05, 0.07, 0.0) * tip;
            }
            baseColor = vec4(c, 1.0);
        } else {
            baseColor = vec4(vertexColor.rgb * surfaceDetail(style, vWorldPos, N), vertexColor.a);
        }
    } else if (useTexture == 1) {
        vec4 texColor = texture(texSampler, vertexUV);
        baseColor = texColor * vertexColor;
    } else {
        baseColor = vertexColor;
    }

    // ── Gentle per-face-axis shading: keeps block edges crisp even under flat
    //    overcast light. (Softer than before — the hemisphere term does most of it.)
    float faceShade;
    if      (N.y >  0.5) faceShade = 1.00;
    else if (N.y < -0.5) faceShade = 0.80;
    else                 faceShade = mix(0.88, 0.94, abs(N.z));
    lit *= faceShade;

    vec3 gammaCorrected = filmic(baseColor.rgb * lit);
    float outAlpha = baseColor.a;

    // ── WATER: ripples, fresnel sky reflection, sun glint ──────────────────────
    if (style == STYLE_WATER && N.y > 0.5) {
        vec2  wp2 = vWorldPos.xz;
        float t   = uTime;
        float r1  = vnoise(wp2 * 0.9 + vec2(t * 0.35, t * 0.22));
        float r2  = vnoise(wp2 * 2.3 - vec2(t * 0.50, -t * 0.31));
        vec3  Np  = normalize(vec3((r1 - 0.5) * 0.35 + (r2 - 0.5) * 0.18, 1.0,
                                   (r2 - 0.5) * 0.35 - (r1 - 0.5) * 0.18));
        float fres = 0.08 + 0.92 * pow(1.0 - max(dot(Np, V), 0.0), 5.0);
        vec3  R    = reflect(-V, Np);
        vec3  refl = mix(skyHorizonCol, skyZenithCol, smoothstep(0.0, 0.6, R.y));
        float spec = pow(max(dot(R, L), 0.0), 180.0) * sunStrength;
        gammaCorrected  = mix(gammaCorrected, refl * 1.05, clamp(fres * 0.85, 0.0, 0.85));
        gammaCorrected += sunColor * spec * 2.4;           // HDR glint → bloom
        gammaCorrected += vec3(0.05, 0.10, 0.12) * (r1 * r2) * (ambientStrength + sunStrength); // caustic shimmer
        outAlpha = mix(baseColor.a, 0.95, fres);
    }

    // ── GLOW: lava, magma & glowcaps are self-lit and breathe ──────────────────
    if (style == STYLE_GLOW) {
        vec2  fp   = floor(facePlane(vWorldPos, N) * 16.0) / 16.0;   // pixel-snapped flow
        float flow = vnoise(fp * 1.6 + vec2(uTime * 0.25, -uTime * 0.18));
        float crust = smoothstep(0.30, 0.55, vnoise(fp * 3.0 - uTime * 0.05));
        float pulse = 0.85 + 0.15 * sin(uTime * 1.7 + flow * 6.2831);
        vec3  hot   = vertexColor.rgb * (0.9 + 0.9 * flow) * pulse;
        // cooler crust patches on top, bright molten seams between them
        gammaCorrected = mix(hot * 1.35, gammaCorrected * 0.9 + hot * 0.35, crust * 0.55);
    }

    // ── BIOLUMINESCENCE: lit normally by day; the darker it gets, the more the
    //    caps glow on their own (HDR at night so the bloom haloes them). ────────
    if (style == STYLE_BIOLUM) {
        float dark  = clamp(1.0 - (sunStrength * 0.9 + ambientStrength), 0.0, 1.0);
        float pulse = 0.85 + 0.15 * sin(uTime * 1.3 + dot(floor(vWorldPos), vec3(1.7, 2.3, 3.1)));
        gammaCorrected += baseColor.rgb * (0.18 + 1.10 * dark) * pulse;
    }

    if (style == STYLE_GLOWPLANT) {
        float dark = clamp(1.0 - (sunStrength * 0.9 + ambientStrength), 0.0, 1.0);
        gammaCorrected += baseColor.rgb * (0.15 + 1.2 * dark) * vertexUV.y;
    }

    // ── CRYSTAL: an inner gleam + rare twinkles so geodes sparkle at night ─────
    if (style == STYLE_CRYSTAL) {
        float fres  = pow(1.0 - max(dot(N, V), 0.0), 3.0);
        vec3  px    = floor((vWorldPos - N * 0.01) * 16.0);
        // Twinkles only come out in the dark (by day they read as dead pixels).
        float dark  = clamp(1.0 - (sunStrength * 0.9 + ambientStrength), 0.0, 1.0);
        float twk   = step(0.996, hash13(px + floor(uTime * 3.0))) * dark;
        gammaCorrected += vertexColor.rgb * (0.10 + 0.35 * fres) + vec3(1.2) * twk;
    }

    // ── UNDERWATER FOG ────────────────────────────────────────────────────────
    if (isUnderwater == 1) {
        float depthDist    = gl_FragCoord.z / gl_FragCoord.w;
        float fogFactor    = 1.0 - exp(-depthDist * 0.15);
        vec3  waterFogColor = vec3(0.05, 0.20, 0.55);
        gammaCorrected     = mix(gammaCorrected, waterFogColor,
                                 clamp(fogFactor + 0.3, 0.0, 1.0));
    }

    // ── SNOW BIOME ATMOSPHERE ─────────────────────────────────────────────────
    if (snowAtmosphereStrength > 0.001) {
        // Darken the haze with the sky's light level so mountains don't glow pale
        // white at night (it used to be a fixed bright colour at all hours).
        float skyLight = clamp(ambientStrength * 1.5 + sunStrength * 0.8, 0.06, 1.0);
        vec3 snowAtmColor = vec3(0.68, 0.82, 0.96) * skyLight;
        gammaCorrected = mix(gammaCorrected, snowAtmColor, snowAtmosphereStrength);
    }

    // ── TIME SCALE VIGNETTE ───────────────────────────────────────────────────
    if (timeVignetteStrength > 0.001) {
        gammaCorrected = mix(gammaCorrected, timeVignetteColor, timeVignetteStrength);
    }

    // ── ABILITY OVERLAY VIGNETTE ──────────────────────────────────────────────
    // Applied after time vignette so abilities visually "win" during activation.
    if (overlayVignetteStrength > 0.001) {
        gammaCorrected = mix(gammaCorrected, overlayVignetteColor, overlayVignetteStrength);
    }

    // ── IMPACT DESATURATION (explosions, slow-mo, near-death) ─────────────────
    if (desaturate > 0.001) {
        float lum = dot(gammaCorrected, vec3(0.299, 0.587, 0.114));
        gammaCorrected = mix(gammaCorrected, vec3(lum), desaturate);
    }

    // ── ORBITAL "LIDAR" SCAN (additive — traces voxel edges in the dark) ──────
    if (orbActive == 1) {
        vec3  wp    = vWorldPos;
        vec3  dEdge = 0.5 - abs(fract(wp) - 0.5);      // 0 at a block boundary, 0.5 at centre
        vec3  nA    = abs(normalize(vertexNormal));    // the face's normal axis
        float lw    = 0.07;                            // wireframe line thickness (world units)
        // Lines on the two IN-PLANE axes of this face (exclude the normal axis,
        // which is always sitting exactly on a boundary).
        float lx = (1.0 - nA.x) * (1.0 - smoothstep(0.0, lw, dEdge.x));
        float ly = (1.0 - nA.y) * (1.0 - smoothstep(0.0, lw, dEdge.y));
        float lz = (1.0 - nA.z) * (1.0 - smoothstep(0.0, lw, dEdge.z));
        float wire = clamp(lx + ly + lz, 0.0, 1.0);    // bright on every 90° block edge

        // Expanding wavefront ring on the XZ plane — sweeps out across the terrain.
        float dist   = length(wp.xz - orbEpicenter.xz);
        float ring   = 1.0 - smoothstep(0.0, orbWidth, abs(dist - orbRadius));
        // Faint persistent contour map left behind the wavefront.
        float inside = (dist < orbRadius) ? (0.30 * (1.0 - dist / max(orbRadius, 0.001))) : 0.0;
        float mask   = max(ring, inside);

        // Green wireframe, white-hot at the wavefront.
        vec3 scanCol  = mix(vec3(0.15, 1.0, 0.35), vec3(1.0, 1.0, 1.0), ring);
        vec3 emission = scanCol * (wire * mask) * orbIntensity;
        // The leading edge sears even across flat faces.
        emission += vec3(0.6, 1.0, 0.7) * (ring * ring) * 0.6 * orbIntensity;

        gammaCorrected += emission;
    }

    // ── ENVIRONMENTAL FLASH — the laser impact lights up the dead world ───────
    if (orbFlash > 0.001) {
        gammaCorrected += vec3(0.85, 1.0, 0.9) * orbFlash;
    }

    // ── TIME STOP — expanding photographic-negative domain, shifted to DIO blue ──
    if (tsActive == 1) {
        float d = length(vWorldPos - tsCenter);          // 3D sphere from the feet
        // Inversion fades in from the surface inward so the boundary sweeps colour.
        float invAmt = smoothstep(tsRadius, tsRadius - tsEdge, d);   // 1 inside → 0 at surface
        if (invAmt > 0.001) {
            vec3  inv = vec3(1.0) - gammaCorrected;       // photographic negative
            vec3  neg = inv * vec3(0.60, 0.85, 1.40);     // shove toward cyan/electric blue
            float lum = dot(inv, vec3(0.299, 0.587, 0.114));
            neg = mix(neg, vec3(lum) * vec3(0.45, 0.70, 1.35), 0.30);  // 30% toward blue-mono
            gammaCorrected = mix(gammaCorrected, neg, invAmt);
        }
        // Searing electric-blue boundary ring riding the domain surface.
        float ring = 1.0 - smoothstep(0.0, tsEdge * 1.5, abs(d - tsRadius));
        gammaCorrected += vec3(0.30, 0.70, 1.0) * ring * 1.8;
    }

    // ── RADAR SCOPE — projected onto the real terrain so it hugs the 3D world ──
    // The ground becomes a dark-green radar scope with range rings + bearing
    // spokes + a bright outer ring, and a rotating sweep arm whose afterglow
    // fades opaque→transparent behind it, "pinging" the terrain as it passes.
    if (vlActive == 1) {
        vec2  rel  = vWorldPos.xz - vlCenter.xz;
        float dist = length(rel);
        float inside = 1.0 - smoothstep(vlRadius - 2.0, vlRadius + 1.0, dist);
        float amt = vlAmount * inside;
        if (amt > 0.001) {
            float ang = atan(rel.y, rel.x);            // bearing of this fragment

            // Dark radar-scope base (keeps a hint of terrain shading).
            float lum   = dot(gammaCorrected, vec3(0.299, 0.587, 0.114));
            vec3  scope = vec3(0.0, 0.045, 0.02) + vec3(0.0, 0.16, 0.06) * lum;

            // Range rings every 12 blocks.
            float rn   = dist / 12.0;
            float ring = 1.0 - smoothstep(0.0, 0.05, abs(rn - floor(rn + 0.5)));
            // Bearing spokes — 12 of them (every 30°).
            float an   = ang / 0.5235988;
            float spoke= 1.0 - smoothstep(0.0, 0.04, abs(an - floor(an + 0.5)));
            // Bright outer boundary ring.
            float outer= 1.0 - smoothstep(0.0, 2.0, abs(dist - vlRadius));

            // Rotating sweep + trailing afterglow (opaque at the arm → 0 behind it).
            float behind = mod(vlSweep - ang, 6.2831853);     // angle behind the arm
            float sweep  = 1.0 - smoothstep(0.0, 1.7, behind); // ~97° afterglow
            sweep *= sweep;
            float arm    = 1.0 - smoothstep(0.0, 0.045, behind); // razor leading edge

            vec3 grid = vec3(0.10, 1.0, 0.35);
            vec3 radar = scope;
            radar += grid * (ring * 0.35 + spoke * 0.28 + outer * 0.8);
            radar += grid * sweep * 0.45;                       // the ping wash
            radar += vec3(0.3, 1.0, 0.45) * arm * 0.7;          // sweep line (green, not white)

            gammaCorrected = mix(gammaCorrected, radar, amt);
        }
    }

    // ── DEPRIVATION DOMAIN: special "absolute domain" lighting + colour grade ──
    // Inside = desaturated gold (hyper-real standoff) with every voxel EDGE traced
    // in glowing gold (the world looks razor-cut and lethal); a gold detection ring
    // sweeps outward on repeat; surfaces facing the player catch a gold sheen.
    // Boundary = searing HDR gold ring. Outside = cooler, distant.
    if (depActive == 1) {
        float d3d    = length(vWorldPos - depCenter);
        float inside = 1.0 - smoothstep(depRadius - 1.5, depRadius + 0.5, d3d);

        // Inside: only a GENTLE warm tint — the dome wireframe (drawn separately)
        // carries the shape, so we must NOT wash the whole view gold here.
        float lum   = dot(gammaCorrected, vec3(0.299, 0.587, 0.114));
        vec3 desat  = mix(gammaCorrected, vec3(lum * 0.92), inside * 0.22);
        vec3 goldIn = mix(desat, desat * vec3(1.18, 1.04, 0.78), inside * 0.20);

        // Outside: cool blue-grey tint — the world beyond feels muted and distant
        float outside = smoothstep(depRadius - 2.0, depRadius + 2.0, d3d);
        vec3 coolOut  = gammaCorrected * mix(vec3(1.0), vec3(0.78, 0.82, 0.92), outside * 0.48);

        // Boundary ring: HDR gold burst — bloom turns this into a glowing edge halo
        float edge    = smoothstep(depRadius - 3.5, depRadius - 0.5, d3d)
                      * smoothstep(depRadius + 1.0, depRadius - 1.0, d3d);
        vec3 edgeGlow = vec3(3.2, 2.3, 0.55) * edge;

        gammaCorrected = mix(coolOut, goldIn, inside) + edgeGlow;

        // ── Golden voxel-edge rim: trace the 90° block edges inside the domain ──
        // (Same edge test as the orbital lidar.) Edges flare on every strike.
        vec3  dE  = 0.5 - abs(fract(vWorldPos) - 0.5);
        vec3  nAx = abs(normalize(vertexNormal));
        float elw = 0.06;
        float wEx = (1.0 - nAx.x) * (1.0 - smoothstep(0.0, elw, dE.x));
        float wEy = (1.0 - nAx.y) * (1.0 - smoothstep(0.0, elw, dE.y));
        float wEz = (1.0 - nAx.z) * (1.0 - smoothstep(0.0, elw, dE.z));
        float wire = clamp(wEx + wEy + wEz, 0.0, 1.0);
        float rimI = inside * (0.16 + 0.95 * depStrike);   // subtle idle; flares on the cut
        gammaCorrected += vec3(1.50, 1.05, 0.30) * wire * rimI;

        // ── Repeating detection sweep ring expanding from the player ──
        float sweepRing = 1.0 - smoothstep(0.0, 1.6, abs(d3d - depSweep));
        float sweepFade = 1.0 - depSweep / max(depRadius, 0.001);   // dim as it nears the edge
        gammaCorrected += vec3(1.30, 0.95, 0.28) * sweepRing * sweepFade * inside * 0.70;

        // ── Radial domain light: surfaces facing the player catch a gold sheen ──
        vec3  toCtr  = normalize(depCenter - vWorldPos);
        float facing = max(0.0, dot(normalize(vertexNormal), toCtr));
        gammaCorrected += vec3(0.90, 0.65, 0.18) * facing * inside * 0.18;

        // Strike flash: white-gold pulse searing through the entire scene
                 if (depStrike > 0.001) {
                     gammaCorrected += vec3(2.5, 1.9, 0.6) * depStrike * 0.38;
                 }
             }

    // ── AERIAL PERSPECTIVE + DISTANCE FOG ─────────────────────────────────────
    // Distant terrain fades into a sky-coloured haze (lifted toward the sun), so
    // ridgelines stack into layered silhouettes instead of all reading at the
    // same contrast. The last 30% of the render distance then fades fully into
    // the sky, hiding chunk edges. Emissive effects and the dome are exempt.
    if (emissiveMode == 0 && domeMode == 0 && isUnderwater == 0) {
        float distToCam = length(WP - camPos);
        vec3  ray       = normalize(WP - camPos);

        // Reconstruct the sky colour behind this pixel.
        float t = smoothstep(0.0, 0.55, ray.y);
        vec3 skyCol = mix(skyHorizonCol, skyZenithCol, t);
        float band = exp(-abs(ray.y) * 5.5) * sunsetFactor;
        skyCol = mix(skyCol, vec3(1.0, 0.48, 0.26), band * 0.75);

        // Sun in-scatter: haze glows warmer when looking toward the sun.
        float sunAmt = pow(max(dot(ray, normalize(sunDirection)), 0.0), 6.0);
        vec3  hazeCol = skyCol + sunColor * sunAmt * sunStrength * 0.20;

        // Deep underground: the haze turns to pitch-black abyss instead of blue sky.
        float depthFactor = smoothstep(190.0, 130.0, min(WP.y, camPos.y));
        vec3  abyssFogCol = vec3(0.012, 0.006, 0.022);
        skyCol  = mix(skyCol,  abyssFogCol, depthFactor);
        hazeCol = mix(hazeCol, abyssFogCol, depthFactor);

        // Height-weighted haze: valleys hold more of it than peaks.
        float heightK = exp(-max(WP.y - 200.0, 0.0) * 0.012);
        float haze    = (1.0 - exp(-distToCam * 0.0085 * (0.55 + 0.45 * heightK))) * 0.80;
        gammaCorrected = mix(gammaCorrected, hazeCol, haze);

        float fogFactor = smoothstep(fogEnd * 0.70, fogEnd, distToCam);
        gammaCorrected  = mix(gammaCorrected, skyCol, fogFactor);
    }

    FragColor = vec4(gammaCorrected, outAlpha * alphaMultiplier);
}
