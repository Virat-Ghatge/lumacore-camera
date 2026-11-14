#include "lumacore_bilateral_slice.hpp"

#include <algorithm>
#include <cmath>
#include <thread>
#include <vector>

namespace lumacore {

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
) {
    if (inPixels == nullptr || outPixels == nullptr || grid == nullptr ||
        width <= 0 || height <= 0 || gridDepth <= 0 || gridH <= 0 || gridW <= 0 ||
        (pixelStride != 3 && pixelStride != 4)) {
        return;
    }

    const int32_t depthStride = gridH * gridW;
    const int32_t channelStride = gridDepth * depthStride;

    const float invW = 1.0f / static_cast<float>(width);
    const float invH = 1.0f / static_cast<float>(height);
    const float scaleW = static_cast<float>(gridW);
    const float scaleH = static_cast<float>(gridH);
    const float scaleD = static_cast<float>(gridDepth);

    const uint32_t hwThreads = std::thread::hardware_concurrency();
    const uint32_t numThreads = std::clamp(hwThreads, 1u, 8u);

    auto sliceRowRange = [&](int32_t startY, int32_t endY) {
        for (int32_t y = startY; y < endY; ++y) {
            const float v = (static_cast<float>(y) + 0.5f) * invH;
            const float gy = v * scaleH - 0.5f;
            const int32_t y0_raw = static_cast<int32_t>(std::floor(gy));
            const float fy_raw = gy - static_cast<float>(y0_raw);
            const float fy = std::clamp(fy_raw, 0.0f, 1.0f);
            const int32_t y0 = std::clamp(y0_raw, 0, gridH - 1);
            const int32_t y1 = std::clamp(y0_raw + 1, 0, gridH - 1);
            const float fy0 = 1.0f - fy;
            const float fy1 = fy;

            const size_t rowOffset = static_cast<size_t>(y) * static_cast<size_t>(width) * static_cast<size_t>(pixelStride);

            for (int32_t x = 0; x < width; ++x) {
                const size_t pxIdx = rowOffset + static_cast<size_t>(x) * static_cast<size_t>(pixelStride);

                const float rNorm = static_cast<float>(inPixels[pxIdx + 0]) * (1.0f / 255.0f);
                const float gNorm = static_cast<float>(inPixels[pxIdx + 1]) * (1.0f / 255.0f);
                const float bNorm = static_cast<float>(inPixels[pxIdx + 2]) * (1.0f / 255.0f);

                const float luma = std::clamp(
                    0.299f * rNorm + 0.587f * gNorm + 0.114f * bNorm,
                    0.0f,
                    1.0f
                );

                const float u = (static_cast<float>(x) + 0.5f) * invW;
                const float gx = u * scaleW - 0.5f;
                const int32_t x0_raw = static_cast<int32_t>(std::floor(gx));
                const float fx_raw = gx - static_cast<float>(x0_raw);
                const float fx = std::clamp(fx_raw, 0.0f, 1.0f);
                const int32_t x0 = std::clamp(x0_raw, 0, gridW - 1);
                const int32_t x1 = std::clamp(x0_raw + 1, 0, gridW - 1);
                const float fx0 = 1.0f - fx;
                const float fx1 = fx;

                const float gz = luma * scaleD - 0.5f;
                const int32_t z0_raw = static_cast<int32_t>(std::floor(gz));
                const float fz_raw = gz - static_cast<float>(z0_raw);
                const float fz = std::clamp(fz_raw, 0.0f, 1.0f);
                const int32_t z0 = std::clamp(z0_raw, 0, gridDepth - 1);
                const int32_t z1 = std::clamp(z0_raw + 1, 0, gridDepth - 1);
                const float fz0 = 1.0f - fz;
                const float fz1 = fz;

                const float w000 = fx0 * fy0 * fz0;
                const float w100 = fx1 * fy0 * fz0;
                const float w010 = fx0 * fy1 * fz0;
                const float w110 = fx1 * fy1 * fz0;
                const float w001 = fx0 * fy0 * fz1;
                const float w101 = fx1 * fy0 * fz1;
                const float w011 = fx0 * fy1 * fz1;
                const float w111 = fx1 * fy1 * fz1;

                const int32_t idx000 = z0 * depthStride + y0 * gridW + x0;
                const int32_t idx100 = z0 * depthStride + y0 * gridW + x1;
                const int32_t idx010 = z0 * depthStride + y1 * gridW + x0;
                const int32_t idx110 = z0 * depthStride + y1 * gridW + x1;
                const int32_t idx001 = z1 * depthStride + y0 * gridW + x0;
                const int32_t idx101 = z1 * depthStride + y0 * gridW + x1;
                const int32_t idx011 = z1 * depthStride + y1 * gridW + x0;
                const int32_t idx111 = z1 * depthStride + y1 * gridW + x1;

                float aff[12];
                for (int32_t c = 0; c < 12; ++c) {
                    const float* g = grid + c * channelStride;
                    aff[c] = w000 * g[idx000] + w100 * g[idx100] +
                             w010 * g[idx010] + w110 * g[idx110] +
                             w001 * g[idx001] + w101 * g[idx101] +
                             w011 * g[idx011] + w111 * g[idx111];
                }

                const float outR = aff[0] * rNorm + aff[1] * gNorm + aff[2] * bNorm + aff[3];
                const float outG = aff[4] * rNorm + aff[5] * gNorm + aff[6] * bNorm + aff[7];
                const float outB = aff[8] * rNorm + aff[9] * gNorm + aff[10] * bNorm + aff[11];

                outPixels[pxIdx + 0] = static_cast<uint8_t>(std::clamp(outR * 255.0f + 0.5f, 0.0f, 255.0f));
                outPixels[pxIdx + 1] = static_cast<uint8_t>(std::clamp(outG * 255.0f + 0.5f, 0.0f, 255.0f));
                outPixels[pxIdx + 2] = static_cast<uint8_t>(std::clamp(outB * 255.0f + 0.5f, 0.0f, 255.0f));

                if (pixelStride == 4) {
                    outPixels[pxIdx + 3] = inPixels[pxIdx + 3];
                }
            }
        }
    };

    if (numThreads <= 1 || height < 32) {
        sliceRowRange(0, height);
    } else {
        std::vector<std::thread> workers;
        workers.reserve(numThreads);
        for (uint32_t t = 0; t < numThreads; ++t) {
            const int32_t startY = static_cast<int32_t>((static_cast<uint64_t>(height) * t) / numThreads);
            const int32_t endY = static_cast<int32_t>((static_cast<uint64_t>(height) * (t + 1)) / numThreads);
            workers.emplace_back([=]() {
                sliceRowRange(startY, endY);
            });
        }
        for (auto& worker : workers) {
            if (worker.joinable()) {
                worker.join();
            }
        }
    }
}

} // namespace lumacore
