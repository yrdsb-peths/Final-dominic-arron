#version 330 core
// Depth-only: nothing to shade. Water (surface style 4) never casts a shadow —
// the world under a lake would otherwise go dark.
in float vStyleU;

void main() {
    if (vStyleU < 0.0 && int(floor(-vStyleU)) - 1 == 4) discard;
}
