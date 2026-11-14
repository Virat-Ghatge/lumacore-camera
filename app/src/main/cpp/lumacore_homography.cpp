#include "lumacore_homography.hpp"

#include <cmath>
#include <algorithm>

namespace lumacore {

Homography3x3 Homography3x3::identity() noexcept {
    return Homography3x3{
        std::array<float, 9>{
            1.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 1.0f
        }
    };
}

Homography3x3 Homography3x3::operator*(const Homography3x3& o) const noexcept {
    Homography3x3 res;
    for (int r = 0; r < 3; ++r) {
        for (int c = 0; c < 3; ++c) {
            res.m[r * 3 + c] =
                m[r * 3 + 0] * o.m[0 * 3 + c] +
                m[r * 3 + 1] * o.m[1 * 3 + c] +
                m[r * 3 + 2] * o.m[2 * 3 + c];
        }
    }
    return res;
}

Homography3x3 Homography3x3::inverse(bool* success) const noexcept {
    const float c00 = m[4] * m[8] - m[5] * m[7];
    const float c01 = m[2] * m[7] - m[1] * m[8];
    const float c02 = m[1] * m[5] - m[2] * m[4];

    const float c10 = m[5] * m[6] - m[3] * m[8];
    const float c11 = m[0] * m[8] - m[2] * m[6];
    const float c12 = m[2] * m[3] - m[0] * m[5];

    const float c20 = m[3] * m[7] - m[4] * m[6];
    const float c21 = m[1] * m[6] - m[0] * m[7];
    const float c22 = m[0] * m[4] - m[1] * m[3];

    const float det = m[0] * c00 + m[1] * c10 + m[2] * c20;

    if (std::abs(det) < 1e-9f) {
        if (success != nullptr) {
            *success = false;
        }
        return Homography3x3::identity();
    }

    if (success != nullptr) {
        *success = true;
    }

    const float invDet = 1.0f / det;
    return Homography3x3{
        std::array<float, 9>{
            c00 * invDet, c01 * invDet, c02 * invDet,
            c10 * invDet, c11 * invDet, c12 * invDet,
            c20 * invDet, c21 * invDet, c22 * invDet
        }
    };
}

void Homography3x3::transform(float x, float y, float* outX, float* outY) const noexcept {
    if (outX == nullptr || outY == nullptr) {
        return;
    }
    const float z = m[6] * x + m[7] * y + m[8];
    if (std::abs(z) < 1e-7f) {
        *outX = x;
        *outY = y;
        return;
    }
    const float invZ = 1.0f / z;
    *outX = (m[0] * x + m[1] * y + m[2]) * invZ;
    *outY = (m[3] * x + m[4] * y + m[5]) * invZ;
}

bool Homography3x3::isNearIdentity(float tolerance) const noexcept {
    const std::array<float, 9> id{
        1.0f, 0.0f, 0.0f,
        0.0f, 1.0f, 0.0f,
        0.0f, 0.0f, 1.0f
    };
    for (size_t i = 0; i < 9; ++i) {
        if (std::abs(m[i] - id[i]) > tolerance) {
            return false;
        }
    }
    return true;
}

Homography3x3 computeGyroHomography(
    float rotX,
    float rotY,
    float rotZ,
    float focalLengthPx,
    float centerX,
    float centerY
) noexcept {
    // Rotation around X axis (pitch)
    const float cx = std::cos(rotX);
    const float sx = std::sin(rotX);
    const Homography3x3 rx{
        std::array<float, 9>{
            1.0f, 0.0f, 0.0f,
            0.0f, cx,   -sx,
            0.0f, sx,   cx
        }
    };

    // Rotation around Y axis (yaw)
    const float cy = std::cos(rotY);
    const float sy = std::sin(rotY);
    const Homography3x3 ry{
        std::array<float, 9>{
            cy,   0.0f, sy,
            0.0f, 1.0f, 0.0f,
            -sy,  0.0f, cy
        }
    };

    // Rotation around Z axis (roll)
    const float cz = std::cos(rotZ);
    const float sz = std::sin(rotZ);
    const Homography3x3 rz{
        std::array<float, 9>{
            cz,   -sz,  0.0f,
            sz,   cz,   0.0f,
            0.0f, 0.0f, 1.0f
        }
    };

    // Composite rotation: R = Rz * Ry * Rx
    const Homography3x3 r = rz * ry * rx;

    // Guard against invalid or near-zero focal length
    const float f = (std::abs(focalLengthPx) < 1.0f) ? 1000.0f : focalLengthPx;
    const float invF = 1.0f / f;

    // Intrinsic matrix K:
    // [ f,  0, cx ]
    // [ 0,  f, cy ]
    // [ 0,  0,  1 ]
    const Homography3x3 k{
        std::array<float, 9>{
            f,    0.0f, centerX,
            0.0f, f,    centerY,
            0.0f, 0.0f, 1.0f
        }
    };

    // Inverse intrinsic matrix K_inv:
    // [ 1/f,    0, -cx/f ]
    // [   0,  1/f, -cy/f ]
    // [   0,    0,     1 ]
    const Homography3x3 kInv{
        std::array<float, 9>{
            invF, 0.0f, -centerX * invF,
            0.0f, invF, -centerY * invF,
            0.0f, 0.0f, 1.0f
        }
    };

    // H = K * R * K_inv
    return k * r * kInv;
}

} // namespace lumacore
