#version 330 core
// Depth-only: nothing to shade. Water (style 4) and small ground-cover plants
// (styles 9+) cast no shadow.
in float vStyleU;

void main() {
    if (vStyleU < 0.0) {
        int st = int(floor(-vStyleU)) - 1;
        if (st == 4 || st >= 9) discard;   // water + ground-cover plants cast no shadow
    }
}
