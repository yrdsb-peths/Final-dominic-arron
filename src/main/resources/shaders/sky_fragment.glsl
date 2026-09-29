#version 330 core
in  vec2 TexCoord;
out vec4 FragColor;

uniform mat4  invViewProj;
uniform vec3  sunDir;
uniform vec3  moonDir;
uniform vec3  skyZenith;
uniform vec3  skyHorizon;
uniform float dayFactor;
uniform float nightFactor;
uniform float sunsetFactor;
uniform float moonPhaseAngle;
uniform float moonBrightLimbAngle;
uniform float time;
uniform float moonBrightness;
uniform float auroraStrength;
uniform float lunarEclipseFactor;

// Milky Way orientation
uniform vec3 milkyWayAxis;
uniform vec3 milkyWayCentre;

// Deep-sky objects
uniform vec3 nebulaDir_M42;
uniform vec3 nebulaDir_M31;
uniform vec3 nebulaDir_M8;
uniform vec3 nebulaDir_EtaCar;

// ─────────────────────────────────────────────────────────────────────────────
// NOISE
// ─────────────────────────────────────────────────────────────────────────────
float hash2(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }

float vnoise(vec2 p) {
    vec2 i = floor(p); vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash2(i),         hash2(i+vec2(1,0)), f.x),
               mix(hash2(i+vec2(0,1)), hash2(i+vec2(1,1)), f.x), f.y);
}

float fbm(vec2 p) {
    float v = 0.0, a = 0.5;
    for (int i = 0; i < 5; i++) { v += a * vnoise(p); p *= 2.1; a *= 0.5; }
    return v;
}

// ─────────────────────────────────────────────────────────────────────────────
// STAR NEST VOLUMETRIC GALAXY (CLAMPED SINGULARITIES)
// ─────────────────────────────────────────────────────────────────────────────
vec3 milkyWay(vec3 ray, float nightAmt) {
    if (nightAmt < 0.05) return vec3(0.0);

    // Safety check: if uniforms aren't uploaded yet, skip rendering
    if (dot(milkyWayAxis, milkyWayAxis) < 0.1) return vec3(0.0);

    // Build perfect galactic coordinate frame
    vec3 Z = normalize(milkyWayAxis);
    vec3 X = normalize(milkyWayCentre);
    vec3 Y = normalize(cross(Z, X));
    X = cross(Y, Z);

    // Project ray into galactic frame
    vec3 g = vec3(dot(ray, X), dot(ray, Y), dot(ray, Z));

    // Calculate galactic latitude to constrain the volume to a band
    float lat = asin(clamp(g.z, -1.0, 1.0));
    float lon = atan(g.y, g.x);

    float bandFade = exp(-lat * lat / 0.06);

    // Early exit
    if (bandFade < 0.01) return vec3(0.0);

    vec3 dir = g;

    // Drifting slowly over time to animate the dust clouds smoothly
    vec3 from = vec3(1.0, 0.5, 0.5) + vec3(time * 0.002, time * 0.001, -time * 0.0015);

    int iterations = 13;
    int volsteps = 12;
    float formuparam = 0.53;
    float stepsize = 0.12;
    float tile = 0.85;
    float brightness = 0.0018;
    float darkmatter = 0.3;
    float distfading = 0.73;
    float saturation = 0.85;

    float s = 0.1, fade = 1.0;
    vec3 v = vec3(0.0);

    for (int r = 0; r < volsteps; r++) {
        vec3 p = from + s * dir * 0.5;
        p = abs(vec3(tile) - mod(p, vec3(tile * 2.0)));

        float pa, a = pa = 0.0;
        for (int i = 0; i < iterations; i++) {
            // CRITICAL FIX: Clamp the denominator to a minimum threshold.
            // This mathematically removes the division-by-zero singularities,
            // erasing the sharp, flickering stars at their source.
            float d = max(dot(p, p), 0.015);
            p = abs(p) / d - formuparam;
            a += abs(length(p) - pa);
            pa = length(p);
        }

        float dm = max(0.0, darkmatter - a * a * 0.001);

        // CRITICAL FIX: Restore the original cubic contrast scaling.
        // Combined with the denominator clamp above, this recovers the rich
        // high-contrast red/blue color gradients and volumetric borders.
        a *= a * a;

        if (r > 4) fade *= 1.0 - dm;

        v += vec3(s, s * s, s * s * s * s) * a * brightness * fade;
        fade *= distfading;
        s += stepsize;
    }

    v = mix(vec3(length(v)), v, saturation);

    float moonWash = 1.0 - moonBrightness * 0.85;

    vec3 finalGalaxy = v * 0.015 * bandFade * vec3(0.65, 0.75, 1.2);

    float gcDist  = sqrt(lon * lon + lat * lat * 4.0);
    float coreBrightness = exp(-gcDist * gcDist * 0.4);
    finalGalaxy += vec3(0.9, 0.7, 0.5) * coreBrightness * bandFade * v * 0.01;

    return finalGalaxy * nightAmt * nightAmt * moonWash;
}

