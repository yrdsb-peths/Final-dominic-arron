'use strict';
/* Nocturne · app: the frame loop, the interface and input. */

(() => {
  const $ = (id) => document.getElementById(id);
  const canvas = $('stage');
  const reduced = !!(window.matchMedia && matchMedia('(prefers-reduced-motion: reduce)').matches);
  const mobile = /Android|iPhone|iPad|iPod/i.test(navigator.userAgent) || (navigator.maxTouchPoints > 1 && Math.min(screen.width, screen.height) < 820);
  const MAXP = mobile ? 55000 : 120000;

  Sim.init(MAXP);
  Sim.flashGain = reduced ? 0.4 : 1;
  let okGL = false;
  try { okGL = Renderer.init(canvas, MAXP); } catch (e) { console.error(e); }
  if (!okGL) { $('nogl').hidden = false; $('intro').hidden = true; return; }

  Show.build();
  const END = Show.end;
  const scripted = Show.scripted.slice().sort((a, b) => a.q - b.q);
  const st = {
    mode: 'intro', t: 0, clock: 0, paused: false, frozen: false, lastAct: null, idle: 0,
    auto: false, autoT: 0, type: 'random', colour: 'mixed', holding: null, holdT: 0,
    pending: [], endShown: false, applauded: false, previewT: 2.5, uiT: 0,
  };

  /* ---------- Programme, specs, timeline and script ---------- */
  const program = $('program');
  for (const a of Show.ACTS) {
    const li = document.createElement('li');
    li.append(span('n', a.n), span('name', a.name), span('t', fmtClock(a.t)));
    program.append(li);
  }
  const specs = `${scripted.length} cues · 5 barges · 1 bridge · ${fmtClock(END + 4)}`;
  $('specs').textContent = specs;
  $('endSpecs').textContent = specs;

  const track = $('track');
  for (const a of Show.ACTS) {
    const b = document.createElement('button');
    b.type = 'button'; b.className = 'mark'; b.textContent = a.n;
    b.title = a.name; b.setAttribute('aria-label', 'Jump to ' + a.name);
    b.style.left = (a.t / (END + 4) * 100) + '%';
    b.addEventListener('click', (e) => { e.stopPropagation(); jump(a.t - 0.4); });
    track.append(b);
  }
  track.addEventListener('click', (e) => {
    const r = track.getBoundingClientRect();
    jump(clamp((e.clientX - r.left) / r.width, 0, 1) * (END + 4) - 1);
  });
  track.addEventListener('keydown', (e) => {
    if (e.key === 'ArrowRight') jump(st.t + 10);
    else if (e.key === 'ArrowLeft') jump(st.t - 10);
  });

  const rows = $('scriptRows'), rowOf = new Map();
  let actIdx = 0;
  for (const c of scripted) {
    while (actIdx < Show.ACTS.length && Show.ACTS[actIdx].t <= c.at + 0.01) {
      const a = Show.ACTS[actIdx++];
      const tr = document.createElement('tr'); tr.className = 'act-row';
      const td = document.createElement('td'); td.colSpan = 5; td.textContent = a.n + '. ' + a.name;
      tr.append(td); tr.addEventListener('click', () => jump(a.t - 0.4)); rows.append(tr);
    }
    const tr = document.createElement('tr');
    for (const v of ['Q' + String(c.q).padStart(3, '0'), fmtCue(c.at), c.pos, c.device, c.effect]) { const td = document.createElement('td'); td.textContent = v; tr.append(td); }
    tr.addEventListener('click', () => jump(c.at - 3.2));
    rows.append(tr); rowOf.set(c, tr);
  }

  /* ---------- Free play palette ---------- */
  const chips = $('chips');
  Free.TYPES.forEach((t, k) => {
    const b = document.createElement('button');
    b.type = 'button'; b.className = 'chip'; b.textContent = t.label; b.dataset.type = t.id;
    b.setAttribute('aria-pressed', t.id === st.type ? 'true' : 'false');
    if (k < 10) b.title = 'Key ' + ((k + 1) % 10);
    b.addEventListener('click', () => selectType(t.id));
    chips.append(b);
  });
  const sws = $('swatches');
  ['mixed', ...COLOURS].forEach((c) => {
    const b = document.createElement('button');
    b.type = 'button'; b.className = 'sw' + (c === 'mixed' ? ' mixed' : ''); b.dataset.colour = c;
    const name = c === 'mixed' ? 'Mixed colours' : PYRO[c].label;
    b.title = name; b.setAttribute('aria-label', name);
    b.setAttribute('aria-pressed', c === st.colour ? 'true' : 'false');
    if (c !== 'mixed') b.style.background = cssColour(PYRO[c].rgb);
    b.addEventListener('click', () => { st.colour = c; for (const x of sws.children) x.setAttribute('aria-pressed', x.dataset.colour === c ? 'true' : 'false'); });
    sws.append(b);
  });
  function selectType(id) {
    st.type = id;
    for (const x of chips.children) x.setAttribute('aria-pressed', x.dataset.type === id ? 'true' : 'false');
  }
  function cssColour(rgb) {
    const m = Math.max(...rgb);
    return 'rgb(' + rgb.map((v) => Math.round(Math.pow(v / m, 1 / 2.2) * 255)).join(',') + ')';
  }
  function span(cls, text) { const s = document.createElement('span'); s.className = cls; s.textContent = text; return s; }

  /* ---------- Sizing and adaptive quality ---------- */
  let scale = 1, ema = 16, slowT = 0, fastT = 0;
  function resize() {
    const cw = window.innerWidth, ch = window.innerHeight;
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    let s = dpr * scale;
    const cap = mobile ? 1.3e6 : 2.4e6, px = cw * ch * s * s;
    if (px > cap) s *= Math.sqrt(cap / px);
    setupCamera(cw / ch);
    Renderer.resize(Math.max(320, Math.round(cw * s)), Math.max(200, Math.round(ch * s)), true);
  }
  function adapt(ms) {
    ema = ema * 0.94 + ms * 0.06;
    if (ema > 25) { slowT += ms; fastT = 0; } else if (ema < 14) { fastT += ms; slowT = 0; } else { slowT = 0; fastT = 0; }
    if (slowT > 1500 && scale > 0.45) { scale *= 0.84; slowT = 0; resize(); }
    else if (fastT > 6000 && scale < 1) { scale = Math.min(1, scale * 1.12); fastT = 0; resize(); }
  }
  let rt = 0;
  window.addEventListener('resize', () => { clearTimeout(rt); rt = setTimeout(resize, 120); });
  resize();

  /* ---------- Modes ---------- */
  function hideIntro() {
    const el = $('intro');
    if (el.hidden) return;
    el.classList.add('gone');
    setTimeout(() => { el.hidden = true; }, 900);
  }
  function setChrome() {
    const show = st.mode === 'show', free = st.mode === 'free';
    $('hud').hidden = !(show || free);
    $('timeline').hidden = !show;
    $('cuelog').hidden = !show;
    $('palette').hidden = !free;
    $('bMode').textContent = free ? 'Watch the show' : 'Free play';
    $('bPause').hidden = !show;
    $('hudAct').textContent = free ? 'Free play' : '';
    if (!show) $('act').classList.remove('on');
  }
  function beginShow(from) {
    Sound.init();
    Sound.suspend(false);
    hideIntro(); hideEnd();
    st.mode = 'show'; st.paused = false; setPauseLabel();
    setChrome();
    jump(from === undefined ? -1.5 : from, true);
  }
  function enterFree() {
    Sound.init();
    Sound.suspend(false);
    hideIntro(); hideEnd();
    st.mode = 'free'; st.paused = false; st.pending.length = 0;
    setChrome();
    closeScript();
  }
  function jump(t, fresh) {
    if (st.mode !== 'show') { beginShow(t); return; }
    Sim.clear(!fresh);
    st.t = t; st.lastAct = null; st.endShown = false; st.applauded = false;
    Show.seek(t);
    hideEnd();
    $('cuelog').replaceChildren();
    for (const [c, tr] of rowOf) tr.className = c.t < t ? 'fired' : '';
    if (st.paused) togglePause();
  }
  function showEnd() {
    st.endShown = true;
    const el = $('end'); el.hidden = false;
    requestAnimationFrame(() => el.classList.add('on'));
    document.body.classList.remove('idle');
  }
  function hideEnd() { const el = $('end'); el.classList.remove('on'); el.hidden = true; }

  function setPauseLabel() { $('bPause').textContent = st.paused ? 'Resume' : 'Pause'; }
  function togglePause() {
    if (st.mode !== 'show') return;
    st.paused = !st.paused; setPauseLabel();
    Sound.suspend(st.paused);
  }
  function toggleSound() {
    Sound.init();
    Sound.setEnabled(!Sound.enabled);
    const b = $('bSound'); b.textContent = Sound.enabled ? 'Sound on' : 'Sound off'; b.setAttribute('aria-pressed', Sound.enabled ? 'true' : 'false');
  }
  function toggleFull() {
    const d = document;
    if (d.fullscreenElement) { d.exitFullscreen && d.exitFullscreen().catch(() => {}); return; }
    const el = d.documentElement;
    if (el.requestFullscreen) el.requestFullscreen().catch(() => {});
  }
  if (!document.fullscreenEnabled) $('bFull').hidden = true;
  function openScript() {
    $('script').hidden = false; $('bScript').setAttribute('aria-pressed', 'true');
    const now = rows.querySelector('tr.now');
    if (now) now.scrollIntoView({ block: 'center' });
  }
  function closeScript() { $('script').hidden = true; $('bScript').setAttribute('aria-pressed', 'false'); }
  function toggleScript() { $('script').hidden ? openScript() : closeScript(); }
  function toggleAuto() {
    st.auto = !st.auto; st.autoT = 0.2;
    $('bAuto').setAttribute('aria-pressed', st.auto ? 'true' : 'false');
  }

  $('begin').addEventListener('click', () => beginShow());
  $('goFree').addEventListener('click', enterFree);
  $('again').addEventListener('click', () => beginShow());
  $('endFree').addEventListener('click', enterFree);
  $('endScript').addEventListener('click', openScript);
  $('bPause').addEventListener('click', togglePause);
  $('bSound').addEventListener('click', toggleSound);
  $('bScript').addEventListener('click', toggleScript);
  $('scriptClose').addEventListener('click', closeScript);
  $('bMode').addEventListener('click', () => (st.mode === 'free' ? beginShow() : enterFree()));
  $('bFull').addEventListener('click', toggleFull);
  $('bAuto').addEventListener('click', toggleAuto);
  $('bFinale').addEventListener('click', () => { for (const a of Free.finale()) st.pending.push(a); hideHint(); });

  /* ---------- Show callbacks ---------- */
  let actTimer = 0;
  function onCue(c) {
    const log = $('cuelog');
    const li = document.createElement('li');
    li.append(span('q', 'Q' + String(c.q).padStart(3, '0')), span('t', fmtCue(c.at)), span('p', c.pos), span('e', c.device + ' · ' + c.effect));
    log.append(li);
    while (log.children.length > 4) log.firstChild.remove();
    const tr = rowOf.get(c);
    if (tr) {
      const prev = rows.querySelector('tr.now'); if (prev) prev.className = 'fired';
      tr.className = 'now';
      if (!$('script').hidden) tr.scrollIntoView({ block: 'nearest' });
    }
  }
  function actCheck() {
    if (st.t < 0) return;
    const a = Show.actAt(st.t);
    if (a === st.lastAct) return;
    st.lastAct = a;
    $('hudAct').textContent = a.n + ' · ' + a.name;
    const el = $('act');
    el.querySelector('.act-n').textContent = 'Movement ' + a.n;
    el.querySelector('.act-name').textContent = a.name;
    el.querySelector('.act-blurb').textContent = a.blurb;
    el.classList.add('on');
    clearTimeout(actTimer);
    actTimer = setTimeout(() => el.classList.remove('on'), 5600);
  }

  /* ---------- Free play and the idle sky ---------- */
  function freeTick(dt) {
    for (let i = st.pending.length - 1; i >= 0; i--) {
      st.pending[i][0] -= dt;
      if (st.pending[i][0] <= 0) { const f = st.pending[i][1]; st.pending.splice(i, 1); f(); }
    }
    if (st.auto) { st.autoT -= dt; if (st.autoT <= 0) { st.autoT = rr(0.6, 1.6); Free.randomShot(st.type, st.colour); } }
    if (st.holding) { st.holdT -= dt; if (st.holdT <= 0) { st.holdT = 0.26; Free.fireAt(st.holding[0], st.holding[1], st.type, st.colour); } }
  }
  const PREVIEW = [{ type: 'kamuro', cal: 250 }, { type: 'willow', cal: 200 }, { type: 'peony', cal: 200, color: 'violet', pistil: 'white' }, { type: 'chrys', cal: 250, color: 'gold', to: 'red', pistil: 'green' }, { type: 'dahlia', cal: 200, color: 'aqua' }];
  let pv = 0;
  function previewTick(dt) {
    st.previewT -= dt;
    if (st.previewT > 0) return;
    st.previewT = rr(4.5, 7);
    const s = { ...PREVIEW[pv++ % PREVIEW.length] }, b = WORLD.barges[pv % 2 ? 3 : 4];
    const y = Show.altOf(s.cal);
    Pyro.launch(s, b, { x: b.x + rr(-40, 60), y, z: b.z }, Show.flight(y));
  }
  // Something is already blooming when the page opens.
  Pyro.burst({ type: 'kamuro', cal: 250 }, 250, 310, 1240, [0, 0, 0]);
  for (let k = 0; k < 50; k++) Sim.update(1 / 60);

  /* ---------- Input ---------- */
  function toNdc(e) { const r = canvas.getBoundingClientRect(); return [(e.clientX - r.left) / r.width * 2 - 1, 1 - (e.clientY - r.top) / r.height * 2]; }
  function hideHint() { $('hint').classList.add('gone'); }
  canvas.addEventListener('pointerdown', (e) => {
    wake();
    if (st.mode !== 'free') return;
    const p = toNdc(e);
    Free.fireAt(p[0], p[1], st.type, st.colour);
    st.holding = p; st.holdT = 0.45;
    try { canvas.setPointerCapture(e.pointerId); } catch (err) { /* capture is optional */ }
    hideHint();
  });
  canvas.addEventListener('pointermove', (e) => { if (st.holding) st.holding = toNdc(e); });
  const release = () => { st.holding = null; };
  canvas.addEventListener('pointerup', release);
  canvas.addEventListener('pointercancel', release);
  window.addEventListener('pointermove', wake, { passive: true });
  window.addEventListener('keydown', (e) => {
    wake();
    const onButton = e.target && e.target.closest && e.target.closest('button, [role="slider"]');
    const k = e.key;
    if (k === ' ' && !onButton) { e.preventDefault(); togglePause(); }
    else if (k === 'm' || k === 'M') toggleSound();
    else if (k === 'f' || k === 'F') toggleFull();
    else if (k === 's' || k === 'S') toggleScript();
    else if (k === 'Escape') closeScript();
    else if ((k === 'a' || k === 'A') && st.mode === 'free') toggleAuto();
    else if (st.mode === 'free' && /^[0-9]$/.test(k)) { const t = Free.TYPES[(Number(k) + 9) % 10]; if (t) selectType(t.id); }
  });
  function wake() { st.idle = 0; document.body.classList.remove('idle'); }

  /* ---------- Frame loop ---------- */
  let last = performance.now();
  function frame(now) {
    const raw = now - last; last = now;
    const dt = Math.min(raw / 1000, 0.1);
    if (!document.hidden && raw < 250) adapt(raw);
    st.clock += dt;
    // Sub-step so a slow device still keeps show time in step with the audio clock.
    const sdt = st.paused || st.frozen ? 0 : dt;
    const steps = Math.max(1, Math.ceil(sdt / 0.034)), h = sdt / steps;
    for (let k = 0; k < steps; k++) {
      if (st.mode === 'show') { if (h > 0) { st.t += h; Show.tick(st.t, onCue); } }
      else if (st.mode === 'free') { if (h > 0) freeTick(h); }
      else if (!st.frozen) previewTick(dt / steps);
      Sim.update(h);
    }
    if (st.mode === 'show' && sdt > 0) {
      actCheck();
      if (!st.applauded && st.t > END + 2) { st.applauded = true; Sound.applause(); }
      if (!st.endShown && st.t > END + 7) showEnd();
    }
    Renderer.render(st.clock);

    st.uiT -= dt;
    if (st.uiT <= 0) {
      st.uiT = 0.1;
      if (st.mode === 'show') {
        const total = END + 4, f = clamp(st.t / total, 0, 1);
        $('fill').style.transform = 'scaleX(' + f + ')';
        $('clock').textContent = fmtClock(st.t) + ' / ' + fmtClock(total);
        track.setAttribute('aria-valuenow', String(Math.round(f * 100)));
      }
      st.idle += 0.1;
      if (st.mode === 'show' && !st.paused && st.idle > 3 && $('script').hidden) document.body.classList.add('idle');
    }
    requestAnimationFrame(frame);
  }
  requestAnimationFrame(frame);

  // Hooks for automated checks: jump the simulation to a moment and hold it there.
  window.__nocturne = {
    at(t) {
      st.mode = 'show'; setChrome(); $('intro').hidden = true;
      Sim.clear(false); Show.seek(0); st.t = 0;
      const h = 1 / 60;
      while (st.t < t) { st.t += h; Show.tick(st.t, null); Sim.update(h); }
      st.frozen = true;
      return this.stats();
    },
    free(on) { st.frozen = !on; },
    fire(type, x, y, colour) { Pyro.launch({ type, cal: 200, color: colour || 'gold' }, WORLD.barges[2], { x, y, z: 1240 }, 0.01); },
    stats() { return { particles: Sim.count, smoke: Sim.smokeCount, t: st.t, size: Renderer.size, hdr: Renderer.hdr, scale, cues: scripted.length, end: END }; },
  };
})();
