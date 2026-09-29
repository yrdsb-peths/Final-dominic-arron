package com.leaf.game.core;

import com.leaf.game.entity.Enemy;
import com.leaf.game.world.Block;

/**
 * CommandHandler — processes developer debug commands typed in the in-game chat.
 *
 * <p>Commands start with {@code /} (e.g. {@code /skip}, {@code /god}, {@code /give}).
 * Open the chat bar with {@code T} and type any command.
 *
 * <p>Useful for grading:
 * <ul>
 *   <li>{@code /skip}      — bypass the tutorial and all wave-unlock practices</li>
 *   <li>{@code /god}       — toggle invincibility</li>
 *   <li>{@code /give all}  — fill the hotbar with every building block</li>
 * </ul>
 */
public class CommandHandler {

    private final Window win;

    public CommandHandler(Window window) {
        this.win = window;
    }

    /** Executes a debug developer/sandbox chat command. */
    public void execute(String commandLine) {
        // Strip the '/' and trim spaces
        String clean = commandLine.substring(1).trim();
        String[] parts = clean.split("\\s+");
        if (parts.length == 0) return;

        String cmd = parts[0].toLowerCase();
        switch (cmd) {
            case "help":
                win.chatHistory.add("[System] Commands:");
                win.chatHistory.add("  /showcase - DEMO MODE: all abilities, godmode, infinite mana, all items.");
                win.chatHistory.add("  /finale - jump straight to THE ENDING (portal trial at spawn).");
                win.chatHistory.add("  /showcase <combat|horde|volcanic|sakura|off> - Stage a demo scene.");
                win.chatHistory.add("  /skip - Skip onboarding AND all wave practices.");
                win.chatHistory.add("  /spawn <spider|tower|treant> - Spawn an entity.");
                win.chatHistory.add("  /spider [ride|laser] - Mount or target spiders.");
                win.chatHistory.add("  /give <block_name> [amt] - Give specific block.");
                win.chatHistory.add("  /give all [amt] - Fill hotbar with all building blocks.");
                win.chatHistory.add("  /god - Toggle invincibility.");
                win.chatHistory.add("  /explore - Toggle free-explore (ambient spawns + tower sites).");
                win.chatHistory.add("  /biome [name] - List biomes or teleport to one.");
                break;

            case "showcase": {
                String scene = parts.length >= 2 ? parts[1].toLowerCase() : "";

                if (scene.equals("off")) {
                    win.showcaseMode = false;
                    win.immunityTimer = 0f;
                    win.chatHistory.add("[SHOWCASE] Demo mode OFF.");
                    break;
                }

                if (scene.equals("combat") || scene.equals("horde")) {
                    int count = scene.equals("horde") ? 14 : 7;
                    float radius = 12f;
                    Enemy.Type[] mix = { Enemy.Type.ZOMBIE, Enemy.Type.THROWER,
                                         Enemy.Type.SLIME, Enemy.Type.GOLEM };
                    for (int i = 0; i < count; i++) {
                        double ang = (Math.PI * 2 * i) / count;
                        int bx = (int) Math.round(win.player.position.x + Math.cos(ang) * radius);
                        int bz = (int) Math.round(win.player.position.z + Math.sin(ang) * radius);
                        win.enemyManager.spawnAt(bx + 0.5f, surfaceYAt(bx, bz) + 0.5f,
                                                 bz + 0.5f, mix[i % mix.length]);
                    }
                    win.chatHistory.add("[SHOWCASE] Spawned " + count + " enemies around you - go wild.");
                    break;
                }

                // A biome name → warp there (+ stage a tower in volcanic).
                com.leaf.game.world.gen.biome.Biome bScene = parseBiomeName(scene);
                if (bScene != null) {
                    int dist = teleportToBiome(bScene);
                    if (dist < 0) {
                        win.chatHistory.add("[SHOWCASE] No " + scene + " biome within range.");
                        break;
                    }
                    win.chatHistory.add("[SHOWCASE] Warped to " + scene + " (" + dist + " blocks).");
                    if (bScene == com.leaf.game.world.gen.biome.Biome.VOLCANIC) {
                        int bx = (int) win.player.position.x + 9;
                        int bz = (int) win.player.position.z;
                        win.enemyManager.spawnAt(bx + 0.5f, surfaceYAt(bx, bz) + 0.5f,
                                                 bz + 0.5f, Enemy.Type.INFERNO_TOWER);
                        win.chatHistory.add("[SHOWCASE] Inferno Tower erected - it erupts lava slimes.");
                    }
                    break;
                }

                if (!scene.isEmpty() && !scene.equals("on") && !scene.equals("arm")) {
                    win.chatHistory.add("[SHOWCASE] Usage: /showcase [combat|horde|volcanic|sakura|<biome>|off]");
                    break;
                }

                // Default (no arg / "on" / "arm") → arm the whole demo loadout.
                win.player.progression.unlockAll();
                win.showcaseMode = true;
                win.immunityTimer = 99999f;

                // Hard-assign ability weapons to slots 1–5 so the teacher can just
                // press 1–5 and RMB without hunting through the backpack.
                win.hotbar[0] = Block.GATLING_GUN;
                win.hotbar[1] = Block.WPN_SNIPER;
                win.hotbar[2] = Block.WPN_ORBITAL;
                win.hotbar[3] = Block.WPN_TIMESTOP;
                win.hotbar[4] = Block.WPN_STONE_CANNON;
                win.hotbar[5] = Block.TORCH;
                win.hotbar[6] = Block.TELESCOPE;
                win.hotbar[7] = Block.GRAPPLING_HOOK;
                win.hotbar[8] = Block.GRASS;
                // Stock inventory with everything else
                for (Block b : Block.values()) {
                    if (b != Block.AIR) win.inventory.addBlockAmount(b, 999);
                }

                // Skip onboarding & practice so nothing interrupts the demo.
                if (win.tutorial != null) win.tutorial.skip();
                win.practiceQueue.clear();
                win.practiceAbility = null;
                win.practiceSteps = null;
                win.practiceStepDone = false;
                win.practiceCelebration = 0f;
                win.showUnlockCard = false;
                if (win.enemyManager != null) {
                    win.enemyManager.getEnemies().removeIf(e -> e.type == Enemy.Type.DUMMY);
                    // Waves OFF so nothing piles on mid-demo; you control fights via
                    // /showcase combat. Free-explore keeps the world feeling alive.
                    win.enemyManager.wavesEnabled = false;
                    win.enemyManager.freeExploreMode = true;
                }

                win.chatHistory.add("[SHOWCASE] ARMED: all abilities, godmode, infinite mana.");
                win.chatHistory.add("[SHOWCASE] Slots 1-5: Gatling | Sniper | Orbital | TimeStop | StoneCannon — RMB to fire");
                win.chatHistory.add("[SHOWCASE] Scenes: /showcase combat | horde | volcanic | sakura | off");
                break;
            }
            case "treant": {
                // Find the surface slightly ahead of the player to spawn the tree
                int bx = (int) Math.floor(win.player.position.x) + 6;
                int bz = (int) Math.floor(win.player.position.z) + 6;
                int sy = (int) Math.floor(win.player.position.y);
                for (int y = Math.min((int) win.player.position.y + 16, com.leaf.game.world.Chunk.HEIGHT - 2); y >= 2; y--) {
                    if (win.world.getBlock(bx, y, bz).isSolid()) {
                        sy = y + 1;
                        break;
                    }
                }
                win.enemyManager.spawnAt(bx + 0.5f, sy, bz + 0.5f, Enemy.Type.TREANT);
                win.chatHistory.add("[System]: Disguised Treant spawned! It looks like an ordinary Oak Tree. Attack or walk near it to provoke it!");
                break;
            }
            case "biome": {
                if (parts.length < 2) {
                    win.chatHistory.add("[Biomes] volcanic sakura mushroom crystal autumn");
                    win.chatHistory.add("[Biomes] forest plains desert savanna taiga tundra ocean");
                    win.chatHistory.add("Type /biome <name> to teleport to the nearest one.");
                    break;
                }
                com.leaf.game.world.gen.biome.Biome target = parseBiomeName(parts[1].toLowerCase());
                if (target == null) {
                    win.chatHistory.add("[System]: Unknown biome '" + parts[1] + "'. Type /biome for the list.");
                    break;
                }
                int px = (int) win.player.position.x;
                int pz = (int) win.player.position.z;
                int step = 160, range = 4000;
                int foundX = Integer.MIN_VALUE, foundZ = Integer.MIN_VALUE;
                float bestDistSq = Float.MAX_VALUE;
                for (int dx = -range; dx <= range; dx += step) {
                    for (int dz = -range; dz <= range; dz += step) {
                        if (win.worldGen.biomeAt(px + dx, pz + dz) == target) {
                            // Lowland biomes: skip matches buried under alpine peaks
                            // (the biome map says "forest" but the terrain is snow).
                            boolean lowland = target != com.leaf.game.world.gen.biome.Biome.ICY_PEAKS
                                    && target != com.leaf.game.world.gen.biome.Biome.TUNDRA
                                    && target != com.leaf.game.world.gen.biome.Biome.SNOWY_PLAINS;
                            if (lowland && win.worldGen.surfaceYEstimate(px + dx, pz + dz) > 262) continue;
                            float dSq = dx * dx + (float) dz * dz;
                            if (dSq < bestDistSq) {
                                bestDistSq = dSq;
                                foundX = px + dx;
                                foundZ = pz + dz;
                            }
                        }
                    }
                }
                if (foundX == Integer.MIN_VALUE) {
                    win.chatHistory.add("[System]: No " + parts[1] + " within " + range + " blocks - try exploring further out.");
                    break;
                }
                // Alpine-aware surface height so we never bury the player in a peak;
                // +4 clearance lets them settle onto the freshly generated ground.
                int surfaceY = (int) win.worldGen.surfaceYEstimate(foundX, foundZ) + 4;
                win.player.position.set(foundX + 0.5f, surfaceY, foundZ + 0.5f);
                int dist = (int) Math.sqrt(bestDistSq);
                win.chatHistory.add("[System]: Warped to " + parts[1] + " biome, " + dist + " blocks away.");
                System.out.println("[Biome] warp " + parts[1] + " -> " + foundX + " " + surfaceY + " " + foundZ);
                break;
            }

            // ── Capture helpers: frame the same shot twice (before/after a visual change) ──
            case "tp": {
                if (parts.length < 4) { win.chatHistory.add("Usage: /tp <x> <y> <z>"); break; }
                try {
                    win.player.position.set(Float.parseFloat(parts[1]),
                            Float.parseFloat(parts[2]), Float.parseFloat(parts[3]));
                    // "/tp x y z fly" hovers there (flight mode) instead of dropping.
                    if (parts.length > 4 && parts[4].equalsIgnoreCase("fly")) win.player.debugMode = true;
                    win.chatHistory.add("[System]: Teleported.");
                } catch (NumberFormatException ex) {
                    win.chatHistory.add("[System]: /tp needs numbers.");
                }
                break;
            }
            case "look": {
                if (parts.length < 3) { win.chatHistory.add("Usage: /look <yawDeg> <pitchDeg>"); break; }
                try {
                    win.camera.yaw   = (float) Math.toRadians(Float.parseFloat(parts[1]));
                    win.camera.pitch = (float) Math.toRadians(Float.parseFloat(parts[2]));
                    win.camera.clampPitch();
                } catch (NumberFormatException ex) {
                    win.chatHistory.add("[System]: /look needs numbers.");
                }
                break;
            }
            case "time": {
                if (parts.length < 2) {
                    win.chatHistory.add("Usage: /time <0-1 | dawn | noon | dusk | night>");
                    break;
                }
                float t;
                switch (parts[1].toLowerCase()) {
                    case "dawn"  -> t = 0.26f;
                    case "noon"  -> t = 0.50f;
                    case "dusk"  -> t = 0.735f;
                    case "night" -> t = 0.95f;
                    default -> {
                        try { t = Float.parseFloat(parts[1]); }
                        catch (NumberFormatException ex) { t = -1f; }
                    }
                }
                if (t < 0f) { win.chatHistory.add("[System]: Unknown time."); break; }
                win.dayNight.time = t - (float) Math.floor(t);
                win.dayNight.recompute();
                break;
            }
            case "hud":
                win.hudHidden = !win.hudHidden;
                win.chatHistory.clear();
                break;
            case "arena": {
                // A flat grass stage floating over the current spot, with a ring of
                // enemies — a consistent, readable set for demos and VFX capture.
                int cx = (int) Math.floor(win.player.position.x);
                int cz = (int) Math.floor(win.player.position.z);
                int top = 0;
                for (int dx = -2; dx <= 2; dx++)
                    for (int dz = -2; dz <= 2; dz++)
                        top = Math.max(top, surfaceYAt(cx + dx, cz + dz));
                int y = Math.min(top + 6, 250);
                int r = 22;
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (dx * dx + dz * dz > r * r) continue;
                        win.world.setBlock(cx + dx, y - 1, cz + dz, Block.STONE);
                        win.world.setBlock(cx + dx, y, cz + dz, Block.GRASS);
                        for (int h = 1; h <= 12; h++) win.world.setBlock(cx + dx, y + h, cz + dz, Block.AIR);
                    }
                }
                win.player.position.set(cx + 0.5f, y + 1.2f, cz + 0.5f);
                win.player.debugMode = false;
                int count = parts.length > 1 ? Integer.parseInt(parts[1]) : 5;
                Enemy.Type[] mix = { Enemy.Type.ZOMBIE, Enemy.Type.THROWER, Enemy.Type.SLIME,
                                     Enemy.Type.GOLEM, Enemy.Type.SPIDER };
                for (int i = 0; i < count; i++) {
                    double a = -Math.PI / 2 + (i - (count - 1) / 2.0) * 0.32;   // a fan in front (+X is yaw 0)
                    float ex = cx + 0.5f + (float) Math.cos(a + Math.PI / 2) * 16f;
                    float ez = cz + 0.5f + (float) Math.sin(a + Math.PI / 2) * 16f;
                    boolean dummies = parts.length > 2 && parts[2].startsWith("d");   // "/arena 5 dummy"
                    win.enemyManager.spawnAt(ex, y + 1.2f, ez, dummies ? Enemy.Type.DUMMY : mix[i % mix.length]);
                }
                win.camera.yaw = 0f;
                win.camera.pitch = (float) Math.toRadians(-6);
                win.chatHistory.add("[System]: Arena ready.");
                break;
            }
            case "fxslow":
                GameConfig.fxTimeScale = parts.length > 1 ? Float.parseFloat(parts[1])
                        : (GameConfig.fxTimeScale < 1f ? 1f : 0.15f);
                win.chatHistory.add("[System]: VFX time scale " + GameConfig.fxTimeScale);
                break;
            case "peace":
                // Calm world for scenery shots: no waves, no roamers, clear the field.
                win.enemyManager.wavesEnabled = false;
                win.enemyManager.freeExploreMode = false;
                for (Enemy e : win.enemyManager.getEnemies()) e.alive = false;
                win.chatHistory.add("[System]: Peace — waves and roamers off.");
                break;

