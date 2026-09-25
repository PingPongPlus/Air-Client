#version 330
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
in vec2 localUV;
in vec4 glassData;
out vec4 fragColor;

void main() {
    vec2 size = 1.0 / max(vec2(length(vec2(dFdx(localUV.x), dFdy(localUV.x))),
        length(vec2(dFdx(localUV.y), dFdy(localUV.y)))), vec2(0.00001));
    vec2 halfSize = size * 0.5;

    // Parallelogram: slanted left/right edges, straight top/bottom.
    float slant = 0.22;
    vec2 p = (localUV - 0.5) * size;
    vec2 s = vec2(p.x - slant * p.y, p.y);
    float r = min(min(halfSize.x, halfSize.y), 5.0);
    vec2 q = abs(s) - halfSize + vec2(r);
    float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
    float aa = max(fwidth(d), 0.75);
    float coverage = 1.0 - smoothstep(-aa, aa, d);

    // Soft outer glow halo (kept even outside the silhouette).
    float halo = 1.0 - smoothstep(0.0, 5.0, d);
    if (coverage < 0.001 && halo < 0.004) discard;

    float t = clamp(p.y / size.y + 0.5, 0.0, 1.0); // 0 bottom, 1 top
    float phase = glassData.b * 6.28318;
    vec3 color;

    if (glassData.r > 0.5) {
        // Softer, translucent blue vertical gradient.
        vec3 top = vec3(0.62, 0.80, 1.00);
        vec3 bottom = vec3(0.30, 0.50, 0.84);
        color = mix(bottom, top, t);

        // Water-like flowing interior: two slowly moving light waves.
        float wave1 = 0.5 + 0.5 * sin(p.x * 0.10 + phase * 1.6);
        float wave2 = 0.5 + 0.5 * sin(p.x * 0.06 - phase * 1.1 + 1.7);
        color += (wave1 * wave1) * 0.09 * t;
        color += (wave2 * wave2) * 0.06;
        // Gentle vertical flow shimmer.
        color += (0.5 + 0.5 * sin(p.y * 0.45 - phase * 2.6)) * 0.03;
    } else {
        // Light neutral glass track so the bar stands out.
        vec2 uv = gl_FragCoord.xy / vec2(textureSize(Sampler0, 0));
        vec3 backdrop = mix(texture(Sampler0, uv).rgb, texture(Sampler1, uv).rgb, 0.7);
        color = mix(backdrop, vec3(0.87, 0.90, 0.94), 0.55);
    }

    // Wide, soft shine sweeping over the bar.
    float band = fract(glassData.b * 0.6 + p.x / size.x * 0.35);
    float shine = exp(-pow((band - 0.5) / 0.24, 2.0));
    color += shine * (glassData.r > 0.5 ? 0.16 : 0.07);

    // Soft top gloss.
    color += 0.06 * pow(t, 2.0);

    // Thin bright outline.
    float edge = 1.0 - smoothstep(0.0, 1.5, abs(d + 1.0));
    color += edge * 0.42;

    // Blue glow aura around the bar.
    vec3 glowColour = vec3(0.45, 0.68, 1.0);
    color += glowColour * halo * 0.32;

    float alpha = max(coverage, halo * 0.45);
    fragColor = vec4(color, alpha * glassData.a);
}