// ─────────────────────────────────────────────────────────────────────────────
// NEBULA PATCHES
// ─────────────────────────────────────────────────────────────────────────────
vec3 nebulaBlob(vec3 ray, vec3 centre, float radius, vec3 colour, float intensity) {
    if (dot(centre, centre) < 0.01) return vec3(0.0);
    float d = distance(ray, normalize(centre));
    return colour * exp(-d * d / (radius * radius)) * intensity;
}

vec3 nebulae(vec3 ray, float nightAmt) {
    if (nightAmt < 0.1) return vec3(0.0);
    float moonWash = 1.0 - moonBrightness * 0.9;
    float fade = nightAmt * nightAmt * moonWash;

    vec3 col = vec3(0.0);
    col += nebulaBlob(ray, nebulaDir_M42,    0.05, vec3(0.90, 0.42, 0.58), 0.06 * fade);
    col += nebulaBlob(ray, nebulaDir_M31,    0.11, vec3(0.62, 0.68, 0.90), 0.05 * fade);
    col += nebulaBlob(ray, nebulaDir_M31,    0.04, vec3(0.85, 0.87, 0.95), 0.07 * fade);
    col += nebulaBlob(ray, nebulaDir_M8,     0.07, vec3(0.92, 0.58, 0.28), 0.04 * fade);
    col += nebulaBlob(ray, nebulaDir_EtaCar, 0.06, vec3(0.48, 0.62, 0.90), 0.03 * fade);
    return col;
}

// ─────────────────────────────────────────────────────────────────────────────
// AIRGLOW
// ─────────────────────────────────────────────────────────────────────────────
vec3 airglow(vec3 ray, float nightAmt) {
    if (nightAmt < 0.3) return vec3(0.0);
    float h    = clamp(ray.y, 0.0, 1.0);
    float band = exp(-h * h / 0.007) * 0.45 + exp(-h * h / 0.16) * 0.10;
    float shim = 0.85 + 0.15 * sin(time * 0.07 + ray.x * 8.0 + ray.z * 6.0);
    vec3  col  = mix(vec3(0.18, 0.52, 0.16), vec3(0.32, 0.50, 0.09), shim);
    return col * band * nightAmt * 0.028;
}

// ─────────────────────────────────────────────────────────────────────────────
// AURORA
// ─────────────────────────────────────────────────────────────────────────────
vec3 aurora(vec3 ray, float nightAmt) {
    if (auroraStrength < 0.01 || nightAmt < 0.5) return vec3(0.0);

    float northward  = clamp(-ray.z * 1.8, 0.0, 1.0);
    float heightBand = smoothstep(0.0, 0.12, ray.y) * smoothstep(0.60, 0.28, ray.y);
    if (heightBand < 0.001) return vec3(0.0);

    float curtainX = ray.x / max(ray.y, 0.06);
    float t1 = time * 0.17, t2 = time * 0.10;

    float curl1 = (sin(curtainX * 2.1 + t1) * 0.5 + 0.5)
                * (sin(curtainX * 3.9 - t1 * 1.2) * 0.4 + 0.6);
    float curl2 = sin(curtainX * 7.5 + t2 * 2.0) * 0.35 + 0.65;
    float rays   = pow(curl1, 1.6) * curl2;
    float vert   = sin(ray.y * 22.0 + curtainX * 2.8 + t1 * 2.3) * 0.15 + 0.85;

    float intensity = rays * vert * heightBand * northward;

    float hf     = clamp((ray.y - 0.1) / 0.4, 0.0, 1.0);
    vec3  green  = vec3(0.04, 0.88, 0.22);
    vec3  redTop = vec3(0.78, 0.12, 0.32);
    vec3  purple = vec3(0.52, 0.08, 0.78);
    vec3  acol   = mix(green, redTop, hf * 0.55);
    acol = mix(acol, purple, max(0.0, auroraStrength - 0.6) * (1.0 - hf) * 0.75);

    return acol * intensity * auroraStrength * 0.55;
}

