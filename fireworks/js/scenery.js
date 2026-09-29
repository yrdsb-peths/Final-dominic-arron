'use strict';
/* Nocturne · scenery. The far shore is painted once into a panorama keyed by azimuth and
   elevation, so buildings, the bridge and the barges line up exactly with the 3D fireworks.
   RGB holds emitted light (windows, lamps), alpha holds the silhouette. */

const Scenery = (() => {
  const MAP = { azMax: 52 * DEG, el0: -1.2 * DEG, el1: 8 * DEG };

  function skyline(W, H) {
    const cv = document.createElement('canvas'); cv.width = W; cv.height = H;
    const g = cv.getContext('2d');
    const R = mulberry32(77);
    const r = (a, b) => a + (b - a) * R();
    const camH = WORLD.camH;
    const X = (x, z) => (Math.atan2(x, z) / (2 * MAP.azMax) + 0.5) * W;
    const Y = (y, x, z) => H - (Math.atan2(y - camH, Math.hypot(x, z)) - MAP.el0) / (MAP.el1 - MAP.el0) * H;
    const blinkers = [];
    const dot = (x, y, z, rad, c) => { g.fillStyle = c; g.beginPath(); g.arc(X(x, z), Y(y, x, z), rad, 0, TAU); g.fill(); };
    const quad = (pts, fill) => { g.fillStyle = fill; g.beginPath(); pts.forEach((p, k) => (k ? g.lineTo(p[0], p[1]) : g.moveTo(p[0], p[1]))); g.closePath(); g.fill(); };
    const sil = '#000';

    // Distant hills behind the city.
    g.fillStyle = 'rgba(0,0,0,0.55)';
    g.beginPath(); g.moveTo(0, H);
    for (let px = 0; px <= W; px += 8) {
      const az = (px / W - 0.5) * 2 * MAP.azMax, z = 7000;
      const h = 60 + 70 * Math.sin(az * 3.1 + 1) + 40 * Math.sin(az * 7.7) + 25 * Math.sin(az * 17.3);
      g.lineTo(px, Y(h, Math.tan(az) * z, z));
    }
    g.lineTo(W, H); g.closePath(); g.fill();

    // Buildings: a far layer in haze, then the waterfront. Downtown rises right of centre.
    const layers = [
      { z: 4300, alpha: 0.72, lit: 0.13, scale: 0.8 },
      { z: 3420, alpha: 1.0, lit: 0.2, scale: 1.0 },
    ];
    for (const L of layers) {
      let x = -Math.tan(MAP.azMax) * L.z * 1.02;
      const xEnd = -x;
      while (x < xEnd) {
        const z = L.z + r(-120, 120);
        const w = r(16, 52);
        const az = Math.atan2(x, z);
        const downtown = Math.exp(-Math.pow((az - 0.13) / 0.14, 2));
        const midtown = Math.exp(-Math.pow((az + 0.3) / 0.12, 2)) * 0.45;
        let h = (r(10, 34) + (downtown + midtown) * r(40, 190) * (R() < 0.7 ? 1 : 0.4)) * L.scale;
        if (R() < 0.04) h *= 1.8;
        drawBuilding(x, w, h, z, L, downtown);
        x += w + (R() < 0.12 ? r(8, 50) : r(0, 5));
      }
    }
    function drawBuilding(x, w, h, z, L, dt) {
      const x0 = X(x, z), x1 = X(x + w, z), yb = Y(0, x, z), yt = Y(h, x, z);
      g.globalAlpha = L.alpha;
      g.fillStyle = sil; g.fillRect(x0, yt, x1 - x0, yb - yt);
      let top = h;
      const crown = R();
      if (h > 90 && crown < 0.35) { // setback tower
        const w2 = w * r(0.45, 0.7), xs = x + (w - w2) / 2, h2 = h * r(1.08, 1.22);
        g.fillRect(X(xs, z), Y(h2, xs, z), X(xs + w2, z) - X(xs, z), Y(h, xs, z) - Y(h2, xs, z));
        top = h2;
        if (R() < 0.5) { const xm = xs + w2 / 2; g.fillRect(X(xm, z) - 0.8, Y(top + h * 0.2, xm, z), 1.6, Y(top, xm, z) - Y(top + h * 0.2, xm, z)); top += h * 0.2; }
      } else if (h > 110 && crown < 0.55) { // spire
        const xm = x + w / 2;
        quad([[X(x + w * 0.2, z), Y(h, xm, z)], [X(xm, z), Y(h * 1.35, xm, z)], [X(x + w * 0.8, z), Y(h, xm, z)]], sil);
        top = h * 1.35;
      } else if (h > 60 && crown < 0.75) { // mast
        const xm = x + w * r(0.3, 0.7);
        g.fillRect(X(xm, z) - 0.6, Y(h + r(10, 30), xm, z), 1.2, Y(h, xm, z) - Y(h + 30, xm, z) + 1);
      }
      g.globalAlpha = 1;
      // Windows: 3.6 m floors, 3.2 m bays, a fraction of them lit.
      const lit = L.lit * (0.55 + 0.9 * R()) * (h > 80 ? 1.2 : 1);
      const warm = R() < 0.72;
      const bays = Math.max(1, Math.floor(w / 3.3)), floors = Math.floor((h - 4) / 3.6);
      const bright = L.alpha < 1 ? 0.55 : 1;
      for (let f = 0; f < floors; f++) {
        const yy = 3 + f * 3.6;
        for (let bI = 0; bI < bays; bI++) {
          if (R() > lit) continue;
          const xx = x + (bI + 0.5) * (w / bays) - 0.9;
          const k = (0.5 + 0.5 * R()) * bright;
          const c = warm ? [255, 190 + 40 * R(), 120 + 50 * R()] : (R() < 0.6 ? [190, 215, 255] : [255, 240, 220]);
          g.fillStyle = `rgb(${c[0] * k | 0},${c[1] * k | 0},${c[2] * k | 0})`;
          const ax = X(xx, z), ay = Y(yy + 2.2, xx, z);
          g.fillRect(ax, ay, Math.max(1, X(xx + 1.8, z) - ax), Math.max(1, Y(yy, xx, z) - ay));
        }
      }
      // Floodlit crowns and aviation lights on the tall ones.
      if (h > 120 && R() < 0.4 && L.alpha === 1) {
        const c = [[255, 200, 140], [150, 200, 255], [255, 150, 200], [170, 255, 220]][Math.floor(R() * 4)];
        const grd = g.createLinearGradient(0, Y(h, x, z), 0, Y(h - 14, x, z));
        grd.addColorStop(0, `rgba(${c},0.85)`); grd.addColorStop(1, 'rgba(0,0,0,1)');
        g.fillStyle = grd; g.fillRect(x0 + 0.5, Y(h, x, z), x1 - x0 - 1, Y(h - 14, x, z) - Y(h, x, z));
      }
      if (top > 150 && blinkers.length < 7 && L.alpha === 1) {
        const xm = x + w / 2;
        blinkers.push([Math.atan2(xm, z), Math.atan2(top + 1 - camH, Math.hypot(xm, z)), R()]);
      }
    }

    // Waterfront promenade lamps: long gold streaks in the water.
    for (let x = -3200; x < 3200; x += r(18, 34)) {
      const z = 3380 + r(-15, 15);
      if (Math.abs(Math.atan2(x, z)) > MAP.azMax) continue;
      dot(x, 6, z, r(0.7, 1.1), R() < 0.8 ? 'rgb(255,190,110)' : 'rgb(210,225,255)');
    }

    // The bridge: cable-stayed pylons, a suspension main cable and a necklace of lamps.
    const br = WORLD.bridge, bz = br.z, [pa, pb] = br.pylons;
    g.globalAlpha = 1;
    // headland on the left where the bridge lands
    g.fillStyle = sil; g.beginPath(); g.moveTo(0, H);
    for (let x = -3200; x <= br.x0 + 60; x += 20) {
      const z = 2300, h = 22 + 38 * smoothstep(-3200, -1900, x) * (1 - smoothstep(-1900, br.x0 + 60, x) * 0.7) + 6 * Math.sin(x * 0.03);
      g.lineTo(X(x, z), Y(h, x, z));
    }
    g.lineTo(X(br.x0 + 60, 2300), H); g.closePath(); g.fill();
    for (let k = 0; k < 40; k++) { const x = r(-3000, br.x0), z = 2300; dot(x, r(4, 30), z, 0.8, 'rgb(255,190,120)'); }
    // island under the right-hand end
    g.fillStyle = sil; g.beginPath(); g.moveTo(X(br.x1 - 60, bz), Y(0, br.x1, bz));
    for (let x = br.x1 - 60; x <= br.x1 + 260; x += 10) g.lineTo(X(x, bz), Y(6 + 10 * Math.sin((x - br.x1 + 60) / 320 * Math.PI) + 3 * Math.sin(x * 0.2), x, bz));
    g.lineTo(X(br.x1 + 260, bz), Y(0, br.x1 + 260, bz)); g.closePath(); g.fill();
    // deck
    const deckY = (x) => (x > br.x1 - 200 ? lerp(br.deck, 14, smoothstep(br.x1 - 200, br.x1, x)) : br.deck);
    g.fillStyle = sil; g.beginPath();
    for (let x = br.x0; x <= br.x1; x += 10) g.lineTo(X(x, bz), Y(deckY(x) + 2, x, bz));
    for (let x = br.x1; x >= br.x0; x -= 10) g.lineTo(X(x, bz), Y(deckY(x) - 2.5, x, bz));
    g.closePath(); g.fill();
    // piers
    for (let x = br.x0 + 120; x < br.x1; x += 150) if (Math.abs(x - pa) > 60 && Math.abs(x - pb) > 60) g.fillRect(X(x - 3, bz), Y(deckY(x), x, bz), Math.max(1.5, X(x + 3, bz) - X(x - 3, bz)), Y(0, x, bz) - Y(deckY(x), x, bz));
    // pylons (H-frame)
    for (const p of [pa, pb]) {
      for (const s of [-1, 1]) {
        const xl = p + s * 7;
        quad([[X(xl - 3, bz), Y(0, xl, bz)], [X(xl + 3, bz), Y(0, xl, bz)], [X(xl + 1.6, bz), Y(br.top, xl, bz)], [X(xl - 1.6, bz), Y(br.top, xl, bz)]], sil);
      }
      g.fillRect(X(p - 9, bz), Y(br.top - 6, p, bz), X(p + 9, bz) - X(p - 9, bz), Y(br.top - 12, p, bz) - Y(br.top - 6, p, bz));
      g.fillRect(X(p - 9, bz), Y(br.deck + 30, p, bz), X(p + 9, bz) - X(p - 9, bz), Y(br.deck + 24, p, bz) - Y(br.deck + 30, p, bz));
      blinkers.push([Math.atan2(p, bz), Math.atan2(br.top + 2 - camH, Math.hypot(p, bz)), R()]);
    }
    // main cable: parabola between pylon tops, and back-spans down to the deck ends
    const cable = (x) => {
      if (x >= pa && x <= pb) { const m = (pa + pb) / 2, u = (x - m) / ((pb - pa) / 2); return br.deck + 4 + (br.top - 8 - br.deck - 4) * u * u; }
      if (x < pa) { const u = (x - br.x0) / (pa - br.x0); return lerp(br.deck + 3, br.top - 8, u * u); }
      const u = (br.x1 - 200 - x) / (br.x1 - 200 - pb); return lerp(br.deck + 3, br.top - 8, clamp(u, 0, 1) ** 2);
    };
    g.strokeStyle = 'rgba(0,0,0,1)'; g.lineWidth = 1.4; g.beginPath();
    for (let x = br.x0 + 40; x <= br.x1 - 200; x += 6) { const px = X(x, bz), py = Y(cable(x), x, bz); x === br.x0 + 40 ? g.moveTo(px, py) : g.lineTo(px, py); }
    g.stroke();
    g.lineWidth = 0.6; g.strokeStyle = 'rgba(0,0,0,0.6)';
    for (let x = br.x0 + 60; x < br.x1 - 210; x += 18) { g.beginPath(); g.moveTo(X(x, bz), Y(cable(x), x, bz)); g.lineTo(X(x, bz), Y(deckY(x), x, bz)); g.stroke(); }
    for (let x = br.x0 + 40; x <= br.x1 - 200; x += 17) dot(x, cable(x) + 0.5, bz, 0.9, 'rgb(200,220,255)');
    for (let x = br.x0; x <= br.x1; x += 11) dot(x, deckY(x) + 1, bz, 0.75, 'rgb(255,200,130)');

    // Barges, dark on the water, each with a red and a white work light.
    for (const b of WORLD.barges) {
      const w = 40, h = 2.6;
      quad([[X(b.x - w / 2, b.z), Y(0, b.x, b.z)], [X(b.x + w / 2, b.z), Y(0, b.x, b.z)], [X(b.x + w / 2, b.z), Y(h, b.x, b.z)], [X(b.x - w / 2, b.z), Y(h, b.x, b.z)]], sil);
      dot(b.x - w / 2 + 2, h + 1.5, b.z, 1.1, 'rgb(255,60,40)');
      dot(b.x + w / 2 - 2, h + 1.5, b.z, 1.0, 'rgb(255,230,200)');
    }
    return { canvas: cv, blinkers };
  }

  // The audience at the rail: heads, shoulders, a few phones held up to the sky.
  function crowd(W, H) {
    const cv = document.createElement('canvas'); cv.width = W; cv.height = H;
    const g = cv.getContext('2d');
    const R = mulberry32(5);
    const r = (a, b) => a + (b - a) * R();
    const rows = [{ base: 0.8, s: 0.14, gap: [1.5, 2.4] }, { base: 0.97, s: 0.2, gap: [1.7, 2.7] }, { base: 1.2, s: 0.3, gap: [1.9, 3.3] }];
    const phones = [];
    for (const row of rows) {
      const unit = H * row.s;
      let x = -r(0, unit);
      while (x < W + unit) {
        person(x, H * row.base + r(-0.04, 0.04) * H, unit * r(0.85, 1.15), row.s < 0.25);
        x += unit * r(row.gap[0], row.gap[1]);
      }
    }
    function person(x, yb, s, canFilm) {
      g.fillStyle = '#000';
      g.beginPath();
      g.moveTo(x - 1.3 * s, yb + s);
      g.bezierCurveTo(x - 1.3 * s, yb - 0.8 * s, x - 0.9 * s, yb - 1.15 * s, x - 0.3 * s, yb - 1.25 * s);
      g.lineTo(x + 0.3 * s, yb - 1.25 * s);
      g.bezierCurveTo(x + 0.9 * s, yb - 1.15 * s, x + 1.3 * s, yb - 0.8 * s, x + 1.3 * s, yb + s);
      g.closePath(); g.fill();
      g.fillRect(x - 0.22 * s, yb - 1.55 * s, 0.44 * s, 0.4 * s);
      const hx = x + r(-0.06, 0.06) * s, hy = yb - 1.95 * s;
      g.beginPath(); g.ellipse(hx, hy, 0.4 * s, 0.5 * s, r(-0.12, 0.12), 0, TAU); g.fill();
      const style = R();
      if (style < 0.12) { g.beginPath(); g.arc(hx + r(-0.1, 0.1) * s, hy - 0.52 * s, 0.17 * s, 0, TAU); g.fill(); }
      else if (style < 0.22) { g.beginPath(); g.ellipse(hx, hy - 0.2 * s, 0.44 * s, 0.38 * s, 0, Math.PI, TAU); g.fill(); }
      else if (style < 0.36) { g.beginPath(); g.ellipse(hx, hy + 0.1 * s, 0.52 * s, 0.62 * s, 0, 0, TAU); g.fill(); }
      if (R() < 0.1) { // a child on the shoulders
        const cs = s * 0.55, cy = hy - 0.35 * s;
        g.beginPath(); g.ellipse(hx, cy - 0.6 * cs, 0.9 * cs, 0.7 * cs, 0, Math.PI, TAU); g.fill();
        g.beginPath(); g.ellipse(hx, cy - 1.7 * cs, 0.42 * cs, 0.5 * cs, 0, 0, TAU); g.fill();
      } else if (R() < 0.1 && canFilm) { // a phone held up to film the show
        const side = R() < 0.5 ? -1 : 1, ax = x + side * 0.8 * s, ay = yb - 1.0 * s;
        const hxp = x + side * r(0.7, 1.3) * s, hyp = yb - r(3.0, 3.6) * s;
        g.strokeStyle = '#000'; g.lineWidth = 0.2 * s; g.lineCap = 'round';
        g.beginPath(); g.moveTo(ax, ay); g.quadraticCurveTo(ax + side * 0.3 * s, (ay + hyp) / 2, hxp, hyp + 0.3 * s); g.stroke();
        const land = R() < 0.4, pw = (land ? 0.42 : 0.24) * s, ph = (land ? 0.24 : 0.42) * s;
        if (hyp - ph > 1) phones.push([hxp - pw / 2, hyp - ph / 2, pw, ph]);
      }
    }
    for (const [x, y, w, h] of phones) {
      g.fillStyle = 'rgb(215,228,255)'; g.fillRect(x, y, w, h);
    }
    return cv;
  }

  return { MAP, skyline, crowd };
})();
