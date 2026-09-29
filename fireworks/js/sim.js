'use strict';
/* Nocturne · simulation. Stars and sparks live in flat typed arrays (structure of arrays)
   and die by swap-remove, so the live set is always packed for upload. Stars feel gravity
   plus linear and quadratic air drag: they leave the burst fast, stop short, then hang. */

const K_STAR = 0, K_SPARK = 1, K_FLASH = 2, K_EMIT = 3;
const F_GLITTER = 2, F_STROBE = 4, F_CRACKLE = 8, F_CHANGE = 16, F_BLACKBODY = 32,
  F_FLICKER = 64, F_HIDDEN = 128, F_FISH = 256, F_SYNC = 512, F_FLUTTER = 1024;
const EV_CALL = 1, EV_SPLIT = 2;

// What a burning star sheds behind it.
const TS = { NONE: 0, GOLD: 1, WILLOW: 2, SILVER: 3, TINT: 4, GLITTER: 5, LIFT: 6, BROCADE: 7, FALL: 8, FOUNT: 9, GFOUNT: 10 };
const TRAIL = [];
TRAIL[TS.GOLD]    = { life: [0.4, 0.9],  jit: 3.2, inh: 0.12, drag: 2.2, grav: 0.3,  size: 0.5,  bright: 1.5, bb: 1, flick: 1 };
TRAIL[TS.WILLOW]  = { life: [1.8, 3.0],  jit: 1.1, inh: 0.05, drag: 2.8, grav: 0.14, size: 0.46, bright: 1.0, bb: 1, flick: 1 };
TRAIL[TS.SILVER]  = { life: [0.16, 0.42], jit: 5,  inh: 0.18, drag: 2.5, grav: 0.25, size: 0.42, bright: 2.4, col: 'silver', flick: 1 };
TRAIL[TS.TINT]    = { life: [0.2, 0.5],  jit: 1.4, inh: 0.3,  drag: 2.2, grav: 0.15, size: 0.6,  bright: 0.7, tint: 1 };
TRAIL[TS.GLITTER] = { life: [0.5, 1.1],  jit: 1.6, inh: 0.08, drag: 2.6, grav: 0.35, size: 0.46, bright: 3.0, col: [1, 0.86, 0.62], glit: 1 };
TRAIL[TS.LIFT]    = { life: [0.25, 0.6], jit: 3.5, inh: 0.0,  drag: 2.0, grav: 0.4,  size: 0.5,  bright: 1.4, bb: 1, flick: 1 };
TRAIL[TS.BROCADE] = { life: [1.2, 2.2],  jit: 1.0, inh: 0.05, drag: 2.6, grav: 0.13, size: 0.5,  bright: 1.45, bb: 1, glit: 1 };
TRAIL[TS.FALL]    = { life: [3.4, 4.3],  jit: 0.7, inh: 1.0,  drag: 0.5, grav: 1.0,  size: 0.5,  bright: 1.25, bb: 1, flick: 1 };
TRAIL[TS.FOUNT]   = { life: [0.8, 1.4],  jit: 0,   inh: 1.0,  drag: 0.9, grav: 1.0,  size: 0.48, bright: 1.9, col: 'silver', flick: 1 };
TRAIL[TS.GFOUNT]  = { life: [0.9, 1.6],  jit: 0,   inh: 1.0,  drag: 0.9, grav: 1.0,  size: 0.5,  bright: 1.6, bb: 1, flick: 1 };
for (const t of TRAIL) if (t && typeof t.col === 'string') t.col = pc(t.col);