            case "explore":
                win.enemyManager.freeExploreMode = !win.enemyManager.freeExploreMode;
                win.chatHistory.add(win.enemyManager.freeExploreMode
                        ? "[System]: Free-explore ENABLED - the world is alive: ambient spawns + Inferno-Tower sites."
                        : "[System]: Free-explore DISABLED.");
                break;

            case "spawn": {
                if (parts.length < 2) {
                    win.chatHistory.add("[System]: Usage: /spawn <tower|treant|spider>");
                    break;
                }
                String entity = parts[1].toLowerCase();

                int bx = (int) Math.floor(win.player.position.x) + 6;
                int bz = (int) Math.floor(win.player.position.z) + 6;
                int sy = -1;
                for (int y = Math.min((int) win.player.position.y + 24, com.leaf.game.world.Chunk.HEIGHT - 2); y >= 2; y--) {
                    if (win.world.getBlock(bx, y, bz).isSolid()) { sy = y + 1; break; }
                }
                if (sy < 0) sy = (int) win.player.position.y; // fallback

                if (entity.equals("tower")) {
                    win.enemyManager.spawnAt(bx + 0.5f, sy, bz + 0.5f, Enemy.Type.INFERNO_TOWER);
                    win.chatHistory.add("[System]: Inferno Tower erected!");
                } else if (entity.equals("treant")) {
                    win.enemyManager.spawnAt(bx + 0.5f, sy, bz + 0.5f, Enemy.Type.TREANT);
                    win.chatHistory.add("[System]: Disguised Treant spawned! It looks like an ordinary tree until disturbed.");
                } else if (entity.equals("spider")) {
                    int legs = 6;
                    float scale = 1.0f;
                    if (parts.length >= 3) {
                        try { legs = Integer.parseInt(parts[2]); } catch (Exception ignored) {}
                    }
                    if (parts.length >= 4) {
                        try { scale = Float.parseFloat(parts[3]); } catch (Exception ignored) {}
                    }
                    Enemy e = win.enemyManager.spawnAt(bx + 0.5f, sy, bz + 0.5f, Enemy.Type.SPIDER);
                    if (e instanceof com.leaf.game.entity.spider.SpiderEnemy) {
                        com.leaf.game.entity.spider.SpiderEnemy se = (com.leaf.game.entity.spider.SpiderEnemy) e;
                        se.customLegCount = legs;
                        se.customScale = scale;
                        se.mode = com.leaf.game.entity.spider.SpiderEnemy.BehaviorMode.IDLE;
                    }
                    win.chatHistory.add("[System]: Spawned a friendly spider.");
                } else {
                    win.chatHistory.add("[System]: Unknown entity. Use /spawn <tower|treant|spider>");
                }
                break;
            }

