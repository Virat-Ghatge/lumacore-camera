#include "lumacore_super_res.hpp"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

namespace lumacore {

bool estimateSubPixelShift(
    const uint8_t* refY,
    const uint8_t* tgtY,
    int width,
    int height,
    int stride,
    float* outDx,
    float* outDy
) {
    if (refY == nullptr || tgtY == nullptr || outDx == nullptr || outDy == nullptr ||
        width < 16 || height < 16 || stride < width) {
        if (outDx != nullptr) *outDx = 0.0f;
        if (outDy != nullptr) *outDy = 0.0f;
        return false;
    }

    // Select center evaluation window
    const int evalW = std::min(width / 2, 128);
    const int evalH = std::min(height / 2, 128);
    const int startX = (width - evalW) / 2;
    const int startY = (height - evalH) / 2;

    constexpr int searchR = 4;
    int bestIx = 0;
    int bestIy = 0;
    int64_t minSad = INT64_MAX;

    // Cache SAD values for 1D parabolic interpolation around minimum
    int64_t sadGrid[(2 * searchR + 1)][(2 * searchR + 1)];

    for (int iy = -searchR; iy <= searchR; ++iy) {
        for (int ix = -searchR; ix <= searchR; ++ix) {
            int64_t currentSad = 0;

            for (int y = 0; y < evalH; ++y) {
                const int refYIdx = (startY + y) * stride;
                const int tgtYIdx = (startY + y + iy) * stride;

                for (int x = 0; x < evalW; ++x) {
                    const int refVal = static_cast<int>(refY[refYIdx + (startX + x)]);
                    const int tgtVal = static_cast<int>(tgtY[tgtYIdx + (startX + x + ix)]);
                    currentSad += std::abs(refVal - tgtVal);
                }
            }

            sadGrid[iy + searchR][ix + searchR] = currentSad;
            if (currentSad < minSad) {
                minSad = currentSad;
                bestIx = ix;
                bestIy = iy;
            }
        }
    }

    // Parabolic sub-pixel peak refinement along X
    float subX = 0.0f;
    if (bestIx > -searchR && bestIx < searchR) {
        const double s0 = static_cast<double>(sadGrid[bestIy + searchR][bestIx - 1 + searchR]);
        const double s1 = static_cast<double>(sadGrid[bestIy + searchR][bestIx + searchR]);
        const double s2 = static_cast<double>(sadGrid[bestIy + searchR][bestIx + 1 + searchR]);
        const double denom = 2.0 * (s0 - 2.0 * s1 + s2);
        if (std::abs(denom) > 1e-5) {
            subX = static_cast<float>((s0 - s2) / denom);
            subX = std::clamp(subX, -0.5f, 0.5f);
        }
    }

    // Parabolic sub-pixel peak refinement along Y
    float subY = 0.0f;
    if (bestIy > -searchR && bestIy < searchR) {
        const double s0 = static_cast<double>(sadGrid[bestIy - 1 + searchR][bestIx + searchR]);
        const double s1 = static_cast<double>(sadGrid[bestIy + searchR][bestIx + searchR]);
        const double s2 = static_cast<double>(sadGrid[bestIy + 1 + searchR][bestIx + searchR]);
        const double denom = 2.0 * (s0 - 2.0 * s1 + s2);
        if (std::abs(denom) > 1e-5) {
            subY = static_cast<float>((s0 - s2) / denom);
            subY = std::clamp(subY, -0.5f, 0.5f);
        }
    }

    *outDx = static_cast<float>(bestIx) + subX;
    *outDy = static_cast<float>(bestIy) + subY;
    return true;
}

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
) {
    if (framesY == nullptr || count <= 0 || framesY[0] == nullptr ||
        inWidth < 8 || inHeight < 8 || inStride < inWidth ||
        out2xY == nullptr || outWidth < inWidth || outHeight < inHeight || outStride < outWidth) {
        return false;
    }

    const float scaleX = static_cast<float>(outWidth) / static_cast<float>(inWidth);
    const float scaleY = static_cast<float>(outHeight) / static_cast<float>(inHeight);

    // If single frame, fallback to high-quality bilinear upscale
    if (count == 1) {
        for (int oy = 0; oy < outHeight; ++oy) {
            const float srcY = ((static_cast<float>(oy) + 0.5f) / scaleY) - 0.5f;
            const int y0 = std::clamp(static_cast<int>(std::floor(srcY)), 0, inHeight - 1);
            const int y1 = std::clamp(y0 + 1, 0, inHeight - 1);
            const float fy = srcY - static_cast<float>(y0);

            uint8_t* rowOut = out2xY + oy * outStride;
            for (int ox = 0; ox < outWidth; ++ox) {
                const float srcX = ((static_cast<float>(ox) + 0.5f) / scaleX) - 0.5f;
                const int x0 = std::clamp(static_cast<int>(std::floor(srcX)), 0, inWidth - 1);
                const int x1 = std::clamp(x0 + 1, 0, inWidth - 1);
                const float fx = srcX - static_cast<float>(x0);

                const float top = (1.0f - fx) * framesY[0][y0 * inStride + x0] + fx * framesY[0][y0 * inStride + x1];
                const float bot = (1.0f - fx) * framesY[0][y1 * inStride + x0] + fx * framesY[0][y1 * inStride + x1];
                const float val = (1.0f - fy) * top + fy * bot;
                rowOut[ox] = static_cast<uint8_t>(std::clamp(std::round(val), 0.0f, 255.0f));
            }
        }
        return true;
    }

    // Estimate sub-pixel shifts relative to reference frame 0
    std::vector<float> shiftsX(static_cast<size_t>(count), 0.0f);
    std::vector<float> shiftsY(static_cast<size_t>(count), 0.0f);

    for (int k = 1; k < count; ++k) {
        if (framesY[k] != nullptr) {
            estimateSubPixelShift(
                framesY[0],
                framesY[k],
                inWidth,
                inHeight,
                inStride,
                &shiftsX[static_cast<size_t>(k)],
                &shiftsY[static_cast<size_t>(k)]
            );
        }
    }

    // Accumulation buffers for 2x destination grid
    const size_t totalPixels = static_cast<size_t>(outWidth) * static_cast<size_t>(outHeight);
    std::vector<float> accumVal(totalPixels, 0.0f);
    std::vector<float> accumWeight(totalPixels, 0.0f);

    constexpr float splatRadius = 1.5f;
    constexpr float splatRadiusSq = splatRadius * splatRadius;
    constexpr float spatialSigma = 0.85f;
    constexpr float twoSigmaSq = 2.0f * spatialSigma * spatialSigma;
    constexpr float photometricSigma = 45.0f;
    constexpr float twoPhotoSigmaSq = 2.0f * photometricSigma * photometricSigma;

    for (int k = 0; k < count; ++k) {
        const uint8_t* currentFrame = framesY[k];
        if (currentFrame == nullptr) {
            continue;
        }

        const float dx = shiftsX[static_cast<size_t>(k)];
        const float dy = shiftsY[static_cast<size_t>(k)];

        for (int y = 0; y < inHeight; ++y) {
            const float yRef = static_cast<float>(y) - dy;
            const float yDest = (yRef + 0.5f) * scaleY - 0.5f;
            const int minOy = std::max(0, static_cast<int>(std::floor(yDest - splatRadius)));
            const int maxOy = std::min(outHeight - 1, static_cast<int>(std::ceil(yDest + splatRadius)));

            const uint8_t* rowIn = currentFrame + y * inStride;

            for (int x = 0; x < inWidth; ++x) {
                const float val = static_cast<float>(rowIn[x]);
                const float xRef = static_cast<float>(x) - dx;
                const float xDest = (xRef + 0.5f) * scaleX - 0.5f;

                // Photometric range weight against reference frame to reject ghost motion
                const int rx = std::clamp(static_cast<int>(std::round(xRef)), 0, inWidth - 1);
                const int ry = std::clamp(static_cast<int>(std::round(yRef)), 0, inHeight - 1);
                const float refVal = static_cast<float>(framesY[0][ry * inStride + rx]);
                const float diff = val - refVal;
                const float wRange = std::exp(-(diff * diff) / twoPhotoSigmaSq);

                const int minOx = std::max(0, static_cast<int>(std::floor(xDest - splatRadius)));
                const int maxOx = std::min(outWidth - 1, static_cast<int>(std::ceil(xDest + splatRadius)));

                for (int oy = minOy; oy <= maxOy; ++oy) {
                    const float dySplat = static_cast<float>(oy) - yDest;
                    const size_t rowOffset = static_cast<size_t>(oy) * static_cast<size_t>(outWidth);

                    for (int ox = minOx; ox <= maxOx; ++ox) {
                        const float dxSplat = static_cast<float>(ox) - xDest;
                        const float distSq = dxSplat * dxSplat + dySplat * dySplat;

                        if (distSq <= splatRadiusSq) {
                            const float wSpatial = std::exp(-distSq / twoSigmaSq);
                            const float w = wSpatial * wRange;
                            const size_t pixelIdx = rowOffset + static_cast<size_t>(ox);

                            accumVal[pixelIdx] += val * w;
                            accumWeight[pixelIdx] += w;
                        }
                    }
                }
            }
        }
    }

    // Normalize accumulated samples onto final destination grid
    for (int oy = 0; oy < outHeight; ++oy) {
        uint8_t* rowOut = out2xY + oy * outStride;
        const size_t rowOffset = static_cast<size_t>(oy) * static_cast<size_t>(outWidth);

        for (int ox = 0; ox < outWidth; ++ox) {
            const size_t pixelIdx = rowOffset + static_cast<size_t>(ox);
            const float totalWeight = accumWeight[pixelIdx];

            if (totalWeight > 1e-4f) {
                const float normalized = accumVal[pixelIdx] / totalWeight;
                rowOut[ox] = static_cast<uint8_t>(std::clamp(std::round(normalized), 0.0f, 255.0f));
            } else {
                // Fallback bilinear interpolation from reference frame
                const float srcX = ((static_cast<float>(ox) + 0.5f) / scaleX) - 0.5f;
                const float srcY = ((static_cast<float>(oy) + 0.5f) / scaleY) - 0.5f;
                const int x0 = std::clamp(static_cast<int>(std::floor(srcX)), 0, inWidth - 1);
                const int y0 = std::clamp(static_cast<int>(std::floor(srcY)), 0, inHeight - 1);
                const int x1 = std::clamp(x0 + 1, 0, inWidth - 1);
                const int y1 = std::clamp(y0 + 1, 0, inHeight - 1);
                const float fx = srcX - static_cast<float>(x0);
                const float fy = srcY - static_cast<float>(y0);

                const float top = (1.0f - fx) * framesY[0][y0 * inStride + x0] + fx * framesY[0][y0 * inStride + x1];
                const float bot = (1.0f - fx) * framesY[0][y1 * inStride + x0] + fx * framesY[0][y1 * inStride + x1];
                const float val = (1.0f - fy) * top + fy * bot;
                rowOut[ox] = static_cast<uint8_t>(std::clamp(std::round(val), 0.0f, 255.0f));
            }
        }
    }

    return true;
}

} // namespace lumacore
