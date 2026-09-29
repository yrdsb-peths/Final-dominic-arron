# DESCENT

**Fight. Fly. Forge.**

DESCENT is a 3D high-mobility voxel survival game. You awaken in a procedurally generated world. Survive 6 waves of enemies at spawn — each wave unlocks a new combat ability from the Chakra Crystal bonded to you. Once you're armed, the crystal shatters: shards scattered across the land. You gain FLIGHT and follow beams of light to claim each one. Every shard you gather is forged into a weapon — culminating in orbital lasers, time-stopping, and parkour at impossible speed.

---

## How to Run

### macOS
```bash
bash play-mac.sh
```

### Windows
```
play-windows.bat
```

### Build from Source
```bash
./gradlew build
java -jar build/libs/game.jar
```

> **Note:** The game uses a custom 3D engine (LWJGL + OpenGL 3.3) as a teacher-approved
> alternative to Greenfoot. It is a standalone `.jar` — no Greenfoot install needed.

---

## How to Play

Click **PLAY** on the title screen and the game guides you from there — no explanation needed.

### Quick-Start

1. Load the game and press **Play**.
2. A **5-step tutorial** (about 1 minute) teaches: movement, Slash (`F`), Dash (`Q`), and the Sniper (hold **Left-Click** to charge, release to fire). You also start with **Radar Sweep** (`F10`) — ping every enemy through walls.
3. **Survive 6 waves** at spawn. Each wave clears, an unlock card appears, press `ENTER` to continue:

   | Wave | New abilities |
   |---|---|
   | 1 | Slash, Dash |
   | 2 | Quagmire |
   | 3 | Lightning, Grab |
   | 4 | Blink, Position Swap |
   | 5 | Stone Pillar, Cannonball |
   | 6 | Stand, Substitute, Seal — **the crystal shatters; the Voyage begins** |

4. **FLIGHT is granted.** Double-tap `Space` to fly. A glowing beam marks your first shard. Follow it — each shard forges a weapon, shown with a full **WEAPON FORGED** reveal card that tells you exactly which hotbar slot it landed in:

   | Destination | What to do | Forges |
   |---|---|---|
   | **Crystal Fields** | fly into the violet beam | **Gatling Gun** |
   | **Glowing Groves** | follow the green beam | **Deprivation Domain** (`'`) |
   | **Floating Isles** | fly **UP** into the sky beam | **Stone Cannon** |
   | **The Ashlands** | destroy the **Inferno Tower**, then claim the core | **Orbital Annihilation** |
   | all shards reunited | automatic | **The World** — stop time |

5. Press `F1` at any time for the full ability reference.

