package com.leaf.game.core;

import com.leaf.game.entity.Enemy;
import com.leaf.game.entity.Enemy.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Per-ability hands-on tutorial sequences.
 *
 * HOW TO ADD / EDIT CONTENT:
 *   Find the case block for your ability in forAbility() below.
 *   Each Step has:
 *     title       - shown in the header
 *     instruction - multi-line (use \n), shown on the card
 *     keyHint     - key chip shown in the header
 *     required    - how many times the done() predicate must fire before advancing
 *     done        - called every frame; return true to count one successful action
 *     onEnter     - called once when the step starts (spawn dummies, damage player, etc.)
 *     doneText    - shown briefly in green when required count is reached
 *     allowSkip   - if false, ENTER cannot skip this step (forced completion)
 *     timeout     - auto-advance after this many seconds if stuck (safety valve)
 *     showManaArrow / showCooldownArrow - draw HUD annotations for first abilities
 */
public class AbilityPractice {

    // ─────────────────────────────────────────────────────────────────────────
    //  Context
    // ─────────────────────────────────────────────────────────────────────────

    public static class StepCtx {
        public Window  win;
        public float   stepAge;
        public boolean flag;        // generic one-shot latch
        public float   snapshot;    // record a value at step-start
        public int     counter;     // how many times done() has fired this step
        public int     required;    // target count

        /** First alive practice enemy in the world (slime/dummy/etc.), or null. */
        public Enemy dummy() {
            if (win == null) return null;
            return win.enemyManager.getEnemies().stream()
                    .filter(e -> e.alive)
                    .findFirst().orElse(null);
        }

        /** Nearest alive enemy to the player, or null. */
        public Enemy nearestEnemy() {
            if (win == null) return null;
            Enemy best = null; float bestD = Float.MAX_VALUE;
            for (Enemy e : win.enemyManager.getEnemies()) {
                if (!e.alive) continue;
                float d = win.player.position.distance(e.position);
                if (d < bestD) { bestD = d; best = e; }
            }
            return best;
        }

        /** Count of alive enemies of any type. */
        public long aliveCount() {
            return win.enemyManager.getEnemies().stream().filter(e -> e.alive).count();
        }

        /**
         * True the frame any practice enemy takes damage OR dies.
         * Tracks the summed HP of all alive enemies: a hit lowers it, and a kill
         * removes that enemy from the alive set (its HP leaves the sum) — so a single
         * drop test catches both. This never soft-locks: it doesn't require a
         * specific target to survive, so killable slimes work fine.
         */
        public boolean anyEnemyDamaged() {
            float total = 0f;
            for (Enemy e : win.enemyManager.getEnemies()) if (e.alive) total += e.health;
            if (!flag) { snapshot = total; flag = true; return false; }
            boolean dropped = total < snapshot - 0.5f;
            snapshot = total;
            return dropped;
        }

        /** True the frame the total kill count increases (one enemy died). */
        public boolean killedOne() {
            int k = win.enemyManager.totalKills;
            if (!flag) { snapshot = k; flag = true; return false; }
            if (k > (int) snapshot) { snapshot = k; return true; }
            return false;
        }

        /** Back-compat alias — now means "any practice enemy took damage/died". */
        public boolean dummyHpDropped() { return anyEnemyDamaged(); }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Step definition
    // ─────────────────────────────────────────────────────────────────────────

    public static class Step {
        public final String             title;
        public final String             instruction;
        public final String             keyHint;
        public final int                required;        // times done() must fire
        public final Predicate<StepCtx> done;            // one successful action
        public final Consumer<StepCtx>  onEnter;
        public final Consumer<StepCtx>  onTick;          // optional per-frame logic
        public final String             doneText;
        public final boolean            allowSkip;       // can player press ENTER to skip?
        public final float              timeout;
        public final boolean            showManaArrow;
        public final boolean            showCooldownArrow;

