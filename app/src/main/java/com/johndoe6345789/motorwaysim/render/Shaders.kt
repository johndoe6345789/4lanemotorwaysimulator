package com.johndoe6345789.motorwaysim.render

/** GLSL ES 3.00 shaders (also valid WebGL 2). */
object Shaders {
    /** Lit, vertex-coloured geometry with sun + sky lighting and distance fog. */
    const val LIT_VS = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec4 aColor;
uniform mat4 uViewProj;
uniform mat4 uModel;
uniform vec4 uTint;
out vec3 vNormal;
out vec4 vColor;
out vec3 vWorld;
void main() {
    vec4 w = uModel * vec4(aPos, 1.0);
    vWorld = w.xyz;
    vNormal = mat3(uModel) * aNormal;
    vColor = vec4(aColor.rgb * uTint.rgb, aColor.a);
    gl_Position = uViewProj * w;
}
"""

    const val LIT_FS = """#version 300 es
precision highp float;
in vec3 vNormal;
in vec4 vColor;
in vec3 vWorld;
uniform vec3 uSun;
uniform vec3 uCam;
uniform vec3 uFogColor;
uniform vec2 uFog;
uniform vec2 uOrigin;
uniform float uGround;
out vec4 fragColor;
void main() {
    vec3 base = vColor.rgb;
    if (uGround > 0.5) {
        vec2 p = vWorld.xy + uOrigin;
        float stripe = mod(floor(p.y / 14.0), 2.0);
        float field = mod(floor(p.x / 170.0) + floor(p.y / 230.0), 2.0);
        base *= (0.95 + 0.05 * stripe) * (0.92 + 0.08 * field);
    }
    vec3 n = normalize(vNormal);
    float diff = max(dot(n, uSun), 0.0);
    float sky = 0.5 + 0.5 * n.z;
    vec3 lit = base * (0.30 + 0.28 * sky + 0.60 * diff);
    vec3 col = mix(lit, base, vColor.a);
    float d = length(vWorld - uCam);
    float f = clamp((d - uFog.x) / (uFog.y - uFog.x), 0.0, 1.0);
    fragColor = vec4(mix(col, uFogColor, f * f * (3.0 - 2.0 * f)), 1.0);
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
