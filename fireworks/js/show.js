'use strict';
/* Nocturne · the show. Written the way a pyrotechnic designer writes one: a firing script of
   numbered cues, each with an effect time, a position and a device. Shell cues are scripted
   by the moment they break; the lift time is subtracted so the mortar fires early. */

const Show = (() => {
  const ACTS = [
    { n: 'I', name: 'Ignition', t: 0, blurb: 'Salutes open the night. The five barges answer each other.' },
    { n: 'II', name: 'Bloom', t: 26, blurb: 'Japanese round shells, each given room to open. Every star burns out on the same beat.' },
    { n: 'III', name: 'Pulse', t: 58, blurb: 'Fans, candles and crossettes fired to a steady 120 beats a minute.' },
    { n: 'IV', name: 'Garden', t: 86.5, blurb: 'Pattern shells: rings, planets, hearts and swimming fish.' },
    { n: 'V', name: 'Gold', t: 114, blurb: 'The hush. A waterfall pours from the bridge beneath willows and a brocade crown.' },
    { n: 'VI', name: 'Finale', t: 146, blurb: 'Every position, everything at once.' },
  ];
  const NAMES = {
    peony: 'Peony', chrys: 'Chrysanthemum', willow: 'Willow', kamuro: 'Kamuro crown', horsetail: 'Horsetail',
    palm: 'Palm', ring: 'Ring', orbit: 'Orbit rings', saturn: 'Saturn', heart: 'Heart', star5: 'Star', crossette: 'Crossette',
    strobe: 'Strobe', crackle: 'Crackle', senrin: 'Senrin (thousand flowers)', salute: 'Titanium salute', dahlia: 'Dahlia',
    fish: 'Fish', glitter: 'Glitter', spider: 'Spider', leaves: 'Falling leaves',
  };
  const cues = [];
  let next = 0, END = 0, qn = 0, R = mulberry32(20260929);
  const rs = (a, b) => a + (b - a) * R();
  const ps = (arr) => arr[Math.floor(R() * arr.length)];
  const BG = WORLD.barges, B = (k) => BG[k - 1];

  const altOf = (cal) => 70 + cal * 0.95;
  const flight = (alt) => 1.3 + alt / 190;

  function describe(s) {
    let d = NAMES[s.type] || s.type;
    if (s.type === 'peony' && s.petal2) d = 'Triple-petal peony';
    else if (s.pistil && (s.type === 'peony' || s.type === 'chrys')) d = 'Double-petal ' + d.toLowerCase();
    const c = [];
    if (s.split) c.push(s.split.join(' / '));
    else if (s.color) c.push(s.to ? s.color + ' → ' + s.to : s.color);
    if (s.petal2) c.push(s.petal2 + ' petal');
    if (s.pistil) c.push(s.pistil + ' pistil');
    if (s.ring) c.push(s.ring + ' ring');
    if (s.tip) c.push(s.tip + ' tips');
    if (s.colors) c.push(s.colors.join(', '));
    if (s.type === 'willow' || s.type === 'kamuro' || s.type === 'horsetail') c.unshift('gold');
    return c.length ? d + ' · ' + c.join(', ') : d;
  }
  function add(t, run, meta) {
    const c = { t, run };
    if (meta) Object.assign(c, meta, { q: ++qn });
    cues.push(c);
    END = Math.max(END, meta && meta.at !== undefined ? meta.at : t);
  }
  // Shell breaking at time tb over barge bi (or at an explicit x), optional offsets.
  function shell(tb, bi, s, dx = 0, dz = 0) {
    const b = B(bi);
    s.cal = s.cal || 150;
    const y = (s.alt || altOf(s.cal)) + rs(-6, 6), T = flight(y);
    const to = { x: b.x + dx + rs(-8, 8), y, z: b.z + dz + rs(-10, 10) };
    add(tb - T, () => Pyro.launch(s, b, to, T), { at: tb, pos: b.id, device: s.cal + ' mm shell', effect: describe(s) });
  }
  function shellX(tb, x, s) {
    let bi = 1, best = 1e9;
    BG.forEach((b, k) => { const d = Math.abs(b.x - x); if (d < best) { best = d; bi = k + 1; } });
    shell(tb, bi, s, x - B(bi).x);
  }
  function fan(t0, bi, s) {
    const b = B(bi), n = s.n || 9, dt = s.dt || 0.11;
    add(t0, null, { at: t0, pos: b.id, device: n + '-shot fan cake', effect: (s.tail === 'silver' ? 'Silver' : s.color ? s.color : 'Gold') + ' comets, ' + s.from + '° to ' + s.to + '°' + (s.pop ? ', crackling tips' : '') });
    for (let k = 0; k < n; k++) {
      const a = lerp(s.from, s.to, n === 1 ? 0.5 : k / (n - 1)) * DEG;
      add(t0 + k * dt, () => Pyro.fanShot(b, a, s));
    }
  }
  function mine(t, bi, s) {
    add(t, () => Pyro.mine(B(bi), s), { at: t, pos: B(bi).id, device: 'Mine', effect: s.colors.join(' / ') + ' stars' + (s.crackle ? ', crackle' : '') });
  }
  function candle(t, bi, color, lean) {
    add(t, () => Pyro.candleShot(B(bi), { color, lean }));
  }
  function fountain(t, bi, dur, gold) {
    add(t, () => Pyro.fountain(B(bi), dur, { gold }), { at: t, pos: B(bi).id, device: 'Gerb, ' + dur + ' s', effect: (gold ? 'Gold' : 'Silver') + ' fountain' });
  }
  function waterfall(t, dur) {
    const span = Math.round(WORLD.bridge.pylons[1] - WORLD.bridge.pylons[0]);
    add(t, () => Pyro.waterfall(dur), { at: t, pos: 'Bridge', device: 'Waterfall, ' + dur + ' s', effect: 'Niagara falls · gold, ' + span + ' m of deck' });
  }

  function build() {
    cues.length = 0; qn = 0; END = 0; R = mulberry32(20260929);
    const RAIN = ['red', 'orange', 'yellow', 'green', 'aqua', 'violet'];
    const LX = [-430, -250, -85, 85, 250, 430];

    /* I · Ignition */
    shell(3.2, 3, { type: 'strobe', cal: 100, color: 'silver', whistle: true, tail: 'silver' });
    shell(5.0, 1, { type: 'salute', cal: 100 });
    shell(5.3, 5, { type: 'salute', cal: 100 });
    shell(5.6, 3, { type: 'salute', cal: 125, alt: 250 });
    shell(6.6, 3, { type: 'kamuro', cal: 250 });
    shell(8.7, 1, { type: 'peony', cal: 125, color: 'red' }); shell(8.7, 5, { type: 'peony', cal: 125, color: 'red' });
    shell(9.4, 2, { type: 'peony', cal: 125, color: 'green' }); shell(9.4, 4, { type: 'peony', cal: 125, color: 'green' });
    shell(10.1, 3, { type: 'peony', cal: 150, color: 'blue', pistil: 'silver' });
    fan(11.0, 2, { n: 9, from: -55, to: 25, dt: 0.09, tail: 'silver', pop: true });
    fan(11.0, 4, { n: 9, from: 55, to: -25, dt: 0.09, tail: 'silver', pop: true });
    fan(12.4, 1, { n: 7, from: -35, to: 30, dt: 0.1 });
    fan(12.4, 5, { n: 7, from: 35, to: -30, dt: 0.1 });
    RAIN.forEach((c, k) => shellX(14.6 + k * 0.42, LX[k], { type: 'chrys', cal: 125, color: c, alt: 150 + k * 26, tail: 'tint' }));
    shell(18.9, 3, { type: 'peony', cal: 200, color: 'red', pistil: 'gold' });
    shell(20.5, 2, { type: 'crackle', cal: 150 }, -20); shell(20.5, 4, { type: 'crackle', cal: 150 }, 20);
    mine(21.9, 2, { colors: ['red', 'gold'] }); mine(21.9, 4, { colors: ['green', 'gold'] }); mine(22.1, 3, { colors: ['aqua', 'silver'], n: 50 });
    shell(22.9, 1, { type: 'salute', cal: 100 }); shell(23.1, 3, { type: 'salute', cal: 100 }); shell(23.3, 5, { type: 'salute', cal: 100 });
    shell(23.8, 3, { type: 'glitter', cal: 250, color: 'white' });

    /* II · Bloom */
    shell(27.8, 3, { type: 'chrys', cal: 250, color: 'gold', to: 'red', at: 0.55, pistil: 'green' });
    shell(30.9, 2, { type: 'peony', cal: 200, color: 'violet', pistil: 'white' }, -30);
    shell(33.6, 4, { type: 'peony', cal: 200, color: 'aqua', pistil: 'gold' }, 30);
    shell(36.4, 3, { type: 'chrys', cal: 250, color: 'silver', to: 'blue', at: 0.5 });
    shell(39.6, 3, { type: 'peony', cal: 300, color: 'red', petal2: 'green', pistil: 'white' });
    shell(43.4, 1, { type: 'peony', cal: 150, color: 'pink', pistil: 'white' }, 60);
    shell(43.4, 3, { type: 'peony', cal: 150, color: 'lime', pistil: 'gold', alt: 250 });
    shell(43.4, 5, { type: 'peony', cal: 150, color: 'blue', pistil: 'silver' }, -60);
    shell(46.4, 3, { type: 'senrin', cal: 300 });
    shell(50.2, 2, { type: 'dahlia', cal: 250, color: 'orange' }, 30);
    shell(51.2, 4, { type: 'dahlia', cal: 250, color: 'purple' }, -30);
    shell(53.8, 3, { type: 'peony', cal: 250, split: ['red', 'aqua'] });
    shell(56.0, 3, { type: 'chrys', cal: 300, color: 'gold', to: 'green', at: 0.52, pistil: 'red', alt: 360 });

    /* III · Pulse: 120 bpm, one beat every half second from 58.0 */
    const beat = (k) => 58 + k * 0.5;
    for (let k = 0; k < 8; k++) {
      const left = k % 2 === 0;
      fan(beat(k), left ? 1 : 5, { n: 5, from: left ? -34 : 34, to: left ? 14 : -14, dt: 0.06, tail: k % 4 < 2 ? 'gold' : 'silver' });
      if (k % 2 === 1) shell(beat(k), left ? 4 : 2, { type: 'peony', cal: 100, color: RAIN[k % 6], alt: 150 + k * 12 }, left ? 30 : -30);
    }
    shell(beat(7) + 0.25, 3, { type: 'strobe', cal: 125, color: 'silver', alt: 230 });
    for (let k = 9; k < 16; k += 2) fan(beat(k), k % 4 === 1 ? 1 : 5, { n: 4, from: k % 4 === 1 ? -20 : 20, to: 0, dt: 0.05, tail: 'silver' });
    for (let k = 8; k < 16; k += 2) shell(beat(k), (k / 2) % 2 ? 4 : 2, { type: 'crossette', cal: 150, color: (k / 2) % 2 ? 'silver' : 'gold' }, (k / 2) % 2 ? 40 : -40);
    shell(beat(15), 3, { type: 'peony', cal: 150, color: 'aqua', pistil: 'white', alt: 230 });
    for (let k = 16; k < 24; k++) {
      for (let j = 0; j < 5; j++) candle(beat(k) + j * 0.1, j + 1, RAIN[(k + j) % 6], (j - 2) * 6);
    }
    add(beat(16), null, { at: beat(16), pos: 'B1–B5', device: '5 × 8-shot candle', effect: 'Colour ripple, left to right' });
    for (let k = 24; k < 32; k++) {
      const bi = [2, 4, 3, 1, 5, 2, 4, 3][k - 24];
      if (k % 2) shell(beat(k), bi, { type: 'ring', cal: 125, color: ps(['aqua', 'pink', 'lime', 'violet']), tilt: rs(-0.5, 0.5) });
      else shell(beat(k), bi, { type: 'strobe', cal: 100, color: 'white' });
    }
    for (let k = 32; k < 40; k++) {
      if (k % 2 === 0) fan(beat(k), (k / 2) % 2 ? 2 : 4, { n: 5, from: -40, to: 40, dt: 0.05, tail: 'tint', color: RAIN[k % 6] });
      if (k % 4 === 0) mine(beat(k), 3, { colors: [RAIN[(k / 4) % 6], 'gold'], n: 34 });
    }
    for (let k = 0; k < 10; k++) shellX(beat(40) + k * 0.125, lerp(-460, 460, k / 9), { type: 'peony', cal: 100, color: RAIN[k % 6], alt: 175 });
    for (let k = 0; k < 10; k++) shellX(beat(44) + k * 0.125, lerp(460, -460, k / 9), { type: 'peony', cal: 100, color: RAIN[(k + 3) % 6], alt: 215 });
    shell(beat(48), 1, { type: 'salute', cal: 100 }); shell(beat(48) + 0.25, 5, { type: 'salute', cal: 100 });
    shell(beat(49) + 0.2, 3, { type: 'crossette', cal: 250, color: 'gold' });
    shell(beat(51), 3, { type: 'crackle', cal: 200, alt: 290 });

    /* IV · Garden */
    shell(87.2, 3, { type: 'ring', cal: 200, color: 'aqua', core: 'white', tilt: 0.1 });
    shell(89.4, 2, { type: 'ring', cal: 150, color: 'red', tilt: -0.6 }, -20);
    shell(89.4, 4, { type: 'ring', cal: 150, color: 'green', tilt: 0.6 }, 20);
    shell(92.0, 3, { type: 'saturn', cal: 250, color: 'violet', ring: 'gold' });
    shell(94.8, 3, { type: 'heart', cal: 200, color: 'pink' });
    shell(96.4, 1, { type: 'heart', cal: 125, color: 'red' }, 50);
    shell(96.6, 5, { type: 'heart', cal: 125, color: 'red' }, -50);
    shell(98.8, 2, { type: 'fish', cal: 150 }); shell(99.4, 4, { type: 'fish', cal: 150 });
    shell(101.6, 3, { type: 'star5', cal: 200, color: 'gold' });
    shell(104.2, 2, { type: 'saturn', cal: 200, color: 'blue', ring: 'silver' }, -20);
    shell(104.9, 4, { type: 'saturn', cal: 200, color: 'green', ring: 'red' }, 20);
    shell(107.4, 3, { type: 'leaves', cal: 250 });
    shell(110.2, 3, { type: 'orbit', cal: 250, colors: ['red', 'aqua', 'gold'] });
    shell(112.2, 1, { type: 'ring', cal: 100, color: 'pink', tilt: 0.4 }, 60);
    shell(112.4, 5, { type: 'ring', cal: 100, color: 'pink', tilt: -0.4 }, -60);

    /* V · Gold */
    waterfall(114.4, 30);
    shell(116.4, 2, { type: 'willow', cal: 200 }, -20); shell(116.4, 4, { type: 'willow', cal: 200 }, 20);
    shell(119.6, 3, { type: 'kamuro', cal: 250 });
    shell(122.8, 1, { type: 'willow', cal: 200, tip: 'red' }, 30); shell(122.8, 5, { type: 'willow', cal: 200, tip: 'green' }, -30);
    shell(125.6, 2, { type: 'horsetail', cal: 200 }); shell(126.1, 3, { type: 'horsetail', cal: 250 }); shell(126.6, 4, { type: 'horsetail', cal: 200 });
    shell(129.5, 2, { type: 'palm', cal: 200, tail: 'palm', glitter: true }); shell(129.5, 4, { type: 'palm', cal: 200, tail: 'palm', glitter: true });
    for (let k = 1; k <= 5; k++) fountain(131.2 + (k === 3 ? 0 : Math.abs(k - 3) * 0.3), k, 9, k !== 3);
    shell(133.0, 3, { type: 'strobe', cal: 150, color: 'white', alt: 190 });
    shell(134.6, 2, { type: 'kamuro', cal: 200 }, -30); shell(135.2, 4, { type: 'kamuro', cal: 200 }, 30);
    shell(138.4, 3, { type: 'kamuro', cal: 300, alt: 375 });

    /* VI · Finale */
    shell(146.0, 1, { type: 'salute', cal: 100 }); shell(146.2, 3, { type: 'salute', cal: 125 }); shell(146.4, 5, { type: 'salute', cal: 100 });
    let t = 146.8;
    while (t < 153) {
      shell(t, 1 + Math.floor(R() * 5), { type: ps(['peony', 'chrys', 'peony', 'dahlia', 'chrys']), cal: ps([125, 150, 150, 175]), color: ps(COLOURS), pistil: R() < 0.3 ? ps(['white', 'gold', 'silver']) : undefined, tail: R() < 0.5 ? 'tint' : undefined }, rs(-50, 50));
      t += rs(0.18, 0.32);
    }
    fan(147.5, 1, { n: 9, from: -50, to: 30, dt: 0.08, tail: 'silver', pop: true });
    fan(147.5, 5, { n: 9, from: 50, to: -30, dt: 0.08, tail: 'silver', pop: true });
    fan(150.2, 2, { n: 9, from: -40, to: 40, dt: 0.06 }); fan(150.2, 4, { n: 9, from: 40, to: -40, dt: 0.06 });
    t = 153;
    while (t < 158) {
      shell(t, 1 + Math.floor(R() * 5), { type: ps(['crackle', 'strobe', 'crossette', 'chrys', 'glitter']), cal: ps([125, 150, 200]), color: ps(['gold', 'silver', 'white', 'red', 'green']) }, rs(-60, 60));
      t += rs(0.16, 0.28);
    }
    mine(154, 2, { colors: ['red', 'white'], crackle: true }); mine(154, 4, { colors: ['blue', 'white'], crackle: true });
    mine(156, 1, { colors: ['gold'], crackle: true }); mine(156, 5, { colors: ['gold'], crackle: true });
    for (let w = 0; w < 3; w++) RAIN.forEach((c, k) => shellX(158 + w * 1.2 + k * 0.12, (w % 2 ? -1 : 1) * LX[k], { type: 'peony', cal: 150, color: c, alt: 200 + w * 40 }));
    shell(161.6, 2, { type: 'ring', cal: 200, color: 'gold', tilt: -0.4 }); shell(161.6, 4, { type: 'ring', cal: 200, color: 'gold', tilt: 0.4 });
    // The wall: ten brocade crowns within a second and a half, crackle under them, salutes to close.
    for (let k = 0; k < 10; k++) shellX(162.6 + k * 0.14, lerp(-480, 480, (k * 7 % 10) / 9), { type: 'kamuro', cal: ps([200, 250]), alt: rs(250, 330) });
    shell(163.2, 3, { type: 'kamuro', cal: 300, alt: 380 });
    for (let k = 0; k < 5; k++) shell(163.6 + k * 0.2, k + 1, { type: 'crackle', cal: 150, alt: 190 });
    fan(163.0, 1, { n: 11, from: -60, to: 60, dt: 0.05, pop: true }); fan(163.0, 5, { n: 11, from: 60, to: -60, dt: 0.05, pop: true });
    shell(165.0, 3, { type: 'strobe', cal: 250, color: 'white', alt: 330 });
    for (let k = 0; k < 6; k++) shell(166.2 + k * 0.22, [1, 5, 2, 4, 3, 3][k], { type: 'salute', cal: k === 5 ? 150 : 100, alt: k === 5 ? 280 : undefined });

    cues.sort((a, b) => a.t - b.t);
    // Number cues in the order the audience sees them, as a firing script does.
    cues.filter((c) => c.q).sort((a, b) => a.at - b.at).forEach((c, k) => { c.q = k + 1; });
    next = 0;
    return cues;
  }

  function tick(t, onCue) {
    while (next < cues.length && cues[next].t <= t) {
      const c = cues[next++];
      if (c.run) c.run();
      if (c.q && onCue) onCue(c);
    }
  }
  function seek(t) {
    next = 0;
    while (next < cues.length && cues[next].t < t) next++;
  }
  const actAt = (t) => { let a = ACTS[0]; for (const x of ACTS) if (t >= x.t - 0.01) a = x; return a; };

  return {
    ACTS, NAMES, build, tick, seek, actAt, describe, altOf, flight,
    get cues() { return cues; }, get end() { return END; },
    get scripted() { return cues.filter((c) => c.q); },
  };
})();

