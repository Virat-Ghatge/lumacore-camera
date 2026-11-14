#include "lumacore_homography.hpp"
#include "lumacore_pyramid.hpp"
#include "lumacore_merge.hpp"
#include "lumacore_bilateral_slice.hpp"

#include <cassert>
#include <chrono>
#include <cmath>
#include <iostream>
#include <vector>

namespace {

void testIdentityHomography() {
    std::cout << "[RUN] testIdentityHomography..." << std::endl;
    const lumacore::Homography3x3 id = lumacore::Homography3x3::identity();

    assert(id.isNearIdentity());

    float outX = 0.0f;
    float outY = 0.0f;
    id.transform(123.45f, 678.90f, &outX, &outY);
    assert(std::abs(outX - 123.45f) < 1e-4f);
    assert(std::abs(outY - 678.90f) < 1e-4f);

    const lumacore::Homography3x3 mult = id * id;
    assert(mult.isNearIdentity());
    std::cout << "[PASS] testIdentityHomography" << std::endl;
}

void testMatrixInverse() {
    std::cout << "[RUN] testMatrixInverse..." << std::endl;
    // Construct affine transformation: rotation ~15 deg + translation (12, -8)
    const float theta = 0.261799f;
    const float cosT = std::cos(theta);
    const float sinT = std::sin(theta);

    const lumacore::Homography3x3 H{
        std::array<float, 9>{
            cosT, -sinT, 12.0f,
            sinT,  cosT, -8.0f,
            0.0f,  0.0f,  1.0f
        }
    };

    bool success = false;
    const lumacore::Homography3x3 H_inv = H.inverse(&success);
    assert(success);

    const lumacore::Homography3x3 identityCheck = H * H_inv;
    assert(identityCheck.isNearIdentity(1e-3f));

    float x = 50.0f;
    float y = 70.0f;
    float tx = 0.0f;
    float ty = 0.0f;
    float rx = 0.0f;
    float ry = 0.0f;

    H.transform(x, y, &tx, &ty);
    H_inv.transform(tx, ty, &rx, &ry);

    assert(std::abs(rx - x) < 1e-3f);
    assert(std::abs(ry - y) < 1e-3f);
    std::cout << "[PASS] testMatrixInverse" << std::endl;
}

void testGyroHomography() {
    std::cout << "[RUN] testGyroHomography..." << std::endl;
    constexpr float f = 1200.0f;
    constexpr float cx = 640.0f;
    constexpr float cy = 480.0f;

    // Test 1: Zero rotation produces identity homography
    const lumacore::Homography3x3 H_zero = lumacore::computeGyroHomography(0.0f, 0.0f, 0.0f, f, cx, cy);
    assert(H_zero.isNearIdentity(1e-4f));

    // Test 2: Small yaw rotation (0.01 rad) around Y axis shifts X approx by f * rotY
    constexpr float rotY = 0.01f;
    const lumacore::Homography3x3 H_yaw = lumacore::computeGyroHomography(0.0f, rotY, 0.0f, f, cx, cy);

    float outX = 0.0f;
    float outY = 0.0f;
    H_yaw.transform(cx, cy, &outX, &outY);

    const float expectedShiftX = f * std::tan(rotY);
    assert(std::abs((outX - cx) - expectedShiftX) < 1.0f);
    assert(std::abs(outY - cy) < 1.0f);

    // Test 3: Benchmark latency < 0.2 ms requirement
    constexpr int iterations = 10000;
    const auto start = std::chrono::high_resolution_clock::now();
    for (int i = 0; i < iterations; ++i) {
        const float r = static_cast<float>(i) * 0.0001f;
        const auto h = lumacore::computeGyroHomography(r, -r, r * 0.5f, f, cx, cy);
        (void)h;
    }
    const auto end = std::chrono::high_resolution_clock::now();
    const double totalMs = std::chrono::duration<double, std::milli>(end - start).count();
    const double avgMs = totalMs / iterations;

    std::cout << "  Average gyro homography latency: " << avgMs << " ms (limit: < 0.2 ms)" << std::endl;
    assert(avgMs < 0.2);
    std::cout << "[PASS] testGyroHomography" << std::endl;
}

void testPyramidTranslationRecovery() {
    std::cout << "[RUN] testPyramidTranslationRecovery (dx=3, dy=2)..." << std::endl;
    constexpr int32_t W = 160;
    constexpr int32_t H = 120;
    std::vector<uint8_t> refImg(W * H, 0);
    std::vector<uint8_t> tgtImg(W * H, 0);

    // Generate rich 2D textured sinusoidal / grid pattern to enable unambiguous block matching
    for (int32_t y = 0; y < H; ++y) {
        for (int32_t x = 0; x < W; ++x) {
            const float fx = static_cast<float>(x);
            const float fy = static_cast<float>(y);
            const float val = 128.0f + 60.0f * std::sin(fx * 0.2f) * std::cos(fy * 0.2f)
                                     + 40.0f * std::sin((fx + fy) * 0.15f);
            refImg[y * W + x] = static_cast<uint8_t>(std::clamp(val, 0.0f, 255.0f));
        }
    }

    // Generate target image synthetically translated by (dx=3, dy=2)
    constexpr int32_t trueDx = 3;
    constexpr int32_t trueDy = 2;
    for (int32_t y = 0; y < H; ++y) {
        for (int32_t x = 0; x < W; ++x) {
            const int32_t srcX = std::clamp(x + trueDx, 0, W - 1);
            const int32_t srcY = std::clamp(y + trueDy, 0, H - 1);
            tgtImg[y * W + x] = refImg[srcY * W + srcX];
        }
    }

    lumacore::LumaPyramid refPyr;
    refPyr.build(refImg.data(), W, H, W);

    lumacore::LumaPyramid tgtPyr;
    tgtPyr.build(tgtImg.data(), W, H, W);

    // Initial estimate is identity (0 gyro motion)
    const lumacore::Homography3x3 refinedH = lumacore::alignPyramidsCoarseToFine(
        refPyr,
        tgtPyr,
        lumacore::Homography3x3::identity()
    );

    // Test that central points recover translation within 0.35 px tolerance
    float outX = 0.0f;
    float outY = 0.0f;
    refinedH.transform(80.0f, 60.0f, &outX, &outY);

    const float recoveredDx = outX - 80.0f;
    const float recoveredDy = outY - 60.0f;

    std::cout << "  True shift: (" << trueDx << ", " << trueDy
              << ") -> Recovered shift: (" << recoveredDx << ", " << recoveredDy << ")" << std::endl;

    assert(std::abs(recoveredDx - static_cast<float>(trueDx)) < 0.35f);
    assert(std::abs(recoveredDy - static_cast<float>(trueDy)) < 0.35f);
    std::cout << "[PASS] testPyramidTranslationRecovery" << std::endl;
}

void testGhostRejection() {
    std::cout << "[RUN] testGhostRejection..." << std::endl;
    constexpr int32_t W = 64;
    constexpr int32_t H = 64;

    // Reference frame: uniform dark texture (val = 40)
    std::vector<uint8_t> refY(W * H, 40);
    std::vector<uint8_t> refU((W / 2) * (H / 2), 128);
    std::vector<uint8_t> refV((W / 2) * (H / 2), 128);

    // Target frame: same background (val = 40), plus high-contrast moving object (val = 240) in central box
    std::vector<uint8_t> tgtY = refY;
    std::vector<uint8_t> tgtU = refU;
    std::vector<uint8_t> tgtV = refV;

    constexpr int32_t boxLeft = 24;
    constexpr int32_t boxTop = 24;
    constexpr int32_t boxSize = 16;
    for (int32_t y = boxTop; y < boxTop + boxSize; ++y) {
        for (int32_t x = boxLeft; x < boxLeft + boxSize; ++x) {
            tgtY[y * W + x] = 240;
        }
    }

    std::vector<uint8_t> outY(W * H, 0);
    std::vector<uint8_t> outU((W / 2) * (H / 2), 0);
    std::vector<uint8_t> outV((W / 2) * (H / 2), 0);

    const lumacore::FramePlaneView refPlanes{
        .yData = refY.data(),
        .uData = refU.data(),
        .vData = refV.data(),
        .yRowStride = W,
        .uvRowStride = W / 2,
        .uvPixelStride = 1
    };

    const lumacore::TargetFrameAlignment tgtAlignment{
        .planes = {
            .yData = tgtY.data(),
            .uData = tgtU.data(),
            .vData = tgtV.data(),
            .yRowStride = W,
            .uvRowStride = W / 2,
            .uvPixelStride = 1
        },
        .homography = lumacore::Homography3x3::identity()
    };

    const lumacore::MergeStats stats = lumacore::mergeBurstFrames(
        refPlanes,
        { tgtAlignment },
        W,
        H,
        outY.data(),
        outU.data(),
        outV.data(),
        W,
        W / 2,
        1
    );

    std::cout << "  Ghost rejection ratio: " << (stats.ghostRejectionRatio * 100.0f) << " %" << std::endl;
    assert(stats.ghostRejectionRatio > 0.05f); // Box area is (16*16) / (64*64) = 6.25%

    // Verify central pixel inside the moving box is NOT corrupted:
    // It should retain reference value (40), rather than averaged (40 + 240)/2 = 140
    const uint8_t mergedBoxPixel = outY[(boxTop + boxSize / 2) * W + (boxLeft + boxSize / 2)];
    assert(std::abs(static_cast<int>(mergedBoxPixel) - 40) <= 2);

    // Verify background pixel outside box merged cleanly
    const uint8_t mergedBgPixel = outY[5 * W + 5];
    assert(std::abs(static_cast<int>(mergedBgPixel) - 40) <= 1);

    std::cout << "[PASS] testGhostRejection" << std::endl;
}

void testBilateralGridSliceIdentity() {
    std::cout << "[RUN] testBilateralGridSliceIdentity..." << std::endl;
    constexpr int32_t gridDepth = 8;
    constexpr int32_t gridH = 16;
    constexpr int32_t gridW = 16;
    constexpr int32_t totalGridElements = 12 * gridDepth * gridH * gridW;

    std::vector<float> grid(totalGridElements, 0.0f);
    constexpr int32_t depthStride = gridH * gridW;
    constexpr int32_t channelStride = gridDepth * depthStride;

    // Set identity affine transform for all grid cells:
    // R_out = 1.0 * R_in (channel 0)
    // G_out = 1.0 * G_in (channel 5)
    // B_out = 1.0 * B_in (channel 10)
    for (int32_t d = 0; d < gridDepth; ++d) {
        for (int32_t y = 0; y < gridH; ++y) {
            for (int32_t x = 0; x < gridW; ++x) {
                const int32_t cellIdx = d * depthStride + y * gridW + x;
                grid[0 * channelStride + cellIdx] = 1.0f;
                grid[5 * channelStride + cellIdx] = 1.0f;
                grid[10 * channelStride + cellIdx] = 1.0f;
            }
        }
    }

    constexpr int32_t W = 64;
    constexpr int32_t H = 48;
    std::vector<uint8_t> inRgb(W * H * 3);
    for (int32_t i = 0; i < W * H * 3; ++i) {
        inRgb[i] = static_cast<uint8_t>((i * 17 + 31) % 256);
    }

    std::vector<uint8_t> outRgb(W * H * 3, 0);
    lumacore::sliceBilateralGrid(inRgb.data(), W, H, grid.data(), gridDepth, gridH, gridW, outRgb.data());

    for (size_t i = 0; i < inRgb.size(); ++i) {
        assert(std::abs(static_cast<int>(outRgb[i]) - static_cast<int>(inRgb[i])) <= 1);
    }

    // Benchmark 1080p image slicing performance
    constexpr int32_t fhdW = 1920;
    constexpr int32_t fhdH = 1080;
    std::vector<uint8_t> fhdIn(fhdW * fhdH * 3, 128);
    std::vector<uint8_t> fhdOut(fhdW * fhdH * 3, 0);

    const auto start = std::chrono::high_resolution_clock::now();
    lumacore::sliceBilateralGrid(fhdIn.data(), fhdW, fhdH, grid.data(), gridDepth, gridH, gridW, fhdOut.data());
    const auto end = std::chrono::high_resolution_clock::now();
    const double sliceMs = std::chrono::duration<double, std::milli>(end - start).count();

    std::cout << "  1080p CPU Bilateral Slicing Latency: " << sliceMs << " ms (target: < 25ms)" << std::endl;
    std::cout << "[PASS] testBilateralGridSliceIdentity" << std::endl;
}

} // anonymous namespace

int main() {
    std::cout << "=== Running LumaCore Milestone 4 Burst Alignment & Bilateral Slice Native Unit Tests ===" << std::endl;
    testIdentityHomography();
    testMatrixInverse();
    testGyroHomography();
    testPyramidTranslationRecovery();
    testGhostRejection();
    testBilateralGridSliceIdentity();
    std::cout << "=== ALL 6 NATIVE TESTS PASSED SUCCESSFULLY! ===" << std::endl;
    return 0;
}
