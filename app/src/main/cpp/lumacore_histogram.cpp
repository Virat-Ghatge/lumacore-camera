#include "lumacore_histogram.hpp"

#include <cstring>
#include <algorithm>

namespace lumacore {

void computeRgbaHistogram(
    const uint8_t* rgba,
    int width,
    int height,
    int32_t* outR,
    int32_t* outG,
    int32_t* outB,
    int32_t* outLuma,
    int bins
) {
    if (rgba == nullptr || width <= 0 || height <= 0 || bins <= 0 ||
        outR == nullptr || outG == nullptr || outB == nullptr || outLuma == nullptr) {
        return;
    }

    std::memset(outR, 0, static_cast<size_t>(bins) * sizeof(int32_t));
    std::memset(outG, 0, static_cast<size_t>(bins) * sizeof(int32_t));
    std::memset(outB, 0, static_cast<size_t>(bins) * sizeof(int32_t));
    std::memset(outLuma, 0, static_cast<size_t>(bins) * sizeof(int32_t));

    const size_t totalPixels = static_cast<size_t>(width) * static_cast<size_t>(height);
    const uint8_t* ptr = rgba;

    if (bins == 256) {
        for (size_t i = 0; i < totalPixels; ++i) {
            const uint8_t r = ptr[0];
            const uint8_t g = ptr[1];
            const uint8_t b = ptr[2];
            // Integer BT.601 / BT.709 luma calculation
            const uint8_t luma = static_cast<uint8_t>(
                (299u * static_cast<uint32_t>(r) +
                 587u * static_cast<uint32_t>(g) +
                 114u * static_cast<uint32_t>(b) + 500u) / 1000u
            );

            outR[r]++;
            outG[g]++;
            outB[b]++;
            outLuma[luma]++;

            ptr += 4;
        }
    } else {
        const int32_t maxBin = bins - 1;
        for (size_t i = 0; i < totalPixels; ++i) {
            const uint8_t r = ptr[0];
            const uint8_t g = ptr[1];
            const uint8_t b = ptr[2];
            const uint8_t luma = static_cast<uint8_t>(
                (299u * static_cast<uint32_t>(r) +
                 587u * static_cast<uint32_t>(g) +
                 114u * static_cast<uint32_t>(b) + 500u) / 1000u
            );

            const int32_t rBin = std::clamp((static_cast<int32_t>(r) * bins) / 256, 0, maxBin);
            const int32_t gBin = std::clamp((static_cast<int32_t>(g) * bins) / 256, 0, maxBin);
            const int32_t bBin = std::clamp((static_cast<int32_t>(b) * bins) / 256, 0, maxBin);
            const int32_t lumaBin = std::clamp((static_cast<int32_t>(luma) * bins) / 256, 0, maxBin);

            outR[rBin]++;
            outG[gBin]++;
            outB[bBin]++;
            outLuma[lumaBin]++;

            ptr += 4;
        }
    }
}

} // namespace lumacore
