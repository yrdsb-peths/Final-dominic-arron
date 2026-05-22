package com.leaf.game;
import java.util.Random;


public class WorldGen {
    private final Random random = new Random();
    public void generate(World world) {
        for (int x = 0; x < World.WIDTH; x++) {
            for (int y = 0; y < World.HEIGHT; y++) {
                for (int z = 0; z < World.DEPTH; z++) {
                    world.setBlock(x, y, z, getBlock(x, y, z));
                }
            }
        }
    }

    private Block getBlock(int x, int y, int z) {
        int surface = 10 + (int) (5 * Math.sin(x * 0.1) + 5*Math.sin(z * 0.1));
        return y < surface ? Block.STONE : Block.AIR;
    }
}