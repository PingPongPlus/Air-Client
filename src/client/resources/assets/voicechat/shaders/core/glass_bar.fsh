#version 330
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
in vec2 localUV;
in vec4 glassData;
out vec4 fragColor;
void main() {
    vec2 size = 1.0 / max(vec2(length(vec2(dFdx(localUV.x),dFdy(localUV.x))), length(vec2(dFdx(localUV.y),dFdy(localUV.y)))),vec2(.00001));
    vec2 halfSize = size * .5;
    // Sharp, angular corners for compact bars (unlike the pill-shaped controls).
    float r = min(min(halfSize.x, halfSize.y), 4.0);
    vec2 p = (localUV-.5)*size;
    vec2 q = abs(p)-halfSize+r;
    float d = length(max(q,0.0))+min(max(q.x,q.y),0.0)-r;
    float aa = max(fwidth(d),.75);
    float coverage = 1.0-smoothstep(-aa,aa,d);
    if (coverage < .001) discard;
    vec2 uv = gl_FragCoord.xy / vec2(textureSize(Sampler0,0));
    vec3 backdrop = mix(texture(Sampler0,uv).rgb,texture(Sampler1,uv).rgb,.7);
    vec3 color = mix(backdrop,glassData.rgb,.86);
    float edge = 1.0-smoothstep(0.0,1.8,abs(d+1.0));
    color += edge * mix(.025,.15,1.0-localUV.y);
    color += .045 * pow(1.0-localUV.y,3.0);
    fragColor=vec4(color,coverage*glassData.a);
}
