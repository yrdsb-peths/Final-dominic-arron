#version 330 core
// Bloom upsample: 3×3 tent filter, blended additively into the next-larger mip.
in  vec2 TexCoord;
out vec4 FragColor;

uniform sampler2D src;
uniform vec2 texel;   // 1 / source size

void main() {
    vec2 t = texel;
    vec3 col = texture(src, TexCoord).rgb * 4.0;
    col += (texture(src, TexCoord + vec2(-t.x, 0)).rgb + texture(src, TexCoord + vec2(t.x, 0)).rgb
          + texture(src, TexCoord + vec2(0, -t.y)).rgb + texture(src, TexCoord + vec2(0, t.y)).rgb) * 2.0;
    col += texture(src, TexCoord + vec2(-t.x, -t.y)).rgb + texture(src, TexCoord + vec2(t.x, -t.y)).rgb
         + texture(src, TexCoord + vec2(-t.x,  t.y)).rgb + texture(src, TexCoord + vec2(t.x,  t.y)).rgb;
    FragColor = vec4(col / 16.0, 1.0);
}
