package com.leaf.game.core;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Progression  -  the ability unlock system for DESCENT.
 *
 * ── How it works ────────────────────────────────────────────────────────────
 *  • The player starts every run able to use SNIPE plus everything they have
 *    EVER unlocked in a previous run (accumulated progress, persisted to disk).
 *  • Clearing a wave unlocks that wave's tier of abilities  -  but only the first
 *    time the player reaches it. Replaying earlier waves shows no card.
 *  • Locked abilities silently do nothing when their key is pressed.
 *
 * ── Editing the unlock schedule / card text ─────────────────────────────────
 *  Everything a designer needs to tweak lives at the top of this file:
 *    - the Ability enum (label / key hint / one-line description  -  also feeds F1)
 *    - TIERS  : which abilities unlock at which wave (index = wave; 0 = start kit)
 *    - FLAVOR : the story headline shown on each wave's unlock card
 *  No other file needs to change to retune the progression.
 */
public class Progression {

    // ═══════════════════════════════════════════════════════════════════════
    //  EDIT HERE  -  abilities, their keys, and one-line descriptions
    //  (used by BOTH the unlock cards and the F1 reference, so they stay in sync)
    // ═══════════════════════════════════════════════════════════════════════
    public enum Ability {
        SNIPE      ("Snipe",            "[C]",        "Hold to charge a crystal bolt, release to fire. Longer charge = bigger blast."),
        SLASH      ("Slash",            "[F]",        "Wide melee swing  -  hits every enemy in a cone in front of you."),
        DASH       ("Dash",             "[Q]",        "Instant burst in your move direction. Short cooldown, leaves a ghost trail."),
        QUAGMIRE   ("Quagmire",         "[M]",        "Fire a mud wave along the ground. Traps the enemy it hits for several seconds."),
        LIGHTNING  ("Lightning",        "[U]",        "Strike the enemy you aim at with lightning. Double-tap [U] for an area burst."),
        HEAL       ("Heal",             "[L]",        "Hold to channel healing  -  restores health over time. You can't move while channeling."),
        GRAB       ("Grab & Slam",      "[O]",        "Grab the enemy in your crosshair, hoist them up, then slam them into the ground."),
        BLINK      ("Blink",            "[E]",        "Teleport to the point you're looking at (up to ~22 blocks)."),
        SWAP       ("Position Swap",    "[J]",        "Instantly swap places with the nearest enemy  -  perfect for escapes."),
        PILLAR     ("Stone Pillar",     "[K]",        "A stone spire erupts under you and launches you skyward."),
        CANNONBALL ("Cannonball",       "[G]",        "Hold to charge, release to launch yourself as an explosive cannonball."),
        STAND      ("Manhattan Transfer","[X] / [TAB]","Deploy a combat drone that auto-fires at enemies. [TAB] to pilot it yourself."),
        TIME       ("Time Dilation",    "[R] / [Y]",  "[R] slows time to a crawl, [Y] speeds it up  -  dodge or line up a shot."),
        SEAL       ("Minato's Seal",    "[H] / [B]",  "[H] throws a teleport seal; [B] warps you to it. Up to 5 active at once."),
        SUBSTITUTE ("Substitute",       "[V]",        "Hold to prime. The next hit is absorbed  -  you blink back and leave an exploding decoy."),
        STONE_CANON("Stone Canon",      "[I]",        "Near stone, hold to absorb it into a giant projectile. Release to fire."),
        KAMUI      ("Kamui",            "[Z]",        "Phase into another dimension  -  invincible while active. Drains mana fast."),
        FLIGHT     ("Flight",           "[Space x2]", "Double-tap Space to fly. [V] cycles flight modes (skim / soar / grapple).");

        public final String label, key, desc;
        Ability(String label, String key, String desc) { this.label = label; this.key = key; this.desc = desc; }
    }

    /**
     * Which abilities unlock at which wave. Index = wave cleared; index 0 = starting kit.
     *
     * KAMUI is intentionally absent from the tier list.
     * It is a death-earned easter egg granted by RunRecords after 3 deaths.
     * Progression.grantKamui() handles it separately.
     */
    private static final Ability[][] TIERS = {
        /* start  */ { Ability.SNIPE },
        /* wave 1 */ { Ability.SLASH, Ability.DASH },
        /* wave 2 */ { Ability.QUAGMIRE },
        /* wave 3 */ { Ability.LIGHTNING, Ability.HEAL },
        /* wave 4 */ { Ability.GRAB },
        /* wave 5 */ { Ability.BLINK, Ability.SWAP },
        /* wave 6 */ { Ability.PILLAR, Ability.CANNONBALL },
        /* wave 7 */ { Ability.STAND },
        /* wave 8 */ { Ability.STONE_CANON, Ability.SUBSTITUTE },
        /* wave 9 */ { Ability.SEAL },        // Minato's Seal — complex, earns its own wave
        /* wave 10*/ { Ability.FLIGHT },       // final gift — the ending
    };