        Step(Builder b) {
            title = b.title; instruction = b.instruction; keyHint = b.keyHint;
            required = b.required; done = b.done; onEnter = b.onEnter; onTick = b.onTick;
            doneText = b.doneText; allowSkip = b.allowSkip; timeout = b.timeout;
            showManaArrow = b.showManaArrow; showCooldownArrow = b.showCooldownArrow;
        }

        public static Builder of(String title) { return new Builder(title); }
    }

    public static class Builder {
        String title = "", instruction = "", keyHint = null, doneText = null;
        int required = 1;
        Predicate<StepCtx> done = ctx -> false;
        Consumer<StepCtx>  onEnter = null, onTick = null;
        boolean allowSkip = false, showManaArrow = false, showCooldownArrow = false;
        float timeout = 90f;

        Builder(String t) { title = t; }
        public Builder instr(String s)  { instruction = s; return this; }
        public Builder key(String k)    { keyHint = k; return this; }
        public Builder need(int n)      { required = n; return this; }
        public Builder when(Predicate<StepCtx> p) { done = p; return this; }
        public Builder setup(Consumer<StepCtx> c) { onEnter = c; return this; }
        public Builder tick(Consumer<StepCtx> c)  { onTick  = c; return this; }
        public Builder win(String t)    { doneText = t; return this; }
        public Builder skip()           { allowSkip = true; return this; }
        public Builder noSkip()         { allowSkip = false; return this; }
        public Builder manaArrow()      { showManaArrow = true; return this; }
        public Builder cooldownArrow()  { showCooldownArrow = true; return this; }
        public Builder tout(float s)    { timeout = s; return this; }
        public Step build()             { return new Step(this); }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Spawn helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Remove ALL practice enemies (waves are off during practice, so all are ours). */
    public static void clearDummies(StepCtx ctx) {
        ctx.win.enemyManager.getEnemies().clear();
    }

    /**
     * Place an enemy on solid, open-sky ground at the given (px, pz).
     * Reuses EnemyManager.surfaceAt — the exact same top-down + open-sky scan the
     * real wave spawner uses — so practice enemies NEVER appear underground or
     * inside a cave. If no open surface is found at that column, nudges toward the
     * player and retries; failing that, drops onto the player's own ground level.
     */
    private static void spawnOnGround(StepCtx ctx, float px, float pz, Type type) {
        org.joml.Vector3f sp = ctx.win.enemyManager.surfaceAt(ctx.win.world, px, pz);
        if (sp == null) {
            // Retry at the midpoint toward the player (their column is known-walkable).
            float mx = (px + ctx.win.player.position.x) * 0.5f;
            float mz = (pz + ctx.win.player.position.z) * 0.5f;
            sp = ctx.win.enemyManager.surfaceAt(ctx.win.world, mx, mz);
        }
        if (sp == null) {
            // Fall back to the player's OWN column surface, then nudge the slime out
            // to the requested side so it isn't on top of the player. The player is
            // standing on valid ground, so this Y is never underground.
            org.joml.Vector3f pc = ctx.win.enemyManager.surfaceAt(
                    ctx.win.world, ctx.win.player.position.x, ctx.win.player.position.z);
            float y = (pc != null) ? pc.y : ctx.win.player.position.y;
            sp = new org.joml.Vector3f(px, y, pz);
        }
        ctx.win.enemyManager.spawnAt(sp.x, sp.y, sp.z, type);
    }

    /** Spawn N slimes in a fan in front of the player, always on visible ground. */
    public static void spawnSlimes(StepCtx ctx, int count) {
        clearDummies(ctx);
        float yaw = ctx.win.lastCameraYaw;
        for (int i = 0; i < count; i++) {
            float angle = yaw + (float)(i - count / 2) * 0.40f;
            float dist  = 6f + (i % 2) * 2f;     // alternate near/far so they don't stack
            float px = ctx.win.player.position.x + (float)Math.sin(angle) * dist;
            float pz = ctx.win.player.position.z + (float)Math.cos(angle) * dist;
            spawnOnGround(ctx, px, pz, Type.SLIME);
        }
    }

