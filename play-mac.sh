#!/bin/bash
# ─────────────────────────────────────────────────────────────────────────────
#  play-mac.sh  —  auto-updates and launches the game (macOS)
#  Just run:  bash play-mac.sh
# ─────────────────────────────────────────────────────────────────────────────

REPO="yrdsb-peths/Final-dominic-arron"
JAR_URL="https://github.com/$REPO/releases/latest/download/game.jar"

# Store the JAR next to this script so everything lives in one folder
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$SCRIPT_DIR/game.jar"

# ── 1. Check Java ─────────────────────────────────────────────────────────────
if ! command -v java &>/dev/null; then
    echo ""
    echo "  ❌  Java is not installed."
    echo "      Please download and install Java 17 from:"
    echo "      https://adoptium.net/temurin/releases/?version=17"
    echo ""
    read -p "  Press Enter to exit..."
    exit 1
fi

JAVA_MAJOR=$(java -version 2>&1 | grep -oE '"[0-9]+' | grep -oE '[0-9]+' | head -1)
if [ -z "$JAVA_MAJOR" ] || [ "$JAVA_MAJOR" -lt 17 ]; then
    echo ""
    echo "  ❌  Java 17 or newer is required (you have Java ${JAVA_MAJOR:-unknown})."
    echo "      Please download Java 17 from:"
    echo "      https://adoptium.net/temurin/releases/?version=17"
    echo ""
    read -p "  Press Enter to exit..."
    exit 1
fi

# ── 2. Download latest build ──────────────────────────────────────────────────
echo ""
echo "  ⬇️   Checking for updates..."
if curl -fsSL "$JAR_URL" -o "$JAR.tmp" 2>/dev/null; then
    mv "$JAR.tmp" "$JAR"
    echo "  ✅  Game is up to date!"
else
    rm -f "$JAR.tmp"
    if [ -f "$JAR" ]; then
        echo "  ⚠️   Couldn't reach update server — launching existing version."
    else
        echo ""
        echo "  ❌  No game file found and download failed."
        echo "      Check your internet connection and try again."
        echo ""
        read -p "  Press Enter to exit..."
        exit 1
    fi
fi

# ── 3. Launch ─────────────────────────────────────────────────────────────────
echo "  🚀  Launching..."
echo ""
# -XstartOnFirstThread is required on macOS for LWJGL/GLFW to work
java -XstartOnFirstThread -jar "$JAR"
