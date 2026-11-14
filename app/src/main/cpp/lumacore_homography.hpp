#pragma once

#include <array>
#include <cmath>
#include <cstdint>

namespace lumacore {

/**
 * 3x3 Projective transformation matrix (Homography) stored in row-major order:
 * [ m[0] m[1] m[2] ]
 * [ m[3] m[4] m[5] ]
 * [ m[6] m[7] m[8] ]
 */
struct Homography3x3 {
    std::array<float, 9> m{
        1.0f, 0.0f, 0.0f,
        0.0f, 1.0f, 0.0f,
        0.0f, 0.0f, 1.0f
    };

    /**
     * Returns a 3x3 identity matrix.
     */
    static Homography3x3 identity() noexcept;

    /**
     * Performs standard 3x3 matrix multiplication: C = (*this) * other.
     */
    Homography3x3 operator*(const Homography3x3& other) const noexcept;

    /**
     * Calculates the exact analytical matrix inverse using cofactors.
     * @param success Optional pointer returning true if determinant is non-zero, false otherwise.
     * @return Inverse matrix, or identity matrix if singular.
     */
    [[nodiscard]] Homography3x3 inverse(bool* success = nullptr) const noexcept;

    /**
     * Projects a 2D Euclidean coordinate (x, y) through projective homography:
     * [x', y', w']^T = H * [x, y, 1]^T, with outX = x'/w', outY = y'/w'.
     */
    void transform(float x, float y, float* outX, float* outY) const noexcept;

    /**
     * Checks if this matrix is approximately an identity matrix within tolerance.
     */
    [[nodiscard]] bool isNearIdentity(float tolerance = 1e-4f) const noexcept;
};

/**
 * Computes rotation-induced Homography matrix K * R * K_inv:
 * Computes rotation matrix R = Rz * Ry * Rx from integrated gyro delta angles.
 * Conjugates by camera intrinsic matrix K = [f, 0, cx; 0, f, cy; 0, 0, 1].
 *
 * @param rotX Gyro rotation angle around X axis (pitch) in radians.
 * @param rotY Gyro rotation angle around Y axis (yaw) in radians.
 * @param rotZ Gyro rotation angle around Z axis (roll) in radians.
 * @param focalLengthPx Camera focal length in pixel units.
 * @param centerX Principal point X coordinate in pixel units.
 * @param centerY Principal point Y coordinate in pixel units.
 * @return 3x3 homography matrix mapping reference coordinates to target coordinates.
 */
Homography3x3 computeGyroHomography(
    float rotX,
    float rotY,
    float rotZ,
    float focalLengthPx,
    float centerX,
    float centerY
) noexcept;

} // namespace lumacore
