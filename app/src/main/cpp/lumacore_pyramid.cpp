#include "lumacore_pyramid.hpp"

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <vector>

namespace lumacore {

namespace {

/**
 * Solves an 8x8 linear system M * x = v using Gaussian elimination with partial pivoting.
 */
bool solveLinear8x8(
    std::array<std::array<float, 8>, 8>& M,
    std::array<float, 8>& v,
    std::array<float, 8>& outX
) noexcept {
    constexpr int N = 8;
    for (int col = 0; col < N; ++col) {
        // Find pivot
        int pivotRow = col;
        float maxVal = std::abs(M[col][col]);
        for (int r = col + 1; r < N; ++r) {
            const float val = std::abs(M[r][col]);
            if (val > maxVal) {
                maxVal = val;
                pivotRow = r;
            }
        }

        if (maxVal < 1e-7f) {
            return false;
        }

        if (pivotRow != col) {
            std::swap(M[col], M[pivotRow]);
            std::swap(v[col], v[pivotRow]);
        }

        // Eliminate column entries below pivot
        const float diag = M[col][col];
        const float invDiag = 1.0f / diag;
        for (int r = col + 1; r < N; ++r) {
            const float factor = M[r][col] * invDiag;
            for (int c = col; c < N; ++c) {
                M[r][c] -= factor * M[col][c];
            }
            v[r] -= factor * v[col];
        }
    }

    // Back-substitution
    for (int r = N - 1; r >= 0; --r) {
        float sum = v[r];
        for (int c = r + 1; c < N; ++c) {
            sum -= M[r][c] * outX[c];
        }
        outX[r] = sum / M[r][r];
    }
    return true;
}

/**
 * Computes L1 sum of absolute differences between ref and target patches.
 */
int32_t computeL1Block(
    const PyramidLevel& refLvl,
    const PyramidLevel& tgtLvl,
    int32_t rx,
    int32_t ry,
    int32_t tx,
    int32_t ty,
    int32_t blockSize
) noexcept {
    const int32_t halfB = blockSize / 2;
    // Boundary check for ref block
    if (rx - halfB < 0 || rx + halfB >= refLvl.width ||
        ry - halfB < 0 || ry + halfB >= refLvl.height) {
        return 10000000;
    }
    // Boundary check for target block
    if (tx - halfB < 0 || tx + halfB >= tgtLvl.width ||
        ty - halfB < 0 || ty + halfB >= tgtLvl.height) {
        return 10000000;
    }

    int32_t sumL1 = 0;
    for (int32_t dy = -halfB; dy < halfB; ++dy) {
        const uint8_t* refRow = refLvl.row(ry + dy);
        const uint8_t* tgtRow = tgtLvl.row(ty + dy);
        for (int32_t dx = -halfB; dx < halfB; ++dx) {
            const int32_t diff = static_cast<int32_t>(refRow[rx + dx]) -
                                 static_cast<int32_t>(tgtRow[tx + dx]);
            sumL1 += std::abs(diff);
        }
    }
    return sumL1;
}

} // anonymous namespace

void LumaPyramid::build(
    const uint8_t* lumaPlane,
    int32_t width,
    int32_t height,
    int32_t stride
) {
    if (lumaPlane == nullptr || width <= 0 || height <= 0 || stride <= 0) {
        return;
    }

    // Level 0: Full resolution copy
    m_levels[0].width = width;
    m_levels[0].height = height;
    m_levels[0].stride = width;
    m_levels[0].data.resize(static_cast<size_t>(width) * static_cast<size_t>(height));

    for (int32_t y = 0; y < height; ++y) {
        const uint8_t* src = lumaPlane + static_cast<size_t>(y) * static_cast<size_t>(stride);
        uint8_t* dst = m_levels[0].row(y);
        std::memcpy(dst, src, static_cast<size_t>(width));
    }

    // Level 1: W/2 x H/2 with 2x2 box filter
    const int32_t w1 = width / 2;
    const int32_t h1 = height / 2;
    m_levels[1].width = w1;
    m_levels[1].height = h1;
    m_levels[1].stride = w1;
    m_levels[1].data.resize(static_cast<size_t>(w1) * static_cast<size_t>(h1));

    for (int32_t y = 0; y < h1; ++y) {
        const uint8_t* src0 = m_levels[0].row(y * 2);
        const uint8_t* src1 = m_levels[0].row(y * 2 + 1);
        uint8_t* dst = m_levels[1].row(y);
        for (int32_t x = 0; x < w1; ++x) {
            const uint32_t sum = static_cast<uint32_t>(src0[x * 2]) +
                                 static_cast<uint32_t>(src0[x * 2 + 1]) +
                                 static_cast<uint32_t>(src1[x * 2]) +
                                 static_cast<uint32_t>(src1[x * 2 + 1]);
            dst[x] = static_cast<uint8_t>((sum + 2) >> 2);
        }
    }

    // Level 2: W/4 x H/4 with 2x2 box filter from Level 1
    const int32_t w2 = w1 / 2;
    const int32_t h2 = h1 / 2;
    m_levels[2].width = w2;
    m_levels[2].height = h2;
    m_levels[2].stride = w2;
    m_levels[2].data.resize(static_cast<size_t>(w2) * static_cast<size_t>(h2));

    for (int32_t y = 0; y < h2; ++y) {
        const uint8_t* src0 = m_levels[1].row(y * 2);
        const uint8_t* src1 = m_levels[1].row(y * 2 + 1);
        uint8_t* dst = m_levels[2].row(y);
        for (int32_t x = 0; x < w2; ++x) {
            const uint32_t sum = static_cast<uint32_t>(src0[x * 2]) +
                                 static_cast<uint32_t>(src0[x * 2 + 1]) +
                                 static_cast<uint32_t>(src1[x * 2]) +
                                 static_cast<uint32_t>(src1[x * 2 + 1]);
            dst[x] = static_cast<uint8_t>((sum + 2) >> 2);
        }
    }
}

Homography3x3 alignPyramidsCoarseToFine(
    const LumaPyramid& refPyramid,
    const LumaPyramid& targetPyramid,
    const Homography3x3& gyroHomography
) {
    const int32_t width = refPyramid.getWidth();
    const int32_t height = refPyramid.getHeight();

    if (width <= 0 || height <= 0) {
        return gyroHomography;
    }

    // Create 5x5 grid of control points across the image
    constexpr int32_t GRID_COLS = 5;
    constexpr int32_t GRID_ROWS = 5;
    const float marginX = static_cast<float>(width) * 0.12f;
    const float marginY = static_cast<float>(height) * 0.12f;
    const float stepX = (static_cast<float>(width) - 2.0f * marginX) / static_cast<float>(GRID_COLS - 1);
    const float stepY = (static_cast<float>(height) - 2.0f * marginY) / static_cast<float>(GRID_ROWS - 1);

    struct PointMatch {
        float rx{0.0f};
        float ry{0.0f};
        float tx{0.0f};
        float ty{0.0f};
        int32_t score{0};
    };

    std::vector<PointMatch> matches;
    matches.reserve(GRID_COLS * GRID_ROWS);

    for (int32_t r = 0; r < GRID_ROWS; ++r) {
        for (int32_t c = 0; c < GRID_COLS; ++c) {
            const float refX0 = marginX + static_cast<float>(c) * stepX;
            const float refY0 = marginY + static_cast<float>(r) * stepY;

            // Project reference coordinate through initial gyro homography
            float gyroTargetX0 = refX0;
            float gyroTargetY0 = refY0;
            gyroHomography.transform(refX0, refY0, &gyroTargetX0, &gyroTargetY0);

            // --- Level 2 (1/4 scale) ---
            const auto& refL2 = refPyramid.getLevel(2);
            const auto& tgtL2 = targetPyramid.getLevel(2);

            const int32_t rx2 = static_cast<int32_t>(std::round(refX0 * 0.25f));
            const int32_t ry2 = static_cast<int32_t>(std::round(refY0 * 0.25f));
            int32_t tx2 = static_cast<int32_t>(std::round(gyroTargetX0 * 0.25f));
            int32_t ty2 = static_cast<int32_t>(std::round(gyroTargetY0 * 0.25f));

            int32_t bestScoreL2 = 10000000;
            int32_t bestDx2 = 0;
            int32_t bestDy2 = 0;
            constexpr int32_t R2 = 4;
            constexpr int32_t B2 = 8;

            for (int32_t sdy = -R2; sdy <= R2; ++sdy) {
                for (int32_t sdx = -R2; sdx <= R2; ++sdx) {
                    const int32_t score = computeL1Block(refL2, tgtL2, rx2, ry2, tx2 + sdx, ty2 + sdy, B2);
                    if (score < bestScoreL2) {
                        bestScoreL2 = score;
                        bestDx2 = sdx;
                        bestDy2 = sdy;
                    }
                }
            }
            tx2 += bestDx2;
            ty2 += bestDy2;

            // --- Level 1 (1/2 scale) ---
            const auto& refL1 = refPyramid.getLevel(1);
            const auto& tgtL1 = targetPyramid.getLevel(1);

            const int32_t rx1 = static_cast<int32_t>(std::round(refX0 * 0.5f));
            const int32_t ry1 = static_cast<int32_t>(std::round(refY0 * 0.5f));
            int32_t tx1 = tx2 * 2;
            int32_t ty1 = ty2 * 2;

            int32_t bestScoreL1 = 10000000;
            int32_t bestDx1 = 0;
            int32_t bestDy1 = 0;
            constexpr int32_t R1 = 2;
            constexpr int32_t B1 = 16;

            for (int32_t sdy = -R1; sdy <= R1; ++sdy) {
                for (int32_t sdx = -R1; sdx <= R1; ++sdx) {
                    const int32_t score = computeL1Block(refL1, tgtL1, rx1, ry1, tx1 + sdx, ty1 + sdy, B1);
                    if (score < bestScoreL1) {
                        bestScoreL1 = score;
                        bestDx1 = sdx;
                        bestDy1 = sdy;
                    }
                }
            }
            tx1 += bestDx1;
            ty1 += bestDy1;

            // --- Level 0 (Full scale) ---
            const auto& refL0 = refPyramid.getLevel(0);
            const auto& tgtL0 = targetPyramid.getLevel(0);

            const int32_t rx0 = static_cast<int32_t>(std::round(refX0));
            const int32_t ry0 = static_cast<int32_t>(std::round(refY0));
            int32_t tx0 = tx1 * 2;
            int32_t ty0 = ty1 * 2;

            int32_t bestScoreL0 = 10000000;
            int32_t bestDx0 = 0;
            int32_t bestDy0 = 0;
            constexpr int32_t R0 = 2;
            constexpr int32_t B0 = 16;

            for (int32_t sdy = -R0; sdy <= R0; ++sdy) {
                for (int32_t sdx = -R0; sdx <= R0; ++sdx) {
                    const int32_t score = computeL1Block(refL0, tgtL0, rx0, ry0, tx0 + sdx, ty0 + sdy, B0);
                    if (score < bestScoreL0) {
                        bestScoreL0 = score;
                        bestDx0 = sdx;
                        bestDy0 = sdy;
                    }
                }
            }
            tx0 += bestDx0;
            ty0 += bestDy0;

            // Sub-pixel parabolic refinement at integer minimum
            const int32_t scoreCenter = bestScoreL0;
            const int32_t scoreLeft = computeL1Block(refL0, tgtL0, rx0, ry0, tx0 - 1, ty0, B0);
            const int32_t scoreRight = computeL1Block(refL0, tgtL0, rx0, ry0, tx0 + 1, ty0, B0);
            const int32_t scoreTop = computeL1Block(refL0, tgtL0, rx0, ry0, tx0, ty0 - 1, B0);
            const int32_t scoreBottom = computeL1Block(refL0, tgtL0, rx0, ry0, tx0, ty0 + 1, B0);

            float subX = 0.0f;
            const float denomX = 2.0f * (static_cast<float>(scoreLeft) - 2.0f * static_cast<float>(scoreCenter) + static_cast<float>(scoreRight));
            if (std::abs(denomX) > 1e-3f) {
                subX = std::clamp((static_cast<float>(scoreLeft) - static_cast<float>(scoreRight)) / denomX, -0.5f, 0.5f);
            }

            float subY = 0.0f;
            const float denomY = 2.0f * (static_cast<float>(scoreTop) - 2.0f * static_cast<float>(scoreCenter) + static_cast<float>(scoreBottom));
            if (std::abs(denomY) > 1e-3f) {
                subY = std::clamp((static_cast<float>(scoreTop) - static_cast<float>(scoreBottom)) / denomY, -0.5f, 0.5f);
            }

            if (bestScoreL0 < 2000000) {
                matches.push_back(PointMatch{
                    .rx = refX0,
                    .ry = refY0,
                    .tx = static_cast<float>(tx0) + subX,
                    .ty = static_cast<float>(ty0) + subY,
                    .score = bestScoreL0
                });
            }
        }
    }

    if (matches.size() < 4) {
        return gyroHomography;
    }

    // Build normal equations (A^T * A + lambda * I) * h = A^T * b + lambda * h_gyro
    std::array<std::array<float, 8>, 8> AtA{};
    std::array<float, 8> Atb{};

    for (const auto& match : matches) {
        const float x = match.rx;
        const float y = match.ry;
        const float u = match.tx;
        const float v = match.ty;

        // Row 1: [ x, y, 1, 0, 0, 0, -u*x, -u*y ] * h = u
        const std::array<float, 8> r1 = { x, y, 1.0f, 0.0f, 0.0f, 0.0f, -u * x, -u * y };
        for (int i = 0; i < 8; ++i) {
            for (int j = 0; j < 8; ++j) {
                AtA[i][j] += r1[i] * r1[j];
            }
            Atb[i] += r1[i] * u;
        }

        // Row 2: [ 0, 0, 0, x, y, 1, -v*x, -v*y ] * h = v
        const std::array<float, 8> r2 = { 0.0f, 0.0f, 0.0f, x, y, 1.0f, -v * x, -v * y };
        for (int i = 0; i < 8; ++i) {
            for (int j = 0; j < 8; ++j) {
                AtA[i][j] += r2[i] * r2[j];
            }
            Atb[i] += r2[i] * v;
        }
    }

    // Normalize gyro homography with h22 = 1.0f
    const float gyroScale = (std::abs(gyroHomography.m[8]) > 1e-6f) ? (1.0f / gyroHomography.m[8]) : 1.0f;
    std::array<float, 8> hGyro{};
    for (int i = 0; i < 8; ++i) {
        hGyro[i] = gyroHomography.m[i] * gyroScale;
    }

    // Tikhonov regularization: lambda * (h - hGyro)
    constexpr float LAMBDA = 1.0f;
    for (int i = 0; i < 8; ++i) {
        AtA[i][i] += LAMBDA;
        Atb[i] += LAMBDA * hGyro[i];
    }

    std::array<float, 8> hOut{};
    if (!solveLinear8x8(AtA, Atb, hOut)) {
        return gyroHomography;
    }

    return Homography3x3{
        std::array<float, 9>{
            hOut[0], hOut[1], hOut[2],
            hOut[3], hOut[4], hOut[5],
            hOut[6], hOut[7], 1.0f
        }
    };
}

} // namespace lumacore
