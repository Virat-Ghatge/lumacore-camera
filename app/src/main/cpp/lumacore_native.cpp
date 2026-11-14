#include "lumacore_homography.hpp"
#include "lumacore_pyramid.hpp"
#include "lumacore_merge.hpp"

#include <jni.h>
#include <android/log.h>
#include <chrono>
#include <string>
#include <vector>

#define LOG_TAG "LumaCoreNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace lumacore {

constexpr const char* VERSION_STRING = "1.0.0-milestone3";

class NativeEngine {
public:
    static NativeEngine& getInstance() {
        static NativeEngine instance;
        return instance;
    }

    bool initialize() {
        LOGI("LumaCore NativeEngine initializing with C++20 burst alignment pipeline...");
        m_initialized = true;
        return true;
    }

    [[nodiscard]] bool isInitialized() const {
        return m_initialized;
    }

    [[nodiscard]] const char* getVersion() const {
        return VERSION_STRING;
    }

private:
    NativeEngine() = default;
    ~NativeEngine() = default;
    NativeEngine(const NativeEngine&) = delete;
    NativeEngine& operator=(const NativeEngine&) = delete;

    bool m_initialized{false};
};

} // namespace lumacore

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)reserved;
    if (vm == nullptr) {
        return JNI_ERR;
    }
    LOGI("JNI_OnLoad: LumaCore native library loaded successfully.");
    return JNI_VERSION_1_6;
}

JNIEXPORT jboolean JNICALL
Java_com_lumacore_camera_core_nativebridge_NativePipeline_nativeInit(
    JNIEnv* env,
    jobject /* thiz */
) {
    (void)env;
    return static_cast<jboolean>(lumacore::NativeEngine::getInstance().initialize());
}

JNIEXPORT jstring JNICALL
Java_com_lumacore_camera_core_nativebridge_NativePipeline_nativeGetVersion(
    JNIEnv* env,
    jobject /* thiz */
) {
    if (env == nullptr) {
        return nullptr;
    }
    const char* version = lumacore::NativeEngine::getInstance().getVersion();
    return env->NewStringUTF(version);
}