> **Easter eggs (try them):** `` ` `` = Flappy Bird mode &nbsp;·&nbsp; `F4` = meteor storm &nbsp;·&nbsp; `F6` = non-Euclidean space

> **For grading / testing:** `/showcase` (unlock everything instantly), `/skip` (skip tutorial), `/biome <name>` (warp to any biome).

### Core Controls

| Key | Action |
|---|---|
| `W` `A` `S` `D` | Move |
| `Space` | Jump |
| Double-tap `W` | Sprint |
| Double-tap `Space` | Toggle flight (after wave 6) · `V` cycles modes |
| Mouse | Look around |
| **Left Click** | **Fire the selected weapon** (every weapon — Sniper, Gatling, Orbital, Time Stop, Stone Cannon) / mine block |
| Right Click | Place selected block (e.g. the Torch at night) |
| `1`–`9` | Select hotbar slot |
| `Left Alt` | Open backpack (drag weapons to hotbar) |
| `T` | Open chat (type commands) |
| `F1` | Full ability reference card |
---

## Rubric Checklist

This section maps every grading criterion directly to the code that fulfils it.

---

### Title Screen & End Screen

| Feature | Implementation |
|---|---|
| Title screen | [`WindowHud.java`](src/main/java/com/leaf/game/core/WindowHud.java) — rendered with Dear ImGui; shows game title and a **Play Game** button |
| Intro cutscene | [`CutsceneManager.java`](src/main/java/com/leaf/game/core/CutsceneManager.java) — letterbox bars + typewriter text plays once when the world finishes loading |
| End screen (win) | Same — ending cutscene triggers after clearing Wave 9, then shows final stats |
| Death screen | [`RunRecords.java`](src/main/java/com/leaf/game/core/RunRecords.java) + `WindowHud` — shows wave reached, time played, and all-time bests |

---

### Instructions / Tutorial

| Feature | Implementation |
|---|---|
| Staged tutorial | [`TutorialManager.java`](src/main/java/com/leaf/game/core/TutorialManager.java) — teaches one concept per step (movement → melee → ranged → flight) and waits for the player to *actually do* each action before advancing |
| Ability practice | [`AbilityPractice.java`](src/main/java/com/leaf/game/core/AbilityPractice.java) — per-ability mini-sessions that play automatically after each wave unlock |
| In-game help | Press `F1` at any time for the full ability reference card |
| Skip option | Type `/skip` in chat to skip the tutorial instantly (useful for grading) |

---

### Scoring & Achievement System

| Feature | Implementation |
|---|---|
| Best wave record | [`RunRecords.java`](src/main/java/com/leaf/game/core/RunRecords.java) — persists best wave reached, total deaths, and run time to `descent_records.txt` |
| Ability unlock progression | [`Progression.java`](src/main/java/com/leaf/game/core/Progression.java) — 18 unlockable abilities accumulate across runs; unlocked set is saved to disk |
| End-of-run stats | Death screen + ending cutscene show: wave reached, deaths, total run time, lifetime best wave |
| Secret achievement | **Kamui** — a hidden ability that only unlocks after dying exactly 3 times |

---

### Motivation to Keep Playing

- **Escalating waves:** each wave spawns more enemies with harder types (`EnemyManager.java`)
- **18 progressive ability unlocks:** a new combat power is revealed on a cinematic card after every wave
- **Persistent records:** `descent_records.txt` tracks your best wave across sessions — beat your own high score
- **Hidden content:** the Kamui ability is only unlocked through dying 3 times, rewarding experimentation
- **Story:** an intro and ending cutscene give the run context and a goal to reach

---

### Sound & Sound Effects

[`AudioManager.java`](src/main/java/com/leaf/game/core/AudioManager.java) — a complete OpenAL 3D spatial audio engine.

- **60+ original sound effects** covering: every ability, movement states (walking, running, landing, water), block break types (stone, soil, sand, crystal), and ambient wind/water environments
- Sounds are positional in 3D space — distant audio fades correctly by distance
- Underwater reverb filter activates when the camera enters water

---

### Animation Using Arrays

The animation system uses arrays as its core data structure throughout:

| Class | Array usage |
|---|---|
| [`AnimClip.java`](src/main/java/com/leaf/game/anim/AnimClip.java) | Stores a `Map<String, List<Keyframe>>` — an array of `Keyframe` objects per named bone |
| [`AnimPlayer.java`](src/main/java/com/leaf/game/anim/AnimPlayer.java) | Iterates the keyframe array each frame to interpolate transforms; outputs a `Map<String, Matrix4f>` (one pose matrix per part) that the renderer applies |
| [`AnimModel.java`](src/main/java/com/leaf/game/anim/AnimModel.java) | Holds an array of `PartDef` (one per bone/cube) defining the skeleton |
| [`BlockbenchImporter.java`](src/main/java/com/leaf/game/anim/BlockbenchImporter.java) | Parses `.bbmodel` files from Blockbench and populates those arrays at load time |
| [`ModelRenderer.java`](src/main/java/com/leaf/game/anim/ModelRenderer.java) | Walks the part array in hierarchy order to compute final world-space transforms |

Enemy models (Golem, Slime) are all animated using this system. Each live enemy owns its own `AnimPlayer` instance so they animate independently.

---

### Source Code Quality

| Requirement | Status |
|---|---|
| All classes capitalised | Yes — every class follows PascalCase |
| Class-level Javadoc (`/** */` comments) | Yes — all major classes have API descriptions |
| Public method Javadoc (`@param`, `@return`) | Yes — all public methods documented |
| Good variable / method / class names | Yes — descriptive names throughout |
| Arrays used correctly | Yes — keyframe arrays, part arrays, enemy lists, hotbar array |
| Organised and correctly formatted | Yes — consistent indentation, grouped sections with divider comments |
| Comments on complex code | Yes — non-obvious logic (A\* pathfinding, FABRIK IK, star coordinate transforms) is explained inline |

**Key classes to sample for marking:**

- [`AnimPlayer.java`](src/main/java/com/leaf/game/anim/AnimPlayer.java) — clean array iteration and interpolation
- [`EnemyManager.java`](src/main/java/com/leaf/game/entity/EnemyManager.java) — wave spawning with clear Javadoc
- [`Progression.java`](src/main/java/com/leaf/game/core/Progression.java) — ability unlock schedule, well-commented enum
- [`TutorialManager.java`](src/main/java/com/leaf/game/core/TutorialManager.java) — multi-step tutorial logic with step-by-step documentation

---

## Developer Cheat Codes

Want to see the whole arsenal without playing all 11 waves? `/showcase` unlocks everything.

| Key / Command | What it does |
|---|---|
| `/showcase` (chat) | **Unlock everything** — every ability + all weapons in slots 1-5, god-mode on |
| `/showcase combat` (chat) | Spawn 7 enemies in a ring around you |
| `/showcase horde` (chat) | Spawn 14 enemies |
| `/showcase volcanic` (chat) | Warp to volcanic biome + erect Inferno Tower |
| `/showcase sakura` (chat) | Warp to cherry-blossom grove |
| `/showcase off` (chat) | Turn demo mode off |
| `/biome <name>` (chat) | Warp to any biome (forest, desert, crystal, autumn, mushroom, volcanic…) — handy to jump straight to a Voyage beacon |
| `/skip` (chat) | Skip the tutorial → the Voyage begins immediately |
| `F3` | Open debug overlay (FPS, player coords, time scale) |
| `P` | Spawn a basic enemy at your crosshair |
| `0` (zero) | Spawn a boss-tier Guardian Golem |

**The god-tier weapons (instant via `/showcase`, or forged on the Voyage):**

| Slot / Key | Weapon | How to use |
|---|---|---|
| Hotbar slot 1 | **Gatling Gun** (Crystal Fields) | hold Left Click to rapid-fire |
| Hotbar slot 2 | **Sniper** (start) | hold LMB → charge, release → crystal bolt explosion |
| Hotbar slot 3 / `F7` | **Orbital Annihilation** (Ashlands tower) | LMB → volumetric cinematic strike |
| Hotbar slot 4 / `F8` | **The World / Time Stop** (all shards) | LMB → freeze all enemies |
| Hotbar slot 5 / `I` | **Stone Cannon** (Floating Isles) | hold LMB near stone → absorb → boulder |
| `F10` | **Radar Sweep** (starting kit) | ping every enemy through walls |
| `'` (apostrophe) | **Deprivation Domain** — golden hemisphere auto-slasher | |
| `,` (comma) | **Quantum Bullet** — phase-shift projectile through walls | |
| `/god` (chat) | Toggle invincibility |
| `/give all` (chat) | Fill hotbar with all building blocks |
| `/spawn spider` (chat) | Spawn a procedural IK spider enemy |
| `/spawn treant` (chat) | Spawn a Treant (disguised tree enemy) |

