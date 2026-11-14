#pragma once

#include "lumacore_homography.hpp"

#include <cstdint>
#include <vector>

namespace lumacore {

struct MergeStats {
    float alignmentMs{0.0f};
    float mergeMs{0.0f};
    float ghostRejectionRatio{0.0f};
    int32_t mergedFrameCount{1};
};

struct FramePlaneView {
    const uint8_t* yData{nullptr};
    const uint8_t* uData{nullptr};
    const uint8_t* vData{nullptr};
    int32_t yRowStride{0};
    int32_t uvRowStride{0};
    int32_t uvPixelStride{1};
};

struct TargetFrameAlignment {
    FramePlaneView planes;
    Homography3x3 homography; // Maps reference coords (rx, ry) -> target coords (tx, ty)
};

/**
 * Executes ghost-rejection temporal merging on aligned burst frames.
 * Reference frame receives baseline weight 1.0. Target frames are warped by homography
 * and weighted pixel-by-pixel using shot-noise thresholding:
 *   sigma^2 = max(1.0, noiseGainA * I_ref + noiseFloorB)
 *   delta = |I_warped - I_ref|
 *   w = (delta > 4*sigma) ? 0 : exp(-0.5 * (delta / (3*sigma))^2)
 *
 * @param refFrame Reference frame plane pointers and strides.
 * @param targetFrames Vector of target frames with plane pointers and refined homographies.
 * @param width Image width in pixels.
 * @param height Image height in pixels.
 * @param outY Destination buffer for merged Y plane.
 * @param outU Destination buffer for merged U plane.
 * @param outV Destination buffer for merged V plane.
 * @param outYRowStride Output Y row stride.
 * @param outUvRowStride Output UV row stride.
 * @param outUvPixelStride Output UV pixel stride (1 for planar, 2 for NV21).
 * @param noiseGainA Shot noise slope (default 0.5f).
 * @param noiseFloorB Read noise floor (default 4.0f).
 * @return MergeStats containing alignment/merge latency, ghost rejection ratio, and count.
 */
MergeStats mergeBurstFrames(
    const FramePlaneView& refFrame,
    const std::vector<TargetFrameAlignment>& targetFrames,
    int32_t width,
    int32_t height,
    uint8_t* outY,
    uint8_t* outU,
    uint8_t* outV,
    int32_t outYRowStride,
    int32_t outUvRowStride,
    int32_t outUvPixelStride,
    float noiseGainA = 0.5f,
    float noiseFloorB = 4.0f
);

} // namespace lumacore