            case "skip":
            case "skiptutorial":
                // 1. Skip core onboarding tutorial
                if (win.tutorial != null) {
                    win.tutorial.skip();
                }

                // 2. Clear out between-wave practice queues and cards
                win.practiceQueue.clear();
                win.practiceAbility = null;
                win.practiceSteps = null;
                win.practiceStepDone = false;
                win.practiceCelebration = 0f;
                win.showUnlockCard = false;

                // 3. Clean up active practice dummies and boot up the spawner
                if (win.enemyManager != null) {
                    win.enemyManager.getEnemies().removeIf(e -> e.type == Enemy.Type.DUMMY);
                    win.enemyManager.wavesEnabled = true;
                    win.enemyManager.beginNextWave(); // Force start next wave immediately
                }

                win.chatHistory.add("[System]: Entire onboarding & practice sequence bypassed! Endless waves are now active.");
                break;

            case "finale": {
                // DEV/DEMO: jump straight to the endgame. Unlock everything and
                // fire the summons as if the voyage had just been completed.
                win.player.progression.unlockAll();
                if (win.tutorial != null) win.tutorial.skip();
                win.practiceQueue.clear();
                win.practiceAbility = null;
                win.practiceSteps = null;
                win.showUnlockCard = false;
                if (win.voyage == null) win.voyage = new Voyage(win);
                win.voyage.complete = true;
                win.voyage.active   = false;
                win.voyageStarted   = true;
                win.chatHistory.add("[FINALE] All powers granted. The crystal calls you home...");
                break;
            }