/* Free play: the sky is yours. */
const Free = (() => {
  const TYPES = [
    { id: 'random', label: 'Surprise me' },
    { id: 'peony', label: 'Peony' }, { id: 'chrys', label: 'Chrysanthemum' }, { id: 'willow', label: 'Willow' },
    { id: 'kamuro', label: 'Kamuro' }, { id: 'palm', label: 'Palm' }, { id: 'ring', label: 'Ring' }, { id: 'saturn', label: 'Saturn' },
    { id: 'heart', label: 'Heart' }, { id: 'star5', label: 'Star' }, { id: 'crossette', label: 'Crossette' }, { id: 'strobe', label: 'Strobe' },
    { id: 'crackle', label: 'Crackle' }, { id: 'senrin', label: 'Senrin' }, { id: 'dahlia', label: 'Dahlia' }, { id: 'fish', label: 'Fish' },
    { id: 'horsetail', label: 'Horsetail' }, { id: 'spider', label: 'Spider' }, { id: 'leaves', label: 'Falling leaves' },
    { id: 'glitter', label: 'Glitter' }, { id: 'salute', label: 'Salute' },
  ];
  const RANDOM_POOL = ['peony', 'peony', 'chrys', 'chrys', 'willow', 'kamuro', 'palm', 'ring', 'saturn', 'heart', 'crossette', 'strobe', 'crackle', 'senrin', 'dahlia', 'fish', 'horsetail', 'spider', 'glitter'];
  const pick = (a) => a[(Math.random() * a.length) | 0];

  function spec(type, colour, cal) {
    if (type === 'random') type = pick(RANDOM_POOL);
    const c = colour === 'mixed' ? pick(COLOURS) : colour;
    const s = { type, cal, color: c };
    if (type === 'peony' || type === 'chrys') {
      if (Math.random() < 0.45) s.pistil = pick(['white', 'gold', 'silver', 'green', 'red'].filter((x) => x !== c));
      if (Math.random() < 0.25) s.to = pick(COLOURS.filter((x) => x !== c));
      if (type === 'chrys' && Math.random() < 0.5) s.tail = 'tint';
    }
    if (type === 'saturn') s.ring = pick(['gold', 'silver', 'aqua', 'red'].filter((x) => x !== c));
    if (type === 'willow' && Math.random() < 0.4) s.tip = pick(['red', 'green', 'aqua']);
    if (type === 'palm') { s.tail = 'palm'; s.glitter = Math.random() < 0.5; }
    if (type === 'heart' && colour === 'mixed') s.color = pick(['pink', 'red', 'violet']);
    if (type === 'star5' && colour === 'mixed') s.color = pick(['gold', 'yellow', 'silver']);
    return s;
  }
  // Fire so the shell breaks where the viewer pointed.
  function fireAt(nx, ny, type, colour) {
    const d = screenRay(nx, ny), z = rr(1150, 1300);
    const t = z / Math.max(d[2], 0.05);
    let x = d[0] * t, y = WORLD.camH + d[1] * t;
    if (y < 45) { const b = nearest(x); Pyro.mine(b, { colors: colour === 'mixed' ? [pick(COLOURS), 'gold'] : [colour, 'gold'], n: 40 }); return; }
    y = clamp(y, 80, 430); x = clamp(x, -720, 720);
    const cal = clamp(Math.round((y - 70) / 0.95 / 25) * 25, 75, 300);
    const s = spec(type, colour, cal), b = nearest(x);
    Pyro.launch(s, b, { x, y, z }, Show.flight(y));
  }
  function nearest(x) {
    let best = WORLD.barges[0];
    for (const b of WORLD.barges) if (Math.abs(b.x - x) < Math.abs(best.x - x)) best = b;
    return best;
  }
  function randomShot(type, colour) {
    const b = pick(WORLD.barges), cal = pick([100, 125, 150, 150, 200, 250]);
    const y = Show.altOf(cal) + rr(-20, 20);
    Pyro.launch(spec(type || 'random', colour || 'mixed', cal), b, { x: b.x + rr(-60, 60), y, z: b.z + rr(-20, 20) }, Show.flight(y));
  }
  function finale() {
    const acts = [];
    for (let k = 0; k < 28; k++) acts.push([k * 0.22 + rr(0, 0.1), () => randomShot(pick(['peony', 'chrys', 'crackle', 'kamuro', 'strobe', 'dahlia']), 'mixed')]);
    for (let k = 0; k < 5; k++) acts.push([6.4 + k * 0.12, () => randomShot('kamuro', 'gold')]);
    for (let k = 0; k < 4; k++) acts.push([7.4 + k * 0.25, () => randomShot('salute', 'white')]);
    return acts;
  }
  return { TYPES, fireAt, randomShot, finale };
})();