**Screenshot / trailer helpers:**

| Command | What it does |
|---|---|
| `/hud` | Hide the whole HUD (damage numbers stay) for clean shots |
| `/time <0-1 \| dawn \| noon \| dusk \| night>` | Set the time of day |
| `/tp <x> <y> <z> [fly]` | Teleport (`fly` = hover there) |
| `/look <yaw> <pitch>` | Point the camera (degrees) |
| `/arena [n] [dummy]` | Build a flat grass stage with a fan of enemies (or practice dummies) in front of you |
| `/peace` | Stop waves & roamers and clear enemies |
| `/fxslow [scale]` | Slow only the ability VFX (e.g. `/fxslow 0.2`) to study an effect |

---

## Graphics

The renderer is a custom OpenGL 3.3 pipeline:

- **Lighting** — hemisphere sky light + warm ground bounce + the sun/moon, all driven by the day/night cycle; creatures share the same light.
- **Shadows** — real-time sun/moon shadow map (trees, cliffs, overhangs), soft-filtered.
- **Materials** — every block without a PNG gets procedural pixel-art detail (rock grain, soil, leaf clumps, bark, crystal); water ripples and reflects the sky; lava flows and glows; mushroom caps and lichen glow at night.
- **Ground cover** — swaying grass tufts and wildflower meadows.
- **Atmosphere** — aerial-perspective haze, sun-lit drifting clouds.
- **Post-processing** — HDR buffer with a restrained bloom (only true light sources glow), a filmic tone curve, subtle grade and vignette.

If a laptop struggles, these switches live at the top of `GameConfig.java`:

| Setting | Default | Effect |
|---|---|---|
| `shadows` | `true` | Turn off to skip the extra shadow pass |
| `shadowMapSize` | `2048` | `1024` is much cheaper and still looks good |
| `postFx` | `true` | Bloom / grade / vignette |
| `vignette` | `0.32` | Corner darkening (0 = off) |

---

## Full Ability Reference

### Combat & Offensive

