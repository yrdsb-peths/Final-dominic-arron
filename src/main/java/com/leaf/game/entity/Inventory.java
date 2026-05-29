package com.leaf.game.entity;

import com.leaf.game.world.Block;

import java.util.HashMap;
import java.util.Map;

public class Inventory {

    // Maps block type to how many the player has
    private final Map<Block, Integer> items = new HashMap<>();

    // How many blocks the player starts with (so they can build immediately)
    public Inventory() {
        items.put(Block.CRYSTAL_AMETHYST, 64);
        items.put(Block.MEGALITH,  64);
        items.put(Block.ANCIENT_MARROW, 64);
        items.put(Block.ICE, 64);
        items.put(Block.CRYSTAL_CITRINE, 64);
        items.put(Block.CRYSTAL_ROSE, 64);
        items.put(Block.CRATER_BLOOM, 64);
    }

    /** Add one block to the inventory. Called when a block is broken. */
    public void addBlock(Block block) {
        if (block == Block.AIR) return;
        items.merge(block, 1, Integer::sum);
    }

    /**
     * Remove one block from the inventory.
     * Returns true if the player had it and it was removed.
     * Returns false if the player is out.
     */
    public boolean useBlock(Block block) {
        int count = items.getOrDefault(block, 0);
        if (count <= 0) return false;
        if (count == 1) items.remove(block);
        else items.put(block, count - 1);
        return true;
    }

    public int getCount(Block block) {
        return items.getOrDefault(block, 0);
    }

    /** Returns a formatted string for the HUD display. */
    public String getHUDText() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Block, Integer> e : items.entrySet()) {
            if (e.getValue() > 0) {
                sb.append(e.getKey().name())
                        .append(": ")
                        .append(e.getValue())
                        .append("  ");
            }
        }
        return sb.toString();
    }
}