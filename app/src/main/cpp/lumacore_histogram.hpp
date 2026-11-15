#pragma once

#include <cstdint>
#include <cstddef>

namespace lumacore {

/**
 * Computes RGBA and Luminance channel histograms for an 8-bit RGBA image buffer.
 *
 * @param rgba Pointer to contiguous RGBA8888 pixel buffer (width * height * 4 bytes).
 * @param width Image width in pixels.
 * @param height Image height in pixels.
 * @param outR Output histogram array for Red channel (must have at least `bins` elements).
 * @param outG Output histogram array for Green channel (must have at least `bins` elements).
 * @param outB Output histogram array for Blue channel (must have at least `bins` elements).
 * @param outLuma Output histogram array for Luminance channel (must have at least `bins` elements).
 * @param bins Number of histogram bins (typically 256 for 8-bit image data).
 */
void computeRgbaHistogram(
    const uint8_t* rgba,
    int width,
    int height,
    int32_t* outR,
    int32_t* outG,
    int32_t* outB,
    int32_t* outLuma,
    int bins = 256
);

} // namespace lumacore