| Ability | Key | Description |
|---|---|---|
| Sniper | hold LMB | Hold to charge a crystal bolt; longer charge = bigger explosion |
| Runic Cleave (Slash) | `F` | Wide melee swing that shatters blocks in a 3D crescent |
| Gatling Gun | Hotbar slot 5 | Hold Left Click to rapid-fire and vaporise terrain |
| Lightning | `U` | Press once — instant full-power strike on your target |
| Stone Canon | `I` | Stand near stone, hold to absorb it into a boulder, release to fire |
| Grab & Slam | `O` | Grab an enemy overhead and slam them into the ground |
| Knife Combo | `;` | Three rapid high-damage melee slashes |

### Movement & Mobility

| Ability | Key | Description |
|---|---|---|
| Flight | Double-tap `Space` | Take to the skies; press `V` to cycle Skim / Soar / Grapple modes |
| Dash | `Q` | Instant burst in your movement direction |
| Blink | `E` | Teleport up to 22 blocks to your crosshair |
| Cannonball | Hold `G` | Charge up, preview your trajectory arc, then launch yourself as a projectile |
| Stone Pillar | `K` | Erupt a stone spire beneath your feet and launch skyward |
| Ground Smash | `Shift` (in air) | Slam into the earth creating a crater; bigger from higher falls |

### Tactical & Utility

| Ability | Key | Description |
|---|---|---|
| Position Swap | `J` | Instantly swap places with the nearest visible enemy |
| Minato's Seal | `H` / `B` / `N` | Throw a warp anchor (`H`), teleport to it (`B`), recall it (`N`) |
| Kamui | `Z` | Phase into another dimension — invincible; hold Left Click to vortex-suck enemies |
| Paper Substitute | Hold `V` | Prime a decoy; the next hit is absorbed, you blink back, decoy explodes |
| Quagmire | `M` | Fire a mud wave along the ground that traps enemies |
| **Hotdogs!** | walk over | Enemies drop hotdogs when they die — walk over one to eat it (+25 HP) |
| Manhattan Transfer | `X` / `Tab` | Deploy a combat drone that auto-fires; `Tab` to pilot it yourself |

---

## Architecture Overview

The game is built on a **custom 3D engine** (teacher-approved):

| Layer | Key classes |
|---|---|
| Engine core | `Window.java` — game loop, input routing, render orchestration |
| Voxel world | `World.java`, `Chunk.java`, `WorldGen.java` — procedural generation + streaming |
| Player | `Player.java` + 7 sub-controllers (`AbilityController`, `AttackController`, `FlightController`, `StandController`, `SealController`, `LightningController`, `GrabController`) |
| Enemies | `Enemy.java`, `EnemyManager.java` — AI state machine, A\* pathfinding, wave spawning |
| Spider | `SpiderEnemy.java` + FABRIK IK system — procedural leg placement on any surface |
| Skeletal animation | `AnimPlayer.java`, `AnimModel.java`, `AnimClip.java` — array-based keyframe interpolation |
| Rendering | `Shader.java`, `ModelRenderer.java`, `ChunkMesher.java`, `BlockTextureAtlas.java` |
| Audio | `AudioManager.java` — OpenAL 3D spatial audio with EFX reverb |
| Sky / Astronomy | `DayNight.java`, `Astronomy.java`, `CelestialNav.java` — real BSC5 star catalogue |
| Progression | `Progression.java`, `RunRecords.java` — unlock system + persistent high-score records |
| HUD / UI | `WindowHud.java`, `CutsceneManager.java`, `TutorialManager.java`, `BackpackUI.java` |

---
## Credits

- **Engine:** Java 17 + LWJGL 3 (OpenGL 3.3)
- **Math:** JOML (Java OpenGL Math Library)
- **UI:** Dear ImGui (via imgui-java)
- **Audio:** OpenAL + Java Sound SPI
- **3D Models & Animations:** Blockbench (`.bbmodel` → custom JSON pipeline)
- **Star data:** Yale Bright Star Catalogue (BSC5)
- **Procedural Spider Kinematics:** Inverse Kinematics (IK) and leg-gait algorithm principles adapted from [TheCymaera's Minecraft Spider](https://github.com/TheCymaera/minecraft-spider)
- **Canyon Generation:** Sedimentary mesa noise algorithms adapted from [GelamiSalami's Hybrid SDF-Voxel Traversal](https://www.shadertoy.com/view/dtVSzw)
- **Mountain Generation:** Analytical derivative FBM and terrain erosion algorithms adapted from [Inigo Quilez's Elevated](https://www.shadertoy.com/view/MdX3Rr)- **Fonts:** [Cinzel](https://github.com/NDISCOVER/Cinzel) and [Rajdhani](https://github.com/itfoundry/rajdhani) — SIL Open Font License (see `src/main/resources/fonts/`)
