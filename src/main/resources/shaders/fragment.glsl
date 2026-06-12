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
uniform vec3 emissiveTint;   // per-object colour × intensity (additive-bloomed)

out vec4 FragColor;

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
        FragColor = vec4(vertexColor.rgb * emissiveTint, 1.0);
        return;
    }

    float diffuse = max(0.0, dot(normalize(vertexNormal), normalize(sunDirection)));
    // Coloured lighting: ambient sky-bounce + tinted directional luminary (sun/moon).
    vec3 lit = ambientColor * ambientStrength + sunColor * (sunStrength * diffuse);

    // ── Torch point lights — warm pools of light that pop at night ─────────────
    vec3 nN = normalize(vertexNormal);
    for (int i = 0; i < torchCount; i++) {
        vec3  d    = torchPos[i] - vWorldPos;
        float dist = length(d);
        float att  = clamp(1.0 - dist / torchRad[i], 0.0, 1.0);
        att = att * att;                                   // soft quadratic falloff
        float ndl  = max(0.0, dot(nN, d / max(dist, 0.001)));
        lit += torchCol[i] * att * (0.35 + 0.65 * ndl);
    }

    // ── Base colour: vertex colour or texture × vertex colour ─────────────────
    vec4 baseColor;
    if (useTexture == 1) {
        vec4 texColor = texture(texSampler, vertexUV);
        baseColor = texColor * vertexColor;
    } else {
        baseColor = vertexColor;
    }

    // ── CONSTANT PER-FACE SHADING ("cheap AO") ────────────────────────────────
    // Voxel terrain reads flat when the sun isn't at a strong angle, because every
    // visible face gets nearly the same light. A fixed brightness per face *axis*
    // (top brightest, bottom darkest, X-sides darker than Z-sides) gives blocks
    // constant form and depth independent of the sun — the classic Minecraft-style
    // "smooth lighting" look, but per-face and free. Tune the four constants to taste.
    {
        vec3  nF = normalize(vertexNormal);
        float faceShade;
        if      (nF.y >  0.5) faceShade = 1.00;   // top    — full light
        else if (nF.y < -0.5) faceShade = 0.62;   // bottom — darkest (underside / overhangs)
        else                  faceShade = mix(0.80, 0.90, abs(nF.z));  // sides: ±X darker than ±Z
        lit *= faceShade;
    }

    vec3 color          = baseColor.rgb * lit;
    vec3 gammaCorrected = pow(clamp(color, 0.0, 1.0), vec3(1.0 / 1.2));

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

             // ── DISTANCE FOG (Seamless horizon blending) ──────────────────────────────
             // Do not apply fog to emissive volumetric effects (like the orbital laser)
             if (emissiveMode == 0 && domeMode == 0) {
                 float distToCam = length(vWorldPos - camPos);

                 // Start fading at 55% of the render distance, completely hidden at 100%
                 float fogStart = fogEnd * 0.85;
                 float fogFactor = smoothstep(fogStart, fogEnd, distToCam);

                 if (fogFactor > 0.001) {
                     vec3 ray = normalize(vWorldPos - camPos);

                     // Reconstruct the exact sky background color behind this pixel
                     float t = smoothstep(0.0, 0.55, ray.y);
                     vec3 skyCol = mix(skyHorizonCol, skyZenithCol, t);

                     // Add the warm sunset band if looking toward the sun
                     float band = exp(-abs(ray.y) * 5.5) * sunsetFactor;
                     skyCol = mix(skyCol, vec3(1.0, 0.48, 0.26), band * 0.75);

                     // Deep underground fix: if the player or terrain is deep down,
                     // fade the fog to pitch-black abyss color instead of blue sky!
                     float depthFactor = smoothstep(190.0, 130.0, min(vWorldPos.y, camPos.y));
                     vec3 abyssFogCol = vec3(0.012, 0.006, 0.022);
                     skyCol = mix(skyCol, abyssFogCol, depthFactor);

                     // Apply the atmospheric fade
                     gammaCorrected = mix(gammaCorrected, skyCol, fogFactor);
                 }
             }

             FragColor = vec4(gammaCorrected, baseColor.a * alphaMultiplier);
         }