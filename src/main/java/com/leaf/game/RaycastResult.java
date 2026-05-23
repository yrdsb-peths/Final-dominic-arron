package com.leaf.game;

public class RaycastResult {
    public boolean hit = false;

    // The solid block that was hit (block to BREAK)
    public int hitX, hitY, hitZ;

    // The air block just before the hit (block position to PLACE into)
    public int placeX, placeY, placeZ;
}