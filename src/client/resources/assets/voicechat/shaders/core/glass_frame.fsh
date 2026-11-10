#version 330
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
in vec2 localUV;
in vec4 glassData;
out vec4 fragColor;

void main() {
    vec2 size = 1.0 / max(vec2(length(vec2(dFdx(localUV.x), dFdy(localUV.x))),
            length(vec2(dFdx(localUV.y), dFdy(localUV.y)))), vec2(0.00001));
    float pixelScale = size.y / max(glassData.b * 255.0, 1.0);
    float cornerScale = floor(glassData.g * 255.0 + 0.5) / 128.0;
    vec2 halfSize = size * 0.5;
    float radius = min(min(halfSize.x, halfSize.y), 4.5 * pixelScale * cornerScale);
    vec2 p = (localUV - 0.5) * size;
    vec2 q = abs(p) - halfSize + radius;
    float distance = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
    float thickness = 2.0 * pixelScale;
    float aa = max(fwidth(distance), 0.6);
    // Derivatives must be evaluated before discarding the hollow center.
    vec2 screenNormal = normalize(vec2(dFdx(distance), dFdy(distance)) + vec2(0.0001));
    float coverage = (1.0 - smoothstep(-aa, aa, distance))
            * smoothstep(-aa, aa, distance + thickness);
    if (coverage < 0.001) discard;

    vec2 texSize = vec2(textureSize(Sampler0, 0));
    vec2 uv = gl_FragCoord.xy / texSize;
    float bevel = clamp(-distance / thickness, 0.0, 1.0);
    vec2 bentUV = uv + screenNormal * (0.5 - bevel) * 4.0 * pixelScale / texSize;
    vec2 halfTexel = 0.5 / texSize;
    bentUV = clamp(bentUV, halfTexel, 1.0 - halfTexel);
    vec3 color = mix(texture(Sampler0, bentUV).rgb, texture(Sampler1, bentUV).rgb, 0.45);

    vec2 normal = normalize(max(q, vec2(0.0)) * sign(p) + vec2(0.0001));
    if (q.x < 0.0 && q.y < 0.0) {
        normal = q.x > q.y ? vec2(sign(p.x), 0.0) : vec2(0.0, sign(p.y));
    }
    float light = pow(max(dot(normal, normalize(vec2(-0.5, -0.85))), 0.0), 2.0);
    float outerRim = 1.0 - smoothstep(0.0, 0.8 * pixelScale, abs(distance + 0.35 * pixelScale));
    float innerRim = 1.0 - smoothstep(0.0, 0.65 * pixelScale, abs(distance + thickness - 0.25 * pixelScale));
    color *= 0.88;
    color = mix(color, vec3(0.94, 0.98, 1.0), 0.18 + outerRim * (0.35 + light * 0.35));
    color = mix(color, vec3(0.72, 0.84, 0.91), innerRim * 0.28);
    fragColor = vec4(clamp(color, 0.0, 1.0), coverage * glassData.a);
}
