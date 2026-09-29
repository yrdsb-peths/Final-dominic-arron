'use strict';
/* Nocturne · sound. Everything is synthesised at start-up: mortar thumps, bursts with a
   rolling tail, crackle, whistles, the hiss of gerbs, and an echo off the city. Sound
   travels from the barge, so it arrives a beat after the light. */

const Sound = (() => {
  let ctx = null, master = null, dry = null, wetBus = null, ready = false, enabled = true;
  let ambient = null, budget = 0, budgetT = 0;
  const B = {};

  function makeBuf(sec, gen) {
    const sr = ctx.sampleRate, n = Math.floor(sec * sr);
    const b = ctx.createBuffer(2, n, sr);
    for (let c = 0; c < 2; c++) gen(b.getChannelData(c), c, sr);
    return normalise(b, 0.9);
  }
  function normalise(b, peak) {
    let m = 1e-6;
    for (let c = 0; c < b.numberOfChannels; c++) { const d = b.getChannelData(c); for (let i = 0; i < d.length; i++) { const a = Math.abs(d[i]); if (a > m) m = a; } }
    const k = peak / m;
    for (let c = 0; c < b.numberOfChannels; c++) { const d = b.getChannelData(c); for (let i = 0; i < d.length; i++) d[i] *= k; }
    return b;
  }

  // A shell burst: a sharp crack, a body of falling low-passed noise, a sub thump and a long rumble.
  function boom(crackAmt, bodyTau, rumbleTau) {
    return makeBuf(3.4, (d, c, sr) => {
      let a1 = 0, a2 = 0, r1 = 0, r2 = 0, ph = 0;
      const ar = 1 - Math.exp(-TAU * 90 / sr);
      for (let i = 0; i < d.length; i++) {
        const t = i / sr, w = Math.random() * 2 - 1;
        const crack = w * Math.exp(-t / 0.004) * crackAmt;
        const fc = 150 + 1600 * Math.exp(-t / 0.06);
        const a = 1 - Math.exp(-TAU * fc / sr);
        a1 += a * (w - a1); a2 += a * (a1 - a2);
        const body = a2 * Math.exp(-t / bodyTau) * 2.8;
        ph += TAU * (32 + 44 * Math.exp(-t / 0.1)) / sr;
        const sub = Math.sin(ph) * Math.exp(-t / 0.3) * 0.8;
        r1 += ar * (w - r1); r2 += ar * (r1 - r2);
        const rumble = r2 * 4.2 * Math.exp(-t / rumbleTau) * smoothstep(0.03, 0.3, t);
        d[i] = (crack + body + sub + rumble) * Math.min(1, t / 0.0012);
      }
    });
  }
  function thump() {
    return makeBuf(0.8, (d, c, sr) => {
      let l = 0, ph = 0;
      const a = 1 - Math.exp(-TAU * 700 / sr);
      for (let i = 0; i < d.length; i++) {
        const t = i / sr, w = Math.random() * 2 - 1;
        ph += TAU * (44 + 110 * Math.exp(-t / 0.025)) / sr;
        l += a * (w - l);
        d[i] = (Math.sin(ph) * Math.exp(-t / 0.08) + l * 1.6 * Math.exp(-t / 0.03) + w * 0.06 * Math.exp(-t / 0.2)) * Math.min(1, t / 0.001);
      }
    });
  }
  function crackle(sec, count) {
    const sr = ctx.sampleRate, n = Math.floor(sec * sr);
    const b = ctx.createBuffer(2, n, sr), L = b.getChannelData(0), R = b.getChannelData(1);
    for (let k = 0; k < count; k++) {
      const t = (0.05 + 0.9 * (Math.random() + Math.random() + Math.random()) / 3) * sec;
      const i0 = Math.floor(t * sr), len = Math.floor(sr * rr(0.0015, 0.005));
      const amp = Math.pow(rr(0.2, 1), 2), pan = Math.random();
      let prev = 0;
      for (let j = 0; j < len && i0 + j < n; j++) {
        const w = Math.random() * 2 - 1, h = w - prev; prev = w;
        const e = Math.exp(-j / (len * 0.22)) * amp;
        L[i0 + j] += h * e * (1 - pan * 0.7); R[i0 + j] += h * e * (0.3 + pan * 0.7);
      }
    }
    return normalise(b, 0.9);
  }
  function noiseLoop(sec) {
    return makeBuf(sec, (d) => { for (let i = 0; i < d.length; i++) d[i] = Math.random() * 2 - 1; });
  }
  function brown(sec) {
    return makeBuf(sec, (d) => { let v = 0; for (let i = 0; i < d.length; i++) { v = (v + 0.02 * (Math.random() * 2 - 1)) / 1.02; d[i] = v; } });
  }
  function applause(sec) {
    const sr = ctx.sampleRate, n = Math.floor(sec * sr);
    const b = ctx.createBuffer(2, n, sr), L = b.getChannelData(0), R = b.getChannelData(1);
    for (let p = 0; p < 46; p++) {
      const rate = rr(3.4, 5.2), f0 = rr(900, 2400), pan = Math.random(), amp0 = rr(0.3, 1);
      const start = rr(0, 0.9), end = sec - rr(0.2, 3.2);
      const w0 = TAU * f0 / sr, alpha = Math.sin(w0) / (2 * 1.4);
      const b0 = alpha, a0 = 1 + alpha, a1 = -2 * Math.cos(w0), a2 = 1 - alpha;
      for (let t = start; t < end; t += (1 / rate) * rr(0.8, 1.2)) {
        const i0 = Math.floor(t * sr), len = Math.floor(sr * 0.022);
        const env = smoothstep(0, 0.8, t) * (1 - smoothstep(end - 2.5, end, t)) * amp0 * rr(0.6, 1);
        let x1 = 0, x2 = 0, y1 = 0, y2 = 0;
        for (let j = 0; j < len && i0 + j < n; j++) {
          const x = (Math.random() * 2 - 1) * Math.exp(-j / (sr * 0.0035));
          const y = (b0 * x - b0 * x2 - a1 * y1 - a2 * y2) / a0;
          x2 = x1; x1 = x; y2 = y1; y1 = y;
          L[i0 + j] += y * env * (1 - pan * 0.6); R[i0 + j] += y * env * (0.4 + pan * 0.6);
        }
      }
    }
    return normalise(b, 0.8);
  }
  // Harbour echo: dense diffuse tail plus discrete slaps off the far shore.
  function impulse() {
    const sr = ctx.sampleRate, n = Math.floor(4.2 * sr);
    const b = ctx.createBuffer(2, n, sr);
    for (let c = 0; c < 2; c++) {
      const d = b.getChannelData(c);
      let lp = 0;
      for (let i = 0; i < n; i++) {
        const t = i / sr;
        const a = 1 - Math.exp(-TAU * (2600 * Math.exp(-t / 1.1) + 250) / sr);
        lp += a * ((Math.random() * 2 - 1) - lp);
        d[i] = lp * Math.exp(-t / 1.05) * smoothstep(0.0, 0.06, t);
      }
      for (let k = 0; k < 7; k++) {
        const t = rr(0.25, 1.6), i0 = Math.floor(t * sr), g = rr(0.3, 0.7) * Math.exp(-t / 1.2);
        for (let j = 0; j < sr * 0.03 && i0 + j < n; j++) d[i0 + j] += (Math.random() * 2 - 1) * g * Math.exp(-j / (sr * 0.008));
      }
    }
    return normalise(b, 0.5);
  }

  function init() {
    if (ctx) { if (ctx.state === 'suspended') ctx.resume(); return; }
    const AC = window.AudioContext || window.webkitAudioContext;
    if (!AC) return;
    try { ctx = new AC({ latencyHint: 'playback' }); } catch (e) { ctx = new AC(); }
    master = ctx.createGain(); master.gain.value = enabled ? 0.9 : 0;
    const comp = ctx.createDynamicsCompressor();
    comp.threshold.value = -16; comp.knee.value = 14; comp.ratio.value = 5; comp.attack.value = 0.002; comp.release.value = 0.3;
    master.connect(comp); comp.connect(ctx.destination);
    dry = ctx.createGain(); dry.connect(master);
    const conv = ctx.createConvolver(); conv.normalize = true;
    wetBus = ctx.createGain(); wetBus.gain.value = 0.62;
    wetBus.connect(conv); conv.connect(master);
    B.boom = [boom(0.7, 0.4, 1.3), boom(0.5, 0.5, 1.5), boom(0.9, 0.34, 1.1), boom(0.6, 0.46, 1.7)];
    B.salute = boom(2.2, 0.26, 1.2);
    B.thump = thump();
    B.crackle = [crackle(1.6, 170), crackle(1.6, 240), crackle(1.2, 110)];
    B.pops = crackle(0.5, 14);
    B.noise = noiseLoop(2);
    B.applause = applause(8);
    conv.buffer = impulse();
    const hum = ctx.createBufferSource(); hum.buffer = brown(4); hum.loop = true;
    const f = ctx.createBiquadFilter(); f.type = 'lowpass'; f.frequency.value = 380;
    ambient = ctx.createGain(); ambient.gain.value = 0.05;
    hum.connect(f); f.connect(ambient); ambient.connect(master); hum.start();
    ready = true;
  }

  function where(x, y, z) {
    const d = Math.hypot(x, y - WORLD.camH, z);
    return { d, delay: d / 343 * WORLD.soundScale, pan: clamp(x / 800, -0.85, 0.85), near: clamp(1200 / d, 0.4, 1.6) };
  }
  // Keep the finale from spawning hundreds of voices in the same instant.
  function allow(cost) {
    const now = ctx.currentTime;
    if (now - budgetT > 0.1) { budgetT = now; budget = 0; }
    budget += cost;
    return budget <= 14;
  }
  function play(buf, o) {
    if (!ready || !enabled || !allow(o.cost || 1)) return;
    const t0 = ctx.currentTime + (o.delay || 0);
    const s = ctx.createBufferSource(); s.buffer = buf; s.playbackRate.value = o.rate || 1; s.loop = !!o.loop;
    let node = s;
    if (o.lp) { const f = ctx.createBiquadFilter(); f.type = 'lowpass'; f.frequency.value = o.lp; f.Q.value = 0.4; node.connect(f); node = f; }
    if (o.hp) { const f = ctx.createBiquadFilter(); f.type = 'highpass'; f.frequency.value = o.hp; node.connect(f); node = f; }
    const g = ctx.createGain(); g.gain.value = o.gain; node.connect(g); node = g;
    if (ctx.createStereoPanner) { const p = ctx.createStereoPanner(); p.pan.value = o.pan || 0; node.connect(p); node = p; }
    node.connect(dry);
    if (o.wet) { const w = ctx.createGain(); w.gain.value = o.wet; node.connect(w); w.connect(wetBus); }
    s.start(t0, o.offset || 0);
    if (o.dur) {
      g.gain.setValueAtTime(0, t0); g.gain.linearRampToValueAtTime(o.gain, t0 + 0.5);
      g.gain.setValueAtTime(o.gain, t0 + o.dur); g.gain.linearRampToValueAtTime(0, t0 + o.dur + 0.8); s.stop(t0 + o.dur + 0.9);
    }
    return s;
  }

  return {
    init,
    get ready() { return ready; },
    get enabled() { return enabled; },
    setEnabled(on) {
      enabled = on;
      if (ctx) master.gain.setTargetAtTime(on ? 0.9 : 0, ctx.currentTime, 0.05);
    },
    suspend(yes) { if (ctx) (yes ? ctx.suspend() : ctx.resume()); },
    launch(x, z, cal) { if (!ready || !enabled) return;
      const w = where(x, 3, z);
      play(B.thump, { delay: w.delay, gain: 0.22 + cal / 900, rate: rr(0.85, 1.15) * (cal > 200 ? 0.8 : 1), pan: w.pan, wet: 0.3, lp: 2400 });
    },
    whistle(x, z, dur) {
      if (!ready || !enabled || !allow(1)) return;
      const w = where(x, 100, z), t0 = ctx.currentTime + w.delay;
      const o = ctx.createOscillator(); o.type = 'sine';
      o.frequency.setValueAtTime(760, t0);
      o.frequency.exponentialRampToValueAtTime(2500, t0 + dur * 0.85);
      o.frequency.exponentialRampToValueAtTime(2100, t0 + dur);
      const lfo = ctx.createOscillator(); lfo.frequency.value = 23; const lg = ctx.createGain(); lg.gain.value = 26;
      lfo.connect(lg); lg.connect(o.frequency);
      const g = ctx.createGain();
      g.gain.setValueAtTime(0.0001, t0); g.gain.exponentialRampToValueAtTime(0.07, t0 + 0.15);
      g.gain.setValueAtTime(0.07, t0 + dur * 0.8); g.gain.exponentialRampToValueAtTime(0.0001, t0 + dur);
      const p = ctx.createStereoPanner ? ctx.createStereoPanner() : ctx.createGain();
      if (p.pan) p.pan.value = w.pan;
      o.connect(g); g.connect(p); p.connect(dry);
      const ws = ctx.createGain(); ws.gain.value = 0.5; p.connect(ws); ws.connect(wetBus);
      o.start(t0); lfo.start(t0); o.stop(t0 + dur + 0.05); lfo.stop(t0 + dur + 0.05);
    },
    burst(x, y, z, cal) { if (!ready || !enabled) return;
      const w = where(x, y, z), big = clamp(cal / 300, 0.2, 1.1);
      play(B.boom[(Math.random() * 4) | 0], { delay: w.delay, gain: (0.3 + big * 0.75) * w.near, rate: clamp(1.28 - big * 0.55, 0.62, 1.2) * rr(0.94, 1.06), pan: w.pan, wet: 0.5 + big * 0.3, lp: 3800 + 4000 * (1 - big), cost: 2 });
    },
    salute(x, y, z) { if (!ready || !enabled) return;
      const w = where(x, y, z);
      play(B.salute, { delay: w.delay, gain: 1.05 * w.near, rate: rr(0.95, 1.08), pan: w.pan, wet: 0.95, cost: 3 });
    },
    crackle(x, y, z, amount, lag) { if (!ready || !enabled) return;
      const w = where(x, y, z);
      play(B.crackle[(Math.random() * 3) | 0], { delay: w.delay + lag, gain: clamp(0.18 + amount * 0.5, 0.15, 0.7) * w.near, rate: rr(0.9, 1.15), pan: w.pan, wet: 0.35, hp: 1200, cost: 2 });
    },
    pops(x, y, z, lag, gain) { if (!ready || !enabled) return;
      const w = where(x, y, z);
      play(B.pops, { delay: w.delay + lag, gain: gain * w.near, rate: rr(0.8, 1.2), pan: w.pan, wet: 0.4, hp: 600 });
    },
    hiss(x, z, dur, gain) { if (!ready || !enabled) return;
      const w = where(x, 20, z);
      play(B.noise, { delay: w.delay, gain, pan: w.pan, wet: 0.25, hp: 2600, lp: 7000, dur, loop: true, rate: rr(0.9, 1.1), cost: 1 });
    },
    applause(lag) {
      if (!ready || !enabled) return;
      play(B.applause, { delay: lag || 0, gain: 0.55, wet: 0.15, cost: 0 }); },
  };
})();
