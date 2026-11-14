#pragma once

#include "lumacore_homography.hpp"

#include <array>
#include <cstdint>
#include <vector>

namespace lumacore {

/**
 * Single pyramid level holding downsampled luma pixels.
 */
struct PyramidLevel {
    int32_t width{0};
    int32_t height{0};
    int32_t stride{0};
    std::vector<uint8_t> data;

    [[nodiscard]] const uint8_t* row(int32_t y) const noexcept {
        return data.data() + static_cast<size_t>(y) * static_cast<size_t>(stride);
    }

    [[nodiscard]] uint8_t* row(int32_t y) noexcept {
        return data.data() + static_cast<size_t>(y) * static_cast<size_t>(stride);
    }
};

/**
 * 3-Level Gaussian/Box downsampled pyramid for sub-pixel burst alignment.
 * Level 0: W x H (full resolution)
 * Level 1: W/2 x H/2
 * Level 2: W/4 x H/4
 */
class LumaPyramid {
public:
    static constexpr int32_t NUM_LEVELS = 3;

    LumaPyramid() = default;
    ~LumaPyramid() = default;

    /**
     * Builds 3 pyramid levels from raw input Luma plane buffer.
     * @param lumaPlane Direct pointer to uint8_t Y plane.
     * @param width Image width in pixels.
     * @param height Image height in pixels.
     * @param stride Row stride in bytes.
     */
    void build(const uint8_t* lumaPlane, int32_t width, int32_t height, int32_t stride);

    [[nodiscard]] const PyramidLevel& getLevel(int32_t level) const noexcept {
        return m_levels[level];
    }

    [[nodiscard]] int32_t getWidth() const noexcept { return m_levels[0].width; }
    [[nodiscard]] int32_t getHeight() const noexcept { return m_levels[0].height; }

private:
    std::array<PyramidLevel, NUM_LEVELS> m_levels;
};

/**
 * Matches patches between reference and target pyramids using coarse-to-fine
 * L1 block matching seeded by gyro homography, producing a refined 3x3 Homography.
 *
 * @param refPyramid Pre-built pyramid of reference frame.
 * @param targetPyramid Pre-built pyramid of target frame.
 * @param gyroHomography Initial projective estimate from gyroscope integration.
 * @return Refined Homography3x3 with sub-pixel optical flow convergence.
 */
Homography3x3 alignPyramidsCoarseToFine(
    const LumaPyramid& refPyramid,
    const LumaPyramid& targetPyramid,
    const Homography3x3& gyroHomography
);

} // namespace lumacore