JNIEXPORT jfloatArray JNICALL
Java_com_lumacore_camera_core_nativebridge_NativePipeline_nativeComputeGyroHomography(
    JNIEnv* env,
    jobject /* thiz */,
    jfloat rotX,
    jfloat rotY,
    jfloat rotZ,
    jfloat focalLength,
    jfloat cx,
    jfloat cy
) {
    if (env == nullptr) {
        return nullptr;
    }

    const lumacore::Homography3x3 H = lumacore::computeGyroHomography(
        rotX, rotY, rotZ, focalLength, cx, cy
    );

    jfloatArray result = env->NewFloatArray(9);
    if (result == nullptr) {
        return nullptr;
    }

    env->SetFloatArrayRegion(result, 0, 9, H.m.data());
    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_com_lumacore_camera_core_nativebridge_NativePipeline_nativeAlignAndMergeBurst(
    JNIEnv* env,
    jobject /* thiz */,
    jobjectArray yPlanes,
    jobjectArray uPlanes,
    jobjectArray vPlanes,
    jint width,
    jint height,
    jint yRowStride,
    jint uvRowStride,
    jint uvPixelStride,
    jfloatArray gyroRotations,
    jobject outYBuffer,
    jobject outUBuffer,
    jobject outVBuffer
) {
    if (env == nullptr || yPlanes == nullptr || uPlanes == nullptr || vPlanes == nullptr ||
        outYBuffer == nullptr || outUBuffer == nullptr || outVBuffer == nullptr ||
        width <= 0 || height <= 0) {
        LOGE("nativeAlignAndMergeBurst: Invalid arguments or null pointer passed");
        return nullptr;
    }

    const jsize frameCount = env->GetArrayLength(yPlanes);
    if (frameCount <= 0) {
        jfloatArray emptyRes = env->NewFloatArray(4);
        const jfloat defaults[4] = { 0.0f, 0.0f, 0.0f, 0.0f };
        env->SetFloatArrayRegion(emptyRes, 0, 4, defaults);
        return emptyRes;
    }

    uint8_t* outYPtr = static_cast<uint8_t*>(env->GetDirectBufferAddress(outYBuffer));
    uint8_t* outUPtr = static_cast<uint8_t*>(env->GetDirectBufferAddress(outUBuffer));
    uint8_t* outVPtr = static_cast<uint8_t*>(env->GetDirectBufferAddress(outVBuffer));

    if (outYPtr == nullptr || outUPtr == nullptr || outVPtr == nullptr) {
        LOGE("nativeAlignAndMergeBurst: Output direct byte buffers are not direct or null");
        return nullptr;
    }

    // Extract frame pointers
    struct RawFrame {
        uint8_t* y{nullptr};
        uint8_t* u{nullptr};
        uint8_t* v{nullptr};
    };

    std::vector<RawFrame> rawFrames(static_cast<size_t>(frameCount));
    for (jsize i = 0; i < frameCount; ++i) {
        jobject yObj = env->GetObjectArrayElement(yPlanes, i);
        jobject uObj = env->GetObjectArrayElement(uPlanes, i);
        jobject vObj = env->GetObjectArrayElement(vPlanes, i);

        rawFrames[i].y = (yObj != nullptr) ? static_cast<uint8_t*>(env->GetDirectBufferAddress(yObj)) : nullptr;
        rawFrames[i].u = (uObj != nullptr) ? static_cast<uint8_t*>(env->GetDirectBufferAddress(uObj)) : nullptr;
        rawFrames[i].v = (vObj != nullptr) ? static_cast<uint8_t*>(env->GetDirectBufferAddress(vObj)) : nullptr;

        if (yObj != nullptr) env->DeleteLocalRef(yObj);
        if (uObj != nullptr) env->DeleteLocalRef(uObj);
        if (vObj != nullptr) env->DeleteLocalRef(vObj);
    }

    // Single frame degenerate case: copy directly
    if (frameCount == 1) {
        for (int32_t r = 0; r < height; ++r) {
            std::memcpy(outYPtr + r * width, rawFrames[0].y + r * yRowStride, static_cast<size_t>(width));
        }
        const int32_t uvH = height / 2;
        const int32_t uvW = width / 2;
        for (int32_t r = 0; r < uvH; ++r) {
            for (int32_t c = 0; c < uvW; ++c) {
                outUPtr[r * uvW + c] = rawFrames[0].u[r * uvRowStride + c * uvPixelStride];
                outVPtr[r * uvW + c] = rawFrames[0].v[r * uvRowStride + c * uvPixelStride];
            }
        }
        jfloatArray result = env->NewFloatArray(4);
        const jfloat stats[4] = { 0.0f, 0.0f, 0.0f, 1.0f };
        env->SetFloatArrayRegion(result, 0, 4, stats);
        return result;
    }

    // Extract gyro integrated rotations
    jfloat* gyroData = nullptr;
    jsize gyroLen = 0;
    if (gyroRotations != nullptr) {
        gyroData = env->GetFloatArrayElements(gyroRotations, nullptr);
        gyroLen = env->GetArrayLength(gyroRotations);
    }

    const auto alignStart = std::chrono::steady_clock::now();

    // Frame 0 is reference frame
    lumacore::LumaPyramid refPyramid;
    refPyramid.build(rawFrames[0].y, width, height, yRowStride);

    const float focalLengthPx = static_cast<float>(std::max(width, height)) * 1.15f;
    const float cx = static_cast<float>(width) * 0.5f;
    const float cy = static_cast<float>(height) * 0.5f;

    std::vector<lumacore::TargetFrameAlignment> targetAlignments;
    targetAlignments.reserve(static_cast<size_t>(frameCount - 1));

    for (jsize k = 1; k < frameCount; ++k) {
        lumacore::LumaPyramid targetPyramid;
        targetPyramid.build(rawFrames[k].y, width, height, yRowStride);

        float rotX = 0.0f;
        float rotY = 0.0f;
        float rotZ = 0.0f;
        const jsize gyroIdx = (k - 1) * 3;
        if (gyroData != nullptr && gyroIdx + 2 < gyroLen) {
            rotX = gyroData[gyroIdx + 0];
            rotY = gyroData[gyroIdx + 1];
            rotZ = gyroData[gyroIdx + 2];
        }

        const lumacore::Homography3x3 gyroH = lumacore::computeGyroHomography(
            rotX, rotY, rotZ, focalLengthPx, cx, cy
        );

        const lumacore::Homography3x3 refinedH = lumacore::alignPyramidsCoarseToFine(
            refPyramid, targetPyramid, gyroH
        );

        targetAlignments.push_back(lumacore::TargetFrameAlignment{
            .planes = {
                .yData = rawFrames[k].y,
                .uData = rawFrames[k].u,
                .vData = rawFrames[k].v,
                .yRowStride = yRowStride,
                .uvRowStride = uvRowStride,
                .uvPixelStride = uvPixelStride
            },
            .homography = refinedH
        });
    }

    const auto alignEnd = std::chrono::steady_clock::now();
    const float alignmentMs = std::chrono::duration<float, std::milli>(alignEnd - alignStart).count();

    if (gyroRotations != nullptr && gyroData != nullptr) {
        env->ReleaseFloatArrayElements(gyroRotations, gyroData, JNI_ABORT);
    }

    const lumacore::FramePlaneView refView{
        .yData = rawFrames[0].y,
        .uData = rawFrames[0].u,
        .vData = rawFrames[0].v,
        .yRowStride = yRowStride,
        .uvRowStride = uvRowStride,
        .uvPixelStride = uvPixelStride
    };

    lumacore::MergeStats mergeStats = lumacore::mergeBurstFrames(
        refView,
        targetAlignments,
        width,
        height,
        outYPtr,
        outUPtr,
        outVPtr,
        width,
        width / 2,
        1
    );

    mergeStats.alignmentMs = alignmentMs;

    LOGI("Burst fusion complete: %d frames, align: %.2f ms, merge: %.2f ms, ghost rejection: %.1f %%",
         mergeStats.mergedFrameCount,
         mergeStats.alignmentMs,
         mergeStats.mergeMs,
         mergeStats.ghostRejectionRatio * 100.0f);

    jfloatArray result = env->NewFloatArray(4);
    if (result != nullptr) {
        const jfloat returnStats[4] = {
            mergeStats.alignmentMs,
            mergeStats.mergeMs,
            mergeStats.ghostRejectionRatio,
            static_cast<jfloat>(mergeStats.mergedFrameCount)
        };
        env->SetFloatArrayRegion(result, 0, 4, returnStats);
    }
    return result;
}

} // extern "C"
