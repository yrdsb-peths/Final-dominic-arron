@echo off
:: ─────────────────────────────────────────────────────────────────────────────
::  play-windows.bat  —  auto-updates and launches the game (Windows)
::  Just double-click this file to play!
:: ─────────────────────────────────────────────────────────────────────────────
setlocal

set REPO=yrdsb-peths/Final-dominic-arron
set JAR_URL=https://github.com/%REPO%/releases/latest/download/game.jar
set JAR=%~dp0game.jar

:: ── 1. Check Java ─────────────────────────────────────────────────────────────
where java >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo.
    echo   Java is not installed.
    echo   Please download and install Java 17 from:
    echo   https://adoptium.net/temurin/releases/?version=17
    echo.
    pause
    exit /b 1
)

:: Extract major version (handles both "17.0.x" and legacy "1.8" formats)
for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do (
    set JAVA_RAW=%%v
)
set JAVA_RAW=%JAVA_RAW:"=%
for /f "tokens=1 delims=." %%m in ("%JAVA_RAW%") do set JAVA_MAJOR=%%m
if "%JAVA_MAJOR%"=="1" (
    :: Legacy format like 1.8 — extract minor as the real version
    for /f "tokens=2 delims=." %%m in ("%JAVA_RAW%") do set JAVA_MAJOR=%%m
)

if %JAVA_MAJOR% LSS 17 (
    echo.
    echo   Java 17 or newer is required ^(you have Java %JAVA_MAJOR%^).
    echo   Please download Java 17 from:
    echo   https://adoptium.net/temurin/releases/?version=17
    echo.
    pause
    exit /b 1
)

:: ── 2. Download latest build ──────────────────────────────────────────────────
echo.
echo   Checking for updates...
powershell -NoProfile -Command ^
  "try { Invoke-WebRequest -Uri '%JAR_URL%' -OutFile '%JAR%.tmp' -UseBasicParsing; exit 0 } catch { exit 1 }"
if %ERRORLEVEL% equ 0 (
    move /y "%JAR%.tmp" "%JAR%" >nul
    echo   Game is up to date!
) else (
    del "%JAR%.tmp" 2>nul
    if exist "%JAR%" (
        echo   Could not reach update server -- launching existing version.
    ) else (
        echo.
        echo   No game file found and download failed.
        echo   Check your internet connection and try again.
        echo.
        pause
        exit /b 1
    )
)

:: ── 3. Launch ─────────────────────────────────────────────────────────────────
echo   Launching...
echo.
java -jar "%JAR%"
if %ERRORLEVEL% neq 0 pause