const Sim = (() => {
  let MAX = 0, n = 0, dtNow = 1 / 60;
  let px, py, pz, vx, vy, vz, age, life, cr, cg, cb, c2r, c2g, c2b, tc, sz, br, dl, dq, gr, em, ac, t2, sd;
  let kd, fl, tr, ev, rf;
  const EVM = new Map(); let evSeq = 1;
  const P = {};
  let flashGain = 1;

  function init(max) {
    MAX = max; n = 0;
    const F = () => new Float32Array(MAX);
    px = F(); py = F(); pz = F(); vx = F(); vy = F(); vz = F(); age = F(); life = F();
    cr = F(); cg = F(); cb = F(); c2r = F(); c2g = F(); c2b = F(); tc = F(); sz = F(); br = F();
    dl = F(); dq = F(); gr = F(); em = F(); ac = F(); t2 = F(); sd = F();
    kd = new Uint8Array(MAX); fl = new Uint16Array(MAX); tr = new Uint8Array(MAX); ev = new Uint8Array(MAX); rf = new Int32Array(MAX);
    Object.assign(P, { px, py, pz, vx, vy, vz });
  }

  function spawn() {
    if (n >= MAX) return -1;
    const i = n++;
    age[i] = 0; fl[i] = 0; ev[i] = 0; em[i] = 0; ac[i] = 0; tr[i] = 0; tc[i] = -1;
    sd[i] = rnd(); gr[i] = 1; dl[i] = 0; dq[i] = 0; kd[i] = K_STAR;
    return i;
  }
  function kill(i) {
    if (ev[i] === EV_CALL) EVM.delete(rf[i]);
    const j = --n;
    if (i === j) return;
    px[i] = px[j]; py[i] = py[j]; pz[i] = pz[j]; vx[i] = vx[j]; vy[i] = vy[j]; vz[i] = vz[j];
    age[i] = age[j]; life[i] = life[j]; cr[i] = cr[j]; cg[i] = cg[j]; cb[i] = cb[j];
    c2r[i] = c2r[j]; c2g[i] = c2g[j]; c2b[i] = c2b[j]; tc[i] = tc[j]; sz[i] = sz[j]; br[i] = br[j];
    dl[i] = dl[j]; dq[i] = dq[j]; gr[i] = gr[j]; em[i] = em[j]; ac[i] = ac[j]; t2[i] = t2[j]; sd[i] = sd[j];
    kd[i] = kd[j]; fl[i] = fl[j]; tr[i] = tr[j]; ev[i] = ev[j]; rf[i] = rf[j];
  }

  // o: { life, lifeJ, col, col2, change, size, bright, dl, dq, grav, flags, trail, emit, ev, t2, t2J, fn }
  function star(x, y, z, ux, uy, uz, o) {
    const i = spawn(); if (i < 0) return -1;
    kd[i] = o.kind || K_STAR;
    px[i] = x; py[i] = y; pz[i] = z; vx[i] = ux; vy[i] = uy; vz[i] = uz;
    const lj = o.lifeJ === undefined ? 0.1 : o.lifeJ;
    life[i] = o.life * (1 + (rnd() * 2 - 1) * lj);
    const c = o.col; cr[i] = c[0]; cg[i] = c[1]; cb[i] = c[2];
    let f = o.flags || 0;
    if (o.col2) { const d = o.col2; c2r[i] = d[0]; c2g[i] = d[1]; c2b[i] = d[2]; tc[i] = life[i] * (o.change || 0.5); f |= F_CHANGE; }
    sz[i] = o.size; br[i] = o.bright; dl[i] = o.dl || 0; dq[i] = o.dq || 0; gr[i] = o.grav === undefined ? 1 : o.grav;
    fl[i] = f;
    if (o.trail) { tr[i] = o.trail; em[i] = o.emit || 20; ac[i] = rnd(); }
    if (o.ev) {
      ev[i] = o.ev;
      const tj = o.t2J === undefined ? 0.1 : o.t2J;
      t2[i] = o.t2 * (1 + (rnd() * 2 - 1) * tj);
      if (o.ev === EV_CALL) { rf[i] = evSeq; EVM.set(evSeq++, o.fn); }
    }
    return i;
  }
  function flash(x, y, z, size, bright, tau, col) {
    const i = spawn(); if (i < 0) return;
    kd[i] = K_FLASH; px[i] = x; py[i] = y; pz[i] = z; vx[i] = vy[i] = vz[i] = 0;
    life[i] = tau * 7; tc[i] = tau; sz[i] = size; br[i] = bright * flashGain;
    cr[i] = col[0]; cg[i] = col[1]; cb[i] = col[2];
  }
  function emitter(x, y, z, v, spread, rate, style, dur) {
    const i = spawn(); if (i < 0) return;
    kd[i] = K_EMIT; px[i] = x; py[i] = y; pz[i] = z; vx[i] = vy[i] = vz[i] = 0;
    life[i] = dur; c2r[i] = v[0]; c2g[i] = v[1]; c2b[i] = v[2]; tc[i] = spread;
    em[i] = rate; tr[i] = style; fl[i] = F_HIDDEN;
  }

  function sparkFrom(i, s, x, y, z, ux, uy, uz) {
    const j = spawn(); if (j < 0) return;
    const S = TRAIL[s];
    kd[j] = K_SPARK; px[j] = x; py[j] = y; pz[j] = z; vx[j] = ux; vy[j] = uy; vz[j] = uz;
    life[j] = rr(S.life[0], S.life[1]); sz[j] = S.size; br[j] = S.bright;
    dl[j] = S.drag; gr[j] = S.grav;
    fl[j] = (S.bb ? F_BLACKBODY : 0) | (S.flick ? F_FLICKER : 0) | (S.glit ? F_GLITTER : 0);
    if (S.tint) { cr[j] = cr[i]; cg[j] = cg[i]; cb[j] = cb[i]; }
    else if (S.col) { cr[j] = S.col[0]; cg[j] = S.col[1]; cb[j] = S.col[2]; }
  }
  function trailSpark(i) {
    const S = TRAIL[tr[i]], back = rnd() * dtNow, J = S.jit;
    sparkFrom(i, tr[i], px[i] - vx[i] * back, py[i] - vy[i] * back, pz[i] - vz[i] * back,
      vx[i] * S.inh + (rnd() * 2 - 1) * J, vy[i] * S.inh + (rnd() * 2 - 1) * J, vz[i] * S.inh + (rnd() * 2 - 1) * J);
  }
  function emitterSpark(i) {
    const s = tc[i];
    sparkFrom(i, tr[i], px[i] + rr(-1, 1), py[i] + rr(-0.5, 0.5), pz[i] + rr(-1, 1),
      c2r[i] + (rnd() * 2 - 1) * s, c2g[i] + (rnd() * 2 - 1) * s, c2b[i] + (rnd() * 2 - 1) * s);
  }

  // A crackling star ends in a tiny pop of white light.
  const POPC = [1, 0.93, 0.82];
  function pop(i) {
    flash(px[i], py[i], pz[i], 2.2, 11, 0.03, POPC);
    for (let k = 0; k < 3; k++) {
      const j = spawn(); if (j < 0) return;
      kd[j] = K_SPARK; px[j] = px[i]; py[j] = py[i]; pz[j] = pz[i];
      vx[j] = rr(-14, 14); vy[j] = rr(-14, 14); vz[j] = rr(-14, 14);
      life[j] = rr(0.1, 0.25); sz[j] = 0.4; br[j] = 2.6; dl[j] = 4; gr[j] = 0.2; fl[j] = F_FLICKER;
      cr[j] = 0.85; cg[j] = 0.9; cb[j] = 1;
    }
  }
  // A crossette star tears itself into four, flying out square to its path.
  const CROSS = { life: 0.95, lifeJ: 0.15, size: 1.0, bright: 2.4, dl: 0.6, dq: 0.012, trail: TS.SILVER, emit: 34 };
  function split(i) {
    const x = px[i], y = py[i], z = pz[i], ux = vx[i], uy = vy[i], uz = vz[i];
    const s0 = Math.hypot(ux, uy, uz) || 1, dx = ux / s0, dy = uy / s0, dz = uz / s0;
    let ax = 0, ay = 1; if (Math.abs(dy) > 0.9) { ax = 1; ay = 0; }
    let e1x = dy * 0 - dz * ay, e1y = dz * ax - dx * 0, e1z = dx * ay - dy * ax;
    const l1 = Math.hypot(e1x, e1y, e1z) || 1; e1x /= l1; e1y /= l1; e1z /= l1;
    const e2x = dy * e1z - dz * e1y, e2y = dz * e1x - dx * e1z, e2z = dx * e1y - dy * e1x;
    const rot = rnd() * TAU, s = 36;
    CROSS.col = [cr[i], cg[i], cb[i]];
    CROSS.trail = tr[i] || TS.SILVER;
    for (let k = 0; k < 4; k++) {
      const a = rot + k * TAU / 4, c = Math.cos(a), sn = Math.sin(a);
      star(x, y, z, ux * 0.3 + (e1x * c + e2x * sn) * s, uy * 0.3 + (e1y * c + e2y * sn) * s, uz * 0.3 + (e1z * c + e2z * sn) * s, CROSS);
    }
    flash(x, y, z, 2.4, 9, 0.03, POPC);
  }

  function update(dt) {
    if (dt <= 0) return;
    dtNow = dt;
    const wx = WORLD.wind, load = n / MAX;
    const emitScale = load < 0.5 ? 1 : Math.max(0.1, 1 - (load - 0.5) / 0.42);
    for (let i = n - 1; i >= 0; i--) {
      const a = age[i] + dt; age[i] = a;
      if (a >= life[i]) { if (fl[i] & F_CRACKLE) pop(i); kill(i); continue; }
      const k = kd[i];
      if (k === K_FLASH) continue;
      if (k === K_EMIT) {
        const env = smoothstep(0, 0.6, a) * (1 - smoothstep(life[i] - 1.2, life[i], a));
        ac[i] += em[i] * dt * env * emitScale;
        while (ac[i] >= 1) { ac[i] -= 1; emitterSpark(i); }
        continue;
      }
      let ux = vx[i] - wx, uy = vy[i], uz = vz[i];
      const drag = dl[i] + dq[i] * Math.sqrt(ux * ux + uy * uy + uz * uz);
      if (drag > 0) { const f = 1 / (1 + drag * dt); ux *= f; uy *= f; uz *= f; }
      uy -= G * gr[i] * dt;
      const f0 = fl[i];
      if (f0 & (F_FISH | F_FLUTTER)) {
        if (f0 & F_FISH) {
          const w = Math.sin(a * 13 + sd[i] * 40) * 7 * dt, c = Math.cos(w), s = Math.sin(w);
          const nx = ux * c - uy * s; uy = ux * s + uy * c; ux = nx;
          const sp = Math.hypot(ux, uy, uz), want = 16;
          if (sp > 0.01 && sp < want) { const m = 1 + (want - sp) / sp * Math.min(1, 2.5 * dt); ux *= m; uy *= m; uz *= m; }
        } else {
          ux += Math.sin(a * 4.3 + sd[i] * 50) * 14 * dt;
          uz += Math.cos(a * 3.1 + sd[i] * 20) * 8 * dt;
        }
      }
      vx[i] = ux + wx; vy[i] = uy; vz[i] = uz;
      px[i] += vx[i] * dt; py[i] += uy * dt; pz[i] += uz * dt;
      if (py[i] < 0.3) { kill(i); continue; }
      if (ev[i] && a >= t2[i]) {
        const e = ev[i]; ev[i] = 0;
        if (e === EV_SPLIT) { split(i); kill(i); continue; }
        const fn = EVM.get(rf[i]); EVM.delete(rf[i]);
        if (!fn || fn(i) !== false) { kill(i); continue; }
      }
      if ((f0 & F_CHANGE) && a >= tc[i]) { cr[i] = c2r[i]; cg[i] = c2g[i]; cb[i] = c2b[i]; fl[i] = f0 & ~F_CHANGE; }
      if (em[i] > 0) {
        ac[i] += em[i] * dt * emitScale;
        while (ac[i] >= 1) { ac[i] -= 1; trailSpark(i); }
      }
    }
    updateLights(dt);
    updateSmoke(dt);
  }

  // Instance stream for the renderer: position+size, velocity, colour*intensity.
  const RAMP = [0, 0, 0];
  function pack(out) {
    let o = 0, m = 0;
    for (let i = 0; i < n; i++) {
      const f = fl[i];
      if (f & F_HIDDEN) continue;
      const a = age[i], u = a / life[i], k = kd[i];
      let I = br[i], r = cr[i], g = cg[i], b = cb[i];
      if (k === K_FLASH) I *= Math.exp(-a / tc[i]);
      else if (k === K_SPARK) {
        if (f & F_BLACKBODY) { sparkRamp(u, RAMP); r = RAMP[0]; g = RAMP[1]; b = RAMP[2]; }
        I *= (1 - u) * (1 - 0.5 * u);
      } else {
        I *= Math.min(1, a / 0.05) * (1 - 0.3 * u) * (0.78 + 0.44 * sd[i]) * (0.9 + 0.2 * rnd());
        I *= (f & F_SYNC) ? 1 - smoothstep(0.94, 1, u) : 1 - smoothstep(0.7, 1, u);
        if (f & F_STROBE) {
          const hz = 6.5 + sd[i] * 6;
          I = (a > 0.35 && (a * hz + sd[i] * 7) % 1 < 0.3) ? I * 2.4 * flashGain : (a > 0.35 ? 0 : I * 0.4);
        }
      }
      if (f & F_FLICKER) I *= 0.5 + rnd();
      if (f & F_GLITTER) I *= rnd() < 0.2 ? 3.4 : 0.28;
      if (I < 0.004) continue;
      out[o] = px[i]; out[o + 1] = py[i]; out[o + 2] = pz[i]; out[o + 3] = sz[i];
      out[o + 4] = vx[i]; out[o + 5] = vy[i]; out[o + 6] = vz[i];
      out[o + 7] = r * I; out[o + 8] = g * I; out[o + 9] = b * I;
      o += 10; m++;
    }
    return m;
  }

  // Light sources: each burst lights the smoke, the sky, the skyline and the crowd.
  const lights = [];
  function light(x, y, z, col, I, R, dur, flashI) {
    if (lights.length > 90) return;
    lights.push({ x, y, z, r: col[0], g: col[1], b: col[2], I: I * 0.2, R, dur, fI: (flashI || 0) * 0.2 * flashGain, age: 0, cur: 0, rad: R });
  }
  function updateLights(dt) {
    for (let k = lights.length - 1; k >= 0; k--) {
      const L = lights[k]; L.age += dt;
      if (L.age > L.dur + 0.6) { lights.splice(k, 1); continue; }
      const u = L.age / L.dur;
      L.cur = L.I * Math.min(1, L.age / 0.1) * (1 - smoothstep(0.65, 1.05, u)) + L.fI * Math.exp(-L.age / 0.07);
      L.rad = L.R * (0.35 + 0.65 * (1 - Math.exp(-L.age / 0.35)));
    }
  }
  function topLights(N) {
    const s = lights.filter((L) => L.cur > 0.002);
    s.sort((a, b) => b.cur - a.cur);
    return s.length > N ? s.slice(0, N) : s;
  }
  // Overall colour of the light the show is throwing on the harbour right now.
  function ambientFlash(out) {
    let r = 0, g = 0, b = 0;
    for (const L of lights) { const w = L.cur * 1100 / Math.hypot(L.x, L.y, L.z); r += L.r * w; g += L.g * w; b += L.b * w; }
    const k = 1 / (1 + 0.12 * (r + g + b) / 3);
    out[0] = r * k; out[1] = g * k; out[2] = b * k;
    return out;
  }

  // Smoke: soft puffs that drift downwind, rise, spread, and glow when a burst lights them.
  const SMAX = 1100; let sn = 0;
  const S = {}; for (const k of ['x', 'y', 'z', 'vx', 'vy', 'vz', 'r', 'dr', 'age', 'life', 'dens', 'seed']) S[k] = new Float32Array(SMAX);
  function puff(x, y, z, ux, uy, uz, r, dr, lifeT, dens) {
    if (sn >= SMAX) return;
    const i = sn++;
    S.x[i] = x; S.y[i] = y; S.z[i] = z; S.vx[i] = ux; S.vy[i] = uy; S.vz[i] = uz;
    S.r[i] = r; S.dr[i] = dr; S.age[i] = 0; S.life[i] = lifeT; S.dens[i] = dens; S.seed[i] = rnd();
  }
  function updateSmoke(dt) {
    const k = 1 - Math.exp(-dt / 2.5), wx = WORLD.wind * 0.9, dd = Math.exp(-dt / 7);
    for (let i = sn - 1; i >= 0; i--) {
      S.age[i] += dt;
      if (S.age[i] > S.life[i]) {
        const j = --sn;
        if (i !== j) for (const key in S) S[key][i] = S[key][j];
        continue;
      }
      S.vx[i] += (wx - S.vx[i]) * k; S.vy[i] += (0.45 - S.vy[i]) * k; S.vz[i] += (0 - S.vz[i]) * k;
      S.x[i] += S.vx[i] * dt; S.y[i] += S.vy[i] * dt; S.z[i] += S.vz[i] * dt;
      S.r[i] += S.dr[i] * dt; S.dr[i] *= dd;
    }
  }
  function packSmoke(out) {
    for (let i = 0; i < sn; i++) {
      const u = S.age[i] / S.life[i];
      const a = S.dens[i] * smoothstep(0, 0.12, u) * Math.pow(1 - u, 1.4);
      const o = i * 8;
      out[o] = S.x[i]; out[o + 1] = S.y[i]; out[o + 2] = S.z[i]; out[o + 3] = S.r[i];
      out[o + 4] = a; out[o + 5] = S.seed[i]; out[o + 6] = u; out[o + 7] = 0;
    }
    return sn;
  }

  function clear(keepSmoke) {
    n = 0; EVM.clear(); lights.length = 0;
    if (!keepSmoke) sn = 0;
  }

  return {
    init, star, flash, emitter, light, puff, update, pack, packSmoke, topLights, ambientFlash, clear, P,
    get count() { return n; }, get max() { return MAX; }, get smokeCount() { return sn; },
    set flashGain(v) { flashGain = v; }, get flashGain() { return flashGain; },
    SMAX,
  };
})();