            case "god":
                if (win.player != null) {
                    if (win.immunityTimer > 0f) {
                        win.immunityTimer = 0f;
                        win.chatHistory.add("[System]: Godmode DISABLED.");
                    } else {
                        win.immunityTimer = 99999f;
                        win.chatHistory.add("[System]: Godmode ENABLED.");
                    }
                }
                break;

            case "give":
                if (parts.length < 2) {
                    win.chatHistory.add("[System]: Usage: /give <block_name_or_all> [amount]");
                    break;
                }

                String target = parts[1].toLowerCase();
                int amount = 256;
                if (parts.length >= 3) {
                    try {
                        amount = Integer.parseInt(parts[2]);
                    } catch (NumberFormatException e) {
                        win.chatHistory.add("[System]: Invalid amount. Defaulting to 256.");
                    }
                }

                if (target.equals("all")) {
                    for (Block b : Block.values()) {
                        if (b != Block.AIR && b != Block.GATLING_GUN && b != Block.TELESCOPE
                                && b != Block.TORCH && b != Block.GRAPPLING_HOOK) {
                            win.inventory.addBlockAmount(b, amount);
                            win.addBlockToHotbar(b);
                        }
                    }
                    win.chatHistory.add("[System]: Granted " + amount + " of all building blocks!");
                } else {
                    Block foundBlock = null;
                    String sanitizedTarget = target.replace("_", "").replace(" ", "");
                    for (Block b : Block.values()) {
                        String bName = b.name().toLowerCase().replace("_", "");
                        if (bName.equals(sanitizedTarget)) {
                            foundBlock = b;
                            break;
                        }
                    }

                    if (foundBlock != null) {
                        win.inventory.addBlockAmount(foundBlock, amount);
                        win.addBlockToHotbar(foundBlock);
                        win.chatHistory.add("[System]: Granted " + amount + "x " + foundBlock.name());
                    } else {
                        win.chatHistory.add("[System]: Block '" + parts[1] + "' not found.");
                    }
                }
                break;
            case "spider":
                if (parts.length < 2) {
                    win.chatHistory.add("[System]: Usage: /spider [spawn|laser|ride]");
                    win.chatHistory.add("          /spider spawn [legs: 4|6|8] [scale: float]");
                    break;
                }
                if (parts[1].equals("spawn")) {
                    int legs = 6;      // Default to 6 legs
                    float scale = 1.0f; // Default to 1.0 scale

                    if (parts.length >= 3) {
                        try {
                            legs = Integer.parseInt(parts[2]);
                            if (legs != 4 && legs != 6 && legs != 8) {
                                win.chatHistory.add("[System]: Leg options are only 4, 6, or 8. Defaulting to 6.");
                                legs = 6;
                            }
                        } catch (NumberFormatException e) {
                            win.chatHistory.add("[System]: Invalid legs format. Defaulting to 6.");
                        }
                    }
                    if (parts.length >= 4) {
                        try {
                            scale = Float.parseFloat(parts[3]);
                            scale = Math.max(0.3f, Math.min(4.0f, scale)); // Safe limits (0.3x to 4.0x)
                        } catch (NumberFormatException e) {
                            win.chatHistory.add("[System]: Invalid scale format. Defaulting to 1.0.");
                        }
                    }

                    Enemy e = win.enemyManager.spawnAt(win.player.position.x + 3, win.player.position.y + 1, win.player.position.z, Enemy.Type.SPIDER);
                    if (e instanceof com.leaf.game.entity.spider.SpiderEnemy) {
                        com.leaf.game.entity.spider.SpiderEnemy se = (com.leaf.game.entity.spider.SpiderEnemy) e;
                        se.customLegCount = legs;
                        se.customScale = scale;
                        se.mode = com.leaf.game.entity.spider.SpiderEnemy.BehaviorMode.IDLE;
                        win.chatHistory.add("[System]: Spawned a friendly spider (Legs: " + legs + ", Scale: " + scale + "x).");
                    }
                } else if (parts[1].equals("laser")) {
                    win.spiderLaserActive = !win.spiderLaserActive;
                    for (Enemy e : win.enemyManager.getEnemies()) {
                        if (e instanceof com.leaf.game.entity.spider.SpiderEnemy) {
                            com.leaf.game.entity.spider.SpiderEnemy se = (com.leaf.game.entity.spider.SpiderEnemy) e;
                            if (se.mode != com.leaf.game.entity.spider.SpiderEnemy.BehaviorMode.HOSTILE) {
                                se.mode = win.spiderLaserActive ?
                                        com.leaf.game.entity.spider.SpiderEnemy.BehaviorMode.FOLLOW_TARGET :
                                        com.leaf.game.entity.spider.SpiderEnemy.BehaviorMode.IDLE;
                            }
                        }
                    }
                    win.chatHistory.add("[System]: Spider laser pointer " + (win.spiderLaserActive ? "ON" : "OFF"));
                } else if (parts[1].equals("ride")) {
                    if (win.riddenSpider != null) {
                        win.riddenSpider.mode = com.leaf.game.entity.spider.SpiderEnemy.BehaviorMode.IDLE;
                        win.riddenSpider = null;
                        win.chatHistory.add("[System]: Dismounted spider.");
                    } else {
                        com.leaf.game.entity.spider.SpiderEnemy best = null;
                        float bestDist = 10f;
                        for (Enemy e : win.enemyManager.getEnemies()) {
                            if (e instanceof com.leaf.game.entity.spider.SpiderEnemy && e.alive) {
                                com.leaf.game.entity.spider.SpiderEnemy se = (com.leaf.game.entity.spider.SpiderEnemy) e;
                                if (se.mode != com.leaf.game.entity.spider.SpiderEnemy.BehaviorMode.HOSTILE) {
                                    float d = se.position.distance(win.player.position);
                                    if (d < bestDist) {
                                        bestDist = d;
                                        best = se;
                                    }
                                }
                            }
                        }
                        if (best != null) {
                            win.riddenSpider = best;
                            best.mode = com.leaf.game.entity.spider.SpiderEnemy.BehaviorMode.RIDDEN;
                            win.spiderLaserActive = false;
                            win.chatHistory.add("[System]: Mounted spider! Use WASD to steer.");
                        } else {
                            win.chatHistory.add("[System]: No friendly spider nearby to mount.");
                        }
                    }
                }
                break;

