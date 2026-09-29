# Nocturne

A three-minute harbour fireworks show in six movements, rendered live in the browser with WebGL 2 and synthesised sound. There is also a free-play mode where you fire shells yourself.

Open `index.html` in a current Chrome, Edge, Firefox or Safari. It needs no build step or server. Any static host works too; for Netlify, drag the `fireworks` folder onto the dashboard.

## The show

| Movement | Starts | What happens |
| --- | --- | --- |
| I. Ignition | 0:00 | Salutes open the night. The five barges answer each other, then a rainbow ladder of chrysanthemums. |
| II. Bloom | 0:26 | Japanese round shells with pistils and colour changes, spaced so each one can open. |
| III. Pulse | 0:58 | Fans, candles and crossettes fired to 120 beats a minute, ending in a sixteenth-note chase. |
| IV. Garden | 1:26 | Pattern shells: rings, Saturns, hearts, a star, swimming fish, falling leaves. |
| V. Gold | 1:54 | A Niagara waterfall from the bridge under willows, horsetails, palms and a 300 mm kamuro crown. |
| VI. Finale | 2:26 | Barrages from every position, a wall of brocade crowns, and six closing salutes. |

The show is written as a firing script (`js/show.js`): 243 numbered cues, each with an effect time, a position (barges B1 to B5 or the bridge) and a device. Shell cues are scripted by the moment they break, and the lift time is subtracted so the mortar fires early. Select **Firing script** during the show to read the whole script and jump to any cue.

## Controls

| Key | Action |
| --- | --- |
| Space | Pause or resume the show |
| M | Sound on or off |
| S | Open or close the firing script |
| F | Fullscreen |
| 1 to 0 | Choose a shell in free play |
| A | Autopilot in free play |

In free play, tap or click the sky to fire a shell that breaks at that point. Hold to keep firing. Higher points get bigger shells, following the rule of thumb that a shell of D millimetres breaks about D metres across.

## How it works

- **Physics** (`js/sim.js`): stars feel gravity plus linear and quadratic air drag, so they leave the burst fast, stop short and hang, as real stars do. Particles live in flat typed arrays and die by swap-remove, so the live set is always packed for upload. Trails are real sparks that cool from yellow-white to dull red.
- **Shells**: peony, chrysanthemum, willow, kamuro, horsetail, palm, ring, orbit, Saturn, heart, star, crossette, strobe, crackle, senrin, salute, dahlia, fish, glitter, spider and falling leaves, plus fan cakes, mines, candles, gerbs and the bridge waterfall. Japanese-style shells burn out on the same beat.
- **Rendering** (`js/render.js`): stars are drawn as motion-blurred streaks into a half-float buffer. One composite pass paints the sky, clouds, the city panorama, the harbour mirror with wave-broken reflections, smoke lit by up to twelve bursts, and the crowd, whose silhouettes catch the colour of each burst. A six-level bloom and AgX tone mapping finish the frame.
- **Sound** (`js/audio.js`): every sound is synthesised at start-up: mortar thumps, bursts with a rolling tail, crackle, whistles, gerb hiss, an echo off the far shore, and applause. Sound travels from the barges, so each boom lands just under a second after its flash.
- **Scenery** (`js/scenery.js`): the skyline, suspension bridge and barges are painted into one panorama keyed by azimuth and elevation, so they line up exactly with the 3D fireworks.

Quality adapts to the device: render resolution drops if frames run slow, and particle emission thins out as the budget fills. With reduced motion turned on in the system settings, salute and strobe flashes are softened.
