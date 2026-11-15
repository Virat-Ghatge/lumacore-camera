#pragma once

#include <cstdint>
#include <cstddef>

namespace lumacore {

/**
 * Camera hardware metadata defining sensor color calibration parameters.
 * Matches Android CameraCharacteristics / CaptureResult keys:
 * SENSOR_DYNAMIC_BLACK_LEVEL, SENSOR_INFO_WHITE_LEVEL,
 * COLOR_CORRECTION_GAINS, COLOR_CORRECTION_TRANSFORM,
 * SENSOR_SENSITIVITY, SENSOR_EXPOSURE_TIME, and lens shading falloff.
 */
struct CameraColorMetadata {
    float blackLevel[4]{0.0f, 0.0f, 0.0f, 0.0f};  // R, Gr, Gb, B offsets
    float whiteLevel{255.0f};                      // Dynamic white level
    float colorGains[4]{1.0f, 1.0f, 1.0f, 1.0f};  // R, G_even, G_odd, B gains
    float colorTransform[9]{                      // 3x3 row-major sensor-to-sRGB matrix
        1.0f, 0.0f, 0.0f,
        0.0f, 1.0f, 0.0f,
        0.0f, 0.0f, 1.0f
    };
    int32_t iso{100};
    int64_t exposureTimeNs{10000000LL};           // e.g. 10ms
    float radialVignetteGain{0.0f};                // Vignette compensation coefficient
};

/**
 * Converts planar YUV420 (Camera2 YUV_420_888) planes to packed RGBA8888.
 * Supports arbitrary row strides and pixel strides.
 */
void convertYuvPlanesToRgba(
    const uint8_t* yPlane,
    const uint8_t* uPlane,
    const uint8_t* vPlane,
    int width,
    int height,
    int yRowStride,
    int uvRowStride,
    int uvPixelStride,
    uint8_t* outRgba
);

/**
 * Applies physical sensor color calibration to RGBA8888 pixels:
 * 1. Black-level subtraction & white-level normalization to [0.0, 1.0] linear.
 * 2. Radial lens shading vignette compensation.
 * 3. White balance gains (channel multipliers).
 * 4. 3x3 sensor-to-sRGB color matrix transformation.
 * 5. Accurate sRGB transfer function (gamma curve).
 * 6. Quantization back to RGBA8888.
 */
void applySensorColorCorrection(
    uint8_t* rgbaPixels,
    int width,
    int height,
    const CameraColorMetadata& meta
);

/**
 * Full end-to-end calibrated pipeline:
 * Converts YUV420 planes to RGBA, followed by calibrated sensor color correction.
 */
void processCalibratedSensorPipeline(
    const uint8_t* yPlane,
    const uint8_t* uPlane,
    const uint8_t* vPlane,
    int width,
    int height,
    int yRowStride,
    int uvRowStride,
    int uvPixelStride,
    const CameraColorMetadata& meta,
    uint8_t* outRgba
);

} // namespace lumacore
