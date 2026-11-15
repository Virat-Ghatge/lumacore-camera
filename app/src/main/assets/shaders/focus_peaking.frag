#version 300 es
precision highp float;

in vec2 vTexCoord;
out vec4 FragColor;

uniform sampler2D uInputTexture;
uniform float uPeakingIntensity; // 0.0 (off) to 1.0 (full intensity)
uniform vec2 uTexelSize;         // 1.0 / vec2(width, height)
uniform float uThreshold;         // Edge gradient threshold (e.g. 0.18)

float getLuma(vec2 uv) {
    vec4 c = texture(uInputTexture, clamp(uv, 0.0, 1.0));
    return dot(c.rgb, vec3(0.299, 0.587, 0.114));
}

void main() {
    vec4 baseColor = texture(uInputTexture, vTexCoord);

    if (uPeakingIntensity <= 0.001) {
        FragColor = baseColor;
        return;
    }

    vec2 dx = vec2(uTexelSize.x > 0.0 ? uTexelSize.x : (1.0 / 1920.0), 0.0);
    vec2 dy = vec2(0.0, uTexelSize.y > 0.0 ? uTexelSize.y : (1.0 / 1080.0));

    // 3x3 Sobel neighborhood luminance sampling
    float l00 = getLuma(vTexCoord - dx - dy);
    float l01 = getLuma(vTexCoord - dy);
    float l02 = getLuma(vTexCoord + dx - dy);

    float l10 = getLuma(vTexCoord - dx);
    float l12 = getLuma(vTexCoord + dx);

    float l20 = getLuma(vTexCoord - dx + dy);
    float l21 = getLuma(vTexCoord + dy);
    float l22 = getLuma(vTexCoord + dx + dy);

    // Sobel kernels
    float gx = (-1.0 * l00) + (1.0 * l02) +
               (-2.0 * l10) + (2.0 * l12) +
               (-1.0 * l20) + (1.0 * l22);

    float gy = (-1.0 * l00) + (-2.0 * l01) + (-1.0 * l02) +
               ( 1.0 * l20) + ( 2.0 * l21) + ( 1.0 * l22);

    float edgeMagnitude = sqrt(gx * gx + gy * gy);
    float threshold = (uThreshold > 0.0) ? uThreshold : 0.18;

    if (edgeMagnitude > threshold) {
        // High frequency in-focus edge highlight: vibrant neon green vec3(0.0, 1.0, 0.25)
        vec3 neonGreen = vec3(0.0, 1.0, 0.25);
        float edgeAlpha = clamp((edgeMagnitude - threshold) * 5.0, 0.0, 1.0) * uPeakingIntensity;
        vec3 peakedColor = mix(baseColor.rgb, neonGreen, edgeAlpha);
        FragColor = vec4(clamp(peakedColor, 0.0, 1.0), baseColor.a);
    } else {
        FragColor = baseColor;
    }
}
