#pragma once

#include <cstdint>
#include <vector>

namespace lumacore {

/**
 * Estimates fractional sub-pixel shift (dx, dy) for target frame relative to reference frame.
 * Uses center-crop normalized cross-correlation / SAD with parabolic peak interpolation.
 *
 * @param refY Reference frame luma plane.
 * @param tgtY Target frame luma plane.
 * @param width Frame width.
 * @param height Frame height.
 * @param stride Row stride in bytes.
 * @param[out] outDx Estimated horizontal sub-pixel displacement.
 * @param[out] outDy Estimated vertical sub-pixel displacement.
 * @return true if displacement was successfully calculated.
 */
bool estimateSubPixelShift(
    const uint8_t* refY,
    const uint8_t* tgtY,
    int width,
    int height,
    int stride,
    float* outDx,
    float* outDy
);

/**
 * 2x Super-Resolution Sub-Pixel Burst Merge:
 * Takes 4-6 center-crop frames with sub-pixel micro-displacements.
 * Computes fractional sub-pixel shift (dx, dy) for each frame relative to reference frame.
 * Accumulates samples onto a 2x higher resolution destination grid (e.g. from center crop W/2 x H/2
 * up to W x H, or from W x H up to 2W x 2H) using sub-pixel kernel splatting (bilateral / Lanczos kernel).
 * Normalizes by accumulated kernel weights to synthesize clean high-frequency optical details.
 *
 * @param framesY Array of luma frame pointers (length count).
 * @param count Number of burst frames (typically 4-6).
 * @param inWidth Width of input frames.
 * @param inHeight Height of input frames.
 * @param inStride Row stride of input frames.
 * @param[out] out2xY Destination buffer for 2x super-resolved luma grid.
 * @param outWidth Destination grid width (typically inWidth * 2).
 * @param outHeight Destination grid height (typically inHeight * 2).
 * @param outStride Row stride of destination buffer.
 * @return true on successful reconstruction, false otherwise.
 */
bool mergeSuperRes2x(
    const uint8_t* const* framesY,
    int count,
    int inWidth,
    int inHeight,
    int inStride,
    uint8_t* out2xY,
    int outWidth,
    int outHeight,
    int outStride
);

} // namespace lumacore
