#version 330 core
// Bloom downsample (13-tap "dual filter", Jimenez 2014). The first level also
// applies a soft-knee bright-pass so only HDR light (> threshold) blooms.
in  vec2 TexCoord;
out vec4 FragColor;

uniform sampler2D src;
uniform vec2  texel;      // 1 / source size
uniform int   prefilter;  // 1 on the first step
uniform float threshold;

vec3 brightPass(vec3 c) {
    float br   = max(c.r, max(c.g, c.b));
    float knee = threshold * 0.5;
    float soft = clamp(br - threshold + knee, 0.0, 2.0 * knee);
    soft = soft * soft / (4.0 * knee + 1e-4);
    float contrib = max(soft, br - threshold) / max(br, 1e-4);
    return c * contrib;
}

void main() {
    vec2 t = texel;
    vec3 a = texture(src, TexCoord + t * vec2(-2,  2)).rgb;
    vec3 b = texture(src, TexCoord + t * vec2( 0,  2)).rgb;
    vec3 c = texture(src, TexCoord + t * vec2( 2,  2)).rgb;
    vec3 d = texture(src, TexCoord + t * vec2(-2,  0)).rgb;
    vec3 e = texture(src, TexCoord).rgb;
    vec3 f = texture(src, TexCoord + t * vec2( 2,  0)).rgb;
    vec3 g = texture(src, TexCoord + t * vec2(-2, -2)).rgb;
    vec3 h = texture(src, TexCoord + t * vec2( 0, -2)).rgb;
    vec3 i = texture(src, TexCoord + t * vec2( 2, -2)).rgb;
    vec3 j = texture(src, TexCoord + t * vec2(-1,  1)).rgb;
    vec3 k = texture(src, TexCoord + t * vec2( 1,  1)).rgb;
    vec3 l = texture(src, TexCoord + t * vec2(-1, -1)).rgb;
    vec3 m = texture(src, TexCoord + t * vec2( 1, -1)).rgb;

    vec3 col = e * 0.125
             + (a + c + g + i) * 0.03125
             + (b + d + f + h) * 0.0625
             + (j + k + l + m) * 0.125;
    col = max(col, 0.0);
    if (prefilter == 1) col = min(brightPass(col), vec3(4.0));   // clamp fireflies
    FragColor = vec4(col, 1.0);
}
