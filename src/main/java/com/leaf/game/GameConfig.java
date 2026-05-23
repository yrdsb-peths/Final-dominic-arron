package com.leaf.game;

public class GameConfig {

    // ── WORLD ────────────────────────────────────────────────
    public static long  seed           = System.currentTimeMillis();
    public static int   renderDistance = 6;
    public static int   seaLevel       = 20;

    // ── NOISE — CONTINENTALNESS ───────────────────────────────
    public static float contFreq    = 0.005f;
    public static int   contOctaves = 3;
    public static float contPersist = 0.5f;

    // ── NOISE — EROSION ───────────────────────────────────────
    public static float erosFreq    = 0.015f;
    public static int   erosOctaves = 4;
    public static float erosPersist = 0.5f;

    // ── NOISE — PEAKS & VALLEYS ───────────────────────────────
    public static float pvFreq    = 0.04f;
    public static int   pvOctaves = 5;
    public static float pvPersist = 0.4f;

    // ── HEIGHT MAPPING ────────────────────────────────────────
    public static int heightBase  = 10;
    public static int heightRange = 40;

    // ── 3D DENSITY NOISE ──────────────────────────────────────
    public static float density3DFreq             = 0.05f;
    public static float density3DVerticalCompress = 0.5f;
    public static int   density3DOctaves          = 3;
    public static float density3DPersist          = 0.5f;
    public static float density3DAmplitude        = 10f;

    // ── DENSITY SHAPE ─────────────────────────────────────────
    public static float densityVerticalScale = 0.15f;
    public static float densityErosionBoost  = 0.15f;

    // ── LIGHTING ──────────────────────────────────────────────
    // sunDirection: which direction light comes FROM (not toward).
    // (0.6, 1.0, 0.4) = roughly south-east overhead.
    public static float sunDirX         = 0.6f;
    public static float sunDirY         = 1.0f;
    public static float sunDirZ         = 0.4f;
    public static float sunStrength     = 0.75f;  // 0 = no sun, 1 = full blast
    public static float ambientStrength = 0.25f;  // floor brightness in full shadow

    // ── PLAYER / CAMERA ──────────────────────────────────────
    public static float mouseSensitivity = 0.001f;
    public static float fov              = 70.0f;

    // ── PLAYER PHYSICS ────────────────────────────────────────
    public static float GRAVITY      = 35.0f;
    public static float JUMP_FORCE   = 10.0f;
    public static float WALK_SPEED   = 5.0f;
    public static float SPRINT_SPEED = 8.5f;
    public static float FLY_SPEED    = 15.0f;
}