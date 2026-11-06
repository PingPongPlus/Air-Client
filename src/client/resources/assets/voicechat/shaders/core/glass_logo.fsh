#version 330
uniform sampler2D Sampler0; // Original mark: silhouette and sculpted relief.
uniform sampler2D Sampler1; // Captured menu scene.
in vec2 localUV;
in vec4 glassData;
out vec4 fragColor;

void main() {
    // Filter at display resolution before taking normals; the source has a fine alpha halo.
    vec2 dx = dFdx(localUV) * .65, dy = dFdy(localUV) * .65;
    vec4 mark = texture(Sampler0, localUV) * .4;
    mark += (texture(Sampler0, localUV + dx) + texture(Sampler0, localUV - dx)
            + texture(Sampler0, localUV + dy) + texture(Sampler0, localUV - dy)) * .15;
    float coverage = smoothstep(.12, .88, mark.a);
    float relief = dot(mark.rgb, vec3(.2126, .7152, .0722));
    float thickness = coverage * (.20 + .80 * relief);
    // Screen derivatives preserve refraction orientation and scale with the displayed mark.
    float physicalHeight = 1.0 / max(length(vec2(dFdx(localUV.y), dFdy(localUV.y))), .00001);
    vec2 slope = vec2(dFdx(thickness), dFdy(thickness)) * physicalHeight * .075;
    vec3 normal = normalize(vec3(-slope, 1.0));
    float edge = length(vec2(dFdx(coverage), dFdy(coverage)));
    if (coverage < .003) discard;

    vec2 size = vec2(textureSize(Sampler1, 0));
    vec2 uv = gl_FragCoord.xy / size;
    vec2 bend = normal.xy * (.012 + .013 * relief);
    vec2 border = .5 / size;
    // Slight spectral separation at thick bevels, restrained enough to remain clear glass.
    vec3 transmitted;
    transmitted.r = texture(Sampler1, clamp(uv + bend * 1.025, border, 1.0 - border)).r;
    transmitted.g = texture(Sampler1, clamp(uv + bend, border, 1.0 - border)).g;
    transmitted.b = texture(Sampler1, clamp(uv + bend * .975, border, 1.0 - border)).b;
    transmitted *= vec3(.87, .94, .97) * mix(.75, 1.0, relief);
    float fresnel = .035 + .965 * pow(1.0 - normal.z, 4.0);
    vec3 reflectedSky = mix(vec3(.24, .43, .52), vec3(.96, .99, 1.0), normal.y * .5 + .5);
    vec3 color = mix(transmitted, reflectedSky, fresnel * .82);
    float glint = pow(max(dot(normal, normalize(vec3(-.5, .8, .65))), 0.0), 42.0);
    float bevel = smoothstep(.35, .85, relief) * .11;
    // Broad reflected light reveals the curved ribbon even against a nearly uniform sky.
    float softbox = exp(-pow((relief - .66) / .115, 2.0));
    float sweep = exp(-pow((localUV.x + localUV.y * .38 - .55) / .16, 2.0));
    color += vec3(.72, .86, .95) * (softbox * .22 + sweep * .08);
    color += vec3(.83, .94, 1.0) * (glint * .48 + min(edge * 1.3, .28) + bevel);
    // A darker opposing edge makes the transparent volume readable against a bright sky.
    color *= 1.0 - .20 * pow(max(dot(normal, normalize(vec3(.7, -.8, .25))), 0.0), 6.0);
    fragColor = vec4(clamp(color, 0.0, 1.0), coverage * glassData.a);
}
