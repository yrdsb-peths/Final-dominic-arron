#!/bin/bash
# ─────────────────────────────────────────────────────────────────────────────
#  preview.sh  —  open the animation sandbox to inspect a model/animation
#
#  Usage:
#    ./preview.sh                    # preview the default model (enemy_basic)
#    ./preview.sh my_monster.bbmodel # import a Blockbench file and preview it
#    ./preview.sh enemy_basic        # preview a model in resources/models
#    ./preview.sh path/to/model.json # preview an AnimModel json on disk
#
#  Controls (also printed in the terminal):
#    Left-drag orbit · Scroll zoom · ←/→ switch animation · Space pause
#    R reload-from-disk (live editing!) · F re-frame · Esc quit
# ─────────────────────────────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR" || exit 1
./gradlew preview -q --console=plain --args="$*"