    /** Back-compat: tutorials now use slimes for everything. */
    public static void spawnDummies(StepCtx ctx, int count) { spawnSlimes(ctx, count); }
    public static void spawnZombies(StepCtx ctx, int count) { spawnSlimes(ctx, count); }

    /**
     * True when any alive enemy is standing on a MUD block.
     * Quagmire doesn't set mudTrapTimer — it actually paints MUD on the ground.
     * Enemies standing on MUD are "trapped" (massively slowed).
     */
    public static boolean anyEnemyOnMud(StepCtx ctx) {
        return ctx.win.enemyManager.getEnemies().stream().anyMatch(e -> {
            if (!e.alive) return false;
            int bx = (int) Math.floor(e.position.x);
            int bz = (int) Math.floor(e.position.z);
            // Check the block the enemy is standing on (footY - 1)
            int footY = (int) Math.floor(e.position.y);
            com.leaf.game.world.Block b = ctx.win.world.getBlock(bx, footY - 1, bz);
            return b == com.leaf.game.world.Block.MUD;
        });
    }

    /** Damage the player to 30% HP so they need to heal. */
    public static void damagePlayer(StepCtx ctx) {
        ctx.win.player.health = ctx.win.player.maxHealth * 0.30f;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Per-ability definitions
    // ─────────────────────────────────────────────────────────────────────────

    public static List<Step> forAbility(Progression.Ability a) {
        List<Step> s = new ArrayList<>();
        switch (a) {

        // ── WAVE 0: SNIPE ─────────────────────────────────────────────────────
        case SNIPE -> {
            s.add(Step.of("SNIPE  -  HOLD [ C ]  to charge, release to fire")
                .instr("HOLD  [ C ]  — keep it pressed. Do NOT tap and release.\n" +
                       "A crystal bolt charges while you hold. Release to fire.\n" +
                       "Longer hold = bigger blast.\n\n" +
                       "MANA bar (blue) drains while charging.\n" +
                       "Fire  3  charged shots.")
                .key("HOLD C → release")
                .need(3)
                .manaArrow().cooldownArrow()
                .setup(ctx -> spawnSlimes(ctx, 3))
                .when(ctx -> {
                    // getChargeFrac() is >0 only WHILE holding C, and snaps to 0 the
                    // instant the shot fires. So: track the peak charge during the hold
                    // (snapshot), and when it returns to 0 (released), decide.
                    //   flag    = "was charging last frame"
                    //   snapshot = peak charge reached this hold
                    float cf = ctx.win.player.attacks.getChargeFrac();
                    if (cf > 0.001f) {                       // charging
                        if (cf > ctx.snapshot) ctx.snapshot = cf;
                        ctx.flag = true;
                        return false;
                    }
                    if (ctx.flag) {                          // just released
                        ctx.flag = false;
                        float peak = ctx.snapshot;
                        ctx.snapshot = 0f;
                        if (peak >= 0.20f) return true;      // a real charged shot
                        // Released almost immediately — that's a click, not a hold.
                        ctx.win.practiceWarnText  = "HOLD  [ C ]  longer before releasing!";
                        ctx.win.practiceWarnTimer = 2.5f;
                    }
                    return false;
                })
                .win("Good shot!")
                .tout(120f).build());
        }

        // ── WAVE 1: SLASH + DASH ──────────────────────────────────────────────
        case SLASH -> {
            s.add(Step.of("SLASH")
                .instr("Press  [ F ]  to swing a wide arc  -  hits everything in front of you.\n" +
                       "Walk close to the slimes and slash them.\n" +
                       "Slash  3 times.")
                .key("F")
                .need(3)
                .cooldownArrow()
                .setup(ctx -> spawnSlimes(ctx, 4))
                .when(ctx -> {
                    // Detect the swing itself (melee cooldown resets on each swing) so
                    // this counts even if the slimes are already dead — no soft-lock.
                    float frac = ctx.win.player.attacks.getMeleeCooldownFrac();
                    if (!ctx.flag) { ctx.snapshot = frac; ctx.flag = true; return false; }
                    boolean swung = frac < ctx.snapshot - 0.3f && ctx.snapshot > 0.8f;
                    ctx.snapshot = frac;
                    return swung;
                })
                .win("Slash!")
                .tout(90f).build());
        }
        case DASH -> {
            s.add(Step.of("DASH")
                .instr("Press  [ Q ]  to burst instantly in your move direction.\n" +
                       "Try it once.")
                .key("Q")
                .need(1)
                .cooldownArrow()
                .when(ctx -> ctx.win.player.abilities.isDashing)
                .win("Dashed!")
                .tout(60f).build());
        }

        // ── WAVE 2: QUAGMIRE ─────────────────────────────────────────────────
        case QUAGMIRE -> {
            s.add(Step.of("QUAGMIRE  -  enemies approaching!")
                .instr("Enemies are closing in!\n" +
                       "Press  [ M ]  to fire a mud wave along the ground.\n" +
                       "Aim at the enemies — the wave travels forward and traps them in mud.")
                .key("M")
                .need(1)
                .setup(ctx -> spawnSlimes(ctx, 3))
                .when(ctx -> anyEnemyOnMud(ctx))
                .win("Trapped in mud!")
                .tout(90f).build());
            s.add(Step.of("QUAGMIRE  -  finish one off!")
                .instr("They are stuck in the mud  -  barely able to move!\n" +
                       "Walk up and kill one while it's trapped.\n" +
                       "Use  [ F ]  to slash or  [ C ]  to snipe.")
                .key("F / C")
                .need(1)
                .when(ctx -> ctx.killedOne())   // one kill is enough — never blocks on a stray
                .win("Got it!")
                .tout(90f).build());
        }

        // ── WAVE 3: LIGHTNING ────────────────────────────────────────────────
        case LIGHTNING -> {
            s.add(Step.of("LIGHTNING  -  HOLD [ U ] to charge, release to strike")
                .instr("HOLD  [ U ]  — keep it held to charge a storm.\n" +
                       "Release to call a lightning strike on the aimed slime.\n" +
                       "Do NOT just click  -  hold until you hear the thunder build up.\n" +
                       "Strike  2  times.")
                .key("HOLD U → release")
                .need(2)
                .cooldownArrow()
                .setup(ctx -> spawnSlimes(ctx, 3))
                .when(ctx -> {
                    // Detect the strike via cooldown reset — enemy-independent, so it
                    // counts even if every slime is already dead (no soft-lock).
                    float frac = ctx.win.player.lightning.getCooldownFrac();
                    if (!ctx.flag) { ctx.snapshot = frac; ctx.flag = true; return false; }
                    boolean fired = frac < ctx.snapshot - 0.3f && ctx.snapshot > 0.8f;
                    ctx.snapshot = frac;
                    return fired;
                })
                .win("Strike!")
                .tout(90f).build());
            s.add(Step.of("LIGHTNING  -  area burst")
                .instr("Double-tap  [ U ]  to fire an area burst.\n" +
                       "It hits every slime nearby at once.\n" +
                       "Do it once  -  the slimes are grouped together.")
                .key("U  U")
                .need(1)
                .setup(ctx -> spawnSlimes(ctx, 5))
                .when(ctx -> {
                    // A big one-frame HP drop across the group = the burst hit several
                    // at once (a single-target strike can't drop this much). Timeout is
                    // the safety net so this can never hard-block.
                    float total = 0f;
                    for (Enemy e : ctx.win.enemyManager.getEnemies()) if (e.alive) total += e.health;
                    if (!ctx.flag) { ctx.snapshot = total; ctx.flag = true; return false; }
                    boolean burst = total < ctx.snapshot - 70f;
                    ctx.snapshot = total;
                    return burst;
                })
                .win("Area burst!")
                .tout(60f).build());
        }
        case HEAL -> {
            s.add(Step.of("HEAL  -  HOLD to channel")
                .instr("You are hurt!\n" +
                       "HOLD  [ L ]  to channel healing  -  do NOT just click, keep it held.\n" +
                       "The MANA bar (blue) drains while you heal.\n" +
                       "You cannot move while channeling.")
                .key("HOLD L")
                .need(1)
                .manaArrow()
                .setup(ctx -> damagePlayer(ctx))
                .when(ctx -> ctx.win.player.abilities.isHealing)
                .win("Healing!")
                .tout(45f).build());
        }

        // ── WAVE 4: GRAB ─────────────────────────────────────────────────────
        case GRAB -> {
            s.add(Step.of("GRAB  -  get close")
                .instr("Grab-and-Slam needs close range.\n" +
                       "Walk within 3 blocks of a slime.")
                .key("Walk")
                .need(1)
                .setup(ctx -> spawnSlimes(ctx, 3))
                .when(ctx -> {
                    Enemy d = ctx.nearestEnemy();
                    return d != null && ctx.win.player.position.distance(d.position) < 3.5f;
                })
                .win("In range!")
                .tout(40f).build());
            s.add(Step.of("GRAB  -  HOLD to slam")
                .instr("HOLD  [ O ]  — keep it held to grab a slime,\n" +
                       "hoist it up, and slam it into the ground.\n" +
                       "Do this  2 times.")
                .key("HOLD O")
                .need(2)
                .setup(ctx -> { ctx.flag = false; spawnSlimes(ctx, 4); })
                .when(ctx -> ctx.anyEnemyDamaged())   // each slam hits a slime
                .win("Slammed!")
                .tout(60f).build());
        }

        // ── WAVE 5: BLINK + SWAP ─────────────────────────────────────────────
        case BLINK -> {
            s.add(Step.of("BLINK")
                .instr("Look at any surface at least 4 blocks away.\n" +
                       "Press  [ E ]  to teleport there instantly.")
                .key("E")
                .need(1)
                .when(ctx -> {
                    float frac = ctx.win.player.abilities.getBlinkCooldownFrac();
                    if (!ctx.flag) { ctx.snapshot = frac; ctx.flag = true; return false; }
                    boolean fired = frac < ctx.snapshot - 0.1f && ctx.snapshot > 0.9f;
                    ctx.snapshot = frac; return fired;
                })
                .win("Blinked!")
                .tout(60f).build());
        }
        case SWAP -> {
            s.add(Step.of("POSITION SWAP")
                .instr("Press  [ J ]  to instantly swap places with the nearest enemy.\n" +
                       "You teleport to where it was  -  it appears where you were.")
                .key("J")
                .need(1)
                .setup(ctx -> spawnDummies(ctx, 1))
                .when(ctx -> ctx.win.todoSwapCooldown > GameConfig.todoCooldown * 0.8f)
                .win("Swapped!")
                .tout(45f).build());
        }

        // ── WAVE 6: PILLAR + CANNONBALL ──────────────────────────────────────
        case PILLAR -> {
            s.add(Step.of("STONE PILLAR  -  launch yourself skyward")
                .instr("Stand on stone or dirt and press  [ K ].\n" +
                       "A spire erupts beneath you and launches you into the air.\n\n" +
                       "If  [ K ]  doesn't work: the ground under you is not solid enough.\n" +
                       "Move to a stone or dirt surface, then try again.")
                .key("K")
                .need(1)
                .tick(ctx -> {
                    // Check if pillar failed — player pressed K but didn't launch
                    // Detect: no pillar cooldown started, player is grounded, K recently pressed
                    // (The ability itself doesn't expose a failure flag, so watch for K press + no launch)
                    if (!ctx.win.player.abilities.isPillaring
                            && ctx.win.player.isOnGround()
                            && ctx.win.player.abilities.getPillarCooldownFrac() > 0.99f
                            && ctx.stepAge > 0.3f) {
                        // Check block density under player to give accurate feedback
                        // (re-check the same condition the ability uses)
                        org.joml.Vector3f pos = ctx.win.player.position;
                        int cx2 = (int) Math.floor(pos.x);
                        int cz2 = (int) Math.floor(pos.z);
                        int sy  = (int) Math.floor(pos.y) - 1;
                        boolean hasSolid = sy >= 0 && ctx.win.world.getBlock(cx2, sy, cz2).isSolid();
                        if (!hasSolid) {
                            ctx.win.practiceWarnText  = "Ground not solid here  -  move to stone or dirt!";
                            ctx.win.practiceWarnTimer = 2.0f;
                        }
                    }
                })
                .when(ctx -> ctx.win.player.abilities.getPillarCooldownFrac() < 0.92f)
                .win("Launched!")
                .tout(60f).build());

            s.add(Step.of("STONE PILLAR  -  GROUND SLAM on the way down")
                .instr("You are in the air!\n" +
                       "While falling, tap  [ SHIFT ]  once.\n" +
                       "You will slam into the ground, crater the terrain,\n" +
                       "and damage everything nearby.")
                .key("SHIFT  (tap once, in air)")
                .need(1)
                .when(ctx -> ctx.win.player.smashImpactX != Integer.MIN_VALUE)
                .win("Ground slam!")
                .tout(20f).build());
        }
        case CANNONBALL -> {
            s.add(Step.of("CANNONBALL")
                .instr("HOLD  [ G ]  to charge  -  do NOT just click, keep it held.\n" +
                       "Release to fire yourself as an explosive cannonball.\n" +
                       "You blast through blocks and enemies on contact.\n\n" +
                       "Press  [ ENTER ]  when you are ready to try it.")
                .key("HOLD G")
                .need(1)
                .skip()
                .when(ctx -> ctx.win.player.abilities.isCannonballing)
                .tout(20f).build());
        }

        // ── WAVE 7: STAND (MANHATTAN TRANSFER) ────────────────────────────────
        case STAND -> {
            s.add(Step.of("MANHATTAN TRANSFER  -  deploy the drone")
                .instr("Manhattan Transfer is a combat drone.\n" +
                       "Press  [ X ]  to deploy it above you.")
                .key("X")
                .need(1)
                .setup(ctx -> {
                    // Spawn a slime on solid ground off to the player's side as a target.
                    float yaw = ctx.win.lastCameraYaw + (float)Math.PI * 0.5f;
                    float px  = ctx.win.player.position.x + (float)Math.sin(yaw) * 12f;
                    float pz  = ctx.win.player.position.z + (float)Math.cos(yaw) * 12f;
                    clearDummies(ctx);
                    spawnOnGround(ctx, px, pz, Type.SLIME);
                })
                .when(ctx -> ctx.win.player.stand.isDeployed())
                .win("Drone deployed!")
                .tout(60f).build());

            s.add(Step.of("MANHATTAN TRANSFER  -  enter drone view")
                .instr("Press  [ TAB ]  to take control of the drone.\n" +
                       "WASD = fly, SPACE = up, SHIFT = down.\n" +
                       "Fly until you can see the target.")
                .key("TAB")
                .need(1)
                .when(ctx -> ctx.win.player.stand.isInStandPerspective())
                .win("Drone view active!")
                .tout(60f).build());

            s.add(Step.of("MANHATTAN TRANSFER  -  fire through the drone")
                .instr("Aim at the target and press  [ C ]  to fire through the drone.\n" +
                       "The shot originates from the drone, not from you.\n" +
                       "Hit the target once.")
                .key("C")
                .need(1)
                .when(ctx -> ctx.dummyHpDropped())
                .win("Hit!")
                .tout(60f).build());
        }

        // ── WAVE 8: STONE CANON + SUBSTITUTE ─────────────────────────────────
        case STONE_CANON -> {
            s.add(Step.of("STONE CANON  -  HOLD to charge")
                .instr("Near a stone surface, HOLD  [ I ]  to absorb stone into a boulder.\n" +
                       "Do NOT just tap  -  keep  [ I ]  held until the boulder is ready.\n" +
                       "Release to fire in the direction you are looking.\n" +
                       "Fire  2 times.")
                .key("HOLD I")
                .need(2)
                .when(ctx -> {
                    if (!ctx.flag && ctx.win.isChargingStoneCanon) { ctx.flag = true; return false; }
                    if (ctx.flag && !ctx.win.isChargingStoneCanon) { ctx.flag = false; return true; }
                    return false;
                })
                .win("Fired!")
                .tout(90f).build());
        }
        case SUBSTITUTE -> {
            s.add(Step.of("PAPER SUBSTITUTE")
                .instr("HOLD  [ V ]  to prime a paper dummy.\n" +
                       "The next hit you take is absorbed  -  you blink back,\n" +
                       "a paper clone appears, and it explodes.\n\n" +
                       "Prime it now with  [ V ].")
                .key("HOLD V")
                .need(1)
                .skip()
                .when(ctx -> ctx.win.substitutePrimed)
                .win("Substitute primed!")
                .tout(20f).build());
        }

        // ── WAVE 9: SEAL (MINATO'S SEAL) ─────────────────────────────────────
        case SEAL -> {
            s.add(Step.of("MINATO'S SEAL  -  place seals")
                .instr("Press  [ H ]  to throw a teleport seal.\n" +
                       "Look in  3 different directions and place one seal in each spot.\n" +
                       "They stick to any surface  -  floors, walls, ceilings.")
                .key("H")
                .need(3)
                .when(ctx -> {
                    int cur = ctx.win.player.seals.getSealCount();
                    if (!ctx.flag) { ctx.snapshot = cur; ctx.flag = true; return false; }
                    if (cur > ctx.snapshot) { ctx.snapshot = cur; return true; }
                    return false;
                })
                .win("Seal placed!")
                .tout(90f).build());

            s.add(Step.of("MINATO'S SEAL  -  warp to a seal")
                .instr("Look at one of your seals and press  [ B ]  to warp there instantly.\n" +
                       "Do this  2 times  from different spots.")
                .key("B")
                .need(2)
                .when(ctx -> {
                    // teleportFlash is set when a seal warp fires; detect its leading edge
                    float flash = ctx.win.player.seals.teleportFlash;
                    boolean justWarped = flash > 0.05f && ctx.snapshot <= 0.05f;
                    ctx.snapshot = flash;
                    return justWarped;
                })
                .win("Warped!")
                .tout(90f).build());

            s.add(Step.of("MINATO'S SEAL  -  recall  (max 5)")
                .instr("You can hold up to  5 seals  at a time.\n" +
                       "Look at a seal you no longer need and press  [ N ]  to recall it\n" +
                       "without teleporting. Recall  1  seal now.")
                .key("N")
                .need(1)
                .when(ctx -> {
                    int cur = ctx.win.player.seals.getSealCount();
                    if (!ctx.flag) { ctx.snapshot = cur; ctx.flag = true; return false; }
                    if (cur < ctx.snapshot) { ctx.snapshot = cur; return true; }
                    return false;
                })
                .win("Seal recalled!")
                .tout(60f).build());
        }

        // ── KAMUI (Easter egg  -  death-unlocked) ─────────────────────────────
        case KAMUI -> {
            s.add(Step.of("KAMUI  -  enter the void")
                .instr("Your body has learned to slip between dimensions.\n" +
                       "Press  [ Z ]  to phase into another reality.\n" +
                       "While inside: nothing can hurt you.")
                .key("Z")
                .need(1)
                .setup(ctx -> spawnDummies(ctx, 1))
                .when(ctx -> ctx.win.player.abilities.isKamui)
                .win("In the void!")
                .tout(60f).build());

            s.add(Step.of("KAMUI  -  HOLD to absorb")
                .instr("While in Kamui, HOLD  [ Left Click ]  near the slime.\n" +
                       "A vortex forms and pulls it in.\n" +
                       "Destroy it, then press  [ Z ]  to return.")
                .key("HOLD LMB")
                .need(1)
                .when(ctx -> ctx.aliveCount() == 0)   // slime gone = absorbed; can't soft-lock
                .win("Absorbed!")
                .tout(90f).build());
        }

        case FLIGHT -> { /* Tutorial is the ending cutscene itself. */ }
        default    -> { }
        }
        return s;
    }
}