    /** Story headline shown on each wave's unlock card (index = wave cleared). */
    private static final String[] FLAVOR = {
        "The crystal stirs. Its first gift is yours.",
        "They're closing in. Move faster  -  strike harder.",
        "Don't let them surround you. Hold them in place.",
        "The mountain's fury  -  and the means to endure it.",
        "They send something bigger. Take it apart.",
        "Be everywhere they aren't.",
        "The earth itself answers to you now.",
        "You are not alone anymore.",
        "Stone and shadow. Break through.",
        "Mark the world. Bend it around you.",
        "One last gift. The mountain releases you. The sky is yours now.",
    };

    /** The wave that triggers the ENDING cutscene and grants FLIGHT. */
    public static final int ENDING_WAVE = 10;

    /** Always shown on unlock cards  -  the user wants players reminded about mana. */
    public static final String MANA_NOTE = "Most abilities draw MANA  -  the blue bar under your health. It refills over time.";

    // ═══════════════════════════════════════════════════════════════════════

    private final EnumSet<Ability> unlocked = EnumSet.noneOf(Ability.class);
    private int maxTier;

    public Progression() {
        reset();   // every run starts fresh  -  only the wave-0 starting kit
    }

    /**
     * Reset to the starting kit (SNIPE only). Called at the start of every run so
     * abilities unlock wave-by-wave each playthrough  -  that's what makes the unlock
     * cards appear every wave and the ability icons reveal one at a time.
     * (Abilities are intentionally NOT carried across runs.)
     */
    public void reset() {
        unlocked.clear();
        maxTier = 0;
        for (int t = 0; t <= maxTier && t < TIERS.length; t++)
            for (Ability a : TIERS[t]) unlocked.add(a);
    }

    /** True if the player may use this ability right now. */
    public boolean isUnlocked(Ability a) { return unlocked.contains(a); }

    /** The abilities a given wave's tier grants (always  -  independent of unlock history). */
    public List<Ability> abilitiesForWave(int wave) {
        List<Ability> out = new ArrayList<>();
        if (wave >= 1 && wave < TIERS.length) for (Ability a : TIERS[wave]) out.add(a);
        return out;
    }

    /**
     * Mark a cleared wave's tier as unlocked.
     * @return the abilities newly unlocked by this clear (empty if already owned or no tier).
     */
    public List<Ability> unlockForWave(int wave) {
        List<Ability> gained = new ArrayList<>();
        if (wave < 1 || wave >= TIERS.length) return gained;  // wave 10+ = boss, no tier
        if (wave <= maxTier) return gained;                   // already earned this run
        for (Ability a : TIERS[wave]) if (unlocked.add(a)) gained.add(a);
        maxTier = Math.max(maxTier, wave);
        return gained;
    }

    /**
     * Directly grant Kamui regardless of wave progress.
     * Called when the 3rd death triggers the awakening cutscene.
     *
     * IMPORTANT: this must NOT touch maxTier. Kamui is death-earned and completely
     * independent of the wave-tier ladder. A previous version set maxTier = 8 here,
     * which made unlockForWave() treat every wave ≤ 8 as "already earned" — so after
     * the 3rd death, clearing waves 1–8 silently failed to unlock their abilities
     * (the card still showed via the abilitiesForWave fallback, but player.can(...)
     * stayed false). Adding KAMUI directly to `unlocked` is all that's needed.
     */
    public void grantKamui() {
        unlocked.add(Ability.KAMUI);
    }

    /** Story headline for a wave's unlock card. */
    public String flavorFor(int wave) {
        return (wave >= 0 && wave < FLAVOR.length) ? FLAVOR[wave] : "";
    }

    /** The wave at which an ability unlocks (0 = starting kit). */
    public int unlockWaveOf(Ability a) {
        for (int t = 0; t < TIERS.length; t++)
            for (Ability x : TIERS[t]) if (x == a) return t;
        return -1;
    }

    /** Highest wave-tier reached this run. */
    public int maxTier() { return maxTier; }

    public Ability[] allAbilities() { return Ability.values(); }
}
