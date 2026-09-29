'use strict';
/* Nocturne · renderer. WebGL2 with half-float targets:
   1. stars and sparks as motion-blurred streaks, additive, into their own HDR layer
   2. smoke at half resolution, lit by up to twelve bursts
   3. one composite pass: sky, clouds, city, bridge, the harbour mirror, smoke, sparks, crowd
   4. bloom from a six-level mip chain, then AgX tone mapping, vignette and grain */

const Renderer = (() => {
  let gl, canvas, W = 0, H = 0, enc = 1, ok = false;
  let F; // render target format
  const P = {};
  let fbScene, fbPart, fbSmoke, mips = [];
  let vaoEmpty, vaoPart, vaoSmoke, bufPart, bufSmoke;
  let texSky, texCrowd, blinkers = [], crowdFrac = 0.2;
  let partData, smokeData, maxPart = 0;
  const LN = 12;
  const lPos = new Float32Array(LN * 4), lCol = new Float32Array(LN * 3), lScr = new Float32Array(LN * 4);
  const flashV = [0, 0, 0], blinkBuf = new Float32Array(32);
  const settings = { exposure: 1.0, bloom: 0.9, shutter: 1 / 30 };

  const HEAD = '#version 300 es\nprecision highp float;\n';
  const COMMON = `
    uniform vec2 uPitch; uniform vec2 uTan; uniform float uCamH;
    vec3 toView(vec3 p){ vec3 d = p - vec3(0.0, uCamH, 0.0); return vec3(d.x, d.y*uPitch.y - d.z*uPitch.x, d.y*uPitch.x + d.z*uPitch.y); }
    float hash12(vec2 p){ vec3 p3 = fract(vec3(p.xyx) * .1031); p3 += dot(p3, p3.yzx + 33.33); return fract((p3.x + p3.y) * p3.z); }
    float vnoise(vec2 p){ vec2 i = floor(p), f = fract(p); vec2 u = f*f*(3.0-2.0*f);
      return mix(mix(hash12(i), hash12(i+vec2(1,0)), u.x), mix(hash12(i+vec2(0,1)), hash12(i+vec2(1,1)), u.x), u.y); }
  `;
  const FS_VERT = HEAD + `out vec2 vUv; void main(){ vec2 p = vec2(float((gl_VertexID<<1)&2), float(gl_VertexID&2)); vUv = p; gl_Position = vec4(p*2.0-1.0, 0.0, 1.0); }`;

  const PART_VS = HEAD + COMMON + `
    layout(location=0) in vec2 aC; layout(location=1) in vec4 aPS; layout(location=2) in vec3 aV; layout(location=3) in vec3 aCol;
    uniform vec2 uRes; uniform float uShutter; uniform float uMinPx; uniform float uEnc;
    out vec3 vCol; out vec2 vL; out float vHL; out float vR;
    void main(){
      vec3 v0 = toView(aPS.xyz), v1 = toView(aPS.xyz - aV*uShutter);
      if (v0.z < 5.0 || v1.z < 5.0) { gl_Position = vec4(0.0, 0.0, -2.0, 1.0); return; }
      vec2 h = 0.5*uRes;
      vec2 s0 = vec2(v0.x/(v0.z*uTan.x), v0.y/(v0.z*uTan.y))*h, s1 = vec2(v1.x/(v1.z*uTan.x), v1.y/(v1.z*uTan.y))*h;
      float rad = aPS.w * h.y / (v0.z*uTan.y), e = 1.0;
      if (rad < uMinPx) { e = rad*rad/(uMinPx*uMinPx); rad = uMinPx; }
      vec2 d = s0 - s1; float len = length(d);
      vec2 ax = len > 1e-3 ? d/len : vec2(1.0, 0.0), ay = vec2(-ax.y, ax.x);
      float hl = 0.5*len;
      vec2 loc = vec2(aC.x*(hl + 2.2*rad), aC.y*2.2*rad);
      vec2 px = 0.5*(s0+s1) + ax*loc.x + ay*loc.y;
      gl_Position = vec4(px/h, 0.0, 1.0);
      vCol = aCol * e / (1.0 + 0.7*hl/rad) * uEnc; vL = loc; vHL = hl; vR = rad;
    }`;
  const PART_FS = HEAD + `
    in vec3 vCol; in vec2 vL; in float vHL; in float vR; out vec4 o;
    void main(){
      float dx = max(abs(vL.x) - vHL, 0.0);
      float q = (dx*dx + vL.y*vL.y)/(vR*vR);
      float k = exp(-2.0*q);
      o = vec4(vCol*k, 1.0);
    }`;

  const SMOKE_VS = HEAD + COMMON + `
    layout(location=0) in vec2 aC; layout(location=1) in vec4 aPR; layout(location=2) in vec4 aPA;
    out vec2 vL; out vec3 vW; out float vA; out float vS;
    void main(){
      vec3 v = toView(aPR.xyz);
      if (v.z < 20.0) { gl_Position = vec4(0.0, 0.0, -2.0, 1.0); return; }
      vec2 c = vec2(v.x/(v.z*uTan.x), v.y/(v.z*uTan.y));
      vec2 ext = vec2(aPR.w/(v.z*uTan.x), aPR.w/(v.z*uTan.y));
      gl_Position = vec4(c + aC*ext, 0.0, 1.0);
      vL = aC; vA = aPA.x; vS = aPA.y;
      vW = aPR.xyz + (vec3(1.0, 0.0, 0.0)*aC.x + vec3(0.0, uPitch.y, -uPitch.x)*aC.y) * aPR.w;
    }`;
  const SMOKE_FS = HEAD + COMMON + `
    in vec2 vL; in vec3 vW; in float vA; in float vS; out vec4 o;
    uniform int uLN; uniform vec4 uLP[12]; uniform vec3 uLC[12]; uniform float uTime; uniform float uEnc; uniform vec3 uFlash;
    void main(){
      float r2 = dot(vL, vL); if (r2 > 1.0) discard;
      vec2 q = vL*1.4 + vec2(vS*37.0, vS*91.0);
      q += (vec2(vnoise(q*1.3 + uTime*0.03), vnoise(q*1.3 + 5.2 - uTime*0.025)) - 0.5)*1.4;
      float n = vnoise(q)*0.55 + vnoise(q*2.1 + 3.1)*0.3 + vnoise(q*4.3 + 7.7)*0.15;
      float edge = 1.0 - r2;
      float dens = smoothstep(0.0, 0.4, n + edge*0.62 - 0.6) * edge * vA;
      if (dens < 0.002) discard;
      vec3 L = vec3(0.011, 0.008, 0.007) * (0.6 + 1.2*exp(-vW.y/120.0)) + vec3(0.002, 0.003, 0.005) + uFlash*0.004;
      for (int i = 0; i < 12; i++) {
        if (i >= uLN) break;
        vec3 d = uLP[i].xyz - vW; float R = uLP[i].w;
        L += uLC[i] * (R*R/(dot(d, d) + R*R));
      }
      o = vec4(L*0.42*dens*uEnc, dens);
    }`;

  const COMP_FS = HEAD + COMMON + `
    in vec2 vUv; out vec4 o;
    uniform sampler2D uPart, uSmoke, uSky, uCrowd;
    uniform vec2 uRes; uniform float uTime, uPixAng, uDec, uEnc, uCrowdH, uElCity, uElBarge, uEmit;
    uniform vec4 uMap; uniform vec3 uFlash;
    uniform int uLN; uniform vec4 uLS[12]; uniform vec3 uLC[12]; uniform vec4 uBlink[8]; uniform int uBN;

    vec3 rayDir(vec2 n){ return normalize(vec3(n.x*uTan.x, uPitch.x + uPitch.y*n.y*uTan.y, uPitch.y - uPitch.x*n.y*uTan.y)); }
    vec2 dirNdc(vec3 d){ float vz = d.y*uPitch.x + d.z*uPitch.y, vy = d.y*uPitch.y - d.z*uPitch.x; return vec2(d.x/(vz*uTan.x), vy/(vz*uTan.y)); }
    vec3 azEl(float az, float el){ float c = cos(el); return vec3(c*sin(az), sin(el), c*cos(az)); }
    float fbm(vec2 p){ float s = 0.0, a = 0.5; for (int i = 0; i < 5; i++){ s += a*vnoise(p); p = p*2.03 + vec2(17.1, 9.2); a *= 0.5; } return s; }

    vec3 glow(vec2 ndc){
      vec3 g = vec3(0.0);
      vec2 t = ndc*uTan;
      for (int i = 0; i < 12; i++) {
        if (i >= uLN) break;
        vec2 d = t - uLS[i].xy*uTan; float q = dot(d, d)/(uLS[i].z*uLS[i].z);
        g += uLC[i] / (1.0 + q*q);
      }
      return g;
    }
    vec3 stars(float az, float el){
      vec2 g = vec2(az, el)/0.0105; vec2 c = floor(g), f = fract(g);
      float h = hash12(c);
      if (h > 0.1) return vec3(0.0);
      vec2 p = vec2(hash12(c + 7.1), hash12(c + 3.7))*0.8 + 0.1;
      float mag = pow(hash12(c + 1.3), 7.0);
      vec2 dp = (f - p)*0.0105/uPixAng;
      float tw = 0.7 + 0.3*sin(uTime*(1.5 + h*14.0) + h*90.0);
      float b = (0.006 + mag*0.7) * exp(-dot(dp, dp)*1.3) * tw * smoothstep(0.03, 0.25, el);
      return mix(vec3(1.0, 0.86, 0.72), vec3(0.72, 0.84, 1.0), hash12(c + 9.9)) * b;
    }
    vec3 sky(vec3 d, vec2 ndc, bool withStars){
      float el = asin(clamp(d.y, -1.0, 1.0)), h = max(el, 0.0), az = atan(d.x, d.z);
      vec3 c = mix(vec3(0.008, 0.009, 0.017), vec3(0.0012, 0.0018, 0.0048), smoothstep(0.0, 0.45, h));
      float city = exp(-pow((az - 0.12)/0.6, 2.0));
      c += vec3(0.036, 0.019, 0.01) * exp(-h*20.0) * (0.4 + 0.6*city);
      c += vec3(0.008, 0.006, 0.009) * exp(-h*5.0);
      vec2 cp = vec2(az*2.4 + uTime*0.004, 1.0/(h + 0.06));
      float cl = smoothstep(0.42, 0.9, fbm(cp*vec2(1.0, 0.55)));
      cl *= smoothstep(0.0, 0.06, h);
      c += vec3(0.007, 0.0045, 0.0032) * cl * exp(-h*5.0) * (0.4 + city);
      vec3 lg = glow(ndc);
      c += lg * (0.003 + 0.035*cl);
      if (withStars) c += stars(az, el) * (1.0 - cl*0.9);
      return c;
    }
    vec4 skyline(float az, float el){
      vec2 uv = vec2(az/(2.0*uMap.x) + 0.5, (el - uMap.y)/(uMap.z - uMap.y));
      if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) return vec4(0.0);
      vec4 s = texture(uSky, uv);
      return vec4(pow(s.rgb, vec3(2.2))*uEmit, s.a);
    }
    vec3 facade(float el){
      return vec3(0.0035, 0.004, 0.0062) + vec3(0.006, 0.004, 0.003)*exp(-max(el - uElCity, 0.0)*300.0) + uFlash*0.006;
    }
    vec3 blinks(float az, float el){
      vec3 c = vec3(0.0);
      for (int i = 0; i < 8; i++) {
        if (i >= uBN) break;
        vec2 d = (vec2(az, el) - uBlink[i].xy)/uPixAng;
        float on = step(fract(uTime*0.55 + uBlink[i].z), 0.35);
        c += vec3(1.0, 0.08, 0.03) * exp(-dot(d, d)*0.8) * on * 1.8;
      }
      return c;
    }
    const vec2 WD0 = vec2(0.12, 1.0), WD1 = vec2(-0.35, 0.94), WD2 = vec2(0.5, 0.87), WD3 = vec2(-0.1, 1.0), WD4 = vec2(0.66, 0.75), WD5 = vec2(-0.6, 0.8);
    vec2 wave(vec2 p, vec2 dir, float wl, float a, float ph, float fx, float fz, inout float r2){
      float k = 6.2831853/wl, foot = abs(dir.x)*fx + abs(dir.y)*fz;
      float vis = clamp(1.7 - 2.2*foot/wl, 0.0, 1.0);
      r2 += a*a*(1.0 - vis);
      return dir * cos(dot(p, dir)*k - sqrt(9.81*k)*uTime + ph) * a * vis;
    }
    vec2 waves(vec2 wp, float fx, float fz, out float rough){
      float r2 = 0.0;
      float n1 = vnoise(wp*vec2(0.035, 0.07) + uTime*0.03)*6.2832, n2 = vnoise(wp*vec2(0.09, 0.16) + 11.0 - uTime*0.05)*6.2832;
      float g = 0.55 + 0.9*vnoise(wp*vec2(0.02, 0.05) + 4.0 + uTime*0.02);
      vec2 sl = vec2(0.0);
      sl += wave(wp, WD0, 11.0, 0.016*g, n1, fx, fz, r2);
      sl += wave(wp, WD1, 6.3, 0.02*g, 1.7 + n2, fx, fz, r2);
      sl += wave(wp, WD2, 3.7, 0.026, 3.1 + n1*0.7 + n2*0.5, fx, fz, r2);
      sl += wave(wp, WD3, 2.2, 0.024*g, 4.6 + n2*1.3, fx, fz, r2);
      sl += wave(wp, WD4, 1.3, 0.022, 0.9 + n1*1.6, fx, fz, r2);
      sl += wave(wp, WD5, 0.75, 0.02, 2.4 + n2*2.1 + n1, fx, fz, r2);
      rough = sqrt(r2) + 0.004;
      return sl;
    }

    void main(){
      vec2 ndc = vUv*2.0 - 1.0;
      vec3 d = rayDir(ndc);
      float el = asin(d.y), az = atan(d.x, d.z);
      vec3 c;
      if (el >= uElCity) {
        c = sky(d, ndc, true);
        vec4 s = skyline(az, el);
        c = mix(c, facade(el), s.a) + s.rgb;
        c += blinks(az, el);
      } else {
        float t = uCamH/max(-d.y, 1e-4);
        vec2 wp = vec2(d.x, d.z)*t;
        float fx = t*uPixAng, fz = fx/max(-d.y, 0.002);
        float rough;
        vec2 sl = waves(wp, fx, fz, rough);
        vec3 n = normalize(vec3(-sl.x, 1.0, -sl.y));
        vec3 rd = reflect(d, n);
        rd.y = max(rd.y, 0.0008); rd = normalize(rd);
        float elR = asin(rd.y), azR = atan(rd.x, rd.z);
        vec3 skyR = sky(rd, dirNdc(rd), false);
        vec3 acc = vec3(0.0);
        float jit = hash12(gl_FragCoord.xy + fract(uTime*3.7)*vec2(97.0, 41.0)) - 0.5;
        float spreadPx = rough*2.2/uPixAng;
        float lod = log2(max(spreadPx*0.35, 1.0));
        for (int k = 0; k < 5; k++) {
          float e = max(elR + (float(k) - 2.0 + jit)*0.5*rough*1.1, 0.0003);
          vec4 sk = skyline(azR, e + 2.0*uElCity);
          vec3 s = mix(skyR, facade(e + 2.0*uElCity), sk.a) + sk.rgb;
          vec2 uvm = dirNdc(azEl(azR, e + 2.0*uElBarge))*0.5 + 0.5;
          if (uvm.y < 1.0 && uvm.x > 0.0 && uvm.x < 1.0) {
            vec4 sm = textureLod(uSmoke, uvm, lod*0.5);
            s = s*(1.0 - sm.a) + sm.rgb*uDec;
            s += textureLod(uPart, uvm, lod).rgb*uDec;
          }
          acc += s;
        }
        acc *= 0.2;
        float cosT = max(dot(-d, n), 0.0);
        float fres = 0.02 + 0.98*pow(1.0 - cosT, 5.0);
        c = vec3(0.0012, 0.0019, 0.003) + uFlash*0.0015 + acc*fres*0.92;
        vec4 s = skyline(az, el);
        c = mix(c, facade(el)*0.6, s.a) + s.rgb;
      }
      vec4 sm = texture(uSmoke, vUv);
      c = c*(1.0 - sm.a) + sm.rgb*uDec;
      c += texture(uPart, vUv).rgb*uDec;
      float cy = vUv.y/uCrowdH;
      if (cy < 1.0) {
        vec4 cr = texture(uCrowd, vec2(vUv.x, cy));
        float above = texture(uCrowd, vec2(vUv.x, cy + 2.5/(uRes.y*uCrowdH))).a;
        float rim = clamp(cr.a - above, 0.0, 1.0);
        vec3 body = vec3(0.0012, 0.0013, 0.0019) + uFlash*0.0012 + (uFlash*0.045 + vec3(0.006, 0.004, 0.003))*rim;
        vec3 screen = pow(cr.rgb, vec3(2.2)) * (0.16 + uFlash*0.18);
        c = mix(c, body, cr.a) + screen*cr.a;
      }
      o = vec4(c*uEnc, 1.0);
    }`;

  const DOWN_FS = HEAD + `
    in vec2 vUv; out vec4 o; uniform sampler2D uSrc; uniform vec2 uTexel; uniform float uKaris;
    vec3 s(vec2 off){ return texture(uSrc, vUv + off*uTexel).rgb; }
    float w(vec3 c){ return 1.0/(1.0 + dot(c, vec3(0.2126, 0.7152, 0.0722))); }
    void main(){
      vec3 a = s(vec2(-2, 2)), b = s(vec2(0, 2)), c = s(vec2(2, 2)), d = s(vec2(-2, 0)), e = s(vec2(0, 0)), f = s(vec2(2, 0));
      vec3 g = s(vec2(-2, -2)), h = s(vec2(0, -2)), i = s(vec2(2, -2)), j = s(vec2(-1, 1)), k = s(vec2(1, 1)), l = s(vec2(-1, -1)), m = s(vec2(1, -1));
      vec3 r;
      if (uKaris > 0.5) {
        vec3 g0 = (a + b + d + e)*0.25, g1 = (b + c + e + f)*0.25, g2 = (d + e + g + h)*0.25, g3 = (e + f + h + i)*0.25, g4 = (j + k + l + m)*0.25;
        float w0 = w(g0), w1 = w(g1), w2 = w(g2), w3 = w(g3), w4 = w(g4);
        r = (g0*w0*0.125 + g1*w1*0.125 + g2*w2*0.125 + g3*w3*0.125 + g4*w4*0.5) / (w0*0.125 + w1*0.125 + w2*0.125 + w3*0.125 + w4*0.5);
      } else {
        r = e*0.125 + (a + c + g + i)*0.03125 + (b + d + f + h)*0.0625 + (j + k + l + m)*0.125;
      }
      o = vec4(r, 1.0);
    }`;
  const UP_FS = HEAD + `
    in vec2 vUv; out vec4 o; uniform sampler2D uSrc; uniform vec2 uTexel; uniform float uW;
    void main(){
      vec2 t = uTexel;
      vec3 r = texture(uSrc, vUv).rgb*4.0
        + (texture(uSrc, vUv + vec2(-t.x, 0)).rgb + texture(uSrc, vUv + vec2(t.x, 0)).rgb + texture(uSrc, vUv + vec2(0, -t.y)).rgb + texture(uSrc, vUv + vec2(0, t.y)).rgb)*2.0
        + texture(uSrc, vUv + vec2(-t.x, -t.y)).rgb + texture(uSrc, vUv + vec2(t.x, -t.y)).rgb + texture(uSrc, vUv + vec2(-t.x, t.y)).rgb + texture(uSrc, vUv + vec2(t.x, t.y)).rgb;
      o = vec4(r*(uW/16.0), 1.0);
    }`;
  const FINAL_FS = HEAD + `
    in vec2 vUv; out vec4 o;
    uniform sampler2D uScene, uBloom; uniform float uBloomK, uExposure, uTime, uDec;
    float hash12(vec2 p){ vec3 p3 = fract(vec3(p.xyx) * .1031); p3 += dot(p3, p3.yzx + 33.33); return fract((p3.x + p3.y) * p3.z); }
    vec3 agxCurve(vec3 x){ vec3 x2 = x*x, x4 = x2*x2;
      return 15.5*x4*x2 - 40.14*x4*x + 31.96*x4 - 6.868*x2*x + 0.4298*x2 + 0.1191*x - 0.00232; }
    vec3 agx(vec3 c){
      const mat3 m = mat3(0.842479062253094, 0.0423282422610123, 0.0423756549057051,
                           0.0784335999999992, 0.878468636469772, 0.0784336,
                           0.0792237451477643, 0.0791661274605434, 0.879142973793104);
      const mat3 mi = mat3(1.19687900512017, -0.0528968517574562, -0.0529716355144438,
                           -0.0980208811401368, 1.15190312990417, -0.0980434501171241,
                           -0.0990297440797205, -0.0989611768448433, 1.15107367264116);
      const float lo = -12.47393, hi = 4.026069;
      c = m*max(c, vec3(1e-10));
      c = clamp(log2(c), lo, hi);
      c = (c - lo)/(hi - lo);
      c = agxCurve(c);
      float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
      c = pow(max(c, vec3(0.0)), vec3(1.12));
      c = l + 1.3*(c - l);
      return mi*c;
    }
    void main(){
      vec3 c = texture(uScene, vUv).rgb*uDec;
      vec3 b = texture(uBloom, vUv).rgb*uDec;
      c = (c + b*uBloomK)*uExposure;
      c = agx(c);
      vec2 q = vUv - 0.5;
      c *= 1.0 - dot(q, q)*0.42;
      float n = hash12(gl_FragCoord.xy + fract(uTime*7.13)*vec2(113.1, 71.7)) + hash12(gl_FragCoord.xy*1.37 + fract(uTime*3.1)*vec2(29.3, 53.9)) - 1.0;
      c += n*(2.0/255.0);
      o = vec4(clamp(c, 0.0, 1.0), 1.0);
    }`;

  function shader(type, src) {
    const s = gl.createShader(type);
    gl.shaderSource(s, src); gl.compileShader(s);
    if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error('Shader: ' + gl.getShaderInfoLog(s));
    return s;
  }
  function program(vs, fs) {
    const p = gl.createProgram();
    gl.attachShader(p, shader(gl.VERTEX_SHADER, vs)); gl.attachShader(p, shader(gl.FRAGMENT_SHADER, fs));
    gl.linkProgram(p);
    if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error('Link: ' + gl.getProgramInfoLog(p));
    const u = {}, n = gl.getProgramParameter(p, gl.ACTIVE_UNIFORMS);
    for (let i = 0; i < n; i++) { const info = gl.getActiveUniform(p, i); u[info.name.replace(/\[0\]$/, '')] = gl.getUniformLocation(p, info.name); }
    return { p, u };
  }
  function target(w, h, mip) {
    const t = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, t);
    const levels = mip ? Math.floor(Math.log2(Math.max(w, h))) + 1 : 1;
    gl.texStorage2D(gl.TEXTURE_2D, levels, F.internal, w, h);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, mip ? gl.LINEAR_MIPMAP_LINEAR : gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    const f = gl.createFramebuffer();
    gl.bindFramebuffer(gl.FRAMEBUFFER, f);
    gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, t, 0);
    return { f, t, w, h, mip, complete: gl.checkFramebufferStatus(gl.FRAMEBUFFER) === gl.FRAMEBUFFER_COMPLETE };
  }
  function free(tg) { if (tg) { gl.deleteFramebuffer(tg.f); gl.deleteTexture(tg.t); } }

  function init(cv, maxParticles) {
    canvas = cv;
    gl = cv.getContext('webgl2', { antialias: false, alpha: false, depth: false, stencil: false, premultipliedAlpha: false, powerPreference: 'high-performance', preserveDrawingBuffer: false });
    if (!gl) return false;
    const hasFloat = gl.getExtension('EXT_color_buffer_float') || gl.getExtension('EXT_color_buffer_half_float');
    F = hasFloat ? { internal: gl.RGBA16F } : { internal: gl.RGBA8 };
    enc = hasFloat ? 1 : 0.25;
    if (hasFloat) {
      const t = target(4, 4, false);
      if (!t.complete) { F = { internal: gl.RGBA8 }; enc = 0.25; }
      free(t);
    }
    P.part = program(PART_VS, PART_FS);
    P.smoke = program(SMOKE_VS, SMOKE_FS);
    P.comp = program(FS_VERT, COMP_FS);
    P.down = program(FS_VERT, DOWN_FS);
    P.up = program(FS_VERT, UP_FS);
    P.final = program(FS_VERT, FINAL_FS);

    vaoEmpty = gl.createVertexArray();
    const corners = new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]);
    maxPart = maxParticles;
    partData = new Float32Array(maxParticles * 10);
    smokeData = new Float32Array(Sim.SMAX * 8);

    const mk = (floats, stride, layout) => {
      const vao = gl.createVertexArray(); gl.bindVertexArray(vao);
      const cb = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, cb); gl.bufferData(gl.ARRAY_BUFFER, corners, gl.STATIC_DRAW);
      gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
      const b = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, b); gl.bufferData(gl.ARRAY_BUFFER, floats * 4, gl.DYNAMIC_DRAW);
      let off = 0;
      layout.forEach((size, k) => {
        gl.enableVertexAttribArray(k + 1); gl.vertexAttribPointer(k + 1, size, gl.FLOAT, false, stride * 4, off * 4); gl.vertexAttribDivisor(k + 1, 1);
        off += size;
      });
      gl.bindVertexArray(null);
      return [vao, b];
    };
    [vaoPart, bufPart] = mk(partData.length, 10, [4, 3, 3]);
    [vaoSmoke, bufSmoke] = mk(smokeData.length, 8, [4, 4]);

    // Panorama of the far shore.
    const maxTex = gl.getParameter(gl.MAX_TEXTURE_SIZE);
    const sk = Scenery.skyline(Math.min(8192, maxTex), 512);
    blinkers = sk.blinkers;
    blinkers.slice(0, 8).forEach((b, k) => { blinkBuf[k * 4] = b[0]; blinkBuf[k * 4 + 1] = b[1]; blinkBuf[k * 4 + 2] = b[2]; });
    texSky = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, texSky);
    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, true);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, sk.canvas);
    gl.generateMipmap(gl.TEXTURE_2D);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR_MIPMAP_LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    texCrowd = gl.createTexture();
    ok = true;
    return true;
  }

  function resize(w, h, showCrowd) {
    if (!ok || (w === W && h === H && texCrowd.showCrowd === showCrowd)) return;
    W = w; H = h; canvas.width = w; canvas.height = h;
    free(fbScene); free(fbPart); free(fbSmoke); mips.forEach(free); mips = [];
    fbScene = target(W, H, false);
    fbPart = target(W, H, true);
    fbSmoke = target(Math.max(1, W >> 1), Math.max(1, H >> 1), true);
    let mw = W >> 1, mh = H >> 1;
    for (let i = 0; i < 6 && mw >= 8 && mh >= 8; i++) { mips.push(target(mw, mh, false)); mw >>= 1; mh >>= 1; }
    crowdFrac = w / h < 1 ? 0.1 : 0.14;
    const ch = Math.max(8, Math.round(H * crowdFrac));
    const cc = showCrowd ? Scenery.crowd(W, ch) : (() => { const c = document.createElement('canvas'); c.width = 4; c.height = 4; return c; })();
    gl.bindTexture(gl.TEXTURE_2D, texCrowd);
    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, true);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, cc);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    texCrowd.showCrowd = showCrowd;
  }

  function camUniforms(u) {
    gl.uniform2f(u.uPitch, CAM.sp, CAM.cp);
    gl.uniform2f(u.uTan, CAM.tanX, CAM.tanY);
    gl.uniform1f(u.uCamH, WORLD.camH);
  }
  function drawFS() { gl.bindVertexArray(vaoEmpty); gl.drawArrays(gl.TRIANGLES, 0, 3); }
  function bindTex(unit, tex, loc) { gl.activeTexture(gl.TEXTURE0 + unit); gl.bindTexture(gl.TEXTURE_2D, tex); gl.uniform1i(loc, unit); }

  function render(time) {
    if (!ok) return;
    // Lights for this frame.
    const Ls = Sim.topLights(LN), nL = Ls.length;
    for (let k = 0; k < nL; k++) {
      const L = Ls[k];
      lPos[k * 4] = L.x; lPos[k * 4 + 1] = L.y; lPos[k * 4 + 2] = L.z; lPos[k * 4 + 3] = L.rad;
      lCol[k * 3] = L.r * L.cur; lCol[k * 3 + 1] = L.g * L.cur; lCol[k * 3 + 2] = L.b * L.cur;
      const p = project(L.x, L.y, L.z);
      if (p) { lScr[k * 4] = p[0]; lScr[k * 4 + 1] = p[1]; lScr[k * 4 + 2] = L.rad * 1.4 / p[2]; }
      else { lScr[k * 4] = 9; lScr[k * 4 + 1] = 9; lScr[k * 4 + 2] = 0.0001; }
    }
    Sim.ambientFlash(flashV);

    gl.disable(gl.DEPTH_TEST);
    // 1 · stars and sparks
    const np = Sim.pack(partData);
    gl.bindFramebuffer(gl.FRAMEBUFFER, fbPart.f); gl.viewport(0, 0, W, H);
    gl.clearColor(0, 0, 0, 0); gl.clear(gl.COLOR_BUFFER_BIT);
    if (np > 0) {
      const u = P.part.u;
      gl.useProgram(P.part.p); camUniforms(u);
      gl.uniform2f(u.uRes, W, H); gl.uniform1f(u.uShutter, settings.shutter);
      gl.uniform1f(u.uMinPx, Math.max(0.7, H / 1400)); gl.uniform1f(u.uEnc, enc);
      gl.enable(gl.BLEND); gl.blendFunc(gl.ONE, gl.ONE);
      gl.bindVertexArray(vaoPart); gl.bindBuffer(gl.ARRAY_BUFFER, bufPart);
      gl.bufferSubData(gl.ARRAY_BUFFER, 0, partData, 0, np * 10);
      gl.drawArraysInstanced(gl.TRIANGLE_STRIP, 0, 4, np);
      gl.disable(gl.BLEND);
    }
    gl.bindTexture(gl.TEXTURE_2D, fbPart.t); gl.generateMipmap(gl.TEXTURE_2D);

    // 2 · smoke
    const ns = Sim.packSmoke(smokeData);
    gl.bindFramebuffer(gl.FRAMEBUFFER, fbSmoke.f); gl.viewport(0, 0, fbSmoke.w, fbSmoke.h);
    gl.clear(gl.COLOR_BUFFER_BIT);
    if (ns > 0) {
      const u = P.smoke.u;
      gl.useProgram(P.smoke.p); camUniforms(u);
      gl.uniform1i(u.uLN, nL); gl.uniform4fv(u.uLP, lPos); gl.uniform3fv(u.uLC, lCol);
      gl.uniform1f(u.uTime, time); gl.uniform1f(u.uEnc, enc); gl.uniform3f(u.uFlash, flashV[0], flashV[1], flashV[2]);
      gl.enable(gl.BLEND); gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
      gl.bindVertexArray(vaoSmoke); gl.bindBuffer(gl.ARRAY_BUFFER, bufSmoke);
      gl.bufferSubData(gl.ARRAY_BUFFER, 0, smokeData, 0, ns * 8);
      gl.drawArraysInstanced(gl.TRIANGLE_STRIP, 0, 4, ns);
      gl.disable(gl.BLEND);
    }
    gl.bindTexture(gl.TEXTURE_2D, fbSmoke.t); gl.generateMipmap(gl.TEXTURE_2D);

    // 3 · composite
    {
      const u = P.comp.u;
      gl.bindFramebuffer(gl.FRAMEBUFFER, fbScene.f); gl.viewport(0, 0, W, H);
      gl.useProgram(P.comp.p); camUniforms(u);
      bindTex(0, fbPart.t, u.uPart); bindTex(1, fbSmoke.t, u.uSmoke); bindTex(2, texSky, u.uSky); bindTex(3, texCrowd, u.uCrowd);
      gl.uniform2f(u.uRes, W, H); gl.uniform1f(u.uTime, time);
      gl.uniform1f(u.uPixAng, 2 * Math.atan(CAM.tanY) / H);
      gl.uniform1f(u.uDec, 1 / enc); gl.uniform1f(u.uEnc, enc);
      gl.uniform1f(u.uCrowdH, texCrowd.showCrowd ? crowdFrac : 0.0001);
      gl.uniform1f(u.uElCity, WORLD.elCity); gl.uniform1f(u.uElBarge, WORLD.elBarge); gl.uniform1f(u.uEmit, 0.9);
      const M = Scenery.MAP; gl.uniform4f(u.uMap, M.azMax, M.el0, M.el1, 0);
      gl.uniform3f(u.uFlash, flashV[0], flashV[1], flashV[2]);
      gl.uniform1i(u.uLN, nL); gl.uniform4fv(u.uLS, lScr); gl.uniform3fv(u.uLC, lCol);
      gl.uniform4fv(u.uBlink, blinkBuf); gl.uniform1i(u.uBN, Math.min(8, blinkers.length));
      drawFS();
    }

    // 4 · bloom
    {
      const u = P.down.u;
      gl.useProgram(P.down.p);
      let src = fbScene;
      mips.forEach((dst, i) => {
        gl.bindFramebuffer(gl.FRAMEBUFFER, dst.f); gl.viewport(0, 0, dst.w, dst.h);
        bindTex(0, src.t, u.uSrc); gl.uniform2f(u.uTexel, 1 / src.w, 1 / src.h); gl.uniform1f(u.uKaris, i === 0 ? 1 : 0);
        drawFS(); src = dst;
      });
      const v = P.up.u;
      gl.useProgram(P.up.p);
      gl.enable(gl.BLEND); gl.blendFunc(gl.ONE, gl.ONE);
      for (let i = mips.length - 1; i > 0; i--) {
        const s = mips[i], d = mips[i - 1];
        gl.bindFramebuffer(gl.FRAMEBUFFER, d.f); gl.viewport(0, 0, d.w, d.h);
        bindTex(0, s.t, v.uSrc); gl.uniform2f(v.uTexel, 1 / s.w, 1 / s.h); gl.uniform1f(v.uW, 1.0);
        drawFS();
      }
      gl.disable(gl.BLEND);
    }

    // 5 · tone map to the screen
    {
      const u = P.final.u;
      gl.bindFramebuffer(gl.FRAMEBUFFER, null); gl.viewport(0, 0, W, H);
      gl.useProgram(P.final.p);
      bindTex(0, fbScene.t, u.uScene); bindTex(1, mips[0].t, u.uBloom);
      gl.uniform1f(u.uBloomK, settings.bloom / Math.max(1, mips.length));
      gl.uniform1f(u.uExposure, settings.exposure); gl.uniform1f(u.uTime, time); gl.uniform1f(u.uDec, 1 / enc);
      drawFS();
    }
  }

  return {
    init, resize, render, settings,
    get size() { return [W, H]; }, get hdr() { return enc === 1; },
  };
})();
