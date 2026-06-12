# DESCENT — Roadmap

A short, honest, prioritized plan. The goal isn't a perfect game — it's to make
the project cheap to extend and good enough that a stranger has fun for 20 minutes.

---

## The core problem (be honest about it)

The game is short and a bit samey **not because the engine is weak** — it's
genuinely capable — but because *adding content has been expensive*. Every new
enemy meant editing several scattered `switch` blocks; every wave was hardcoded
magic numbers. So content stopped getting added.

Everything below is ordered to fix that first, then build on it.

---

## Now done ✅

- **`EnemyArchetype`** — all per-enemy stats (HP, speed, damage, hitbox, render
  scale, knockback resist) live in ONE place: `EnemyArchetype.forType(...)`.
  Adding an enemy's *numbers* is now one block, and the compiler refuses to build
  until a new type is fully defined. (Was: 3+ scattered switches that could drift.)

---

## Next — lower the cost of content (do these in order)

### 1. Data-driven waves  ⭐ highest leverage
Right now wave composition lives in `EnemyManager.pickType()` / `spawnWave()` as
hardcoded probability bands. Pull it into a single readable table:

```
wave 1 → { count: 4,  mix: ZOMBIE×3 THROWER×1 }
wave 5 → { count: 8,  mix: ..., modifier: FAST }
wave 8 → BOSS: TREANT, arena clears
```

Once this exists you can author the *entire difficulty curve* — themed waves,
boss waves, "all spiders" waves — by editing one file. This is what makes the
game feel designed instead of random.

### 2. One genuinely new enemy
Prove the pipeline works end-to-end by adding a single new enemy with a distinct
behavior (e.g. a *ranged kiter* that backs away while shooting, or an *exploder*
that rushes and detonates). One archetype row + one `tickXxxAI`. If this feels
quick, the refactors worked.

### 3. Make the moment-to-moment fighting feel good
The thing players judge in the first 2 minutes. Cheap, high-impact polish:
- hit-stop (freeze 2–3 frames on a solid hit)
- knockback + a satisfying hit sound + a flash
- enemies telegraph attacks clearly (wind-up pose) before they land
You already have the VFX hooks; this is tuning, not new systems.

---

## Then — give the 20 minutes a shape

- **A reason to move.** Right now you stand and survive. Add one pull: a drop to
  grab, a zone that shrinks, a node to defend. One is enough.
- **A visible goal.** "Reach wave 10" / "survive 5 minutes" on the HUD so players
  know what they're aiming at.
- **Tighten the path to the finale.** The colosseum endgame is your best content
  and most players won't reach it. Make it reachable in ~15 min, or add a way to
  preview/jump toward it.

---

## Shipping (when the above feels fun, not before)

- [ ] itch.io page live (free / pay-what-you-want) — packages already built in `itch-release/`
- [ ] 3 screenshots + a 30–60s screen-recording (games with a clip get far more clicks)
- [ ] One honest paragraph: "rough indie prototype, ~20 min, made from a custom Java engine"
- [ ] Post once in a place that likes jank-but-interesting (r/IndieDev feedback threads)

Earning "a few bucks" realistically comes *after* a handful of strangers say
"this is actually cool." Ship to learn that, not to get rich.

---

## Health / infra (do whenever, low urgency)

- [ ] `Window.java` is **8,666 lines** — a god-class. Not urgent, but it's the
      thing that'll slow you down most as you add features. Carve off cohesive
      pieces over time (input handling, HUD wiring, the render pass) — never all
      at once, and only with a working build between each cut.
- [ ] No automated tests. A single "does the game boot without crashing" smoke
      test would catch the dependency/packaging breakages that hurt most.
- [ ] Old `hs_err_pid*.log` crash dump sits in the repo root (already gitignored).