/* Nocturne · Pyro: the shell library and ground devices. Burst sizes follow the trade rule
   of thumb: a shell of D millimetres breaks about D metres across. */
const Pyro = (() => {
  const P = Sim.P;

  function randRot() {
    const u1 = rnd(), u2 = rnd() * TAU, u3 = rnd() * TAU, a = Math.sqrt(1 - u1), b = Math.sqrt(u1);
    const x = a * Math.sin(u2), y = a * Math.cos(u2), z = b * Math.sin(u3), w = b * Math.cos(u3);
    return [1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w),
      2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w),
      2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)];
  }
  // Evenly spread directions on a sphere (Fibonacci lattice), randomly turned.
  function fib(n, jit) {
    const out = new Float32Array(n * 3), ga = Math.PI * (3 - Math.sqrt(5)), m = randRot();
    for (let k = 0; k < n; k++) {
      const y0 = 1 - 2 * (k + 0.5) / n, r = Math.sqrt(1 - y0 * y0), th = ga * k;
      let x = Math.cos(th) * r, y = y0, z = Math.sin(th) * r;
      if (jit) { x += (rnd() * 2 - 1) * jit; y += (rnd() * 2 - 1) * jit; z += (rnd() * 2 - 1) * jit; const l = Math.hypot(x, y, z); x /= l; y /= l; z /= l; }
      out[k * 3] = m[0] * x + m[1] * y + m[2] * z;
      out[k * 3 + 1] = m[3] * x + m[4] * y + m[5] * z;
      out[k * 3 + 2] = m[6] * x + m[7] * y + m[8] * z;
    }
    return out;
  }
  // Launch speed that carries a star R metres in T seconds against drag.
  function solveV0(R, T, dL, dQ) {
    let lo = 1, hi = 5000;
    const h = 1 / 90;
    for (let it = 0; it < 20; it++) {
      const v0 = (lo + hi) / 2;
      let v = v0, x = 0;
      for (let t = 0; t < T; t += h) { v /= 1 + (dL + dQ * v) * h; x += v * h; }
      if (x < R) lo = v0; else hi = v0;
    }
    return (lo + hi) / 2;
  }
  function sphere(x, y, z, bv, n, R, T, o, jit, spJ, colFn) {
    const d = fib(n, jit === undefined ? 0.03 : jit), v0 = solveV0(R, T, o.dl || 0, o.dq || 0), sj = spJ === undefined ? 0.03 : spJ;
    for (let k = 0; k < n; k++) {
      const dx = d[k * 3], dy = d[k * 3 + 1], dz = d[k * 3 + 2], s = v0 * (1 + (rnd() * 2 - 1) * sj);
      if (colFn) o.col = colFn(dx, dy, dz, k);
      Sim.star(x, y, z, bv[0] + dx * s, bv[1] + dy * s, bv[2] + dz * s, o);
    }
  }
  // Stars laid out on a flat figure (ring, heart, star). Linear drag keeps the figure's shape.
  function figure(x, y, z, bv, pts, R, T, e1, e2, o) {
    const dL = o.dl, v0 = R * dL / (1 - Math.exp(-dL * T));
    for (let k = 0; k < pts.length; k += 2) {
      const a = pts[k], b = pts[k + 1];
      Sim.star(x, y, z, bv[0] + (e1[0] * a + e2[0] * b) * v0, bv[1] + (e1[1] * a + e2[1] * b) * v0, bv[2] + (e1[2] * a + e2[2] * b) * v0, o);
    }
  }
  function basis(nx, ny, nz) {
    const l = Math.hypot(nx, ny, nz); nx /= l; ny /= l; nz /= l;
    let ax = 0, ay = 1, az = 0; if (Math.abs(ny) > 0.9) { ax = 1; ay = 0; }
    let e1 = [ay * nz - az * ny, az * nx - ax * nz, ax * ny - ay * nx];
    const m = Math.hypot(e1[0], e1[1], e1[2]); e1 = [e1[0] / m, e1[1] / m, e1[2] / m];
    const e2 = [ny * e1[2] - nz * e1[1], nz * e1[0] - nx * e1[2], nx * e1[1] - ny * e1[0]];
    return [e1, e2];
  }
  // A figure plane that faces the audience, turned a little so it reads as a solid shape.
  function facing(yaw, tilt) {
    const e1 = [Math.cos(yaw), 0, Math.sin(yaw)];
    const e2 = [-Math.sin(yaw) * Math.sin(tilt), Math.cos(tilt), Math.cos(yaw) * Math.sin(tilt)];
    return [e1, e2];
  }
  const circlePts = (m) => { const p = []; for (let k = 0; k < m; k++) { const a = k / m * TAU; p.push(Math.cos(a), Math.sin(a)); } return p; };

  const lifeOf = (cal, base) => base + cal / 320;
  const col = (c) => (Array.isArray(c) ? c : pc(c));

  // Each shell returns the colour and strength of the light it throws, and how long it burns.
  const SHELLS = {
    peony(s, x, y, z, bv) {
      const cal = s.cal, R = cal * 0.5 * (s.spread || 1), n = s.n || Math.round(cal * 0.95 + 40), life = s.life || lifeOf(cal, 1.65);
      const o = { life, lifeJ: 0.03, col: col(s.color), size: 1.0 + cal / 330, bright: 3.3, dl: 0.3, dq: 1.7 / R, flags: F_SYNC };
      if (s.to) { o.col2 = col(s.to); o.change = s.at || 0.5; }
      const colFn = s.split ? ((dx, dy) => (dy > 0 ? pc(s.split[0]) : pc(s.split[1]))) : null;
      sphere(x, y, z, bv, n, R, 0.72 + cal / 1000, o, 0.03, 0.03, colFn);
      if (s.petal2) sphere(x, y, z, bv, Math.round(n * 0.6), R * 0.68, 0.72, { ...o, col: col(s.petal2), col2: null, size: o.size * 0.9 });
      if (s.pistil) sphere(x, y, z, bv, Math.round(n * 0.38), R * (s.petal2 ? 0.36 : 0.42), 0.7, { ...o, col: col(s.pistil), col2: null, size: o.size * 0.85, bright: 3.6, life: life * 0.9 });
      return { col: col(s.split ? s.split[0] : s.color), I: n * 0.03, dur: life };
    },
    chrys(s, x, y, z, bv) {
      const cal = s.cal, R = cal * 0.5 * (s.spread || 1), n = s.n || Math.round(cal * 0.85 + 36), life = s.life || lifeOf(cal, 1.9);
      const tint = s.tail === 'tint';
      const o = { life, lifeJ: 0.04, col: col(s.color), size: 0.95 + cal / 400, bright: tint ? 2.8 : 2.3, dl: 0.3, dq: 1.8 / R, flags: F_SYNC, trail: tint ? TS.TINT : TS.GOLD, emit: tint ? 30 : 24 };
      if (s.to) { o.col2 = col(s.to); o.change = s.at || 0.5; }
      sphere(x, y, z, bv, n, R, 0.78 + cal / 1000, o);
      if (s.pistil) sphere(x, y, z, bv, Math.round(n * 0.4), R * 0.42, 0.7, { ...o, col: col(s.pistil), col2: null, trail: 0, emit: 0, size: o.size * 0.9, bright: 3.6, life: life * 0.85 });
      return { col: col(s.color), I: n * 0.026, dur: life };
    },
    willow(s, x, y, z, bv) {
      const cal = s.cal, R = cal * 0.42, n = s.n || Math.round(cal * 0.5 + 40), life = lifeOf(cal, 4.3);
      const o = { life, lifeJ: 0.12, col: pc('gold'), size: 0.75, bright: 0.9, dl: 1.05, dq: 2.3 / R, trail: TS.WILLOW, emit: 30, flags: F_FLICKER };
      if (s.tip) { o.col2 = pc(s.tip); o.change = 0.8; }
      sphere(x, y, z, bv, n, R, 1.1, o, 0.05);
      return { col: pc('gold'), I: n * 0.024, dur: life * 0.8 };
    },
    kamuro(s, x, y, z, bv) {
      const cal = s.cal, R = cal * 0.5, n = s.n || Math.round(cal * 0.75 + 50), life = lifeOf(cal, 4.9);
      sphere(x, y, z, bv, n, R, 1.0, { life, lifeJ: 0.08, col: [1, 0.62, 0.26], size: 0.8, bright: 1.3, dl: 0.9, dq: 2.1 / R, trail: TS.BROCADE, emit: 36, flags: F_GLITTER }, 0.04);
      return { col: pc('gold'), I: n * 0.03, dur: life * 0.8 };
    },
    horsetail(s, x, y, z, bv) {
      const cal = s.cal, R = cal * 0.32, n = Math.round(cal * 0.4 + 30), d = fib(n, 0.05);
      const o = { life: lifeOf(cal, 3.3), lifeJ: 0.15, col: [1, 0.62, 0.26], size: 0.8, bright: 1.2, dl: 0.9, dq: 2 / R, trail: TS.BROCADE, emit: 32, flags: F_GLITTER };
      const v0 = solveV0(R, 0.8, o.dl, o.dq);
      for (let k = 0; k < n; k++) {
        let dx = d[k * 3] * 0.8, dy = -Math.abs(d[k * 3 + 1]) * 0.7 - 0.3, dz = d[k * 3 + 2] * 0.8;
        const l = Math.hypot(dx, dy, dz); dx /= l; dy /= l; dz /= l;
        Sim.star(x, y, z, bv[0] + dx * v0, bv[1] + dy * v0, bv[2] + dz * v0, o);
      }
      return { col: pc('gold'), I: n * 0.03, dur: o.life };
    },
    palm(s, x, y, z, bv) {
      const cal = s.cal, R = cal * 0.45, n = s.n || 9, c = col(s.color || 'gold');
      const o = { life: lifeOf(cal, 2.4), lifeJ: 0.08, col: c, size: 2.0, bright: 3.4, dl: 0.25, dq: 1.2 / R, trail: s.glitter ? TS.GLITTER : TS.BROCADE, emit: s.glitter ? 120 : 150, flags: s.crackle ? F_CRACKLE : 0 };
      const v0 = solveV0(R, 0.9, o.dl, o.dq), off = rnd() * TAU;
      for (let k = 0; k < n; k++) {
        const az = off + k / n * TAU + rr(-0.15, 0.15), el = rr(0.05, 0.75);
        const dx = Math.cos(el) * Math.cos(az), dy = Math.sin(el), dz = Math.cos(el) * Math.sin(az);
        Sim.star(x, y, z, bv[0] + dx * v0, bv[1] + dy * v0, bv[2] + dz * v0, o);
      }
      if (s.crackle) Sound.crackle(x, y, z, 0.5, o.life - 0.2);
      return { col: pc('gold'), I: 3.5, dur: o.life };
    },
    ring(s, x, y, z, bv) {
      const cal = s.cal, R = cal * 0.5, m = s.n || Math.round(40 + cal / 8);
      const tilt = s.tilt === undefined ? rr(-0.35, 0.35) : s.tilt;
      const [e1, e2] = basis(Math.sin(tilt) * 1.2, rr(-0.25, 0.25), -1);
      const o = { life: lifeOf(cal, 1.8), lifeJ: 0.03, col: col(s.color), size: 1.2, bright: 3.4, dl: 1.5, grav: 0.5, flags: F_SYNC };
      figure(x, y, z, bv, circlePts(m), R, 0.85, e1, e2, o);
      if (s.core) sphere(x, y, z, bv, 26, R * 0.25, 0.6, { ...o, col: col(s.core), dq: 0.05, dl: 0.4, size: 1.0 });
      return { col: col(s.color), I: m * 0.04, dur: o.life };
    },
    orbit(s, x, y, z, bv) {
      const cal = s.cal, R = cal * 0.5, cs = s.colors || ['red', 'aqua', 'gold'];
      const o = { life: lifeOf(cal, 2.0), lifeJ: 0.03, size: 1.15, bright: 3.2, dl: 1.5, grav: 0.4, flags: F_SYNC };
      cs.forEach((c, k) => {
        const a = k / cs.length * Math.PI + 0.3;
        const [e1, e2] = basis(Math.cos(a) * 0.9, Math.sin(a) * 0.9, -0.6);
        figure(x, y, z, bv, circlePts(54), R * (1 - k * 0.04), 0.85, e1, e2, { ...o, col: pc(c) });
      });
      return { col: pc(cs[0]), I: 5, dur: o.life };
    },
    saturn(s, x, y, z, bv) {
      const cal = s.cal, R = cal * 0.5, life = lifeOf(cal, 2.0);
      sphere(x, y, z, bv, Math.round(cal * 0.45 + 20), R * 0.4, 0.7, { life, lifeJ: 0.03, col: col(s.color), size: 1.1, bright: 3.3, dl: 0.4, dq: 3 / R, flags: F_SYNC });
      const [e1, e2] = basis(rr(-0.12, 0.12), 1, rr(-0.55, -0.35));
      figure(x, y, z, bv, circlePts(72), R * 1.05, 0.85, e1, e2, { life, lifeJ: 0.03, col: col(s.ring || 'gold'), size: 1.1, bright: 3.3, dl: 1.5, grav: 0.4, flags: F_SYNC });
      return { col: col(s.color), I: 5, dur: life };
    },
    heart(s, x, y, z, bv) {
      const cal = s.cal, R = cal * 0.55, m = s.n || 72, pts = [];
      for (let k = 0; k < m; k++) {
        const t = k / m * TAU, sn = Math.sin(t);
        pts.push(16 * sn * sn * sn / 17, (13 * Math.cos(t) - 5 * Math.cos(2 * t) - 2 * Math.cos(3 * t) - Math.cos(4 * t) + 3) / 17);
      }
      const [e1, e2] = facing(rr(-0.35, 0.35), rr(-0.15, 0.15));
      const o = { life: lifeOf(cal, 2.0), lifeJ: 0.03, col: col(s.color || 'pink'), size: 1.2, bright: 3.4, dl: 1.5, grav: 0.3, flags: F_SYNC };
      figure(x, y, z, bv, pts, R, 0.85, e1, e2, o);
      return { col: o.col, I: 5, dur: o.life };
    },
    star5(s, x, y, z, bv) {
      const cal = s.cal, R = cal * 0.55, pts = [], per = 9;
      const vtx = []; for (let k = 0; k < 10; k++) { const a = Math.PI / 2 + k * Math.PI / 5, r = k % 2 ? 0.42 : 1; vtx.push([Math.cos(a) * r, Math.sin(a) * r]); }
      for (let k = 0; k < 10; k++) { const A = vtx[k], B = vtx[(k + 1) % 10]; for (let j = 0; j < per; j++) { const t = j / per; pts.push(lerp(A[0], B[0], t), lerp(A[1], B[1], t)); } }
      const [e1, e2] = facing(rr(-0.3, 0.3), rr(-0.1, 0.1));
      const o = { life: lifeOf(cal, 2.0), lifeJ: 0.03, col: col(s.color || 'gold'), size: 1.15, bright: 3.4, dl: 1.5, grav: 0.3, flags: F_SYNC };
      figure(x, y, z, bv, pts, R, 0.85, e1, e2, o);
      return { col: o.col, I: 5, dur: o.life };
    },
    crossette(s, x, y, z, bv) {
      const cal = s.cal, R = cal * 0.55, n = s.n || Math.round(10 + cal / 14);
      sphere(x, y, z, bv, n, R * 0.55, 0.8, { life: 2, col: col(s.color || 'silver'), size: 1.25, bright: 2.8, dl: 0.4, dq: 1.2 / R, trail: s.color === 'gold' ? TS.GOLD : TS.SILVER, emit: 30, ev: EV_SPLIT, t2: 0.8, t2J: 0.08 }, 0.08);
      Sound.pops(x, y, z, 0.78, 0.5);
      return { col: col(s.color || 'silver'), I: 3, dur: 1.8 };
    },
    strobe(s, x, y, z, bv) {
      const cal = s.cal, R = cal * 0.5 * 0.55, n = Math.round(cal * 0.55 + 30);
      sphere(x, y, z, bv, n, R, 0.9, { life: lifeOf(cal, 2.6), lifeJ: 0.2, col: col(s.color || 'white'), size: 1.0, bright: 3.6, dl: 0.8, dq: 2.4 / R, flags: F_STROBE }, 0.06, 0.12);
      return { col: pc('white'), I: n * 0.015, dur: 3 };
    },
    crackle(s, x, y, z, bv) {
      const cal = s.cal, R = cal * 0.5 * 0.68, n = Math.round(cal * 0.9 + 50);
      sphere(x, y, z, bv, n, R, 0.75, { life: 1.1, lifeJ: 0.35, col: [1, 0.6, 0.22], size: 0.75, bright: 1.2, dl: 0.4, dq: 1.9 / R, trail: TS.GOLD, emit: 10, flags: F_CRACKLE }, 0.05, 0.08);
      Sound.crackle(x, y, z, n / 250, 0.6);
      return { col: pc('gold'), I: n * 0.02, dur: 1.5 };
    },
    senrin(s, x, y, z, bv) {
      const cal = s.cal, R = cal * 0.5 * 0.62, n = s.n || 30, pal = s.palette || ['red', 'green', 'gold', 'violet', 'aqua'];
      const fn = (i) => {
        const c = pc(pal[(rnd() * pal.length) | 0]), X = P.px[i], Y = P.py[i], Z = P.pz[i];
        sphere(X, Y, Z, [P.vx[i] * 0.3, P.vy[i] * 0.3, P.vz[i] * 0.3], 16, rr(9, 14), 0.4, { life: 1.1, lifeJ: 0.1, col: c, size: 0.9, bright: 3.2, dl: 0.5, dq: 0.12, flags: 0 }, 0.08);
        Sim.flash(X, Y, Z, 3, 10, 0.035, [1, 0.9, 0.8]);
        Sim.light(X, Y, Z, c, 0.4, 25, 1.1, 0.5);
      };
      sphere(x, y, z, bv, n, R, 0.9, { life: 3, col: [1, 0.6, 0.25], size: 0.6, bright: 1.1, dl: 0.4, dq: 1.5 / R, trail: TS.GOLD, emit: 8, ev: EV_CALL, t2: 1.0, t2J: 0.3, fn }, 0.1, 0.15);
      Sound.crackle(x, y, z, 0.35, 0.8);
      return { col: pc('gold'), I: 1.5, dur: 1.4 };
    },
    salute(s, x, y, z, bv) {
      Sim.flash(x, y, z, 26, 55, 0.07, [1, 0.97, 0.92]);
      sphere(x, y, z, bv, 60, 22, 0.2, { life: 0.35, lifeJ: 0.3, col: pc('silver'), size: 0.6, bright: 3.4, dl: 3, dq: 0.02 }, 0.2, 0.3);
      return { col: [1, 0.96, 0.9], I: 0, flashI: 40, dur: 0.3, salute: true };
    },
    dahlia(s, x, y, z, bv) {
      const cal = s.cal, R = cal * 0.5 * 0.9, n = Math.round(cal * 0.22 + 18), life = lifeOf(cal, 2.8);
      sphere(x, y, z, bv, n, R, 0.8, { life, lifeJ: 0.05, col: col(s.color), size: 1.9, bright: 3.4, dl: 0.35, dq: 1.4 / R, trail: TS.TINT, emit: 18, flags: F_SYNC });
      return { col: col(s.color), I: n * 0.07, dur: life };
    },
    fish(s, x, y, z, bv) {
      const cal = s.cal, R = cal * 0.5 * 0.35, n = s.n || 28;
      sphere(x, y, z, bv, n, R, 0.6, { life: 2.4, lifeJ: 0.2, col: pc('silver'), size: 0.6, bright: 1.0, dl: 0.3, dq: 0.01, grav: 0.12, trail: TS.SILVER, emit: 46, flags: F_FISH }, 0.1, 0.2);
      return { col: pc('silver'), I: 1.5, dur: 2.4 };
    },
    glitter(s, x, y, z, bv) {
      const cal = s.cal, R = cal * 0.5, n = Math.round(cal * 0.7 + 30), life = lifeOf(cal, 2.4);
      sphere(x, y, z, bv, n, R, 0.8, { life, lifeJ: 0.05, col: col(s.color || 'white'), size: 0.9, bright: 2.2, dl: 0.5, dq: 1.9 / R, trail: TS.GLITTER, emit: 22, flags: F_SYNC });
      return { col: col(s.color || 'white'), I: n * 0.025, dur: life };
    },
    spider(s, x, y, z, bv) {
      const cal = s.cal, R = cal * 0.5 * 1.05, n = Math.round(cal * 0.5 + 40);
      sphere(x, y, z, bv, n, R, 0.55, { life: 1.3, lifeJ: 0.04, col: [1, 0.66, 0.3], size: 0.9, bright: 2.2, dl: 0.15, dq: 0.8 / R, trail: TS.GOLD, emit: 70, flags: F_SYNC });
      return { col: pc('gold'), I: n * 0.03, dur: 1.4 };
    },
    leaves(s, x, y, z, bv) {
      const cal = s.cal, R = cal * 0.5 * 0.6, n = Math.round(cal * 0.25 + 30);
      sphere(x, y, z, bv, n, R, 0.7, { life: 4.6, lifeJ: 0.3, col: col(s.color || 'gold'), size: 0.75, bright: 1.7, dl: 2.6, dq: 0.02, grav: 0.3, flags: F_GLITTER | F_FLUTTER }, 0.1, 0.25);
      return { col: pc('gold'), I: 2, dur: 4 };
    },
  };

  function burst(s, x, y, z, bv) {
    const fn = SHELLS[s.type] || SHELLS.peony;
    const cal = s.cal || 150;
    const res = fn(s, x, y, z, bv);
    if (!res.salute) Sim.flash(x, y, z, 4 + cal * 0.03, 12 + cal * 0.06, 0.05, [1, 0.88, 0.7]);
    Sim.light(x, y, z, res.col, res.I, cal * 0.65, res.dur, res.flashI || (4 + cal * 0.04));
    const R = cal * 0.5, puffs = Math.round(2 + cal / 60);
    for (let k = 0; k < puffs; k++) {
      Sim.puff(x + rr(-0.4, 0.4) * R, y + rr(-0.3, 0.3) * R, z + rr(-0.3, 0.3) * R, rr(-2, 2), rr(-1, 1), rr(-2, 2),
        R * rr(0.35, 0.6), rr(1.2, 3), rr(26, 42), rr(0.035, 0.075));
    }
    if (res.salute) Sound.salute(x, y, z); else Sound.burst(x, y, z, cal);
    return res;
  }

  // A shell leaves its mortar and climbs, slowing, to break at its apex T seconds later.
  function launch(s, from, to, T) {
    const x0 = from.x + rr(-5, 5), z0 = from.z + rr(-4, 4), y0 = 2.5;
    const g = 2 * (to.y - y0) / (T * T);
    const palm = s.tail === 'palm', none = s.tail === 'none';
    Sim.star(x0, y0, z0, (to.x - x0) / T, g * T, (to.z - z0) / T, {
      life: T + 1, lifeJ: 0, col: s.tail === 'silver' ? pc('silver') : [1, 0.7, 0.38],
      size: palm ? 1.6 : 0.85, bright: none ? 0.35 : palm ? 3 : 2.2, grav: g / G,
      trail: none ? 0 : palm ? TS.BROCADE : s.tail === 'silver' ? TS.SILVER : TS.LIFT, emit: palm ? 170 : 42,
      ev: EV_CALL, t2: T, t2J: 0,
      fn: (i) => { burst(s, P.px[i], P.py[i], P.pz[i], [P.vx[i] * 0.2, 0, P.vz[i] * 0.2]); },
    });
    Sim.flash(x0, 3, z0, 3.2, 8, 0.05, [1, 0.78, 0.5]);
    Sim.light(x0, 5, z0, [1, 0.6, 0.3], 0.2, 40, 0.3, 2.5);
    Sim.puff(x0, 7, z0, 0, 3, 0, 4, 2.4, rr(12, 22), 0.2);
    Sound.launch(x0, z0, s.cal || 150);
    if (s.whistle) Sound.whistle(x0, z0, T * 0.9);
  }

  // Ground devices on the barges.
  function fanShot(b, angle, s) {
    const sp = s.speed || rr(92, 112), dx = Math.sin(angle), dy = Math.cos(angle), dz = rr(-0.08, 0.08);
    const c = s.color ? pc(s.color) : (s.tail === 'silver' ? pc('silver') : [1, 0.66, 0.3]);
    Sim.star(b.x + rr(-3, 3), 3, b.z + rr(-3, 3), dx * sp, dy * sp, dz * sp, {
      life: rr(1.5, 2.0), lifeJ: 0.05, col: c, size: 1.4, bright: 2.8, dl: 0.25, dq: 0.0045,
      trail: s.tail === 'silver' ? TS.SILVER : s.tail === 'tint' ? TS.TINT : TS.GOLD, emit: 80, flags: s.pop ? F_CRACKLE : 0,
    });
    Sim.flash(b.x, 3, b.z, 2.4, 5, 0.04, [1, 0.8, 0.5]);
    Sound.launch(b.x, b.z, 60);
  }
  function mine(b, s) {
    const n = s.n || 40, cs = s.colors || ['gold'];
    for (let k = 0; k < n; k++) {
      const az = rnd() * TAU, off = Math.sqrt(rnd()) * 0.38, sp = rr(55, 105);
      const dx = Math.sin(off) * Math.cos(az), dy = Math.cos(off), dz = Math.sin(off) * Math.sin(az);
      Sim.star(b.x + rr(-2, 2), 3, b.z + rr(-2, 2), dx * sp, dy * sp, dz * sp, {
        life: rr(1.3, 1.9), lifeJ: 0.1, col: pc(cs[k % cs.length]), size: 1.2, bright: 3, dl: 0.3, dq: 0.009, trail: TS.TINT, emit: 22, flags: s.crackle ? F_CRACKLE : 0,
      });
    }
    Sim.flash(b.x, 4, b.z, 7, 14, 0.06, [1, 0.85, 0.6]);
    Sim.light(b.x, 30, b.z, pc(cs[0]), 1.2, 90, 1.6, 3);
    Sim.puff(b.x, 8, b.z, 0, 4, 0, 7, 3, rr(15, 25), 0.22);
    Sound.launch(b.x, b.z, 180);
    if (s.crackle) Sound.crackle(b.x, 90, b.z, 0.4, 1.3);
  }
  function candleShot(b, s) {
    const c = pc(s.color || 'gold');
    Sim.star(b.x + rr(-3, 3), 3, b.z + rr(-2, 2), rr(-5, 5) + (s.lean || 0), rr(72, 86), rr(-3, 3), {
      life: 1.9, lifeJ: 0.05, col: c, size: 1.6, bright: 3.3, dl: 0.2, dq: 0.0085, trail: TS.TINT, emit: 32, flags: F_SYNC,
    });
    Sim.flash(b.x, 3, b.z, 2.6, 6, 0.04, c);
    Sound.launch(b.x, b.z, 50);
  }
  function fountain(b, dur, s) {
    const gold = s && s.gold;
    Sim.emitter(b.x, 3, b.z, [0, 30, 0], 5.5, 380, gold ? TS.GFOUNT : TS.FOUNT, dur);
    Sim.light(b.x, 25, b.z, gold ? pc('gold') : pc('silver'), 0.9, 70, dur, 0);
    Sound.hiss(b.x, b.z, dur, 0.1);
  }
  // Niagara: a curtain of gold sparks poured from the bridge deck into the harbour.
  function waterfall(dur) {
    const br = WORLD.bridge, x0 = br.pylons[0] + 14, x1 = br.pylons[1] - 14;
    for (let x = x0; x <= x1; x += 7.5) {
      Sim.emitter(x + rr(-2, 2), br.deck - 1.5, br.z - 4, [0, -1.2, -0.4], 1.1, 40, TS.FALL, dur + rr(-0.6, 0.6));
    }
    for (let k = 0; k < 4; k++) Sim.light(lerp(x0, x1, (k + 0.5) / 4), br.deck - 20, br.z - 10, pc('gold'), 0.9, 160, dur, 0);
    Sound.hiss((x0 + x1) / 2, br.z, dur, 0.16);
    Sound.crackle((x0 + x1) / 2, br.deck, br.z, 0.1, 1);
  }

  return { launch, burst, fanShot, mine, candleShot, fountain, waterfall, SHELLS };
})();
