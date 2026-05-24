package com.leaf.game.core;

public class GameConfig {
    public static long  seed           = 69420L;
    public static int   renderDistance = 6;
    public static int   seaLevel       = 20;

    // Lower frequency = larger continents. Gives mountains room to form!
    public static float contFreq    = 0.001f;
    public static int   contOctaves = 2;
    public static float contPersist = 0.35f;

    public static float erosFreq         = 0.0015f;
    public static int   erosOctaves      = 3;
    public static float erosPersist      = 0.45f;
    public static float erosWarpFreq     = 0.001f;
    public static float erosWarpStrength = 180f;

    public static float pvFreq    = 0.008f;
    public static int   pvOctaves = 5;
    public static float pvPersist = 0.50f;

    public static float tempFreq    = 0.0008f;
    public static int   tempOctaves = 2;
    public static float tempPersist = 0.3f;

    public static float humFreq    = 0.002f;
    public static int   humOctaves = 3;
    public static float humPersist = 0.4f;

    public static float riverFreq            = 0.003f;
    public static int   riverOctaves         = 2;
    public static float riverPersist         = 0.50f;
    public static float riverThreshold       = 0.050f;
    public static float riverCarveDepth      = 0.045f;
    public static float riverFloorMargin     = 0.025f;

    public static int heightBase  = 8;
    public static int heightRange = 52;

    public static float density3DFreq             = 0.05f;
    public static float density3DVerticalCompress = 0.35f;
    public static int   density3DOctaves          = 3;
    public static float density3DPersist          = 0.5f;
    public static float density3DAmplitude        = 8f;
    public static float densityVerticalScale      = 0.12f;

    public static float cheeseFreq         = 0.040f;
    public static float cheeseVertCompress = 0.50f;
    public static int   cheeseOctaves      = 3;
    public static float cheesePersist      = 0.50f;
    public static float cheeseThreshold    = 0.58f;
    public static float cheeseDepthBoost   = 0.12f;

    public static float spagFreq           = 0.022f;
    public static float spagVertCompress   = 0.65f;
    public static int   spagOctaves        = 2;
    public static float spagPersist        = 0.50f;
    public static float spagThreshold      = 0.68f;

    public static int caveSurfaceBuffer = 6;
    public static int caveBedrockFloor  = 4;

    public static int beachMaxAltitude  = 2;
    public static int snowAltitude      = 44;
    public static int mountainSnowAltitude = 120;

    public static float sunDirX         = 0.6f;
    public static float sunDirY         = 1.0f;
    public static float sunDirZ         = 0.4f;
    public static float sunStrength     = 0.75f;
    public static float ambientStrength = 0.25f;

    public static float mouseSensitivity = 0.001f;
    public static float fov              = 70.0f;

    public static float GRAVITY      = 35.0f;
    public static float JUMP_FORCE   = 10.0f;
    public static float WALK_SPEED   = 5.0f;
    public static float SPRINT_SPEED = 8.5f;
    public static float FLY_SPEED    = 100.0f;
}