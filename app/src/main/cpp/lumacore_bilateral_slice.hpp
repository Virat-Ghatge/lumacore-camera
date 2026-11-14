#pragma once

#include <cstdint>
#include <cstddef>

namespace lumacore {

/**
 * High-performance C++20 bilateral grid slicing accelerator.
 * Evaluates local 3x4 affine color transforms for every pixel via trilinear
 * interpolation across spatial (X, Y) and range (Luma) coordinates.
 *
 * @param inPixels    Pointer to input interleaved RGB/RGBA bytes.
 * @param width       Image width in pixels.
 * @param height      Image height in pixels.
 * @param pixelStride Number of bytes per pixel (3 for RGB, 4 for RGBA).
 * @param grid        Pointer to 12-channel bilateral grid tensor [12, gridDepth, gridH, gridW].
 * @param gridDepth   Depth dimension of bilateral grid (e.g., 8).
 * @param gridH       Height dimension of bilateral grid (e.g., 16).
 * @param gridW       Width dimension of bilateral grid (e.g., 16).
 * @param outPixels   Pointer to output interleaved RGB/RGBA buffer.
 */
void sliceBilateralGrid(
    const uint8_t* inPixels,
    int32_t width,
    int32_t height,
    int32_t pixelStride,
    const float* grid,
    int32_t gridDepth,
    int32_t gridH,
    int32_t gridW,
    uint8_t* outPixels
);

/**
 * Standard 3-channel RGB convenience wrapper.
 */
inline void sliceBilateralGrid(
    const uint8_t* inRgb,
    int width,
    int height,
    const float* grid,
    int gridDepth,
    int gridH,
    int gridW,
    uint8_t* outRgb
) {
    sliceBilateralGrid(
        inRgb,
        static_cast<int32_t>(width),
        static_cast<int32_t>(height),
        3,
        grid,
        static_cast<int32_t>(gridDepth),
        static_cast<int32_t>(gridH),
        static_cast<int32_t>(gridW),
        outRgb
    );
}

} // namespace lumacore
