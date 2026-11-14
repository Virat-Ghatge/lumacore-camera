#include "lumacore_sharpness.hpp"

#include <algorithm>
#include <cmath>

namespace lumacore {

float computeLaplacianVariance(
    const uint8_t* luma,
    int width,
    int height,
    int stride
) {
    if (luma == nullptr || width < 3 || height < 3 || stride < width) {
        return 0.0f;
    }

    const int validWidth = width - 2;
    const int validHeight = height - 2;
    const double n = static_cast<double>(validWidth) * static_cast<double>(validHeight);
    if (n <= 0.0) {
        return 0.0f;
    }

    double sum = 0.0;
    double sumSq = 0.0;

    for (int y = 1; y < height - 1; ++y) {
        const uint8_t* rowPrev = luma + (y - 1) * stride;
        const uint8_t* rowCurr = luma + y * stride;
        const uint8_t* rowNext = luma + (y + 1) * stride;

        for (int x = 1; x < width - 1; ++x) {
            // Discrete 3x3 Laplacian operator: [0, 1, 0; 1, -4, 1; 0, 1, 0]
            const int center = static_cast<int>(rowCurr[x]);
            const int top = static_cast<int>(rowPrev[x]);
            const int left = static_cast<int>(rowCurr[x - 1]);
            const int right = static_cast<int>(rowCurr[x + 1]);
            const int bottom = static_cast<int>(rowNext[x]);

            const int laplacian = top + left + right + bottom - 4 * center;
            const double lapVal = static_cast<double>(laplacian);

            sum += lapVal;
            sumSq += lapVal * lapVal;
        }
    }

    const double mean = sum / n;
    const double variance = (sumSq / n) - (mean * mean);

    return static_cast<float>(std::max(0.0, variance));
}

int selectSharpestFrame(
    const uint8_t* const* frames,
    int count,
    int width,
    int height,
    int stride
) {
    if (frames == nullptr || count <= 0 || width < 3 || height < 3) {
        return 0;
    }

    int bestIdx = 0;
    float maxVariance = -1.0f;

    for (int i = 0; i < count; ++i) {
        if (frames[i] == nullptr) {
            continue;
        }
        const float variance = computeLaplacianVariance(frames[i], width, height, stride);
        if (variance > maxVariance) {
            maxVariance = variance;
            bestIdx = i;
        }
    }

    return bestIdx;
}

} // namespace lumacore
