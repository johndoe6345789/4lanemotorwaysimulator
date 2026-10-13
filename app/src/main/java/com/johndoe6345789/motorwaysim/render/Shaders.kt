package com.johndoe6345789.motorwaysim.render

/** GLSL ES 3.00 shaders (also valid WebGL 2). */
object Shaders {
    /**
     * Lit, vertex-coloured geometry: sun with soft shadows, sky/ground ambient light, specular
     * highlights, distance fog. The colour's alpha selects a material: 0–1 is how emissive
     * (self-lit) the surface is; 2 marks asphalt, which gets a procedural texture.
     */
    const val LIT_VS = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec4 aColor;
uniform mat4 uViewProj;
uniform mat4 uModel;
uniform mat4 uShadowMatrix;
uniform vec4 uTint;
out vec3 vNormal;
out vec4 vColor;
out vec3 vWorld;
out vec4 vShadow;
void main() {
    vec4 w = uModel * vec4(aPos, 1.0);
    vWorld = w.xyz;
    vNormal = mat3(uModel) * aNormal;
    vColor = vec4(aColor.rgb * uTint.rgb, aColor.a);
    vShadow = uShadowMatrix * w;
    gl_Position = uViewProj * w;
}
"""

    const val LIT_FS = """#version 300 es
precision highp float;
precision highp sampler2DShadow;
in vec3 vNormal;
in vec4 vColor;
in vec3 vWorld;
in vec4 vShadow;
uniform vec3 uSun;
uniform vec3 uCam;
uniform vec3 uFogColor;
uniform vec2 uFog;
uniform vec2 uOrigin;
uniform float uGround;
uniform float uShine;
uniform float uFogMax;
uniform float uShadowOn;
uniform float uShadowTexel;
uniform sampler2DShadow uShadowMap;
out vec4 fragColor;

float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
}

float shadowAt() {
    if (uShadowOn < 0.5) return 1.0;
    vec3 sc = vShadow.xyz / vShadow.w;
    if (sc.x <= 0.0 || sc.x >= 1.0 || sc.y <= 0.0 || sc.y >= 1.0 || sc.z >= 1.0) return 1.0;
    float t = uShadowTexel;
    float z = sc.z - 0.0012;
    float s = texture(uShadowMap, vec3(sc.xy + vec2(-t, -t), z));
    s += texture(uShadowMap, vec3(sc.xy + vec2(t, -t), z));
    s += texture(uShadowMap, vec3(sc.xy + vec2(-t, t), z));
    s += texture(uShadowMap, vec3(sc.xy + vec2(t, t), z));
    // Fade out towards the edge of the shadow map.
    vec2 e = min(sc.xy, 1.0 - sc.xy);
    float edge = clamp(min(e.x, e.y) * 12.0, 0.0, 1.0);
    return mix(1.0, s * 0.25, edge);
}

void main() {
    vec3 base = vColor.rgb;
    float emissive = vColor.a;
    vec2 p = mod(vWorld.xy + uOrigin, 2000.0);
    if (emissive > 1.5) {
        // Asphalt: coarse patches and fine grain.
        base *= 0.86 + 0.16 * noise(p * 0.35) + 0.08 * noise(p * 6.0);
        emissive = 0.0;
    }
    if (uGround > 0.5) {
        float stripe = mod(floor(p.y / 14.0), 2.0);
        float field = mod(floor(p.x / 170.0) + floor(p.y / 230.0), 2.0);
        base *= (0.95 + 0.05 * stripe) * (0.9 + 0.1 * field) * (0.88 + 0.24 * noise(p * 0.08));
    }
    vec3 n = normalize(vNormal);
    float diff = max(dot(n, uSun), 0.0);
    float shadow = shadowAt();
    vec3 ambient = mix(vec3(0.30, 0.33, 0.26), vec3(0.58, 0.68, 0.84), 0.5 + 0.5 * n.z) * 0.62;
    vec3 v = normalize(uCam - vWorld);
    vec3 h = normalize(uSun + v);
    float spec = pow(max(dot(n, h), 0.0), 60.0) * uShine * shadow;
    vec3 lit = base * (ambient + vec3(1.0, 0.95, 0.86) * 0.78 * diff * shadow) + vec3(spec);
    vec3 col = mix(lit, base, clamp(emissive, 0.0, 1.0));
    float d = length(vWorld - uCam);
    float f = clamp((d - uFog.x) / (uFog.y - uFog.x), 0.0, 1.0);
    f = f * f * (3.0 - 2.0 * f) * uFogMax;
    fragColor = vec4(mix(col, uFogColor, f), 1.0);
}
"""

    /** Depth-only pass from the sun for shadows. */
    const val SHADOW_VS = """#version 300 es
