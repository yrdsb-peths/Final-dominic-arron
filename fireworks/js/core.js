'use strict';
/* Nocturne · core: maths, pyrotechnic colour, camera and the harbour layout.
   World units are metres and seconds. x runs right, y up, z away from the viewer. */

const TAU = Math.PI * 2, DEG = Math.PI / 180, G = 9.81;
const rnd = Math.random;
const rr = (a, b) => a + (b - a) * Math.random();
const clamp = (x, a, b) => (x < a ? a : x > b ? b : x);
const lerp = (a, b, t) => a + (b - a) * t;
const smoothstep = (a, b, x) => { const t = clamp((x - a) / (b - a), 0, 1); return t * t * (3 - 2 * t); };

function mulberry32(seed) {
  return function () {
    seed |= 0; seed = seed + 0x6D2B79F5 | 0;
    let t = Math.imul(seed ^ seed >>> 15, 1 | seed);
    t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t;
    return ((t ^ t >>> 14) >>> 0) / 4294967296;
  };
}

// Star colours in linear light, named by the chemistry that makes them.
const PYRO = {
  red:    { rgb: [1.00, 0.07, 0.035], label: 'strontium red' },
  orange: { rgb: [1.00, 0.30, 0.05], label: 'calcium orange' },
  gold:   { rgb: [1.00, 0.49, 0.12], label: 'charcoal gold' },
  yellow: { rgb: [1.00, 0.78, 0.22], label: 'sodium yellow' },
  lime:   { rgb: [0.52, 1.00, 0.10], label: 'lime' },
  green:  { rgb: [0.10, 1.00, 0.24], label: 'barium green' },
  aqua:   { rgb: [0.06, 0.78, 1.00], label: 'aqua' },
  blue:   { rgb: [0.09, 0.24, 1.00], label: 'copper blue' },
  violet: { rgb: [0.48, 0.14, 1.00], label: 'violet' },
  purple: { rgb: [0.86, 0.12, 0.95], label: 'purple' },
  pink:   { rgb: [1.00, 0.24, 0.52], label: 'pink' },
  silver: { rgb: [0.80, 0.88, 1.00], label: 'titanium silver' },
  white:  { rgb: [1.00, 0.95, 0.88], label: 'magnesium white' },
};
// Balance perceived brightness so reds and blues hold their own next to green.
for (const k in PYRO) {
  const c = PYRO[k].rgb, l = 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
  const f = Math.pow(0.55 / l, 0.5);
  PYRO[k].c = [c[0] * f, c[1] * f, c[2] * f];
}
const pc = (name) => (PYRO[name] || PYRO.gold).c;
const COLOURS = ['red', 'orange', 'gold', 'yellow', 'lime', 'green', 'aqua', 'blue', 'violet', 'purple', 'pink', 'silver', 'white'];

// Charcoal sparks cool from yellow-white to a dull red as they die.
function sparkRamp(u, out) {
  if (u < 0.3) { const t = u / 0.3; out[0] = 1; out[1] = lerp(0.8, 0.5, t); out[2] = lerp(0.46, 0.15, t); }
  else { const t = (u - 0.3) / 0.7; out[0] = lerp(1, 0.72, t); out[1] = lerp(0.5, 0.15, t); out[2] = lerp(0.15, 0.02, t); }
}

// The harbour. The viewer stands on the south promenade, 10 m above the water.
const WORLD = {
  camH: 10,
  cityZ: 3400,
  bargeZ: 1240,
  wind: 3.0,          // m/s, drifting smoke and stars to the right
  soundScale: 0.26,   // fraction of the true 343 m/s delay (a full 3.6 s felt broken)
  barges: [
    { id: 'B1', x: -430, z: 1300 },
    { id: 'B2', x: -215, z: 1215 },
    { id: 'B3', x: 0, z: 1245 },
    { id: 'B4', x: 220, z: 1210 },
    { id: 'B5', x: 440, z: 1295 },
  ],
  bridge: { z: 2050, x0: -1500, x1: 40, pylons: [-760, -300], deck: 56, top: 160 },
};
WORLD.elCity = Math.atan2(-WORLD.camH, WORLD.cityZ);
WORLD.elBarge = Math.atan2(-WORLD.camH, WORLD.bargeZ);

const CAM = { tanX: 0.54, tanY: 0.31, pitch: 0.12, sp: 0.12, cp: 0.99 };
function setupCamera(aspect) {
  const tanY = Math.max(Math.tan(15.5 * DEG), Math.tan(19 * DEG) / aspect);
  const horizon = aspect < 1 ? 0.34 : 0.3;      // horizon height as a fraction of the screen
  const pitch = Math.atan((1 - 2 * horizon) * tanY);
  Object.assign(CAM, { tanX: tanY * aspect, tanY, pitch, sp: Math.sin(pitch), cp: Math.cos(pitch) });
}
function project(x, y, z) {
  const dy = y - WORLD.camH;
  const vy = dy * CAM.cp - z * CAM.sp, vz = dy * CAM.sp + z * CAM.cp;
  if (vz < 1) return null;
  return [x / (vz * CAM.tanX), vy / (vz * CAM.tanY), vz];
}
function screenRay(nx, ny) {
  const dx = nx * CAM.tanX, dy = CAM.sp + CAM.cp * ny * CAM.tanY, dz = CAM.cp - CAM.sp * ny * CAM.tanY;
  const l = Math.hypot(dx, dy, dz);
  return [dx / l, dy / l, dz / l];
}
function fmtClock(t) {
  t = Math.max(0, t);
  const m = Math.floor(t / 60), s = Math.floor(t - m * 60);
  return m + ':' + (s < 10 ? '0' : '') + s;
}
function fmtCue(t) {
  t = Math.max(0, t);
  const m = Math.floor(t / 60), s = t - m * 60;
  return m + ':' + (s < 10 ? '0' : '') + s.toFixed(1);
}
