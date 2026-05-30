package com.leaf.game;

import com.leaf.game.core.Window;


public class Main {
    public static void main(String[] args) {
        // CRITICAL MAC OS FIX: Prevent AWT from spinning up a competing UI thread
        // which causes the "objc Method cache corrupted" / SIGABRT on startup.
        System.setProperty("java.awt.headless", "true");

        Window gameWindow = new Window();
        gameWindow.run();
    }
}