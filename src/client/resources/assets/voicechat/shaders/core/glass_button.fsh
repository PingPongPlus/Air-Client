#version 330
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
in vec2 localUV;
in vec4 glassData;
out vec4 fragColor;

float roundedBox(vec2 p, vec2 halfSize, float radius) {
    vec2 q = abs(p) - halfSize + radius;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
}

void main() {
    // Recover size in physical pixels, so GUI scale and high-DPI displays stay crisp.
    vec2 size = 1.0 / max(vec2(length(vec2(dFdx(localUV.x), dFdy(localUV.x))),
            length(vec2(dFdx(localUV.y), dFdy(localUV.y)))), vec2(0.00001));
    vec2 halfSize = size * 0.5;
    vec2 p = (localUV - 0.5) * size;
    float pixelScale = max(size.y / max(glassData.b * 255.0, 1.0), 1.0);
    // Green channel packs the enabled bit (bit 7) plus the global corner-radius scale (bits 0-6).
    float greenByte = floor(glassData.g * 255.0 + 0.5);
    float enabled = step(128.0, greenByte);
    float cornerScale = (greenByte - enabled * 128.0) / 64.0;
    // Buttons are capsules; larger panels retain practical rounded corners. Both scale globally.
    float radius = min(halfSize.x, halfSize.y) * cornerScale;
    if (glassData.b * 255.0 > 40.0) radius = min(radius, 14.0 * pixelScale * cornerScale);
    float distance = roundedBox(p, halfSize, radius);
    float aa = max(fwidth(distance), 0.75);
    // Evaluate derivatives before divergent discard/return branches at the silhouette.
    vec2 screenNormal = normalize(vec2(dFdx(distance), dFdy(distance)) + vec2(0.0001));
    float coverage = 1.0 - smoothstep(-aa, aa, distance);
    float hover = glassData.r;
    float opacity = glassData.a;
    // Stronger interaction feedback only for controls, not cursor-lit large panels.
    float highlight = hover * enabled * (1.0 - step(40.5, glassData.b * 255.0));

    // Soft shadow extends beyond the rounded silhouette.
    float shadowDistance = roundedBox(p - vec2(0.0, pixelScale), halfSize, radius);
    float shadow = exp(-max(shadowDistance, 0.0) / (1.1 * pixelScale)) * 0.18;
    if (coverage < 0.001) {
        if (shadow < 0.005) discard;
        fragColor = vec4(0.025, 0.025, 0.025, shadow * opacity);
        return;
    }

    vec2 texSize = vec2(textureSize(Sampler0, 0));
    vec2 screenUV = gl_FragCoord.xy / texSize;
    // Rounded edge normal acts as a small lens, strongest near the rim.
    vec2 q = abs(p) - halfSize + radius;
    vec2 normal = normalize(max(q, vec2(0.0)) * sign(p) + vec2(0.0001));
    if (q.x < 0.0 && q.y < 0.0) {
        normal = q.x > q.y ? vec2(sign(p.x), 0.0) : vec2(0.0, sign(p.y));
    }
    float rim = 1.0 - smoothstep(0.0, max(radius, 1.0), max(-distance, 0.0));
    // UV derivatives carry the proper screen orientation for the active GPU backend.
    vec2 bentUV = screenUV - screenNormal * rim * rim * pixelScale * (2.0 + hover) / texSize;
    vec2 halfTexel = 0.5 / texSize;
    bentUV = clamp(bentUV, halfTexel, 1.0 - halfTexel);

    // Sample a continuous Gaussian blur rather than distant taps that create ghost edges.
    vec3 color = texture(Sampler1, bentUV).rgb;
    // Preserve the scenery's hue; only a little neutral absorption improves label contrast.
    float absorption = 0.12 + (1.0 - enabled) * 0.08 - hover * 0.035;
    color *= 1.0 - absorption;
    color = mix(color, vec3(1.0), highlight * 0.18);

    float edge = 1.0 - smoothstep(0.0, (0.7 + highlight * 0.45) * pixelScale, abs(distance + 0.45 * pixelScale));
    float light = pow(max(dot(normal, normalize(vec2(-0.5, -0.85))), 0.0), 2.0);
    float lowerRim = pow(max(dot(normal, normalize(vec2(0.45, 0.9))), 0.0), 5.0);
    color = mix(color, vec3(0.96), edge * (0.20 + light * 0.32 + lowerRim * 0.14 + hover * 0.08 + highlight * 0.26));
    float sheen = exp(-pow((localUV.y - 0.08) / 0.20, 2.0)) * (0.014 + hover * 0.012 + highlight * 0.04);
    color += sheen;
    fragColor = vec4(clamp(color, 0.0, 1.0), coverage * opacity);
}
