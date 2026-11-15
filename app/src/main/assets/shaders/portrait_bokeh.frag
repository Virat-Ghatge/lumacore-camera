#version 300 es
precision highp float;

in vec2 vTexCoord;
out vec4 FragColor;

uniform sampler2D uInputTexture;
uniform float uApertureRadius; // f/1.4 (large blur ~0.035) to f/16 (pinhole ~0.001)
uniform vec2 uFocusPoint;      // normalized focal center (default 0.5, 0.5)
uniform float uAspect;          // width / height aspect ratio for circular bokeh discs

// Golden angle in radians
const float GOLDEN_ANGLE = 2.39996323;
const int NUM_SAMPLES = 24;

void main() {
    vec4 baseColor = texture(uInputTexture, vTexCoord);

    float aspect = (uAspect > 0.0) ? uAspect : 1.0;
    vec2 offsetFromFocus = vTexCoord - uFocusPoint;
    vec2 aspectCorrectedOffset = vec2(offsetFromFocus.x * aspect, offsetFromFocus.y);
    float distFromFocus = length(aspectCorrectedOffset);

    // Depth falloff mask: in-focus zone around uFocusPoint with smooth transition outward
    float depthMask = smoothstep(0.12, 0.45, distFromFocus);
    float effectiveRadius = max(0.0, uApertureRadius * depthMask);

    if (effectiveRadius <= 0.0008) {
        FragColor = baseColor;
        return;
    }

    vec3 bokehAccum = vec3(0.0);
    float totalWeight = 0.0;

    for (int i = 0; i < NUM_SAMPLES; ++i) {
        float fi = float(i);
        float theta = fi * GOLDEN_ANGLE;
        float radius = sqrt((fi + 1.0) / float(NUM_SAMPLES)) * effectiveRadius;

        vec2 sampleOffset = vec2(cos(theta), sin(theta)) * radius;
        sampleOffset.x /= aspect; // restore texture coordinate space

        vec2 sampleUv = clamp(vTexCoord + sampleOffset, 0.0, 1.0);
        vec4 sampleColor = texture(uInputTexture, sampleUv);

        // Specular highlight boost for photographic bokeh disc rendering
        float sampleLuma = dot(sampleColor.rgb, vec3(0.299, 0.587, 0.114));
        float sampleWeight = 1.0 + pow(sampleLuma, 3.5) * 3.0;

        bokehAccum += sampleColor.rgb * sampleWeight;
        totalWeight += sampleWeight;
    }

    vec3 blurredColor = bokehAccum / max(totalWeight, 0.0001);
    vec3 finalColor = mix(baseColor.rgb, blurredColor, min(1.0, depthMask * 1.25));

    FragColor = vec4(clamp(finalColor, 0.0, 1.0), baseColor.a);
}