// ─────────────────────────────────────────────────────────────────────────────
// CLOUDS — a soft drifting layer projected onto a high plane. Two fbm lookups
// (shape + a sun-ward offset for self-shadowing) give puffy tops and greyer
// bellies; they take the sun's colour, so sunsets set them on fire.
// ─────────────────────────────────────────────────────────────────────────────
vec4 clouds(vec3 ray) {
    if (ray.y < 0.015) return vec4(0.0);
    vec2  uv   = ray.xz / (ray.y + 0.08) * 1.4 + vec2(time * 0.012, time * 0.004);
    float n    = fbm(uv);
    float cov  = smoothstep(0.50, 0.78, n);                // coverage: scattered cumulus
    if (cov < 0.002) return vec4(0.0);
    vec2  toSun = normalize(sunDir.xz + 1e-4) * 0.06;
    float lit  = clamp((n - fbm(uv + toSun)) * 4.0 + 0.55, 0.25, 1.0);   // brighter on the sun side
    float dayAmt = clamp(1.0 - nightFactor, 0.0, 1.0);
    vec3  sunC = mix(vec3(1.0, 0.98, 0.95), vec3(1.0, 0.52, 0.30), sunsetFactor);
    vec3  top  = mix(vec3(0.10, 0.12, 0.20), sunC, dayAmt);
    vec3  belly= mix(vec3(0.04, 0.05, 0.10), mix(skyHorizon, vec3(0.55, 0.58, 0.68), 0.5), dayAmt);
    vec3  c    = mix(belly, top, lit);
    float fade = smoothstep(0.015, 0.20, ray.y);           // thin out toward the horizon
    return vec4(c, cov * fade * mix(0.35, 0.92, dayAmt));
}

// ─────────────────────────────────────────────────────────────────────────────
// MAIN
// ─────────────────────────────────────────────────────────────────────────────
void main() {
    vec2 ndc   = TexCoord * 2.0 - 1.0;
    vec4 nearP = invViewProj * vec4(ndc, -1.0, 1.0);
    vec4 farP  = invViewProj * vec4(ndc,  1.0, 1.0);
    vec3 ray   = normalize(farP.xyz / farP.w - nearP.xyz / nearP.w);

    float t   = smoothstep(0.0, 0.55, ray.y);
    vec3  col = mix(skyHorizon, skyZenith, t);

    float band = exp(-abs(ray.y) * 5.5) * sunsetFactor;
    col = mix(col, vec3(1.0, 0.48, 0.26), band * 0.75);

    float sd    = max(dot(ray, sunDir), 0.0);
    float sunUp = clamp(sunDir.y * 8.0 + 0.25, 0.0, 1.0);
    float disc  = smoothstep(0.9982, 0.9990, sd);
    float glow  = pow(sd, 250.0) * 1.3 + pow(sd, 22.0) * 0.55
                + pow(sd, 5.0) * (0.20 + 0.55 * sunsetFactor);
    vec3  sunCol = mix(vec3(1.0, 0.96, 0.85), vec3(1.0, 0.45, 0.16), sunsetFactor);
    col += sunUp * (disc * 5.0 + glow) * sunCol;

    vec4 cl = clouds(ray);
    if (ray.y > -0.05 && nightFactor > 0.05) {
        // Atmospheric extinction: smoothly fade all volumetric night sky features
        // to zero between 5 degrees above the horizon and slightly below it.
        float horizonFade = smoothstep(-0.02, 0.08, ray.y);
        col += milkyWay(ray, nightFactor) * horizonFade;
        col += nebulae(ray, nightFactor) * horizonFade;
        col += airglow(ray, nightFactor) * horizonFade;
        col += aurora(ray, nightFactor) * horizonFade;
    }

    col = mix(col, cl.rgb, cl.a);            // clouds sit in front of stars & the sun glow

    if (lunarEclipseFactor > 0.01 && nightFactor > 0.5) {
        float et = lunarEclipseFactor * 0.04;
        col += vec3(et, et * 0.15, 0.0);
    }

    FragColor = vec4(col, 1.0);
}