            default:
                win.chatHistory.add("[System]: Unknown command. Type '/help' for options.");
                break;
        }
    }

    /** Highest solid surface +1 at (bx,bz), scanning down from just above the player. */
    private int surfaceYAt(int bx, int bz) {
        for (int y = Math.min((int) win.player.position.y + 24, com.leaf.game.world.Chunk.HEIGHT - 2);
             y >= 2; y--) {
            if (win.world.getBlock(bx, y, bz).isSolid()) return y + 1;
        }
        return (int) win.player.position.y;
    }

    /** Warp the player to the nearest patch of {@code target}. Returns the distance in
     *  blocks, or -1 if none was found within range. Mirrors the {@code /biome} search. */
    private int teleportToBiome(com.leaf.game.world.gen.biome.Biome target) {
        int px = (int) win.player.position.x;
        int pz = (int) win.player.position.z;
        int step = 160, range = 4000;
        int foundX = Integer.MIN_VALUE, foundZ = Integer.MIN_VALUE;
        float bestDistSq = Float.MAX_VALUE;
        for (int dx = -range; dx <= range; dx += step) {
            for (int dz = -range; dz <= range; dz += step) {
                if (win.worldGen.biomeAt(px + dx, pz + dz) == target) {
                    float dSq = dx * dx + (float) dz * dz;
                    if (dSq < bestDistSq) { bestDistSq = dSq; foundX = px + dx; foundZ = pz + dz; }
                }
            }
        }
        if (foundX == Integer.MIN_VALUE) return -1;
        int surfaceY = (int) win.worldGen.surfaceYEstimate(foundX, foundZ) + 4;
        win.player.position.set(foundX + 0.5f, surfaceY, foundZ + 0.5f);
        return (int) Math.sqrt(bestDistSq);
    }

    private static com.leaf.game.world.gen.biome.Biome parseBiomeName(String name) {
        return switch (name) {
            case "volcanic", "volcano", "lava", "ashlands"    -> com.leaf.game.world.gen.biome.Biome.VOLCANIC;
            case "sakura", "cherry", "blossom", "pink"        -> com.leaf.game.world.gen.biome.Biome.SAKURA;
            case "mushroom", "shroom", "mycelium", "glow"     -> com.leaf.game.world.gen.biome.Biome.MUSHROOM;
            case "crystal", "amethyst", "geode", "crystals"   -> com.leaf.game.world.gen.biome.Biome.CRYSTAL_FIELDS;
            case "autumn", "maple", "fall", "orange"          -> com.leaf.game.world.gen.biome.Biome.AUTUMN;
            case "forest", "oak", "trees"                     -> com.leaf.game.world.gen.biome.Biome.FOREST;
            case "plains", "grass", "flat"                    -> com.leaf.game.world.gen.biome.Biome.PLAINS;
            case "desert", "sand", "dunes"                    -> com.leaf.game.world.gen.biome.Biome.DESERT;
            case "savanna", "savannah"                        -> com.leaf.game.world.gen.biome.Biome.SAVANNA;
            case "taiga", "spruce", "conifer"                 -> com.leaf.game.world.gen.biome.Biome.TAIGA;
            case "snowy", "snow", "snowplains"                -> com.leaf.game.world.gen.biome.Biome.SNOWY_PLAINS;
            case "tundra", "frozen", "icy"                    -> com.leaf.game.world.gen.biome.Biome.TUNDRA;
            case "icypeaks", "icy_peaks", "peaks", "mountain" -> com.leaf.game.world.gen.biome.Biome.ICY_PEAKS;
            case "ocean", "sea", "water"                      -> com.leaf.game.world.gen.biome.Biome.OCEAN;
            case "beach", "shore"                             -> com.leaf.game.world.gen.biome.Biome.BEACH;
            case "river"                                      -> com.leaf.game.world.gen.biome.Biome.RIVER;
            default -> null;
        };
    }
}