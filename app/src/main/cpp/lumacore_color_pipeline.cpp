#include "lumacore_color_pipeline.hpp"

#include <algorithm>
#include <cmath>
#include <vector>

namespace lumacore {

namespace {

/**
 * Standard sRGB gamma transfer function with linear toe.
 * v must be linear light intensity in [0.0, 1.0].
 */
[[nodiscard]] inline float linearToSrgbGamma(float v) noexcept {
    const float clamped = std::clamp(v, 0.0f, 1.0f);
    if (clamped <= 0.0031308f) {
        return 12.92f * clamped;
    }
    return 1.055f * std::pow(clamped, 1.0f / 2.4f) - 0.055f;
}

/**
 * Fast clamp to uint8_t [0, 255].
 */
[[nodiscard]] inline uint8_t clampToUint8(float val) noexcept {
    if (val <= 0.0f) {
        return static_cast<uint8_t>(0);
    }
    if (val >= 255.0f) {
        return static_cast<uint8_t>(255);
    }
    return static_cast<uint8_t>(std::round(val));
}

} // anonymous namespace

void convertYuvPlanesToRgba(
    const uint8_t* const yPlane,
    const uint8_t* const uPlane,
    const uint8_t* const vPlane,
    const int width,
    const int height,
    const int yRowStride,
    const int uvRowStride,
    const int uvPixelStride,
    uint8_t* const outRgba
) {
    if (yPlane == nullptr || uPlane == nullptr || vPlane == nullptr || outRgba == nullptr) {
        return;
    }
    if (width <= 0 || height <= 0 || yRowStride < width) {
        return;
    }

    // ITU-R BT.601 full-range conversion constants:
    // R = Y + 1.402 * (V - 128)
    // G = Y - 0.344136 * (U - 128) - 0.714136 * (V - 128)
    // B = Y + 1.772 * (U - 128)
    constexpr float rVCoeff = 1.402f;
    constexpr float gUCoeff = 0.344136f;
    constexpr float gVCoeff = 0.714136f;
    constexpr float bUCoeff = 1.772f;

    for (int y = 0; y < height; ++y) {
        const uint8_t* const yRow = yPlane + y * yRowStride;
        const int uvY = y / 2;
        const uint8_t* const uRow = uPlane + uvY * uvRowStride;
        const uint8_t* const vRow = vPlane + uvY * uvRowStride;
        uint8_t* const outRow = outRgba + (y * width * 4);

        for (int x = 0; x < width; ++x) {
            const int uvX = x / 2;
            const int uvIdx = uvX * uvPixelStride;

            const float yVal = static_cast<float>(yRow[x]);
            const float uDiff = static_cast<float>(uRow[uvIdx]) - 128.0f;
            const float vDiff = static_cast<float>(vRow[uvIdx]) - 128.0f;

            const float r = yVal + (rVCoeff * vDiff);
            const float g = yVal - (gUCoeff * uDiff) - (gVCoeff * vDiff);
            const float b = yVal + (bUCoeff * uDiff);

            const int pixelIdx = x * 4;
            outRow[pixelIdx + 0] = clampToUint8(r);
            outRow[pixelIdx + 1] = clampToUint8(g);
            outRow[pixelIdx + 2] = clampToUint8(b);
            outRow[pixelIdx + 3] = static_cast<uint8_t>(255);
        }
    }
}

void applySensorColorCorrection(
    uint8_t* const rgbaPixels,
    const int width,
    const int height,
    const CameraColorMetadata& meta
) {
    if (rgbaPixels == nullptr || width <= 0 || height <= 0) {
        return;
    }

    // 1. Black level parameters per channel:
    // Sensor layout: R, Gr, Gb, B
    const float blackR = meta.blackLevel[0];
    const float blackG = (meta.blackLevel[1] + meta.blackLevel[2]) * 0.5f;
    const float blackB = meta.blackLevel[3];

    // Dynamic white level normalization range:
    const float denomR = std::max(meta.whiteLevel - blackR, 1.0f);
    const float denomG = std::max(meta.whiteLevel - blackG, 1.0f);
    const float denomB = std::max(meta.whiteLevel - blackB, 1.0f);

    const float invDenomR = 1.0f / denomR;
    const float invDenomG = 1.0f / denomG;
    const float invDenomB = 1.0f / denomB;

    // 2. White balance gains:
    const float gainR = meta.colorGains[0];
    const float gainG = (meta.colorGains[1] + meta.colorGains[2]) * 0.5f;
    const float gainB = meta.colorGains[3];

    // 3. 3x3 Color transform matrix (sensor to linear sRGB):
    const float m00 = meta.colorTransform[0];
    const float m01 = meta.colorTransform[1];
    const float m02 = meta.colorTransform[2];
    const float m10 = meta.colorTransform[3];
    const float m11 = meta.colorTransform[4];
    const float m12 = meta.colorTransform[5];
    const float m20 = meta.colorTransform[6];
    const float m21 = meta.colorTransform[7];
    const float m22 = meta.colorTransform[8];

    const float invWidth = 1.0f / static_cast<float>(width);
    const float invHeight = 1.0f / static_cast<float>(height);
    const float vignetteGain = meta.radialVignetteGain;

    for (int y = 0; y < height; ++y) {
        const float normY = (static_cast<float>(y) + 0.5f) * invHeight;
        const float dy = normY - 0.5f;
        const float dySq = dy * dy;

        uint8_t* const rowPtr = rgbaPixels + (y * width * 4);

        for (int x = 0; x < width; ++x) {
            const int px = x * 4;
            const float inR = static_cast<float>(rowPtr[px + 0]);
            const float inG = static_cast<float>(rowPtr[px + 1]);
            const float inB = static_cast<float>(rowPtr[px + 2]);

            // Step 1: Normalize to [0.0, 1.0] linear light
            float linR = std::clamp((inR - blackR) * invDenomR, 0.0f, 1.0f);
            float linG = std::clamp((inG - blackG) * invDenomG, 0.0f, 1.0f);
            float linB = std::clamp((inB - blackB) * invDenomB, 0.0f, 1.0f);

            // Step 2: Radial lens shading vignette compensation
            const float normX = (static_cast<float>(x) + 0.5f) * invWidth;
            const float dx = normX - 0.5f;
            const float distSq = dx * dx + dySq;
            const float vignette = 1.0f + (vignetteGain * distSq);

            linR *= vignette;
            linG *= vignette;
            linB *= vignette;

            // Step 3: White balance gains
            linR *= gainR;
            linG *= gainG;
            linB *= gainB;

            // Step 4: Multiply by 3x3 sensor-to-sRGB matrix
            const float transR = m00 * linR + m01 * linG + m02 * linB;
            const float transG = m10 * linR + m11 * linG + m12 * linB;
            const float transB = m20 * linR + m21 * linG + m22 * linB;

            // Step 5: sRGB gamma tone curve & quantize to uint8_t
            const float srgbR = linearToSrgbGamma(transR);
            const float srgbG = linearToSrgbGamma(transG);
            const float srgbB = linearToSrgbGamma(transB);

            rowPtr[px + 0] = clampToUint8(srgbR * 255.0f);
            rowPtr[px + 1] = clampToUint8(srgbG * 255.0f);
            rowPtr[px + 2] = clampToUint8(srgbB * 255.0f);
            rowPtr[px + 3] = static_cast<uint8_t>(255);
        }
    }
}

void processCalibratedSensorPipeline(
    const uint8_t* const yPlane,
    const uint8_t* const uPlane,
    const uint8_t* const vPlane,
    const int width,
    const int height,
    const int yRowStride,
    const int uvRowStride,
    const int uvPixelStride,
    const CameraColorMetadata& meta,
    uint8_t* const outRgba
) {
    if (outRgba == nullptr || width <= 0 || height <= 0) {
        return;
    }

    // Step 1: Convert YUV to intermediate RGBA
    convertYuvPlanesToRgba(
        yPlane,
        uPlane,
        vPlane,
        width,
        height,
        yRowStride,
        uvRowStride,
        uvPixelStride,
        outRgba
    );

    // Step 2: Apply calibrated sensor color correction in-place
    applySensorColorCorrection(outRgba, width, height, meta);
}

} // namespace lumacore
