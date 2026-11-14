#pragma once

#include <cstdint>
#include <vector>

namespace lumacore {

/**
 * Computes the 3x3 discrete Laplacian operator over the Luma (Y) channel:
 * kernel:
 * [  0,  1,  0 ]
 * [  1, -4,  1 ]
 * [  0,  1,  0 ]
 *
 * Calculates the mean and variance of the Laplacian response.
 * Higher variance indicates sharper edges and absence of motion blur.
 *
 * @param luma Pointer to 8-bit luma channel buffer.
 * @param width Width of the image in pixels.
 * @param height Height of the image in pixels.
 * @param stride Row stride in bytes.
 * @return Laplacian response variance (>= 0.0f).
 */
float computeLaplacianVariance(
    const uint8_t* luma,
    int width,
    int height,
    int stride
);

/**
 * Evaluates all burst frames to pick the sharpest frame index using Laplacian variance.
 *
 * @param frames Array of pointers to luma frame buffers.
 * @param count Number of frames in the burst.
 * @param width Width of each frame in pixels.
 * @param height Height of each frame in pixels.
 * @param stride Row stride in bytes.
 * @return 0-based index of the sharpest frame (0 if count <= 0 or error).
 */
int selectSharpestFrame(
    const uint8_t* const* frames,
    int count,
    int width,
    int height,
    int stride
);

} // namespace lumacore
