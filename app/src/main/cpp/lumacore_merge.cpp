#include "lumacore_merge.hpp"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <vector>

namespace lumacore {

namespace {

inline float sampleBilinear(
    const uint8_t* plane,
    int32_t width,
    int32_t height,
    int32_t rowStride,
    int32_t pixelStride,
    float x,
    float y
) noexcept {
    if (plane == nullptr ||
        x < 0.0f || x > static_cast<float>(width - 1) ||
        y < 0.0f || y > static_cast<float>(height - 1)) {
        return -1.0f;
    }

    const int32_t x0 = static_cast<int32_t>(x);
    const int32_t y0 = static_cast<int32_t>(y);
    const int32_t x1 = std::min(x0 + 1, width - 1);
    const int32_t y1 = std::min(y0 + 1, height - 1);

    const float fx = x - static_cast<float>(x0);
    const float fy = y - static_cast<float>(y0);

    const float p00 = static_cast<float>(plane[y0 * rowStride + x0 * pixelStride]);
    const float p10 = static_cast<float>(plane[y0 * rowStride + x1 * pixelStride]);
    const float p01 = static_cast<float>(plane[y1 * rowStride + x0 * pixelStride]);
    const float p11 = static_cast<float>(plane[y1 * rowStride + x1 * pixelStride]);

    return (1.0f - fx) * (1.0f - fy) * p00 +
           fx * (1.0f - fy) * p10 +
           (1.0f - fx) * fy * p01 +
           fx * fy * p11;
}

} // anonymous namespace

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
    float noiseGainA,
    float noiseFloorB
) {
    MergeStats stats{};
    stats.mergedFrameCount = 1 + static_cast<int32_t>(targetFrames.size());

    if (refFrame.yData == nullptr || outY == nullptr || width <= 0 || height <= 0) {
        return stats;
    }

    const auto startTime = std::chrono::steady_clock::now();

    const size_t totalPixels = static_cast<size_t>(width) * static_cast<size_t>(height);
    const size_t numTargets = targetFrames.size();

    // Intermediate weight cache for UV plane averaging
    // targetWeights[frameIndex][pixelIndex]
    std::vector<std::vector<float>> targetWeights(numTargets, std::vector<float>(totalPixels, 0.0f));

    uint64_t candidatePixels = 0;
    uint64_t ghostRejected = 0;

    // --- Phase 1: Merge Y Plane (Luma) ---
    for (int32_t y = 0; y < height; ++y) {
        const uint8_t* refRow = refFrame.yData + static_cast<size_t>(y) * static_cast<size_t>(refFrame.yRowStride);
        uint8_t* outRow = outY + static_cast<size_t>(y) * static_cast<size_t>(outYRowStride);

        for (int32_t x = 0; x < width; ++x) {
            const float refVal = static_cast<float>(refRow[x]);
            float accY = refVal * 1.0f;
            float totalWeightY = 1.0f;

            // Shot noise variance: sigma^2 = max(1.0, a * I_ref + b)
            const float sigmaSq = std::max(1.0f, noiseGainA * refVal + noiseFloorB);
            const float sigma = std::sqrt(sigmaSq);
            const float fourSigma = 4.0f * sigma;
            const float threeSigmaInv = 1.0f / (3.0f * sigma);

            const size_t pixelIdx = static_cast<size_t>(y) * static_cast<size_t>(width) + static_cast<size_t>(x);

            for (size_t k = 0; k < numTargets; ++k) {
                const auto& tgt = targetFrames[k];
                float tx = static_cast<float>(x);
                float ty = static_cast<float>(y);
                tgt.homography.transform(static_cast<float>(x), static_cast<float>(y), &tx, &ty);

                const float warpedVal = sampleBilinear(
                    tgt.planes.yData,
                    width,
                    height,
                    tgt.planes.yRowStride,
                    1,
                    tx,
                    ty
                );

                if (warpedVal < 0.0f) {
                    targetWeights[k][pixelIdx] = 0.0f;
                    continue;
                }

                candidatePixels++;
                const float delta = std::abs(warpedVal - refVal);

                if (delta > fourSigma) {
                    // Ghost rejection: motion delta exceeds 4 sigma
                    ghostRejected++;
                    targetWeights[k][pixelIdx] = 0.0f;
                } else {
                    const float normDiff = delta * threeSigmaInv;
                    const float weight = std::exp(-0.5f * normDiff * normDiff);
                    targetWeights[k][pixelIdx] = weight;
                    accY += weight * warpedVal;
                    totalWeightY += weight;
                }
            }

            const int32_t mergedInt = static_cast<int32_t>(std::round(accY / totalWeightY));
            outRow[x] = static_cast<uint8_t>(std::clamp(mergedInt, 0, 255));
        }
    }

    // --- Phase 2: Merge UV Planes (Chroma) ---
    if (refFrame.uData != nullptr && refFrame.vData != nullptr &&
        outU != nullptr && outV != nullptr) {

        const int32_t uvWidth = width / 2;
        const int32_t uvHeight = height / 2;

        for (int32_t cy = 0; cy < uvHeight; ++cy) {
            const int32_t ry0 = cy * 2;
            const int32_t ry1 = std::min(ry0 + 1, height - 1);

            for (int32_t cx = 0; cx < uvWidth; ++cx) {
                const int32_t rx0 = cx * 2;
                const int32_t rx1 = std::min(rx0 + 1, width - 1);

                const size_t refUOffset = static_cast<size_t>(cy) * static_cast<size_t>(refFrame.uvRowStride) +
                                          static_cast<size_t>(cx) * static_cast<size_t>(refFrame.uvPixelStride);
                const size_t refVOffset = static_cast<size_t>(cy) * static_cast<size_t>(refFrame.uvRowStride) +
                                          static_cast<size_t>(cx) * static_cast<size_t>(refFrame.uvPixelStride);

                const float refUVal = static_cast<float>(refFrame.uData[refUOffset]);
                const float refVVal = static_cast<float>(refFrame.vData[refVOffset]);

                float accU = refUVal * 1.0f;
                float accV = refVVal * 1.0f;
                float totalWeightUV = 1.0f;

                const size_t idx00 = static_cast<size_t>(ry0) * static_cast<size_t>(width) + static_cast<size_t>(rx0);
                const size_t idx10 = static_cast<size_t>(ry0) * static_cast<size_t>(width) + static_cast<size_t>(rx1);
                const size_t idx01 = static_cast<size_t>(ry1) * static_cast<size_t>(width) + static_cast<size_t>(rx0);
                const size_t idx11 = static_cast<size_t>(ry1) * static_cast<size_t>(width) + static_cast<size_t>(rx1);

                for (size_t k = 0; k < numTargets; ++k) {
                    const auto& tgt = targetFrames[k];
                    // Average the 2x2 luma block weights for chroma
                    const float wAvg = 0.25f * (
                        targetWeights[k][idx00] +
                        targetWeights[k][idx10] +
                        targetWeights[k][idx01] +
                        targetWeights[k][idx11]
                    );

                    if (wAvg < 1e-4f) {
                        continue;
                    }

                    // Warp center of 2x2 quad
                    float tpx = static_cast<float>(rx0) + 0.5f;
                    float tpy = static_cast<float>(ry0) + 0.5f;
                    tgt.homography.transform(tpx, tpy, &tpx, &tpy);

                    const float tcx = tpx * 0.5f;
                    const float tcy = tpy * 0.5f;

                    const float warpedU = sampleBilinear(
                        tgt.planes.uData,
                        uvWidth,
                        uvHeight,
                        tgt.planes.uvRowStride,
                        tgt.planes.uvPixelStride,
                        tcx,
                        tcy
                    );

                    const float warpedV = sampleBilinear(
                        tgt.planes.vData,
                        uvWidth,
                        uvHeight,
                        tgt.planes.uvRowStride,
                        tgt.planes.uvPixelStride,
                        tcx,
                        tcy
                    );

                    if (warpedU >= 0.0f && warpedV >= 0.0f) {
                        accU += wAvg * warpedU;
                        accV += wAvg * warpedV;
                        totalWeightUV += wAvg;
                    }
                }

                const size_t outUOffset = static_cast<size_t>(cy) * static_cast<size_t>(outUvRowStride) +
                                          static_cast<size_t>(cx) * static_cast<size_t>(outUvPixelStride);
                const size_t outVOffset = static_cast<size_t>(cy) * static_cast<size_t>(outUvRowStride) +
                                          static_cast<size_t>(cx) * static_cast<size_t>(outUvPixelStride);

                outU[outUOffset] = static_cast<uint8_t>(std::clamp(static_cast<int32_t>(std::round(accU / totalWeightUV)), 0, 255));
                outV[outVOffset] = static_cast<uint8_t>(std::clamp(static_cast<int32_t>(std::round(accV / totalWeightUV)), 0, 255));
            }
        }
    }

    const auto endTime = std::chrono::steady_clock::now();
    stats.mergeMs = std::chrono::duration<float, std::milli>(endTime - startTime).count();
    stats.ghostRejectionRatio = (candidatePixels > 0)
        ? (static_cast<float>(ghostRejected) / static_cast<float>(candidatePixels))
        : 0.0f;

    return stats;
}

} // namespace lumacore