layout(location = 0) in vec3 aPos;
uniform mat4 uLightViewProj;
uniform mat4 uModel;
void main() {
    gl_Position = uLightViewProj * uModel * vec4(aPos, 1.0);
}
"""

    const val SHADOW_FS = """#version 300 es
precision mediump float;
void main() {
}
"""

    /** Sky: a full-screen triangle shaded by view direction, with a sun glow. */
    const val SKY_VS = """#version 300 es
layout(location = 0) in vec2 aPos;
out vec2 vNdc;
void main() {
    vNdc = aPos;
    gl_Position = vec4(aPos, 0.9999, 1.0);
}
"""

    const val SKY_FS = """#version 300 es
precision highp float;
in vec2 vNdc;
uniform mat4 uInvViewProj;
uniform vec3 uCam;
uniform vec3 uSun;
uniform vec3 uFogColor;
out vec4 fragColor;
void main() {
    vec4 far = uInvViewProj * vec4(vNdc, 1.0, 1.0);
    vec3 dir = normalize(far.xyz / far.w - uCam);
    float up = clamp(dir.z, 0.0, 1.0);
    vec3 zenith = vec3(0.30, 0.50, 0.82);
    vec3 col = mix(uFogColor, zenith, smoothstep(0.0, 0.45, up));
    float s = max(dot(dir, uSun), 0.0);
    col += vec3(1.0, 0.92, 0.75) * (pow(s, 600.0) * 1.5 + pow(s, 12.0) * 0.18);
    fragColor = vec4(col, 1.0);
}
"""

    /** Textured, unlit sign faces. */
    const val SIGN_VS = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aUv;
uniform mat4 uViewProj;
out vec2 vUv;
out vec3 vWorld;
void main() {
    vUv = aUv;
    vWorld = aPos;
    gl_Position = uViewProj * vec4(aPos, 1.0);
}
"""

    const val SIGN_FS = """#version 300 es
precision highp float;
in vec2 vUv;
in vec3 vWorld;
uniform sampler2D uTex;
uniform vec3 uCam;
uniform vec3 uFogColor;
uniform vec2 uFog;
out vec4 fragColor;
void main() {
    vec4 t = texture(uTex, vUv);
    if (t.a < 0.5) discard;
    float d = length(vWorld - uCam);
    float f = clamp((d - uFog.x) / (uFog.y - uFog.x), 0.0, 1.0);
    fragColor = vec4(mix(t.rgb, uFogColor, f * f * (3.0 - 2.0 * f)), 1.0);
}
"""

    /** Camera-facing additive light glows (brake lights, indicators, beacons). */
    const val GLOW_VS = """#version 300 es
layout(location = 0) in vec3 aCenter;
layout(location = 1) in vec3 aCorner;
layout(location = 2) in vec4 aColor;
uniform mat4 uViewProj;
uniform vec3 uRight;
uniform vec3 uUp;
uniform vec3 uCam;
uniform vec2 uFog;
out vec2 vCorner;
out vec4 vColor;
void main() {
    vCorner = aCorner.xy;
    float d = length(aCenter - uCam);
    float f = clamp((d - uFog.x) / (uFog.y - uFog.x), 0.0, 1.0);
    vColor = vec4(aColor.rgb, aColor.a * (1.0 - f));
    vec3 p = aCenter + (uRight * aCorner.x + uUp * aCorner.y) * aCorner.z;
    gl_Position = uViewProj * vec4(p, 1.0);
}
"""

    const val GLOW_FS = """#version 300 es
precision mediump float;
in vec2 vCorner;
in vec4 vColor;
out vec4 fragColor;
void main() {
    float r = length(vCorner);
    float a = clamp(1.0 - r, 0.0, 1.0);
    a = a * a * vColor.a;
    fragColor = vec4(vColor.rgb * a, a);
}
"""
}
